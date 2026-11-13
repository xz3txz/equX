package com.xz3t.equX.audio

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Кривые равной громкости ISO 226:2003 («Флетчер–Мэнсон») для тонкомпенсации.
 *
 * Что здесь по стандарту, а что — допущение:
 * - Коэффициенты αf, L_U, T_f и формула фон → дБ SPL — полная таблица 1 стандарта (29 частот,
 *   20 Гц … 12.5 кГц). Нужный подъём = насколько кривая на тихом уровне выше кривой на опорном,
 *   с нормировкой точно по 1 кГц (определение фона).
 * - Полоса DynamicsProcessing даёт одно усиление на весь диапазон между соседними cutoff
 *   (cutoff — ВЕРХНЯЯ граница полосы). Поэтому подъём полосы — среднее нужного подъёма по
 *   её диапазону в логарифмической шкале частот, а не значение в точке cutoff.
 *   Ниже 20 Гц и выше 12.5 кГц стандарт не определён: туда берётся крайняя точка таблицы.
 * - Допущение: реальное звуковое давление в салоне неизвестно. Уровень (фон) оценивается по шагу
 *   громкости: на пороге считается [REF_PHON], ниже — по таблице ослабления шагов.
 */
object LoudnessCurve {

    /** Опорный уровень прослушивания (phon), на котором компенсация нулевая. */
    const val REF_PHON = 80f
    // ISO 226 действует от 20 phon (для 0–20 phon стандарт только информативен).
    private const val MIN_PHON = 20f

    /**
     * Ослабление (дБ) по шагам громкости 0..39 — типичная калибровка автомобильной системы:
     * шаги 11..39 по 1 дБ, ниже — круче. Реальная кривая усилителя может отличаться;
     * поэтому есть «порог» и «сила».
     */
    private val VOLUME_TABLE_DB = intArrayOf(
        -96, -60, -55, -50, -45, -43, -41, -39, -37, -35, -33, -32, -31, -30, -29, -28, -27, -26, -25, -24,
        -23, -22, -21, -20, -19, -18, -17, -16, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4,
    )

    /** Для другой шкалы громкости (не 0..39) — линейная оценка дБ на шаг. */
    private const val DB_PER_STEP = 1.5f

    /** Потолок подъёма полосы: без него самый низ на минимальной громкости уходит за +20 дБ. */
    const val MAX_BOOST_DB = 12f

    // ISO 226:2003, таблица 1.
    private val FREQ = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0,
        500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0,
        8000.0, 10000.0, 12500.0,
    )
    private val ALPHA = doubleArrayOf(
        0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301, 0.288, 0.276,
        0.267, 0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245,
        0.254, 0.271, 0.301,
    )
    private val LU = doubleArrayOf(
        -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1, -0.4,
        0.0, 0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1,
        -11.2, -10.7, -3.1,
    )
    private val TF = doubleArrayOf(
        78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2,
        4.4, 3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0,
        12.6, 13.9, 12.3,
    )
    private val REF_INDEX = FREQ.indexOfFirst { it == 1000.0 }

    /** Точек усреднения на полосу (в лог-шкале частот). */
    private const val SAMPLES_PER_BAND = 16

    /** Уровень звукового давления (дБ SPL) на частоте таблицы [i] для кривой громкости [phon]. */
    private fun splAt(i: Int, phon: Double): Double {
        val a = ALPHA[i]
        val af = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15) +
            (0.4 * 10.0.pow((TF[i] + LU[i]) / 10.0 - 9.0)).pow(a)
        return 10.0 / a * log10(af) - LU[i] + 94.0
    }

    /** Нужный подъём (дБ) в точках таблицы: кривая [phon] минус опорная, нормировано по 1 кГц. */
    fun requiredBoostAtTable(phon: Float): DoubleArray {
        val p = phon.toDouble()
        val ref = REF_PHON.toDouble()
        val refShift = splAt(REF_INDEX, p) - splAt(REF_INDEX, ref)
        return DoubleArray(FREQ.size) { i -> splAt(i, p) - splAt(i, ref) - refShift }
    }

    /** Линейная интерполяция [table] по лог-частоте; вне 20 Гц…12.5 кГц — крайняя точка. */
    private fun interpolate(table: DoubleArray, fHz: Double): Double {
        if (fHz <= FREQ.first()) return table.first()
        if (fHz >= FREQ.last()) return table.last()
        var i = 1
        while (FREQ[i] < fHz) i++
        val t = (ln(fHz) - ln(FREQ[i - 1])) / (ln(FREQ[i]) - ln(FREQ[i - 1]))
        return table[i - 1] + (table[i] - table[i - 1]) * t
    }

    /** Уровень прослушивания (phon) для громкости [volume] при пороге [thresholdStep] и шкале 0..[maxVolume]. */
    fun phonFor(volume: Int, thresholdStep: Int, maxVolume: Int): Float {
        val dropDb = if (maxVolume == VOLUME_TABLE_DB.lastIndex) {
            val v = volume.coerceIn(0, maxVolume)
            val t = thresholdStep.coerceIn(0, maxVolume)
            (VOLUME_TABLE_DB[t] - VOLUME_TABLE_DB[v]).coerceAtLeast(0).toFloat()
        } else {
            (thresholdStep - volume).coerceAtLeast(0) * DB_PER_STEP
        }
        return (REF_PHON - dropDb).coerceAtLeast(MIN_PHON)
    }

    /**
     * Подъём полос (дБ) для уровня [phon] с силой [strengthPercent] (100% — полная разница кривых).
     * [bandCutoffsHz] — верхние границы полос DP по возрастанию; нижняя граница первой полосы — 20 Гц.
     * Отрицательные значения (2–5 кГц, где слух на тихом уровне чувствительнее) сохраняются —
     * это часть кривой.
     */
    fun gainsDb(phon: Float, strengthPercent: Int, bandCutoffsHz: FloatArray, out: FloatArray) {
        val k = strengthPercent.coerceIn(0, 100) / 100.0
        val boost = requiredBoostAtTable(phon)
        var lowHz = FREQ.first()
        for (b in out.indices) {
            val highHz = bandCutoffsHz.getOrElse(b) { bandCutoffsHz.last() }.toDouble().coerceAtLeast(lowHz)
            out[b] = (bandAverage(boost, lowHz, highHz) * k).toFloat().coerceAtMost(MAX_BOOST_DB)
            lowHz = highHz
        }
    }

    /** Среднее нужного подъёма по полосе [lowHz]..[highHz] в лог-шкале частот. */
    private fun bandAverage(boost: DoubleArray, lowHz: Double, highHz: Double): Double {
        if (highHz <= lowHz) return interpolate(boost, lowHz)
        val lnLow = ln(lowHz)
        val step = (ln(highHz) - lnLow) / SAMPLES_PER_BAND
        var sum = 0.0
        for (s in 0 until SAMPLES_PER_BAND) {
            // Середины подотрезков.
            sum += interpolate(boost, kotlin.math.exp(lnLow + step * (s + 0.5)))
        }
        return sum / SAMPLES_PER_BAND
    }
}
