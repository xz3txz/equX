package com.xz3t.equX

import com.xz3t.equX.audio.sanitizeVisualizerSampleRate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Регрессия: на vivo callback возвращал samplingRate = 48000000 вместо 48000,
 * из-за чего спектр ломался (все полосы в один бин). Этот баг повторялся 3+ раз.
 */
class VisualizerSampleRateTest {

    @Test
    fun `normal rate passes through`() {
        assertEquals(48_000, sanitizeVisualizerSampleRate(48_000))
    }

    @Test
    fun `rate multiplied by 1000 is divided`() {
        assertEquals(48_000, sanitizeVisualizerSampleRate(48_000_000))
    }

    @Test
    fun `zero falls back to default`() {
        assertEquals(44_100, sanitizeVisualizerSampleRate(0))
    }

    @Test
    fun `too small rate falls back`() {
        assertEquals(44_100, sanitizeVisualizerSampleRate(4_000))
    }

    @Test
    fun `too large rate falls back`() {
        assertEquals(44_100, sanitizeVisualizerSampleRate(500_000))
    }

    @Test
    fun `uses provided fallback when invalid`() {
        assertEquals(12_000, sanitizeVisualizerSampleRate(1, fallback = 12_000))
    }
}
