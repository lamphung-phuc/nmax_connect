package com.lampp.nmaxconnect.core.vietmap

import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.navigation.Maneuver
import com.lampp.nmaxconnect.core.navigation.RawInstruction
import com.lampp.nmaxconnect.core.navigation.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePickerTest {

    /** Same straight-line routes as MainRoadsTest: the car one is mostly main roads, the moto one half alleys. */
    private fun route(timeMs: Long, vararg stretches: Pair<String, Double>): Route {
        val total = stretches.sumOf { it.second }
        val geometry = (0..10).map { GeoPoint(lat = 10.0 + total * it / 10 / 111_195.0, lng = 106.7) }
        val instructions = stretches.map { (street, m) -> RawInstruction(0, "", street, m, (timeMs * m / total).toLong()) } +
            RawInstruction(Maneuver.SIGN_FINISH, "", "", 0.0, 0)
        return Route.of(geometry, total, timeMs, instructions)
    }

    private val moto = route(600_000, "Hẻm 283 Cách Mạng Tháng 8" to 1000.0, "Võ Thị Sáu" to 1000.0)
    private val car = route(480_000, "Ngõ 9 Liễu Giai" to 50.0, "Đường 3 Tháng 2" to 1500.0, "" to 50.0, "Sư Vạn Hạnh" to 500.0)
    private val check = route(660_000, "Đường 3 Tháng 2" to 2150.0)
    private val a = GeoPoint(10.0, 106.7)
    private val b = GeoPoint(10.02, 106.7)

    /** Fake VietMap: car call, check call (through vias), or plain motorcycle call. */
    private fun picker(carDelayMs: Long = 0, checkFails: Boolean = false) = RoutePicker({ points, extra ->
        when {
            ("vehicle" to "car") in extra -> {
                delay(carDelayMs)
                listOf(car)
            }
            points.size > 2 -> if (checkFails) throw VietMapException("HTTP 400", 400) else listOf(check)
            else -> listOf(moto)
        }
    }) {}

    @Test fun checkedMainRoadsWin() = runTest {
        assertTrue(picker().route(a, b, null).mainRoads)
        assertEquals(listOf(true, false), picker().routes(a, b).map { it.mainRoads })
    }

    @Test fun failedCheckCallFallsBackToTheMotorcycleRoute() = runTest {
        assertSame(moto, picker(checkFails = true).route(a, b, 90f))
        assertEquals(listOf(moto), picker(checkFails = true).routes(a, b))
    }

    @Test fun slowMainRoadsCheckDoesNotHoldUpAReroute() = runTest {
        val picked = picker(carDelayMs = 10_000).route(a, b, null)
        assertSame(moto, picked)
        assertFalse(picked.mainRoads)
        assertEquals(RoutePicker.REROUTE_MAIN_ROADS_MS, currentTime)
    }
}
