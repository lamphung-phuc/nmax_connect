package com.lampp.nmaxconnect.core.protocol.ccu

import com.lampp.nmaxconnect.core.protocol.tft.SpeedUnit

/**
 * CCU → phone messages. Every source (SPP payload, control notify, reassembled DDT-in / DDT v2) is
 * `[cmd u16][body]`; protobuf bodies sit after a u24 length at offset 5. Lengths mirror the SDK's parsers.
 */
sealed interface CcuEvent {
    /** 314 over SPP: BLE credentials. Keep the raw bytes for [CcuMessages.authenticationV2]. */
    class BleConnectInformation(val ccuId: ByteArray, val passkey: ByteArray) : CcuEvent {
        val ccuIdText: String get() = String(ccuId, Charsets.US_ASCII)
    }

    data class Authentication(val ok: Boolean) : CcuEvent
    data class AliveCheck(val echo: Byte) : CcuEvent
    data class ServerList(val servers: List<Int>) : CcuEvent
    data class SupportIdList(val server: Int, val ids: List<Int>) : CcuEvent

    /** 271: raw records by data id; decode with [LocalRecord]. */
    class VehicleInformation(val server: Int, val records: Map<Int, ByteArray>) : CcuEvent

    /** 276: raw CAN frames by CAN id; see [CanInjection]. */
    class Can(val frames: Map<Int, ByteArray>) : CcuEvent

    /** null when the CCU reports neither on nor off. */
    data class IgnitionState(val on: Boolean?) : CcuEvent

    /** Cumulative fuel injection; the unit isn't traced yet. */
    data class CumulativeInjection(val quantity: Long) : CcuEvent

    /** Cmd 7 live feed. Speeds are the raw i16 the SDK passes through (scale unverified). */
    data class LiveVehicle(val frontSpeed: Int, val rearSpeed: Int, val distractionLimitSpeed: Int, val ignitionOn: Boolean, val unit: SpeedUnit) :
        CcuEvent

    /** Cmd 279 dash music button: [NEXT], [PREVIOUS], [PLAY] or [PAUSE]. */
    data class MusicControl(val button: Int) : CcuEvent {
        companion object {
            const val NEXT = 1
            const val PREVIOUS = 2
            const val PLAY = 3
            const val PAUSE = 4
        }
    }

    /** Cmd 285 dash volume button. */
    data class VolumeControl(val op: Int) : CcuEvent {
        companion object {
            const val CALL_UP = 1
            const val CALL_DOWN = 2
            const val MIC_MUTE = 3
            const val MUTE = 4
            const val MEDIA_UP = 5
            const val MEDIA_DOWN = 6
        }
    }

    /** Cmd 281 dash call button: [REJECT], [ANSWER] or [END]. */
    data class CallControl(val action: Int) : CcuEvent {
        companion object {
            const val REJECT = 0
            const val ANSWER = 1
            const val END = 2
        }
    }

    /** Cmd 3: forecast for the phone's position (both null) or for the given point (degrees). */
    data class WeatherRequest(val lat: Double? = null, val lng: Double? = null) : CcuEvent

    data object VolumeControllableRequest : CcuEvent
    data object ClockRequest : CcuEvent
    data object LanguageRequest : CcuEvent
    data class CommandError(val command: Int, val code: Int, val message: String) : CcuEvent
    class Unknown(val command: Int, val body: ByteArray) : CcuEvent
    data class Malformed(val command: Int, val reason: String) : CcuEvent

