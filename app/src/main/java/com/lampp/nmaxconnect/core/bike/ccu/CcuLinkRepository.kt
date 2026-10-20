package com.lampp.nmaxconnect.core.bike.ccu

import android.content.Context
import com.lampp.nmaxconnect.core.bike.hasBluetoothPermission
import com.lampp.nmaxconnect.core.bike.serveSocket
import com.lampp.nmaxconnect.core.bike.superviseLink
import com.lampp.nmaxconnect.core.di.IoDispatcher
import com.lampp.nmaxconnect.core.maintenance.MaintenanceRepository
import com.lampp.nmaxconnect.core.protocol.ccu.CcuEvent
import com.lampp.nmaxconnect.core.protocol.ccu.CcuMessages
import com.lampp.nmaxconnect.core.protocol.ccu.LocalRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

sealed interface CcuStatus {
    data object Idle : CcuStatus
    data class Connecting(val attempt: Int) : CcuStatus
    data class Linked(val state: CcuLinkState) : CcuStatus
    data class Retrying(val inMs: Long, val reason: String) : CcuStatus

    /** Needs the user (grant nearby-devices permission); the loop has stopped. */
    data object MissingPermission : CcuStatus

    /** The CCU refused our credentials; retrying won't help. The loop has stopped. */
    data object Rejected : CcuStatus
}

/**
 * Owns the CCU link (Link B): SPP → [CcuSession] → on failure back off and reconnect, until cancelled.
 * Independent of the TFT link; [BikeLinkService] runs both side by side.
 */
@Singleton
class CcuLinkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connector: CcuConnector,
    private val maintenance: MaintenanceRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val _status = MutableStateFlow<CcuStatus>(CcuStatus.Idle)
    val status: StateFlow<CcuStatus> = _status.asStateFlow()

    private val _vehicle = MutableStateFlow<LocalRecord?>(null)

    /** Last values the CCU pushed; kept after a disconnect so the UI can show the last known ODO. */
    val vehicle: StateFlow<LocalRecord?> = _vehicle.asStateFlow()

    private val session = MutableStateFlow<CcuSession?>(null)

    /** Every message from the live CCU session (dash buttons, ignition, errors, …). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: Flow<CcuEvent> = session.flatMapLatest { it?.events ?: emptyFlow() }

    /** Sends a phone → CCU bulk message now; false (dropped) while the CCU link isn't Ready. */
    suspend fun send(ddtPayload: ByteArray): Boolean = session.value?.sendDdt(ddtPayload) ?: false

    /** Sends a control message now; false (dropped) while the CCU link isn't Ready. */
    suspend fun sendControl(message: ByteArray): Boolean = session.value?.sendControl(message) ?: false

    /** Shows [n] on the dash now; false (dropped) while the CCU link isn't Ready. */
    suspend fun sendNotification(n: DashNotification): Boolean =
        send(CcuMessages.notificationDataV2(n.id, n.isCall, LocalDateTime.now(), n.title, "", n.detail))

    suspend fun run(ccuMac: String) {
        try {
            superviseLink(
                TAG,
                { hasBluetoothPermission(context) },
                onConnecting = { _status.value = CcuStatus.Connecting(it) },
                onRetrying = { inMs, reason -> _status.value = CcuStatus.Retrying(inMs, reason) },
            ) { connectAndServe(ccuMac) }
            _status.value = CcuStatus.MissingPermission
        } catch (e: CcuAuthRejected) {
            _status.value = CcuStatus.Rejected
        } finally {
            if (_status.value !is CcuStatus.MissingPermission && _status.value !is CcuStatus.Rejected) _status.value = CcuStatus.Idle
        }
    }

    /** Returns whether the session got to Ready before it ended (resets the backoff). */
    @Suppress("MissingPermission") // checked by hasBluetoothPermission() right before
    private suspend fun connectAndServe(ccuMac: String): Boolean {
        val socket = connector.connectSpp(ccuMac)
        val session = CcuSession(socket.inputStream, socket.outputStream, connector::openGatt, ioDispatcher)
        this.session.value = session
        var reachedReady = false
        try {
            serveSocket(socket, ioDispatcher, {
                launch {
                    session.state.collect {
                        if (it is CcuLinkState.Ready) reachedReady = true
                        _status.value = CcuStatus.Linked(it)
                    }
                }
                launch { session.vehicle.collect { v -> if (v != null) _vehicle.value = v } }
                // Pushes come every 100 ms; the ODO only changes every 100 m.
                launch { session.vehicle.mapNotNull { it?.odometerKm }.distinctUntilChanged().collect { maintenance.saveOdometer(it) } }
            }) { session.run() }
        } catch (e: CcuAuthRejected) {
            throw e
        } catch (e: IOException) {
            if (!reachedReady) throw e
        } finally {
            this.session.value = null
        }
        return reachedReady
    }

    private companion object {
        const val TAG = "CcuLink"
    }
}
