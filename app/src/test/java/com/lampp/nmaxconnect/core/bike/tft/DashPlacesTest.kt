package com.lampp.nmaxconnect.core.bike.tft

import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.places.SavedPlace
import com.lampp.nmaxconnect.core.protocol.tft.PoiDirection
import com.lampp.nmaxconnect.core.protocol.tft.PoiList
import com.lampp.nmaxconnect.core.protocol.tft.TftMessages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DashPlacesTest {
    private val here = GeoPoint(10.7726, 106.6980)
    private val north = GeoPoint(10.7826, 106.6980)
    private val east = GeoPoint(10.7726, 106.7080)

    @Test fun directionIsRelativeToHeading() {
        assertEquals(PoiDirection.FRONT, DashPlaces.direction(here, north, 0.0))
        assertEquals(PoiDirection.RIGHT, DashPlaces.direction(here, east, 0.0))
        assertEquals(PoiDirection.LEFT, DashPlaces.direction(here, north, 90.0))
        assertEquals(PoiDirection.BACK, DashPlaces.direction(here, north, 180.0))
        assertEquals(PoiDirection.FRONT_RIGHT, DashPlaces.direction(here, north, 315.0))
        assertEquals(PoiDirection.FRONT, DashPlaces.direction(here, north, 350.0)) // wraps past 360
    }

    @Test fun listIsHeaderThenOneItemPerPlace() {
        val places = listOf(SavedPlace("Nha", "Q1", north), SavedPlace("Cay xang", "Q1", east))
        val packets = DashPlaces.poiPackets(PoiList.GAS_STATIONS, 1, places, here, 0.0)

        assertEquals(3, packets.size)
        assertArrayEquals(TftMessages.poiListHeader(PoiList.GAS_STATIONS, 2), packets[0])
        // ~1.1 km due east → item 1 of list 1, arrow right
        assertArrayEquals(TftMessages.poiItem(PoiList.GAS_STATIONS, 1, 1, PoiDirection.RIGHT, 1.1f, "km", "Cay xang"), packets[2])
    }

    @Test fun withoutPositionArrowIsUndefined() {
        val packets = DashPlaces.poiPackets(PoiList.FAVORITES, 0, listOf(SavedPlace("Nha", "Q1", north)), null, 0.0)
        assertArrayEquals(TftMessages.poiItem(PoiList.FAVORITES, 0, 0, PoiDirection.UNDEFINED, 0f, "", "Nha"), packets[1])
    }
}
