package com.lampp.nmaxconnect.feature.vehicle

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.map.DashGuidance
import com.lampp.nmaxconnect.core.map.DashMockup
import com.lampp.nmaxconnect.core.protocol.ccu.LocalRecord
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.core.settings.DashStat
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.GroupedRow
import com.lampp.nmaxconnect.ui.theme.AppMotion

/** What the map stream shows: a preview of the dash, then the turn / stats switches. */
internal fun LazyListScope.dashStreamSection(uiState: VehicleUiState, onAction: (VehicleUiAction) -> Unit) {
    if (!uiState.dashLoaded) return
    item(key = "dashPreview") {
        DashPreview(
            display = (uiState.tftLink as? BikeLinkState.Ready)?.display ?: DisplayModel.IMWW23,
            turns = uiState.dashTurns,
            stats = uiState.dashStats,
            items = uiState.dashStatItems,
        )
    }
    item(key = "dashSwitches") {
        val statRows = if (uiState.dashStats) DashStat.entries else emptyList()
        GroupedItems(count = 2 + statRows.size, modifier = Modifier.padding(top = 8.dp)) { index ->
            when (index) {
                0 -> DashSwitchRow(
                    stringResource(R.string.home_dash_turns),
                    stringResource(R.string.home_dash_turns_body),
                    Icons.Filled.TurnRight,
                    uiState.dashTurns,
                ) { onAction(VehicleUiAction.SetDashTurns(it)) }
                1 -> DashSwitchRow(
                    stringResource(R.string.home_dash_stats),
                    stringResource(R.string.home_dash_stats_body),
                    Icons.Filled.Insights,
                    uiState.dashStats,
                ) { onAction(VehicleUiAction.SetDashStats(it)) }
                else -> {
                    val stat = statRows[index - 2]
                    DashSwitchRow(
                        stringResource(stat.label),
                        null,
                        stat.icon,
                        stat in uiState.dashStatItems,
                        Modifier.padding(start = 16.dp),
                    ) { onAction(VehicleUiAction.SetDashStat(stat, it)) }
                }
            }
        }
    }
}

@Composable
private fun DashSwitchRow(
    headline: String,
    supporting: String?,
    icon: ImageVector,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    GroupedRow(
        headline = headline,
        supporting = supporting,
        leading = { Icon(icon, contentDescription = null) },
        trailing = { Switch(checked = checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
        modifier = modifier,
    )
}

private val DashStat.label
    get() = when (this) {
        DashStat.AIR -> R.string.vehicle_air
        DashStat.COOLANT -> R.string.vehicle_coolant
        DashStat.FUEL -> R.string.vehicle_fuel_economy
        DashStat.BATTERY -> R.string.vehicle_battery
    }

private val DashStat.icon
    get() = when (this) {
        DashStat.AIR -> Icons.Filled.Air
        DashStat.COOLANT -> Icons.Filled.Thermostat
        DashStat.FUEL -> Icons.Filled.LocalGasStation
        DashStat.BATTERY -> Icons.Filled.BatteryFull
    }

/** The whole dash as the rider will see it while guiding, with sample values; the frame part is the streamer's own [DashMockup]/DashHud drawing. */
@Composable
internal fun DashPreview(display: DisplayModel, turns: Boolean, stats: Boolean, items: Set<DashStat>) {
    val image = remember(display, turns, stats, items) {
        val shownStats = PREVIEW_STATS.takeIf { stats && items.isNotEmpty() }
        DashMockup.render(display, turns, shownStats, items, PREVIEW_TURN, PREVIEW_ETA, PREVIEW_ROAD)
    }
    Crossfade(targetState = image, animationSpec = AppMotion.effects(), label = "dashPreview") {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = stringResource(R.string.home_dash_preview),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(MaterialTheme.shapes.large)
                .aspectRatio(it.width.toFloat() / it.height),
        )
    }
}

// Sample guidance in the dash's ASCII style (it strips diacritics).
private val PREVIEW_TURN = DashGuidance(icon = 34, distance = "80 m", street = "Duy Tan")
private const val PREVIEW_ETA = "7:56"
private const val PREVIEW_ROAD = "17 Duy Tan"
private val PREVIEW_STATS = LocalRecord(batteryVolts = 13.8f, coolantCelsius = 88, airCelsius = 31, fuelKmPerL = 42.5f)
