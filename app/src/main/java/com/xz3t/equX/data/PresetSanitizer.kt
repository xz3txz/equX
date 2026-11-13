package com.xz3t.equX.data

/**
 * Валидация пресета как «терпимое чтение» (без миграций).
 *
 * Gson создаёт [PresetModel] и [BandDynamicParams] через Unsafe (у них есть параметры без дефолтов),
 * поэтому Kotlin-дефолты не срабатывают: отсутствующий блок (`equalizer`, `compressor`...) приходит
 * null, отсутствующее Float-поле полосы — 0.0, элемент списка может быть null. Типы при этом
 * «не-null», и падение случается позже, в UI или движке.
 *
 * [sanitizePreset] — одна чистая функция для всех входов (импорт из файла, DataStore, DE-кэш):
 *  - отсутствующее поле / null → дефолт;
 *  - NaN/Infinity и значения вне диапазонов слайдеров UI → прижимаются к диапазону;
 *  - списки полос EQ / MBC / скоростного EQ → ровно [BAND_COUNT] (дополняются стандартными
 *    частотами или обрезаются);
 *  - частоты EQ должны строго возрастать, иначе — стандартные;
 *  - Simple-лимитер выравнивается по формуле ([normalizeSimpleLimiter]).
 *
 * Функция идемпотентна: повторный вызов на результате ничего не меняет.
 */
object PresetLimits {
    const val BAND_COUNT = 10
    val FREQ_HZ = 10f..22000f

    val EQ_GAIN_DB = -12f..12f

    const val PERCENT_MIN = 0
    const val PERCENT_MAX = 100

    // Compressor Complex (CompressorScreen)
    val MBC_PRE_GAIN_DB = 0f..12f
    val MBC_THRESHOLD_DB = -60f..0f
    val MBC_RATIO = 1f..20f
    val MBC_KNEE_DB = 0f..20f
    val MBC_ATTACK_MS = 0.1f..100f
    val MBC_RELEASE_MS = 10f..1000f
    val MBC_NOISE_GATE_DB = -90f..-30f
    val MBC_EXPANDER_RATIO = 1f..10f
    val MBC_POST_GAIN_DB = -12f..12f

    // Limiter (LimiterScreen)
    val LIM_POST_GAIN_DB = -12f..6f
    val LIM_THRESHOLD_DB = -20f..0f
    val LIM_ATTACK_MS = 0.1f..30f
    val LIM_RATIO = 2f..20f
    val LIM_RELEASE_MS = 10f..500f

    // Loudness (SpatialFxScreen)
    const val LOUDNESS_THRESHOLD_MIN = 10

    // Car (CarScreen)
    const val CAR_MIN_SPEED_MIN = 10
    const val CAR_MIN_SPEED_MAX = 200
    const val CAR_MAX_SPEED_MIN = 20
    const val CAR_MAX_SPEED_MAX = 250
    const val CAR_SPEED_GAP = 10
    const val CAR_BOOST_STEPS_MAX = 39
    val SPEED_EQ_GAIN_DB = -12f..12f
}

/** Версия формата файла пресета. Отсутствует в JSON → 1. Повышать только при смене смысла поля. */
const val PRESET_FORMAT_VERSION = 1

val DEFAULT_BAND_FREQUENCIES_HZ: List<Float> = SPEED_EQ_FREQUENCIES_HZ

fun sanitizePreset(preset: PresetModel, fallbackName: String = "Preset"): PresetModel {
    val name = orDefault<String>(preset.presetName, fallbackName).trim().ifEmpty { fallbackName.trim() }

    val sanitized = preset.copy(
        presetName = name,
        formatVersion = PRESET_FORMAT_VERSION,
        rawApp = EQUX_PRESET_MARKER,
        rawBoundDevices = preset.boundDevices.filterValid { it.isValid() },
        rawBoundSources = preset.boundSources.filterValid { it.isValid() },
        rawBoundApps = preset.boundApps.filterValid { it.isValid() },
        equalizer = sanitizeEqualizer(orDefault(preset.equalizer, EqualizerConfig())),
        compressor = sanitizeCompressor(orDefault(preset.compressor, CompressorConfig())),
        spatial3d = sanitizeSpatial(orDefault(preset.spatial3d, SpatialFxConfig())),
        limiter = sanitizeLimiterConfig(orDefault(preset.limiter, LimiterConfig())),
        rawCar = sanitizeCar(preset.car),
        rawLoudness = sanitizeLoudness(preset.loudness),
    )
    return normalizeSimpleLimiter(sanitized)
}

