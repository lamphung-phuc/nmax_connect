package com.lampp.nmaxconnect.feature.navigate

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.navigation.NavState
import com.lampp.nmaxconnect.core.places.SavedPlace
import com.lampp.nmaxconnect.core.vietmap.PlaceSuggestion
import com.lampp.nmaxconnect.ui.components.EmptyState
import com.lampp.nmaxconnect.ui.components.HelpSection
import com.lampp.nmaxconnect.ui.components.HelpSheet
import com.lampp.nmaxconnect.ui.components.InfoCard
import com.lampp.nmaxconnect.ui.theme.AppMotion
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme

private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@Composable
fun NavigateRoute(
    mapHolder: NavigateMapHolder,
    dashConnected: Boolean,
    modifier: Modifier = Modifier,
    viewModel: NavigateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The result map is empty when the request is interrupted, so the ViewModel re-reads the permission instead.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onAction(NavigateUiAction.LocationPermissionChanged)
    }
    val sharedText by viewModel.sharedText.collectAsStateWithLifecycle()
    LaunchedEffect(sharedText) { if (sharedText != null) viewModel.onAction(NavigateUiAction.OpenShared) }
    LifecycleResumeEffect(Unit) {
        viewModel.onAction(NavigateUiAction.LocationPermissionChanged)
        onPauseOrDispose { }
    }
    NavigateScreen(
        uiState = uiState,
        onAction = { if (it == NavigateUiAction.RequestLocation) launcher.launch(locationPermissions) else viewModel.onAction(it) },
        modifier = modifier,
        mapHolder = mapHolder,
        dashConnected = dashConnected,
    )
}

/**
 * Google Maps-style: the map fills the screen; search floats on top (expanding into suggestions,
 * saved places and recents); the place card, route options or trip bar sit in a bottom panel.
 * The map is the one screen without AppScaffold (docs/ui-spec.md).
 */
@Composable
fun NavigateScreen(
    uiState: NavigateUiState,
    onAction: (NavigateUiAction) -> Unit,
    modifier: Modifier = Modifier,
    mapHolder: NavigateMapHolder = rememberNavigateMapHolder(),
    dashConnected: Boolean = false,
) {
    // Guiding with the dash linked: the dash shows the map, the phone leaves its own out.
    val mapOnDash = headerKey(uiState) == Header.GUIDING && dashConnected
    var recenter by rememberSaveable { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) HelpSheet(listOf(HelpSection(null, navigateHelpTopics)), onDismiss = { showHelp = false })

    BackHandler(enabled = uiState.searchExpanded) { onAction(NavigateUiAction.SearchExpanded(false)) }
    BackHandler(enabled = uiState.choosingOnMap) { onAction(NavigateUiAction.CancelMapPick) }
    // Back from routes (or the arrived card) returns to the plain map instead of leaving the tab.
    // Not while guiding: a stray edge swipe must not end a trip.
    val showingRoutes = uiState.nav.let {
        it is NavState.Routing || it is NavState.Preview || it is NavState.Failed || it is NavState.Arrived
    }
    BackHandler(enabled = showingRoutes && !uiState.searchExpanded && !uiState.choosingOnMap) {
        onAction(NavigateUiAction.DismissNavigation)
        onAction(NavigateUiAction.CloseSelected)
    }
    BackHandler(enabled = uiState.selected != null && uiState.nav == NavState.Idle && !uiState.searchExpanded) {
        onAction(NavigateUiAction.CloseSelected)
    }

    // Header (search + chips, or the turn banner) is its own block; the map sits below it, not under it.
    // Expanded search takes the whole column height, so the map shrinks to nothing meanwhile.
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Fade through between header modes; within a mode the state just updates (typing, GPS ticks).
        AnimatedContent(
            targetState = uiState,
            contentKey = ::headerKey,
            transitionSpec = { AppMotion.fadeThrough() },
            label = "header",
        ) { state ->
            when (headerKey(state)) {
                Header.GUIDING -> ManeuverBanner(
                    state.nav as NavState.Active,
                    Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Header.SEARCH -> SearchView(state, onAction)
                Header.DIRECTIONS -> DirectionsHeader(state.nav, onAction)
                // Search bar + help only, like the other tabs' headers; the saved chips float on the map.
                Header.IDLE -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    SearchField(state, onAction, Modifier.weight(1f))
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.help))
                    }
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { mapSize = it }
                .clip(MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))),
        ) {
            // While guiding the map is only rendered for the dash (DashStreamer); leaving the phone's out saves GPU and heat.
            if (mapOnDash) {
                EmptyState(
                    icon = Icons.Filled.Cast,
                    title = stringResource(R.string.navigate_streamed_title),
                    body = stringResource(R.string.navigate_streamed_body),
                    modifier = Modifier.fillMaxSize().padding(bottom = with(LocalDensity.current) { bottomPx.toDp() }),
                )
            } else if (uiState.placesLoaded) { // its first frame then already has the final size (a resize stretches it)
                NavigateMap(mapHolder, uiState, onAction, recenter, bottomInsetPx = bottomPx, modifier = Modifier.fillMaxSize())
            }
            MapPin(visible = uiState.choosingOnMap, bottomPx = bottomPx)
            if (headerKey(uiState) == Header.IDLE && !uiState.choosingOnMap) {
                SavedChips(uiState, onAction, Modifier.align(Alignment.TopStart))
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                AnimatedVisibility(
                    visible = uiState.locationGranted && !mapOnDash,
                    enter = scaleIn(AppMotion.spatial()) + fadeIn(AppMotion.effects()),
                    exit = scaleOut(AppMotion.spatialFast()) + fadeOut(AppMotion.effectsFast()),
                ) {
                    SmallFloatingActionButton(
                        onClick = { recenter++ },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                        modifier = Modifier.padding(16.dp),
                    ) { Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.navigate_my_location)) }
                }
                // Panels rise from / slide down to the bottom edge when the map has none, and fade through when one
                // replaces another. No panel is full width (BottomPanel), so only the height animates.
                AnimatedContent(
                    targetState = uiState,
                    contentKey = ::panelKey,
                    contentAlignment = Alignment.BottomCenter,
                    // The panel alone (not the my-location button above it) covers the map: routes centre above it.
                    modifier = Modifier.onSizeChanged { bottomPx = it.height },
                    transitionSpec = {
                        when {
                            panelKey(initialState) == Panel.NONE -> AppMotion.enterFromBottom togetherWith fadeOut(AppMotion.effectsFast())
                            // Slide only: the shared exit's quick fade hid the card before it moved.
                            panelKey(targetState) == Panel.NONE ->
                                fadeIn(AppMotion.effects()) togetherWith slideOutVertically(AppMotion.spatial()) { it }
                            else -> AppMotion.fadeThroughEnter togetherWith AppMotion.fadeThroughExit
                        }.using(AppMotion.sizeTransform)
                    },
                    label = "panel",
                ) { state ->
                    if (state.choosingOnMap) {
                        MapPickPanel(onAction) { mapHolder.pointAt(mapSize.width / 2f, (mapSize.height - bottomPx) / 2f) }
                    } else {
                        BottomPanel(state, onAction)
                    }
                }
            }
        }
    }
}

