package com.lampp.nmaxconnect.core.navigation

import com.lampp.nmaxconnect.core.location.GeoPoint

/**
 * When the next reroute may go out. Each one is a paid VietMap request (vietmap skill: don't reroute on every fix),
 * but in an alley maze a fixed 10 s gap made the rider wait at every wrong turn. So: the first one goes at once,
 * repeats back off 3 → 6 → 10 s, and no more than [maxPerMinute] routes a minute. Failed requests (usually no
 * signal) retry 2 → 4 → 8 → 10 s and don't count toward the cap, so the route comes back soon after the signal does.
 *
 * After a reroute, the next one also waits until the bike is [minMoveMeters] from where that one was asked. A new route
 * starts on the nearest road, so a bike parked > 30 m from it (car park, café, indoor GPS) stays off-route: without
 * this it rerouted 6 times a minute (3 route requests each, ~1,000 an hour) until the rider stopped guidance.
 * Times are fix times (ms).
 */
class ReroutePacer(private val maxPerMinute: Int = 6, private val minMoveMeters: Double = 30.0) {
    private val routed = ArrayDeque<Long>() // successful reroutes in the last minute
    private var failures = 0
    private var lastAt: Long? = null
    private var routedFrom: GeoPoint? = null // where the last successful reroute was asked

    fun canReroute(nowMs: Long, at: GeoPoint): Boolean {
        while (routed.isNotEmpty() && nowMs - routed.first() >= WINDOW_MS) routed.removeFirst()
        val gap = when {
            failures > 0 -> RETRY_MS[(failures - 1).coerceAtMost(RETRY_MS.lastIndex)]
            routed.isEmpty() -> 0L
            else -> BACKOFF_MS[(routed.size - 1).coerceAtMost(BACKOFF_MS.lastIndex)]
        }
        val moved = routedFrom?.let { haversine(it, at) >= minMoveMeters } != false
        return moved && routed.size < maxPerMinute && lastAt?.let { nowMs - it >= gap } != false
    }

    fun onAttempt(nowMs: Long, succeeded: Boolean, at: GeoPoint) {
        lastAt = nowMs
        if (succeeded) {
            routed.addLast(nowMs)
            routedFrom = at
            failures = 0
        } else {
            failures++
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        val BACKOFF_MS = longArrayOf(3_000, 6_000, 10_000)
        val RETRY_MS = longArrayOf(2_000, 4_000, 8_000, 10_000)
    }
}