    companion object {
        private const val PROTO_OFFSET = 5

        fun parse(m: ByteArray): CcuEvent {
            if (m.size < 2) return Malformed(-1, "size ${m.size}")
            val cmd = readU16(m, 0)
            fun bad(why: String = "size ${m.size}") = Malformed(cmd, why)
            fun proto(): ProtoMessage? =
                if (m.size < PROTO_OFFSET) null else runCatching { ProtoMessage(m.copyOfRange(PROTO_OFFSET, m.size)) }.getOrNull()

            return when (cmd) {
                CcuCommand.BLE_CONNECT_INFORMATION_RESPONSE ->
                    if (m.size == 22) BleConnectInformation(m.copyOfRange(2, 16), m.copyOfRange(16, 22)) else bad()
                CcuCommand.AUTHENTICATION_V2_RESPONSE -> if (m.size == 3) Authentication(m[2].toInt() == 1) else bad()
                CcuCommand.ALIVE_CHECK_REQUEST -> if (m.size == 3) AliveCheck(m[2]) else bad()
                CcuCommand.CONNECT_SERVER_LIST_RESPONSE -> proto()?.let { ServerList(it.ints(3)) } ?: bad("protobuf")
                CcuCommand.VEHICLE_INFORMATION_SUPPORT_ID_LIST_RESPONSE ->
                    proto()?.let { SupportIdList(it.int(3), it.ints(4)) } ?: bad("protobuf")
                CcuCommand.VEHICLE_INFORMATION_RESPONSE -> proto()?.let { pb ->
                    VehicleInformation(pb.int(3), pb.messages(4).associate { it.int(1) to it.bytes(2) })
                } ?: bad("protobuf")
                CcuCommand.CAN_RESPONSE -> proto()?.let { pb -> Can(pb.messages(3).associate { it.int(1) to it.bytes(2) }) } ?: bad("protobuf")
                CcuCommand.IGNITION_STATE_RESPONSE ->
                    if (m.size == 3) IgnitionState(when (m[2].toInt()) { 0 -> false; 1 -> true; else -> null }) else bad()
                CcuCommand.CUMULATIVE_INJECTION_QUANTITY -> if (m.size == 6) CumulativeInjection(readUnsigned(m, 2, 4)) else bad()
                CcuCommand.VEHICLE_INFORMATION -> liveVehicle(m) ?: bad()
                CcuCommand.BLUETOOTH_MUSIC_CONTROL ->
                    if (m.size == 3 && m[2].toInt() in MusicControl.NEXT..MusicControl.PAUSE) MusicControl(m[2].toInt()) else bad()
                CcuCommand.PHONE_VOLUME_CONTROL ->
                    if (m.size == 3 && m[2].toInt() in VolumeControl.CALL_UP..VolumeControl.MEDIA_DOWN) VolumeControl(m[2].toInt()) else bad()
                CcuCommand.INCOMING_CALL_CONTROL ->
                    if (m.size == 3 && m[2].toInt() in CallControl.REJECT..CallControl.END) CallControl(m[2].toInt()) else bad()
                CcuCommand.WEATHER_REQUEST -> when {
                    m.size == 3 && m[2].toInt() == 1 -> WeatherRequest()
                    m.size == 11 && m[2].toInt() == 0 -> WeatherRequest(radE8ToDegrees(readU32(m, 3)), radE8ToDegrees(readU32(m, 7)))
                    else -> bad()
                }
                CcuCommand.PHONE_VOLUME_CONTROLLABLE_REQUEST -> VolumeControllableRequest
                CcuCommand.VEHICLE_SETTING_REQUEST -> ClockRequest
                CcuCommand.LANGUAGE_TYPE_REQUEST -> LanguageRequest
                CcuCommand.COMMAND_ERROR ->
                    if (m.size >= 5) CommandError(readU16(m, 2), m[4].toInt() and 0xFF, String(m, 5, m.size - 5, Charsets.UTF_8)) else bad()
                else -> Unknown(cmd, m.copyOfRange(2, m.size))
            }
        }

        /** The CCU sends coordinates as radians × 1e8 (motoconsdk C20672e). */
        private fun radE8ToDegrees(v: Int) = Math.toDegrees(v * 1e-8)

        private fun liveVehicle(m: ByteArray): LiveVehicle? {
            if (m.size != 10) return null
            val ignition = when (m[8].toInt()) { 0 -> false; 1 -> true; else -> return null }
            val unit = when (m[9].toInt()) { 0 -> SpeedUnit.KMH; 1 -> SpeedUnit.MPH; else -> return null }
            return LiveVehicle(readI16(m, 2), readI16(m, 4), readI16(m, 6), ignition, unit)
        }
    }
}

/** Decoded local-record data ids (VehicleInfoStore.calculateLocalRecordData). Null = not in this push. */
data class LocalRecord(
    val engineRpm: Int? = null,
    val speedKmh: Int? = null,
    val ignitionTimingDeg: Int? = null,
    val batteryVolts: Float? = null,
    val throttlePercent: Float? = null,
    val coolantCelsius: Int? = null,
    val airCelsius: Int? = null,
    val intakePressure: Float? = null,
    val atmosphericPressure: Float? = null,
    val dtcCount: Int? = null,
    /** Total mileage in units of 100 m (dash ODO km = value / 10). */
    val odometerHectometres: Long? = null,
    /** Not in 271: [InstantFuelEconomy] over 271 speed + 276 CAN injection. */
    val fuelKmPerL: Float? = null,
) {
    val odometerKm: Double? get() = odometerHectometres?.let { it / 10.0 }

    companion object {
        const val ENGINE = 1
        const val ODOMETER = 72

        /** Data ids Y-Connect subscribes for server 0x4E0; other servers get [ENGINE] only. */
        fun wantedIds(server: Int): List<Int> = if (server == 1248) listOf(ENGINE, ODOMETER) else listOf(ENGINE)

        fun decode(records: Map<Int, ByteArray>): LocalRecord {
            var r = LocalRecord()
            records[ENGINE]?.takeIf { it.size >= ENGINE_MIN_SIZE }?.let { b ->
                fun u(at: Int, len: Int = 1) = readUnsigned(b, at, len).toInt()
                r = r.copy(
                    engineRpm = u(0, 2),
                    speedKmh = u(2),
                    ignitionTimingDeg = u(4) - 30,
                    batteryVolts = u(6) * 18.75f / 256f,
                    throttlePercent = u(8) * 125f / 256f,
                    coolantCelsius = u(14) - 30,
                    airCelsius = u(15) - 30,
                    intakePressure = u(16) * 127f / 256f,
                    atmosphericPressure = u(18) * 127f / 256f,
                    dtcCount = u(23),
                )
            }
            records[ODOMETER]?.takeIf { it.isNotEmpty() && it.size <= 7 }?.let { r = r.copy(odometerHectometres = readUnsigned(it)) }
            return r
        }

        /** Y-Connect reads up to byte 25 of id 1. */
        private const val ENGINE_MIN_SIZE = 26
    }
}