// ------------------------------------------------------------------ модули

private fun sanitizeEqualizer(eq: EqualizerConfig): EqualizerConfig {
    val gains = List(PresetLimits.BAND_COUNT) { i ->
        floatAt(eq.gainsDb, i)?.coerceIn(PresetLimits.EQ_GAIN_DB) ?: 0f
    }
    val rawFreqs = List(PresetLimits.BAND_COUNT) { i ->
        floatAt(eq.customFrequenciesHz, i)?.takeIf { it in PresetLimits.FREQ_HZ }
    }
    val freqs = if (rawFreqs.all { it != null } && rawFreqs.zipWithNext().all { (a, b) -> a!! < b!! }) {
        rawFreqs.map { it!! }
    } else {
        DEFAULT_BAND_FREQUENCIES_HZ
    }
    return EqualizerConfig(isEnabled = eq.isEnabled, gainsDb = gains, customFrequenciesHz = freqs)
}

private fun sanitizeCompressor(c: CompressorConfig): CompressorConfig {
    val raw: List<*> = orDefault<List<*>>(c.bands, emptyList<Any>())
    val bands = List(PresetLimits.BAND_COUNT) { i ->
        val band = raw.getOrNull(i) as? BandDynamicParams
        if (band == null) BandDynamicParams(frequencyHz = DEFAULT_BAND_FREQUENCIES_HZ[i])
        else sanitizeBand(band, DEFAULT_BAND_FREQUENCIES_HZ[i])
    }
    return CompressorConfig(
        isEnabled = c.isEnabled,
        simpleIntensityPercent = c.simpleIntensityPercent.coerceIn(PresetLimits.PERCENT_MIN, PresetLimits.PERCENT_MAX),
        bands = bands,
    )
}

private fun sanitizeBand(b: BandDynamicParams, defaultFreq: Float): BandDynamicParams {
    val d = BandDynamicParams(frequencyHz = defaultFreq)
    // Гейт выше −30 dB (в т.ч. 0.0 от Gson при отсутствии поля) — это не «прижать», а включить
    // агрессивный гейт; безопаснее выключить его (−90 dB).
    val gate = b.noiseThresholdDb.takeIf { it.isFinite() && it in PresetLimits.MBC_NOISE_GATE_DB }
        ?: d.noiseThresholdDb
    return BandDynamicParams(
        frequencyHz = b.frequencyHz.takeIf { it.isFinite() && it in PresetLimits.FREQ_HZ } ?: defaultFreq,
        isPreEqEnabled = b.isPreEqEnabled,
        preGainDb = b.preGainDb.clampOr(PresetLimits.MBC_PRE_GAIN_DB, d.preGainDb),
        thresholdDb = b.thresholdDb.clampOr(PresetLimits.MBC_THRESHOLD_DB, d.thresholdDb),
        ratio = b.ratio.clampOr(PresetLimits.MBC_RATIO, d.ratio),
        kneeDb = b.kneeDb.clampOr(PresetLimits.MBC_KNEE_DB, d.kneeDb),
        attackMs = b.attackMs.positiveOr(d.attackMs).coerceIn(PresetLimits.MBC_ATTACK_MS),
        releaseMs = b.releaseMs.positiveOr(d.releaseMs).coerceIn(PresetLimits.MBC_RELEASE_MS),
        noiseThresholdDb = gate,
        expanderRatio = b.expanderRatio.clampOr(PresetLimits.MBC_EXPANDER_RATIO, d.expanderRatio),
        postGainDb = b.postGainDb.clampOr(PresetLimits.MBC_POST_GAIN_DB, d.postGainDb),
    )
}

private fun sanitizeSpatial(s: SpatialFxConfig): SpatialFxConfig = s.copy(
    strengthPercent = s.strengthPercent.coerceIn(PresetLimits.PERCENT_MIN, PresetLimits.PERCENT_MAX),
).withAir(s.airPercent.coerceIn(PresetLimits.PERCENT_MIN, PresetLimits.PERCENT_MAX)).copy(
    crossoverFrequencyHz = s.crossoverFrequencyHz.takeIf { it.isFinite() && it in PresetLimits.FREQ_HZ }
        ?: SpatialFxConfig().crossoverFrequencyHz,
    vocalFocusPercent = s.vocalFocusPercent.coerceIn(PresetLimits.PERCENT_MIN, PresetLimits.PERCENT_MAX),
)

