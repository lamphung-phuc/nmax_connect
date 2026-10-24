package com.lampp.nmaxconnect.core.map

import android.content.Context
import android.util.Log
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.bike.ccu.CcuLinkRepository
import com.lampp.nmaxconnect.core.bike.ccu.CcuLinkState
import com.lampp.nmaxconnect.core.bike.ccu.CcuStatus
import com.lampp.nmaxconnect.core.di.DefaultDispatcher
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.location.LocationSource
import com.lampp.nmaxconnect.core.navigation.Fix
import com.lampp.nmaxconnect.core.navigation.NavProgress
import com.lampp.nmaxconnect.core.navigation.NavState
import com.lampp.nmaxconnect.core.navigation.Navigator
import com.lampp.nmaxconnect.core.navigation.TurnIcons
import com.lampp.nmaxconnect.core.navigation.dashDistance
import com.lampp.nmaxconnect.core.protocol.ccu.LocalRecord
import com.lampp.nmaxconnect.core.protocol.tft.DayNight
import com.lampp.nmaxconnect.core.protocol.tft.ImageType
import com.lampp.nmaxconnect.core.protocol.tft.TftEvent
import com.lampp.nmaxconnect.core.protocol.tft.TftMessages
import com.lampp.nmaxconnect.core.settings.AppSettings
import com.lampp.nmaxconnect.core.settings.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production map stream: while the bike link is up, renders VietMap at the dash size with a track-up camera
 * on GPS and the active route line; while the dash asks for video (55 NAVI_IMAGE) it sends ACK-paced JPEG frames.
 * The renderer stays warm across dash screen switches (56 → 55), so coming back to the map shows it at once.
 * Runs inside [com.lampp.nmaxconnect.core.bike.BikeLinkService]'s main-thread scope.
 */
