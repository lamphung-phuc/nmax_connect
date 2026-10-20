package com.lampp.nmaxconnect.core.protocol.ccu

import com.lampp.nmaxconnect.core.protocol.tft.SpeedUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte-exact vectors derived from Y-Connect 3.9.0's motoconsdk encoders (docs/bike-protocol.md §6). */
class CcuProtocolTest {

    private fun hex(s: String) = s.split(' ').filter(String::isNotEmpty).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    private val ccuId = "00081234567890".toByteArray()
    private val passkey = "123456".toByteArray()

    // Engine record (id 1, 26 B): 3000 rpm, 42 km/h, timing 40-30, battery 0xB0, throttle 0x80, coolant 120-30, air 60-30, 2 DTCs.
    private val engine = hex("0b b8 2a 00 28 00 b0 00 80 00 00 00 00 00 78 3c 00 00 00 00 00 00 00 02 00 00")

    @Test fun sppBootstrapRequestMatchesSpec() {
        assertEquals("01 00 00 00 02 01 39 04", CcuSpp.encode(CcuMessages.bleConnectInformationRequest()).hex())
    }

    @Test fun controlMessagesMatchSdkEncoders() {
        assertEquals("00 02 07 00 00 12 34", CcuMessages.connectAppId(7).hex())
        assertEquals("01 36 2a", CcuMessages.aliveCheckResponse(0x2A).hex())
        assertEquals("01 03", CcuMessages.connectServerListRequest().hex())
        assertEquals("01 26 04 e0", CcuMessages.supportIdListRequest(1248).hex())
        assertEquals("01 37", CcuMessages.ignitionStateRequest().hex())
        assertEquals("00 06 05 00 c8", CcuMessages.vehicleInformationSubscribeInterval(5, 200).hex())
        assertEquals("00 0a 09 00 00 00 00 65 2c 0e 40", CcuMessages.vehicleSettingDatetime(9, 1_697_386_048L).hex())
        assertEquals("00 0c 03 0f", CcuMessages.languageTypeResponse(3, CcuLanguage.VIETNAMESE).hex())
    }

    @Test fun authenticationV2IsCommandThenRawCredentials() {
        assertArrayEquals(hex("01 01") + ccuId + passkey, CcuMessages.authenticationV2(ccuId, passkey))
    }

    @Test fun vehicleInformationIntervalIsLengthPrefixedProtobuf() {
        // {3: 1248, 4: packed [1, 72], 5: false (omitted), 6: 100}
        assertEquals("01 10 00 00 09 18 e0 09 22 02 01 48 30 64", CcuMessages.vehicleInformationInterval(1248, listOf(1, 72)).hex())
        assertEquals("01 0e 00 00 05 18 05 22 01 01", CcuMessages.vehicleInformationRequest(5, listOf(1)).hex())
    }

    @Test fun canIntervalIsLengthPrefixedProtobuf() {
        // {3: packed [574, 899], 4: 100}
        assertEquals("01 3b 00 00 08 1a 04 be 04 83 07 20 64", CcuMessages.canInterval(listOf(574, 899)).hex())
    }

    @Test fun parsesCanFramesAndInjection() {
        // {3: {1: 899, 2: 00 00 00 23 ..}}
        val pb = hex("1a 0d 08 83 07 12 08 00 00 00 23 00 00 00 00")
        val e = CcuEvent.parse(hex("01 14") + u24(pb.size) + pb) as CcuEvent.Can
        assertEquals(setOf(899), e.frames.keys)
        assertEquals(0.35f, CanInjection.decode(e.frames)!!, 1e-6f)
        assertNull(CanInjection.decode(mapOf(574 to hex("00 00 01"))))
    }

