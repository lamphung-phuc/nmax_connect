package com.lampp.nmaxconnect.core.navigation

import com.lampp.nmaxconnect.core.protocol.tft.TftMessages
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Route v4 `sign` → TFT turn icon (cmd 4). Codes are field-tested (docs/bike-protocol.md §3.3). */
object TurnIcons {
    const val ARRIVE = 0
    const val STRAIGHT = 8
    const val U_TURN_LEFT = 36

    fun forManeuver(m: Maneuver): Int {
        if (m.text.contains("quay đầu", ignoreCase = true)) return U_TURN_LEFT // no sign value for U-turns
        return when (m.sign) {
            -3 -> 32
            -2 -> 34
            -1 -> 6
            0 -> STRAIGHT
            1 -> 7
            2 -> 35
            3 -> 33
            4, 5 -> ARRIVE
            6 -> 14
            else -> STRAIGHT
        }
    }
}

/** Distance as shown on the dash: metres (to 10 m) below 1 km, then km with one decimal. */
fun dashDistance(meters: Double): Pair<Float, String> =
    if (meters < 1_000) ((meters / 10).roundToInt() * 10).toFloat() to "m"
    else ((meters / 100).roundToInt() / 10f) to "km"

/**
 * Turns navigation progress into TFT packets, sending each message only when what the dash
 * would show actually changes (nav updates must not crowd out map frames).
 */
class DashNavMapper(private val zone: ZoneId = ZoneId.systemDefault()) {
    private var navigating = false
    private var lastTurn: Any? = null
    private var lastEta: Pair<Int, Int>? = null
    private var lastRoad: String? = null

    fun update(progress: NavProgress, nowMs: Long): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        if (!navigating) {
            out += TftMessages.navigationStatus(true)
            navigating = true
        }

        val target = progress.next ?: progress.current
        val icon = if (progress.arrived) TurnIcons.ARRIVE else TurnIcons.forManeuver(target)
        val (value, unit) = dashDistance(progress.distanceToNextMeters)
        val road = target.street
        val turn = listOf(icon, value, unit, road)
        if (turn != lastTurn) {
            out += TftMessages.nextTurn(icon, value, unit, road)
            lastTurn = turn
        }

        val arrival = Instant.ofEpochMilli(nowMs + progress.remainingTimeMs).atZone(zone)
        val eta = arrival.hour to arrival.minute
        if (eta != lastEta) {
            out += TftMessages.eta(eta.first, eta.second)
            lastEta = eta
        }

        if (progress.current.street != lastRoad) {
            out += TftMessages.currentRoadName(progress.current.street)
            lastRoad = progress.current.street
        }
        return out
    }

    /** Ends guidance on the dash and forgets what was sent. */
    fun stop(): List<ByteArray> {
        val out = if (navigating) listOf(TftMessages.navigationStatus(false)) else emptyList()
        reset()
        return out
    }

    /** Forget what was sent (e.g. a new TFT session): the next update resends everything. */
    fun reset() {
        navigating = false
        lastTurn = null
        lastEta = null
        lastRoad = null
    }
}
