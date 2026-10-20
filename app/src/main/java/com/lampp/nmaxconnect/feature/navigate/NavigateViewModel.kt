package com.lampp.nmaxconnect.feature.navigate

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.location.LocationSource
import com.lampp.nmaxconnect.core.navigation.NavState
import com.lampp.nmaxconnect.core.navigation.Navigator
import com.lampp.nmaxconnect.core.navigation.haversine
import com.lampp.nmaxconnect.core.places.PlacesRepository
import com.lampp.nmaxconnect.core.places.PlusCode
import com.lampp.nmaxconnect.core.places.SavedPlace
import com.lampp.nmaxconnect.core.places.SharedLocation
import com.lampp.nmaxconnect.core.places.SharedLocationInbox
import com.lampp.nmaxconnect.core.places.SharedLocationResolver
import com.lampp.nmaxconnect.core.vietmap.PlaceSearchApi
import com.lampp.nmaxconnect.core.vietmap.PlaceSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchProblem { NOT_CONFIGURED, NETWORK, NO_RESULTS }

/** What a pick in the search view becomes: a place card, or the From / To of the shown directions. */
enum class SearchTarget { PLACE, ORIGIN, DESTINATION }

@Immutable
data class NavigateUiState(
    val searchAvailable: Boolean = true,
    val query: String = "",
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val searching: Boolean = false,
    val problem: SearchProblem? = null,
    val home: SavedPlace? = null,
    val work: SavedPlace? = null,
    val recents: List<SavedPlace> = emptyList(),
    val favorites: List<SavedPlace> = emptyList(),
    /** Destination the user picked; routing (roadmap step 3) starts from here. */
    val selected: SavedPlace? = null,
    val resolving: Boolean = false,
    val locationGranted: Boolean = false,
    /** Route preview / active guidance; replaces the place card while not Idle. */
    val nav: NavState = NavState.Idle,
    /** Full-screen search view open (suggestions, saved places, recents) over the map. */
    val searchExpanded: Boolean = false,
    val searchTarget: SearchTarget = SearchTarget.PLACE,
    /** "Choose on map": a fixed pin over the map picks the spot for [searchTarget]. */
    val choosingOnMap: Boolean = false,
    /** Start on the place card: routing to [selected] and starting on the fastest route, without the preview. */
    val quickStarting: Boolean = false,
    /** False until saved places are read: the Home/Work chips would push the map down after its first frame. */
    val placesLoaded: Boolean = false,
) {
    val selectedIsFavorite: Boolean get() = selected?.let { PlacesRepository.isFavorite(favorites, it) } ?: false
    val selectedIsHome: Boolean get() = selected?.let { PlacesRepository.isFavorite(listOfNotNull(home), it) } ?: false
    val selectedIsWork: Boolean get() = selected?.let { PlacesRepository.isFavorite(listOfNotNull(work), it) } ?: false
}

sealed interface NavigateUiAction {
    data class QueryChanged(val text: String) : NavigateUiAction
    data object ClearQuery : NavigateUiAction
    data class SearchExpanded(val expanded: Boolean) : NavigateUiAction

    /** From / To boxes of the directions header: open the search for that end. */
    data object EditOrigin : NavigateUiAction
    data object EditDestination : NavigateUiAction

    /** "Your location" row while choosing From. */
    data object UseMyLocation : NavigateUiAction

    /** Swap button of the directions header: From and To change places. */
    data object SwapEndpoints : NavigateUiAction

    /** "Choose on map" row → pin mode; confirm with the coordinate under the pin, or cancel. */
    data object ChooseOnMap : NavigateUiAction
    data class ConfirmMapPoint(val point: GeoPoint) : NavigateUiAction
    data object CancelMapPick : NavigateUiAction

    /** Long press on the map: Reverse v4 names the spot, the coordinates are kept as-is. */
    data class DropPin(val point: GeoPoint) : NavigateUiAction
    data class SelectRoute(val index: Int) : NavigateUiAction
    data class PickSuggestion(val suggestion: PlaceSuggestion) : NavigateUiAction
    data class PickSaved(val place: SavedPlace) : NavigateUiAction
    data object CloseSelected : NavigateUiAction

    /** Set the selected place as Home / Work, or clear it if it already is. */
    data object ToggleHome : NavigateUiAction
    data object ToggleWork : NavigateUiAction

    /** Bookmark / un-bookmark the selected place. */
    data object ToggleFavorite : NavigateUiAction

