package com.xz3t.equX

import com.xz3t.equX.data.formatFrequencyShort
import com.xz3t.equX.data.parseFrequencyInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrequencyFormattingTest {

    @Test
    fun `parses plain number`() {
        assertEquals(16000f, parseFrequencyInput("16000")!!, 0.001f)
    }

    @Test
    fun `parses k suffix latin`() {
        assertEquals(16000f, parseFrequencyInput("16k")!!, 0.001f)
    }

    @Test
    fun `parses k suffix cyrillic`() {
        assertEquals(16000f, parseFrequencyInput("16к")!!, 0.001f)
    }

    @Test
    fun `parses hz suffix`() {
        assertEquals(16000f, parseFrequencyInput("16000hz")!!, 0.001f)
    }

    @Test
    fun `parses cyrillic hz suffix`() {
        assertEquals(16000f, parseFrequencyInput("16000гц")!!, 0.001f)
    }

    @Test
    fun `parses fractional k`() {
        assertEquals(1500f, parseFrequencyInput("1.5k")!!, 0.001f)
    }

    @Test
    fun `returns null for garbage`() {
        assertNull(parseFrequencyInput("abc"))
    }

    @Test
    fun `formats sub-khz as integer`() {
        assertEquals("500", formatFrequencyShort(500f))
    }

    @Test
    fun `formats exact khz without decimal`() {
        assertEquals("1k", formatFrequencyShort(1000f))
        assertEquals("16k", formatFrequencyShort(16000f))
    }

    @Test
    fun `formats fractional khz with one decimal`() {
        assertEquals("1.5k", formatFrequencyShort(1500f))
    }
}
