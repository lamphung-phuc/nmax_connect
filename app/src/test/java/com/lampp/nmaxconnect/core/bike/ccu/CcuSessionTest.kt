package com.lampp.nmaxconnect.core.bike.ccu

import com.lampp.nmaxconnect.core.protocol.ccu.CcuMessages
import com.lampp.nmaxconnect.core.protocol.ccu.CcuSpp
import com.lampp.nmaxconnect.core.protocol.ccu.CcuSppDecoder
import com.lampp.nmaxconnect.core.protocol.ccu.CcuUuids
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CcuSessionTest {

    private fun hex(s: String) = s.split(' ').filter(String::isNotEmpty).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }
    private fun u24(n: Int) = byteArrayOf((n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun withLen(cmd: String, pb: ByteArray) = hex(cmd) + u24(pb.size) + pb

    private val ccuId = "00081234567890"
    private val engine = hex("0b b8 2a 00 28 00 b0 00 80 00 00 00 00 00 78 3c 00 00 00 00 00 00 00 02 00 00")
    private val serverList = withLen("01 04", hex("1a 02 e0 09")) // {3: packed [1248]}
    private val supportIds = withLen("01 27", hex("18 e0 09 22 03 01 48 60")) // {3: 1248, 4: [1, 72, 96]}
    private val vehiclePush = withLen("01 0f", hex("18 e0 09 22 1e 08 01 12 1a") + engine + hex("22 08 08 48 12 04 00 01 e2 40"))

    /** The bike's SPP side: answers 313 with 314. */
    private inner class FakeSpp {
        private val toPhone = LinkedBlockingQueue<ByteArray>()
        private val decoder = CcuSppDecoder()
        val bleInfoRequests = CopyOnWriteArrayList<ByteArray>()

        val input = object : InputStream() {
            private var pending = ByteArray(0)
            private var pos = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
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
                for (frame in decoder.feed(b.copyOfRange(off, off + len))) {
                    bleInfoRequests += frame
                    toPhone.put(CcuSpp.encode(hex("01 3a") + ccuId.toByteArray() + "123456".toByteArray()))
                }
            }
        }
    }

    /** The CCU's GATT side: real DDT-out handshake, DDT-in / v2 replies, scripted behaviour. */
    private inner class FakeGatt(
        private val mtu: Int = 512,
        private val authOk: Boolean = true,
        private val pushData: Boolean = true,
        /** DDT-out headers after this many go unanswered, like a CCU that ignores a command. */
        private val answerDdtHeaders: Int = Int.MAX_VALUE,
    ) : CcuGatt {
        private val channel = Channel<GattNotification>(Channel.UNLIMITED)
        override val notifications: Flow<GattNotification> = channel.receiveAsFlow()

        val enabled = CopyOnWriteArrayList<Pair<UUID, Boolean>>()
        val control = CopyOnWriteArrayList<ByteArray>()
        val ddtMessages = CopyOnWriteArrayList<ByteArray>()
        var ddtChunks = 0
        private var ddtHeaders = 0

        private var status = hex("00 00")
        private var inId: Byte = 0
        private var inBuf = ByteArray(0)
        private val outChunks = ArrayDeque<ByteArray>()
        private var nextOutId = 0x40

        override suspend fun requestMtu(mtu: Int) = this.mtu
        override suspend fun enable(uuid: UUID, indicate: Boolean) { enabled += uuid to indicate }
        override suspend fun read(uuid: UUID): ByteArray = synchronized(this) { status }
        override fun close() = Unit

        fun notify(uuid: UUID, value: ByteArray) = channel.trySend(GattNotification(uuid, value))
        fun drop() = channel.close(IOException("link lost"))

        override suspend fun write(uuid: UUID, value: ByteArray): Unit = synchronized(this) {
            when (uuid) {
                CcuUuids.CONTROL_WRITE -> { control += value; handle(value) }
                CcuUuids.DDT_OUT_HEADER -> if (ddtHeaders++ < answerDdtHeaders) {
                    inId = value[0]
                    val size = ((value[1].toInt() and 0xFF) shl 16) or ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
                    inBuf = ByteArray(size)
                    status = byteArrayOf(1, inId)
                    notify(CcuUuids.DDT_OUT_STATUS, status)
                }
                CcuUuids.DDT_OUT_DATA -> {
                    check(value[0] == inId) { "chunk for id ${value[0]}" }
                    check(value.size - 4 <= minOf(mtu - 3, 180) - 4) { "chunk ${value.size} B too big for MTU $mtu" }
                    ddtChunks++
                    val off = ((value[1].toInt() and 0xFF) shl 16) or ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
                    value.copyInto(inBuf, off, 4, value.size)
                    if (off + value.size - 4 == inBuf.size) {
                        status = hex("00 00")
                        notify(CcuUuids.DDT_OUT_STATUS, status)
                        ddtMessages += inBuf
                        handle(inBuf)
                    }
                }
                CcuUuids.DDT_IN_ACK, CcuUuids.DDT_IN_DATA ->
                    if (!(value.size == 2 && value[0].toInt() == 0)) outChunks.removeFirstOrNull()?.let { notify(CcuUuids.DDT_IN_DATA, it) }
            }
        }

        /** CCU → phone over DDT-in in 10-byte chunks, each released by the phone's ack. */
        fun sendDdtIn(message: ByteArray) {
            val id = (nextOutId++).toByte()
            notify(CcuUuids.DDT_IN_HEADER, byteArrayOf(id) + u24(message.size))
            message.toList().chunked(10).forEachIndexed { i, part -> outChunks.addLast(byteArrayOf(id) + u24(i * 10) + part.toByteArray()) }
        }

        /** CCU → phone over DDT v2 in two parts, second part first. */
        fun sendV2(message: ByteArray) {
            val half = message.size / 2
            notify(CcuUuids.DDT_V2_NOTIFY, hex("00 01 02 02") + message.copyOfRange(half, message.size))
            notify(CcuUuids.DDT_V2_NOTIFY, hex("00 01 01 02") + message.copyOfRange(0, half))
        }

        private fun handle(m: ByteArray) {
            when (m.copyOfRange(0, 2).hex()) {
                "01 01" -> notify(CcuUuids.CONTROL_NOTIFY, hex(if (authOk) "01 02 01" else "01 02 00"))
                "01 03" -> sendDdtIn(serverList)
                "01 26" -> sendV2(supportIds)
                "01 10" -> if (pushData) sendDdtIn(vehiclePush)
            }
        }
    }

    private fun newSession(spp: FakeSpp, gatts: ArrayDeque<FakeGatt>, timeouts: CcuTimeouts = CcuTimeouts()) =
        CcuSession(spp.input, spp.output, { id -> assertEquals(ccuId, id); gatts.removeFirst() }, Dispatchers.IO, timeouts)

    @Test fun reachesReadyAndPublishesVehicleData() = runBlocking {
        val spp = FakeSpp()
        val gatt = FakeGatt()
        val session = newSession(spp, ArrayDeque(listOf(gatt)))
        val runner = launch(Dispatchers.IO) { session.run() }

        assertEquals(CcuLinkState.Ready(ccuId, listOf(1248)), withTimeout(5_000) { session.state.first { it is CcuLinkState.Ready } })
        val v = withTimeout(5_000) { session.vehicle.first { it != null } }!!
        assertEquals(3000, v.engineRpm)
        assertEquals(12_345.6, v.odometerKm!!, 1e-9)

        assertEquals(CcuUuids.NOTIFY.map { it to false } + CcuUuids.INDICATE.map { it to true }, gatt.enabled.toList())
        assertEquals("00 02", gatt.control.first().copyOfRange(0, 2).hex())
        assertArrayEquals(CcuMessages.authenticationV2(ccuId.toByteArray(), "123456".toByteArray()), gatt.ddtMessages[0])
        assertArrayEquals(CcuMessages.vehicleInformationInterval(1248, listOf(1, 72)), gatt.ddtMessages[1])
        withTimeout(5_000) { while (gatt.ddtMessages.size < 3) delay(10) } // CAN subscription goes out after Ready
        assertArrayEquals(CcuMessages.canInterval(listOf(574, 899)), gatt.ddtMessages[2])
        runner.cancelAndJoin()
    }

    @Test fun smallMtuSplitsDdtIntoChunks() = runBlocking {
        val spp = FakeSpp()
        val gatt = FakeGatt(mtu = 23)
        val session = newSession(spp, ArrayDeque(listOf(gatt)))
        val runner = launch(Dispatchers.IO) { session.run() }
        withTimeout(5_000) { session.state.first { it is CcuLinkState.Ready } }
        // auth 22 B → 16 + 6; subscription 14 B → 1 chunk; CAN subscription 13 B → 1 chunk
        withTimeout(5_000) { while (gatt.ddtChunks < 4) delay(10) }
        assertEquals(4, gatt.ddtChunks)
        runner.cancelAndJoin()
    }

    @Test fun unansweredCanSubscriptionDoesNotDelayReady() = runBlocking {
        val spp = FakeSpp()
        val gatt = FakeGatt(answerDdtHeaders = 2) // auth + 272 answered, 315 ignored
        val session = newSession(spp, ArrayDeque(listOf(gatt)), CcuTimeouts(ddtStepMs = 60_000))
        val runner = launch(Dispatchers.IO) { session.run() }
        withTimeout(5_000) { session.state.first { it is CcuLinkState.Ready } }
        runner.cancelAndJoin()
    }

    @Test fun keepAliveIsEchoed() = runBlocking {
        val spp = FakeSpp()
        val gatt = FakeGatt()
        val session = newSession(spp, ArrayDeque(listOf(gatt)))
        val runner = launch(Dispatchers.IO) { session.run() }
        withTimeout(5_000) { session.state.first { it is CcuLinkState.Ready } }

        gatt.notify(CcuUuids.CONTROL_NOTIFY, hex("01 35 2a"))
        withTimeout(2_000) { while (gatt.control.none { it.hex() == "01 36 2a" }) delay(10) }
        runner.cancelAndJoin()
    }

    @Test fun sendDdtGoesOutOnlyWhileReady() = runBlocking {
        val spp = FakeSpp()
        val gatt = FakeGatt()
        val session = newSession(spp, ArrayDeque(listOf(gatt)))
        val note = CcuMessages.notificationDataV2(1, false, java.time.LocalDateTime.of(2026, 1, 1, 0, 0), "A", "", "B")
        assertEquals("dropped before Ready", false, session.sendDdt(note))

        val runner = launch(Dispatchers.IO) { session.run() }
        withTimeout(5_000) { session.state.first { it is CcuLinkState.Ready } }
        assertEquals(true, session.sendDdt(note))
        assertArrayEquals(note, gatt.ddtMessages.last())
        runner.cancelAndJoin()
    }

    @Test fun saysVolumeIsControllableAndSendsControlWhileReady() = runBlocking {
        val spp = FakeSpp()
        val gatt = FakeGatt()
        val session = newSession(spp, ArrayDeque(listOf(gatt)))
        val status = CcuMessages.phoneStateCombined(80, false, 50, 50, 3, 0, false)
        assertEquals("dropped before Ready", false, session.sendControl(status))
        val runner = launch(Dispatchers.IO) { session.run() }
        withTimeout(5_000) { session.state.first { it is CcuLinkState.Ready } }

        assertEquals(true, session.sendControl(status))
        gatt.notify(CcuUuids.CONTROL_NOTIFY, hex("01 29"))
        withTimeout(2_000) { while (gatt.control.none { it.hex() == "01 2a 01" }) delay(10) }
        assertTrue(gatt.control.any { it.contentEquals(status) })
        runner.cancelAndJoin()
    }

    @Test fun answersClockAndLanguageRequests() = runBlocking {
        val spp = FakeSpp()
        val gatt = FakeGatt()
        val session = CcuSession(
            spp.input, spp.output, { gatt }, Dispatchers.IO,
            localEpochSeconds = { 1_697_386_048L }, language = { 15 },
        )
        val runner = launch(Dispatchers.IO) { session.run() }
        withTimeout(5_000) { session.state.first { it is CcuLinkState.Ready } }

        gatt.notify(CcuUuids.CONTROL_NOTIFY, hex("00 09"))
        gatt.notify(CcuUuids.CONTROL_NOTIFY, hex("00 0b"))
        fun sent(cmd: String) = gatt.control.any { it.copyOfRange(0, 2).hex() == cmd }
        withTimeout(2_000) { while (!sent("00 0a") || !sent("00 0c")) delay(10) }
        val clock = gatt.control.single { it.copyOfRange(0, 2).hex() == "00 0a" }
        assertEquals("00 00 00 00 65 2c 0e 40", clock.copyOfRange(3, 11).hex())
        val lang = gatt.control.single { it.copyOfRange(0, 2).hex() == "00 0c" }
        assertEquals(15, lang[3].toInt())
        runner.cancelAndJoin()
    }

    @Test(expected = CcuAuthRejected::class)
    fun rejectedAuthEndsTheSession(): Unit = runBlocking {
        val spp = FakeSpp()
        withTimeout(5_000) { newSession(spp, ArrayDeque(listOf(FakeGatt(authOk = false)))).run() }
    }

    @Test fun bleDropWithSppUpAsksForBleInfoAgain() = runBlocking {
        val spp = FakeSpp()
        val first = FakeGatt()
        val second = FakeGatt()
        val session = newSession(spp, ArrayDeque(listOf(first, second)))
        val runner = launch(Dispatchers.IO) { session.run() }
        withTimeout(5_000) { session.state.first { it is CcuLinkState.Ready } }

        first.drop()
        withTimeout(5_000) { while (second.ddtMessages.size < 2) delay(10) } // second link authenticated + subscribed
        assertEquals(2, spp.bleInfoRequests.size)
        withTimeout(5_000) { session.state.first { it is CcuLinkState.Ready } }
        runner.cancelAndJoin()
    }

    @Test fun silentCcuIsResubscribedOnceThenDropped() = runBlocking {
        val spp = FakeSpp()
        val quiet = FakeGatt(pushData = false)
        val session = newSession(spp, ArrayDeque(listOf(quiet, FakeGatt())), CcuTimeouts(vehicleSilenceMs = 300))
        val failed = async(Dispatchers.IO) { runCatching { session.run() } }

        withTimeout(5_000) { while (spp.bleInfoRequests.size < 2) delay(10) } // gave up on the quiet link
        val subscribes = quiet.ddtMessages.count { it.copyOfRange(0, 2).hex() == "01 10" }
        assertEquals("subscribed, then resubscribed once", 2, subscribes)
        withTimeout(5_000) { session.vehicle.first { it != null } } // the next link pushes data
        failed.cancelAndJoin()
    }
}