private enum class Header { GUIDING, SEARCH, DIRECTIONS, IDLE }

private fun headerKey(s: NavigateUiState) = when {
    s.nav is NavState.Active -> Header.GUIDING
    s.searchExpanded -> Header.SEARCH
    s.quickStarting -> Header.IDLE
    s.nav is NavState.Routing || s.nav is NavState.Preview || s.nav is NavState.Failed -> Header.DIRECTIONS
    else -> Header.IDLE
}

/** Which bottom panel shows; route states are separate keys so Routing -> Preview -> Active fade through. */
private enum class Panel { PICK, ROUTING, PREVIEW, ACTIVE, ARRIVED, FAILED, PLACE, RESOLVING, UNAVAILABLE, LOCATION, NONE }

private fun panelKey(s: NavigateUiState) = when {
    s.choosingOnMap -> Panel.PICK
    s.quickStarting -> Panel.PLACE
    s.nav is NavState.Routing -> Panel.ROUTING
    s.nav is NavState.Preview -> Panel.PREVIEW
    s.nav is NavState.Active -> Panel.ACTIVE
    s.nav is NavState.Arrived -> Panel.ARRIVED
    s.nav is NavState.Failed -> Panel.FAILED
    s.selected != null -> Panel.PLACE
    s.resolving -> Panel.RESOLVING
    !s.searchAvailable -> Panel.UNAVAILABLE
    !s.locationGranted -> Panel.LOCATION
    else -> Panel.NONE
}

/** Fixed pin over the middle of the visible map (above the panel); the map moves under it. Drops in from above. */
@Composable
private fun MapPin(visible: Boolean, bottomPx: Int) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(AppMotion.spatial()) { -it / 2 } + fadeIn(AppMotion.effects()),
        exit = fadeOut(AppMotion.effectsFast()),
    ) {
        Box(Modifier.fillMaxSize().padding(bottom = with(LocalDensity.current) { bottomPx.toDp() })) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.align(Alignment.Center).offset(y = (-24).dp).size(48.dp),
            )
        }
    }
}

