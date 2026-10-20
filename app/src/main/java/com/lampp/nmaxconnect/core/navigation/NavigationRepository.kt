package com.lampp.nmaxconnect.core.navigation

import android.content.Context
import android.util.Log
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.bike.tft.TftLinkState
import com.lampp.nmaxconnect.core.bike.tft.TftSession
import com.lampp.nmaxconnect.core.di.ApplicationScope
import com.lampp.nmaxconnect.core.location.LocationSource
import com.lampp.nmaxconnect.core.map.OfflineMaps
import com.lampp.nmaxconnect.core.places.SavedPlace
import com.lampp.nmaxconnect.core.vietmap.RouteApi
import com.lampp.nmaxconnect.core.vietmap.VietMapException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface NavState {
    data object Idle : NavState

    // [origin] null = the rider's current position.
    data class Routing(val destination: SavedPlace, val origin: SavedPlace? = null) : NavState

    /** [routes] = recommended first, then alternatives; [route] is the one the rider picked. */
    data class Preview(
        val destination: SavedPlace,
        val routes: List<Route>,
        val selected: Int = 0,
        val origin: SavedPlace? = null,
    ) : NavState {
        val route: Route get() = routes[selected]
    }

    /** [offline]: off the route and the last reroute couldn't reach the network; the old route stays. */
    data class Active(
        val destination: SavedPlace,
        val route: Route,
        val progress: NavProgress?,
        val rerouting: Boolean,
        val offline: Boolean = false,
    ) : NavState

    data class Arrived(val destination: SavedPlace) : NavState
    data class Failed(val destination: SavedPlace, val reason: Reason, val origin: SavedPlace? = null) : NavState

    enum class Reason { NOT_CONFIGURED, NO_LOCATION, NETWORK }
}

/** What screens need from navigation; lets ViewModel tests use a fake. */
interface Navigator {
    val state: StateFlow<NavState>

    /** Routes to [destination] from [origin], or from the current position when null. */
    fun preview(destination: SavedPlace, origin: SavedPlace? = null)

    /** Picks one of the Preview's routes; ignored outside Preview or out of range. */
    fun selectRoute(index: Int)
    fun start()
    fun stop()

    /** Leaves Preview / Arrived / Failed. */
    fun dismiss()
}

/**
 * Owns the active trip: routes via Route v4, follows GPS with [NavigationEngine], reroutes when off
 * the line, and pushes guidance to the dash whenever a TFT session is authenticated.
 * Runs in the application scope; [NavigationService] keeps the process and GPS alive with the screen off.
 */
