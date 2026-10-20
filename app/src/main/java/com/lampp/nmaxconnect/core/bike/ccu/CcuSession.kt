package com.lampp.nmaxconnect.core.bike.ccu

import com.lampp.nmaxconnect.core.protocol.ccu.CanInjection
import com.lampp.nmaxconnect.core.protocol.ccu.CcuDdtIn
import com.lampp.nmaxconnect.core.protocol.ccu.CcuDdtOut
import com.lampp.nmaxconnect.core.protocol.ccu.CcuEvent
import com.lampp.nmaxconnect.core.protocol.ccu.CcuLanguage
import com.lampp.nmaxconnect.core.protocol.ccu.CcuMessages
import com.lampp.nmaxconnect.core.protocol.ccu.CcuPartAssembler
import com.lampp.nmaxconnect.core.protocol.ccu.CcuRequestIds
import com.lampp.nmaxconnect.core.protocol.ccu.CcuSpp
import com.lampp.nmaxconnect.core.protocol.ccu.CcuSppDecoder
import com.lampp.nmaxconnect.core.protocol.ccu.CcuUuids
import com.lampp.nmaxconnect.core.protocol.ccu.InstantFuelEconomy
import com.lampp.nmaxconnect.core.protocol.ccu.LocalRecord
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A characteristic change from the CCU. */
class GattNotification(val uuid: UUID, val value: ByteArray)

/**
 * One connected, service-discovered GATT link to the CCU. The Android side serializes the ops
 * (one in flight) and throws IOException when the link drops.
 */
interface CcuGatt {
    suspend fun requestMtu(mtu: Int): Int
    suspend fun enable(uuid: UUID, indicate: Boolean)
    suspend fun write(uuid: UUID, value: ByteArray)
    suspend fun read(uuid: UUID): ByteArray

    /** Hot stream of notifications/indications; completes or throws when the link drops. */
    val notifications: Flow<GattNotification>
    fun close()
}

sealed interface CcuLinkState {
    data object Bootstrapping : CcuLinkState
    data class ConnectingBle(val ccuId: String) : CcuLinkState
    data class Authenticating(val ccuId: String) : CcuLinkState
    data class Ready(val ccuId: String, val servers: List<Int>) : CcuLinkState
}

/** Thrown when the CCU refuses our credentials; reconnecting won't help. */
class CcuAuthRejected : IOException("CCU rejected authentication")

/** Timeouts and retries from Y-Connect's pairing State enum and DDT sessions. */
data class CcuTimeouts(
    val bleInfoMs: Long = 5_000,
    val bleInfoTries: Int = 5,
    val authMs: Long = 10_000,
    val authTries: Int = 5,
    val replyMs: Long = 5_000,
    val ddtStepMs: Long = 3_000,
    val vehicleSilenceMs: Long = 5_000,
    val bleFailures: Int = 5,
)

/**
 * One conversation with the CCU (docs/bike-protocol.md §6): SPP 313/314 → BLE → MTU + subscriptions →
 * auth (2 + 257) → 259/294/272 → vehicle data pushes. The SPP socket stays open and supervises BLE:
 * when BLE drops, ask 313 again. Returns only by throwing; IOException = reconnect, [CcuAuthRejected] = stop.
 *
 * [openGatt] finds the CCU by id (scan/bond/connect) and returns a discovered link, or throws IOException.
 */