    /** Sent on resume and after the permission dialog: the ViewModel re-reads the permission. */
    data object LocationPermissionChanged : NavigateUiAction
    data object PreviewRoute : NavigateUiAction
    data object StartNavigation : NavigateUiAction

    /** Start on the place card: route to the selected place and go on the fastest route. */
    data object QuickStart : NavigateUiAction
    data object StopNavigation : NavigateUiAction
    data object RetryRoute : NavigateUiAction
    data object DismissNavigation : NavigateUiAction

    /** A place shared from Google Maps (or a `geo:` link) is waiting in the inbox. */
    data object OpenShared : NavigateUiAction

    /** Handled by the Route (permission dialog). */
    data object RequestLocation : NavigateUiAction
}

/**
 * Destination search: Autocomplete v4 while typing (debounced, ≥ 2 chars, stale requests cancelled),
 * Place v4 once on selection, per docs/vietmap-agent-docs/search-geocoding.txt.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class NavigateViewModel @Inject constructor(
    private val api: PlaceSearchApi,
    private val places: PlacesRepository,
    private val location: LocationSource,
    private val navigator: Navigator,
    private val sharedInbox: SharedLocationInbox,
    private val sharedResolver: SharedLocationResolver,
) : ViewModel() {

    // Real value from the start: the Route's resume refresh only lands after the tab's enter transition.
    private val local = MutableStateFlow(NavigateUiState(searchAvailable = api.isConfigured, locationGranted = location.hasPermission()))
    private val query = MutableStateFlow("")

    val uiState: StateFlow<NavigateUiState> = combine(local, places.places, navigator.state) { l, saved, nav ->
        l.copy(home = saved.home, work = saved.work, recents = saved.recents, favorites = saved.favorites, nav = nav, placesLoaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    /** Non-null while a share waits; the Route sends [NavigateUiAction.OpenShared] only while the tab is shown. */
    val sharedText: StateFlow<String?> = sharedInbox.text

    init {
        viewModelScope.launch {
            query.debounce(DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged().collectLatest(::search)
        }
    }

    fun onAction(action: NavigateUiAction) {
        when (action) {
            is NavigateUiAction.QueryChanged -> {
                local.update { it.copy(query = action.text) }
                query.value = action.text
            }
            NavigateUiAction.ClearQuery -> {
                local.update { it.copy(query = "", suggestions = emptyList(), problem = null) }
                query.value = ""
            }
            // The field reports "expanded" again when it takes focus; that must not reset a From/To search.
            is NavigateUiAction.SearchExpanded -> local.update {
                if (it.searchExpanded == action.expanded) {
                    it
                } else {
                    it.copy(searchExpanded = action.expanded, searchTarget = SearchTarget.PLACE)
                }
            }
            NavigateUiAction.EditOrigin -> local.update { it.copy(searchExpanded = true, searchTarget = SearchTarget.ORIGIN) }
            NavigateUiAction.EditDestination -> local.update { it.copy(searchExpanded = true, searchTarget = SearchTarget.DESTINATION) }
            // Keeps searchTarget, so the confirmed spot becomes From / To.
            NavigateUiAction.ChooseOnMap -> local.update { it.copy(searchExpanded = false, choosingOnMap = true) }
            is NavigateUiAction.ConfirmMapPoint -> dropPin(action.point)
            NavigateUiAction.CancelMapPick -> closeSearch()
            NavigateUiAction.SwapEndpoints -> swap()
            NavigateUiAction.UseMyLocation -> {
                closeSearch()
                navigator.state.value.destination()?.let { navigator.preview(it) }
            }
            is NavigateUiAction.DropPin -> dropPin(action.point)
            is NavigateUiAction.SelectRoute -> navigator.selectRoute(action.index)
            is NavigateUiAction.PickSuggestion -> resolve(action.suggestion)
            is NavigateUiAction.PickSaved -> select(action.place)
            NavigateUiAction.CloseSelected -> local.update { it.copy(selected = null) }
            NavigateUiAction.ToggleHome -> uiState.value.let { s ->
                s.selected?.let { viewModelScope.launch { places.setHome(it.takeUnless { s.selectedIsHome }) } }
            }
            NavigateUiAction.ToggleWork -> uiState.value.let { s ->
                s.selected?.let { viewModelScope.launch { places.setWork(it.takeUnless { s.selectedIsWork }) } }
            }
            NavigateUiAction.ToggleFavorite -> local.value.selected?.let { viewModelScope.launch { places.toggleFavorite(it) } }
            NavigateUiAction.LocationPermissionChanged -> local.update { it.copy(locationGranted = location.hasPermission()) }
            NavigateUiAction.PreviewRoute -> local.value.selected?.let(navigator::preview)
            NavigateUiAction.StartNavigation -> navigator.start()
            NavigateUiAction.QuickStart -> quickStart()
            NavigateUiAction.StopNavigation -> navigator.stop()
            NavigateUiAction.RetryRoute -> (navigator.state.value as? NavState.Failed)?.let { navigator.preview(it.destination, it.origin) }
            NavigateUiAction.DismissNavigation -> navigator.dismiss()
            NavigateUiAction.OpenShared -> sharedInbox.take()?.let(::openShared)
            NavigateUiAction.RequestLocation -> Unit
        }
    }

    private fun quickStart() {
        val place = local.value.selected ?: return
        if (local.value.quickStarting) return
        local.update { it.copy(quickStarting = true) }
        navigator.preview(place)
        viewModelScope.launch {
            val trip = navigator.state.first { it !is NavState.Routing }
            if (trip is NavState.Preview) {
                // Main roads beat a faster alley route (N27); otherwise the fastest.
                val mainRoads = trip.routes.indexOfFirst { it.mainRoads }
                navigator.selectRoute(if (mainRoads >= 0) mainRoads else trip.routes.indices.minBy { trip.routes[it].timeMs })
                navigator.start()
                // Active is set from the navigator's scope; until then keep the card instead of flashing the preview.
                navigator.state.first { it !is NavState.Preview }
            }
            local.update { it.copy(quickStarting = false) }
        }
    }

    private suspend fun search(text: String) {
        if (text.length < MIN_CHARS) {
            local.update { it.copy(suggestions = emptyList(), searching = false, problem = null) }
            return
        }
        if (!api.isConfigured) {
            local.update { it.copy(problem = SearchProblem.NOT_CONFIGURED) }
            return
        }
        local.update { it.copy(searching = true, problem = null) }
        try {
            // Nearest first; without a fix there is no distance and VietMap's order is kept (stable sort).
            val results = api.autocomplete(text, location.current()).sortedBy { it.distance ?: Double.MAX_VALUE }
            local.update {
                it.copy(suggestions = results, searching = false, problem = if (results.isEmpty()) SearchProblem.NO_RESULTS else null)
            }
        } catch (e: IOException) {
            local.update { it.copy(suggestions = emptyList(), searching = false, problem = SearchProblem.NETWORK) }
        }
    }

    private fun resolve(suggestion: PlaceSuggestion) {
        if (local.value.resolving) return // one Place v4 call per selection
        local.update { it.copy(resolving = true, problem = null) }
        viewModelScope.launch {
            try {
                select(placeOf(suggestion))
            } catch (e: IOException) {
                local.update { it.copy(resolving = false, problem = SearchProblem.NETWORK) }
            }
        }
    }

    private suspend fun placeOf(suggestion: PlaceSuggestion): SavedPlace {
        val detail = api.place(suggestion.refId)
        return SavedPlace(
            name = suggestion.name.ifBlank { detail.name.ifBlank { detail.display } },
            address = suggestion.address.ifBlank { detail.display },
            point = GeoPoint(detail.lat, detail.lng),
        )
    }

    /**
     * Google's own coordinates win over any VietMap POI of the same name (that can be a different branch or
     * the far side of a campus). Place shares carry only a name and address: those are matched on VietMap, and
     * only when no hit plausibly names the same place does the rider pick from search.
     */
    private fun openShared(text: String) {
        closeSearch()
        local.update { it.copy(resolving = true, selected = null) }
        viewModelScope.launch {
            val shared = sharedResolver.resolve(text)
            val point = shared.point
            val place = if (point != null) {
                shared.name?.let { SavedPlace(it, shared.address.orEmpty(), point) } ?: namedSpot(point)
            } else {
                plusCodePlace(shared) ?: vietMapMatch(shared)
            }
            if (place != null) {
                select(place)
            } else {
                val name = shared.name.orEmpty()
                local.update { it.copy(resolving = false, searchExpanded = true, query = name) }
                query.value = name
            }
        }
    }

    /** The first of VietMap's top hits that [SharedLocation.matches] the share, else null (the rider picks). */
    @Suppress("SwallowedException") // offline: the search view that opens instead shows the problem
    private suspend fun vietMapMatch(shared: SharedLocation): SavedPlace? {
        val text = shared.searchText?.takeIf { api.isConfigured } ?: return null
        return try {
            api.autocomplete(text, location.current())
                .take(MATCH_CANDIDATES)
                .firstOrNull { shared.matches(it.name, it.address) }
                ?.let { placeOf(it) }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * A short Plus Code ("RH6J+F7M") is Google's exact pin once its ~1° cell is known: decoded against the rider's
     * position, else a VietMap hit for the shared area, and kept only if Reverse puts it in the shared city
     * (a wrong cell lands ~110 km away, in another province).
     */
    @Suppress("SwallowedException") // offline: the search view that opens instead shows the problem
    private suspend fun plusCodePlace(shared: SharedLocation): SavedPlace? {
        val code = shared.plusCode?.takeIf { api.isConfigured } ?: return null
        val references: List<suspend () -> GeoPoint?> = listOf({ location.current() }, { areaPoint(shared) })
        return try {
            references.firstNotNullOfOrNull { reference -> reference()?.let { inSharedCity(shared, PlusCode.recover(code, it)) } }
        } catch (e: IOException) {
            null
        }
    }

    private suspend fun areaPoint(shared: SharedLocation): GeoPoint? {
        val area = shared.locality ?: return null
        return api.autocomplete(area, null)
            .take(MATCH_CANDIDATES)
            .firstOrNull { shared.inSharedCity(it.address) }
            ?.let { placeOf(it).point }
    }

    private suspend fun inSharedCity(shared: SharedLocation, point: GeoPoint): SavedPlace? {
        val hit = api.reverse(point).firstOrNull()?.takeIf { shared.inSharedCity(it.address) } ?: return null
        return SavedPlace(name = shared.name ?: hit.name, address = shared.address ?: hit.address, point = point)
    }

    private fun dropPin(point: GeoPoint) {
        if (local.value.resolving) return
        local.update { it.copy(resolving = true) }
        viewModelScope.launch { select(namedSpot(point)) }
    }

    /** Reverse v4 names [point]; offline or unconfigured still gives a usable place, named by its coordinates. */
    private suspend fun namedSpot(point: GeoPoint): SavedPlace {
        val hit = try { api.reverse(point).firstOrNull() } catch (e: IOException) { null }
        return SavedPlace(
            name = hit?.name?.ifBlank { null } ?: hit?.display?.ifBlank { null } ?: "%.5f, %.5f".format(Locale.US, point.lat, point.lng),
            address = hit?.address.orEmpty(),
            point = point,
        )
    }

    /** From ↔ To. "Your location" as From becomes a real To: the current fix, named like a dropped pin. */
    private fun swap() {
        val trip = navigator.state.value
        val destination = trip.destination() ?: return
        val origin = trip.origin()
        viewModelScope.launch {
            val here = location.current()
            if (origin != null) {
                // Swapping back onto where the rider stands restores "Your location", so Start comes back.
                val atRider = here != null && haversine(here, destination.point) < AT_RIDER_M
                navigator.preview(origin, origin = destination.takeUnless { atRider })
            } else if (here != null) {
                navigator.preview(namedSpot(here), origin = destination)
            }
        }
    }

    private fun select(place: SavedPlace) {
        val target = local.value.searchTarget
        val trip = navigator.state.value
        closeSearch()
        if (target != SearchTarget.ORIGIN) local.update { it.copy(selected = place) }
        when (target) {
            SearchTarget.ORIGIN -> trip.destination()?.let { navigator.preview(it, origin = place) }
            SearchTarget.DESTINATION -> navigator.preview(place, origin = trip.origin())
            // A new pick replaces the previous destination's routes; an active trip keeps going.
            SearchTarget.PLACE -> if (trip != NavState.Idle && trip !is NavState.Active) navigator.dismiss()
        }
        viewModelScope.launch { places.addRecent(place) }
    }

    private fun closeSearch() {
        local.update {
            it.copy(
                resolving = false,
                query = "",
                suggestions = emptyList(),
                problem = null,
                searchExpanded = false,
                searchTarget = SearchTarget.PLACE,
                choosingOnMap = false,
            )
        }
        query.value = ""
    }

    private fun NavState.destination(): SavedPlace? = when (this) {
        is NavState.Routing -> destination
        is NavState.Preview -> destination
        is NavState.Failed -> destination
        else -> null
    }

    private fun NavState.origin(): SavedPlace? = when (this) {
        is NavState.Routing -> origin
        is NavState.Preview -> origin
        is NavState.Failed -> origin
        else -> null
    }

    companion object {
        const val DEBOUNCE_MS = 250L
        const val MIN_CHARS = 2
        private const val AT_RIDER_M = 50.0
        private const val MATCH_CANDIDATES = 5
    }
}
