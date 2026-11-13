package com.xz3t.equX.service

import android.car.media.CarAudioManager

/**
 * Подписка на изменения OEM custom setting'ов CarAudioManager (ECARX): штатное меню
 * «Звук» меняет их, и car_service рассылает колбэк.
 *
 * Суперкласс есть только в прошивке (в APK — compile-only stub из :carstubs), поэтому
 * экземпляр создаётся лишь там, где Car API уже доступен ([CarVolumeController]).
 * Вызывается с потока car_service — в [listener] только лёгкая работа.
 */
class HuCustomSettingCallback(
    private val listener: (zoneId: Int, param: Int, event: String) -> Unit,
) : CarAudioManager.CarCustomSettingCallback() {

    override fun onEnableChanged(zoneId: Int, param: Int, enable: Boolean) {
        listener(zoneId, param, "enable=$enable")
    }

    override fun onValueChanged(zoneId: Int, param: Int, value: Int) {
        listener(zoneId, param, "value=$value")
    }
}