/** "Choose on map": confirm the spot under the pin (Reverse v4 names it) or cancel. */
@Composable
private fun MapPickPanel(onAction: (NavigateUiAction) -> Unit, pinPoint: () -> GeoPoint?) {
    Sheet {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.navigate_map_pick_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onAction(NavigateUiAction.CancelMapPick) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { pinPoint()?.let { onAction(NavigateUiAction.ConfirmMapPoint(it)) } },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.navigate_confirm_point)) }
            }
        }
    }
}

/** Google-style directions header: back, then From (default: your location) and To; tapping either opens the search for it. */
@Composable
private fun DirectionsHeader(nav: NavState, onAction: (NavigateUiAction) -> Unit) {
    val (origin, destination) = when (nav) {
        is NavState.Routing -> nav.origin to nav.destination
        is NavState.Preview -> nav.origin to nav.destination
        is NavState.Failed -> nav.origin to nav.destination
        else -> return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = { onAction(NavigateUiAction.DismissNavigation) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
        }
        // Swap: the button turns half a turn at once; the fields trade places when the swapped trip arrives
        // (it can wait for a GPS fix and a name), each starting from the other's row.
        var swaps by remember { mutableIntStateOf(0) }
        var swapPending by remember { mutableStateOf(false) }
        val turn by animateFloatAsState(swaps * 180f, AppMotion.spatial(), label = "swapTurn")
        val slide = remember { Animatable(0f) }
        var pitchPx by remember { mutableIntStateOf(0) }
        val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
        LaunchedEffect(origin, destination) {
            if (!swapPending) return@LaunchedEffect
            swapPending = false
            slide.snapTo(1f)
            slide.animateTo(0f, AppMotion.spatial())
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            EndpointField(
                label = R.string.navigate_from,
                icon = if (origin == null) Icons.Filled.MyLocation else Icons.Filled.TripOrigin,
                tint = MaterialTheme.colorScheme.primary,
                text = origin?.name ?: stringResource(R.string.navigate_your_location),
                modifier = Modifier.onSizeChanged { pitchPx = it.height + gapPx }.graphicsLayer { translationY = slide.value * pitchPx },
            ) { onAction(NavigateUiAction.EditOrigin) }
            EndpointField(
                label = R.string.navigate_to,
                icon = Icons.Filled.Place,
                tint = MaterialTheme.colorScheme.error, // red destination pin, same as on the map
                text = destination.name,
                modifier = Modifier.graphicsLayer { translationY = -slide.value * pitchPx },
            ) { onAction(NavigateUiAction.EditDestination) }
        }
        IconButton(onClick = {
            swaps++
            swapPending = true
            onAction(NavigateUiAction.SwapEndpoints)
        }) {
            Icon(
                Icons.Filled.SwapVert,
                contentDescription = stringResource(R.string.navigate_swap),
                modifier = Modifier.graphicsLayer { rotationZ = turn },
            )
        }
    }
}