private fun sanitizeLimiterConfig(l: LimiterConfig): LimiterConfig {
    val d = LimiterConfig()
    return l.copy(
        thresholdDb = l.thresholdDb.clampOr(PresetLimits.LIM_THRESHOLD_DB, d.thresholdDb),
        // 0 от Gson (поле отсутствует) → дефолт, а не нижняя граница.
        attackMs = l.attackMs.positiveOr(d.attackMs).coerceIn(PresetLimits.LIM_ATTACK_MS),
        releaseMs = l.releaseMs.positiveOr(d.releaseMs).coerceIn(PresetLimits.LIM_RELEASE_MS),
        postGainDb = l.postGainDb.clampOr(PresetLimits.LIM_POST_GAIN_DB, d.postGainDb),
        ratio = l.ratio.positiveOr(d.ratio).coerceIn(PresetLimits.LIM_RATIO),
        simpleIntensityPercent = l.simpleIntensityPercent.coerceIn(PresetLimits.PERCENT_MIN, PresetLimits.PERCENT_MAX),
    )
}

private fun sanitizeLoudness(l: LoudnessConfig): LoudnessConfig = l.copy(
    strengthPercent = l.strengthPercent.coerceIn(PresetLimits.PERCENT_MIN, PresetLimits.PERCENT_MAX),
    thresholdPercent = l.thresholdPercent.coerceIn(PresetLimits.LOUDNESS_THRESHOLD_MIN, PresetLimits.PERCENT_MAX),
)

private fun sanitizeCar(c: CarConfig): CarConfig {
    var minSpeed = c.minSpeed.coerceIn(PresetLimits.CAR_MIN_SPEED_MIN, PresetLimits.CAR_MIN_SPEED_MAX)
    val maxSpeed = c.maxSpeed.coerceIn(
        maxOf(PresetLimits.CAR_MAX_SPEED_MIN, minSpeed + PresetLimits.CAR_SPEED_GAP),
        PresetLimits.CAR_MAX_SPEED_MAX,
    )
    minSpeed = minSpeed.coerceAtMost(maxSpeed - PresetLimits.CAR_SPEED_GAP)

    val raw: List<*> = orDefault<List<*>>(c.bands, emptyList<Any>())
    val bands = List(PresetLimits.BAND_COUNT) { i ->
        val defaultFreq = SPEED_EQ_FREQUENCIES_HZ[i]
        val band = raw.getOrNull(i) as? SpeedEqBand
        if (band == null) SpeedEqBand(frequencyHz = defaultFreq)
        else SpeedEqBand(
            isEnabled = band.isEnabled,
            frequencyHz = band.frequencyHz.takeIf { it.isFinite() && it in PresetLimits.FREQ_HZ } ?: defaultFreq,
            gainDb = band.gainDb.clampOr(PresetLimits.SPEED_EQ_GAIN_DB, 0f),
        )
    }
    return c.copy(
        minSpeed = minSpeed,
        maxSpeed = maxSpeed,
        maxBoostSteps = c.maxBoostSteps.coerceIn(0, PresetLimits.CAR_BOOST_STEPS_MAX),
        bands = bands,
    )
}

// ------------------------------------------------------------------ привязки

private fun AudioDeviceBinding.isValid(): Boolean =
    orDefault<String>(id, "").isNotBlank() && orDefault<String?>(name, null) != null &&
        orDefault<String?>(deviceType, null) != null

private fun AppBinding.isValid(): Boolean =
    orDefault<String>(packageName, "").isNotBlank() && orDefault<String?>(appName, null) != null

private inline fun <T : Any> List<T>.filterValid(isValid: (T) -> Boolean): List<T> =
    (this as List<T?>).filterNotNull().filter(isValid)

// ------------------------------------------------------------------ helpers

/**
 * Gson может положить null в «не-null» поле. Параметр nullable, поэтому Kotlin не вставляет
 * проверку при передаче, и `?:` действительно срабатывает.
 */
private fun <T> orDefault(value: T?, default: T): T = value ?: default

/** Элемент списка как конечный Float; null/не число/NaN/Infinity → null. */
private fun floatAt(list: List<*>?, index: Int): Float? =
    (orDefault<List<*>?>(list, null)?.getOrNull(index) as? Number)?.toFloat()?.takeIf { it.isFinite() }

private fun Float.clampOr(range: ClosedFloatingPointRange<Float>, default: Float): Float =
    if (isFinite()) coerceIn(range) else default

private fun Float.positiveOr(default: Float): Float = if (isFinite() && this > 0f) this else default
