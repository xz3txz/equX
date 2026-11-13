package com.xz3t.equX.data

import com.google.gson.annotations.SerializedName

/**
 * Маркер формата, по которому при импорте определяется, что JSON действительно
 * является пресетом equX, а не произвольным файлом.
 */
const val EQUX_PRESET_MARKER = "equX"

/**
 * Модель привязки пресета к конкретному аудиоустройству (Bluetooth, USB DAC, AUX, Динамик)
 */
data class AudioDeviceBinding(
    val id: String,          // Уникальный ID устройства (MAC-адрес или системный ключ "TYPE_AUX", "TYPE_USB_DAC", "TYPE_SPEAKER")
    val name: String,        // Название устройства
    val deviceType: String   // Тип: "BLUETOOTH", "AUX", "USB_DAC", "SPEAKER"
)

/**
 * Модель привязки пресета к конкретному медиа-приложению
 */
data class AppBinding(
    val packageName: String, // Package Name приложения
    val appName: String      // Понятное имя приложения
)

/**
 * Главная модель данных, описывающая полную конфигурацию звукового профиля equX.
 * Защищена от NullPointerException при десериализации старых JSON файлов из DataStore.
 */
data class PresetModel(
    val presetName: String,
    // Версия формата файла (см. PRESET_FORMAT_VERSION). В старых JSON нет → sanitizePreset ставит текущую.
    val formatVersion: Int = PRESET_FORMAT_VERSION,
    @SerializedName("app")
    private val rawApp: String? = EQUX_PRESET_MARKER,
    @SerializedName("boundDevices")
    private val rawBoundDevices: List<AudioDeviceBinding>? = emptyList(),
    @SerializedName("boundSources")
    private val rawBoundSources: List<AudioDeviceBinding>? = emptyList(),
    @SerializedName("boundApps")
    private val rawBoundApps: List<AppBinding>? = emptyList(),
    // Независимые режимы отображения для каждого модуля (Simple/Complex).
    // Complex-режим EQ временно скрыт из UI, но флаг оставлен для будущих версий.
    val eqComplexMode: Boolean = false,
    val compressorComplexMode: Boolean = false,
    val limiterComplexMode: Boolean = false,
    val audioSessionId: Int = 0,
    val equalizer: EqualizerConfig = EqualizerConfig(),
    val compressor: CompressorConfig = CompressorConfig(),
    val spatial3d: SpatialFxConfig = SpatialFxConfig(),
    @SerializedName("fxEnabled")
    private val rawFxEnabled: Boolean? = null,
    val limiter: LimiterConfig = LimiterConfig(),
    @SerializedName("car")
    private val rawCar: CarConfig? = null,
    @SerializedName("loudness")
    private val rawLoudness: LoudnessConfig? = null
) {
    // Безопасные свойства, предотвращающие crash при старом JSON
    val boundDevices: List<AudioDeviceBinding>
        get() = rawBoundDevices ?: emptyList()

    // Источники звука (актуально только на ГУ): сопряжённые телефоны как A2DP-источники.
    val boundSources: List<AudioDeviceBinding>
        get() = rawBoundSources ?: emptyList()

    val boundApps: List<AppBinding>
        get() = rawBoundApps ?: emptyList()

    // Старые JSON не содержат этого поля, поэтому оно может быть null.
    val app: String
        get() = rawApp ?: EQUX_PRESET_MARKER

    // Старые JSON не содержат этого поля, поэтому оно может быть null.
    val fxEnabled: Boolean
        get() = rawFxEnabled ?: false

    // Старые JSON не содержат car, поэтому подставляем дефолт.
    val car: CarConfig
        get() = rawCar ?: CarConfig()

    // Тонкомпенсация появилась позже — в старых JSON её нет.
    val loudness: LoudnessConfig
        get() = rawLoudness ?: LoudnessConfig()
}

/**
 * Настройки эквалайзера (Post-Gain каскад)
 */
data class EqualizerConfig(
    val isEnabled: Boolean = true,
    // Децибелы усиления для каждой из 10 полос (0.0 dB = прозрачный звук)
    val gainsDb: List<Float> = List(10) { 0.0f },
    val customFrequenciesHz: List<Float> = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
)

/**
 * Настройки динамической обработки
 */
