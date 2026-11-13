package com.xz3t.equX.service

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.xz3t.equX.data.EquXLog
import com.xz3t.equX.data.VolumeMethod
import com.xz3t.equX.data.VolumeMethodStatus
import java.lang.reflect.Method

/**
 * Реально управляет системной громкостью через выбранный метод.
 *
 * В отличие от [CarVolumeMethodProbe] (который только проверяет, работает ли метод),
 * этот объект используется для фактической установки громкости при скоростной
 * компенсации (GALA).
 *
 * Метод 2 — «Android API»: `android.media.AudioManager` (STREAM_MUSIC).
 * Метод 3 — «Android Car API»: `android.car.media.CarAudioManager` (группа media = 0).
 */
object CarVolumeController {

    private const val TAG = "equX_VolumeCtrl"
    private const val PRIMARY_ZONE = 0
    private const val MEDIA_GROUP = 0
    // CarAudioManager.PARAM_VOLUME_ADJUST_BY_SPEED (OEM-расширение ECARX): штатная регулировка
    // громкости по скорости в ГУ. Вкл/выкл — отдельный флаг getCustomSettingEnable,
    // в value при выключении остаётся последний уровень (1..3).
    const val PARAM_VOLUME_ADJUST_BY_SPEED = 6
    // Переключатель штатного меню «Звук» (adaptapi CompensationImp): его читает
    // isSpeedCompensatedVolumeEnabled(). Отсутствие ключа система считает за 1.
    private const val SETTING_SPEED_LEVEL_SWITCH = "speed_level_switch_enable"

    // Кэш Car-подключения, чтобы не дёргать createCar()/disconnect() на каждый шаг громкости.
    @Volatile
    private var cachedCar: Any? = null
    @Volatile
    private var cachedCarClass: Class<*>? = null
    @Volatile
    private var cachedCarAudio: Any? = null
    @Volatile
    private var cachedGetVolume: Method? = null
    @Volatile
    private var cachedSetVolume: Method? = null
    @Volatile
    private var cachedGetMax: Method? = null
    @Volatile
    private var cachedIsMute: Method? = null
    @Volatile
    private var cachedGetCustomSetting: Method? = null
    @Volatile
    private var cachedGetCustomEnable: Method? = null
    // Колбэк custom setting'ов живёт вместе с CarAudioManager: сбрасывается с кэшем Car.
    @Volatile
    private var huSettingCallback: Any? = null
    @Volatile
    private var huSettingCallbackErrorLogged = false
    @Volatile
    private var volumeCallback: Any? = null
    @Volatile
    private var volumeCallbackErrorLogged = false

    /**
     * Штатная регулировка громкости по скорости ГУ: [enabled] — флаг вкл/выкл
     * (null — флаг не читается), [value] — уровень 1..3 (LOW/MIDDLE/HIGH),
     * [menuSwitch] — `Settings.System` переключатель меню (null — ключа нет / не читается).
     */
    data class HuSpeedVolume(val enabled: Boolean?, val value: Int, val menuSwitch: Int?) {
        /** Уровень штатной регулировки: 0 — выкл, null — неизвестно. */
        val level: Int?
            get() = when (enabled) {
                true -> value
                false -> 0
                null -> null
            }
    }

    /**
     * Превращает выбранный метод в конкретный метод для применения.
     *
     * AUTO выбирает первый рабочий метод (по порядку 2 → 3); конкретный метод
     * возвращается только если он протестирован как [VolumeMethodStatus.WORKS].
     * Возвращает null, если рабочего метода нет — тогда GALA не действует (DSP-фолбэка нет).
     */
    fun resolve(
        method: VolumeMethod,
        method2Status: VolumeMethodStatus,
        method3Status: VolumeMethodStatus,
    ): VolumeMethod? {
        return when (method) {
            VolumeMethod.METHOD_2 -> if (method2Status == VolumeMethodStatus.WORKS) VolumeMethod.METHOD_2 else null
            VolumeMethod.METHOD_3 -> if (method3Status == VolumeMethodStatus.WORKS) VolumeMethod.METHOD_3 else null
            VolumeMethod.AUTO -> when {
                method2Status == VolumeMethodStatus.WORKS -> VolumeMethod.METHOD_2
                method3Status == VolumeMethodStatus.WORKS -> VolumeMethod.METHOD_3
                else -> null
            }
        }
    }

