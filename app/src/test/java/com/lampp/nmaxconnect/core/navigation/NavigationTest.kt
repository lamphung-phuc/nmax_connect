package com.lampp.nmaxconnect.core.navigation

import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.protocol.tft.NaviLiteDecoder
import com.lampp.nmaxconnect.core.protocol.tft.TftService
import com.lampp.nmaxconnect.core.vietmap.RouteResponseDto
import com.lampp.nmaxconnect.core.vietmap.toRoutes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a real Route v4 response (Bến Thành → Công xã Paris, via the proxy) as fixture. */
class NavigationTest {

    private val route: Route = javaClass.getResource("/route_ben_thanh.json")!!.readText()
        .let { Json { ignoreUnknownKeys = true }.decodeFromString<RouteResponseDto>(it) }
        .toRoutes().first()

    /** Point [meters] along the route geometry. */
    private fun pointAt(meters: Double): GeoPoint {
        val i = route.cumulative.indexOfLast { it <= meters }.coerceIn(0, route.geometry.size - 2)
        val t = (meters - route.cumulative[i]) / (route.cumulative[i + 1] - route.cumulative[i])
        val a = route.geometry[i]
        val b = route.geometry[i + 1]
        return GeoPoint(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
    }

    @Test fun parsesRealResponseIntoLatLngAndScaledManeuvers() {
        assertEquals(38, route.geometry.size)
        assertEquals(GeoPoint(lat = 10.772212, lng = 106.697548), route.geometry.first()) // [lng,lat] flipped
        assertEquals(10, route.maneuvers.size)
        assertTrue(route.maneuvers.last().isFinish)
        assertEquals(route.geometryLength, route.maneuvers.last().atMeters, 1.0)
        assertEquals("Lý Tự Trọng", route.maneuvers[3].street)
        assertEquals(1.8 + 141 + 183, route.maneuvers[3].atMeters, 1.0)
    }

    /** Live `alternative=true&annotations=congestion` response (Bến Thành → Tân Định area): 3 paths. */
    @Test fun parsesAlternativesWithCongestion() {
        val routes = javaClass.getResource("/route_alternatives.json")!!.readText()
            .let { Json { ignoreUnknownKeys = true }.decodeFromString<RouteResponseDto>(it) }
            .toRoutes()
        assertEquals(listOf(5390.0, 6040.0, 5659.5), routes.map { it.distanceMeters })
        routes.forEach { r ->
            assertTrue(r.traffic.isNotEmpty())
            assertTrue(r.traffic.all { it.first <= it.last && it.last < r.geometry.size })
            assertTrue(r.trafficShare(Congestion.LOW) > 0.99) // spans cover the whole line
            assertTrue(r.trafficShare(Congestion.SEVERE) <= r.trafficShare(Congestion.HEAVY))
        }
        assertEquals(Congestion.HEAVY, routes[0].traffic[2].level) // {"value":"heavy","first":2,"last":5}
    }

    @Test fun trafficShareIsLengthWeighted() {
        val line = listOf(GeoPoint(10.0, 106.0), GeoPoint(10.001, 106.0), GeoPoint(10.004, 106.0)) // ~111 m + ~333 m
        val r = Route.of(line, 444.0, 60_000, emptyList(), traffic = listOf(TrafficSpan(1, 2, Congestion.HEAVY)))
        assertEquals(0.75, r.trafficShare(Congestion.HEAVY), 0.01)
        assertEquals(0.0, r.trafficShare(Congestion.SEVERE), 0.0)
    }

    @Test fun progressAlongTheRoute() {
        val engine = NavigationEngine(route)
        // 100 m into the "turn right onto Nguyễn An Ninh" leg (starts at 1.8 m, 141 m long)
        val p = engine.update(Fix(pointAt(101.8), timeMs = 0))
        assertEquals("Nguyễn An Ninh", p.current.street)
        assertEquals("Trương Định", p.next!!.street)
        assertEquals(41.0, p.distanceToNextMeters, 2.0)
        assertEquals(route.geometryLength - 101.8, p.remainingMeters, 2.0)
        assertTrue(p.offsetMeters < 1)
        assertFalse(p.offRoute)
        assertFalse(p.arrived)
    }

    @Test fun aheadOfDropsThePassedLine() {
        val p = NavigationEngine(route).update(Fix(pointAt(300.0), timeMs = 0))
        val ahead = route.aheadOf(p)
        assertEquals(p.snapped, ahead.first())
        assertEquals(route.geometry.last(), ahead.last())
        assertEquals(route.cumulative.count { it > 300.0 } + 1, ahead.size)
    }

    @Test fun offRouteOnlyAfterStayingAwayLongEnough() {
        val engine = NavigationEngine(route)
        val far = GeoPoint(route.geometry[5].lat + 0.001, route.geometry[5].lng) // ~110 m off the line
        assertFalse(engine.update(Fix(far, timeMs = 0)).offRoute)
        assertFalse(engine.update(Fix(far, timeMs = 2_000)).offRoute)
        assertTrue(engine.update(Fix(far, timeMs = 3_100)).offRoute)
        assertFalse("back on the line resets", engine.update(Fix(pointAt(300.0), timeMs = 4_000)).offRoute)
    }

    /** Straight 1.1 km road due north; [eastMeters] puts the rider beside it halfway. */
    private val northRoad = Route.of(
        listOf(GeoPoint(10.0, 106.0), GeoPoint(10.01, 106.0)),
        1_112.0,
        120_000,
        listOf(RawInstruction(0, "", "Road", 1_112.0, 120_000), RawInstruction(4, "", "", 0.0, 0)),
    )

    private fun beside(eastMeters: Double) = GeoPoint(10.005, 106.0 + eastMeters / (111_320.0 * Math.cos(Math.toRadians(10.0))))

    @Test fun turningAwayIsOffRouteAfterTwoFixes() {
        val engine = NavigationEngine(northRoad)
        val first = engine.update(Fix(beside(15.0), bearing = 90f, timeMs = 0, speedMps = 5f))
        assertEquals(90.0, first.headingOffDegrees!!, 1.0)
        assertFalse(first.offRoute)
        assertTrue(engine.update(Fix(beside(20.0), bearing = 90f, timeMs = 1_000, speedMps = 5f)).offRoute)
    }

    @Test fun driftAlongTheRoadIsNotOffRoute() {
        val engine = NavigationEngine(northRoad)
        repeat(5) { i -> assertFalse(engine.update(Fix(beside(15.0), bearing = 5f, timeMs = i * 1_000L, speedMps = 5f)).offRoute) }
    }

    @Test fun headingIsIgnoredWhenSlowOrInaccurate() {
        val slow = NavigationEngine(northRoad)
        repeat(2) { i -> assertFalse(slow.update(Fix(beside(15.0), bearing = 90f, timeMs = i * 1_000L, speedMps = 1f)).offRoute) }
        val vague = NavigationEngine(northRoad)
        repeat(2) { i ->
            assertFalse(vague.update(Fix(beside(15.0), bearing = 90f, timeMs = i * 1_000L, speedMps = 5f, accuracyMeters = 25f)).offRoute)
        }
    }

    @Test fun goingStraightWhereTheRouteTurnsIsOffRoute() {
        // north 555 m, then east; the rider carries on north past the corner
        val corner = Route.of(
            listOf(GeoPoint(10.0, 106.0), GeoPoint(10.005, 106.0), GeoPoint(10.005, 106.005)),
            1_103.0,
            120_000,
            listOf(RawInstruction(0, "", "A", 555.0, 60_000), RawInstruction(2, "", "B", 548.0, 60_000), RawInstruction(4, "", "", 0.0, 0)),
        )
        val engine = NavigationEngine(corner)
        assertFalse(engine.update(Fix(GeoPoint(10.0052, 106.0), bearing = 0f, timeMs = 0, speedMps = 5f)).offRoute) // 22 m past the corner
        assertTrue(engine.update(Fix(GeoPoint(10.0053, 106.0), bearing = 0f, timeMs = 1_000, speedMps = 5f)).offRoute)
    }

    @Test fun reroutePacerBacksOffAndRetriesFailuresSoon() {
        val pacer = ReroutePacer(maxPerMinute = 3)
        assertTrue(pacer.canReroute(0))
        pacer.onAttempt(0, succeeded = true)
        assertFalse(pacer.canReroute(2_999))
        assertTrue(pacer.canReroute(3_000))
        pacer.onAttempt(3_000, succeeded = true)
        assertFalse(pacer.canReroute(8_999))
        assertTrue(pacer.canReroute(9_000))
        pacer.onAttempt(9_000, succeeded = false)
        assertTrue("a failure retries after 2 s", pacer.canReroute(11_000))
        pacer.onAttempt(11_000, succeeded = false)
        assertFalse("then 4 s", pacer.canReroute(14_999))
        assertTrue(pacer.canReroute(15_000))
        pacer.onAttempt(15_000, succeeded = true)
        assertFalse("cap of 3 routes a minute; failures don't count", pacer.canReroute(40_000))
        assertTrue("quiet minute: immediate again", pacer.canReroute(75_000))
    }

    @Test fun arrivesNearTheEnd() {
        val engine = NavigationEngine(route)
        assertTrue(engine.update(Fix(pointAt(route.geometryLength - 10), timeMs = 0)).arrived)
    }

    @Test fun turnIconsFollowTheSkillTable() {
        fun m(sign: Int, text: String = "") = Maneuver(sign, text, "", 0.0, 0.0, 0)
        assertEquals(
            listOf(32, 34, 6, 8, 7, 35, 33, 0, 0, 14),
            listOf(-3, -2, -1, 0, 1, 2, 3, 4, 5, 6).map { TurnIcons.forManeuver(m(it)) },
        )
        assertEquals(36, TurnIcons.forManeuver(m(-2, "Quay đầu tại Lê Lợi")))
    }

    @Test fun dashDistanceRoundsLikeADash() {
        assertEquals(40f to "m", dashDistance(41.0))
        assertEquals(990f to "m", dashDistance(994.0))
        assertEquals(1.2f to "km", dashDistance(1_234.0))
    }

    @Test fun mapperSendsOnlyChanges() {
        val mapper = DashNavMapper(ZoneOffset.ofHours(7))
        val engine = NavigationEngine(route)
        val first = mapper.update(engine.update(Fix(pointAt(101.8), timeMs = 0)), nowMs = 0)
        assertEquals(
            listOf(TftService.NAVIGATION_STATUS, TftService.NEXT_TURN_DIST, TftService.ETA, TftService.CUR_ROAD_NAME),
            first.map(::service),
        )
        // Next turn is "turn right onto Trương Định" → icon 35, 40 m
        val turn = NaviLiteDecoder().feed(first[1]).single().payload
        assertEquals(35, turn[0].toInt())
        assertEquals(40f, ByteBuffer.wrap(turn, 1, 4).order(ByteOrder.LITTLE_ENDIAN).float)

        assertEquals(
            "same position, same minute: nothing to send",
            emptyList<Int>(),
            mapper.update(engine.update(Fix(pointAt(101.8), timeMs = 1_000)), nowMs = 1_000).map(::service),
        )
        assertEquals(
            listOf(TftService.NEXT_TURN_DIST),
            mapper.update(engine.update(Fix(pointAt(121.8), timeMs = 2_000)), nowMs = 2_000).map(::service),
        )

        assertEquals(listOf(TftService.NAVIGATION_STATUS), mapper.stop().map(::service))
        assertEquals(emptyList<Int>(), mapper.stop().map(::service))
    }

    private fun service(packet: ByteArray) = packet[6].toInt() and 0xFF
}