    @Test fun instantFuelEconomyFollowsYConnectFormula() {
        val fe = InstantFuelEconomy()
        repeat(30) { i ->
            fe.speed(45)
            fe.injection(i * 100L, 0.30f)
        }
        assertNull(fe.kmPerL) // block not 3 s yet
        fe.speed(45)
        fe.injection(3_000, 0.30f)
        assertEquals(45 / (3.6f * 0.30f), fe.kmPerL!!, 1e-3f) // 41.7 km/L

        fe.speed(50)
        fe.injection(3_100, 0f)
        fe.injection(6_100, 0f)
        assertEquals(41.67f, fe.kmPerL!!, 1e-2f) // no injection: keep last

        fe.speed(5)
        fe.injection(6_200, 0.1f)
        fe.injection(9_200, 0.1f)
        assertNull(fe.kmPerL) // too slow

        fe.speed(60)
        fe.injection(9_300, 0.01f)
        fe.injection(12_300, 0.01f)
        assertEquals(80f, fe.kmPerL!!, 0f) // capped
    }

    @Test fun parsesBleConnectInformation() {
        val e = CcuEvent.parse(hex("01 3a") + ccuId + passkey) as CcuEvent.BleConnectInformation
        assertEquals("00081234567890", e.ccuIdText)
        assertArrayEquals(passkey, e.passkey)
    }

    @Test fun parsesSmallControlReplies() {
        assertEquals(CcuEvent.Authentication(true), CcuEvent.parse(hex("01 02 01")))
        assertEquals(CcuEvent.Authentication(false), CcuEvent.parse(hex("01 02 00")))
        assertEquals(CcuEvent.AliveCheck(0x2A), CcuEvent.parse(hex("01 35 2a")))
        assertEquals(CcuEvent.IgnitionState(true), CcuEvent.parse(hex("01 38 01")))
        assertEquals(CcuEvent.IgnitionState(null), CcuEvent.parse(hex("01 38 07")))
        assertEquals(CcuEvent.CumulativeInjection(70_000), CcuEvent.parse(hex("01 20 00 01 11 70")))
        assertEquals(CcuEvent.ClockRequest, CcuEvent.parse(hex("00 09")))
        assertEquals(CcuEvent.LanguageRequest, CcuEvent.parse(hex("00 0b")))
        assertEquals(CcuEvent.CommandError(272, 4, "x"), CcuEvent.parse(hex("ff 01 01 10 04 78")))
        assertEquals(CcuEvent.LiveVehicle(42, 41, 0, true, SpeedUnit.KMH), CcuEvent.parse(hex("00 07 00 2a 00 29 00 00 01 00")))
    }

    @Test fun rejectsWrongLengths() {
        assertTrue(CcuEvent.parse(hex("01 02 01 00")) is CcuEvent.Malformed)
        assertTrue(CcuEvent.parse(hex("01 3a 00")) is CcuEvent.Malformed)
        assertTrue(CcuEvent.parse(hex("00 07 00 2a")) is CcuEvent.Malformed)
        assertTrue(CcuEvent.parse(hex("01")) is CcuEvent.Malformed)
    }

    @Test fun parsesServerAndSupportListsPackedOrNot() {
        assertEquals(CcuEvent.ServerList(listOf(1248)), CcuEvent.parse(hex("01 04 00 00 04 1a 02 e0 09")))
        assertEquals(CcuEvent.ServerList(listOf(1, 2)), CcuEvent.parse(hex("01 04 00 00 04 18 01 18 02")))
        assertEquals(CcuEvent.SupportIdList(1248, listOf(1, 72, 96)), CcuEvent.parse(hex("01 27 00 00 08 18 e0 09 22 03 01 48 60")))
    }

    @Test fun parsesAndDecodesVehicleInformation() {
        // {3: 1248, 4: {1: 1, 2: engine}, 4: {1: 72, 2: 00 01 e2 40}}
        val pb = hex("18 e0 09 22 1e 08 01 12 1a") + engine + hex("22 08 08 48 12 04 00 01 e2 40")
        val e = CcuEvent.parse(hex("01 0f") + u24(pb.size) + pb) as CcuEvent.VehicleInformation
        assertEquals(1248, e.server)
        assertEquals(setOf(1, 72), e.records.keys)

        val r = LocalRecord.decode(e.records)
        assertEquals(3000, r.engineRpm)
        assertEquals(42, r.speedKmh)
        assertEquals(10, r.ignitionTimingDeg)
        assertEquals(12.890625f, r.batteryVolts!!, 0f)
        assertEquals(62.5f, r.throttlePercent!!, 0f)
        assertEquals(90, r.coolantCelsius)
        assertEquals(30, r.airCelsius)
        assertEquals(2, r.dtcCount)
        assertEquals(123_456L, r.odometerHectometres)
        assertEquals(12_345.6, r.odometerKm!!, 1e-9)
    }