    fun getVolume(context: Context, method: VolumeMethod): Int? = when (method) {
        VolumeMethod.METHOD_2 -> getAudioManagerVolume(context)
        VolumeMethod.METHOD_3 -> getCarAudioVolume(context)
        VolumeMethod.AUTO -> null
    }

    fun getMaxVolume(context: Context, method: VolumeMethod): Int? = when (method) {
        VolumeMethod.METHOD_2 -> getAudioManagerMaxVolume(context)
        VolumeMethod.METHOD_3 -> getCarAudioMaxVolume(context)
        VolumeMethod.AUTO -> null
    }

    /**
     * Замьючена ли группа media. Важно для GALA: на части ГУ
     * `CarAudioService.setGroupVolume(index != 0)` сам снимает mute с группы,
     * а `setGroupVolume(0)` — включает mute. Поэтому при mute громкость писать нельзя.
     * null — состояние не удалось прочитать.
     */
    fun isMuted(context: Context, method: VolumeMethod): Boolean? = when (method) {
        VolumeMethod.METHOD_2 -> audioManager(context)?.isStreamMute(AudioManager.STREAM_MUSIC)
        VolumeMethod.METHOD_3 -> isCarAudioMuted(context)
        VolumeMethod.AUTO -> null
    }

    /**
     * Штатная регулировка громкости по скорости ГУ (OEM-расширение ECARX): флаг
     * `getCustomSettingEnable` + уровень `getCustomSettingValue` по
     * PARAM_VOLUME_ADJUST_BY_SPEED. Вместе с GALA даёт двойную прибавку громкости.
     * null — параметра нет.
     */
    fun getHuSpeedVolume(context: Context): HuSpeedVolume? {
        val value = try {
            ensureCarAudio(context) ?: return null
            val getter = cachedGetCustomSetting ?: return null
            getter.invoke(cachedCarAudio, PRIMARY_ZONE, PARAM_VOLUME_ADJUST_BY_SPEED) as? Int ?: return null
        } catch (e: Throwable) {
            EquXLog.w(TAG, "getCustomSettingValue failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val enabled = try {
            val getter = cachedGetCustomEnable
            when (getter?.parameterTypes?.size) {
                2 -> getter.invoke(cachedCarAudio, PRIMARY_ZONE, PARAM_VOLUME_ADJUST_BY_SPEED) as? Boolean
                1 -> getter.invoke(cachedCarAudio, PARAM_VOLUME_ADJUST_BY_SPEED) as? Boolean
                else -> null
            }
        } catch (e: Throwable) {
            EquXLog.w(TAG, "getCustomSettingEnable failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val menuSwitch = try {
            Settings.System.getString(context.contentResolver, SETTING_SPEED_LEVEL_SWITCH)?.toIntOrNull()
        } catch (e: Throwable) {
            null
        }
        return HuSpeedVolume(enabled, value, menuSwitch)
    }

    /**
     * Подписка на изменения custom setting'ов ГУ ([listener] получает zoneId, param и событие).
     * Повторный вызов при живой подписке ничего не делает. false — Car API нет или прошивка
     * без `registerCarCustomSettingCallback` (ошибка пишется в лог один раз).
     */
    fun registerHuSettingCallback(
        context: Context,
        listener: (zoneId: Int, param: Int, event: String) -> Unit,
    ): Boolean {
        if (huSettingCallback != null) return true
        return try {
            val mgr = ensureCarAudio(context) ?: return false
            val callbackClass = Class.forName("android.car.media.CarAudioManager\$CarCustomSettingCallback")
            val callback = HuCustomSettingCallback(listener)
            mgr.javaClass.getMethod("registerCarCustomSettingCallback", callbackClass).invoke(mgr, callback)
            huSettingCallback = callback
            EquXLog.i(TAG, "registerCarCustomSettingCallback: OK")
            true
        } catch (e: Throwable) {
            if (!huSettingCallbackErrorLogged) {
                huSettingCallbackErrorLogged = true
                EquXLog.w(TAG, "registerCarCustomSettingCallback failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            false
        }
    }

    /**
     * Подписка на смену громкости групп CarAudioManager ([listener] получает zoneId и groupId).
     * Повторный вызов при живой подписке ничего не делает. false — Car API нет.
     */
    fun registerVolumeCallback(context: Context, listener: (zoneId: Int, groupId: Int) -> Unit): Boolean {
        if (volumeCallback != null) return true
        return try {
            val mgr = ensureCarAudio(context) ?: return false
            val callbackClass = Class.forName("android.car.media.CarAudioManager\$CarVolumeCallback")
            val callback = CarGroupVolumeCallback(listener)
            mgr.javaClass.getMethod("registerCarVolumeCallback", callbackClass).invoke(mgr, callback)
            volumeCallback = callback
            EquXLog.i(TAG, "registerCarVolumeCallback: OK")
            true
        } catch (e: Throwable) {
            if (!volumeCallbackErrorLogged) {
                volumeCallbackErrorLogged = true
                EquXLog.w(TAG, "registerCarVolumeCallback failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            false
        }
    }

    private fun unregisterVolumeCallback() {
        val callback = volumeCallback ?: return
        volumeCallback = null
        val mgr = cachedCarAudio ?: return
        runCatching {
            val callbackClass = Class.forName("android.car.media.CarAudioManager\$CarVolumeCallback")
            mgr.javaClass.getMethod("unregisterCarVolumeCallback", callbackClass).invoke(mgr, callback)
        }
    }

    private fun unregisterHuSettingCallback() {
        val callback = huSettingCallback ?: return
        huSettingCallback = null
        val mgr = cachedCarAudio ?: return
        runCatching {
            val callbackClass = Class.forName("android.car.media.CarAudioManager\$CarCustomSettingCallback")
            mgr.javaClass.getMethod("unregisterCarCustomSettingCallback", callbackClass).invoke(mgr, callback)
        }
    }

    fun setVolume(context: Context, method: VolumeMethod, index: Int) {
        when (method) {
            VolumeMethod.METHOD_2 -> setAudioManagerVolume(context, index)
            VolumeMethod.METHOD_3 -> setCarAudioVolume(context, index)
            VolumeMethod.AUTO -> Unit
        }
    }

    fun release() {
        unregisterHuSettingCallback()
        unregisterVolumeCallback()
        val car = cachedCar
        val carClass = cachedCarClass
        cachedCar = null
        cachedCarClass = null
        cachedCarAudio = null
        cachedGetVolume = null
        cachedSetVolume = null
        cachedGetMax = null
        cachedIsMute = null
        cachedGetCustomSetting = null
        cachedGetCustomEnable = null
        if (car != null && carClass != null) {
            runCatching { carClass.getMethod("disconnect").invoke(car) }
        }
    }

    // --- Метод 2: Android API (AudioManager) ---

    private fun audioManager(context: Context): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun getAudioManagerVolume(context: Context): Int? =
        audioManager(context)?.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun getAudioManagerMaxVolume(context: Context): Int? =
        audioManager(context)?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private fun setAudioManagerVolume(context: Context, index: Int) {
        audioManager(context)?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    // --- Метод 3: Android Car API (CarAudioManager) ---

    private fun ensureCarAudio(context: Context): Any? {
        cachedCarAudio?.let { return it }

        return try {
            val carClass = Class.forName("android.car.Car")
            val car = carClass.getMethod("createCar", Context::class.java).invoke(null, context) ?: return null

            // На части OEM-сборок getCarManager работает и без явного connect(),
            // но вызываем его для корректности (не фатально, если вернётся быстро).
            runCatching { carClass.getMethod("connect").invoke(car) }

            val mgr = carClass.getMethod("getCarManager", String::class.java).invoke(car, "audio") ?: return null

            cachedCar = car
            cachedCarClass = carClass
            cachedCarAudio = mgr
            cachedGetVolume = mgr.javaClass.getMethod(
                "getGroupVolume",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            cachedSetVolume = mgr.javaClass.getMethod(
                "setGroupVolume",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            cachedGetMax = mgr.javaClass.getMethod(
                "getGroupMaxVolume",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            // isGroupMute(zone, group) есть не во всех сборках car-lib — не фатально.
            cachedIsMute = runCatching {
                mgr.javaClass.getMethod(
                    "isGroupMute",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            // OEM-расширение ECARX; в стоковом car-lib его нет.
            cachedGetCustomSetting = runCatching {
                mgr.javaClass.getMethod(
                    "getCustomSettingValue",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            // Флаг вкл/выкл той же настройки: сигнатура (zone, param) или (param) — пробуем обе.
            cachedGetCustomEnable = runCatching {
                mgr.javaClass.getMethod(
                    "getCustomSettingEnable",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull() ?: runCatching {
                mgr.javaClass.getMethod("getCustomSettingEnable", Int::class.javaPrimitiveType)
            }.getOrNull()
            mgr
        } catch (e: Throwable) {
            EquXLog.w(TAG, "CarAudioManager init failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun getCarAudioVolume(context: Context): Int? {
        return try {
            ensureCarAudio(context) ?: return null
            cachedGetVolume?.invoke(cachedCarAudio, PRIMARY_ZONE, MEDIA_GROUP) as? Int
        } catch (e: Throwable) {
            invalidateCarCache()
            EquXLog.w(TAG, "getGroupVolume failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun getCarAudioMaxVolume(context: Context): Int? {
        return try {
            ensureCarAudio(context) ?: return null
            cachedGetMax?.invoke(cachedCarAudio, PRIMARY_ZONE, MEDIA_GROUP) as? Int
        } catch (e: Throwable) {
            invalidateCarCache()
            EquXLog.w(TAG, "getGroupMaxVolume failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun isCarAudioMuted(context: Context): Boolean? {
        return try {
            ensureCarAudio(context) ?: return null
            cachedIsMute?.invoke(cachedCarAudio, PRIMARY_ZONE, MEDIA_GROUP) as? Boolean
        } catch (e: Throwable) {
            invalidateCarCache()
            EquXLog.w(TAG, "isGroupMute failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun setCarAudioVolume(context: Context, index: Int) {
        try {
            ensureCarAudio(context) ?: return
            cachedSetVolume?.invoke(cachedCarAudio, PRIMARY_ZONE, MEDIA_GROUP, index, 0)
        } catch (e: Throwable) {
            invalidateCarCache()
            EquXLog.w(TAG, "setGroupVolume failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun invalidateCarCache() {
        // Car переподключается — старая подписка умрёт вместе с ним; сервис оформит новую.
        huSettingCallback = null
        volumeCallback = null
        val car = cachedCar
        val carClass = cachedCarClass
        cachedCar = null
        cachedCarClass = null
        cachedCarAudio = null
        cachedGetVolume = null
        cachedSetVolume = null
        cachedGetMax = null
        cachedIsMute = null
        cachedGetCustomSetting = null
        cachedGetCustomEnable = null
        if (car != null && carClass != null) {
            runCatching { carClass.getMethod("disconnect").invoke(car) }
        }
    }
}
