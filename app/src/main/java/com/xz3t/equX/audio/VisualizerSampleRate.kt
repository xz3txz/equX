package com.xz3t.equX.audio

/**
 * Приводит частоту дискретизации, которую отдаёт `Visualizer.onFftDataCapture` / `getSamplingRate()`,
 * к нормальному диапазону.
 *
 * На некоторых прошивках callback возвращает мусор вида `48000000` (Hz * 1000) вместо `48000`,
 * что ломает сопоставление FFT-бина с частотой (`bin = freq * captureSize / sampleRate`).
 */
fun sanitizeVisualizerSampleRate(samplingRate: Int, fallback: Int = 44_100): Int {
    var rate = samplingRate
    // Космические значения (> 1 млн) трактуем как Hz * 1000 и делим.
    if (rate > 1_000_000) rate /= 1000
    // Защита от нуля и прочего мусора.
    if (rate !in 8_000..384_000) return fallback
    return rate
}
