package com.lampp.nmaxconnect.core.bike.tft

import android.util.Log
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.location.LocationSource
import com.lampp.nmaxconnect.core.map.DashStreamer
import com.lampp.nmaxconnect.core.navigation.NavState
import com.lampp.nmaxconnect.core.navigation.Navigator
import com.lampp.nmaxconnect.core.navigation.dashDistance
import com.lampp.nmaxconnect.core.navigation.haversine
import com.lampp.nmaxconnect.core.places.PlacesRepository
import com.lampp.nmaxconnect.core.places.SavedPlace
import com.lampp.nmaxconnect.core.protocol.tft.ContentType
import com.lampp.nmaxconnect.core.protocol.tft.PoiDirection
import com.lampp.nmaxconnect.core.protocol.tft.PoiList
import com.lampp.nmaxconnect.core.protocol.tft.TftEvent
import com.lampp.nmaxconnect.core.protocol.tft.TftMessages
import com.lampp.nmaxconnect.core.vietmap.PlaceSearchApi
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * The dash's own destination menu, like Garmin StreetCross (docs/bike-protocol.md §4.4): Home/Work
 * (10/11, 53/54) mirror the app's saved Home/Work, Favourites (55[3]) the app's favorites, Gas stations
 * (55[4]) VietMap POIs within 5 km. Picking an entry (48) or Home/Work routes there and starts guidance;
 * Stop route (49) ends it.
 */
@Singleton
class DashPlaces @Inject constructor(
    private val bikeLink: BikeLinkRepository,
    private val places: PlacesRepository,
    private val search: PlaceSearchApi,
    private val location: LocationSource,
    private val navigator: Navigator,
    private val streamer: DashStreamer,
) {
    /** Lists last sent, by listIdx; alternating 0/1 so a pick from the previous list still resolves. */
    private val lists = arrayOfNulls<List<SavedPlace>>(2)
    private var nextList = 0

    suspend fun run() = coroutineScope {
        launch { syncHomeWork() }
        // Only menu events reach collectLatest: frame ACKs must not cancel a gas lookup or a pending route.
        bikeLink.events.mapNotNull(::actionFor).collectLatest { it() }
    }

    private fun actionFor(e: TftEvent): (suspend () -> Unit)? = when (e) {
        is TftEvent.GoHome -> { { places.places.first().home?.let { navigate(it) } } }
        is TftEvent.GoOffice -> { { places.places.first().work?.let { navigate(it) } } }
        is TftEvent.StartRoute -> { { lists.getOrNull(e.listIndex)?.getOrNull(e.itemIndex)?.let { navigate(it) } } }
        TftEvent.StopRoute -> { { navigator.stop() } }
        is TftEvent.StartContent -> when (e.content) {
            ContentType.FAVORITE_LIST -> { { sendList(PoiList.FAVORITES) { places.places.first().favorites } } }
            ContentType.STATIONS_LIST -> { { sendList(PoiList.GAS_STATIONS, ::gasStations) } }
            else -> null
        }
        else -> null
    }

    /** Home/Work show in the dash menu only when saved; resent to every newly authenticated session. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun syncHomeWork() {
        val ready = bikeLink.session
            .flatMapLatest { s -> s?.state?.map { if (it is TftLinkState.Ready) s else null } ?: flowOf(null) }
            .distinctUntilChanged()
        val saved = places.places.map { (it.home != null) to (it.work != null) }.distinctUntilChanged()
        combine(ready, saved, ::Pair).collect { (session, homeWork) ->
            session?.trySend(listOf(TftMessages.homeSet(homeWork.first), TftMessages.officeSet(homeWork.second)))
        }
    }

    private suspend fun sendList(list: PoiList, load: suspend (GeoPoint?) -> List<SavedPlace>) {
        val session = bikeLink.session.value ?: return
        val here = location.current()
        val items = load(here).sortedBy { p -> here?.let { haversine(it, p.point) } ?: 0.0 }.take(MAX_ITEMS)
        val index = nextList.also { nextList = 1 - it }
        lists[index] = items
        session.trySend(poiPackets(list, index, items, here, streamer.heading))
    }

    /** Each station costs one Place v4 call for its coordinates, so the list is capped at [MAX_GAS]. */
    private suspend fun gasStations(here: GeoPoint?): List<SavedPlace> {
        if (here == null || !search.isConfigured) return emptyList()
        return try {
            coroutineScope {
                search.gasStations(here, GAS_RADIUS_M).take(MAX_GAS).map { s ->
                    async { search.place(s.refId).let { SavedPlace(s.name, s.address, GeoPoint(it.lat, it.lng)) } }
                }.awaitAll()
            }
        } catch (e: IOException) {
            Log.w(TAG, "gas station search failed", e)
            emptyList()
        }
    }

    /** Same path as the app's Navigate button: route preview, then start guidance on the recommended route. */
    private suspend fun navigate(place: SavedPlace) {
        navigator.preview(place)
        val s = navigator.state.first { it !is NavState.Routing }
        if (s is NavState.Preview && s.destination == place) navigator.start()
    }

    private suspend fun TftSession.trySend(packets: List<ByteArray>) =
        runCatching { send(*packets.toTypedArray()) }.onFailure { Log.w(TAG, "dash send failed", it) }

    companion object {
        private const val TAG = "DashPlaces"
        const val MAX_ITEMS = 30
        const val MAX_GAS = 10
        const val GAS_RADIUS_M = 5_000

        /** Header (7/8) then one item (98/99) per place; distance and arrow from [here], relative to [headingDeg]. */
        fun poiPackets(list: PoiList, listIndex: Int, items: List<SavedPlace>, here: GeoPoint?, headingDeg: Double): List<ByteArray> =
            listOf(TftMessages.poiListHeader(list, items.size)) + items.mapIndexed { i, p ->
                val (dist, unit) = here?.let { dashDistance(haversine(it, p.point)) } ?: (0f to "")
                val dir = here?.let { direction(it, p.point, headingDeg) } ?: PoiDirection.UNDEFINED
                TftMessages.poiItem(list, i, listIndex, dir, dist, unit, p.name)
            }

        /** 45° sector of the target relative to the rider's heading (1 front … 8 front-left, clockwise). */
        fun direction(from: GeoPoint, to: GeoPoint, headingDeg: Double): PoiDirection {
            val relative = (bearing(from, to) - headingDeg + 360 + 22.5) % 360
            return PoiDirection.entries[(relative / 45).toInt() + 1]
        }

        private fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val y = sin(dLng) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }
}