    @Test fun localRecordIgnoresShortEngineRecord() {
        assertEquals(LocalRecord(odometerHectometres = 10), LocalRecord.decode(mapOf(1 to ByteArray(10), 72 to hex("0a"))))
        assertEquals(listOf(1, 72), LocalRecord.wantedIds(1248))
        assertEquals(listOf(1), LocalRecord.wantedIds(7))
    }

    @Test fun truncatedProtobufIsMalformed() {
        assertTrue(CcuEvent.parse(hex("01 0f 00 00 05 22 1e 08 01")) is CcuEvent.Malformed)
    }

    @Test fun sppDecoderHandlesSplitReadsGarbageAndBadEndByte() {
        val reply = CcuSpp.encode(hex("01 3a") + ccuId + passkey)
        val badEnd = CcuSpp.encode(hex("01 02 01")).also { it[it.lastIndex] = 0x05 }
        val stream = hex("ff 00") + badEnd + reply
        val decoder = CcuSppDecoder()
        val frames = stream.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(1, frames.size)
        assertTrue(CcuEvent.parse(frames[0]) is CcuEvent.BleConnectInformation)
        assertTrue(decoder.rejected >= 1)
    }

    @Test fun ddtOutChunksFollowMtu() {
        assertEquals(16, CcuDdtOut.chunkSize(23))
        assertEquals(176, CcuDdtOut.chunkSize(512))
        assertEquals("07 00 01 2c", CcuDdtOut.header(7, 300).hex())

        val payload = ByteArray(300) { it.toByte() }
        val last = CcuDdtOut.chunk(7, payload, 288, mtu = 23)
        assertEquals("07 00 01 20", last.copyOfRange(0, 4).hex())
        assertEquals(12 + 4, last.size)

        assertTrue(CcuDdtOut.isActive(hex("01 07"), 7))
        assertTrue(!CcuDdtOut.isActive(hex("01 08"), 7))
        assertTrue(CcuDdtOut.isIdle(hex("00 00")))
    }

    @Test fun sppUuidAndBleNameRules() {
        assertEquals(CcuUuids.SPP_GENERIC, CcuUuids.sppFor("NMAX_0000" + "11111BB"))
        assertEquals(CcuUuids.SPP_YAMAHA, CcuUuids.sppFor("NMAX_0000" + "1234567"))
        assertNull(CcuUuids.sppFor("NMAX"))
        assertEquals("00081234567890", CcuUuids.ccuIdFromBleName("YCCU_00081234567890"))
        assertNull(CcuUuids.ccuIdFromBleName("YCCU_0008"))
        assertNull(CcuUuids.ccuIdFromBleName("XMAX_00081234567890"))
    }

    @Test fun requestIdsSkipZero() {
        val ids = CcuRequestIds(last = 254)
        assertEquals(listOf(255, 1, 2), List(3) { ids.next() })
    }

    @Test fun ddtInAcksEachChunkAndCompletesOnLength() {
        val rx = CcuDdtIn()
        assertNull("data before header", rx.data(hex("05 00 00 00 01")))
        val start = rx.header(hex("05 00 00 05"))!!
        assertEquals(CcuUuids.DDT_IN_ACK, start.writeTo)
        assertEquals("01 05", start.value.hex())

        val mid = rx.data(hex("05 00 00 00 01 04 00"))!!
        assertEquals(CcuUuids.DDT_IN_DATA, mid.writeTo)
        assertEquals("05", mid.value.hex())
        assertNull(mid.message)
        assertNull("wrong id", rx.data(hex("06 00 00 03 00 00")))

        val done = rx.data(hex("05 00 00 03 00 00"))!!
        assertEquals(CcuUuids.DDT_IN_ACK, done.writeTo)
        assertEquals("00 00", done.value.hex())
        assertEquals("01 04 00 00 00", done.message!!.hex())
    }

