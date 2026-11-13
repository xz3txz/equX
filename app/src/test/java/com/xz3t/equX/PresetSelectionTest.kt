package com.xz3t.equX

import com.xz3t.equX.data.AppBinding
import com.xz3t.equX.data.AudioDeviceBinding
import com.xz3t.equX.data.PresetModel
import com.xz3t.equX.data.removeAppBinding
import com.xz3t.equX.data.retainDeviceBindings
import com.xz3t.equX.data.selectPresetForAutoSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PresetSelectionTest {

    private fun device(id: String) = AudioDeviceBinding(id, "device-$id", "BLUETOOTH")
    private fun source(id: String) = AudioDeviceBinding(id, "source-$id", "SOURCE")
    private fun app(pkg: String) = AppBinding(pkg, "app-$pkg")

    private fun preset(
        name: String,
        devices: List<String> = emptyList(),
        sources: List<String> = emptyList(),
        apps: List<String> = emptyList(),
    ) = PresetModel(
        presetName = name,
        rawBoundDevices = devices.map(::device),
        rawBoundSources = sources.map(::source),
        rawBoundApps = apps.map(::app),
    )

    // --- Auto-Switch selection ---

    @Test
    fun `selects preset bound to both device and app first`() {
        val presets = listOf(
            preset("Default"),
            preset("Device only", devices = listOf("BT1")),
            preset("Device + Music app", devices = listOf("BT1"), apps = listOf("com.example.music")),
        )

        val result = selectPresetForAutoSwitch(
            pkg = "com.example.music",
            activeDeviceId = "BT1",
            allPresets = presets,
            defaultPresetName = "Default",
        )

        assertEquals("Device + Music app", result?.presetName)
    }

    @Test
    fun `falls back to device-only preset when no app matches`() {
        val presets = listOf(
            preset("Default"),
            preset("Device only", devices = listOf("BT1")),
            preset("Other app", devices = listOf("BT1"), apps = listOf("com.example.other")),
        )

        val result = selectPresetForAutoSwitch(
            pkg = "com.example.video",
            activeDeviceId = "BT1",
            allPresets = presets,
            defaultPresetName = "Default",
        )

        assertEquals("Device only", result?.presetName)
    }

    @Test
    fun `falls back to default when nothing matches`() {
        val presets = listOf(
            preset("Default"),
            preset("Device only", devices = listOf("BT2")),
        )

        val result = selectPresetForAutoSwitch(
            pkg = "com.example.video",
            activeDeviceId = "BT1",
            allPresets = presets,
            defaultPresetName = "Default",
        )

        assertEquals("Default", result?.presetName)
    }

    @Test
    fun `GLOBAL package skips app matching`() {
        val presets = listOf(
            preset("Default"),
            preset("Device only", devices = listOf("BT1")),
            preset("Device + Music app", devices = listOf("BT1"), apps = listOf("com.example.music")),
        )

        val result = selectPresetForAutoSwitch(
            pkg = "GLOBAL",
            activeDeviceId = "BT1",
            allPresets = presets,
            defaultPresetName = "Default",
        )

        // Для GLOBAL правило «устройство + приложение» не применяется,
        // поэтому берётся пресет только по устройству.
        assertEquals("Device only", result?.presetName)
    }

    @Test
    fun `blank package skips app matching`() {
        val presets = listOf(
            preset("Default"),
            preset("Device only", devices = listOf("BT1")),
        )

        val result = selectPresetForAutoSwitch(
            pkg = "  ",
            activeDeviceId = "BT1",
            allPresets = presets,
            defaultPresetName = "Default",
        )

        assertEquals("Device only", result?.presetName)
    }

    // --- Source (head unit) selection ---

    @Test
    fun `selects source plus app preset first on head unit`() {
        val presets = listOf(
            preset("Default"),
            preset("Source only", sources = listOf("PHONE1")),
            preset("Source + app", sources = listOf("PHONE1"), apps = listOf("com.example.music")),
            preset("Device + app", devices = listOf("SPEAKER"), apps = listOf("com.example.music")),
        )

        val result = selectPresetForAutoSwitch(
            pkg = "com.example.music",
            activeDeviceId = "SPEAKER",
            activeSourceId = "PHONE1",
            allPresets = presets,
            defaultPresetName = "Default",
        )

        assertEquals("Source + app", result?.presetName)
    }

    @Test
    fun `falls back to source-only preset when no app matches on head unit`() {
        val presets = listOf(
            preset("Default"),
            preset("Source only", sources = listOf("PHONE1")),
            preset("Device only", devices = listOf("SPEAKER")),
        )

        val result = selectPresetForAutoSwitch(
            pkg = "com.example.video",
            activeDeviceId = "SPEAKER",
            activeSourceId = "PHONE1",
            allPresets = presets,
            defaultPresetName = "Default",
        )

        assertEquals("Source only", result?.presetName)
    }

    @Test
    fun `without source id behaves like legacy device matching`() {
        val presets = listOf(
            preset("Default"),
            preset("Device only", devices = listOf("BT1")),
            preset("Source only", sources = listOf("PHONE1")),
        )

        val result = selectPresetForAutoSwitch(
            pkg = "com.example.video",
            activeDeviceId = "BT1",
            activeSourceId = null,
            allPresets = presets,
            defaultPresetName = "Default",
        )

        assertEquals("Device only", result?.presetName)
    }

    @Test
    fun `returns null when no default preset exists`() {
        val result = selectPresetForAutoSwitch(
            pkg = "com.example.video",
            activeDeviceId = "BT1",
            allPresets = emptyList(),
            defaultPresetName = "Default",
        )

        assertNull(result)
    }

    // --- Binding pruning ---

    @Test
    fun `removes only the uninstalled app binding`() {
        val p = PresetModel(
            presetName = "P",
            rawBoundApps = listOf(app("com.example.music"), app("com.example.gone")),
        )
        assertEquals(
            listOf("com.example.music"),
            removeAppBinding(p, "com.example.gone").boundApps.map { it.packageName },
        )
        assertSame(p, removeAppBinding(p, "com.example.other"))
    }

    @Test
    fun `retains only present devices and sources`() {
        val p = PresetModel(
            presetName = "P",
            rawBoundDevices = listOf(device("BT1"), device("BT2")),
            rawBoundSources = listOf(source("PH1"), source("PH2")),
        )
        val cleaned = retainDeviceBindings(p, { it.id == "BT1" }, { it.id == "PH2" })
        assertEquals(listOf("BT1"), cleaned.boundDevices.map { it.id })
        assertEquals(listOf("PH2"), cleaned.boundSources.map { it.id })
        assertSame(p, retainDeviceBindings(p, { true }, { true }))
    }
}