@Singleton
class NavigationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routes: RouteApi,
    private val location: LocationSource,
    private val bikeLink: BikeLinkRepository,
    private val offlineMaps: OfflineMaps,
    @ApplicationScope private val scope: CoroutineScope,
) : Navigator {
    private val _state = MutableStateFlow<NavState>(NavState.Idle)
    override val state: StateFlow<NavState> = _state.asStateFlow()

    private var job: Job? = null
    private val dash = DashNavMapper()
    private var dashSession: TftSession? = null

    override fun preview(destination: SavedPlace, origin: SavedPlace?) {
        job?.cancel()
        if (!routes.isConfigured) {
            _state.value = NavState.Failed(destination, NavState.Reason.NOT_CONFIGURED, origin)
            return
        }
        _state.value = NavState.Routing(destination, origin)
        job = scope.launch {
            val from = origin?.point ?: location.current()
            _state.value = if (from == null) {
                NavState.Failed(destination, NavState.Reason.NO_LOCATION, origin)
            } else {
                try {
                    // Fastest first (and picked): the chooser lists options like Google Maps does.
                    NavState.Preview(destination, routes.routes(from, destination.point).sortedBy { it.timeMs }, origin = origin)
                } catch (e: IOException) {
                    Log.w(TAG, "route failed", e)
                    NavState.Failed(destination, NavState.Reason.NETWORK, origin)
                }
            }
        }
    }

    override fun selectRoute(index: Int) {
        _state.update { s -> if (s is NavState.Preview && index in s.routes.indices) s.copy(selected = index) else s }
    }

    override fun start() {
        // Guidance follows GPS, so a trip only starts from the rider's own position.
        val preview = (_state.value as? NavState.Preview)?.takeIf { it.origin == null } ?: return
        job?.cancel()
        NavigationService.start(context, preview.destination.name)
        offlineMaps.prefetch(preview.route.geometry)
        job = scope.launch {
            try {
                follow(preview.destination, preview.route, preview.routes)
            } catch (e: SecurityException) {
                Log.w(TAG, "location permission lost", e)
                _state.value = NavState.Failed(preview.destination, NavState.Reason.NO_LOCATION)
            } finally {
                NavigationService.stop(context)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        sendToDash(dash.stop())
        NavigationService.stop(context)
        _state.value = NavState.Idle
    }

    override fun dismiss() {
        if (_state.value is NavState.Active) return // use stop()
        job?.cancel()
        sendToDash(dash.stop())
        _state.value = NavState.Idle
    }

    /** [known] = the preview's routes: riding onto one of them switches to it without a request. */
    private suspend fun follow(destination: SavedPlace, initial: Route, known: List<Route>) {
        var engine = NavigationEngine(initial)
        val pacer = ReroutePacer()
        var offLineSince: Long? = null // first fix off the line, for the reroute timing log
        var offline = false
        var arrived = false
        _state.value = NavState.Active(destination, initial, progress = null, rerouting = false)

        location.updates().takeWhile { !arrived }.collect { fix ->
            var progress = engine.update(fix)
            offLineSince = if (progress.offsetMeters > OFF_LINE_LOG_METERS) offLineSince ?: fix.timeMs else null
            val offFor = fix.timeMs - (offLineSince ?: fix.timeMs)

            if (!progress.offRoute) offline = false
            if (progress.offRoute && !progress.arrived) {
                val rejoined = rejoin(known, engine.route, fix)
                if (rejoined != null) {
                    engine = rejoined.first
                    progress = rejoined.second
                    Log.i(TAG, "reroute: rejoined a known route after $offFor ms off the line")
                } else if (pacer.canReroute(fix.timeMs)) {
                    _state.value = NavState.Active(destination, engine.route, progress, rerouting = true, offline = offline)
                    val sentAt = System.currentTimeMillis()
                    val ok = try {
                        engine = NavigationEngine(routes.route(fix.point, destination.point, fix.bearing))
                        true
                    } catch (e: IOException) {
                        Log.w(TAG, "reroute failed; keeping the old route and retrying soon", e)
                        offline = e !is VietMapException // that one means the server answered
                        false
                    }
                    pacer.onAttempt(fix.timeMs, ok)
                    if (ok) {
                        Log.i(
                            TAG,
                            "reroute: confirmed $offFor ms after leaving the line (offset ${progress.offsetMeters.toInt()} m, " +
                                "heading off ${progress.headingOffDegrees?.toInt()}°), request ${System.currentTimeMillis() - sentAt} ms",
                        )
                        progress = engine.update(fix) // the dash gets the new turn now, not on the next fix
                        offline = false
                        offlineMaps.prefetch(engine.route.geometry)
                    }
                }
            }

            pushToDash(progress)
            if (progress.arrived) {
                arrived = true
                _state.value = NavState.Arrived(destination)
            } else {
                _state.value = NavState.Active(destination, engine.route, progress, rerouting = false, offline = offline)
            }
        }
    }

    /** A known route the rider is on now (close and heading its way), with a fresh engine on it. */
    private fun rejoin(known: List<Route>, current: Route, fix: Fix): Pair<NavigationEngine, NavProgress>? {
        for (route in known) {
            if (route === current) continue
            val engine = NavigationEngine(route)
            val p = engine.update(fix)
            if (p.offsetMeters <= REJOIN_METERS && (p.headingOffDegrees ?: 0.0) < REJOIN_ANGLE_DEGREES) return engine to p
        }
        return null
    }

    /** Only an authenticated session gets packets; a new session gets the full state again. */
    private fun pushToDash(progress: NavProgress) {
        val session = bikeLink.session.value?.takeIf { it.state.value is TftLinkState.Ready }
        if (session !== dashSession) {
            dash.reset()
            dashSession = session
        }
        sendToDash(session?.let { dash.update(progress, System.currentTimeMillis()) }.orEmpty())
    }

    private fun sendToDash(packets: List<ByteArray>) {
        val session = dashSession ?: return
        if (packets.isEmpty()) return
        scope.launch {
            runCatching { session.send(*packets.toTypedArray()) }.onFailure { Log.w(TAG, "dash send failed", it) }
        }
    }

    private companion object {
        const val TAG = "Navigation"

        const val OFF_LINE_LOG_METERS = 12.0
        const val REJOIN_METERS = 20.0
        const val REJOIN_ANGLE_DEGREES = 45.0
    }
}
