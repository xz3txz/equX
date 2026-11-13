package com.xz3t.equX.receiver

import com.xz3t.equX.data.EquXLog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.xz3t.equX.service.EqualizerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        EquXLog.d("equX_BootReceiver", "Получен системный триггер автозагрузки/зажигания: $action")

        val serviceIntent = Intent(context, EqualizerService::class.java)

        // Полная поддержка всех вариантов триггеров телефонов и автомагнитол (Android Automotive, FYT, Topway, QuickBoot ACC)
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_PRESENT ||
            action == Intent.ACTION_SCREEN_ON ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == "com.syu.ms.action.ACC_ON") {

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                EquXLog.d("equX_BootReceiver", "Фоновая служба equX успешно запущена по триггеру $action")
            } catch (e: Exception) {
                Log.e("equX_BootReceiver", "Критическая ошибка запуска службы на старте: ${e.message}")
            }
        }
    }
}