data class CompressorConfig(
    val isEnabled: Boolean = true,
    // Положение ползунка Simple-компрессии (0% = прозрачный звук без обработки)
    val simpleIntensityPercent: Int = 0,
    val bands: List<BandDynamicParams> = List(10) { index ->
        BandDynamicParams(
            frequencyHz = when (index) {
                0 -> 31f; 1 -> 62f; 2 -> 125f; 3 -> 250f; 4 -> 500f
                5 -> 1000f; 6 -> 2000f; 7 -> 4000f; 8 -> 8000f; else -> 16000f
            }
        )
    }
)

/**
 * Студийные параметры одной частотной полосы (По умолчанию выключена, 1:1, 0 dB - абсолютно прозрачный звук)
 */
data class BandDynamicParams(
    val frequencyHz: Float,
    val isPreEqEnabled: Boolean = false,  // По умолчанию галочка полосы выключена
    val preGainDb: Float = 0.0f,
    val thresholdDb: Float = 0.0f,       // 0.0 dB порог -> нет сжатия
    val ratio: Float = 1.0f,             // 1.0:1 отношение -> нет компрессии
    val kneeDb: Float = 0.0f,
    val attackMs: Float = 15.0f,
    val releaseMs: Float = 80.0f,
    val noiseThresholdDb: Float = -90.0f, // -90.0 dB -> гейт выключен
    val expanderRatio: Float = 1.0f,     // 1.0:1 отношение -> нет экспандера
    val postGainDb: Float = 0.0f
)

/**
 * «Пространство»: [strengthPercent] — ширина (сила системного Virtualizer, 0% — выкл),
 * [airPercent] — подъём верхов 1–16 кГц в PreEQ. Раньше был один ползунок на оба —
 * в старых JSON поля airPercent нет, и верха берут значение ширины (звук не меняется).
 */
data class SpatialFxConfig(
    val isEnabled: Boolean = true,
    val strengthPercent: Int = 0,         // ширина: 0% -> Virtualizer выключен
    @SerializedName("airPercent")
    private val rawAirPercent: Int? = null,
    val crossoverFrequencyHz: Float = 120f,
    val crossfeedDelayMs: Int = 12,
    val crossfeedAttenuationDb: Int = -6,
    val vocalFocusPercent: Int = 50
) {
    val airPercent: Int
        get() = rawAirPercent ?: strengthPercent

    fun withAir(percent: Int): SpatialFxConfig = copy(rawAirPercent = percent)
}

/**
 * Тонкомпенсация по кривым равной громкости (ISO 226, «Флетчер–Мэнсон»): на тихой громкости
 * поднимает НЧ/ВЧ так, как их «теряет» слух. [strengthPercent] — доля от расчётной кривой,
 * [thresholdPercent] — громкость (в % от максимума), начиная с которой компенсация не нужна.
 */
data class LoudnessConfig(
    val isEnabled: Boolean = false,
    val strengthPercent: Int = 50,
    val thresholdPercent: Int = 60,
)

/**
 * Настройки скоростной компенсации звука (GALA + скоростной эквалайзер).
 * Как и остальные модули, хранятся внутри пресета и участвуют в экспорте/импорте.
 */
data class CarConfig(
    val isEnabled: Boolean = false,
    val volumeEnabled: Boolean = false,
    // Метод громкости и источник скорости — глобальные настройки (DataStore), не в пресете:
    // это свойства машины/устройства, а не звука.
    val minSpeed: Int = 40,
    val maxSpeed: Int = 120,
    val maxBoostSteps: Int = 6,
    val eqEnabled: Boolean = false,
    val bands: List<SpeedEqBand> = defaultSpeedEqBands(),
)

/**
 * Настройки финального жесткого лимитера (0.0 dB threshold = прозрачный звук, postGainDb = лимит громкости)
 */
data class LimiterConfig(
    val isEnabled: Boolean = true,
    val thresholdDb: Float = 0.0f,       // 0.0 dB -> нет среза
    val attackMs: Float = 1.0f,
    val releaseMs: Float = 60.0f,
    val postGainDb: Float = 0.0f,        // Максимальный лимит громкости (-12.0 dB .. +6.0 dB)
    val ratio: Float = 10.0f,            // Соотношение компрессии (2:1 .. 20:1; 10 по умолчанию)
    val simpleIntensityPercent: Int = 0  // Simple-режим: одна ручка «Интенсивность» (0..100)
)

/**
 * Производные параметры лимитера для Simple-режима.
 */
