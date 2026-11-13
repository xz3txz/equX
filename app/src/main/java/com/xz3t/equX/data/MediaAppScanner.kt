package com.xz3t.equX.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.xz3t.equX.service.MediaNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaAppScanner(private val context: Context) {

    companion object {
        /** Срок жизни кэша списка приложений. */
        private const val CACHE_TTL_MS = 60_000L

        @Volatile
        private var cachedApps: List<AppBinding>? = null

        @Volatile
        private var cachedPackageNames: Set<String> = emptySet()

        @Volatile
        private var lastScanTimeMs: Long = 0L

        /** Принудительный сброс кэша (например, после установки/удаления приложения). */
        fun invalidateCache() {
            cachedApps = null
            cachedPackageNames = emptySet()
            lastScanTimeMs = 0L
        }

        /** Пакеты медиаплееров из последнего динамического сканирования (без хардкода). */
        fun getCachedPackageNames(): Set<String> = cachedPackageNames
    }

    /**
     * Асинхронное сканирование установленных медиа-приложений с кэшированием.
     * Выполняется на IO-диспетчере, чтобы не блокировать UI.
     */
    suspend fun scanInstalledMediaApps(): List<AppBinding> =
        withContext(Dispatchers.IO) { scanBlocking() }

    private fun scanBlocking(): List<AppBinding> {
        val now = System.currentTimeMillis()
        cachedApps?.let { cached ->
            if (now - lastScanTimeMs < CACHE_TTL_MS) return cached
        }

        val pm = context.packageManager
        val seen = HashSet<String>()
        val result = mutableListOf<AppBinding>()

        // Единая точка добавления с дедупликацией по packageName.
        fun add(pkg: String, label: String) {
            val cleanPkg = pkg.trim()
            if (cleanPkg.isBlank() || !seen.add(cleanPkg)) return
            result.add(AppBinding(cleanPkg, label.trim().ifBlank { cleanPkg }))
        }

        // 1. Приложения, объявившие себя музыкальными (CATEGORY_APP_MUSIC).
        try {
            val musicIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
            pm.queryIntentActivities(musicIntent, 0).forEach { addResolveInfo(it, pm, ::add) }
        } catch (_: Exception) {
            // Не роняем всё сканирование из-за одного сбойного источника.
        }

        // 2. Сервисы MediaBrowserService.
        try {
            val mediaIntent = Intent("android.media.browse.MediaBrowserService")
            pm.queryIntentServices(mediaIntent, 0).forEach { addResolveInfo(it, pm, ::add) }
        } catch (_: Exception) {
        }

        // 3. Приёмники MEDIA_BUTTON — динамический признак медиа-плеера.
        try {
            val buttonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            pm.queryBroadcastReceivers(buttonIntent, 0).forEach { addResolveInfo(it, pm, ::add) }
        } catch (_: Exception) {
        }

        // 4. Активные MediaSession — ловим запущенные плееры, которых нет в других источниках.
        try {
            for (session in MediaNotificationService.getActiveMediaSessions(context)) {
                val pkg = session.packageName
                if (pkg.isBlank()) continue
                addPackageLabel(pkg, pm, ::add)
            }
        } catch (_: Exception) {
        }

        val sorted = result.sortedBy { it.appName }
        cachedApps = sorted
        cachedPackageNames = sorted.mapTo(HashSet()) { it.packageName }
        lastScanTimeMs = now
        return sorted
    }

    private fun addResolveInfo(
        info: ResolveInfo,
        pm: PackageManager,
        add: (String, String) -> Unit,
    ) {
        try {
            // Для broadcast-приёмников (MEDIA_BUTTON) packageName доступен через activityInfo.
            val pkg = info.activityInfo?.packageName ?: info.serviceInfo?.packageName ?: return
            add(pkg, info.loadLabel(pm).toString())
        } catch (_: Exception) {
            // Пропускаем некорректную запись, не прерывая общий цикл.
        }
    }

    private fun addPackageLabel(
        pkg: String,
        pm: PackageManager,
        add: (String, String) -> Unit,
    ) {
        try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            add(pkg, pm.getApplicationLabel(appInfo).toString())
        } catch (_: Exception) {
            // Fallback: используем packageName как отображаемое имя.
            add(pkg, pkg)
        }
    }
}
