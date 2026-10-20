package com.lampp.nmaxconnect.core.vietmap

import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.places.PlacesRepository
import com.lampp.nmaxconnect.core.places.SavedPlace
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class VietMapSearchTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Trimmed from docs/vietmap-agent-docs/search-geocoding.txt §1 and §4.
    private val autocompleteJson = """
        [{"ref_id":"geocode:RAkPcicmZ3d-NQhac2kADHYlbFA...","distance":0.069,
          "address":"Phường 4,Quận 5,Thành Phố Hồ Chí Minh","name":"197 Trần Phú",
          "display":"197 Trần Phú Phường 4,Quận 5,Thành Phố Hồ Chí Minh",
          "boundaries":[{"type":2,"id":656652,"name":"4","prefix":"Phường","full_name":"Phường 4"}],
          "categories":[],"entry_points":[],"data_new":{"ref_id":"x"},"data_old":null}]
    """.trimIndent()

    private val placeJson = """
        {"display":"197 Đường Trần Phú,Phường Chợ Quán,Thành Phố Hồ Chí Minh","name":"","hs_num":"197",
         "street":"Đường Trần Phú","address":"197 Đường Trần Phú","city_id":12,"city":"Thành Phố Hồ Chí Minh",
         "district_id":0,"district":"","ward_id":18700,"ward":"Phường Chợ Quán","lat":10.759222947,"lng":106.675902691}
    """.trimIndent()

    @Test fun parsesDocumentedAutocompleteResponse() {
        val s = json.decodeFromString<List<PlaceSuggestion>>(autocompleteJson).single()
        assertEquals("geocode:RAkPcicmZ3d-NQhac2kADHYlbFA...", s.refId)
        assertEquals("197 Trần Phú", s.name)
        assertEquals(0.069, s.distance!!, 1e-9)
    }

    @Test fun parsesDocumentedPlaceResponse() {
        val p = json.decodeFromString<PlaceDetail>(placeJson)
        assertEquals(10.759222947, p.lat, 1e-9)
        assertEquals(106.675902691, p.lng, 1e-9)
        assertEquals("197 Đường Trần Phú", p.address)
    }

    @Test fun proxyUrlKeepsLatLngOrderAndRefIdVerbatim() {
        val base = "https://proxy.example.workers.dev".toHttpUrl()
        val auto = VietMapProxyClient.proxyUrl(
            base,
            "autocomplete",
            listOf("text" to "197 trần phú", "focus" to GeoPoint(10.7589, 106.6754).toQuery(), "display_type" to "6"),
        )
        assertEquals("/autocomplete", auto.encodedPath)
        assertEquals("10.7589,106.6754", auto.queryParameter("focus"))
        assertEquals("197 trần phú", auto.queryParameter("text"))

        val refId = "auto:RAkP/ci+mZ3d=&x"
        assertEquals(refId, VietMapProxyClient.proxyUrl(base, "place", listOf("refid" to refId)).queryParameter("refid"))
    }

    @Test fun recentsAreNewestFirstDedupedAndCapped() {
        fun place(i: Int) = SavedPlace("P$i", "", GeoPoint(10.0 + i * 0.01, 106.0))
        val many = (1..PlacesRepository.MAX_RECENTS).map(::place)
        val updated = PlacesRepository.withRecent(many, place(5).copy(name = "again"))
        assertEquals("again", updated.first().name)
        assertEquals(PlacesRepository.MAX_RECENTS, updated.size)
        assertEquals("P5 moved to the front, not duplicated", 1, updated.count { it.point == place(5).point })
        assertEquals(PlacesRepository.MAX_RECENTS, PlacesRepository.withRecent(many, place(99)).size)
    }
}
