package com.xz3t.equX.receiver

import com.xz3t.equX.data.EquXLog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.xz3t.equX.service.EqualizerService

/**
 * OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION от плееров. Учёт аудиосессий не ведём (движок один на
 * session 0, «кто играет» определяет MediaSession) — событие лишь запускает службу, если её убили,
 * и даёт ей сразу пересчитать пресет.
 */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return
        EquXLog.i("equX_SessionReceiver", "Системный интент сессии: ${intent.action}")
        val serviceIntent = Intent(context, EqualizerService::class.java).setAction(intent.action)
        try {
            // startForegroundService: после перезагрузки процесс может быть не запущен, а обычный
            // startService из фонового ресивера на Android 8+ блокируется.
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e("equX_SessionReceiver", "Ошибка запуска EqualizerService: ${e.message}")
        }
    }
}
