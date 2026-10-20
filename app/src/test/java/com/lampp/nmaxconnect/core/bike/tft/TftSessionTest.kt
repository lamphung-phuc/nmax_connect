package com.lampp.nmaxconnect.core.bike.tft

import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.core.protocol.tft.FrameType
import com.lampp.nmaxconnect.core.protocol.tft.ImageType
import com.lampp.nmaxconnect.core.protocol.tft.NaviLite
import com.lampp.nmaxconnect.core.protocol.tft.NaviLiteDecoder
import com.lampp.nmaxconnect.core.protocol.tft.NaviLitePacket
import com.lampp.nmaxconnect.core.protocol.tft.PayloadType
import com.lampp.nmaxconnect.core.protocol.tft.TftService
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TftSessionTest {

    /** Plays the dash: feeds bytes to the session and collects the packets it writes. */
    private class FakeTft {
        private val toPhone = LinkedBlockingQueue<ByteArray>()
        private val fromPhone = LinkedBlockingQueue<NaviLitePacket>()
        private val decoder = NaviLiteDecoder()

        val input = object : InputStream() {
            private var pending = ByteArray(0)
            private var pos = 0

            @Volatile private var closed = false
            override fun close() {
                closed = true
            }
            override fun read(): Int = throw UnsupportedOperationException()

            @Suppress("ReturnCount")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (closed) return -1
                if (pos == pending.size) {
                    pending = toPhone.poll(20, TimeUnit.MILLISECONDS) ?: return 0
                    pos = 0
                }
                val n = minOf(len, pending.size - pos)
                pending.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }

        val output = object : OutputStream() {
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray, off: Int, len: Int) {
                decoder.feed(b.copyOfRange(off, off + len)).forEach(fromPhone::put)
            }
        }

        fun send(frame: Int, service: Int, type: Int, payload: ByteArray) =
            toPhone.put(NaviLite.encode(frame, service, type, payload))

        fun sendValue(frame: Int, service: Int, v: Int) =
            send(frame, service, PayloadType.VALUE, byteArrayOf(v.toByte(), (v shr 8).toByte()))

        fun expect(vararg services: Int): List<NaviLitePacket> = services.map { want ->
            val p = fromPhone.poll(2, TimeUnit.SECONDS) ?: error("phone sent nothing, wanted $want")
            assertEquals("service", want, p.service)
            p
        }

        fun authenticate(partNumber: String = "006-B4160"): List<NaviLitePacket> {
            send(FrameType.MOBILE_UPDATE, TftService.ESN, PayloadType.POINTER, "ESN0123456".toByteArray())
            expect(TftService.ESN_ACK, TftService.AUTH_REQUEST)
            val clear = partNumber.padEnd(12, '\u0000').toByteArray() + byteArrayOf(1, 2, 3, 4)
            val sealed = ByteArray(clear.size) { (clear[it].toInt() xor 0x0A).toByte() }
            send(FrameType.MOBILE_UPDATE, TftService.SEC_DATA, PayloadType.POINTER, sealed)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), expect(TftService.SEC_DATA_ACK).single().payload)
            sendValue(FrameType.MOBILE_UPDATE, TftService.AUTH_ACK, 1)
            return expect(
                TftService.NAVIGATION_STATUS, TftService.HOME_SETTING, TftService.OFFICE_SETTING, TftService.GPS_STATUS,
                TftService.APP_SETTING, TftService.DAY_NIGHT_MODE, TftService.MAP_ZOOM_LEVEL,
            )
        }
    }

    @Test fun initialStateCarriesSavedHomeWork() = runBlocking {
        val tft = FakeTft()
        val session = TftSession(tft.input, tft.output, Dispatchers.IO) { true to false }
        val reader = launch(Dispatchers.IO) { session.run() }
        val initial = tft.authenticate()
        assertEquals(1, initial[1].payload[0].toInt()) // home set
        assertEquals(0, initial[2].payload[0].toInt()) // work not set
        reader.cancelAndJoin()
    }

    @Test fun handshakeThenStreamOneFrame() = runBlocking {
        val tft = FakeTft()
        val session = TftSession(tft.input, tft.output, Dispatchers.IO)
        val reader = launch(Dispatchers.IO) { session.run() }

        tft.authenticate()
        assertEquals(
            TftLinkState.Ready(DisplayModel.IMWW23, streaming = false),
            withTimeout(2_000) {
                session.state.first { it is TftLinkState.Ready }
            }
        )
        assertFalse("no video before the dash asks", session.sendFrame(ByteArray(3000), ImageType.NAVIGATION))

        tft.sendValue(FrameType.MOBILE_REQUEST, TftService.START_CONTENT, 1)
        withTimeout(2_000) { session.state.first { (it as? TftLinkState.Ready)?.streaming == true } }

        val sent = async(Dispatchers.IO) { session.sendFrame(ByteArray(100) { 7 }, ImageType.NAVIGATION_EXPANDED) }
        val frame = tft.expect(TftService.IMAGE_FRAME).single()
        assertEquals(ImageType.NAVIGATION_EXPANDED, frame.payload[0].toInt())
        assertEquals("small JPEG padded to 2050 B", 3 + 2050, frame.payload.size)
        tft.sendValue(FrameType.MOBILE_UPDATE, TftService.IMAGE_ACK, 0)
        assertTrue(sent.await())

        reader.cancelAndJoin()
    }

    @Test fun stopContentReleasesPendingFrameAndRepliesImageStopped() = runBlocking {
        val tft = FakeTft()
        val session = TftSession(tft.input, tft.output, Dispatchers.IO)
        val reader = launch(Dispatchers.IO) { session.run() }
        tft.authenticate()
        tft.sendValue(FrameType.MOBILE_REQUEST, TftService.START_CONTENT, 1)
        withTimeout(2_000) { session.state.first { (it as? TftLinkState.Ready)?.streaming == true } }

        val sent = async(Dispatchers.IO) { session.sendFrame(ByteArray(3000), ImageType.NAVIGATION) }
        tft.expect(TftService.IMAGE_FRAME)
        tft.sendValue(FrameType.MOBILE_REQUEST, TftService.STOP_CONTENT, 1)
        tft.expect(TftService.IMAGE_STOPPED)
        assertFalse(sent.await())

        reader.cancelAndJoin()
    }

    @Test
    fun missingAckEndsTheLink(): Unit = runBlocking {
        val tft = FakeTft()
        val session = TftSession(tft.input, tft.output, Dispatchers.IO, ackTimeoutMs = 200)
        val reader = async(Dispatchers.IO) { runCatching { session.run() } }
        tft.authenticate()
        tft.sendValue(FrameType.MOBILE_REQUEST, TftService.START_CONTENT, 1)
        withTimeout(2_000) { session.state.first { (it as? TftLinkState.Ready)?.streaming == true } }
        val sent = runCatching { session.sendFrame(ByteArray(3000), ImageType.NAVIGATION) }
        assertTrue(sent.exceptionOrNull() is IOException)
        assertTrue("run() ends so the owner reconnects", withTimeout(2_000) { reader.await() }.isFailure)
    }

    @Test(expected = IOException::class)
    fun rejectedAuthEndsTheSession(): Unit = runBlocking {
        val tft = FakeTft()
        val session = TftSession(tft.input, tft.output, Dispatchers.IO)
        tft.sendValue(FrameType.MOBILE_UPDATE, TftService.AUTH_ACK, 0)
        withTimeout(2_000) { session.run() }
    }
}
