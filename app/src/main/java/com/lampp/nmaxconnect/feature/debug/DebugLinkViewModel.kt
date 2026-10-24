package com.lampp.nmaxconnect.feature.debug

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.bike.BondedBike
import com.lampp.nmaxconnect.core.bike.tft.TftLinkState
import com.lampp.nmaxconnect.core.di.DefaultDispatcher
import com.lampp.nmaxconnect.core.map.DashCamera
import com.lampp.nmaxconnect.core.map.DashStreamer
import com.lampp.nmaxconnect.core.map.VietMapDashRenderer
import com.lampp.nmaxconnect.core.map.VietMapStyle
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.core.protocol.tft.ImageType
import com.lampp.nmaxconnect.core.protocol.tft.TftEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StreamSource { OFF, TEST_PATTERN, VIETMAP }

@Immutable
data class StreamStats(
    val framesSent: Int = 0,
    val lastAckMs: Long = 0,
    val fps: Float = 0f,
    val lastFrameBytes: Int = 0,
)

@Immutable
data class DebugLinkUiState(
    val permissionsGranted: Boolean = false,
    val bondedBikes: List<BondedBike> = emptyList(),
    val selectedMac: String? = null,
    val link: BikeLinkState = BikeLinkState.Idle,
    val source: StreamSource = StreamSource.OFF,
    val vietMapAvailable: Boolean = false,
    val preview: ImageBitmap? = null,
    val stats: StreamStats = StreamStats(),
    val log: List<String> = emptyList(),
)

sealed interface DebugLinkUiAction {
    /** Handled by the Route (needs an Activity result launcher). */
    data object RequestPermissions : DebugLinkUiAction
    data class PermissionsResult(val granted: Boolean) : DebugLinkUiAction
    data class SelectBike(val mac: String) : DebugLinkUiAction
    data object StartLink : DebugLinkUiAction
    data object StopLink : DebugLinkUiAction
    data class SelectSource(val source: StreamSource) : DebugLinkUiAction
}

/** Debug screen for the first real-bike test: connect, watch the handshake, stream a test pattern or VietMap. */
@HiltViewModel
class DebugLinkViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: BikeLinkRepository,
    private val streamer: DashStreamer,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val local = MutableStateFlow(DebugLinkUiState(vietMapAvailable = VietMapStyle.available))
    private var sourceJob: Job? = null

    val uiState: StateFlow<DebugLinkUiState> = combine(local, repository.state) { l, link -> l.copy(link = link) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch { repository.state.collect { log("link: ${it.javaClass.simpleName}") } }
        viewModelScope.launch {
            repository.events.collect { if (it !is TftEvent.ImageAck) log("← ${it.describe()}") } // ACKs show up in stats
        }
    }

    fun onAction(action: DebugLinkUiAction) {
        when (action) {
            DebugLinkUiAction.RequestPermissions -> Unit
            is DebugLinkUiAction.PermissionsResult -> local.update {
                val bikes = if (action.granted) repository.bondedBikes() else emptyList()
                it.copy(permissionsGranted = action.granted, bondedBikes = bikes, selectedMac = it.selectedMac ?: bikes.singleOrNull()?.mac)
            }
            is DebugLinkUiAction.SelectBike -> local.update { it.copy(selectedMac = action.mac) }
            DebugLinkUiAction.StartLink -> local.value.selectedMac?.let(repository::start)
            DebugLinkUiAction.StopLink -> repository.stop()
            is DebugLinkUiAction.SelectSource -> selectSource(action.source)
        }
    }

    private fun selectSource(source: StreamSource) {
        local.update { it.copy(source = source, stats = StreamStats(), preview = null) }
        sourceJob?.cancel()
        streamer.pause(source != StreamSource.OFF) // one frame sender at a time
        if (source == StreamSource.OFF) return
        sourceJob = viewModelScope.launch {
            // Before the bike reports its part number, render at the common 480×240.
            repository.state
                .map { (it as? BikeLinkState.Ready)?.display ?: DisplayModel.IMWW23 }
                .distinctUntilChanged()
                .collectLatest { display ->
                    when (source) {
                        StreamSource.TEST_PATTERN -> {
                            val pattern = TestPattern(display)
                            var n = 0
                            pump { withContext(defaultDispatcher) { pattern.render(n++) } }
                        }
                        StreamSource.VIETMAP -> {
                            val style = VietMapStyle.url(VietMapStyle.DAY)
                            val renderer = VietMapDashRenderer(appContext, display, style) // main thread
                            try {
                                coroutineScope {
                                    launch { orbitDemo(renderer) }
                                    pump { withContext(defaultDispatcher) { renderer.captureJpeg() } }
                                }
                            } finally {
                                renderer.close()
                            }
                        }
                        StreamSource.OFF -> Unit
                    }
                }
        }
    }

    /**
     * Produces frames forever: sends them while the dash requests video (one in flight, ACK-paced),
     * otherwise idles; refreshes the on-screen preview twice a second either way.
     */
    private suspend fun pump(nextFrame: suspend () -> ByteArray?) {
        val window = ArrayDeque<Long>()
        var sent = 0
        var lastPreview = 0L
        while (true) {
            val jpeg = nextFrame()
            if (jpeg == null) {
                delay(50)
                continue
            }
            val session = repository.session.value
            if ((session?.state?.value as? TftLinkState.Ready)?.streaming == true) {
                val sentAt = System.currentTimeMillis()
                val accepted = try {
                    session.sendFrame(jpeg, ImageType.NAVIGATION_EXPANDED)
                } catch (e: IOException) {
                    log("✗ ${e.message}")
                    false
                }
                if (accepted) {
                    val now = System.currentTimeMillis()
                    window.addLast(now)
                    while (window.first() < now - 1_000) window.removeFirst()
                    sent++
                    local.update { it.copy(stats = StreamStats(sent, now - sentAt, window.size.toFloat(), jpeg.size)) }
                } else {
                    delay(IDLE_FRAME_MS)
                }
            } else {
                delay(IDLE_FRAME_MS)
            }
            val now = System.currentTimeMillis()
            if (now - lastPreview >= PREVIEW_EVERY_MS) {
                lastPreview = now
                val bitmap = withContext(defaultDispatcher) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
                local.update { it.copy(preview = bitmap) }
            }
        }
    }

    /** Keeps the map moving (and re-rendering) without GPS: slow orbit around Ben Thanh market. */
    private suspend fun orbitDemo(renderer: VietMapDashRenderer) {
        var bearing = 0.0
        while (true) {
            renderer.moveCamera(DashCamera(lat = 10.7725, lng = 106.6980, bearing = bearing, zoom = 16.5, tilt = 45.0))
            bearing = (bearing + 2) % 360
            delay(100)
        }
    }

    override fun onCleared() {
        streamer.pause(false)
    }

    private fun log(line: String) = local.update {
        it.copy(log = (listOf("${LocalTime.now().format(TIME)} $line") + it.log).take(LOG_LINES))
    }

    private fun TftEvent.describe(): String = when (this) {
        is TftEvent.SecData -> "SecData part=$partNumber key=${key.size}B display=${display.width}x${display.height}"
        is TftEvent.Unknown -> "Unknown service=$service ${payload.size}B"
        else -> toString()
    }

    private companion object {
        const val LOG_LINES = 50
        const val IDLE_FRAME_MS = 200L
        const val PREVIEW_EVERY_MS = 500L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
