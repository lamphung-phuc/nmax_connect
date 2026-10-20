package com.lampp.nmaxconnect.core.navigation

import com.lampp.nmaxconnect.core.location.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One instruction of a Route v4 path. [atMeters] is where it starts along the route.
 * VietMap's `interval` indices don't line up with the geometry, so position comes from summing
 * instruction distances (which match the path distance).
 */
data class Maneuver(
    val sign: Int,
    val text: String,
    val street: String,
    val atMeters: Double,
    val lengthMeters: Double,
    val timeMs: Long,
) {
    val isFinish get() = sign == SIGN_FINISH

    companion object {
        const val SIGN_FINISH = 4
    }
}

class Route(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val timeMs: Long,
    val maneuvers: List<Maneuver>,
    /** Live congestion along [geometry] (Route v4 `annotations=congestion`); empty when not reported. */
    val traffic: List<TrafficSpan> = emptyList(),
    /** Built by [MainRoads.accept]: the car-profile line, checked rideable by motorbike. */
    val mainRoads: Boolean = false,
) {
    /** Distance from the start to each geometry vertex, metres. */
    val cumulative: DoubleArray = DoubleArray(geometry.size).also { acc ->
        for (i in 1 until geometry.size) acc[i] = acc[i - 1] + haversine(geometry[i - 1], geometry[i])
    }

    val geometryLength: Double get() = cumulative.lastOrNull() ?: 0.0

    /** Fraction (0..1) of the route length with congestion at [atLeast] or worse. */
    fun trafficShare(atLeast: Congestion): Double {
        if (geometryLength <= 0) return 0.0
        val slow = traffic.filter { it.level >= atLeast }.sumOf { s ->
            val last = s.last.coerceIn(0, cumulative.lastIndex)
            (cumulative[last] - cumulative[s.first.coerceIn(0, last)])
        }
        return (slow / geometryLength).coerceIn(0.0, 1.0)
    }

    /** The part of the line still to ride, starting at [progress]'s snapped position. */
    fun aheadOf(progress: NavProgress): List<GeoPoint> {
        val along = geometryLength - progress.remainingMeters
        return listOf(progress.snapped) + geometry.filterIndexed { i, _ -> cumulative[i] > along }
    }

    companion object {
        /** Builds maneuvers from (sign, text, street, length m, time ms), scaled onto the geometry length. */
        fun of(
            geometry: List<GeoPoint>,
            distanceMeters: Double,
            timeMs: Long,
            instructions: List<RawInstruction>,
            traffic: List<TrafficSpan> = emptyList(),
        ): Route {
            val provisional = Route(geometry, distanceMeters, timeMs, emptyList())
            val scale = if (distanceMeters > 0) provisional.geometryLength / distanceMeters else 1.0
            var at = 0.0
            val maneuvers = instructions.map { i ->
                Maneuver(i.sign, i.text, i.street, at * scale, i.lengthMeters * scale, i.timeMs).also { at += i.lengthMeters }
            }
            return Route(geometry, distanceMeters, timeMs, maneuvers, traffic)
        }
    }
}

/** Geometry vertices [first]..[last] have congestion [level]. */
data class TrafficSpan(val first: Int, val last: Int, val level: Congestion)

enum class Congestion { LOW, MODERATE, HEAVY, SEVERE }

data class RawInstruction(val sign: Int, val text: String, val street: String, val lengthMeters: Double, val timeMs: Long)

private const val EARTH_RADIUS_M = 6_371_008.8

fun haversine(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}