data class LimiterDerivedParams(
    val thresholdDb: Float,
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
)

/**
 * Раскладывает одну ручку «Интенсивность» (0..100) в реальные параметры лимитера.
 * 0% — мягкая/прозрачная защита, 100% — агрессивный brickwall.
 */
fun deriveLimiterParams(intensityPercent: Int): LimiterDerivedParams {
    val t = intensityPercent.coerceIn(0, 100) / 100f
    return LimiterDerivedParams(
        thresholdDb = -1f - t * 19f,   // -1 .. -20 дБ
        ratio = 2f + t * 18f,          // 2:1 .. 20:1
        attackMs = 30f - t * 29f,      // 30 .. 1 мс
        releaseMs = 250f - t * 190f,   // 250 .. 60 мс
    )
}

/**
 * Параметры лимитера, которые реально звучат: в Complex — поля пресета,
 * в Simple — формула от интенсивности. Единый источник для движка и ручных колбэков UI.
 */
fun effectiveLimiterParams(preset: PresetModel): LimiterDerivedParams =
    if (preset.limiterComplexMode) {
        LimiterDerivedParams(
            thresholdDb = preset.limiter.thresholdDb,
            ratio = preset.limiter.ratio,
            attackMs = preset.limiter.attackMs,
            releaseMs = preset.limiter.releaseMs,
        )
    } else {
        deriveLimiterParams(preset.limiter.simpleIntensityPercent)
    }

/**
 * Частотные группы для Simple-компрессора.
 * В UI остаются все 10 полос, но под капотом их поведение различается по диапазону.
 */
enum class CompressorBandGroup { LOW, MID, HIGH }

fun compressorBandGroup(frequencyHz: Float): CompressorBandGroup = when {
    frequencyHz <= 250f -> CompressorBandGroup.LOW
    frequencyHz <= 4000f -> CompressorBandGroup.MID
    else -> CompressorBandGroup.HIGH
}

/**
 * Раскладывает Simple-«Интенсивность» полосы (0..100%) в реальные параметры MBC.
 * 0% — прозрачный bypass (threshold=0, ratio=1:1), 100% — максимальная обработка.
 * Характер кривой зависит от частотной группы: Low сохраняет панч, Mid сбалансирован, High ловит сибилянты.
 */
fun deriveCompressorBandParams(
    frequencyHz: Float,
    intensityPercent: Int,
    isPreEqEnabled: Boolean,
): BandDynamicParams {
    val t = intensityPercent.coerceIn(0, 100) / 100f
    return when (compressorBandGroup(frequencyHz)) {
        CompressorBandGroup.LOW -> BandDynamicParams(
            frequencyHz = frequencyHz,
            isPreEqEnabled = isPreEqEnabled,
            thresholdDb = -18f * t,
            ratio = 1f + 4f * t,
            kneeDb = 8f * t,
            attackMs = 30f - 20f * t,
            releaseMs = 200f - 80f * t,
            noiseThresholdDb = -90f,
            expanderRatio = 1f,
            preGainDb = 3f * t,
            postGainDb = 4f * t,
        )
        CompressorBandGroup.MID -> BandDynamicParams(
            frequencyHz = frequencyHz,
            isPreEqEnabled = isPreEqEnabled,
            thresholdDb = -20f * t,
            ratio = 1f + 5f * t,
            kneeDb = 6f * t,
            attackMs = 15f - 10f * t,
            releaseMs = 150f - 60f * t,
            noiseThresholdDb = -90f,
            expanderRatio = 1f,
            preGainDb = 2f * t,
            postGainDb = 3f * t,
        )
        CompressorBandGroup.HIGH -> BandDynamicParams(
            frequencyHz = frequencyHz,
            isPreEqEnabled = isPreEqEnabled,
            thresholdDb = -22f * t,
            ratio = 1f + 5f * t,
            kneeDb = 4f * t,
            attackMs = 5f - 4f * t,
            releaseMs = 100f - 40f * t,
            noiseThresholdDb = -90f,
            expanderRatio = 1f,
            preGainDb = 1f * t,
            postGainDb = 2f * t,
        )
    }
}

/**
 * Восстанавливает приблизительную интенсивность полосы из её сохранённых параметров.
 * Используется для показа слайдера при повторном входе в Simple-режим.
 */
