package com.lampp.nmaxconnect.core.bike

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectDelayTest {
    @Test fun backsOff3sThenUpTo15s() {
        assertEquals(
            listOf(3_000L, 3_000L, 3_000L, 5_000L, 10_000L, 15_000L, 15_000L),
            (1..7).map(BikeLinkRepository::reconnectDelayMs),
        )
    }
}
