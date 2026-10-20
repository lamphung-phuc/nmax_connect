package com.lampp.nmaxconnect.core.bike

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.lampp.nmaxconnect.core.bike.tft.TftConnector
import com.lampp.nmaxconnect.core.bike.tft.TftLinkState
import com.lampp.nmaxconnect.core.bike.tft.TftSession
import com.lampp.nmaxconnect.core.di.IoDispatcher
import com.lampp.nmaxconnect.core.places.PlacesRepository
import com.lampp.nmaxconnect.core.protocol.ccu.CcuUuids
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.core.protocol.tft.TftEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

sealed interface BikeLinkState {
    data object Idle : BikeLinkState
    data class Connecting(val attempt: Int) : BikeLinkState
    data object Handshaking : BikeLinkState
    data class Ready(val display: DisplayModel, val streaming: Boolean) : BikeLinkState
    data class Retrying(val inMs: Long, val reason: String) : BikeLinkState

    /** Needs the user (grant permission); the loop has stopped. */
    data object MissingPermission : BikeLinkState
}

/** [name] is the Bluetooth name; [nickname] is the rider's own, set in Settings (saved bike only). */
data class BondedBike(val name: String, val mac: String, val nickname: String? = null)

/**
 * Owns the TFT link: connect → session → on any failure back off and reconnect, until cancelled.
 * Run [run] from a foreground service ([BikeLinkService]). The CCU link is [ccu.CcuLinkRepository].
 */
@Singleton
class BikeLinkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val connector: TftConnector,
    private val places: PlacesRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Devices paired in Android Bluetooth settings; empty without permission or Bluetooth. */
    @Suppress("MissingPermission") // checked by hasBluetoothPermission()
    fun bondedBikes(): List<BondedBike> = try {
        if (!hasBluetoothPermission(context)) emptyList()
        else adapter?.bondedDevices.orEmpty().map { BondedBike(it.name ?: it.address, it.address) }.sortedBy { it.name }
    } catch (e: SecurityException) {
        emptyList()
    }

    /** Bonded Yamaha CCUs only (`YCCU_`/`YSCCU_` + CCU id), for the bike pickers. */
    fun bondedCcus(): List<BondedBike> = bondedBikes().filter { CcuUuids.ccuIdFromBleName(it.name) != null }

    /** Starts the foreground service that calls [run]. */
    fun start(bikeMac: String) = BikeLinkService.start(context, bikeMac)

    fun stop() = BikeLinkService.stop(context)

    private val _state = MutableStateFlow<BikeLinkState>(BikeLinkState.Idle)
    val state: StateFlow<BikeLinkState> = _state.asStateFlow()

    private val _session = MutableStateFlow<TftSession?>(null)

    /** The live session, for sending frames and nav updates; null while disconnected. */
    val session: StateFlow<TftSession?> = _session.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val events: Flow<TftEvent> = _session.flatMapLatest { it?.events ?: emptyFlow() }

    suspend fun run(bikeMac: String) {
        try {
            superviseLink(
                TAG,
                { hasBluetoothPermission(context) },
                onConnecting = { _state.value = BikeLinkState.Connecting(it) },
                onRetrying = { inMs, reason -> _state.value = BikeLinkState.Retrying(inMs, reason) },
            ) { connectAndServe(bikeMac) }
            _state.value = BikeLinkState.MissingPermission
        } finally {
            _session.value = null
            if (_state.value !is BikeLinkState.MissingPermission) _state.value = BikeLinkState.Idle
        }
    }

    /** Returns whether the session got authenticated before it ended. */
    @Suppress("MissingPermission") // checked by hasBluetoothPermission() right before
    private suspend fun connectAndServe(bikeMac: String): Boolean {
        val socket = connector.connect(bikeMac)
        val session = TftSession(socket.inputStream, socket.outputStream, ioDispatcher) {
            places.places.first().let { (it.home != null) to (it.work != null) }
        }
        _session.value = session
        try {
            serveSocket(socket, ioDispatcher, { launch { session.state.collect { _state.value = it.toLinkState() } } }) {
                session.run()
            }
        } catch (e: IOException) {
            if (session.state.value !is TftLinkState.Ready) throw e
        } finally {
            _session.value = null
        }
        return session.state.value is TftLinkState.Ready
    }

    private fun TftLinkState.toLinkState(): BikeLinkState = when (this) {
        TftLinkState.Handshaking -> BikeLinkState.Handshaking
        is TftLinkState.Ready -> BikeLinkState.Ready(display, streaming)
    }

    companion object {
        private const val TAG = "BikeLink"

        /** 3 s for the first three attempts, then 5 s, 10 s, capped at 15 s. */
        fun reconnectDelayMs(failures: Int): Long = when {
            failures <= 3 -> 3_000
            failures == 4 -> 5_000
            failures == 5 -> 10_000
            else -> 15_000
        }
    }
}