fun inferCompressorIntensity(frequencyHz: Float, band: BandDynamicParams): Int {
    if (band.thresholdDb >= 0f) return 0
    val maxThreshold = when (compressorBandGroup(frequencyHz)) {
        CompressorBandGroup.LOW -> 18f
        CompressorBandGroup.MID -> 20f
        CompressorBandGroup.HIGH -> 22f
    }
    return kotlin.math.round((-band.thresholdDb / maxThreshold) * 100f)
        .toInt()
        .coerceIn(0, 100)
}

/**
 * Восстанавливает Simple-интенсивность лимитера из threshold (обратная к deriveLimiterParams).
 */
fun inferLimiterIntensity(thresholdDb: Float): Int {
    val t = (-1f - thresholdDb) / 19f
    return kotlin.math.round(t * 100f).toInt().coerceIn(0, 100)
}

/**
 * Совпадают ли Complex-поля лимитера с формулой Simple для данной интенсивности.
 */
fun limiterMatchesIntensity(limiter: LimiterConfig, intensityPercent: Int): Boolean {
    val p = deriveLimiterParams(intensityPercent)
    return kotlin.math.abs(limiter.thresholdDb - p.thresholdDb) <= 0.1f &&
        kotlin.math.abs(limiter.ratio - p.ratio) <= 0.1f &&
        kotlin.math.abs(limiter.attackMs - p.attackMs) <= 0.1f &&
        kotlin.math.abs(limiter.releaseMs - p.releaseMs) <= 1.0f
}

/**
 * Инвариант Simple-лимитера: Complex-поля = deriveLimiterParams(simpleIntensityPercent).
 * В Simple движок и так играет формулу, поэтому выравнивание звук не меняет
 * (нужно для старых/новых пресетов, где поля по умолчанию 0 dB / 10:1 / 1 / 60 мс).
 */
fun normalizeSimpleLimiter(preset: PresetModel): PresetModel {
    if (preset.limiterComplexMode) return preset
    val limiter = preset.limiter
    if (limiterMatchesIntensity(limiter, limiter.simpleIntensityPercent)) return preset
    val p = deriveLimiterParams(limiter.simpleIntensityPercent)
    return preset.copy(
        limiter = limiter.copy(
            thresholdDb = p.thresholdDb,
            ratio = p.ratio,
            attackMs = p.attackMs,
            releaseMs = p.releaseMs
        )
    )
}

/**
 * Значения лимитера для перехода Complex→Simple: если поля уже на формуле для
 * сохранённой интенсивности — она и остаётся, иначе интенсивность восстанавливается
 * по threshold и поля приводятся к формуле.
 */
fun snapLimiterToSimple(limiter: LimiterConfig): LimiterConfig {
    if (limiterMatchesIntensity(limiter, limiter.simpleIntensityPercent)) return limiter
    val intensity = inferLimiterIntensity(limiter.thresholdDb)
    val p = deriveLimiterParams(intensity)
    return limiter.copy(
        simpleIntensityPercent = intensity,
        thresholdDb = p.thresholdDb,
        ratio = p.ratio,
        attackMs = p.attackMs,
        releaseMs = p.releaseMs
    )
}

/**
 * Полоса MBC звучит прозрачно (как формула при 0%): без компрессии, гейта и усиления.
 * Attack/release/knee на такой полосе ни на что не влияют.
 */
private fun isCompressorBandTransparent(band: BandDynamicParams): Boolean =
    band.thresholdDb >= -0.1f && band.ratio <= 1.1f && band.expanderRatio <= 1.05f &&
        kotlin.math.abs(band.preGainDb) <= 0.1f && kotlin.math.abs(band.postGainDb) <= 0.1f

/**
 * Полоса MBC, приведённая к формуле Simple (интенсивность восстанавливается по threshold).
 */
fun snapCompressorBandToSimple(band: BandDynamicParams): BandDynamicParams =
    deriveCompressorBandParams(
        frequencyHz = band.frequencyHz,
        intensityPercent = inferCompressorIntensity(band.frequencyHz, band),
        isPreEqEnabled = band.isPreEqEnabled
    )

/**
 * Полоса MBC отредактирована вручную в Complex: её значения не лежат на формуле Simple.
 * Прозрачная полоса (в т.ч. с дефолтными attack/release) ручной не считается.
 */
fun isCompressorBandManual(band: BandDynamicParams): Boolean {
    if (isCompressorBandTransparent(band)) return false
    return compressorBandChanged(snapCompressorBandToSimple(band), band)
}
