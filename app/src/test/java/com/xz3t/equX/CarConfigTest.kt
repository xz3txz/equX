package com.xz3t.equX

import com.xz3t.equX.data.CarConfig
import com.xz3t.equX.data.SPEED_EQ_FREQUENCIES_HZ
import com.xz3t.equX.data.VolumeMethod
import com.xz3t.equX.data.SpeedEqBand
import com.xz3t.equX.data.defaultSpeedEqBands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Инварианты Car-модели (GALA + скоростной эквалайзер).
 *
 * Эти баги уже не раз «всплывали» после правок UI/сериализации, поэтому
 * фиксируем дефолты и форму полос тестами, чтобы регрессии ловились сразу.
 */
class CarConfigTest {

    @Test
    fun `car config defaults are stable`() {
        val car = CarConfig()

        assertFalse(car.isEnabled)
        assertFalse(car.volumeEnabled)
        assertFalse(car.eqEnabled)
        assertEquals(40, car.minSpeed)
        assertEquals(120, car.maxSpeed)
        assertEquals(6, car.maxBoostSteps)
        assertEquals(10, car.bands.size)
    }

    @Test
    fun `speed eq has exactly 10 standard frequencies`() {
        assertEquals(10, SPEED_EQ_FREQUENCIES_HZ.size)
        assertEquals(
            listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f),
            SPEED_EQ_FREQUENCIES_HZ,
        )
    }

    @Test
    fun `default speed eq bands are all disabled with zero gain`() {
        val bands = defaultSpeedEqBands()

        assertEquals(10, bands.size)
        assertTrue(bands.all { !it.isEnabled })
        assertTrue(bands.all { it.gainDb == 0f })
        assertEquals(SPEED_EQ_FREQUENCIES_HZ, bands.map { it.frequencyHz })
    }

    @Test
    fun `speed eq band keeps frequency when copying gain`() {
        val band = SpeedEqBand(frequencyHz = 62f, isEnabled = true, gainDb = 4f)
        val updated = band.copy(gainDb = 6f)

        assertEquals(62f, updated.frequencyHz, 0.001f)
        assertTrue(updated.isEnabled)
        assertEquals(6f, updated.gainDb, 0.001f)
    }

    @Test
    fun `car config copy preserves bands independently`() {
        val original = CarConfig(
            isEnabled = true,
            eqEnabled = true,
            bands = defaultSpeedEqBands(),
        )
        val changed = original.copy(
            bands = original.bands.toMutableList().apply {
                this[0] = this[0].copy(isEnabled = true, gainDb = 3f)
            },
        )

        assertFalse(original.bands[0].isEnabled)
        assertTrue(changed.bands[0].isEnabled)
        assertEquals(3f, changed.bands[0].gainDb, 0.001f)
    }

    @Test
    fun `volume method exposes all options`() {
        assertEquals(
            listOf(VolumeMethod.AUTO, VolumeMethod.METHOD_2, VolumeMethod.METHOD_3),
            VolumeMethod.entries.toList(),
        )
    }
}
