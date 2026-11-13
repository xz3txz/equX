package com.xz3t.equX

import com.xz3t.equX.data.SpeedSource
import com.xz3t.equX.data.resolveSpeedSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedSourceTest {

    @Test
    fun `auto prefers ecarx, then vhal, then gps`() {
        assertEquals(SpeedSource.ECARX, resolveSpeedSource(SpeedSource.AUTO, ecarxDelivered = true, vhalDelivered = true))
        assertEquals(SpeedSource.VHAL, resolveSpeedSource(SpeedSource.AUTO, ecarxDelivered = false, vhalDelivered = true))
        assertEquals(SpeedSource.GPS, resolveSpeedSource(SpeedSource.AUTO, ecarxDelivered = false, vhalDelivered = false))
    }

    @Test
    fun `explicit choice is never replaced by another source`() {
        for (source in listOf(SpeedSource.ECARX, SpeedSource.VHAL, SpeedSource.GPS)) {
            assertEquals(source, resolveSpeedSource(source, ecarxDelivered = false, vhalDelivered = false))
            assertEquals(source, resolveSpeedSource(source, ecarxDelivered = true, vhalDelivered = true))
        }
    }
}
