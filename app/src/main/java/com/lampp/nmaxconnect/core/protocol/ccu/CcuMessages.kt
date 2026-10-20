package com.lampp.nmaxconnect.core.protocol.ccu

import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Command ids (motoconsdk EnumC20670c) we send or handle. Spec: docs/bike-protocol.md §6. */
object CcuCommand {
    const val CONNECT_APP_ID = 2
    const val WEATHER_REQUEST = 3
    const val WEATHER_LOCATION = 4
    const val WEATHER_INFO = 5
    const val VEHICLE_INFORMATION_SUBSCRIBE_INTERVAL = 6
    const val VEHICLE_INFORMATION = 7
    const val VEHICLE_SETTING_REQUEST = 9 // CCU asks for the clock
    const val VEHICLE_SETTING_DATETIME = 10
    const val LANGUAGE_TYPE_REQUEST = 11
    const val LANGUAGE_TYPE_RESPONSE = 12
    const val AUTHENTICATION_V2_REQUEST = 257
    const val AUTHENTICATION_V2_RESPONSE = 258
    const val BLUETOOTH_MUSIC_META_DATA = 278
    const val BLUETOOTH_MUSIC_CONTROL = 279
    const val INCOMING_CALL_INFORMATION = 280
    const val INCOMING_CALL_CONTROL = 281
    const val CALL_CHANGE_NOTIFICATION = 308
    const val PHONE_VOLUME_CONTROL = 285
    const val PHONE_VOLUME_CONTROLLABLE_REQUEST = 297
    const val PHONE_VOLUME_CONTROLLABLE_RESPONSE = 298
    const val NOTIFICATION_DATA_V2 = 303
    const val CONNECT_SERVER_LIST_REQUEST = 259
    const val CONNECT_SERVER_LIST_RESPONSE = 260
    const val VEHICLE_INFORMATION_REQUEST = 270
    const val VEHICLE_INFORMATION_RESPONSE = 271
    const val VEHICLE_INFORMATION_INTERVAL_REQUEST = 272
    const val CAN_RESPONSE = 276
    const val CAN_INTERVAL_REQUEST = 315
    const val CUMULATIVE_INJECTION_QUANTITY_REQUEST = 287
    const val CUMULATIVE_INJECTION_QUANTITY = 288
    const val VEHICLE_INFORMATION_SUPPORT_ID_LIST_REQUEST = 294
    const val VEHICLE_INFORMATION_SUPPORT_ID_LIST_RESPONSE = 295
    const val ALIVE_CHECK_REQUEST = 309
    const val ALIVE_CHECK_RESPONSE = 310
    const val IGNITION_STATE_REQUEST = 311
    const val IGNITION_STATE_RESPONSE = 312
    const val BLE_CONNECT_INFORMATION_REQUEST = 313
    const val BLE_CONNECT_INFORMATION_RESPONSE = 314
    const val PHONE_STATE_COMBINED = 316
    const val COMMAND_ERROR = 0xFF01 // -255 as u16
}

/** Playback state byte of cmd 278 (McBluetoothMusicMetaData.PlaybackState). */
object CcuMusicState {
    const val PAUSED = 1
    const val PLAYING = 2
    const val REWINDING = 3
    const val FAST_FORWARDING = 4
    const val UNAVAILABLE = 0xFF
}

/** Weather slots and icons for cmd 5 (McWeatherTime / McWeather wire values). */
object CcuWeather {
    const val SLOT_NOW = 0

    /** Days ahead 1..6 → slots 1-5, then 9 (Y-Connect's McWeatherTime table). */
    fun daySlot(daysAhead: Int) = if (daysAhead == 6) 9 else daysAhead

    /** Hours ahead 1..3 → slots 6-8. */
    fun hourSlot(hoursAhead: Int) = 5 + hoursAhead

    const val SUNNY = 0
    const val PARTLY_CLOUDY = 1
    const val CLOUDY_PARTIAL = 2
    const val RAIN = 3
    const val SNOW = 4
    const val WIND = 5
    const val THUNDERSTORM = 6
    const val SLEET = 7
    const val FOG = 8
    const val SMOG = 11
    const val HAIL = 12
    const val SHOWERS = 13
    const val SCATTERED_STORMS = 14
    const val NO_DATA = 15
    const val RAIN_LIGHT = 16
    const val RAIN_HEAVY = 17
    const val SNOW_LIGHT = 18
    const val SNOW_HEAVY = 19
    const val SLEET_LIGHT = 20
    const val SLEET_HEAVY = 21
    const val OVERCAST = 22
    const val DUST = 23
    const val TORNADO = 24

    /** Night variants exist for clear, partly cloudy, cloudy, showers and scattered storms: 0x80 | day icon. */
    const val NIGHT = 0x80
}

/** `McLanguage` wire codes for cmd 12. */
object CcuLanguage {
    const val ENGLISH = 0
    const val VIETNAMESE = 15
    const val OTHERS = 31
}

/**
 * Phone → CCU message bodies, each starting with `[cmd u16]`. Control messages go to CONTROL_WRITE,
 * DDT ones through the DDT-out handshake, the bootstrap one inside [CcuSpp.encode].
 * Never build 317 (factory mode) or 289–302 (OTA).
 */
