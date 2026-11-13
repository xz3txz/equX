package com.xz3t.equX

import com.xz3t.equX.data.AppBinding
import com.xz3t.equX.data.AudioDeviceBinding
import com.xz3t.equX.data.BindingConflictType
import com.xz3t.equX.data.PresetModel
import com.xz3t.equX.data.checkAppRemovalConflict
import com.xz3t.equX.data.checkBindingConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetConflictTest {

    private fun device(id: String) = AudioDeviceBinding(id, "device-$id", "BLUETOOTH")
    private fun app(pkg: String) = AppBinding(pkg, "app-$pkg")

    private fun preset(
        name: String,
        devices: List<String> = emptyList(),
        apps: List<String> = emptyList(),
    ) = PresetModel(
        presetName = name,
        rawBoundDevices = devices.map(::device),
        rawBoundApps = apps.map(::app),
    )

    // --- GLOBAL_DEVICE_DUPLICATE (Правило 2) ---

    @Test
    fun `global device duplicate when both presets are global for same device`() {
        val target = preset("Target", devices = listOf("BT1"))
        val other = preset("Other", devices = listOf("BT1"))

        val conflict = checkBindingConflict(target, listOf(other), app = null, device = null)

        assertEquals(BindingConflictType.GLOBAL_DEVICE_DUPLICATE, conflict?.type)
        assertEquals("Other", conflict?.conflictingPreset?.presetName)
        assertEquals("BT1", conflict?.conflictingDevice?.id)
    }

    // --- CANNOT_BE_GLOBAL_HAS_APPS (Правило 1) ---

    @Test
    fun `cannot be global when other preset already has apps for device`() {
        val target = preset("Target")
        val other = preset("Other", devices = listOf("BT1"), apps = listOf("com.example.music"))

        // Пытаемся привязать BT1 как глобальное (без приложений) к target.
        val conflict = checkBindingConflict(target, listOf(other), app = null, device = device("BT1"))

        assertEquals(BindingConflictType.CANNOT_BE_GLOBAL_HAS_APPS, conflict?.type)
        assertEquals("Other", conflict?.conflictingPreset?.presetName)
    }

    // --- CANNOT_BIND_DEVICE_OTHER_IS_GLOBAL (Правило 1) ---

    @Test
    fun `cannot bind device when other preset is global for it`() {
        val target = preset("Target")
        val other = preset("Other", devices = listOf("BT1"))

        // Пытаемся привязать BT1 + приложение к target, но BT1 уже глобальный в other.
        val conflict = checkBindingConflict(
            target,
            listOf(other),
            app = app("com.example.music"),
            device = device("BT1"),
        )

        assertEquals(BindingConflictType.CANNOT_BIND_DEVICE_OTHER_IS_GLOBAL, conflict?.type)
    }

    // --- APP_DEVICE_PAIR_DUPLICATE ---

    @Test
    fun `app device pair duplicate when same pair exists in other preset`() {
        val target = preset("Target", devices = listOf("BT1"))
        val other = preset(
            "Other",
            devices = listOf("BT1"),
            apps = listOf("com.example.music"),
        )

        val conflict = checkBindingConflict(
            target,
            listOf(other),
            app = app("com.example.music"),
            device = device("BT1"),
        )

        assertEquals(BindingConflictType.APP_DEVICE_PAIR_DUPLICATE, conflict?.type)
        assertEquals("com.example.music", conflict?.conflictingApp?.packageName)
    }

    @Test
    fun `no conflict when same device but different app`() {
        val target = preset("Target", devices = listOf("BT1"))
        val other = preset(
            "Other",
            devices = listOf("BT1"),
            apps = listOf("com.example.music"),
        )

        val conflict = checkBindingConflict(
            target,
            listOf(other),
            app = app("com.example.video"),
            device = device("BT1"),
        )

        assertNull(conflict)
    }

    @Test
    fun `no conflict when different device same app`() {
        val target = preset("Target", devices = listOf("BT1"))
        val other = preset(
            "Other",
            devices = listOf("BT2"),
            apps = listOf("com.example.music"),
        )

        val conflict = checkBindingConflict(
            target,
            listOf(other),
            app = app("com.example.music"),
            device = device("BT1"),
        )

        assertNull(conflict)
    }

    // --- App removal ---

    @Test
    fun `removing last app returns global duplicate when other global preset exists`() {
        val target = preset("Target", devices = listOf("BT1"), apps = listOf("com.example.music"))
        val other = preset("Other", devices = listOf("BT1"))

        val conflict = checkAppRemovalConflict(target, listOf(other), app("com.example.music"))

        assertEquals(BindingConflictType.GLOBAL_DEVICE_DUPLICATE, conflict?.type)
    }

    @Test
    fun `removing last app returns cannot be global when other preset has apps`() {
        val target = preset("Target", devices = listOf("BT1"), apps = listOf("com.example.music"))
        val other = preset("Other", devices = listOf("BT1"), apps = listOf("com.example.video"))

        val conflict = checkAppRemovalConflict(target, listOf(other), app("com.example.music"))

        assertEquals(BindingConflictType.CANNOT_BE_GLOBAL_HAS_APPS, conflict?.type)
    }

    @Test
    fun `removing non-last app returns no conflict`() {
        val target = preset(
            "Target",
            devices = listOf("BT1"),
            apps = listOf("com.example.music", "com.example.video"),
        )
        val other = preset("Other", devices = listOf("BT1"))

        val conflict = checkAppRemovalConflict(target, listOf(other), app("com.example.music"))

        assertNull(conflict)
    }
}
