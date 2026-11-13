package com.xz3t.equX.data

import kotlin.math.abs

/**
 * Чистая функция сравнения сохранённого пресета с черновиком.
 * Возвращает true, если пользователь внёс несохранённые изменения.
 *
 * Вынесена из PresetDataManager для покрытия unit-тестами.
 */
fun hasUnsavedChanges(original: PresetModel, draft: PresetModel): Boolean {
    if (original.equalizer.isEnabled != draft.equalizer.isEnabled) return true
    if (original.compressor.isEnabled != draft.compressor.isEnabled) return true
    if (original.fxEnabled != draft.fxEnabled) return true
    if (original.spatial3d.isEnabled != draft.spatial3d.isEnabled) return true
    if (original.loudness.isEnabled != draft.loudness.isEnabled) return true
    if (original.limiter.isEnabled != draft.limiter.isEnabled) return true
    // Флаги Simple/Complex не сравниваются: это вид экрана. В Simple значения всегда лежат
    // на формуле, поэтому смена режима сама по себе звук не меняет; если меняет
    // (Complex→Simple с ручными значениями) — изменятся сами значения.
    if (carChanged(original.car, draft.car)) return true

    val origGains = original.equalizer.gainsDb
    val draftGains = draft.equalizer.gainsDb
    for (i in 0 until origGains.size.coerceAtMost(draftGains.size)) {
        if (abs(origGains[i] - draftGains[i]) > 0.05f) return true
    }

    val origFreqs = original.equalizer.customFrequenciesHz
    val draftFreqs = draft.equalizer.customFrequenciesHz
    for (i in 0 until origFreqs.size.coerceAtMost(draftFreqs.size)) {
        if (abs(origFreqs[i] - draftFreqs[i]) > 0.5f) return true
    }

    if (original.compressor.isEnabled) {
        val origBands = original.compressor.bands
        val draftBands = draft.compressor.bands
        for (i in 0 until origBands.size.coerceAtMost(draftBands.size)) {
            val oB = origBands[i]
            val dB = draftBands[i]

            if (oB.isPreEqEnabled != dB.isPreEqEnabled) return true

            if (oB.isPreEqEnabled && compressorBandChanged(oB, dB)) return true
        }
    }

    if (original.spatial3d.isEnabled) {
        if (original.spatial3d.strengthPercent != draft.spatial3d.strengthPercent) return true
        if (original.spatial3d.airPercent != draft.spatial3d.airPercent) return true
    }

    if (original.loudness.isEnabled && original.loudness != draft.loudness) return true

    if (original.limiter.isEnabled) {
        if (abs(original.limiter.thresholdDb - draft.limiter.thresholdDb) > 0.1f) return true
        if (abs(original.limiter.postGainDb - draft.limiter.postGainDb) > 0.1f) return true
        if (abs(original.limiter.releaseMs - draft.limiter.releaseMs) > 1.0f) return true
        if (abs(original.limiter.attackMs - draft.limiter.attackMs) > 0.1f) return true
        if (abs(original.limiter.ratio - draft.limiter.ratio) > 0.1f) return true
        if (original.limiter.simpleIntensityPercent != draft.limiter.simpleIntensityPercent) return true
    }

    return false
}

/**
 * Сравнивает все DSP-параметры полосы MBC (без галочки активности).
 * Используется и в hasUnsavedChanges, и для подсветки/сброса полосы в CompressorScreen.
 */
fun compressorBandChanged(original: BandDynamicParams, draft: BandDynamicParams): Boolean {
    if (abs(original.thresholdDb - draft.thresholdDb) > 0.1f) return true
    if (abs(original.ratio - draft.ratio) > 0.1f) return true
    if (abs(original.preGainDb - draft.preGainDb) > 0.1f) return true
    if (abs(original.postGainDb - draft.postGainDb) > 0.1f) return true
    if (abs(original.kneeDb - draft.kneeDb) > 0.1f) return true
    if (abs(original.attackMs - draft.attackMs) > 0.05f) return true
    if (abs(original.releaseMs - draft.releaseMs) > 0.5f) return true
    if (abs(original.noiseThresholdDb - draft.noiseThresholdDb) > 0.1f) return true
    if (abs(original.expanderRatio - draft.expanderRatio) > 0.05f) return true
    return false
}

/**
 * Сравнивает настройки Car-аудио между сохранённым пресетом и черновиком.
 */
private fun carChanged(original: CarConfig, draft: CarConfig): Boolean {
    if (original.isEnabled != draft.isEnabled) return true
    if (original.volumeEnabled != draft.volumeEnabled) return true
    if (original.eqEnabled != draft.eqEnabled) return true
    if (original.minSpeed != draft.minSpeed) return true
    if (original.maxSpeed != draft.maxSpeed) return true
    if (original.maxBoostSteps != draft.maxBoostSteps) return true

    val origBands = original.bands
    val draftBands = draft.bands
    if (origBands.size != draftBands.size) return true
    for (i in origBands.indices) {
        if (origBands[i].isEnabled != draftBands[i].isEnabled) return true
        if (abs(origBands[i].gainDb - draftBands[i].gainDb) > 0.05f) return true
    }
    return false
}