object CcuMessages {
    /** Y-Connect's ParameterConstant.APP_ID (0x1234). */
    const val APP_ID = 4660

    /** Default vehicle-data push period (BluetoothGattClientStore.mCommunicationInterval). */
    const val VEHICLE_INFO_INTERVAL_MS = 100

    /** SPP: ask for the BLE CCU id + passkey (reply 314). */
    fun bleConnectInformationRequest() = u16(CcuCommand.BLE_CONNECT_INFORMATION_REQUEST)

    fun connectAppId(requestId: Int) = u16(CcuCommand.CONNECT_APP_ID) + byteArrayOf(requestId.toByte()) + u32(APP_ID)

    /** DDT. [ccuId] (14 B) and [passkey] (6 B) come raw from the 314 reply. */
    fun authenticationV2(ccuId: ByteArray, passkey: ByteArray): ByteArray {
        require(ccuId.size == CcuUuids.CCU_ID_LENGTH && passkey.size == PASSKEY_LENGTH) { "ccuId ${ccuId.size} B, passkey ${passkey.size} B" }
        return u16(CcuCommand.AUTHENTICATION_V2_REQUEST) + ccuId + passkey
    }

    /** Echo the byte of the CCU's 309 keep-alive. */
    fun aliveCheckResponse(echo: Byte) = u16(CcuCommand.ALIVE_CHECK_RESPONSE) + byteArrayOf(echo)

    fun connectServerListRequest() = u16(CcuCommand.CONNECT_SERVER_LIST_REQUEST)

    fun supportIdListRequest(server: Int) = u16(CcuCommand.VEHICLE_INFORMATION_SUPPORT_ID_LIST_REQUEST) + u16(server)

    /** DDT 272: subscribe; the CCU then pushes 271 every [intervalMs]. */
    fun vehicleInformationInterval(server: Int, ids: List<Int>, intervalMs: Int = VEHICLE_INFO_INTERVAL_MS) =
        protobuf(CcuCommand.VEHICLE_INFORMATION_INTERVAL_REQUEST, ProtoWriter().int(3, server).packedInts(4, ids).bool(5, false).int(6, intervalMs))

    /** DDT 315: subscribe to raw CAN frames; the CCU then pushes 276 every [intervalMs]. */
    fun canInterval(ids: List<Int>, intervalMs: Int = VEHICLE_INFO_INTERVAL_MS) =
        protobuf(CcuCommand.CAN_INTERVAL_REQUEST, ProtoWriter().packedInts(3, ids).int(4, intervalMs))

    /** DDT 270: one-shot read; answered by one 271. */
    fun vehicleInformationRequest(server: Int, ids: List<Int>) =
        protobuf(CcuCommand.VEHICLE_INFORMATION_REQUEST, ProtoWriter().int(3, server).packedInts(4, ids).bool(5, false))

    fun ignitionStateRequest() = u16(CcuCommand.IGNITION_STATE_REQUEST)

    fun cumulativeInjectionQuantityRequest() = u16(CcuCommand.CUMULATIVE_INJECTION_QUANTITY_REQUEST)

    /** Live speed feed: the CCU pushes cmd 7 every [intervalMs] (Y-Connect default 200). */
    fun vehicleInformationSubscribeInterval(requestId: Int, intervalMs: Int) =
        u16(CcuCommand.VEHICLE_INFORMATION_SUBSCRIBE_INTERVAL) + byteArrayOf(requestId.toByte()) + u16(intervalMs)

    /** Clock: [localEpochSeconds] is UTC epoch seconds plus the zone offset, as Y-Connect sends it. */
    fun vehicleSettingDatetime(requestId: Int, localEpochSeconds: Long) =
        u16(CcuCommand.VEHICLE_SETTING_DATETIME) + byteArrayOf(requestId.toByte()) + u64(localEpochSeconds)

    /** [language] is a [CcuLanguage] code. */
    fun languageTypeResponse(requestId: Int, language: Int) =
        u16(CcuCommand.LANGUAGE_TYPE_RESPONSE) + byteArrayOf(requestId.toByte(), language.toByte())

    /**
     * DDT 303 (McNotificationDataV2). Texts are cut to 64/64/512 UTF-8 bytes like the SDK, but on whole
     * code points so an emoji is never split. [time] is the phone's local wall clock.
     */
    fun notificationDataV2(id: Int, isCall: Boolean, time: LocalDateTime, title: String, subtitle: String, detail: String) =
        protobuf(
            CcuCommand.NOTIFICATION_DATA_V2,
            ProtoWriter()
                .uint32(3, id)
                .int(4, if (isCall) 1 else 0)
                .int(5, time.year).int(6, time.monthValue).int(7, time.dayOfMonth)
                .int(8, time.hour).int(9, time.minute).int(10, time.second)
                .string(11, title.utf8Prefix(TITLE_BYTES))
                .string(12, subtitle.utf8Prefix(TITLE_BYTES))
                .string(13, detail.utf8Prefix(DETAIL_BYTES)),
        )

