package com.lampp.nmaxconnect.core.protocol.tft

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** TFT → phone messages. Size/frame rules mirror Garmin's unpackPayloadParameters. */
sealed interface TftEvent {
    /** Dash menu: route to item [itemIndex] of the POI list you sent as [listIndex]. */
    data class StartRoute(val itemIndex: Int, val listIndex: Int, val option: RouteOption) : TftEvent
    data object StopRoute : TftEvent
    data object SkipNextWaypoint : TftEvent
    data object ZoomIn : TftEvent
    data object ZoomOut : TftEvent
    data class GoHome(val value: Int) : TftEvent
    data class GoOffice(val value: Int) : TftEvent
    data class StartContent(val content: ContentType) : TftEvent
    data class StopContent(val content: ContentType) : TftEvent

    /** The bike's own speedometer reading (not the road speed limit). */
    data class VehicleSpeed(val speed: Int, val unit: SpeedUnit) : TftEvent
    data class Esn(val esn: String) : TftEvent
    data class SysInfo(val text: String) : TftEvent
    data class DialogSelect(val dialogId: Int, val button: Int) : TftEvent
    data class ImageAck(val seq: Int) : TftEvent
    data class AuthAck(val ok: Boolean) : TftEvent

    /** De-obfuscated SEC_DATA: answer with [TftMessages.secDataAck]([key]). */
    class SecData(val partNumber: String, val key: ByteArray) : TftEvent {
        val display: DisplayModel get() = DisplayModel.fromPartNumber(partNumber)
    }

    class Unknown(val service: Int, val payload: ByteArray) : TftEvent
    data class Malformed(val service: Int, val reason: String) : TftEvent

    companion object {
        fun parse(p: NaviLitePacket): TftEvent {
            val s = p.service
            val isRequest = s in TftService.START_ROUTE..TftService.STOP_CONTENT
            val expectedFrame = if (isRequest) FrameType.MOBILE_REQUEST else FrameType.MOBILE_UPDATE
            if (p.frameType != expectedFrame) return Malformed(s, "frame type ${p.frameType} != $expectedFrame")

            val b = ByteBuffer.wrap(p.payload).order(ByteOrder.LITTLE_ENDIAN)
            fun value(): Int? = if (p.payloadType == PayloadType.VALUE && p.payload.size == 2) b.getShort(0).toInt() else null
            fun bad(why: String = "size ${p.payload.size}, type ${p.payloadType}") = Malformed(s, why)
            val pointer = p.payloadType == PayloadType.POINTER

            return when (s) {
                TftService.START_ROUTE ->
                    if (pointer && p.payload.size >= 5) {
                        StartRoute(b.getShort(0).toInt(), b.getShort(2).toInt(), RouteOption.of(p.payload[4].toInt()))
                    } else bad()
                TftService.STOP_ROUTE -> StopRoute
                TftService.SKIP_NEXT_WAYPOINT -> SkipNextWaypoint
                TftService.ZOOM_IN -> ZoomIn
                TftService.ZOOM_OUT -> ZoomOut
                TftService.GO_HOME -> value()?.let(::GoHome) ?: bad()
                TftService.GO_OFFICE -> value()?.let(::GoOffice) ?: bad()
                TftService.START_CONTENT -> value()?.let { StartContent(ContentType.of(it)) } ?: bad()
                TftService.STOP_CONTENT -> value()?.let { StopContent(ContentType.of(it)) } ?: bad()
                TftService.VEHICLE_SPEED ->
                    if (pointer && p.payload.size == 3) {
                        VehicleSpeed(b.getShort(1).toInt(), if (p.payload[0].toInt() == 1) SpeedUnit.MPH else SpeedUnit.KMH)
                    } else bad()
                TftService.ESN ->
                    if (pointer && p.payload.size == 10) Esn(String(p.payload, Charsets.US_ASCII)) else bad()
                TftService.SYS_INFO -> if (pointer) SysInfo(String(p.payload, Charsets.ISO_8859_1).trimEnd('\u0000')) else bad()
                TftService.DIALOG_USER_SELECT ->
                    if (pointer && p.payload.size == 2) {
                        DialogSelect(p.payload[0].toInt() and 0xFF, p.payload[1].toInt() and 0xFF)
                    } else bad()
                TftService.IMAGE_ACK -> value()?.let { ImageAck(it and 0xFFFF) } ?: bad()
                TftService.AUTH_ACK -> value()?.let { AuthAck(it == 1) } ?: bad()
                TftService.SEC_DATA -> if (pointer && p.payload.size >= 16) secData(p.payload) else bad()
                else -> Unknown(s, p.payload)
            }
        }

        private const val PART_NUMBER_LENGTH = 12
        private const val SEC_DATA_XOR = 0x0A

        private fun secData(raw: ByteArray): SecData {
            val clear = ByteArray(raw.size) { (raw[it].toInt() xor SEC_DATA_XOR).toByte() }
            val partNumber = String(clear, 0, PART_NUMBER_LENGTH, Charsets.US_ASCII).trimEnd('\u0000', ' ')
            return SecData(partNumber, clear.copyOfRange(PART_NUMBER_LENGTH, clear.size))
        }
    }
}

enum class SpeedUnit { KMH, MPH }

enum class RouteOption(val wire: Int) {
    UNKNOWN(0), NEW_ROUTE(1), NEXT_STOP(2), LAST_STOP(3);

    companion object {
        fun of(wire: Int) = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

enum class ContentType(val wire: Int) {
    NAVI_IMAGE(1), TBT_LIST(2), FAVORITE_LIST(3), STATIONS_LIST(4), NAVI_IMAGE_THROUGHPUT(11), UNKNOWN(-1);

    companion object {
        fun of(wire: Int) = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** Frame size the dash expects, chosen from the part number in SEC_DATA (LinkCardAuthenticationManager). */
enum class DisplayModel(val width: Int, val height: Int) {
    IMWW23(480, 240),
    IXWW22(480, 234),
    UNKNOWN(480, 234),
    ;

    companion object {
        fun fromPartNumber(partNumber: String) = when {
            "006-B4160" in partNumber || "006-B4920" in partNumber -> IMWW23
            "006-B3952" in partNumber -> IXWW22
            else -> UNKNOWN
        }
    }
}
