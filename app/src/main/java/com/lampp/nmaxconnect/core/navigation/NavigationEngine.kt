package com.lampp.nmaxconnect.core.navigation

import com.lampp.nmaxconnect.core.location.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * One GPS fix. [timeMs] is the fix time (monotonic or wall clock, only differences matter);
 * [accuracyMeters] is the receiver's 68 % radius.
 */
data class Fix(
    val point: GeoPoint,
    val bearing: Float? = null,
    val timeMs: Long,
    val speedMps: Float? = null,
    val accuracyMeters: Float? = null,
)

data class NavProgress(
    /** Instruction being followed now (its street is the current road). */
    val current: Maneuver,
    /** Upcoming instruction (the next turn); null only on a degenerate one-instruction route. */
    val next: Maneuver?,
    val distanceToNextMeters: Double,
    val remainingMeters: Double,
    val remainingTimeMs: Long,
    /** Distance from the route line right now. */
    val offsetMeters: Double,
    /** True once the rider has been off the route for long enough to reroute. */
    val offRoute: Boolean,
    val arrived: Boolean,
    val snapped: GeoPoint,
    /** Angle between the GPS bearing and the route's direction here, 0..180; null when slow or no bearing. */
    val headingOffDegrees: Double? = null,
)

/**
 * Follows one [Route]. Pure Kotlin: feed fixes, read progress. Off-route is either
 * - a turn away: moving, > [turnOffMeters] off the line (or the fix's accuracy, if worse) and heading
 *   > [turnAngleDegrees] away from the route for [turnFixes] fixes in a row; GPS drift along the right road keeps
 *   the road's heading, so this catches a wrong turn in ~2–3 s without false reroutes; or
 * - > [offRouteMeters] for [offRouteMs] (vietmap skill: > ~30 m for > 3 s), for a slow or stopped bike.
 */
class NavigationEngine(
    val route: Route,
    private val offRouteMeters: Double = 30.0,
    private val offRouteMs: Long = 3_000,
    private val arriveMeters: Double = 25.0,
    private val turnOffMeters: Double = 12.0,
    private val turnAngleDegrees: Double = 50.0,
    private val turnFixes: Int = 2,
) {
    private var lastSegment = 0
    private var offSince: Long? = null
    private var awayFixes = 0

    init {
        require(route.geometry.size >= 2 && route.maneuvers.isNotEmpty()) { "Route has no geometry or instructions" }
    }

    fun update(fix: Fix): NavProgress {
        val snap = snap(fix.point)
        lastSegment = snap.segment

        offSince = if (snap.offset > offRouteMeters) offSince ?: fix.timeMs else null
        val headingOff = headingOff(fix, snap)
        val turnMeters = maxOf(turnOffMeters, minOf(fix.accuracyMeters?.toDouble() ?: 0.0, offRouteMeters))
        awayFixes = if (snap.offset > turnMeters && (headingOff ?: 0.0) > turnAngleDegrees) awayFixes + 1 else 0
        val offRoute = awayFixes >= turnFixes || offSince?.let { fix.timeMs - it >= offRouteMs } ?: false

        val along = snap.along
        val index = route.maneuvers.indexOfLast { it.atMeters <= along + 1.0 }.coerceAtLeast(0)
        val current = route.maneuvers[index]
        val next = route.maneuvers.getOrNull(index + 1)
        val remaining = (route.geometryLength - along).coerceAtLeast(0.0)

        val leftInCurrent = ((current.atMeters + current.lengthMeters) - along).coerceAtLeast(0.0)
        val fractionLeft = if (current.lengthMeters > 0) leftInCurrent / current.lengthMeters else 0.0
        val remainingTime = (current.timeMs * fractionLeft).toLong() +
            route.maneuvers.drop(index + 1).sumOf { it.timeMs }

        return NavProgress(
            current = current,
            next = next,
            distanceToNextMeters = next?.let { (it.atMeters - along).coerceAtLeast(0.0) } ?: remaining,
            remainingMeters = remaining,
            remainingTimeMs = remainingTime,
            offsetMeters = snap.offset,
            offRoute = offRoute,
            arrived = remaining <= arriveMeters && snap.offset <= offRouteMeters,
            snapped = snap.point,
            headingOffDegrees = headingOff,
        )
    }

    /** GPS bearing vs the route's direction just ahead of the snapped point; null when slow (bearing is noise). */
    private fun headingOff(fix: Fix, snap: Snap): Double? {
        val g = route.geometry
        val bearing = fix.bearing?.takeIf { (fix.speedMps ?: 0f) >= MIN_HEADING_SPEED_MPS }
        // Past a vertex the next leg is what counts: going straight where the route turns is a wrong turn.
        val ahead = bearing?.let { (snap.segment + 1 until g.size).firstOrNull { haversine(snap.point, g[it]) > 3.0 } }
        if (bearing == null || ahead == null) return null
        val diff = abs(bearing - bearingDegrees(snap.point, g[ahead])) % 360
        return if (diff > 180) 360 - diff else diff
    }

    private fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val dx = (b.lng - a.lng) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }

    private data class Snap(val segment: Int, val along: Double, val offset: Double, val point: GeoPoint)

    /**
     * Nearest point on the line. Searches from a little behind the last match onward, so a route
     * that doubles back on itself doesn't snap to the earlier pass.
     */
    private fun snap(p: GeoPoint): Snap {
        val g = route.geometry
        var best: Snap? = null
        for (i in (lastSegment - 2).coerceAtLeast(0) until g.size - 1) {
            val (t, d) = project(p, g[i], g[i + 1])
            if (best == null || d < best.offset) {
                val point = GeoPoint(g[i].lat + t * (g[i + 1].lat - g[i].lat), g[i].lng + t * (g[i + 1].lng - g[i].lng))
                val along = route.cumulative[i] + t * (route.cumulative[i + 1] - route.cumulative[i])
                best = Snap(i, along, d, point)
            }
        }
        return best!!
    }

    /** Projection of [p] on segment a→b in a local flat frame: (t in 0..1, distance m). Fine at route scale. */
    private fun project(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<Double, Double> {
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(a.lat))
        val bx = (b.lng - a.lng) * mPerDegLng
        val by = (b.lat - a.lat) * mPerDegLat
        val px = (p.lng - a.lng) * mPerDegLng
        val py = (p.lat - a.lat) * mPerDegLat
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return t to kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val MIN_HEADING_SPEED_MPS = 2f // ~7 km/h
    }
}
