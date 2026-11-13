package com.xz3t.equX

import com.xz3t.equX.data.BandDynamicParams
import com.xz3t.equX.data.LimiterConfig
import com.xz3t.equX.data.PresetModel
import com.xz3t.equX.data.deriveCompressorBandParams
import com.xz3t.equX.data.deriveLimiterParams
import com.xz3t.equX.data.effectiveLimiterParams
import com.xz3t.equX.data.inferLimiterIntensity
import com.xz3t.equX.data.isCompressorBandManual
import com.xz3t.equX.data.limiterMatchesIntensity
import com.xz3t.equX.data.normalizeSimpleLimiter
import com.xz3t.equX.data.snapCompressorBandToSimple
import com.xz3t.equX.data.snapLimiterToSimple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleComplexTest {

    private val freqs = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    @Test
    fun `bands written by simple slider are not manual`() {
        for (f in freqs) for (i in listOf(0, 1, 37, 50, 99, 100)) {
            val band = deriveCompressorBandParams(f, i, isPreEqEnabled = true)
            assertFalse("f=$f i=$i", isCompressorBandManual(band))
        }
    }

    @Test
    fun `default transparent band is not manual`() {
        assertFalse(isCompressorBandManual(BandDynamicParams(frequencyHz = 1000f, isPreEqEnabled = true)))
    }

    @Test
    fun `band edited in complex is manual and snaps back to curve`() {
        val band = deriveCompressorBandParams(1000f, 50, true).copy(attackMs = 80f)
        assertTrue(isCompressorBandManual(band))
        val snapped = snapCompressorBandToSimple(band)
        assertFalse(isCompressorBandManual(snapped))
        assertEquals(deriveCompressorBandParams(1000f, 50, true), snapped)
    }

    @Test
    fun `limiter intensity is inferred from threshold`() {
        for (i in listOf(0, 25, 50, 100)) {
            assertEquals(i, inferLimiterIntensity(deriveLimiterParams(i).thresholdDb))
        }
        assertEquals(0, inferLimiterIntensity(0f))
    }

    @Test
    fun `limiter on formula keeps its intensity when switching to simple`() {
        val p = deriveLimiterParams(40)
        val limiter = LimiterConfig(
            simpleIntensityPercent = 40,
            thresholdDb = p.thresholdDb, ratio = p.ratio, attackMs = p.attackMs, releaseMs = p.releaseMs
        )
        assertSame(limiter, snapLimiterToSimple(limiter))
    }

    @Test
    fun `manually edited limiter snaps to formula by threshold`() {
        val limiter = LimiterConfig(simpleIntensityPercent = 10, thresholdDb = -10.5f, ratio = 15f, attackMs = 5f, releaseMs = 100f)
        val snapped = snapLimiterToSimple(limiter)
        assertEquals(50, snapped.simpleIntensityPercent)
        assertTrue(limiterMatchesIntensity(snapped, 50))
    }

    @Test
    fun `simple limiter with default complex fields is normalized`() {
        val preset = PresetModel(presetName = "x", limiter = LimiterConfig())
        val normalized = normalizeSimpleLimiter(preset)
        assertTrue(limiterMatchesIntensity(normalized.limiter, 0))
        val complex = preset.copy(limiterComplexMode = true)
        assertSame(complex, normalizeSimpleLimiter(complex))
    }

    @Test
    fun `effective limiter params use formula in simple and fields in complex`() {
        // Поля не на формуле (дефолты нового пресета) — Simple всё равно играет формулу.
        val simple = PresetModel(presetName = "x", limiter = LimiterConfig(simpleIntensityPercent = 30))
        assertEquals(deriveLimiterParams(30), effectiveLimiterParams(simple))

        val complex = simple.copy(limiterComplexMode = true)
        val p = effectiveLimiterParams(complex)
        assertEquals(complex.limiter.thresholdDb, p.thresholdDb)
        assertEquals(complex.limiter.ratio, p.ratio)
    }
}
