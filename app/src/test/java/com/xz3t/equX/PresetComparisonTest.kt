package com.xz3t.equX

import com.xz3t.equX.data.BandDynamicParams
import com.xz3t.equX.data.CarConfig
import com.xz3t.equX.data.CompressorConfig
import com.xz3t.equX.data.EqualizerConfig
import com.xz3t.equX.data.LimiterConfig
import com.xz3t.equX.data.PresetModel
import com.xz3t.equX.data.SpeedEqBand
import com.xz3t.equX.data.SpatialFxConfig
import com.xz3t.equX.data.VolumeMethod
import com.xz3t.equX.data.hasUnsavedChanges
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetComparisonTest {

    private fun base() = PresetModel(
        presetName = "Test",
        equalizer = EqualizerConfig(isEnabled = true, gainsDb = List(10) { 0f }),
        limiter = LimiterConfig(isEnabled = false),
        spatial3d = SpatialFxConfig(isEnabled = false),
    )

    @Test
    fun `identical presets have no unsaved changes`() {
        val a = base()
        val b = base()
        assertFalse(hasUnsavedChanges(a, b))
    }

    @Test
    fun `toggling equalizer is unsaved change`() {
        val a = base()
        val b = base().copy(equalizer = a.equalizer.copy(isEnabled = false))
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `changing a band gain is unsaved change`() {
        val a = base()
        val newGains = a.equalizer.gainsDb.toMutableList().apply { this[3] = 5f }
        val b = a.copy(equalizer = a.equalizer.copy(gainsDb = newGains))
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `tiny gain difference below epsilon is not a change`() {
        val a = base()
        val newGains = a.equalizer.gainsDb.toMutableList().apply { this[3] = 0.01f }
        val b = a.copy(equalizer = a.equalizer.copy(gainsDb = newGains))
        assertFalse(hasUnsavedChanges(a, b))
    }

    @Test
    fun `changing custom frequency is unsaved change`() {
        val a = base()
        val newFreqs = a.equalizer.customFrequenciesHz.toMutableList().apply { this[0] = 40f }
        val b = a.copy(equalizer = a.equalizer.copy(customFrequenciesHz = newFreqs))
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `changing limiter intensity when limiter enabled is unsaved change`() {
        val a = base().copy(
            limiter = LimiterConfig(isEnabled = true, simpleIntensityPercent = 0),
        )
        val b = a.copy(
            limiter = a.limiter.copy(simpleIntensityPercent = 50),
        )
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `limiter changes ignored when limiter disabled`() {
        val a = base().copy(limiter = LimiterConfig(isEnabled = false, simpleIntensityPercent = 0))
        val b = a.copy(limiter = a.limiter.copy(simpleIntensityPercent = 50))
        assertFalse(hasUnsavedChanges(a, b))
    }

    @Test
    fun `toggling fx master is unsaved change`() {
        val a = base().copy(rawFxEnabled = true)
        val b = base().copy(rawFxEnabled = false)
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `toggling spatial fx is unsaved change`() {
        val a = base()
        val b = base().copy(spatial3d = SpatialFxConfig(isEnabled = true, strengthPercent = 0))
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `spatial fx intensity ignored when spatial fx disabled`() {
        val a = base()
        val b = base().copy(spatial3d = SpatialFxConfig(isEnabled = false, strengthPercent = 80))
        assertFalse(hasUnsavedChanges(a, b))
    }

    // --- Car (GALA + скоростной EQ) ---

    @Test
    fun `toggling car master is unsaved change`() {
        val a = base()
        val b = base().copy(rawCar = CarConfig(isEnabled = true))
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `changing car volume is unsaved change`() {
        val a = base().copy(rawCar = CarConfig(isEnabled = true, volumeEnabled = false))
        val b = base().copy(rawCar = CarConfig(isEnabled = true, volumeEnabled = true))
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `changing car min speed is unsaved change`() {
        val a = base().copy(rawCar = CarConfig(isEnabled = true, minSpeed = 40))
        val b = base().copy(rawCar = CarConfig(isEnabled = true, minSpeed = 60))
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `changing car max boost is unsaved change`() {
        val a = base().copy(rawCar = CarConfig(isEnabled = true, maxBoostSteps = 6))
        val b = base().copy(rawCar = CarConfig(isEnabled = true, maxBoostSteps = 9))
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `changing car speed eq band gain is unsaved change`() {
        val a = base().copy(
            rawCar = CarConfig(
                isEnabled = true,
                eqEnabled = true,
                bands = listOf(SpeedEqBand(isEnabled = true, frequencyHz = 31f, gainDb = 0f)),
            ),
        )
        val b = base().copy(
            rawCar = CarConfig(
                isEnabled = true,
                eqEnabled = true,
                bands = listOf(SpeedEqBand(isEnabled = true, frequencyHz = 31f, gainDb = 3f)),
            ),
        )
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `identical car configs are not unsaved changes`() {
        val car = CarConfig(isEnabled = true, volumeEnabled = true, minSpeed = 40, maxSpeed = 120)
        val a = base().copy(rawCar = car)
        val b = base().copy(rawCar = car.copy())
        assertFalse(hasUnsavedChanges(a, b))
    }

    @Test
    fun `car with different band list length is unsaved change`() {
        val a = base().copy(
            rawCar = CarConfig(
                isEnabled = true,
                eqEnabled = true,
                bands = listOf(SpeedEqBand(frequencyHz = 31f)),
            ),
        )
        val b = base().copy(
            rawCar = CarConfig(
                isEnabled = true,
                eqEnabled = true,
                bands = listOf(
                    SpeedEqBand(frequencyHz = 31f),
                    SpeedEqBand(frequencyHz = 62f),
                ),
            ),
        )
        assertTrue(hasUnsavedChanges(a, b))
    }

    @Test
    fun `car with default config is not unsaved change`() {
        val a = base()
        val b = base().copy(rawCar = CarConfig())
        assertFalse(hasUnsavedChanges(a, b))
    }

    private fun withActiveBand(edit: (BandDynamicParams) -> BandDynamicParams): Pair<PresetModel, PresetModel> {
        val freqs = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val bands = freqs.map { BandDynamicParams(frequencyHz = it, isPreEqEnabled = true) }
        val a = base().copy(compressor = CompressorConfig(isEnabled = true, bands = bands))
        val edited = bands.toMutableList().apply { this[2] = edit(this[2]) }
        return a to a.copy(compressor = a.compressor.copy(bands = edited))
    }

    @Test
    fun `changing compressor knee attack release noise expander is unsaved change`() {
        listOf<(BandDynamicParams) -> BandDynamicParams>(
            { it.copy(kneeDb = 6f) },
            { it.copy(attackMs = 40f) },
            { it.copy(releaseMs = 300f) },
            { it.copy(noiseThresholdDb = -60f) },
            { it.copy(expanderRatio = 3f) },
        ).forEach { edit ->
            val (a, b) = withActiveBand(edit)
            assertTrue(hasUnsavedChanges(a, b))
        }
    }

    @Test
    fun `identical compressor bands are not unsaved change`() {
        val (a, b) = withActiveBand { it.copy() }
        assertFalse(hasUnsavedChanges(a, b))
    }

    @Test
    fun `switching compressor or limiter complex mode alone is not unsaved change`() {
        val a = base()
        assertFalse(hasUnsavedChanges(a, a.copy(compressorComplexMode = true)))
        assertFalse(hasUnsavedChanges(a, a.copy(limiterComplexMode = true)))
    }
}
