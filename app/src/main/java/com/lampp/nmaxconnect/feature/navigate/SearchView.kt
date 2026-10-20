package com.lampp.nmaxconnect.feature.navigate

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.places.SavedPlace
import com.lampp.nmaxconnect.core.vietmap.PlaceSuggestion
import com.lampp.nmaxconnect.ui.components.EmptyState
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.GroupedRow
import com.lampp.nmaxconnect.ui.components.InfoCard
import com.lampp.nmaxconnect.ui.components.sectionHeader
import com.lampp.nmaxconnect.ui.theme.AppMotion
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme
import kotlin.math.roundToInt

/**
 * Pill search field (no voice input). Collapsed it sits in the header and opens the search on focus;
 * expanded it heads [SearchView] with a back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchField(
    uiState: NavigateUiState,
    onAction: (NavigateUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = uiState.searchExpanded
    val onExpandedChange = { open: Boolean -> onAction(NavigateUiAction.SearchExpanded(open)) }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceBright, modifier = modifier.fillMaxWidth()) {
        SearchBarDefaults.InputField(
            query = uiState.query,
            onQueryChange = { onAction(NavigateUiAction.QueryChanged(it)) },
            onSearch = { },
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            enabled = uiState.searchAvailable,
            placeholder = {
                val hint = when (uiState.searchTarget) {
                    SearchTarget.PLACE -> R.string.navigate_search_hint
                    SearchTarget.ORIGIN -> R.string.navigate_from_hint
                    SearchTarget.DESTINATION -> R.string.navigate_to_hint
                }
                Text(stringResource(hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = {
                if (expanded) {
                    IconButton(onClick = { onExpandedChange(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                val trailing = when {
                    uiState.searching -> Trailing.PROGRESS
                    uiState.query.isNotEmpty() -> Trailing.CLEAR
                    else -> Trailing.NONE
                }
                AnimatedContent(targetState = trailing, transitionSpec = { AppMotion.fadeThrough() }, label = "searchTrailing") {
                    when (it) {
                        Trailing.PROGRESS -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        Trailing.CLEAR -> IconButton(onClick = { onAction(NavigateUiAction.ClearQuery) }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.navigate_clear))
                        }
                        Trailing.NONE -> Unit
                    }
                }
            },
            // Transparent so the pill's surfaceBright shows (the field paints its own container otherwise).
            colors = SearchBarDefaults.inputFieldColors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private enum class Trailing { PROGRESS, CLEAR, NONE }

/**
 * Full-screen search in the app's Pixel style: field, quick actions ("Your location" when choosing From,
 * "Choose on map" for From/To) as a grouped list, Saved (Home / Work / favorites) as one tonal island
 * scrolling sideways, then Recent as a grouped list. Live suggestions replace it all from 2 characters.
 */
@Composable
fun SearchView(uiState: NavigateUiState, onAction: (NavigateUiAction) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } } // keyboard up as soon as it opens
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        SearchField(uiState, onAction, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus))
        if (uiState.resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize().imePadding(),
        ) {
            if (uiState.query.trim().length >= NavigateViewModel.MIN_CHARS) suggestions(uiState, onAction) else shortcuts(uiState, onAction)
        }
    }
}

private fun LazyListScope.shortcuts(uiState: NavigateUiState, onAction: (NavigateUiAction) -> Unit) {
    val quick = buildList {
        if (uiState.searchTarget == SearchTarget.ORIGIN && uiState.locationGranted) {
            add(Triple(R.string.navigate_your_location, Icons.Filled.MyLocation, NavigateUiAction.UseMyLocation))
        }
        if (uiState.searchTarget != SearchTarget.PLACE) {
            add(Triple(R.string.navigate_choose_on_map, Icons.Filled.Map, NavigateUiAction.ChooseOnMap))
        }
    }
    if (quick.isNotEmpty()) {
        item(key = "quick") {
            GroupedItems(count = quick.size, modifier = Modifier.animateItem()) { i ->
                val (label, icon, action) = quick[i]
                GroupedRow(
                    headline = stringResource(label),
                    leading = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onAction(action) },
                )
            }
        }
    }
    sectionHeader(R.string.navigate_section_saved)
    item(key = "saved") { SavedStrip(uiState, onAction, Modifier.animateItem()) }
    if (uiState.recents.isEmpty()) {
        item(key = "empty") {
            EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.navigate_empty_title),
                body = stringResource(R.string.navigate_empty_body),
                modifier = Modifier.animateItem(),
            )
        }
    } else {
        sectionHeader(R.string.navigate_section_recent)
        item(key = "recents") {
            GroupedItems(count = uiState.recents.size, modifier = Modifier.animateItem()) { i ->
                val place = uiState.recents[i]
                GroupedRow(
                    headline = place.name,
                    supporting = place.address.takeIf { it.isNotBlank() },
                    leading = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                    onClick = { onAction(NavigateUiAction.PickSaved(place)) },
                )
            }
        }
    }
}

