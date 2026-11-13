package com.xz3t.equX

import com.xz3t.equX.data.PresetNameError
import com.xz3t.equX.data.presetNameError
import com.xz3t.equX.data.sanitizePresetName
import com.xz3t.equX.data.suggestPresetName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetNamingTest {

    private val taken = listOf("По умолчанию", "Default", "Rock", "Rock_2", "Jazz")

    @Test
    fun `valid free name has no error`() {
        assertNull(presetNameError("  Pop 2.0 ", taken))
    }

    @Test
    fun `empty name is error`() {
        assertEquals(PresetNameError.EMPTY, presetNameError("   ", taken))
    }

    @Test
    fun `every forbidden char is rejected`() {
        for (c in "/\\:*?\"<>|") {
            assertEquals("char $c", PresetNameError.FORBIDDEN_CHARS, presetNameError("A${c}B", taken))
        }
    }

    @Test
    fun `trailing dot is rejected but inner dot is fine`() {
        assertEquals(PresetNameError.TRAILING_DOT, presetNameError("Rock.", taken))
        assertNull(presetNameError("v1.2", taken))
    }

    @Test
    fun `taken check ignores case and covers built-in name`() {
        assertEquals(PresetNameError.TAKEN, presetNameError("rock", taken))
        assertEquals(PresetNameError.TAKEN, presetNameError("default", taken))
        assertEquals(PresetNameError.TAKEN, presetNameError("по умолчанию", taken))
    }

    @Test
    fun `own name is not taken on rename, case change allowed`() {
        assertNull(presetNameError("ROCK", taken, ownName = "Rock"))
        assertEquals(PresetNameError.TAKEN, presetNameError("Jazz", taken, ownName = "Rock"))
    }

    @Test
    fun `sanitize replaces forbidden chars and trims trailing dots`() {
        assertEquals("Rock_Live", sanitizePresetName("Rock:Live", "X"))
        assertEquals("A_B", sanitizePresetName(" A/B. . ", "X"))
        assertEquals("X", sanitizePresetName(" ... ", "X"))
    }

    @Test
    fun `suggest keeps free name`() {
        assertEquals("Pop", suggestPresetName("Pop", taken, "X"))
    }

    @Test
    fun `suggest appends suffix for taken name`() {
        assertEquals("Jazz_2", suggestPresetName("Jazz", taken, "X"))
        assertEquals("rock_3", suggestPresetName("rock", taken, "X"))
    }

    @Test
    fun `suggest continues existing numeric suffix`() {
        assertEquals("Rock_3", suggestPresetName("Rock_2", taken, "X"))
        assertEquals("Track_1999", suggestPresetName("Track_1999", taken, "X"))
        assertEquals("Track_2000", suggestPresetName("Track_1999", taken + "Track_1999", "X"))
    }

    @Test
    fun `suggest sanitizes before checking`() {
        assertEquals("Rock_Live", suggestPresetName("Rock:Live", taken, "X"))
        assertEquals("Rock_3", suggestPresetName("Rock_2.", taken, "X"))
    }
}
