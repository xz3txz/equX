package com.xz3t.equX.data

import android.util.DisplayMetrics

/** Минимальный/максимальный масштаб интерфейса. */
const val APP_SCALE_MIN = 0.5f
const val APP_SCALE_MAX = 2.0f
const val APP_SCALE_DEFAULT = 1.0f

/**
 * Отношение физического DPI к системному densityDpi, при превышении которого
 * считаем, что системная плотность занижена и UI реально мельче ожидаемого.
 *
 * На обычных телефонах это отношение близко к 1.0 (часто даже < 1.0), поэтому
 * масштаб менять не нужно. Заниженная densityDpi встречается на OEM-ГУ, где,
 * например, физический DPI ~320, а densityDpi=160 (ratio ~2.0).
 */
private const val RECOMMENDED_SCALE_TRIGGER_RATIO = 1.25f

/**
 * «Комфортный» коэффициент компенсации.
 *
 * Полная компенсация (ratio * 1.0) даёт слишком крупный UI и упирается в
 * [APP_SCALE_MAX]. 0.8 даёт для ГУ ratio 2.0 → 160%, что визуально нормально.
 */
private const val RECOMMENDED_SCALE_FACTOR = 0.8f

/**
 * Приводит масштаб интерфейса к допустимому диапазону.
 *
 * Регрессия: при смене масштаба экран мерцал, а после переоткрытия настройки
 * значение сбрасывалось в 100%. Выносим диапазон и валидацию в чистую функцию,
 * чтобы слайдер и DataStore всегда использовали один и тот же диапазон.
 */
fun coerceAppScale(scale: Float): Float =
    scale.coerceIn(APP_SCALE_MIN, APP_SCALE_MAX)

/**
 * Вычисляет рекомендованный масштаб по [DisplayMetrics].
 *
 * Тонкая обёртка над [computeRecommendedAppScale] для вызова из Android-кода.
 */
fun computeRecommendedAppScale(metrics: DisplayMetrics): Float =
    computeRecommendedAppScale(
        densityDpi = metrics.densityDpi.toFloat(),
        physicalDpi = maxOf(metrics.xdpi, metrics.ydpi),
    )

/**
 * Чистая функция расчёта рекомендованного масштаба (для unit-тестов).
 *
 * @param densityDpi системная плотность экрана (densityDpi из DisplayMetrics).
 * @param physicalDpi физический DPI экрана (обычно max(xdpi, ydpi)).
 *
 * Логика:
 *  - при некорректных входных значениях возвращаем 100%;
 *  - при отношении < [RECOMMENDED_SCALE_TRIGGER_RATIO] возвращаем 100% — это
 *    обычные телефоны/планшеты, где densityDpi совпадает с физическим DPI;
 *  - иначе компенсируем расхождение с коэффициентом [RECOMMENDED_SCALE_FACTOR].
 */
fun computeRecommendedAppScale(densityDpi: Float, physicalDpi: Float): Float {
    if (densityDpi <= 0f || physicalDpi <= 0f) return APP_SCALE_DEFAULT

    val ratio = physicalDpi / densityDpi
    if (ratio < RECOMMENDED_SCALE_TRIGGER_RATIO) return APP_SCALE_DEFAULT

    return coerceAppScale(ratio * RECOMMENDED_SCALE_FACTOR)
}
