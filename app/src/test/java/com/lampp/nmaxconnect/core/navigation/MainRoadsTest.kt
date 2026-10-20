package com.lampp.nmaxconnect.core.navigation

import com.lampp.nmaxconnect.core.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainRoadsTest {

    /** A straight line north along 106.7°E, cut into (street, metres) stretches; ~1 m per 0.000009°. */
    private fun route(timeMs: Long, vararg stretches: Pair<String, Double>): Route {
        val total = stretches.sumOf { it.second }
        val geometry = (0..10).map { GeoPoint(lat = 10.0 + total * it / 10 / 111_195.0, lng = 106.7) }
        val instructions = stretches.map { (street, m) -> RawInstruction(0, "", street, m, (timeMs * m / total).toLong()) } +
            RawInstruction(Maneuver.SIGN_FINISH, "", "", 0.0, 0)
        return Route.of(geometry, total, timeMs, instructions)
    }

    private val moto = route(600_000, "Hẻm 283 Cách Mạng Tháng 8" to 1000.0, "Võ Thị Sáu" to 1000.0)
    private val car = route(480_000, "Ngõ 9 Liễu Giai" to 50.0, "Đường 3 Tháng 2" to 1500.0, "" to 50.0, "Sư Vạn Hạnh" to 500.0)

    @Test fun alleysAreNamedHemNgoNgachKietOrUnnamed() {
        listOf("Hẻm 562 Lê Quang Định", "ngõ 16 Hoàng Cầu", "Ngách 34/68 Hoàng Cầu", "Kiệt 12 Lê Lợi", "Ngõ", "", " ").forEach {
            assertTrue(it, MainRoads.isAlley(it))
        }
        listOf("Lê Quang Định", "Đường 3 Tháng 2", "Ngô Quyền", "Cầu Vượt Nguyễn Chí Thanh Kim Mã").forEach {
            assertFalse(it, MainRoads.isAlley(it))
        }
        assertEquals(0.5, MainRoads.alleyShare(moto), 0.01)
    }

    @Test fun checkpointsSitMidwayAlongEachMainRoadStretch() {
        val vias = MainRoads.checkpoints(car)!!
        assertEquals(2, vias.size) // the 50 m alley and the unnamed stretch aren't checked
        val (threeFeb, suVanHanh) = car.maneuvers[1] to car.maneuvers[3]
        assertEquals(car.pointAt(threeFeb.atMeters + threeFeb.lengthMeters / 2), vias[0])
        assertEquals(car.pointAt(suVanHanh.atMeters + suVanHanh.lengthMeters / 2), vias[1])
        assertEquals(10.0 + 800 / 111_195.0, vias[0].lat, 1e-6) // 50 m alley + half of 1500 m
        assertEquals(car.geometry.first(), car.pointAt(-5.0))
        assertEquals(car.geometry.last(), car.pointAt(1e9))
    }

    @Test fun tooManyStretchesToCheckInOneCallKeepsTheMotorcycleRoute() {
        val long = route(1, *Array(MainRoads.MAX_CHECKPOINTS + 1) { "Quốc Lộ 1" to 200.0 })
        assertNull(MainRoads.checkpoints(long))
    }

    @Test fun acceptedCarRouteIsTimedAtMotorbikeSpeed() {
        val check = route(660_000, "Đường 3 Tháng 2" to 2150.0)
        val main = MainRoads.accept(car, check, moto)!!
        assertTrue(main.mainRoads)
        assertEquals(car.geometry, main.geometry)
        assertEquals(660_000, main.timeMs)
        assertEquals(car.maneuvers[1].timeMs * 660 / 480, main.maneuvers[1].timeMs)
    }

    @Test fun rejectsStrayCheckDetourOrNoFewerAlleys() {
        val strayed = route(700_000, "Đường 3 Tháng 2" to 2500.0) // > 1.1 × 2100 m: went round a closed road
        assertNull(MainRoads.accept(car, strayed, moto))
        val ok = route(660_000, "Đường 3 Tháng 2" to 2150.0)
        val shortMoto = route(300_000, "Hẻm 1" to 800.0, "Võ Thị Sáu" to 800.0) // car 2100 m > 1.25 × 1600 m
        assertNull(MainRoads.accept(car, ok, shortMoto))
        val cleanMoto = route(500_000, "Võ Thị Sáu" to 2000.0)
        assertNull(MainRoads.accept(car, ok, cleanMoto))
    }
}
