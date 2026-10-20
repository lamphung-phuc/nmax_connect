package com.lampp.nmaxconnect.core.map

import com.lampp.nmaxconnect.core.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCorridorTest {
    /** ~10 km zigzag, a vertex every ~110 m. */
    private val route = List(91) { i -> GeoPoint(10.0 + i * 0.001, 106.0 + (if (i / 10 % 2 == 0) i % 10 else 10 - i % 10) * 0.001) }

    @Test fun boxesCoverEverySegmentWithPadding() {
        val boxes = corridorBoxes(route, chunkMeters = 1_000.0, padMeters = 300.0)
        assertTrue(boxes.size in 10..16)
        route.zipWithNext().forEach { (a, b) ->
            assertTrue("segment $a-$b", boxes.any { it.holds(a, 0.0025) && it.holds(b, 0.0025) })
        }
    }

    @Test fun twoPointsMakeOneBox() {
        assertEquals(1, corridorBoxes(listOf(GeoPoint(10.0, 106.0), GeoPoint(10.001, 106.0))).size)
    }

    /** [p] inside with at least [margin] degrees to spare (300 m ≈ 0.0027°). */
    private fun GeoBox.holds(p: GeoPoint, margin: Double) =
        p.lat - south >= margin && north - p.lat >= margin && p.lng - west >= margin && east - p.lng >= margin
}
