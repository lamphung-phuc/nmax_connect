package com.lampp.nmaxconnect.core.navigation

import com.lampp.nmaxconnect.core.location.GeoPoint

/**
 * Main-road routing for big bikes (docs/navigate-roadmap.md N27). VietMap's motorcycle profile threads
 * through hẻm/ngõ; its car profile (+ `avoid=motorway`) keeps to main roads but may use roads closed to
 * motorbikes (elevated Vành Đai 3). So the car route is only ridden after the motorcycle profile reaches a
 * [checkpoints] via on each of its main-road stretches: a closed road answers "connection not found".
 */
object MainRoads {
    // Probe 2026-10-18: alley streets are named Hẻm/Ngõ/Ngách/Kiệt; unnamed ones are mostly alleys too.
    // Not `\b`: on the JVM it only sees ASCII letters, so it never ends a word after "õ".
    private val ALLEY = Regex("^(hẻm|ngõ|ngách|kiệt)(\\s|$)", RegexOption.IGNORE_CASE)

    /** Shorter stretches aren't checked: few are closed to motorbikes and each via costs money. */
    private const val MIN_CHECKED_M = 100.0

    /** 13 vias + 2 ends = 15 points = 4 Route transactions (⌊points/5⌋+1); longer trips stay on the motorcycle route. */
    const val MAX_CHECKPOINTS = 13

    /** The motorcycle route through the checkpoints must follow the car route, not detour around a closed road. */
    private const val MAX_CHECK_STRETCH = 1.1

    /** Car-only restrictions can force a long way round (Vĩnh Tuy → Ngã Tư Sở: 15.5 vs 6.5 km). */
    private const val MAX_DETOUR = 1.25

    fun isAlley(street: String) = street.isBlank() || ALLEY.containsMatchIn(street.trim())

    /** Share (0..1) of the route length on alleys. */
    fun alleyShare(route: Route): Double {
        if (route.geometryLength <= 0) return 0.0
        return route.maneuvers.filter { isAlley(it.street) }.sumOf { it.lengthMeters } / route.geometryLength
    }

    /** The middle of each main-road stretch of [car]; null when there are too many to check in one call. */
    fun checkpoints(car: Route): List<GeoPoint>? =
        car.maneuvers.filter { !isAlley(it.street) && it.lengthMeters >= MIN_CHECKED_M }
            .map { car.pointAt(it.atMeters + it.lengthMeters / 2) }
            .takeIf { it.size <= MAX_CHECKPOINTS }

    /**
     * [car] to ride, timed at motorbike speed from [check] (the motorcycle route through [checkpoints]), or
     * null to keep [moto]: the check strayed, the car route is a detour, or it has no fewer alleys.
     */
    fun accept(car: Route, check: Route, moto: Route): Route? {
        val rideable = check.distanceMeters <= car.distanceMeters * MAX_CHECK_STRETCH &&
            car.distanceMeters <= moto.distanceMeters * MAX_DETOUR &&
            alleyShare(car) < alleyShare(moto)
        if (!rideable) return null
        val timeScale = if (car.timeMs > 0) check.timeMs.toDouble() / car.timeMs else 1.0
        return Route(
            car.geometry,
            car.distanceMeters,
            check.timeMs,
            car.maneuvers.map { it.copy(timeMs = (it.timeMs * timeScale).toLong()) },
            car.traffic,
            mainRoads = true,
        )
    }
}

/** Point [meters] along the geometry (clamped to the ends). */
internal fun Route.pointAt(meters: Double): GeoPoint = when (val i = cumulative.indexOfFirst { it >= meters }) {
    0 -> geometry.first()
    -1 -> geometry.last()
    else -> {
        val f = (meters - cumulative[i - 1]) / (cumulative[i] - cumulative[i - 1])
        val a = geometry[i - 1]
        val b = geometry[i]
        GeoPoint(lat = a.lat + (b.lat - a.lat) * f, lng = a.lng + (b.lng - a.lng) * f)
    }
}