class CcuSession(
    private val sppInput: InputStream,
    private val sppOutput: OutputStream,
    private val openGatt: suspend (ccuId: String) -> CcuGatt,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeouts: CcuTimeouts = CcuTimeouts(),
    private val localEpochSeconds: () -> Long = ::localEpochSecondsNow,
    private val language: () -> Int = ::phoneLanguage,
) {
    private val _state = MutableStateFlow<CcuLinkState>(CcuLinkState.Bootstrapping)
    val state: StateFlow<CcuLinkState> = _state.asStateFlow()

    private val _vehicle = MutableStateFlow<LocalRecord?>(null)

    /** Latest engine/ODO values; each 271 push updates only the ids it carries. */
    val vehicle: StateFlow<LocalRecord?> = _vehicle.asStateFlow()

    private val _events = MutableSharedFlow<CcuEvent>(extraBufferCapacity = 64)

    /** Every parsed CCU message (ignition, errors, unknown commands, …) for the app layer. */
    val events: SharedFlow<CcuEvent> = _events.asSharedFlow()

    private val ids = CcuRequestIds()

    @Volatile private var readyLink: Link? = null

    /** Sends a control message (e.g. [CcuMessages.phoneStateCombined]) on the live link; false when not Ready or it fails. */
    suspend fun sendControl(message: ByteArray): Boolean {
        val link = readyLink ?: return false
        return try {
            link.control(message)
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Sends a phone → CCU bulk message (e.g. [CcuMessages.notificationDataV2]) on the live link.
     * Returns false when the link isn't Ready or the transfer fails; the link itself is left alone.
     */
    suspend fun sendDdt(payload: ByteArray): Boolean {
        val link = readyLink ?: return false
        return try {
            link.sendDdt(payload)
            true
        } catch (e: IOException) {
            false
        }
    }

    suspend fun run() = coroutineScope {
        val spp = Channel<CcuEvent>(Channel.UNLIMITED)
        launch(ioDispatcher) { readSpp(spp) }

        var failures = 0
        while (true) {
            _state.value = CcuLinkState.Bootstrapping
            val info = requestBleInfo(spp)
            _state.value = CcuLinkState.ConnectingBle(info.ccuIdText)
            try {
                val gatt = openGatt(info.ccuIdText)
                try {
                    runGatt(gatt, info) { failures = 0 }
                } finally {
                    readyLink = null
                    gatt.close()
                }
            } catch (e: CcuAuthRejected) {
                throw e
            } catch (e: IOException) {
                if (++failures >= timeouts.bleFailures) throw IOException("BLE failed $failures times", e)
            }
        }
    }

    private suspend fun readSpp(out: Channel<CcuEvent>) {
        val decoder = CcuSppDecoder()
        val buf = ByteArray(256)
        while (currentCoroutineContext().isActive) {
            val n = sppInput.read(buf)
            if (n < 0) throw EOFException("CCU closed SPP")
            for (frame in decoder.feed(buf, n)) out.send(CcuEvent.parse(frame).also { _events.tryEmit(it) })
        }
    }

    private suspend fun requestBleInfo(spp: Channel<CcuEvent>): CcuEvent.BleConnectInformation {
        repeat(timeouts.bleInfoTries) {
            withContext(ioDispatcher) {
                sppOutput.write(CcuSpp.encode(CcuMessages.bleConnectInformationRequest()))
                sppOutput.flush()
            }
            withTimeoutOrNull(timeouts.bleInfoMs) { spp.awaitEvent<CcuEvent.BleConnectInformation>() }?.let { return it }
        }
        throw IOException("No BLE info (314) from CCU")
    }

    /** Runs one BLE link until it fails; [onReady] fires once the vehicle-data subscription is up. */
    private suspend fun runGatt(gatt: CcuGatt, info: CcuEvent.BleConnectInformation, onReady: () -> Unit) = coroutineScope {
        val mtu = gatt.requestMtu(MTU)
        CcuUuids.NOTIFY.forEach { gatt.enable(it, indicate = false) }
        CcuUuids.INDICATE.forEach { gatt.enable(it, indicate = true) }

        val link = Link(gatt, mtu)
        launch {
            gatt.notifications.collect(link::route)
            throw IOException("CCU BLE link closed")
        }

        _state.value = CcuLinkState.Authenticating(info.ccuIdText)
        authenticate(link, info)

        val servers = link.request<CcuEvent.ServerList>(CcuMessages.connectServerListRequest()) { it }.servers
        val subscriptions = servers.mapNotNull { server ->
            val support = link.request<CcuEvent.SupportIdList>(CcuMessages.supportIdListRequest(server)) { it.takeIf { e -> e.server == server } }
            val wanted = LocalRecord.wantedIds(server).filter { it in support.ids }
            if (wanted.isEmpty()) null else CcuMessages.vehicleInformationInterval(server, wanted)
        }
        subscriptions.forEach { link.sendDdt(it) }
        _state.value = CcuLinkState.Ready(info.ccuIdText, servers)
        readyLink = link
        onReady()
        // After Ready, once per link: a CCU that ignores 315 stalls DDT-out ~3 s, dropping the dash's weather request.
        launch { link.subscribeCan() }

        if (subscriptions.isEmpty()) awaitCancellation()
        // Only a real 271 push proves the CCU alive; resubscribe once after a silence, then drop the link.
        var seen = link.vehiclePushes
        var silentSince = System.currentTimeMillis()
        var resubscribed = false
        while (true) {
            delay(timeouts.vehicleSilenceMs / 5)
            val now = System.currentTimeMillis()
            if (link.vehiclePushes != seen) {
                seen = link.vehiclePushes
                silentSince = now
                resubscribed = false
            } else if (now - silentSince >= timeouts.vehicleSilenceMs) {
                if (resubscribed) throw IOException("No vehicle data from CCU")
                subscriptions.forEach { link.sendDdt(it) }
                silentSince = System.currentTimeMillis()
                resubscribed = true
            }
        }
    }

    private suspend fun authenticate(link: Link, info: CcuEvent.BleConnectInformation) {
        repeat(timeouts.authTries) {
            link.control(CcuMessages.connectAppId(ids.next()))
            link.sendDdt(CcuMessages.authenticationV2(info.ccuId, info.passkey))
            val reply = withTimeoutOrNull(timeouts.authMs) { link.inbox.awaitEvent<CcuEvent.Authentication>() }
            if (reply != null) {
                if (reply.ok) return else throw CcuAuthRejected()
            }
        }
        throw IOException("No authentication reply (258)")
    }

    /** Per-BLE-link state: routes notifications, owns the DDT-out handshake. */
    private inner class Link(private val gatt: CcuGatt, private val mtu: Int) {
        /** Replies for the startup awaiters; bounded so 271 pushes after Ready can't pile up. */
        val inbox = Channel<CcuEvent>(INBOX_SIZE, BufferOverflow.DROP_OLDEST)
        private val ddtStatus = Channel<ByteArray>(Channel.UNLIMITED)
        private val ddtLock = Mutex()
        private val ddtIn = CcuDdtIn()
        private val v2 = CcuPartAssembler()
        private val v3 = CcuPartAssembler()

        @Volatile var vehiclePushes = 0
        private val fuel = InstantFuelEconomy()

        /** Best effort: bikes without these CAN ids answer 0xFF01 or nothing, which mustn't drop the link. */
        suspend fun subscribeCan() {
            try {
                sendDdt(CcuMessages.canInterval(CanInjection.IDS))
            } catch (e: IOException) {
                Unit
            }
        }

        suspend fun route(n: GattNotification) {
            when (n.uuid) {
                CcuUuids.CONTROL_NOTIFY -> incoming(n.value)
                CcuUuids.DDT_OUT_STATUS -> ddtStatus.send(n.value)
                CcuUuids.DDT_IN_HEADER -> ddtIn.header(n.value)?.let { gatt.write(it.writeTo, it.value) }
                CcuUuids.DDT_IN_DATA -> ddtIn.data(n.value)?.let { step ->
                    gatt.write(step.writeTo, step.value)
                    step.message?.let { incoming(it) }
                }
                CcuUuids.DDT_V2_NOTIFY -> v2.feed(n.value)?.let { incoming(it) }
                CcuUuids.INDICATE_A -> v3.feed(n.value)?.let { incoming(it) }
            }
        }

        private suspend fun incoming(message: ByteArray) {
            val event = CcuEvent.parse(message)
            when (event) {
                is CcuEvent.AliveCheck -> control(CcuMessages.aliveCheckResponse(event.echo))
                CcuEvent.ClockRequest -> control(CcuMessages.vehicleSettingDatetime(ids.next(), localEpochSeconds()))
                CcuEvent.LanguageRequest -> control(CcuMessages.languageTypeResponse(ids.next(), language()))
                CcuEvent.VolumeControllableRequest -> control(CcuMessages.phoneVolumeControllableResponse(true))
                is CcuEvent.VehicleInformation -> {
                    vehiclePushes++
                    val record = LocalRecord.decode(event.records)
                    record.speedKmh?.let(fuel::speed)
                    _vehicle.value = merge(_vehicle.value, record)
                }
                is CcuEvent.Can -> CanInjection.decode(event.frames)?.let { rate ->
                    fuel.injection(System.currentTimeMillis(), rate)
                    _vehicle.value = (_vehicle.value ?: LocalRecord()).copy(fuelKmPerL = fuel.kmPerL)
                }
                else -> Unit
            }
            _events.tryEmit(event)
            inbox.send(event)
        }

        suspend fun control(message: ByteArray) = gatt.write(CcuUuids.CONTROL_WRITE, message)

        /** Sends a control request and waits for the reply [pick] accepts. */
        suspend inline fun <reified T : CcuEvent> request(message: ByteArray, crossinline pick: (T) -> T?): T {
            control(message)
            return withTimeoutOrNull(timeouts.replyMs) {
                var found: T? = null
                while (found == null) found = (inbox.receive() as? T)?.let(pick)
                found
            } ?: throw IOException("No ${T::class.simpleName} reply")
        }

        /** DDT-out handshake (docs/bike-protocol.md §6.3); one transfer at a time. */
        suspend fun sendDdt(payload: ByteArray) = ddtLock.withLock {
            while (ddtStatus.tryReceive().isSuccess) Unit
            val id = ids.next()
            if (!CcuDdtOut.isIdle(gatt.read(CcuUuids.DDT_OUT_STATUS))) throw IOException("DDT-out busy")
            gatt.write(CcuUuids.DDT_OUT_HEADER, CcuDdtOut.header(id, payload.size))
            awaitStatus { CcuDdtOut.isActive(it, id) }
            var offset = 0
            while (offset < payload.size) {
                val chunk = CcuDdtOut.chunk(id, payload, offset, mtu)
                gatt.write(CcuUuids.DDT_OUT_DATA, chunk)
                offset += chunk.size - 4
                if (offset < payload.size && !CcuDdtOut.isActive(gatt.read(CcuUuids.DDT_OUT_STATUS), id)) {
                    throw IOException("DDT-out $id lost the CCU mid-transfer")
                }
            }
            awaitStatus { CcuDdtOut.isIdle(it) }
        }

        private suspend fun awaitStatus(ok: (ByteArray) -> Boolean) {
            val status = withTimeoutOrNull(timeouts.ddtStepMs) { ddtStatus.receive() } ?: throw IOException("DDT-out timeout")
            if (!ok(status)) throw IOException("DDT-out refused: ${status.joinToString(" ") { "%02x".format(it) }}")
        }
    }

    private companion object {
        const val MTU = 512
        const val INBOX_SIZE = 32

        suspend inline fun <reified T : CcuEvent> Channel<CcuEvent>.awaitEvent(): T {
            while (true) (receive() as? T)?.let { return it }
        }

        /** Keeps fields this push didn't carry (271 may hold only some ids). */
        fun merge(old: LocalRecord?, new: LocalRecord): LocalRecord = if (old == null) new else LocalRecord(
            engineRpm = new.engineRpm ?: old.engineRpm,
            speedKmh = new.speedKmh ?: old.speedKmh,
            ignitionTimingDeg = new.ignitionTimingDeg ?: old.ignitionTimingDeg,
            batteryVolts = new.batteryVolts ?: old.batteryVolts,
            throttlePercent = new.throttlePercent ?: old.throttlePercent,
            coolantCelsius = new.coolantCelsius ?: old.coolantCelsius,
            airCelsius = new.airCelsius ?: old.airCelsius,
            intakePressure = new.intakePressure ?: old.intakePressure,
            atmosphericPressure = new.atmosphericPressure ?: old.atmosphericPressure,
            dtcCount = new.dtcCount ?: old.dtcCount,
            odometerHectometres = new.odometerHectometres ?: old.odometerHectometres,
            fuelKmPerL = old.fuelKmPerL,
        )
    }
}

/** Y-Connect sends local wall-clock seconds: UTC epoch + the zone offset. */
fun localEpochSecondsNow(): Long {
    val now = System.currentTimeMillis()
    return (now + TimeZone.getDefault().getOffset(now)) / 1000
}

/** The app ships en + vi only; anything else shows English on the dash. */
fun phoneLanguage(): Int = if (Locale.getDefault().language == "vi") CcuLanguage.VIETNAMESE else CcuLanguage.ENGLISH
