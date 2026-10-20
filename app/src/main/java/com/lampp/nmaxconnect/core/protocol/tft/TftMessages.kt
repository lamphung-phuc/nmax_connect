package com.lampp.nmaxconnect.core.protocol.tft

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Service ids (NaviLiteServiceType, Garmin StreetCross 1.87). Spec: docs/bike-protocol.md §3. */
object TftService {
    const val IMAGE_FRAME = 0
    const val ETA = 1
    const val NAVIGATION_STATUS = 2
    const val CUR_ROAD_NAME = 3
    const val NEXT_TURN_DIST = 4
    const val FAV_POI_LIST = 7
    const val GAS_POI_LIST = 8
    const val HOME_SETTING = 10
    const val OFFICE_SETTING = 11
    const val APP_SETTING = 12
    const val GPS_STATUS = 13
    const val MAP_ZOOM_LEVEL = 14
    const val ROUTE_CALC_PROGRESS = 15
    const val SPEED_LIMIT = 17
    const val IMAGE_STOPPED = 20
    const val DAY_NIGHT_MODE = 31
    const val AUTH_REQUEST = 33
    const val FAV_POI_DATA = 98
    const val GAS_POI_DATA = 99
    const val ESN_ACK = 81
    const val SEC_DATA_ACK = 84

    const val START_ROUTE = 48
    const val STOP_ROUTE = 49
    const val SKIP_NEXT_WAYPOINT = 50
    const val ZOOM_IN = 51
    const val ZOOM_OUT = 52
    const val GO_HOME = 53
    const val GO_OFFICE = 54
    const val START_CONTENT = 55
    const val STOP_CONTENT = 56
    const val VEHICLE_SPEED = 65
    const val ESN = 66
    const val SYS_INFO = 69
    const val DIALOG_USER_SELECT = 70
    const val IMAGE_ACK = 80
    const val AUTH_ACK = 82
    const val SEC_DATA = 83
}

object ImageType {
    const val NAVIGATION = 0 // map with the dash's own turn panel visible
    const val JUNCTION_VIEW = 1
    const val THROUGHPUT_TEST = 2
    const val NAVIGATION_EXPANDED = 3 // full-screen map
    const val OTHER = 4
}

enum class DayNight(val wire: Int) { DAY(1), NIGHT(2) } // 0 (auto) is rejected by the official parser

enum class PoiList(val listService: Int, val itemService: Int) {
    FAVORITES(TftService.FAV_POI_LIST, TftService.FAV_POI_DATA),
    GAS_STATIONS(TftService.GAS_POI_LIST, TftService.GAS_POI_DATA),
}

/** 1 front … 8 front-left, clockwise (NaviLitePoiDirectionType). */
enum class PoiDirection(val wire: Int) {
    UNDEFINED(0), FRONT(1), FRONT_RIGHT(2), RIGHT(3), BACK_RIGHT(4), BACK(5), BACK_LEFT(6), LEFT(7), FRONT_LEFT(8)
}

/** Phone → TFT packets, ready to write to the socket. All use frame type MCU_UPDATE (6). */
object TftMessages {
    /** Value used by Garmin StreetCross 1.87 (1660 is also reported to work). */
    const val APP_VERSION = 1870

    fun imageFrame(imageType: Int, seq: Int, jpeg: ByteArray): ByteArray =
        pointer(TftService.IMAGE_FRAME, le(3 + jpeg.size).put(imageType.toByte()).putShort(seq.toShort()).put(jpeg))

    fun eta(hour: Int, minute: Int): ByteArray {
        require(hour in 0..23 && minute in 0..59) { "ETA $hour:$minute out of range" }
        return pointer(TftService.ETA, le(4).putInt(hour * 60 + minute))
    }

    fun navigationStatus(navigating: Boolean) = value(TftService.NAVIGATION_STATUS, navigating)
    fun homeSet(isSet: Boolean) = value(TftService.HOME_SETTING, isSet)
    fun officeSet(isSet: Boolean) = value(TftService.OFFICE_SETTING, isSet)
    fun initSetupComplete() = value(TftService.APP_SETTING, true)
    fun gpsFix(fixed: Boolean) = value(TftService.GPS_STATUS, fixed)
    fun dayNight(mode: DayNight) = value(TftService.DAY_NIGHT_MODE, mode.wire)
    fun esnAck(validated: Boolean) = value(TftService.ESN_ACK, validated)

