package com.xz3t.equX

import com.xz3t.equX.audio.LoudnessCurve
import com.xz3t.equX.data.SpatialFxConfig
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessCurveTest {

    private val cutoffs = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    private fun gains(phon: Float, strength: Int = 100) =
        FloatArray(10).also { LoudnessCurve.gainsDb(phon, strength, cutoffs, it) }

    @Test
    fun noBoostAtReferenceLevel() {
        gains(LoudnessCurve.REF_PHON).forEach { assertEquals(0f, it, 0.01f) }
    }

    @Test
    fun boostIsZeroAtOneKilohertzPoint() {
        // Нормировка фона — по 1 кГц: в точке таблицы 1000 Гц подъём строго 0.
        val table = LoudnessCurve.requiredBoostAtTable(40f)
        assertEquals(0.0, table[17], 1e-9)
    }

    @Test
    fun quieterMeansMoreBass() {
        val at60 = gains(60f)
        val at40 = gains(40f)
        assertTrue(at60[1] > 0f)
        assertTrue(at40[1] > at60[1])
        // Бас поднимается сильнее, чем середина 250–500 Гц.
        assertTrue(at40[1] > at40[4])
    }

    @Test
    fun presenceRegionIsNotBoosted() {
        // 2–4 кГц на тихом уровне слух чувствительнее: подъёма нет (≤ 0).
        assertTrue(gains(40f)[7] <= 0.1f)
    }

    @Test
    fun boostIsCappedAndScaledByStrength() {
        val full = gains(20f)
        full.forEach { assertTrue(it <= LoudnessCurve.MAX_BOOST_DB) }
        val half = gains(40f, 50)
        val whole = gains(40f, 100)
        // 250–500 Hz: подъём ниже потолка, поэтому масштабируется линейно.
        assertTrue(whole[4] < LoudnessCurve.MAX_BOOST_DB)
        assertEquals(whole[4] / 2f, half[4], 0.01f)
    }

    @Test
    fun oldPresetTrebleFollowsWidth() {
        // В старых JSON нет airPercent: «Верха» берут значение прежнего единого ползунка.
        val old = Gson().fromJson("""{"isEnabled":true,"strengthPercent":70}""", SpatialFxConfig::class.java)
        assertEquals(70, old.strengthPercent)
        assertEquals(70, old.airPercent)
        val split = old.withAir(20)
        assertEquals(20, Gson().fromJson(Gson().toJson(split), SpatialFxConfig::class.java).airPercent)
    }
}
