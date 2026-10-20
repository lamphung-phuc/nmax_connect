package com.lampp.nmaxconnect.core.protocol.ccu

/** Fuel injection rate in a 276 CAN frame (VehicleInfoStore.calculateCanData): EG status bytes 2-3 × 0.01. */
object CanInjection {
    /** 0x23E (LinkCard 4.2 / LinkBox2 gen 1) and 0x383 (LinkCard 5 / LinkBox2 gen 2); a CCU sends the one it has. */
    val IDS = listOf(574, 899)

    fun decode(frames: Map<Int, ByteArray>): Float? =
        IDS.firstNotNullOfOrNull { id -> frames[id]?.takeIf { it.size >= 4 }?.let { readUnsigned(it, 2, 2) / 100f } }
}

/**
 * Instant fuel economy as Y-Connect's revs dashboard computes it (SubMeterStoreYmsl.calcAndUpdateFuel): per
 * [windowMs] block, km/L = mean speed / (3.6 × mean injection rate). None at ≤ 10 km/h, capped at 80 km/L;
 * a block with no injection (engine braking) keeps the last value.
 */
class InstantFuelEconomy(private val windowMs: Long = 3_000) {
    private val speeds = mutableListOf<Int>()
    private val injections = mutableListOf<Float>()
    private var blockStart = -1L

    var kmPerL: Float? = null
        private set

    fun speed(kmh: Int) {
        speeds += kmh
    }

    fun injection(atMs: Long, rate: Float) {
        if (blockStart < 0) blockStart = atMs
        injections += rate
        if (atMs - blockStart < windowMs) return
        val avgSpeed = speeds.average()
        val litresPerHour = 3.6 * injections.average()
        when {
            speeds.isEmpty() || avgSpeed <= MIN_SPEED_KMH -> kmPerL = null
            litresPerHour > 0 -> kmPerL = (avgSpeed / litresPerHour).coerceAtMost(MAX_KM_PER_L).toFloat()
        }
        speeds.clear()
        injections.clear()
        blockStart = -1
    }

    private companion object {
        const val MIN_SPEED_KMH = 10.0
        const val MAX_KM_PER_L = 80.0
    }
}
