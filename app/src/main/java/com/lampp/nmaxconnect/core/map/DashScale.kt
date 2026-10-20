package com.lampp.nmaxconnect.core.map

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Sun elevation in degrees (low-precision almanac, ±1°: plenty for a day/night switch). */
fun sunElevation(lat: Double, lng: Double, timeMs: Long): Double {
    val d = timeMs / 86_400_000.0 - 10_957.5 // days since J2000.0
    val g = Math.toRadians(357.529 + 0.98560028 * d)
    val q = 280.459 + 0.98564736 * d
    val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
    val e = Math.toRadians(23.439 - 0.00000036 * d)
    val dec = asin(sin(e) * sin(l))
    val ra = atan2(cos(e) * sin(l), cos(l))
    val gmstDeg = 280.46061837 + 360.98564736629 * d
    val ha = Math.toRadians(gmstDeg + lng) - ra
    val phi = Math.toRadians(lat)
    return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha)))
}

/** Night once the sun's upper edge is below the horizon (standard -0.833°). */
fun isNight(lat: Double, lng: Double, timeMs: Long) = sunElevation(lat, lng, timeMs) < -0.833

/**
 * Dash scale text (msg 14) for a camera: the largest 1/2/5 step that fits in [SCALE_BAR_PX] at [zoom].
 * 512 px tiles at density 1 (the renderer runs at 160 dpi).
 */
fun scaleLabel(zoom: Double, lat: Double): String {
    val metres = 78_271.517 * cos(Math.toRadians(lat)) / 2.0.pow(zoom) * SCALE_BAR_PX
    val magnitude = 10.0.pow(floor(log10(metres)))
    val step = listOf(5, 2, 1).first { it * magnitude <= metres } * magnitude
    return if (step >= 1_000) "${(step / 1_000).toInt()} km" else "${step.toInt()} m"
}

// shortcut: the dash's own scale bar length is unknown; 100 px guessed. Calibrate on the bike.
private const val SCALE_BAR_PX = 100.0
