package com.lampp.nmaxconnect.core.protocol.tft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviLiteProtocolTest {

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    // Vectors from docs/bike-protocol.md §2.3
    private val esnAck = "6e 41 6c 40 01 06 51 02 00 00 00 00 1a 4d 1c ec 01 00"
    private val authRequest = "6e 41 6c 40 01 06 21 08 00 00 00 01 6f 6d 5a e2 4e 07 00 01 00 00 00 00"
    private val navStatusOff = "6e 41 6c 40 01 06 02 02 00 00 00 00 11 ea df c1 00 00"
    private val dayNightDay = "6e 41 6c 40 01 06 1f 02 00 00 00 00 6d e3 75 5b 01 00"
    private val imageAckSeq5 = "6e 41 6c 40 01 03 50 02 00 00 00 00 5e 95 d8 c5 05 00"
    private val startContentImage = "6e 41 6c 40 01 01 37 02 00 00 00 00 2e 00 08 07 01 00"

    @Test fun crcMatchesMpeg2CheckValue() {
        assertEquals(0x0376E6E7, crc32Mpeg2("123456789".toByteArray()))
    }

    @Test fun outgoingMessagesMatchSpecVectors() {
        assertEquals(esnAck, TftMessages.esnAck(true).hex())
        assertEquals(authRequest, TftMessages.authRequest().hex())
        assertEquals(navStatusOff, TftMessages.navigationStatus(false).hex())
        assertEquals(dayNightDay, TftMessages.dayNight(DayNight.DAY).hex())
    }

    @Test fun decodesIncomingSpecVectors() {
        val events = NaviLiteDecoder().feed(hex("$imageAckSeq5 $startContentImage")).map(TftEvent::parse)
        assertEquals(listOf(TftEvent.ImageAck(5), TftEvent.StartContent(ContentType.NAVI_IMAGE)), events)
    }

    @Test fun decoderHandlesByteByByteReads() {
        val decoder = NaviLiteDecoder()
        val out = hex(imageAckSeq5).flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(listOf(TftEvent.ImageAck(5)), out.map(TftEvent::parse))
    }

    @Test fun decoderResyncsAfterGarbageAndBadCrc() {
        val corrupt = hex(imageAckSeq5).also { it[16] = 0x06 } // payload changed, CRC now wrong
        val stream = byteArrayOf(0x00, 0x6E, 0x41) + corrupt + hex(startContentImage)
        val decoder = NaviLiteDecoder()
        val events = decoder.feed(stream).map(TftEvent::parse)
        assertEquals(listOf(TftEvent.StartContent(ContentType.NAVI_IMAGE)), events)
        assertEquals(1, decoder.rejected)
    }

    @Test fun decoderSkipsImplausibleLength() {
        val bogus = NaviLite.MAGIC + byteArrayOf(1, 3, 80, 0, 0, 0, 0x7F, 0) + ByteArray(4)
        val decoder = NaviLiteDecoder()
        assertEquals(listOf(TftEvent.ImageAck(5)), decoder.feed(bogus + hex(imageAckSeq5)).map(TftEvent::parse))
    }

    @Test fun encodeThenDecodeRoundTrip() {
        val packet = NaviLiteDecoder().feed(TftMessages.imageFrame(ImageType.NAVIGATION_EXPANDED, 65535, byteArrayOf(1, 2, 3))).single()
        assertEquals(FrameType.MCU_UPDATE, packet.frameType)
        assertEquals(TftService.IMAGE_FRAME, packet.service)
        assertArrayEquals(byteArrayOf(3, -1, -1, 1, 2, 3), packet.payload)
    }

    @Test fun secDataIsXorDecodedAndPicksDisplayModel() {
        val clear = "006-B4920\u0000\u0000\u0000".toByteArray() + byteArrayOf(9, 8, 7, 6)
        val raw = ByteArray(clear.size) { (clear[it].toInt() xor 0x0A).toByte() }
        val event = TftEvent.parse(NaviLitePacket(FrameType.MOBILE_UPDATE, TftService.SEC_DATA, PayloadType.POINTER, raw))
        event as TftEvent.SecData
        assertEquals("006-B4920", event.partNumber)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), event.key)
        assertEquals(DisplayModel.IMWW23, event.display)
        assertEquals(240, event.display.height)
        assertEquals(DisplayModel.IXWW22, DisplayModel.fromPartNumber("006-B3952-00"))
    }

    @Test fun vehicleSpeedAndValidation() {
        val speed = NaviLitePacket(FrameType.MOBILE_UPDATE, TftService.VEHICLE_SPEED, PayloadType.POINTER, byteArrayOf(0, 42, 0))
        assertEquals(TftEvent.VehicleSpeed(42, SpeedUnit.KMH), TftEvent.parse(speed))

        val shortValue = NaviLitePacket(FrameType.MOBILE_UPDATE, TftService.AUTH_ACK, PayloadType.VALUE, byteArrayOf(1))
        assertTrue(TftEvent.parse(shortValue) is TftEvent.Malformed)

        val wrongFrame = NaviLitePacket(FrameType.MOBILE_UPDATE, TftService.ZOOM_IN, PayloadType.VALUE, ByteArray(0))
        assertTrue(TftEvent.parse(wrongFrame) is TftEvent.Malformed)
    }

    @Test fun startRouteFieldOrderIsItemThenList() {
        val p = NaviLitePacket(FrameType.MOBILE_REQUEST, TftService.START_ROUTE, PayloadType.POINTER, byteArrayOf(2, 0, 1, 0, 1))
        assertEquals(TftEvent.StartRoute(itemIndex = 2, listIndex = 1, option = RouteOption.NEW_ROUTE), TftEvent.parse(p))
    }

    @Test fun poiItemLayoutPutsUnitBeforeName() {
        val packet = NaviLiteDecoder().feed(
            TftMessages.poiItem(PoiList.GAS_STATIONS, 3, 1, PoiDirection.RIGHT, 1.5f, "km", "Cây xăng"),
        ).single()
        assertEquals(TftService.GAS_POI_DATA, packet.service)
        // item=3, list=1, dir=3, nameLen=8, unitLen=2, 1.5f LE, "km", "Cay xang"
        assertEquals("03 00 01 00 03 08 02 00 00 c0 3f 6b 6d 43 61 79 20 78 61 6e 67", packet.payload.hex())
    }

    @Test fun dashAsciiStripsVietnameseDiacritics() {
        assertEquals("Duong Tran Phu - Q5", String(NaviLite.dashAscii("Đường  Trần Phú · Q5")))
        assertEquals(64, NaviLite.dashAscii("x".repeat(100), NaviLite.MAX_ROAD_NAME).size)
    }
}