    @Test fun partAssemblerJoinsOutOfOrderPartsAndExpiresStale() {
        var now = 0L
        val rx = CcuPartAssembler(timeoutMs = 100) { now }
        assertNull(rx.feed(hex("00 07 02 02 cc dd")))
        assertEquals("aa bb cc dd", rx.feed(hex("00 07 01 02 aa bb"))!!.hex())
        assertEquals("01 02 01", rx.feed(hex("00 08 01 01 01 02 01"))!!.hex())

        assertNull(rx.feed(hex("00 09 01 02 aa")))
        now = 500
        assertNull("first part expired", rx.feed(hex("00 09 02 02 bb")))
        assertNull("part > total", rx.feed(hex("00 0a 03 02 bb")))
    }

    @Test fun notificationDataV2MatchesSdkEncoding() {
        val time = java.time.LocalDateTime.of(2026, 10, 16, 18, 45, 7)
        // {3: uint32 -1, 4: MESSAGE (omitted), 5..10: date/time, 11: "Lan", 12: "" (omitted), 13: "Chào"}
        assertEquals(
            "01 2f 00 00 1f 18 ff ff ff ff 0f 28 ea 0f 30 0a 38 10 40 12 48 2d 50 07 5a 03 4c 61 6e 6a 05 43 68 c3 a0 6f",
            CcuMessages.notificationDataV2(-1, isCall = false, time, "Lan", "", "Chào").hex(),
        )
        val call = CcuMessages.notificationDataV2(5, isCall = true, time, "Mẹ", "", " ")
        assertEquals(1, ProtoMessage(call.copyOfRange(5, call.size)).int(4))
    }

    @Test fun notificationTextIsCutOnUtf8BytesWithoutSplittingCharacters() {
        val time = java.time.LocalDateTime.of(2026, 1, 1, 0, 0, 0)
        val m = CcuMessages.notificationDataV2(1, false, time, "đ".repeat(70), "a" + "😀".repeat(20), "x".repeat(600))
        val pb = ProtoMessage(m.copyOfRange(5, m.size))
        assertEquals("đ".repeat(32), String(pb.bytes(11), Charsets.UTF_8)) // 2 B each → 64 B
        assertEquals("a" + "😀".repeat(15), String(pb.bytes(12), Charsets.UTF_8)) // 1 + 15×4 = 61 B, a 16th would overflow
        assertEquals(512, pb.bytes(13).size)
    }

    @Test fun musicMetaDataMatchesSdkLayout() {
        assertEquals(
            "01 16 01 41 01 42 03 43 c3 a9 00 00 c8 02 00 0a 00 00 0f",
            CcuMessages.musicMetaData("A", "B", "Cé", 200, CcuMusicState.PLAYING, 1.0f, 15).hex(),
        )
        val idle = CcuMessages.musicMetaData("", "", "", 0, CcuMusicState.UNAVAILABLE, 1.0f, 0)
        assertEquals("01 16 00 00 00 00 00 00 ff 00 0a 00 00 00", idle.hex())
        assertEquals(64, CcuMessages.musicMetaData("đ".repeat(40), "", "", 0, 1, 1f, 0)[2].toInt())
    }

    @Test fun parsesMusicButtons() {
        assertEquals(CcuEvent.MusicControl(CcuEvent.MusicControl.NEXT), CcuEvent.parse(hex("01 17 01")))
        assertEquals(CcuEvent.MusicControl(CcuEvent.MusicControl.PAUSE), CcuEvent.parse(hex("01 17 04")))
        assertTrue(CcuEvent.parse(hex("01 17 09")) is CcuEvent.Malformed)
    }

    @Test fun incomingCallMatchesSdkLayout() {
        assertEquals("01 18 03 41 6e 68 03 31 32 33 00", CcuMessages.incomingCallInformation("Anh", "123", missed = false).hex())
        assertEquals("01 18 00 00 01", CcuMessages.incomingCallInformation("", "", missed = true).hex())
        val long = CcuMessages.incomingCallInformation("đ".repeat(40), "+84901234567890", missed = false)
        assertEquals(64, long[2].toInt())
        assertEquals("+84901234567", String(long, 3 + 64 + 1, long[3 + 64].toInt()))
        assertEquals("01 34 01", CcuMessages.callChangeNotification(started = true).hex())
        assertEquals("01 34 02", CcuMessages.callChangeNotification(started = false).hex())
    }

