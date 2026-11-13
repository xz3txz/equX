package com.xz3t.equX.data

/**
 * Способ, которым equX меняет системную громкость при скоростной компенсации (GALA).
 *
 * METHOD_2 — «Android API»: системный `android.media.AudioManager` (STREAM_MUSIC).
 * METHOD_3 — «Android Car API»: Android Automotive `android.car.media.CarAudioManager`
 *            (setGroupVolume / setGroupMute) — только на Automotive-образе.
 * AUTO     — использовать рабочий метод автоматически (по результатам проверки).
 */
enum class VolumeMethod {
    AUTO,
    METHOD_2,
    METHOD_3,
}

/**
 * Результат проверки метода регулировки громкости на реальном устройстве.
 *
 * Отображается маленьким индикатором:
 * UNTESTED — серый (ещё не проверяли),
 * WORKS    — зелёный (протестировано, работает),
 * FAILS    — красный (протестировано, не работает).
 */
enum class VolumeMethodStatus {
    UNTESTED,
    WORKS,
    FAILS,
}
