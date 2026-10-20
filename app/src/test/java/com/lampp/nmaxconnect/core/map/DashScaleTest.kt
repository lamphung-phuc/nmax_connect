package com.lampp.nmaxconnect.core.map

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashScaleTest {
    private val lat = 10.7725
    private val lng = 106.6980
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `saigon day and night`() {
        assertTrue(sunElevation(lat, lng, at("2026-06-21T05:00:00Z")) > 60) // local noon
        assertFalse(isNight(lat, lng, at("2026-06-21T05:00:00Z")))
        assertTrue(isNight(lat, lng, at("2026-06-21T17:00:00Z"))) // local midnight
        // Sunset in Saigon on 2026-10-16 is ~17:35 local (10:35Z).
        assertFalse(isNight(lat, lng, at("2026-10-16T10:20:00Z")))
        assertTrue(isNight(lat, lng, at("2026-10-16T10:50:00Z")))
    }

    @Test
    fun `scale label rounds down to 1-2-5 steps`() {
        assertEquals("50 m", scaleLabel(17.0, lat)) // ~59 m per 100 px
        assertEquals("200 m", scaleLabel(15.0, lat)) // ~235 m
        assertEquals("1 km", scaleLabel(12.5, lat)) // ~1.3 km
    }
}