@Singleton
class DashStreamer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bikeLink: BikeLinkRepository,
    private val navigator: Navigator,
    private val location: LocationSource,
    private val ccu: CcuLinkRepository,
    private val settings: AppSettingsRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    private val paused = MutableStateFlow(false)

    /** Bearing the dash map is turned to (track-up), degrees; the dash POI menu arrows use it too. */
    @Volatile var heading = 0.0
        private set

    /** Dash zoom buttons (51/52): steps added to the speed auto-zoom; kept across streams. */
    private val zoomOffset = MutableStateFlow(0.0)

    /** The debug screen streams its own sources meanwhile. */
    fun pause(on: Boolean) {
        paused.value = on
    }

    suspend fun run() {
        if (!VietMapStyle.available) return
        combine(bikeLink.state, paused) { link, p -> (link as? BikeLinkState.Ready)?.takeIf { !p }?.display }
            .distinctUntilChanged()
            .collectLatest { display ->
                if (display == null) return@collectLatest
                val start = location.current()
                val overlay = DashOverlay(night = start?.let { isNight(it.lat, it.lng, System.currentTimeMillis()) } ?: false)
                val renderer = VietMapDashRenderer(context, display, styleUrl(overlay.night))
                try {
                    coroutineScope {
                        val prefs = settings.settings.stateIn(this)
                        val streaming = bikeLink.state.map { (it as? BikeLinkState.Ready)?.streaming == true }.stateIn(this)
                        launch { followGps(renderer, start, overlay, streaming) }
                        launch { drawRoute(renderer, prefs) }
                        launch { drawStats(renderer, prefs) }
                        launch { followZoomButtons() }
                        streaming.collectLatest { if (it) pump(renderer) }
                    }
                } finally {
                    renderer.close()
                }
            }
    }

    /** Day/night (31) and scale label (14) last sent in this stream; a new stream resends both. */
    private class DashOverlay(var night: Boolean, var sentNight: Boolean? = null, var label: String? = null)

    private suspend fun followZoomButtons() {
        bikeLink.events.collect { e ->
            val step = when (e) {
                TftEvent.ZoomIn -> 1.0
                TftEvent.ZoomOut -> -1.0
                else -> return@collect
            }
            zoomOffset.value = (zoomOffset.value + step).coerceIn(MIN_ZOOM_OFFSET, MAX_ZOOM_OFFSET)
        }
    }

    /**
     * Camera on each fix, zoom press or stream start; before the first fix it sits on [start]. While the dash
     * shows another screen the camera still follows (so the map around the rider is already drawn) but jumps
     * instead of easing: an ease renders every frame for a second, a jump renders once.
     */
    private suspend fun followGps(
        renderer: VietMapDashRenderer,
        start: GeoPoint?,
        overlay: DashOverlay,
        streaming: Flow<Boolean>,
    ) {
        val fixes = location.updates().map<Fix, Fix?> { it }.onStart { emit(null) }
        var hadFix = false
        try {
            combine(fixes, zoomOffset, streaming, ::Triple).collect { (fix, offset, on) ->
                fix?.bearing?.let { heading = it.toDouble() } // GPS bearing is noise when stopped; keep the last one
                val at = fix?.point ?: start
                val zoom = zoomForSpeed(fix?.speedMps ?: 0f) + offset
                val ease = if (hadFix && on) CAMERA_EASE_MS else 0 // the first fix jumps off a possibly stale last-known spot
                if (at != null) renderer.moveCamera(DashCamera(at.lat, at.lng, heading, zoom, TILT), durationMs = ease)
                hadFix = hadFix || fix != null
                if (!on) {
                    overlay.sentNight = null // a new stream needs msg 31 + 14 again
                    overlay.label = null
                }
                syncOverlay(renderer, overlay, at, zoom, on)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no location permission; map stays at the last known position", e)
        }
    }

    /** Switches the map style at sunset/sunrise and keeps the dash's theme and scale text in step. */
    private suspend fun syncOverlay(
        renderer: VietMapDashRenderer,
        overlay: DashOverlay,
        at: GeoPoint?,
        zoom: Double,
        streaming: Boolean,
    ) {
        if (at != null) {
            val night = isNight(at.lat, at.lng, System.currentTimeMillis())
            if (night != overlay.night) {
                overlay.night = night
                renderer.setStyle(styleUrl(night))
            }
        }
        if (!streaming) return
        val label = scaleLabel(zoom, at?.lat ?: VIETNAM_MID_LAT)
        val packets = buildList {
            if (overlay.sentNight != overlay.night) add(TftMessages.dayNight(if (overlay.night) DayNight.NIGHT else DayNight.DAY))
            if (label != overlay.label) add(TftMessages.mapScaleLabel(label))
        }
        val session = bikeLink.session.value
        if (session == null || packets.isEmpty()) return
        try {
            session.send(*packets.toTypedArray())
            overlay.sentNight = overlay.night
            overlay.label = label
        } catch (e: IOException) {
            Log.w(TAG, "overlay send failed", e)
        }
    }

    private fun styleUrl(night: Boolean) =
        VietMapStyle.url(if (night) VietMapStyle.NIGHT else VietMapStyle.DAY)

    /** Route line (passed part grey while guiding, all grey while rerouting) and, if the rider wants it, the next turn. */
    private suspend fun drawRoute(renderer: VietMapDashRenderer, prefs: StateFlow<AppSettings>) {
        combine(navigator.state, prefs, ::Pair).collect { (state, p) ->
            val active = state as? NavState.Active
            val route = active?.route ?: (state as? NavState.Preview)?.route
            val progress = active?.progress
            // all grey while rerouting, so the rider sees the app noticed the wrong turn
            val ahead = if (active?.rerouting == true) emptyList() else progress?.let { route?.aheadOf(it) }
            renderer.setRoute(route?.geometry, ahead)
            renderer.setGuidance(progress?.takeIf { p.showTurns }?.let(::guidance))
        }
    }

    /**
     * Bike stats column while enabled with at least one stat chosen and guiding (outside navigation the dash's zoom
     * buttons sit on it); blank values ("--") unless the CCU link is up, so nothing stale shows.
     */
    private suspend fun drawStats(renderer: VietMapDashRenderer, prefs: StateFlow<AppSettings>) {
        val guiding = navigator.state.map { it is NavState.Active }.distinctUntilChanged()
        combine(prefs, guiding, ccu.status, ccu.vehicle) { p, nav, status, vehicle ->
            val live = vehicle?.takeIf { (status as? CcuStatus.Linked)?.state is CcuLinkState.Ready }
            (if (p.dashStats && p.dashStatItems.isNotEmpty() && nav) live ?: LocalRecord() else null) to p.dashStatItems
        }.collect { (stats, items) -> renderer.setStats(stats, items) }
    }

    private fun guidance(p: NavProgress): DashGuidance {
        val target = p.next ?: p.current
        val (value, unit) = dashDistance(p.distanceToNextMeters)
        val distance = if (unit == "m") "${value.toInt()} m" else "$value km"
        return DashGuidance(if (p.arrived) TurnIcons.ARRIVE else TurnIcons.forManeuver(target), distance, target.street)
    }

    /** One frame in flight (ACK-paced); an unchanged map is resent only once a second. */
    private suspend fun pump(renderer: VietMapDashRenderer) {
        var last: ByteArray? = null
        var lastSentAt = 0L
        while (true) {
            val jpeg = withContext(defaultDispatcher) { renderer.captureJpeg() }
            val session = bikeLink.session.value
            val now = System.currentTimeMillis()
            if (jpeg == null || session == null || (jpeg === last && now - lastSentAt < RESEND_MS)) {
                delay(FRAME_POLL_MS)
                continue
            }
            // imageType 0 is what puts the dash in its navigation layout (turn column, ETA, zoom buttons aside); keep it tied to guiding only
            val type = if (navigator.state.value is NavState.Active) ImageType.NAVIGATION else ImageType.NAVIGATION_EXPANDED
            val accepted = try {
                session.sendFrame(jpeg, type)
            } catch (e: IOException) {
                Log.w(TAG, "frame not ACKed", e) // the link loop reconnects if the socket is really dead
                false
            }
            if (accepted) {
                last = jpeg
                lastSentAt = now
            } else {
                delay(IDLE_MS)
            }
        }
    }

    companion object {
        private const val TAG = "DashStreamer"
        private const val TILT = 45.0
        private const val CAMERA_EASE_MS = 1_000
        private const val FRAME_POLL_MS = 50L
        private const val RESEND_MS = 1_000L
        private const val IDLE_MS = 200L
        private const val VIETNAM_MID_LAT = 16.0 // scale before the first fix
        private const val MIN_ZOOM_OFFSET = -4.0
        private const val MAX_ZOOM_OFFSET = 2.0

        /** Auto zoom (docs/bike-protocol.md §5): 17 slow, 15.5 at 30, 15 at 50, 14.5 from 70 km/h. */
        fun zoomForSpeed(mps: Float): Double {
            val kmh = mps * 3.6
            val stops = listOf(10.0 to 17.0, 30.0 to 15.5, 50.0 to 15.0, 70.0 to 14.5)
            if (kmh <= stops.first().first) return stops.first().second
            if (kmh >= stops.last().first) return stops.last().second
            val (lo, hi) = stops.zipWithNext().first { (_, b) -> kmh <= b.first }
            return lo.second + (hi.second - lo.second) * (kmh - lo.first) / (hi.first - lo.first)
        }
    }
}