@Composable
private fun EndpointField(
    label: Int,
    icon: ImageVector,
    tint: Color,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(icon, contentDescription = stringResource(label), tint = tint)
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One-tap chips floating at the top of the map, like Google's shortcut row: Home / Work when set, then favorites. */
@Composable
private fun SavedChips(uiState: NavigateUiState, onAction: (NavigateUiAction) -> Unit, modifier: Modifier = Modifier) {
    val chips = listOfNotNull(
        uiState.home?.let { Triple(stringResource(R.string.navigate_home), Icons.Filled.Home, it) },
        uiState.work?.let { Triple(stringResource(R.string.navigate_work), Icons.Filled.Work, it) },
    ) + uiState.favorites.map { Triple(it.name, Icons.Filled.Bookmark, it) }
    if (chips.isEmpty() || uiState.nav != NavState.Idle) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        chips.forEach { (label, icon, place) ->
            AssistChip(
                onClick = { onAction(NavigateUiAction.PickSaved(place)) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                border = null,
            )
        }
    }
}

@Composable
private fun BottomPanel(uiState: NavigateUiState, onAction: (NavigateUiAction) -> Unit) {
    val place = uiState.selected
    when {
        uiState.quickStarting && place != null -> Sheet { PlacePanel(uiState, place, quickStarting = true, onAction) }
        uiState.nav != NavState.Idle -> Sheet { NavPanel(uiState.nav, onAction) }
        place != null -> Sheet { PlacePanel(uiState, place, quickStarting = false, onAction) }
        uiState.resolving -> Sheet { LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp)) }
        !uiState.searchAvailable -> InfoCard(
            title = stringResource(R.string.navigate_unavailable_title),
            body = stringResource(R.string.navigate_unavailable_body),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
        !uiState.locationGranted -> InfoCard(
            title = stringResource(R.string.navigate_location_title),
            body = stringResource(R.string.navigate_location_body),
            actionLabel = stringResource(R.string.action_grant),
            onAction = { onAction(NavigateUiAction.RequestLocation) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
        // Full width, no height: a panel coming or going then only changes height, so it slides straight.
        else -> Spacer(Modifier.fillMaxWidth())
    }
}

/** Bottom sheet look (top corners only, tonal, no shadow); sized to its content. */
@Composable
private fun Sheet(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        content = content,
    )
}

@Composable
private fun PlacePanel(uiState: NavigateUiState, place: SavedPlace, quickStarting: Boolean, onAction: (NavigateUiAction) -> Unit) {
    val favorite = uiState.selectedIsFavorite
    val isHome = uiState.selectedIsHome
    val isWork = uiState.selectedIsWork
    Box {
        Column(modifier = Modifier.padding(16.dp)) {
            // Clears the four 48 dp icons in the top-right corner.
            Text(place.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 184.dp))
            if (place.address.isNotBlank()) {
                Text(place.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onAction(NavigateUiAction.PreviewRoute) },
                    enabled = !quickStarting,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { ButtonContent(R.string.navigate_start, Icons.Filled.Directions) }
                Button(
                    onClick = { onAction(NavigateUiAction.QuickStart) },
                    enabled = !quickStarting,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    if (quickStarting) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.nav_start), maxLines = 1)
                    } else {
                        ButtonContent(R.string.nav_start, Icons.Filled.Navigation)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            PlaceToggle(isHome, Icons.Filled.Home, Icons.Outlined.Home, R.string.navigate_remove_home, R.string.navigate_save_home) {
                onAction(NavigateUiAction.ToggleHome)
            }
            PlaceToggle(isWork, Icons.Filled.Work, Icons.Outlined.WorkOutline, R.string.navigate_remove_work, R.string.navigate_save_work) {
                onAction(NavigateUiAction.ToggleWork)
            }
            PlaceToggle(favorite, Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder, R.string.navigate_unsave, R.string.navigate_save) {
                onAction(NavigateUiAction.ToggleFavorite)
            }
            IconButton(onClick = { onAction(NavigateUiAction.CloseSelected) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.navigate_close))
            }
        }
    }
}

/** Top-right card icon: filled + primary when [checked], outlined otherwise. */
@Composable
private fun PlaceToggle(checked: Boolean, on: ImageVector, off: ImageVector, onLabel: Int, offLabel: Int, onClick: () -> Unit) {
    IconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
        val tint by animateColorAsState(
            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            AppMotion.effects(),
            label = "placeToggleTint",
        )
        Crossfade(targetState = checked, animationSpec = AppMotion.effectsFast(), label = "placeToggle") {
            Icon(if (it) on else off, contentDescription = stringResource(if (it) onLabel else offLabel), tint = tint)
        }
    }
}

@Composable
private fun ButtonContent(label: Int, icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
    Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private val previewPlace = SavedPlace("Chợ Bến Thành", "Lê Lợi, Phường Bến Thành, TP Hồ Chí Minh", GeoPoint(10.7725, 106.698))

@PreviewLightDark
@Composable
private fun NavigateSearchPreview() {
    NmaxConnectTheme {
        NavigateScreen(
            uiState = NavigateUiState(
                query = "197 tran phu",
                locationGranted = true,
                searchExpanded = true,
                suggestions = listOf(
                    PlaceSuggestion("a", "197 Trần Phú", "Phường 4, Quận 5, TP Hồ Chí Minh", distance = 0.07),
                    PlaceSuggestion("b", "197 Trần Phú", "Phường 2, Quận 5, TP Hồ Chí Minh", distance = 1.4),
                ),
            ),
            onAction = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun NavigatePlacePreview() {
    NmaxConnectTheme {
        NavigateScreen(
            uiState = NavigateUiState(locationGranted = true, selected = previewPlace, home = previewPlace, recents = listOf(previewPlace)),
            onAction = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun NavigateQuickStartPreview() {
    NmaxConnectTheme {
        NavigateScreen(uiState = NavigateUiState(locationGranted = true, selected = previewPlace, quickStarting = true), onAction = {})
    }
}

@PreviewLightDark
@Composable
private fun NavigateRoutesPreview() {
    NmaxConnectTheme { NavigateScreen(uiState = NavigateUiState(locationGranted = true, nav = previewRoutes), onAction = {}) }
}

@PreviewLightDark
@Composable
private fun NavigateGuidingPreview() {
    NmaxConnectTheme {
        NavigateScreen(uiState = NavigateUiState(locationGranted = true, nav = previewActive), onAction = {}, dashConnected = true)
    }
}
