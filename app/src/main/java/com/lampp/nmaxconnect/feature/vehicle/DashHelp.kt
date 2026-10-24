package com.lampp.nmaxconnect.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.map.DashHud
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.core.settings.DashStat
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.GroupedRow
import com.lampp.nmaxconnect.ui.components.HelpMarker
import com.lampp.nmaxconnect.ui.components.HelpPage
import com.lampp.nmaxconnect.ui.components.HelpTopic
import com.lampp.nmaxconnect.ui.components.Marked
import com.lampp.nmaxconnect.ui.components.TapHint

val dashHelpTopics = listOf(
    HelpTopic(
        title = R.string.help_dash_layout,
        summary = R.string.help_dash_layout_summary,
        icon = Icons.Filled.Insights,
        pages = listOf(
            HelpPage(R.string.home_dash_turns, listOf(R.string.help_dash_turns_1)) {
                MarkedPreview(turns = true, stats = false, marker = 1, at = TURN_COLUMN)
            },
            HelpPage(R.string.home_dash_stats, listOf(R.string.help_dash_stats_1, R.string.help_dash_stats_2)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MarkedPreview(turns = true, stats = true, marker = 1, at = STATS)
                    Marked(2) { MissingStat() }
                }
            },
        ),
    ),
    HelpTopic(
        title = R.string.help_dash_buttons,
        summary = R.string.help_dash_buttons_summary,
        icon = Icons.Filled.Menu,
        pages = listOf(
            HelpPage(R.string.help_dash_zoom, listOf(R.string.help_dash_zoom_1, R.string.help_dash_zoom_2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Marked(1) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TapHint { RoundIcon(Icons.Filled.Add) }
                            RoundIcon(Icons.Filled.Remove)
                        }
                    }
                    Marked(2) { RoundIcon(Icons.Filled.Speed) }
                }
            },
            HelpPage(R.string.help_dash_menu, listOf(R.string.help_dash_menu_1, R.string.help_dash_menu_2)) { MenuSample() },
        ),
    ),
)

// Spots in DashMockup's layout: the turn arrow at the left edge (marker half outside), and right of the stats list,
// under the zoom buttons, so it doesn't cover the stat icons.
private val TURN_COLUMN = BiasAlignment(-1f, -0.45f)
private val STATS = BiasAlignment(-0.3f, 0.6f)

/** The tab's own preview render with one marker on the area the caption names. */
@Composable
private fun MarkedPreview(turns: Boolean, stats: Boolean, marker: Int, at: Alignment) {
    Box {
        DashPreview(DisplayModel.IMWW23, turns, stats, DashStat.entries.toSet())
        HelpMarker(marker, Modifier.align(at).offset(x = if (at == TURN_COLUMN) (-8).dp else 0.dp))
    }
}

/** A stat as the dash shows it while the control unit is down. */
@Composable
private fun MissingStat() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Same colour the dash draws the engine temperature icon in.
            Icon(
                Icons.Filled.Thermostat,
                contentDescription = null,
                tint = Color(DashHud.statIconColor(DashStat.COOLANT)),
                modifier = Modifier.size(20.dp),
            )
            Text("-- °C", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun MenuSample() {
    val entries = listOf(
        R.string.navigate_home to Icons.Filled.Home,
        R.string.navigate_work to Icons.Filled.Work,
        R.string.help_dash_favourites to Icons.Filled.Star,
        R.string.help_dash_gas to Icons.Filled.LocalGasStation,
    )
    GroupedItems(count = entries.size) { index ->
        val (label, icon) = entries[index]
        val marker = when (index) {
            0 -> 1
            entries.lastIndex -> 2
            else -> null
        }
        GroupedRow(
            stringResource(label),
            leading = { Icon(icon, contentDescription = null) },
            trailing = marker?.let { { HelpMarker(it) } },
        )
    }
}
