package com.xz3t.equX.data

/**
 * Источник скорости для GALA и скоростного EQ (глобальная настройка, не в пресете).
 *
 * AUTO  — первый автомобильный источник с данными (ECARX → VHAL); GPS — только если автомобильных нет.
 * ECARX — vendor-служба ECARX `car_signal`, VehSpdLgtA 0x2160728C (м/с) + качество 0x2140728F.
 * VHAL  — Android Automotive: CarPropertyManager PERF_VEHICLE_SPEED, фолбэк `cmd car_service`.
 * GPS   — LocationManager.GPS_PROVIDER, Location.getSpeed().
 */
enum class SpeedSource {
    AUTO,
    ECARX,
    VHAL,
    GPS,
}

/**
 * Состояние источника скорости (индикатор в селекторе).
 * UNKNOWN     — серый: ещё нет данных (подключается, GPS ищет спутники, не опрашивается);
 * ACTIVE      — зелёный: отдаёт валидные значения;
 * UNAVAILABLE — красный: недоступен (нет службы/класса, нет разрешения, геолокация выключена).
 */
enum class SpeedSourceStatus {
    UNKNOWN,
    ACTIVE,
    UNAVAILABLE,
}

/** Состояние источника и максимальная скорость от него за время работы службы (null — данных не было). */
data class SpeedSourceInfo(
    val status: SpeedSourceStatus = SpeedSourceStatus.UNKNOWN,
    val maxKmh: Float? = null,
)

/**
 * Какой источник реально использовать. Для AUTO — первый автомобильный, который за время работы службы
 * хотя бы раз отдал данные (ECARX → VHAL), иначе GPS. «Хотя бы раз», а не «прямо сейчас»: кратковременная
 * потеря качества ECARX не должна переключать на VHAL (на части ГУ он всегда 0) — пропуск закроет удержание
 * в EffectiveSpeedFilter. Явно выбранный источник возвращается как есть (без подмены другим).
 */
fun resolveSpeedSource(
    selected: SpeedSource,
    ecarxDelivered: Boolean,
    vhalDelivered: Boolean,
): SpeedSource = when (selected) {
    SpeedSource.AUTO -> when {
        ecarxDelivered -> SpeedSource.ECARX
        vhalDelivered -> SpeedSource.VHAL
        else -> SpeedSource.GPS
    }
    else -> selected
}
