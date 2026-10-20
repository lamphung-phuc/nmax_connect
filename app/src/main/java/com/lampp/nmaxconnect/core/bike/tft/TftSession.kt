package com.lampp.nmaxconnect.core.bike.tft

import com.lampp.nmaxconnect.core.protocol.tft.ContentType
import com.lampp.nmaxconnect.core.protocol.tft.DayNight
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.core.protocol.tft.NaviLiteDecoder
import com.lampp.nmaxconnect.core.protocol.tft.TftEvent
import com.lampp.nmaxconnect.core.protocol.tft.TftMessages
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface TftLinkState {
    data object Handshaking : TftLinkState
    data class Ready(val display: DisplayModel, val streaming: Boolean) : TftLinkState
}

/**
 * One authenticated conversation with the TFT over an already-open RFCOMM socket.
 * Spec: docs/bike-protocol.md §4 (handshake, initial state, frame/ACK loop).
 *
 * The owner runs [run] on the IO dispatcher and closes the socket to end it; any IOException
 * from [run] or [sendFrame] means the link is dead and the owner should reconnect.
 */
class TftSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val ioDispatcher: CoroutineDispatcher,
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    /** Saved Home/Work for the initial state; the dash keeps "not set" if they arrive only afterwards. */
    private val homeWorkSaved: suspend () -> Pair<Boolean, Boolean> = { false to false },
) {
    private val _state = MutableStateFlow<TftLinkState>(TftLinkState.Handshaking)
    val state: StateFlow<TftLinkState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TftEvent>(extraBufferCapacity = 64)

    /** Every parsed TFT message (menu requests, vehicle speed, …) for the app layer. */
    val events: SharedFlow<TftEvent> = _events.asSharedFlow()

    private val writeLock = Mutex()
    private val frameLock = Mutex()
    private var display = DisplayModel.UNKNOWN
    private var seq = 0

    @Volatile private var pendingSeq = -1

    @Volatile private var pendingAck: CompletableDeferred<Boolean>? = null

    /** Reads until the link drops. Blocking reads: call on the IO dispatcher. */
    suspend fun run() {
        val decoder = NaviLiteDecoder()
        val buf = ByteArray(4096)
        while (currentCoroutineContext().isActive) {
            val n = input.read(buf)
            if (n < 0) throw EOFException("TFT closed the link")
            for (packet in decoder.feed(buf, n)) handle(TftEvent.parse(packet))
        }
    }

    /**
     * Sends one JPEG and suspends until the TFT ACKs it (one frame in flight).
     * Returns false if the dash isn't accepting video right now; throws IOException on ACK timeout.
     */
    suspend fun sendFrame(jpeg: ByteArray, imageType: Int): Boolean = frameLock.withLock {
        if ((state.value as? TftLinkState.Ready)?.streaming != true) return false
        val ack = CompletableDeferred<Boolean>()
        val s = seq
        seq = (seq + 1) and 0xFFFF
        pendingSeq = s
        pendingAck = ack
        write(TftMessages.imageFrame(imageType, s, padForDash(jpeg)))
        withTimeoutOrNull(ackTimeoutMs) { ack.await() } ?: run {
            runCatching { input.close() } // ends run() so the owner's loop reconnects; a socket can stay open with the dash stuck
            throw IOException("No ACK for frame $s within $ackTimeoutMs ms")
        }
    }

    /** Nav/overlay updates (next turn, ETA, …). Dropped until authenticated. */
    suspend fun send(vararg packets: ByteArray) {
        if (state.value is TftLinkState.Ready) write(*packets)
    }

    private suspend fun handle(event: TftEvent) {
        when (event) {
            // ESN validation is skipped: the bike accepts an unconditional ACK (field-tested).
            is TftEvent.Esn -> write(TftMessages.esnAck(true), TftMessages.authRequest())
            is TftEvent.SecData -> {
                display = event.display
                write(TftMessages.secDataAck(event.key))
            }
            is TftEvent.AuthAck -> {
                if (!event.ok) throw IOException("TFT rejected authentication")
                val (home, work) = homeWorkSaved()
                write(*initialState(home, work))
                _state.value = TftLinkState.Ready(display, streaming = false)
            }
            is TftEvent.StartContent -> if (event.content == ContentType.NAVI_IMAGE) setStreaming(true)
            is TftEvent.StopContent -> if (event.content == ContentType.NAVI_IMAGE) {
                setStreaming(false)
                pendingAck?.complete(false)
                write(TftMessages.imageStopped())
            }
            is TftEvent.ImageAck -> if (event.seq == pendingSeq) pendingAck?.complete(true)
            else -> Unit
        }
        _events.tryEmit(event)
    }

    private fun setStreaming(on: Boolean) = _state.update {
        if (it is TftLinkState.Ready) it.copy(streaming = on) else it
    }

    private suspend fun write(vararg packets: ByteArray) = writeLock.withLock {
        withContext(ioDispatcher) {
            packets.forEach(output::write)
            output.flush()
        }
    }

    companion object {
        /** Drop the link after 2 s without an ACK. */
        const val ACK_TIMEOUT_MS = 2_000L

        /** Defaults until the app knows better; the nav layer overwrites them via [send]. */
        fun initialState(home: Boolean = false, work: Boolean = false): Array<ByteArray> = arrayOf(
            TftMessages.navigationStatus(false),
            TftMessages.homeSet(home),
            TftMessages.officeSet(work),
            TftMessages.gpsFix(false),
            TftMessages.initSetupComplete(),
            TftMessages.dayNight(DayNight.DAY),
            TftMessages.mapScaleLabel("300 m"),
        )

        // shortcut: JPEGs <= 2048 B are padded to 2050 B (field-tested); the official parser has no such rule.
        // Drop this once a real-bike test shows small frames are accepted.
        private fun padForDash(jpeg: ByteArray) = if (jpeg.size <= 2048) jpeg.copyOf(2050) else jpeg
    }
}
