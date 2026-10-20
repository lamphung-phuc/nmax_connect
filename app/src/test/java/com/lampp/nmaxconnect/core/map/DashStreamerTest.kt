package com.lampp.nmaxconnect.core.map

import org.junit.Assert.assertEquals
import org.junit.Test

class DashStreamerTest {
    @Test
    fun `zoom follows speed stops and interpolates between them`() {
        assertEquals(17.0, DashStreamer.zoomForSpeed(0f), 1e-9)
        assertEquals(15.5, DashStreamer.zoomForSpeed(30 / 3.6f), 1e-3)
        assertEquals(15.25, DashStreamer.zoomForSpeed(40 / 3.6f), 1e-3)
        assertEquals(14.5, DashStreamer.zoomForSpeed(100 / 3.6f), 1e-9)
    }
}
