package com.xz3t.equX

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xz3t.equX.data.BandDynamicParams
import com.xz3t.equX.data.CarConfig
import com.xz3t.equX.data.DEFAULT_BAND_FREQUENCIES_HZ
import com.xz3t.equX.data.EqualizerConfig
import com.xz3t.equX.data.LimiterConfig
import com.xz3t.equX.data.PRESET_FORMAT_VERSION
import com.xz3t.equX.data.PresetModel
import com.xz3t.equX.data.SpeedEqBand
import com.xz3t.equX.data.deriveLimiterParams
import com.xz3t.equX.data.sanitizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sanitizePreset(): битые, старые и «враждебные» JSON не должны ронять UI/движок.
 */
class PresetSanitizerTest {

    private val gson = Gson()

    private fun parse(json: String): PresetModel =
        sanitizePreset(gson.fromJson(json, PresetModel::class.java), "Fallback")

    /** Пресет полностью пригоден к использованию: все списки по 10, все доступы не падают. */
    private fun assertUsable(p: PresetModel) {
        assertEquals(10, p.equalizer.gainsDb.size)
        assertEquals(10, p.equalizer.customFrequenciesHz.size)
        assertEquals(10, p.compressor.bands.size)
        assertEquals(10, p.car.bands.size)
        p.equalizer.gainsDb.forEach { assertTrue(it.isFinite() && it in -12f..12f) }
        p.compressor.bands.forEach { b ->
            assertTrue(b.frequencyHz.isFinite())
            assertTrue(b.ratio >= 1f && b.attackMs > 0f && b.releaseMs > 0f)
        }
        p.car.bands.forEach { assertTrue(it.gainDb in -12f..12f) }
        assertTrue(p.limiter.ratio >= 2f && p.limiter.attackMs > 0f)
        assertTrue(p.boundDevices.isEmpty() || p.boundDevices.all { it.id.isNotBlank() })
        assertEquals(PRESET_FORMAT_VERSION, p.formatVersion)
    }

    @Test
    fun `minimal json gets full defaults`() {
        val p = parse("""{ "app": "equX", "presetName": "Min" }""")
        assertUsable(p)
        assertEquals("Min", p.presetName)
        assertEquals(DEFAULT_BAND_FREQUENCIES_HZ, p.equalizer.customFrequenciesHz)
        // Отсутствующий гейт (0.0 от Gson) не должен включать агрессивный гейт.
        p.compressor.bands.forEach { assertEquals(-90f, it.noiseThresholdDb) }
    }

    @Test
    fun `null blocks and null list elements`() {
        val p = parse(
            """
            { "presetName": "Nulls",
              "equalizer": { "gainsDb": null, "customFrequenciesHz": [31, null, 125] },
              "compressor": null, "limiter": null, "spatial3d": null,
              "car": { "bands": [null, { "gainDb": 3 }] },
              "boundDevices": [null, { "id": "", "name": "x", "deviceType": "BLUETOOTH" }],
              "boundApps": null }
            """
        )
        assertUsable(p)
        assertTrue(p.boundDevices.isEmpty())
        assertEquals(3f, p.car.bands[1].gainDb)
        assertEquals(31f, p.car.bands[0].frequencyHz)
    }

    @Test
    fun `short speed eq list is padded with standard frequencies`() {
        val p = parse("""{ "presetName": "Short", "car": { "isEnabled": true, "bands": [ { "frequencyHz": 62, "gainDb": 4 } ] } }""")
        assertUsable(p)
        assertEquals(62f, p.car.bands[0].frequencyHz)
        assertEquals(DEFAULT_BAND_FREQUENCIES_HZ.drop(1), p.car.bands.drop(1).map { it.frequencyHz })
    }

    @Test
    fun `long lists are truncated to 10`() {
        val gains = (1..15).joinToString { "1" }
        val p = parse("""{ "presetName": "Long", "equalizer": { "gainsDb": [$gains] } }""")
        assertUsable(p)
        assertTrue(p.equalizer.gainsDb.all { it == 1f })
    }

