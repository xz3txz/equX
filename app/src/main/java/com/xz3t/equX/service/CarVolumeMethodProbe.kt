package com.xz3t.equX.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.xz3t.equX.data.EquXLog
import com.xz3t.equX.data.VolumeMethod
import com.xz3t.equX.data.VolumeMethodStatus

/**
 * Проверяет на реальном устройстве, какой способ регулировки громкости работает.
 *
 * Проверка реально меняет громкость на один шаг (вверх, если есть запас до максимума,
 * иначе вниз), считывает её обратно и восстанавливает исходное значение. Метод
 * считается рабочим только если после записи громкость действительно изменилась.
 *
 * Этапы пишутся в [EquXLog] (info — только в debug-сборке).
 *
 * Метод 2 — «Android API»: системный `android.media.AudioManager` (STREAM_MUSIC).
 * Метод 3 — «Android Car API»: Android Automotive `android.car.media.CarAudioManager`.
 */
object CarVolumeMethodProbe {

    private const val TAG = "equX_VolumeProbe"
    private const val PRIMARY_ZONE = 0
    private const val MEDIA_GROUP = 0
    private const val READBACK_DELAY_MS = 150L

    data class Result(
        val method2: VolumeMethodStatus,
        val method3: VolumeMethodStatus,
        /** Проба не проводилась: звук замьючен (запись громкости сняла бы mute). */
        val deferredByMute: Boolean = false,
    )

    fun run(context: Context): Result {
        // На части ГУ setGroupVolume(index != 0) снимает mute с группы — проба
        // на шаг вверх/вниз размьютила бы звук. Откладываем до снятия mute.
        val muted = CarVolumeController.isMuted(context, VolumeMethod.METHOD_3) == true ||
            CarVolumeController.isMuted(context, VolumeMethod.METHOD_2) == true
        if (muted) {
            EquXLog.i(TAG, "проба методов громкости отложена: звук замьючен")
            return Result(
                method2 = VolumeMethodStatus.UNTESTED,
                method3 = VolumeMethodStatus.UNTESTED,
                deferredByMute = true,
            )
        }
        EquXLog.i(TAG, "=== старт пробы методов регулировки громкости ===")
        val m2 = probeMethod2(context)
        val m3 = probeMethod3(context)
        EquXLog.i(TAG, "=== итог пробы: method2=$m2, method3=$m3 ===")
        return Result(method2 = m2, method3 = m3)
    }

    private fun probeMethod2(context: Context): VolumeMethodStatus {
        EquXLog.i(TAG, "Метод 2 (Android API / AudioManager): начало")
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return fail2("getSystemService(AUDIO_SERVICE) вернул null")

            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = targetVolume(current, max)
                ?: return fail2("некуда шагать: current=$current, max=$max")

            EquXLog.i(TAG, "Метод 2 (Android API): current=$current, max=$max, target=$target")

            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                Thread.sleep(READBACK_DELAY_MS)
                val readback = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val status = if (readback == current) {
                    fail2("громкость не изменилась: readback=$readback, current=$current")
                } else {
                    EquXLog.i(TAG, "Метод 2 (Android API): OK — readback=$readback")
                    VolumeMethodStatus.WORKS
                }
                status
            } finally {
                EquXLog.i(TAG, "Метод 2 (Android API): восстановление громкости до $current")
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, current, 0) }
            }
        } catch (e: Throwable) {
            fail2("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probeMethod3(context: Context): VolumeMethodStatus {
        EquXLog.i(TAG, "Метод 3 (Android Car API / CarAudioManager): начало")
        return try {
            val carClass = Class.forName("android.car.Car")
            val createCar = carClass.getMethod("createCar", Context::class.java)
            val car = createCar.invoke(null, context) ?: return fail3("Car.createCar() вернул null")

            try {
                val audioManager = carClass.getMethod("getCarManager", String::class.java)
                    .invoke(car, "audio") ?: return fail3("getCarManager(\"audio\") вернул null")

                val getVolume = audioManager.javaClass.getMethod(
                    "getGroupVolume",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                val setVolume = audioManager.javaClass.getMethod(
                    "setGroupVolume",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                val getMax = audioManager.javaClass.getMethod(
                    "getGroupMaxVolume",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )

                val current = getVolume.invoke(audioManager, PRIMARY_ZONE, MEDIA_GROUP) as? Int
                    ?: return fail3("getGroupVolume вернул не Int")
                val max = getMax.invoke(audioManager, PRIMARY_ZONE, MEDIA_GROUP) as? Int ?: current
                val target = targetVolume(current, max)
                    ?: return fail3("некуда шагать: current=$current, max=$max")

                EquXLog.i(TAG, "Метод 3 (Android Car API): current=$current, max=$max, target=$target")

                try {
                    setVolume.invoke(audioManager, PRIMARY_ZONE, MEDIA_GROUP, target, 0)
                    Thread.sleep(READBACK_DELAY_MS)
                    val readback = getVolume.invoke(audioManager, PRIMARY_ZONE, MEDIA_GROUP) as? Int ?: current
                    val status = if (readback == current) {
                        fail3("громкость не изменилась: readback=$readback, current=$current")
                    } else {
                        EquXLog.i(TAG, "Метод 3 (Android Car API): OK — readback=$readback")
                        VolumeMethodStatus.WORKS
                    }
                    status
                } finally {
                    EquXLog.i(TAG, "Метод 3 (Android Car API): восстановление громкости до $current")
                    runCatching { setVolume.invoke(audioManager, PRIMARY_ZONE, MEDIA_GROUP, current, 0) }
                }
            } finally {
                runCatching { carClass.getMethod("disconnect").invoke(car) }
            }
        } catch (e: Throwable) {
            fail3("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun targetVolume(current: Int, max: Int): Int? {
        return when {
            current < max -> current + 1
            current > 0 -> current - 1
            else -> null
        }
    }

    private fun fail2(reason: String): VolumeMethodStatus {
        EquXLog.w(TAG, "Метод 2 (Android API) не работает: $reason")
        Log.w(TAG, "Метод 2 (Android API) не работает: $reason")
        return VolumeMethodStatus.FAILS
    }

    private fun fail3(reason: String): VolumeMethodStatus {
        EquXLog.w(TAG, "Метод 3 (Android Car API) не работает: $reason")
        Log.w(TAG, "Метод 3 (Android Car API) не работает: $reason")
        return VolumeMethodStatus.FAILS
    }
}
