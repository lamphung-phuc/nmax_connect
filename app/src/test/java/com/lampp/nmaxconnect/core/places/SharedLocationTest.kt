package com.lampp.nmaxconnect.core.places

import com.lampp.nmaxconnect.core.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLocationTest {

    @Test fun placeLinkUsesThePinNotTheCameraCentre() {
        val shared = SharedLocation.parse(
            "Chợ Bến Thành\nLê Lợi, Bến Thành, Quận 1\n" +
                "https://www.google.com/maps/place/Ch%E1%BB%A3+B%E1%BA%BFn+Th%C3%A0nh/@10.7700,106.6950,17z/" +
                "data=!3m1!4b1!4m6!3m5!1s0x31752f3f:0x9a1d!8m2!3d10.7725!4d106.6980!16s",
        )
        assertEquals(SharedLocation("Chợ Bến Thành", "Lê Lợi, Bến Thành, Quận 1", GeoPoint(10.7725, 106.6980)), shared)
    }

    @Test fun droppedPinLinksGiveTheirCoordinates() {
        assertEquals(GeoPoint(10.7769, 106.7009), SharedLocation.parse("https://maps.google.com/?q=10.7769,106.7009").point)
        val search = "https://www.google.com/maps/search/21.0285,+105.8542?entry=tts"
        assertEquals(GeoPoint(21.0285, 105.8542), SharedLocation.parse(search).point)
        val directions = "https://www.google.com/maps/dir/?api=1&destination=16.0544%2C108.2022"
        assertEquals(GeoPoint(16.0544, 108.2022), SharedLocation.parse(directions).point)
    }

    @Test fun geoUriKeepsItsLabel() {
        assertEquals(SharedLocation("Nhà", null, GeoPoint(10.77, 106.70)), SharedLocation.parse("geo:10.77,106.70?q=10.77,106.70(Nhà)"))
        assertEquals(SharedLocation("Hà Nội", null, null), SharedLocation.parse("geo:0,0?q=H%C3%A0+N%E1%BB%99i"))
    }

    @Test fun nameOnlyLinkHasNoPointSoTheRiderPicksFromSearch() {
        val shared = SharedLocation.parse("https://maps.google.com/?q=Ch%E1%BB%A3+B%E1%BA%BFn+Th%C3%A0nh&ftid=0x31752f3f:0x9a1d&entry=gps")
        assertEquals(SharedLocation("Chợ Bến Thành", null, null), shared)
    }

    @Test fun placeQuerySplitsIntoNameAndAddress() {
        val link = "https://maps.google.com/?q=Lotte+Hotel+Hanoi,+54+Li%E1%BB%85u+Giai,+Ba+%C4%90%C3%ACnh&ftid=0x1:0x2"
        val shared = SharedLocation.parse(link)
        assertEquals(SharedLocation("Lotte Hotel Hanoi", "54 Liễu Giai, Ba Đình", null), shared)
    }

    @Test fun namesMatchOnADistinctiveWordOnly() {
        assertTrue(SharedLocation.sameName("Lotte Hotel Hanoi", "Khách sạn Lotte Hà Nội"))
        assertTrue(SharedLocation.sameName("Chợ Bến Thành", "Cho Ben Thanh"))
        assertFalse(SharedLocation.sameName("Khách sạn Hà Nội Daewoo", "Khách sạn Lotte Hà Nội"))
    }

    // Hits are VietMap's live answers (2026-10-17) for the share's searchText.
    @Test fun vietMapHitMustNameThePlaceInTheSharedCity() {
        val shared = SharedLocation("Khách sạn Lotte Hà Nội", "54 P. Liễu Giai, Cống Vị, Ba Đình, Hà Nội 100000, Vietnam", null)
        assertEquals("Khách sạn Lotte Hà Nội 54 P. Liễu Giai", shared.searchText)
        assertFalse(shared.matches("Khách Sạn Lotte Sài Gòn 2A-4A Tôn Đức Thắng", "Phường Bến Nghé, Quận 1, Thành phố Hồ Chí Minh"))
        assertFalse(shared.matches("Khách Sạn Hanoi Delight", "Phường Đội Cấn, Quận Ba Đình, Thành phố Hà Nội"))
        assertTrue(shared.matches("Lotte Hotel Hanoi", "54 Đào Tấn, Phường Ngọc Khánh, Quận Ba Đình, Thành phố Hà Nội"))
        // No city in the share: nothing can be checked, so nothing is auto-picked.
        assertFalse(SharedLocation("Chợ Bến Thành", null, null).matches("Chợ Bến Thành", "Phường Bến Thành, Quận 1, Thành phố Hồ Chí Minh"))
    }

    @Test fun plusCodeIsKeptApartFromTheName() {
        val shared = SharedLocation.parse(
            "https://maps.google.com/?q=RH6J%2BF7M+Camping+%C4%90%E1%BB%93i+B%C3%B9,+Unnamed+Road,+Xu%C3%A2n+Mai," +
                "+H%C3%A0+N%E1%BB%99i+100000,+Vi%E1%BB%87t+Nam&ftid=0x1:0x2",
        )
        assertEquals(SharedLocation("Camping Đồi Bù", "Unnamed Road, Xuân Mai, Hà Nội 100000, Việt Nam", null, "RH6J+F7M"), shared)
        assertEquals("Xuân Mai, Hà Nội", shared.locality)
        val oneLine = SharedLocation.parse("RH6J+F7M Camping Đồi Bù, Unnamed Road, Xuân Mai, Hà Nội 100000, Việt Nam")
        assertEquals(shared, oneLine)
        // Cell centre: 2 m from Google's pin (20.8112089, 105.5806866), within the 11-digit code's ~3 m cell.
        assertEquals(GeoPoint(20.81121, 105.58067), SharedLocation.parse("7PG7RH6J+F7M Camping Đồi Bù").point?.rounded())
    }

    private fun GeoPoint.rounded() = GeoPoint(Math.round(lat * 1e5) / 1e5, Math.round(lng * 1e5) / 1e5)

    @Test fun cameraCentreIsALastResortOnlyWithoutAName() {
        assertEquals(GeoPoint(10.1, 106.2), SharedLocation.parse("https://www.google.com/maps/@10.1,106.2,15z").point)
        assertNull(SharedLocation.parse("Quán X https://www.google.com/maps/@10.1,106.2,15z").point)
    }

    @Test fun shortLinksAreFoundAndOtherHostsIgnored() {
        val link = "https://maps.app.goo.gl/AbC123?g_st=ac"
        assertEquals(link, SharedLocation.shortLink("Chợ Bến Thành\n$link"))
        assertNull(SharedLocation.shortLink("https://evil.example/maps.app.goo.gl/x"))
    }

    @Test fun onlyGoogleOwnHostsAreFollowed() {
        listOf("maps.app.goo.gl", "goo.gl", "g.co", "www.google.com", "maps.google.com.vn").forEach { assertTrue(it, isGoogleHost(it)) }
        listOf("google.attacker.com", "notgoogle.com", "goo.gl.evil.io", "google.com.evil.io").forEach { assertFalse(it, isGoogleHost(it)) }
    }
}