    /**
     * DDT 278 now playing: `[01 16][len u8][artist][len][album][len][title][duration u24 s][state u8][rate×10 i16][elapsed u24 s]`.
     * Texts are cut to 64 UTF-8 bytes; the dash extrapolates [elapsedSec] with [rate].
     */
    fun musicMetaData(artist: String, album: String, title: String, durationSec: Int, state: Int, rate: Float, elapsedSec: Int): ByteArray {
        fun text(s: String) = s.utf8Prefix(TITLE_BYTES).toByteArray(Charsets.UTF_8).let { byteArrayOf(it.size.toByte()) + it }
        return u16(CcuCommand.BLUETOOTH_MUSIC_META_DATA) + text(artist) + text(album) + text(title) +
            u24(durationSec) + byteArrayOf(state.toByte()) + u16((rate * 10).toInt()) + u24(elapsedSec)
    }

    /** DDT 280 caller: `[01 18][len u8][name ≤ 64 B][len u8][number ≤ 12 B][missed u8]`, no u24 length (traced in motoconsdk). */
    fun incomingCallInformation(name: String, number: String, missed: Boolean): ByteArray {
        fun text(s: String, max: Int) = s.utf8Prefix(max).toByteArray(Charsets.UTF_8).let { byteArrayOf(it.size.toByte()) + it }
        return u16(CcuCommand.INCOMING_CALL_INFORMATION) + text(name, TITLE_BYTES) + text(number, NUMBER_BYTES) +
            byteArrayOf(if (missed) 1 else 0)
    }

    /** Control 308: the call was picked up (1) or ended (2). */
    fun callChangeNotification(started: Boolean) =
        u16(CcuCommand.CALL_CHANGE_NOTIFICATION) + byteArrayOf(if (started) 1 else 2)

    private fun String.utf8Prefix(maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < length) {
            val next = offsetByCodePoints(end, 1)
            bytes += substring(end, next).toByteArray(Charsets.UTF_8).size
            if (bytes > maxBytes) break
            end = next
        }
        return substring(0, end)
    }

    /**
     * Control 316 phone status, 4 bytes: `[battery% | charging<<7][callVol% | 0x80][mediaVol% | 0x80]`
     * `[onHook | signal<<4 | thermal<<2 | headset<<1]`. [signal]: 0 no service, 1 no signal, 2-5 = 1-4 bars;
     * [thermal]: 0 low, 1 middle, 2 high. Y-Connect always sets the three "enabled" bits.
     */
    fun phoneStateCombined(batteryPct: Int, charging: Boolean, callVolumePct: Int, mediaVolumePct: Int, signal: Int, thermal: Int, headset: Boolean): ByteArray {
        fun pct(v: Int) = v.coerceIn(0, 100)
        return u16(CcuCommand.PHONE_STATE_COMBINED) + byteArrayOf(
            (pct(batteryPct) or (if (charging) 0x80 else 0)).toByte(),
            (pct(callVolumePct) or 0x80).toByte(),
            (pct(mediaVolumePct) or 0x80).toByte(),
            (1 or (signal.coerceIn(0, 5) shl 4) or (thermal.coerceIn(0, 2) shl 2) or (if (headset) 2 else 0)).toByte(),
        )
    }

    /** Reply to 297; yes makes the dash offer its volume buttons (285). */
    fun phoneVolumeControllableResponse(controllable: Boolean) =
        u16(CcuCommand.PHONE_VOLUME_CONTROLLABLE_RESPONSE) + byteArrayOf(if (controllable) 1 else 0)

    /** Control 4: place name shown above the forecast, raw UTF-8 cut to 21 bytes (no length byte). */
    fun weatherLocation(requestId: Int, name: String) =
        u16(CcuCommand.WEATHER_LOCATION) + byteArrayOf(requestId.toByte()) + name.utf8Prefix(PLACE_BYTES).toByteArray(Charsets.UTF_8)

    /**
     * Control 5, one forecast slot: `[reqId][slot][icon][temp i16][max i16][min i16][pop/5]`, temperatures in °C × 10
     * (rounded; Y-Connect truncates), [popPercent] 0-100.
     */
    fun weatherInfo(requestId: Int, slot: Int, icon: Int, tempC: Double, maxC: Double, minC: Double, popPercent: Int): ByteArray {
        fun tenths(c: Double) = u16((c * 10).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        return u16(CcuCommand.WEATHER_INFO) + byteArrayOf(requestId.toByte(), slot.toByte(), icon.toByte()) +
            tenths(tempC) + tenths(maxC) + tenths(minC) + byteArrayOf((popPercent.coerceIn(0, 100) / 5).toByte())
    }

    /** Bulk body `[cmd u16][len u24][protobuf]`. */
    private fun protobuf(command: Int, message: ProtoWriter): ByteArray {
        val pb = message.toByteArray()
        return u16(command) + u24(pb.size) + pb
    }

    const val PASSKEY_LENGTH = 6
    private const val TITLE_BYTES = 64
    private const val DETAIL_BYTES = 512
    private const val PLACE_BYTES = 21
    private const val NUMBER_BYTES = 12
}