    @Test
    fun `out of range values are clamped`() {
        val p = parse(
            """
            { "presetName": "Wild",
              "equalizer": { "gainsDb": [100, -100, 0, 0, 0, 0, 0, 0, 0, 0] },
              "limiter": { "postGainDb": 50, "ratio": 1000, "attackMs": -5, "simpleIntensityPercent": 500 },
              "spatial3d": { "strengthPercent": 999 },
              "loudness": { "strengthPercent": -5, "thresholdPercent": 0 },
              "car": { "minSpeed": 500, "maxSpeed": 5, "maxBoostSteps": 99 } }
            """
        )
        assertUsable(p)
        assertEquals(12f, p.equalizer.gainsDb[0])
        assertEquals(-12f, p.equalizer.gainsDb[1])
        assertEquals(6f, p.limiter.postGainDb)
        assertEquals(100, p.limiter.simpleIntensityPercent)
        assertEquals(100, p.spatial3d.strengthPercent)
        assertEquals(0, p.loudness.strengthPercent)
        assertEquals(10, p.loudness.thresholdPercent)
        assertTrue(p.car.maxSpeed - p.car.minSpeed >= 10)
        assertTrue(p.car.minSpeed in 10..200 && p.car.maxSpeed in 20..250)
        assertEquals(39, p.car.maxBoostSteps)
    }

    @Test
    fun `non increasing eq frequencies fall back to defaults`() {
        val p = parse(
            """{ "presetName": "Freq", "equalizer": { "customFrequenciesHz": [31, 62, 50, 250, 500, 1000, 2000, 4000, 8000, 16000] } }"""
        )
        assertEquals(DEFAULT_BAND_FREQUENCIES_HZ, p.equalizer.customFrequenciesHz)
    }

    @Test
    fun `nan and infinity are replaced`() {
        val preset = PresetModel(
            presetName = "NaN",
            equalizer = EqualizerConfig(gainsDb = List(10) { Float.NaN }),
            limiter = LimiterConfig(postGainDb = Float.POSITIVE_INFINITY),
            rawCar = CarConfig(bands = List(10) { SpeedEqBand(frequencyHz = Float.NaN, gainDb = Float.NaN) }),
        )
        val p = sanitizePreset(preset)
        assertUsable(p)
        assertTrue(p.equalizer.gainsDb.all { it == 0f })
        assertEquals(0f, p.limiter.postGainDb)
    }

    @Test
    fun `missing name uses fallback, name is trimmed`() {
        assertEquals("Fallback", parse("""{ "app": "equX" }""").presetName)
        assertEquals("Rock", parse("""{ "presetName": "  Rock  " }""").presetName)
    }

    @Test
    fun `old limiter zero ratio and attack get defaults`() {
        val p = parse("""{ "presetName": "Old", "limiterComplexMode": true, "limiter": { "ratio": 0, "attackMs": 0 } }""")
        assertEquals(10f, p.limiter.ratio)
        assertEquals(1f, p.limiter.attackMs)
    }

    @Test
    fun `simple limiter is aligned to formula`() {
        val p = parse("""{ "presetName": "S", "limiter": { "simpleIntensityPercent": 50 } }""")
        assertEquals(deriveLimiterParams(50).thresholdDb, p.limiter.thresholdDb)
    }

    @Test
    fun `valid preset is unchanged and sanitize is idempotent`() {
        val valid = sanitizePreset(
            PresetModel(
                presetName = "Valid",
                equalizer = EqualizerConfig(gainsDb = List(10) { it - 5f }),
                rawCar = CarConfig(isEnabled = true, minSpeed = 50, maxSpeed = 130),
            )
        )
        assertEquals(valid, sanitizePreset(valid))
        val roundtrip = sanitizePreset(gson.fromJson(gson.toJson(valid), PresetModel::class.java))
        assertEquals(valid, roundtrip)
    }

    @Test
    fun `datastore map with null entry is tolerated`() {
        val type = object : TypeToken<Map<String, PresetModel?>>() {}.type
        val map: Map<String, PresetModel?> = gson.fromJson("""{ "A": null, "B": { "presetName": "B" } }""", type)
        val cleaned = map.filterValues { it != null }.mapValues { (k, p) -> sanitizePreset(p!!, k) }
        assertEquals(setOf("B"), cleaned.keys)
        assertUsable(cleaned.getValue("B"))
    }

    @Test
    fun `compressor band fields default when missing`() {
        val p = parse("""{ "presetName": "C", "compressor": { "bands": [ { "frequencyHz": 31 } ] } }""")
        val b: BandDynamicParams = p.compressor.bands[0]
        assertEquals(1f, b.ratio)
        assertEquals(-90f, b.noiseThresholdDb)
        assertEquals(1f, b.expanderRatio)
        assertFalse(b.isPreEqEnabled)
    }
}
