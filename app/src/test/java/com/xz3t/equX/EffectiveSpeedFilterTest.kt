package com.xz3t.equX

import com.xz3t.equX.data.EffectiveSpeedFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveSpeedFilterTest {

    /** Подаёт постоянную скорость раз в секунду с [fromSec] по [toSec] включительно. */
    private fun EffectiveSpeedFilter.feed(kmh: Float, fromSec: Int, toSec: Int): Float {
        var e = effectiveKmh
        for (t in fromSec..toSec) e = onSample(kmh, t * 1000L)
        return e
    }

    @Test
    fun `first sample is taken as is`() {
        val f = EffectiveSpeedFilter()
        assertEquals(60f, f.onSample(60f, 0L), 0.001f)
    }

    @Test
    fun `step change is smoothed, not instant`() {
        val f = EffectiveSpeedFilter()
        f.feed(0f, 0, 5)
        val afterOneSec = f.onSample(100f, 6_000L)
        assertTrue("after 1 s: $afterOneSec", afterOneSec in 20f..50f)
        val settled = f.feed(100f, 7, 30)
        assertEquals(100f, settled, 3f)
    }

    @Test
    fun `smoothing does not depend on source rate`() {
        val slow = EffectiveSpeedFilter(deadBandKmh = 0f)
        val fast = EffectiveSpeedFilter(deadBandKmh = 0f)
        slow.onSample(0f, 0L); fast.onSample(0f, 0L)
        for (t in 1..3) slow.onSample(100f, t * 1000L)
        for (t in 1..30) fast.onSample(100f, t * 100L)
        assertEquals(slow.effectiveKmh, fast.effectiveKmh, 1f)
    }

    @Test
    fun `noise inside dead band does not move effective speed`() {
        val f = EffectiveSpeedFilter()
        f.feed(80f, 0, 20)
        val base = f.effectiveKmh
        val noise = floatArrayOf(81.5f, 78.5f, 81f, 79f, 80.5f, 79.5f)
        noise.forEachIndexed { i, v -> f.onSample(v, (21 + i) * 1000L) }
        assertEquals(base, f.effectiveKmh, 0.001f)
    }

    @Test
    fun `stop snaps to zero`() {
        val f = EffectiveSpeedFilter()
        f.feed(30f, 0, 20)
        assertEquals(0f, f.feed(0f, 21, 60), 0.001f)
    }

    @Test
    fun `signal loss holds last speed, then decays at 1 mps2`() {
        val f = EffectiveSpeedFilter()
        f.feed(90f, 0, 30)
        val held = f.effectiveKmh
        // Удержание 15 с от последнего сэмпла (t = 30 с).
        for (t in 31..45) f.tick(t * 1000L)
        assertEquals(held, f.effectiveKmh, 0.001f)
        // Ещё 10 с снижения: 1 м/с² = 3.6 км/ч в секунду → −36 км/ч.
        for (t in 46..55) f.tick(t * 1000L)
        assertEquals(held - 36f, f.effectiveKmh, 0.5f)
        // До нуля и не ниже.
        for (t in 56..200) f.tick(t * 1000L)
        assertEquals(0f, f.effectiveKmh, 0.001f)
    }

    @Test
    fun `tick does nothing while samples are fresh`() {
        val f = EffectiveSpeedFilter()
        f.feed(50f, 0, 10)
        val e = f.effectiveKmh
        f.tick(11_000L)
        f.tick(12_500L)
        assertEquals(e, f.effectiveKmh, 0.001f)
    }

    @Test
    fun `signal return after loss catches up smoothly`() {
        val f = EffectiveSpeedFilter()
        f.feed(90f, 0, 30)
        for (t in 31..60) f.tick(t * 1000L) // удержание и снижение
        val decayed = f.effectiveKmh
        val first = f.onSample(90f, 61_000L)
        assertTrue("decayed=$decayed first=$first", first > decayed && first < 90f)
    }
}
