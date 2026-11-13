package com.xz3t.equX

import com.google.gson.Gson
import com.xz3t.equX.data.AudioDeviceBinding
import com.xz3t.equX.data.AppBinding
import com.xz3t.equX.data.CarConfig
import com.xz3t.equX.data.EqualizerConfig
import com.xz3t.equX.data.PresetModel
import com.xz3t.equX.data.SpeedEqBand
import com.xz3t.equX.data.SpatialFxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверяет сериализацию/десериализацию PresetModel через Gson.
 *
 * Это защита от регрессии, которая уже ломала приложение: при добавлении новых полей
 * старые сохранённые JSON без них десериализовывались в null и падали
 * на copy(). Эти тесты фиксируют обратную совместимость.
 */
class PresetJsonTest {

    private val gson = Gson()

    @Test
    fun `old json without new fields deserializes with safe defaults`() {
        val oldJson = """
            {
              "presetName": "My Preset",
              "equalizer": { "isEnabled": true },
              "spatial3d": { "isEnabled": false, "strengthPercent": 0 }
            }
        """.trimIndent()

        val preset = gson.fromJson(oldJson, PresetModel::class.java)

        assertEquals("My Preset", preset.presetName)
        assertFalse(preset.fxEnabled)                       // FX выключен по умолчанию
        assertEquals(0, preset.spatial3d.strengthPercent)
    }

    @Test
    fun `roundtrip preserves new fx fields`() {
        val original = PresetModel(
            presetName = "FX Preset",
            equalizer = EqualizerConfig(isEnabled = true),
            spatial3d = SpatialFxConfig(isEnabled = true, strengthPercent = 70),
            rawFxEnabled = false,
        )

        val json = gson.toJson(original)
        val restored = gson.fromJson(json, PresetModel::class.java)

        assertEquals("FX Preset", restored.presetName)
        assertFalse(restored.fxEnabled)
        assertTrue(restored.spatial3d.isEnabled)
        assertEquals(70, restored.spatial3d.strengthPercent)
    }

    @Test
    fun `roundtrip preserves device and app bindings`() {
        val original = PresetModel(
            presetName = "Bound Preset",
            rawBoundDevices = listOf(
                AudioDeviceBinding("BT1", "Headphones", "BLUETOOTH"),
                AudioDeviceBinding("TYPE_AUX", "AUX", "AUX"),
            ),
            rawBoundApps = listOf(
                AppBinding("com.example.music", "Music app"),
            ),
        )

        val restored = gson.fromJson(gson.toJson(original), PresetModel::class.java)

        assertEquals(listOf("BT1", "TYPE_AUX"), restored.boundDevices.map { it.id })
        assertEquals(listOf("com.example.music"), restored.boundApps.map { it.packageName })
    }

    @Test
    fun `old json with null bindings deserializes as empty lists`() {
        val json = """
            {
              "presetName": "No bindings",
              "boundDevices": null,
              "boundApps": null
            }
        """.trimIndent()

        val preset = gson.fromJson(json, PresetModel::class.java)

        assertEquals(emptyList<AudioDeviceBinding>(), preset.boundDevices)
        assertEquals(emptyList<AppBinding>(), preset.boundApps)
    }

    @Test
    fun `old json without car deserializes with default car config`() {
        val json = """
            {
              "presetName": "No car",
              "equalizer": { "isEnabled": true }
            }
        """.trimIndent()

        val preset = gson.fromJson(json, PresetModel::class.java)

        assertFalse(preset.car.isEnabled)
        assertFalse(preset.car.volumeEnabled)
        assertFalse(preset.car.eqEnabled)
        assertEquals(40, preset.car.minSpeed)
        assertEquals(120, preset.car.maxSpeed)
        assertEquals(emptyList<SpeedEqBand>(), preset.car.bands.filter { it.isEnabled })
    }

    @Test
    fun `roundtrip preserves car config`() {
        val original = PresetModel(
            presetName = "Car preset",
            rawCar = CarConfig(
                isEnabled = true,
                volumeEnabled = true,
                minSpeed = 30,
                maxSpeed = 140,
                maxBoostSteps = 8,
                eqEnabled = true,
                bands = listOf(SpeedEqBand(isEnabled = true, frequencyHz = 62f, gainDb = 4f)),
            ),
        )

        val restored = gson.fromJson(gson.toJson(original), PresetModel::class.java)

        assertTrue(restored.car.isEnabled)
        assertTrue(restored.car.volumeEnabled)
        assertTrue(restored.car.eqEnabled)
        assertEquals(30, restored.car.minSpeed)
        assertEquals(140, restored.car.maxSpeed)
        assertEquals(8, restored.car.maxBoostSteps)
        assertEquals(4f, restored.car.bands[0].gainDb, 0.001f)
    }
}