private fun LazyListScope.suggestions(uiState: NavigateUiState, onAction: (NavigateUiAction) -> Unit) {
    when {
        uiState.problem == SearchProblem.NETWORK -> item(key = "error") {
            InfoCard(
                title = stringResource(R.string.navigate_error_title),
                body = stringResource(R.string.navigate_error_body),
                modifier = Modifier.animateItem(),
            )
        }
        uiState.problem == SearchProblem.NO_RESULTS -> item(key = "none") {
            Text(
                stringResource(R.string.navigate_no_results),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.animateItem().padding(16.dp),
            )
        }
        uiState.suggestions.isNotEmpty() -> item(key = "suggestions") {
            GroupedItems(count = uiState.suggestions.size, modifier = Modifier.animateItem()) { i ->
                val s = uiState.suggestions[i]
                GroupedRow(
                    headline = s.name.ifBlank { s.display },
                    supporting = s.address.takeIf { it.isNotBlank() },
                    leading = { Icon(Icons.Filled.Place, contentDescription = null) },
                    // VietMap reports 0 when no focus was sent, so 0 means "unknown".
                    trailing = s.distance?.takeIf { it > 0 }?.let { km ->
                        { Text(distanceLabel(km), style = MaterialTheme.typography.labelLarge) }
                    },
                    enabled = !uiState.resolving,
                    onClick = { onAction(NavigateUiAction.PickSuggestion(s)) },
                )
            }
        }
    }
}

/** Autocomplete distance is in km; under 1 km read it in metres (rounded to 10 m). */
@Composable
private fun distanceLabel(km: Double): String =
    if (km < 1) stringResource(R.string.navigate_distance_m, (km * 100).roundToInt() * 10)
    else stringResource(R.string.navigate_distance_km, km)

/**
 * Home / Work ("Not set" until saved) and favorites in one tonal island (`surfaceContainerLow`, 24 dp),
 * scrolling sideways; each shortcut has its icon on a `secondaryContainer` circle.
 */
@Composable
private fun SavedStrip(uiState: NavigateUiState, onAction: (NavigateUiAction) -> Unit, modifier: Modifier = Modifier) {
    val notSet = stringResource(R.string.navigate_not_set)
    val items = listOf(
        Triple(Icons.Filled.Home, stringResource(R.string.navigate_home), uiState.home),
        Triple(Icons.Filled.Work, stringResource(R.string.navigate_work), uiState.work),
    ) + uiState.favorites.map { Triple(Icons.Filled.Bookmark, it.name, it) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
        ) {
            items.forEach { (icon, title, place) ->
                val alpha = if (place != null) 1f else 0.38f
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(enabled = place != null) { place?.let { onAction(NavigateUiAction.PickSaved(it)) } }
                        .padding(8.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Column(Modifier.widthIn(max = 160.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Home/Work show the saved place; a favorite's title is already its name.
                        val subtitle = if (icon == Icons.Filled.Bookmark) place?.address else place?.name ?: notSet
                        subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val previewHome = SavedPlace("56 Phố Đào Tấn", "Phường Cống Vị, Quận Ba Đình, Hà Nội", GeoPoint(21.0337, 105.8066))
private val previewWork = SavedPlace("17 Phố Dịch Vọng Hậu", "Phố Duy Tân, Cầu Giấy, Hà Nội", GeoPoint(21.0306, 105.7832))

@PreviewLightDark
@Composable
private fun ChooseStartPreview() {
    NmaxConnectTheme {
        SearchView(
            uiState = NavigateUiState(
                locationGranted = true,
                searchExpanded = true,
                searchTarget = SearchTarget.ORIGIN,
                home = previewHome,
                work = previewWork,
                recents = listOf(previewWork, previewHome),
            ),
            onAction = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun SuggestionsPreview() {
    NmaxConnectTheme {
        SearchView(
            uiState = NavigateUiState(
                query = "197 tran phu",
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