    @Test fun parsesCallButtons() {
        assertEquals(CcuEvent.CallControl(CcuEvent.CallControl.REJECT), CcuEvent.parse(hex("01 19 00")))
        assertEquals(CcuEvent.CallControl(CcuEvent.CallControl.ANSWER), CcuEvent.parse(hex("01 19 01")))
        assertEquals(CcuEvent.CallControl(CcuEvent.CallControl.END), CcuEvent.parse(hex("01 19 02")))
        assertTrue(CcuEvent.parse(hex("01 19 03")) is CcuEvent.Malformed)
        assertTrue(CcuEvent.parse(hex("01 19 01 00")) is CcuEvent.Malformed)
    }

    @Test fun phoneStateCombinedPacksBitsLikeSdk() {
        // 87 % charging, call 60 %, media 33 %, 4 bars (5), thermal middle (1), headset
        assertEquals("01 3c d7 bc a1 57", CcuMessages.phoneStateCombined(87, true, 60, 33, 5, 1, true).hex())
        // clamped: no service, low thermal, no headset → only the on-hook bit
        assertEquals("01 3c 64 80 80 01", CcuMessages.phoneStateCombined(140, false, -5, 0, 0, 0, false).hex())
        assertEquals("01 2a 01", CcuMessages.phoneVolumeControllableResponse(true).hex())
        assertEquals(CcuEvent.VolumeControllableRequest, CcuEvent.parse(hex("01 29")))
        assertEquals(CcuEvent.VolumeControl(CcuEvent.VolumeControl.MEDIA_UP), CcuEvent.parse(hex("01 1d 05")))
        assertEquals(CcuEvent.VolumeControl(CcuEvent.VolumeControl.MEDIA_DOWN), CcuEvent.parse(hex("01 1d 06")))
        assertTrue(CcuEvent.parse(hex("01 1d 07")) is CcuEvent.Malformed)
    }

    @Test fun weatherMessagesMatchSdkLayout() {
        assertEquals("00 04 07 50 68 c6 b0 e1 bb 9d 6e 67 20 34", CcuMessages.weatherLocation(7, "Phường 4").hex())
        // 21-byte cap on whole characters: "Ch" ends at 20 B and "í" (2 B) would overflow
        val place = CcuMessages.weatherLocation(1, "Thành Phố Hồ Chí Minh")
        assertEquals("Thành Phố Hồ Ch", String(place, 3, place.size - 3, Charsets.UTF_8))
        // now, clear night, 27.5 °C (31.2 / 24.0), 40 % rain
        assertEquals(
            "00 05 09 00 80 01 13 01 38 00 f0 08",
            CcuMessages.weatherInfo(9, CcuWeather.SLOT_NOW, CcuWeather.SUNNY or CcuWeather.NIGHT, 27.5, 31.2, 24.0, 40).hex(),
        )
        assertEquals("ff dd", CcuMessages.weatherInfo(1, 1, 0, -3.5, 0.0, 0.0, 0).copyOfRange(5, 7).hex())
        assertEquals(listOf(1, 2, 3, 4, 5, 9), (1..6).map(CcuWeather::daySlot))
        assertEquals(listOf(6, 7, 8), (1..3).map(CcuWeather::hourSlot))
    }

    @Test fun parsesWeatherRequests() {
        assertEquals(CcuEvent.WeatherRequest(), CcuEvent.parse(hex("00 03 01")))
        // 10.7769 °N, 106.7009 °E as radians × 1e8
        val lat = Math.round(Math.toRadians(10.7769) * 1e8).toInt()
        val lng = Math.round(Math.toRadians(106.7009) * 1e8).toInt()
        val e = CcuEvent.parse(hex("00 03 00") + u32(lat) + u32(lng)) as CcuEvent.WeatherRequest
        assertEquals(10.7769, e.lat!!, 1e-6)
        assertEquals(106.7009, e.lng!!, 1e-6)
        assertTrue(CcuEvent.parse(hex("00 03 00 01")) is CcuEvent.Malformed)
    }
}
