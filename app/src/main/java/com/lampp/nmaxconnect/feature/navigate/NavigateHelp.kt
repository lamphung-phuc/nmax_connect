package com.lampp.nmaxconnect.feature.navigate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.HelpMarker
import com.lampp.nmaxconnect.ui.components.HelpPage
import com.lampp.nmaxconnect.ui.components.HelpTopic
import com.lampp.nmaxconnect.ui.components.Marked
import com.lampp.nmaxconnect.ui.components.TapHint

val navigateHelpTopics = listOf(
    HelpTopic(
        title = R.string.help_nav_find,
        summary = R.string.help_nav_find_summary,
        icon = Icons.Filled.Search,
        pages = listOf(
            HelpPage(R.string.help_nav_search, listOf(R.string.help_nav_search_1, R.string.help_nav_search_2)) { SearchSample() },
            HelpPage(R.string.help_nav_pin, listOf(R.string.help_nav_pin_1, R.string.help_nav_pin_2)) { PinSample() },
        ),
    ),
    HelpTopic(
        title = R.string.help_nav_route,
        summary = R.string.help_nav_route_summary,
        icon = Icons.Filled.Route,
        pages = listOf(
            HelpPage(R.string.help_nav_options, listOf(R.string.help_nav_options_1, R.string.help_nav_options_2)) { RoutesSample() },
            HelpPage(
                R.string.help_nav_ends,
                listOf(R.string.help_nav_ends_2, R.string.help_nav_ends_3),
            ) { EndsSample() },
        ),
    ),
    HelpTopic(
        title = R.string.help_nav_riding,
        summary = R.string.help_nav_riding_summary,
        icon = Icons.Filled.Navigation,
        pages = listOf(
            HelpPage(R.string.help_nav_turns, listOf(R.string.help_nav_turns_1, R.string.help_nav_turns_2)) { BannerSample() },
            HelpPage(R.string.help_nav_stop, listOf(R.string.help_nav_stop_1, R.string.help_nav_stop_2)) { TripSample() },
        ),
    ),
)

// Illustrations: look-alikes of the real header/panels with sample values, not interactive.
// Each marker number matches the caption with the same number.

@Composable
private fun SearchSample() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Marked(1) {
            TapHint {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text(stringResource(R.string.navigate_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // The chips float on the map, so they sit on a map-coloured strip here.
        MapStrip {
            Marked(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
                    SampleChip(R.string.navigate_home, Icons.Filled.Home)
                    SampleChip(R.string.navigate_work, Icons.Filled.Work)
                }
            }
        }
    }
}

@Composable
private fun SampleChip(label: Int, icon: ImageVector) {
    AssistChip(
        onClick = {},
        label = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = null,
    )
}

/** Stand-in for the map: a tonal block the samples sit on. */
@Composable
private fun MapStrip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) { Box(contentAlignment = Alignment.CenterStart) { content() } }
}

@Composable
private fun PinSample() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MapStrip(Modifier.weight(1f).height(120.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Marked(1) {
                    TapHint {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
            }
        }
        Marked(2) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RoundIcon(Icons.Filled.Map)
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                RoundIcon(Icons.Filled.TwoWheeler)
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, container: Color = MaterialTheme.colorScheme.primaryContainer) {
    Surface(shape = CircleShape, color = container, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun RoutesSample() {
    // GroupedItems clips its rows, so the ring goes around the whole list.
    TapHint { RouteList() }
}

@Composable
private fun RouteList() {
    GroupedItems(count = 2) { index ->
        if (index == 0) {
            RouteRow(stringResource(R.string.nav_minutes, 18), marker = 1) {
                Text(stringResource(R.string.nav_fastest), color = MaterialTheme.colorScheme.tertiary)
            }
        } else {
            RouteRow(stringResource(R.string.nav_minutes, 22), marker = 2) {
                Text(stringResource(R.string.nav_traffic_moderate), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Route option look-alike: time left, coloured hint and its marker right. */
@Composable
private fun RouteRow(headline: String, marker: Int, supporting: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        supporting()
        HelpMarker(marker)
    }
}

@Composable
private fun EndsSample() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Marked(2) { EndBox(Icons.Filled.MyLocation, stringResource(R.string.navigate_your_location)) }
            EndBox(Icons.Filled.Place, stringResource(R.string.navigate_home))
        }
        Marked(1) { TapHint { RoundIcon(Icons.Filled.SwapVert) } }
    }
}

@Composable
private fun EndBox(icon: ImageVector, text: String) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(text)
        }
    }
}

@Composable
private fun BannerSample() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Marked(1) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(Icons.Filled.TurnRight, contentDescription = null, modifier = Modifier.size(48.dp))
                    Text("200 m", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        // Phone → dash: the map leaves the phone while guiding.
        Marked(2) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIcon(Icons.Filled.Map, MaterialTheme.colorScheme.surfaceContainerHighest)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                RoundIcon(Icons.Filled.Cast)
            }
        }
    }
}

@Composable
private fun TripSample() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The trip panel as a card of its own, so it stands off the illustration panel.
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.nav_minutes, 12),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                Marked(1) {
                    TapHint {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
        Marked(2) { RoundIcon(Icons.AutoMirrored.Filled.AltRoute) }
    }
}