    fun routeCalcProgress(percent: Int): ByteArray {
        require(percent in 0..100) { "progress $percent" }
        return value(TftService.ROUTE_CALC_PROGRESS, percent)
    }

    fun imageStopped(): ByteArray =
        NaviLite.encode(FrameType.MCU_UPDATE, TftService.IMAGE_STOPPED, PayloadType.VALUE, ByteArray(0))

    fun currentRoadName(name: String): ByteArray =
        pointer(TftService.CUR_ROAD_NAME, NaviLite.dashAscii(name, NaviLite.MAX_ROAD_NAME).takeIf { it.isNotEmpty() } ?: SPACE)

    /** [turnIcon] codes: docs/bike-protocol.md §3.3. */
    fun nextTurn(turnIcon: Int, distance: Float, unit: String, nextRoad: String): ByteArray {
        val u = NaviLite.dashAscii(unit)
        val road = NaviLite.dashAscii(nextRoad, NaviLite.MAX_ROAD_NAME)
        return pointer(
            TftService.NEXT_TURN_DIST,
            le(7 + u.size + road.size).put(turnIcon.toByte()).putFloat(distance)
                .put(u.size.toByte()).put(road.size.toByte()).put(u).put(road),
        )
    }

    fun speedLimit(limit: Float, unit: String = "km/h"): ByteArray {
        val u = NaviLite.dashAscii(unit)
        return pointer(TftService.SPEED_LIMIT, le(5 + u.size).putFloat(limit).put(u.size.toByte()).put(u))
    }

    /**
     * Map scale text shown on the dash (e.g. "300 m").
     * shortcut: bytes 0,1,3 are field-tested constants (07 1A … 00); the official
     * fields are (current, min, max, label, show). Decode them in libnaviliteprotocol.so when zoom sync is needed.
     */
    fun mapScaleLabel(label: String): ByteArray {
        val l = NaviLite.dashAscii(label)
        return pointer(TftService.MAP_ZOOM_LEVEL, le(4 + l.size).put(7).put(0x1A).put(l.size.toByte()).put(0).put(l))
    }

    fun poiListHeader(list: PoiList, count: Int, hasMore: Boolean = false): ByteArray =
        pointer(list.listService, le(3).putShort(count.toShort()).put((if (hasMore) 1 else 0).toByte()))

    fun poiItem(
        list: PoiList,
        itemIndex: Int,
        listIndex: Int,
        direction: PoiDirection,
        distance: Float,
        unit: String,
        name: String,
    ): ByteArray {
        val u = NaviLite.dashAscii(unit)
        val n = NaviLite.dashAscii(name)
        return pointer(
            list.itemService,
            le(11 + u.size + n.size).putShort(itemIndex.toShort()).putShort(listIndex.toShort())
                .put(direction.wire.toByte()).put(n.size.toByte()).put(u.size.toByte()).putFloat(distance)
                .put(u).put(n),
        )
    }

    fun authRequest(appVersion: Int = APP_VERSION, extra: Int = 0): ByteArray =
        pointer(TftService.AUTH_REQUEST, le(8).putShort(appVersion.toShort()).put(0).put(1).putInt(extra))

    /** Echo the seeder key from [TftEvent.SecData] unchanged. */
    fun secDataAck(key: ByteArray): ByteArray = pointer(TftService.SEC_DATA_ACK, key)

    private val SPACE = byteArrayOf(0x20)

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun pointer(service: Int, payload: ByteBuffer) = pointer(service, payload.array())

    private fun pointer(service: Int, payload: ByteArray) =
        NaviLite.encode(FrameType.MCU_UPDATE, service, PayloadType.POINTER, payload)

    private fun value(service: Int, flag: Boolean) = value(service, if (flag) 1 else 0)

    private fun value(service: Int, v: Int) =
        NaviLite.encode(FrameType.MCU_UPDATE, service, PayloadType.VALUE, byteArrayOf(v.toByte(), (v shr 8).toByte()))
}
