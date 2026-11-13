package com.xz3t.equX.data

/**
 * Одна полоса скоростного эквалайзера (Speed Compensated EQ).
 *
 * При движении автомобиля дорожный шум растёт (в основном низкие частоты).
 * Каждая включённая полоса плавно поднимает/опускает свою частоту от 0 дБ
 * на «начальной скорости» до [gainDb] на «предельной скорости».
 */
data class SpeedEqBand(
    val isEnabled: Boolean = false,
    val frequencyHz: Float = 31f,
    val gainDb: Float = 0f,
)

/** Стандартные 10 частот, используемые и в обычном эквалайзере. */
val SPEED_EQ_FREQUENCIES_HZ: List<Float> = listOf(
    31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
)

/** Пресет по умолчанию: все 10 полос выключены. */
fun defaultSpeedEqBands(): List<SpeedEqBand> =
    SPEED_EQ_FREQUENCIES_HZ.map { SpeedEqBand(frequencyHz = it) }
