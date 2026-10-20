package com.lampp.nmaxconnect.feature.navigate

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.location.LocationSource
import com.lampp.nmaxconnect.core.navigation.NavState
import com.lampp.nmaxconnect.core.navigation.Navigator
import com.lampp.nmaxconnect.core.navigation.Route
import com.lampp.nmaxconnect.core.places.PlacesRepository
import com.lampp.nmaxconnect.core.places.SavedPlace
import com.lampp.nmaxconnect.core.places.SharedLocationInbox
import com.lampp.nmaxconnect.core.places.SharedLocationResolver
import com.lampp.nmaxconnect.core.vietmap.PlaceDetail
import com.lampp.nmaxconnect.core.vietmap.PlaceSearchApi
import com.lampp.nmaxconnect.core.vietmap.PlaceSuggestion
import com.lampp.nmaxconnect.core.vietmap.VietMapException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigateViewModelTest {

    private class FakeApi(var configured: Boolean = true) : PlaceSearchApi {
        /** Answers Reverse with a city by latitude (north of 20° is Hà Nội); none by default. */
        var reverseByLatitude = false
        override suspend fun reverse(point: GeoPoint) = if (!reverseByLatitude) {
            emptyList()
        } else {
            val city = if (point.lat > 20) "Huyện Chương Mỹ,Thành Phố Hà Nội" else "Quận 5, Thành phố Hồ Chí Minh"
            listOf(PlaceSuggestion("rev", "spot", city))
        }
        override suspend fun gasStations(at: GeoPoint, radiusM: Int) = emptyList<PlaceSuggestion>()
        val queries = mutableListOf<Pair<String, GeoPoint?>>()
        val placeCalls = mutableListOf<String>()
        var fail = false
        var address = "Q5"
        var placePoint = GeoPoint(10.75, 106.67)
        override val isConfigured get() = configured
        override suspend fun autocomplete(text: String, focus: GeoPoint?): List<PlaceSuggestion> {
            queries += text to focus
            if (fail) throw VietMapException("offline")
            return listOf(PlaceSuggestion("ref:$text", "Result for $text", address))
        }
        override suspend fun place(refId: String): PlaceDetail {
            placeCalls += refId
            return PlaceDetail(display = "full", lat = placePoint.lat, lng = placePoint.lng)
        }
    }

    /** In-memory DataStore: runs entirely on the test clock (the real one does file I/O off it). */
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(state.value).also { state.value = it }
    }

    /** Records calls; navigation itself is covered by NavigationTest. */
    private class FakeNavigator : Navigator {
        val previews = mutableListOf<SavedPlace>()
        override val state = MutableStateFlow<NavState>(NavState.Idle)
        val origins = mutableListOf<SavedPlace?>()
        override fun preview(destination: SavedPlace, origin: SavedPlace?) {
            previews += destination
            origins += origin
            state.value = NavState.Routing(destination, origin)
        }
        var selected = -1
        override fun selectRoute(index: Int) { selected = index }
        override fun start() {
            val p = state.value as NavState.Preview
            state.value = NavState.Active(p.destination, p.routes[selected], progress = null, rerouting = false)
        }
        override fun stop() = Unit
        override fun dismiss() { state.value = NavState.Idle }
    }

    private val navigator = FakeNavigator()
    private val inbox = SharedLocationInbox()
    private val here = GeoPoint(10.7589, 106.6754)
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(api: FakeApi): NavigateViewModel {
        val location = object : LocationSource {
            override suspend fun current() = here
            override fun hasPermission() = true
        }
        val vm = NavigateViewModel(
            api,
            PlacesRepository(MemoryStore(), Json),
            location,
            navigator,
            inbox,
            SharedLocationResolver(OkHttpClient(), dispatcher),
        )
        backgroundScope.launch { vm.uiState.collect {} } // WhileSubscribed needs a subscriber
        return vm
    }

    @Test fun quickStartGoesOnTheFastestRouteAndKeepsTheCardWhileRouting() = runTest(dispatcher) {
        val vm = viewModel(FakeApi())
        val dest = SavedPlace("Dest", "", GeoPoint(10.0, 106.0))
        vm.onAction(NavigateUiAction.PickSaved(dest))
        vm.onAction(NavigateUiAction.QuickStart)
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.quickStarting)
        assertEquals(NavState.Routing(dest), navigator.state.value)

        val slow = Route(emptyList(), 1000.0, timeMs = 300_000, maneuvers = emptyList())
        val fast = Route(emptyList(), 1200.0, timeMs = 200_000, maneuvers = emptyList())
        navigator.state.value = NavState.Preview(dest, listOf(slow, fast))
        advanceUntilIdle()
        assertEquals(fast, (navigator.state.value as NavState.Active).route)
        assertEquals(false, vm.uiState.value.quickStarting)
    }

    @Test fun quickStartPrefersTheMainRoadRouteOverAFasterAlleyOne() = runTest(dispatcher) {
        val vm = viewModel(FakeApi())
        val dest = SavedPlace("Dest", "", GeoPoint(10.0, 106.0))
        vm.onAction(NavigateUiAction.PickSaved(dest))
        vm.onAction(NavigateUiAction.QuickStart)
        advanceUntilIdle()

        val main = Route(emptyList(), 1000.0, timeMs = 300_000, maneuvers = emptyList(), mainRoads = true)
        val alleys = Route(emptyList(), 900.0, timeMs = 280_000, maneuvers = emptyList())
        // Previews list the fastest first, so the main-road route is found wherever it sits.
        navigator.state.value = NavState.Preview(dest, listOf(alleys, main))
        advanceUntilIdle()
        assertEquals(main, (navigator.state.value as NavState.Active).route)
    }

    @Test fun sharedPlaceOpensItsCardAtGooglesCoordinates() = runTest(dispatcher) {
        val vm = viewModel(FakeApi())
        inbox.offer("Chợ Bến Thành\nhttps://maps.google.com/?q=10.7725,106.698")
        vm.onAction(NavigateUiAction.OpenShared)
        advanceUntilIdle()
        assertEquals(SavedPlace("Chợ Bến Thành", "", GeoPoint(10.7725, 106.698)), vm.uiState.value.selected)
        assertNull(inbox.text.value)
    }

    @Test fun sharedPlaceWithoutCoordinatesIsMatchedOnVietMap() = runTest(dispatcher) {
        val api = FakeApi().apply { address = "Phường Ngọc Khánh, Quận Ba Đình, Thành phố Hà Nội" }
        val vm = viewModel(api)
        inbox.offer(LOTTE_SHARE)
        vm.onAction(NavigateUiAction.OpenShared)
        advanceUntilIdle()
        assertEquals(listOf("Lotte Hotel Hanoi 54 Liễu Giai" to here), api.queries)
        assertEquals(GeoPoint(10.75, 106.67), vm.uiState.value.selected?.point)
        assertEquals(false, vm.uiState.value.searchExpanded)
    }

    @Test fun sharedPlusCodeFarFromTheRiderIsRecoveredFromTheSharedArea() = runTest(dispatcher) {
        val api = FakeApi().apply {
            reverseByLatitude = true
            address = "Huyện Chương Mỹ, Thành phố Hà Nội"
            placePoint = GeoPoint(21.0, 105.8) // VietMap's hit for "Xuân Mai, Hà Nội"
        }
        val vm = viewModel(api) // the rider is in Sài Gòn: that reference decodes into the wrong cell
        inbox.offer("RH6J+F7M Camping Đồi Bù\nUnnamed Road, Xuân Mai, Hà Nội 100000, Việt Nam")
        vm.onAction(NavigateUiAction.OpenShared)
        advanceUntilIdle()
        val selected = vm.uiState.value.selected!!
        assertEquals("Camping Đồi Bù", selected.name)
        assertEquals(20.8112, selected.point.lat, 0.0001)
        assertEquals(105.5807, selected.point.lng, 0.0001)
        assertEquals(listOf("Xuân Mai, Hà Nội" to null), api.queries)
    }

    @Test fun sharedPlaceWithoutAMatchOpensSearchForIt() = runTest(dispatcher) {
        val vm = viewModel(FakeApi().apply { fail = true })
        inbox.offer(LOTTE_SHARE)
        vm.onAction(NavigateUiAction.OpenShared)
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.searchExpanded)
        assertEquals("Lotte Hotel Hanoi", vm.uiState.value.query)
        assertNull(vm.uiState.value.selected)
    }

    @Test fun typingIsDebouncedAndOnlyTheLastQueryIsSent() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        listOf("t", "tr", "tra", "tran phu").forEach {
            vm.onAction(NavigateUiAction.QueryChanged(it))
            advanceTimeBy(100)
        }
        advanceUntilIdle()
        assertEquals(listOf("tran phu" to here), api.queries)
        assertEquals("ref:tran phu", vm.uiState.value.suggestions.single().refId)
    }

    @Test fun droppedPinWithoutAnAddressIsNamedByItsCoordinates() = runTest(dispatcher) {
        val vm = viewModel(FakeApi())
        vm.onAction(NavigateUiAction.SearchExpanded(true))
        vm.onAction(NavigateUiAction.DropPin(GeoPoint(10.7725, 106.698)))
        advanceUntilIdle()
        val pin = vm.uiState.value.selected!!
        assertEquals("10.77250, 106.69800", pin.name)
        assertEquals(GeoPoint(10.7725, 106.698), pin.point)
        assertEquals(false, vm.uiState.value.searchExpanded)
    }

    @Test fun fromAndToBoxesRerouteWithTheOtherEndKept() = runTest(dispatcher) {
        val vm = viewModel(FakeApi())
        val dest = SavedPlace("Dest", "", GeoPoint(10.0, 106.0))
        val school = SavedPlace("School", "", GeoPoint(10.1, 106.1))
        val market = SavedPlace("Market", "", GeoPoint(10.2, 106.2))
        vm.onAction(NavigateUiAction.PickSaved(dest))
        vm.onAction(NavigateUiAction.PreviewRoute)

        vm.onAction(NavigateUiAction.EditOrigin)
        vm.onAction(NavigateUiAction.SearchExpanded(true)) // the field gaining focus
        advanceUntilIdle()
        assertEquals(SearchTarget.ORIGIN, vm.uiState.value.searchTarget)
        vm.onAction(NavigateUiAction.PickSaved(school))
        assertEquals(NavState.Routing(dest, school), navigator.state.value)

        vm.onAction(NavigateUiAction.EditDestination)
        vm.onAction(NavigateUiAction.PickSaved(market))
        assertEquals(NavState.Routing(market, school), navigator.state.value) // From kept

        vm.onAction(NavigateUiAction.EditOrigin)
        vm.onAction(NavigateUiAction.UseMyLocation)
        advanceUntilIdle()
        assertEquals(NavState.Routing(market, null), navigator.state.value)
        assertEquals(false, vm.uiState.value.searchExpanded)
        assertEquals(SearchTarget.PLACE, vm.uiState.value.searchTarget)
    }

    @Test fun swapExchangesEndsAndTurnsYourLocationIntoARealPlace() = runTest(dispatcher) {
        val vm = viewModel(FakeApi())
        val dest = SavedPlace("Dest", "", GeoPoint(10.0, 106.0))
        val school = SavedPlace("School", "", GeoPoint(10.1, 106.1))

        navigator.state.value = NavState.Routing(dest, origin = school)
        vm.onAction(NavigateUiAction.SwapEndpoints)
        advanceUntilIdle()
        assertEquals(NavState.Routing(school, origin = dest), navigator.state.value)

        // From = your location: the current fix becomes To (no Reverse hit → named by coordinates).
        navigator.state.value = NavState.Routing(dest, origin = null)
        vm.onAction(NavigateUiAction.SwapEndpoints)
        advanceUntilIdle()
        val swapped = navigator.state.value as NavState.Routing
        assertEquals(dest, swapped.origin)
        assertEquals(here, swapped.destination.point)
        assertEquals("10.75890, 106.67540", swapped.destination.name)

        // Swapping back: the rider's spot becomes From again as "Your location".
        vm.onAction(NavigateUiAction.SwapEndpoints)
        advanceUntilIdle()
        assertEquals(NavState.Routing(dest, origin = null), navigator.state.value)
    }

    @Test fun chooseOnMapSetsTheStartingPoint() = runTest(dispatcher) {
        val vm = viewModel(FakeApi())
        val dest = SavedPlace("Dest", "", GeoPoint(10.0, 106.0))
        vm.onAction(NavigateUiAction.PickSaved(dest))
        vm.onAction(NavigateUiAction.PreviewRoute)
        vm.onAction(NavigateUiAction.EditOrigin)
        vm.onAction(NavigateUiAction.ChooseOnMap)
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.choosingOnMap)
        assertEquals(false, vm.uiState.value.searchExpanded)

        vm.onAction(NavigateUiAction.ConfirmMapPoint(GeoPoint(10.5, 106.5)))
        advanceUntilIdle()
        val state = navigator.state.value as NavState.Routing
        assertEquals(dest, state.destination)
        assertEquals(GeoPoint(10.5, 106.5), state.origin!!.point)
        assertEquals(false, vm.uiState.value.choosingOnMap)
    }

    @Test fun bookmarkTogglesTheSelectedPlaceInFavorites() = runTest(dispatcher) {
        val vm = viewModel(FakeApi())
        val place = SavedPlace("Hồ Gươm", "Hoàn Kiếm", GeoPoint(21.0287, 105.8524))
        vm.onAction(NavigateUiAction.PickSaved(place))
        vm.onAction(NavigateUiAction.ToggleFavorite)
        advanceUntilIdle()
        assertEquals(listOf(place), vm.uiState.value.favorites)
        assertEquals(true, vm.uiState.value.selectedIsFavorite)

        // Same spot picked again (e.g. from search, slightly different coordinates) un-saves it.
        vm.onAction(NavigateUiAction.PickSaved(place.copy(point = GeoPoint(21.02872, 105.85241))))
        vm.onAction(NavigateUiAction.ToggleFavorite)
        advanceUntilIdle()
        assertEquals(emptyList<SavedPlace>(), vm.uiState.value.favorites)
    }

    @Test fun pickingANewPlaceClosesThePreviousRoutesButNotATrip() = runTest(dispatcher) {
        val vm = viewModel(FakeApi())
        val new = SavedPlace("New", "", GeoPoint(10.1, 106.1))

        navigator.state.value = previewRoutes
        vm.onAction(NavigateUiAction.PickSaved(new))
        advanceUntilIdle()
        assertEquals(NavState.Idle, vm.uiState.value.nav)
        assertEquals(new, vm.uiState.value.selected)

        navigator.state.value = previewActive
        vm.onAction(NavigateUiAction.PickSaved(new))
        advanceUntilIdle()
        assertEquals(previewActive, vm.uiState.value.nav)
    }

    @Test fun singleCharacterNeverCallsTheApi() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.onAction(NavigateUiAction.QueryChanged("a"))
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), api.queries)
    }

    @Test fun notConfiguredAndNetworkFailuresSurfaceAsProblems() = runTest(dispatcher) {
        val unconfigured = viewModel(FakeApi(configured = false))
        unconfigured.onAction(NavigateUiAction.QueryChanged("tran phu"))
        advanceUntilIdle()
        assertEquals(SearchProblem.NOT_CONFIGURED, unconfigured.uiState.value.problem)

        val api = FakeApi().apply { fail = true }
        val offline = viewModel(api)
        offline.onAction(NavigateUiAction.QueryChanged("tran phu"))
        advanceUntilIdle()
        assertEquals(SearchProblem.NETWORK, offline.uiState.value.problem)
    }

    @Test fun pickingResolvesOnceSelectsAndRemembers() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.onAction(NavigateUiAction.QueryChanged("ben thanh"))
        advanceUntilIdle()
        val suggestion = vm.uiState.value.suggestions.single()

        vm.onAction(NavigateUiAction.PickSuggestion(suggestion))
        vm.onAction(NavigateUiAction.PickSuggestion(suggestion)) // double tap
        advanceUntilIdle()

        assertEquals(listOf("ref:ben thanh"), api.placeCalls)
        val selected = vm.uiState.value.selected!!
        assertEquals("Result for ben thanh", selected.name)
        assertEquals(GeoPoint(10.75, 106.67), selected.point)
        assertEquals(listOf(selected), vm.uiState.value.recents)
        assertEquals("", vm.uiState.value.query)

        vm.onAction(NavigateUiAction.ToggleHome)
        advanceUntilIdle()
        assertEquals(selected, vm.uiState.value.home)
        assertNull(vm.uiState.value.work)
        vm.onAction(NavigateUiAction.ToggleHome) // second tap clears it, like the bookmark
        advanceUntilIdle()
        assertNull(vm.uiState.value.home)

        vm.onAction(NavigateUiAction.PreviewRoute)
        assertEquals(listOf(selected), navigator.previews)
    }

    private companion object {
        // What a Google Maps place share expands to: name + address, a place id, no coordinates.
        const val LOTTE_SHARE = "https://maps.google.com/?q=Lotte+Hotel+Hanoi,+54+Li%E1%BB%85u+Giai," +
            "+Ba+%C4%90%C3%ACnh,+H%C3%A0+N%E1%BB%99i&ftid=0x3135ab6cecd4ed9d:0xaa0bbd53d1491c7a&entry=gps"
    }
}
