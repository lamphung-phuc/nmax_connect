package com.lampp.nmaxconnect.core.places

import com.lampp.nmaxconnect.core.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlusCodeTest {
    // Google's pin for "RH6J+F7M Camping Đồi Bù, Xuân Mai, Hà Nội" (probed 2026-10-17); VietMap names it 7PG7RH6J+F7.
    private val doiBu = GeoPoint(20.8112089, 105.5806866)

    private fun assertNear(expected: GeoPoint, actual: GeoPoint, degrees: Double = 0.00005) {
        assertEquals(expected.lat, actual.lat, degrees)
        assertEquals(expected.lng, actual.lng, degrees)
    }

    @Test fun fullCodeDecodesToGooglesPin() {
        assertNear(doiBu, PlusCode.decode("7PG7RH6J+F7M"))
        assertNear(doiBu, PlusCode.decode("7PG7RH6J+F7"), 0.0002)
    }

    @Test fun shortCodeIsRecoveredFromANearbyReference() {
        assertNear(doiBu, PlusCode.recover("RH6J+F7M", GeoPoint(21.0285, 105.8542))) // Hoàn Kiếm, ~30 km away
        assertNear(doiBu, PlusCode.recover("RH6J+F7M", GeoPoint(20.4, 105.2))) // across the cell edge
    }

    @Test fun aFarReferenceRecoversTheWrongCell() {
        val wrong = PlusCode.recover("RH6J+F7M", GeoPoint(10.7769, 106.7009)) // from Sài Gòn
        assertTrue(wrong.lat < 11.5) // ~1,100 km off: why the result is checked against the shared city
    }

    @Test fun findsCodesInNamesOnly() {
        assertEquals("RH6J+F7M", PlusCode.find("RH6J+F7M Camping Đồi Bù"))
        assertEquals("7PG7RH6J+F7", PlusCode.find("7PG7RH6J+F7 Xã Nam Phương Tiến"))
        assertNull(PlusCode.find("Khách sạn Lotte Hà Nội, 54 P. Liễu Giai"))
    }
}
