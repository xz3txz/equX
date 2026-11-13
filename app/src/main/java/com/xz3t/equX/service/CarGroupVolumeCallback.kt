package com.xz3t.equX.service

import android.car.media.CarAudioManager

/**
 * Подписка на смену громкости групп CarAudioManager — нужна тонкомпенсации (без опроса
 * `getGroupVolume`, на который OEM car-lib пишет Log.e).
 *
 * Суперкласс есть только в прошивке (в APK — compile-only stub из :carstubs), поэтому
 * экземпляр создаётся лишь там, где Car API уже доступен ([CarVolumeController]).
 * Вызывается с потока car_service — в [listener] только лёгкая работа.
 */
class CarGroupVolumeCallback(
    private val listener: (zoneId: Int, groupId: Int) -> Unit,
) : CarAudioManager.CarVolumeCallback() {

    override fun onGroupVolumeChanged(zoneId: Int, groupId: Int, flags: Int) {
        listener(zoneId, groupId)
    }

    override fun onMasterMuteChanged(zoneId: Int, flags: Int) {
        listener(zoneId, -1)
    }
}
