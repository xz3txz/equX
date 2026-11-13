package com.xz3t.equX.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Журнал equX в приватном хранилище: `filesDir/logs/equx.log`. Пишется всегда (без переключателей).
 *
 * - Запись — в одном фоновом потоке, по порядку; вызывающий поток (в т.ч. главный) только ставит строку в очередь.
 * - Ротация как logrotate: при [MAX_FILE_BYTES] `equx.log` → `equx.log.1` … `equx.log.N`, старше N — удаляются;
 *   ротированные файлы старше [MAX_AGE_MS] удаляются при старте и при ротации. На диске не больше ~(N+1) × [MAX_FILE_BYTES].
 * - Отчёт ([exportToDownload] / [buildShareZip]): zip с заголовком, журналом и дампом logcat своего процесса.
 *   После успешной выгрузки журнал очищается (truncate): в следующий отчёт попадёт только новое.
 */
object LogFileWriter {

    private const val LOG_DIR = "logs"
    private const val FILE_NAME = "equx.log"
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    private const val MAX_ROTATED_FILES = 5
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val LOGCAT_LINES = 3000

    @Volatile
    private var appContext: Context? = null

    // Всё, что ниже, трогается только из потока записи.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "equX-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private var out: OutputStream? = null
    private var currentBytes = 0L
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Инициализируется из MainActivity и EqualizerService (идемпотентно). */
    fun init(context: Context) {
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
        executor.execute { logDir()?.let(::pruneOldFiles) }
        // Заголовок с версией — видно, какая сборка писала журнал.
        log("I", "equX", "=== equX ${appVersion()} started (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}) ===")
    }

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (appContext == null) return
        val timeMs = System.currentTimeMillis()
        val thread = Thread.currentThread().name
        executor.execute { writeLine(timeMs, level, tag, thread, message, throwable) }
    }

    // ------------------------------------------------------------------ запись

    private fun logDir(): File? = appContext?.let { File(it.filesDir, LOG_DIR) }

    private fun writeLine(timeMs: Long, level: String, tag: String, thread: String, message: String, throwable: Throwable?) {
        try {
            val stream = out ?: openStream() ?: return
            val line = StringBuilder()
                .append(timeFormat.format(Date(timeMs)))
                .append(' ').append(level).append('/').append(tag)
                .append(" [").append(thread).append("]: ").append(message)
            if (throwable != null) line.append('\n').append(Log.getStackTraceString(throwable))
            line.append('\n')
            val bytes = line.toString().toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            currentBytes += bytes.size
            // Сброс каждой строки: при падении процесса журнал не теряет последние записи.
            stream.flush()
            if (currentBytes >= MAX_FILE_BYTES) rotate()
        } catch (_: Exception) {
            // Не роняем приложение из-за проблем записи журнала.
            closeStream()
        }
    }

    private fun openStream(): OutputStream? {
        val dir = logDir() ?: return null
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        currentBytes = file.length()
        return BufferedOutputStream(FileOutputStream(file, true), 16 * 1024).also { out = it }
    }

    /** equx.log → .1, .1 → .2 … последний удаляется; затем чистка по возрасту. */
    private fun rotate() {
        closeStream()
        val dir = logDir() ?: return
        File(dir, "$FILE_NAME.$MAX_ROTATED_FILES").delete()
        for (i in MAX_ROTATED_FILES - 1 downTo 1) {
            val f = File(dir, "$FILE_NAME.$i")
            if (f.exists()) f.renameTo(File(dir, "$FILE_NAME.${i + 1}"))
        }
        File(dir, FILE_NAME).renameTo(File(dir, "$FILE_NAME.1"))
        currentBytes = 0L
        pruneOldFiles(dir)
    }

    private fun pruneOldFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != FILE_NAME && f.lastModified() < cutoff) f.delete()
        }
    }

    private fun closeStream() {
        try {
            out?.close()
        } catch (_: Exception) {
        }
        out = null
    }

    /** Файлы журнала от старых к новым: equx.log.N … equx.log.1, equx.log. */
    private fun logFiles(): List<File> {
        val dir = logDir() ?: return emptyList()
        return ((MAX_ROTATED_FILES downTo 1).map { File(dir, "$FILE_NAME.$it") } + File(dir, FILE_NAME))
            .filter { it.isFile }
    }

    /** Очистка журнала после выгрузки: ротированные файлы удаляются, текущий обрезается до нуля. */
    private fun truncate() {
        runOnWriter {
            closeStream()
            val dir = logDir() ?: return@runOnWriter
            (1..MAX_ROTATED_FILES).forEach { File(dir, "$FILE_NAME.$it").delete() }
            File(dir, FILE_NAME).takeIf { it.exists() }?.let { FileOutputStream(it).close() }
            currentBytes = 0L
        }
        log("I", "equX", "=== журнал выгружен и очищен (equX ${appVersion()}) ===")
    }

    /** Выполняет [block] в потоке записи и ждёт его (не дольше 2 с). */
    private fun runOnWriter(block: () -> Unit) {
        try {
            executor.submit(block).get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ отчёт

    private fun appVersion(): String {
        val ctx = appContext ?: return "unknown"
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (code ${pi.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Пишет zip отчёта в [target]: `report.txt` (заголовок + logcat) и `logs/` (файлы журнала). */
    private fun writeReportZip(ctx: Context, target: OutputStream) {
        runOnWriter { out?.flush() }
        ZipOutputStream(BufferedOutputStream(target)).use { zos ->
            zos.putNextEntry(ZipEntry("report.txt"))
            fun text(s: String) = zos.write(s.toByteArray(Charsets.UTF_8))
            text("equX error report\n")
            text("==============================\n")
            text("App: ${ctx.packageName} ${appVersion()}\n")
            text("Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
            text("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}\n\n")
            text("=== logcat ===\n")
            dumpLogcat(ctx, zos)
            zos.closeEntry()

            val files = logFiles()
            if (files.isEmpty()) {
                zos.putNextEntry(ZipEntry("logs/empty.txt"))
                text("(no app log)\n")
                zos.closeEntry()
            }
            for (f in files) {
                zos.putNextEntry(ZipEntry("logs/${f.name}"))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    /**
     * Отчёт одним zip в Download (MediaStore, без разрешений на хранилище), затем очистка журнала.
     * Блокирующий — не с главного потока. @return имя файла или null.
     */
    fun exportToDownload(): String? {
        val ctx = appContext ?: return null
        val fileName = "equx_report_${stamp()}.zip"
        val resolver = ctx.contentResolver
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("openOutputStream == null")
            writeReportZip(ctx, stream)
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            truncate()
            fileName
        } catch (e: Throwable) {
            Log.e("equX_LogFile", "Не удалось сохранить отчёт в Download: ${e.message}", e)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    /**
     * Zip для «Поделиться» в `cacheDir/share` (отдаётся через FileProvider), затем очистка журнала:
     * копия уже в архиве. Блокирующий — не с главного потока. @return файл или null.
     */
    fun buildShareZip(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "equx_report_${stamp()}.zip")
        return try {
            FileOutputStream(file).use { writeReportZip(ctx, it) }
            truncate()
            file
        } catch (e: Throwable) {
            Log.e("equX_LogFile", "Не удалось собрать отчёт: ${e.message}", e)
            file.delete()
            null
        }
    }

    /**
     * Дамп logcat прямо в поток отчёта. Обычное приложение видит в logcat только свои строки
     * (в том числе от прошлых запусков процесса). Если READ_LOGS выдан (системная сборка ГУ),
     * видно всё — тогда ограничиваемся своим процессом.
     */
    private fun dumpLogcat(context: Context, target: OutputStream) {
        try {
            val cmd = mutableListOf("logcat", "-d", "-t", LOGCAT_LINES.toString())
            if (context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
                cmd += "--pid=${Process.myPid()}"
            }
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process.inputStream.use { it.copyTo(target) }
            process.waitFor()
        } catch (e: Exception) {
            target.write("logcat unavailable: ${e.message}\n".toByteArray(Charsets.UTF_8))
        }
    }
}
