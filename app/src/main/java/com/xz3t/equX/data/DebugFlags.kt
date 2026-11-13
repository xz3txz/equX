package com.xz3t.equX.data

import android.util.Log
import com.xz3t.equX.BuildConfig

/**
 * Глобальные отладочные флаги.
 *
 * [carModeForced] — скрытое меню (три быстрых нажатия на карточку аудиоустройства),
 * не сохраняются: после перезапуска приложения выключены.
 */
object DebugFlags {
    /** Вкладка Car на устройстве, которое не определилось как ГУ. */
    @Volatile
    var carModeForced: Boolean = false

    @Volatile
    var mockSpeedEnabled: Boolean = false

    @Volatile
    var mockSpeedValue: Float = 0f
}

/**
 * Логирование equX.
 * - В файл ([LogFileWriter], журнал с ротацией) — все уровни, всегда.
 * - В logcat: info/debug — только в debug-сборке; предупреждения и ошибки — всегда.
 */
object EquXLog {
    private val verbose: Boolean get() = BuildConfig.DEBUG

    fun i(tag: String, message: String) {
        LogFileWriter.log("I", tag, message)
        if (verbose) Log.i(tag, message)
    }

    fun d(tag: String, message: String) {
        LogFileWriter.log("D", tag, message)
        if (verbose) Log.d(tag, message)
    }

    fun w(tag: String, message: String) {
        LogFileWriter.log("W", tag, message)
        Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        LogFileWriter.log("E", tag, message, throwable)
        Log.e(tag, message, throwable)
    }
}
