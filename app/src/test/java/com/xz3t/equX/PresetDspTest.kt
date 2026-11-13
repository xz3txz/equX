package com.xz3t.equX

import com.xz3t.equX.data.BandDynamicParams
import com.xz3t.equX.data.CompressorBandGroup
import com.xz3t.equX.data.compressorBandGroup
import com.xz3t.equX.data.deriveCompressorBandParams
import com.xz3t.equX.data.deriveLimiterParams
import com.xz3t.equX.data.inferCompressorIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetDspTest {

    // --- Limiter intensity mapping ---

    @Test
    fun `limiter at 0 percent is soft and transparent`() {
        val p = deriveLimiterParams(0)
        assertEquals(-1f, p.thresholdDb, 0.001f)
        assertEquals(2f, p.ratio, 0.001f)
        assertEquals(30f, p.attackMs, 0.001f)
        assertEquals(250f, p.releaseMs, 0.001f)
    }

    @Test
    fun `limiter at 100 percent is aggressive brickwall`() {
        val p = deriveLimiterParams(100)
        assertEquals(-20f, p.thresholdDb, 0.001f)
        assertEquals(20f, p.ratio, 0.001f)
        assertEquals(1f, p.attackMs, 0.001f)
        assertEquals(60f, p.releaseMs, 0.001f)
    }

    @Test
    fun `limiter clamps out of range intensity`() {
        assertEquals(deriveLimiterParams(0), deriveLimiterParams(-50))
        assertEquals(deriveLimiterParams(100), deriveLimiterParams(150))
    }

    // --- Compressor band grouping ---

    @Test
    fun `compressor band groups use correct frequency boundaries`() {
        assertEquals(CompressorBandGroup.LOW, compressorBandGroup(31f))
        assertEquals(CompressorBandGroup.LOW, compressorBandGroup(250f))
        assertEquals(CompressorBandGroup.MID, compressorBandGroup(251f))
        assertEquals(CompressorBandGroup.MID, compressorBandGroup(4000f))
        assertEquals(CompressorBandGroup.HIGH, compressorBandGroup(4001f))
        assertEquals(CompressorBandGroup.HIGH, compressorBandGroup(16000f))
    }

    // --- Compressor band derivation ---

    @Test
    fun `compressor at 0 percent is transparent bypass`() {
        val band = deriveCompressorBandParams(frequencyHz = 500f, intensityPercent = 0, isPreEqEnabled = false)
        assertEquals(0f, band.thresholdDb, 0.001f)
        assertEquals(1f, band.ratio, 0.001f)
        assertTrue(!band.isPreEqEnabled)
    }

    @Test
    fun `compressor preserves pre-eq flag`() {
        val band = deriveCompressorBandParams(frequencyHz = 500f, intensityPercent = 50, isPreEqEnabled = true)
        assertTrue(band.isPreEqEnabled)
    }

    @Test
    fun `compressor clamps out of range intensity`() {
        val atZero = deriveCompressorBandParams(500f, 0, false)
        val belowZero = deriveCompressorBandParams(500f, -10, false)
        assertEquals(atZero.thresholdDb, belowZero.thresholdDb, 0.001f)

        val atMax = deriveCompressorBandParams(500f, 100, false)
        val aboveMax = deriveCompressorBandParams(500f, 200, false)
        assertEquals(atMax.thresholdDb, aboveMax.thresholdDb, 0.001f)
    }

    // --- Compressor intensity inference ---

    @Test
    fun `infer returns 0 when threshold is transparent`() {
        val band = BandDynamicParams(frequencyHz = 500f, thresholdDb = 0f)
        assertEquals(0, inferCompressorIntensity(500f, band))
    }

    @Test
    fun `infer returns 100 at max low-band threshold`() {
        val band = BandDynamicParams(frequencyHz = 100f, thresholdDb = -18f)
        assertEquals(100, inferCompressorIntensity(100f, band))
    }

    @Test
    fun `infer clamps result to 0 to 100`() {
        val tooDeep = BandDynamicParams(frequencyHz = 100f, thresholdDb = -100f)
        assertEquals(100, inferCompressorIntensity(100f, tooDeep))
    }
}
