package com.lampp.nmaxconnect.core.bike.ccu

import org.junit.Assert.assertEquals
import org.junit.Test

class DashPhoneStatusTest {
    @Test fun signalBarsShiftByOneAndNoServiceIsZero() {
        assertEquals(1, dashSignal(0, inService = true))
        assertEquals(5, dashSignal(4, inService = true))
        assertEquals(5, dashSignal(9, inService = true))
        assertEquals(0, dashSignal(3, inService = false))
        assertEquals(0, dashSignal(null, inService = true))
    }

    @Test fun thermalStatusBucketsLikeYConnect() {
        assertEquals(listOf(0, 0, 0, 1, 2, 2, 2), (0..6).map(::dashThermal))
    }

    @Test fun percentHandlesZeroMax() {
        assertEquals(46, percent(7, 15))
        assertEquals(0, percent(3, 0))
    }
}
