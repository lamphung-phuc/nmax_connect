package com.lampp.nmaxconnect.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.GroupedRow
import com.lampp.nmaxconnect.ui.components.HelpMarker
import com.lampp.nmaxconnect.ui.components.HelpPage
import com.lampp.nmaxconnect.ui.components.HelpTopic
import com.lampp.nmaxconnect.ui.components.LinkStatusChip
import com.lampp.nmaxconnect.ui.components.Marked
import com.lampp.nmaxconnect.ui.components.TapHint
import kotlinx.coroutines.delay

val homeHelpTopics = listOf(
    HelpTopic(
        title = R.string.help_home_connect,
        summary = R.string.help_home_connect_summary,
        icon = Icons.Filled.BluetoothConnected,
        pages = listOf(
            HelpPage(R.string.help_home_connect, listOf(R.string.help_home_connect_1, R.string.help_home_connect_2)) {
                ConnectSteps()
            },
            HelpPage(R.string.help_home_background, listOf(R.string.help_home_background_1, R.string.help_home_background_2)) {
                BackgroundSample()
            },
        ),
    ),
    HelpTopic(
        title = R.string.help_home_failing,
        summary = R.string.help_home_failing_summary,
        icon = Icons.Filled.SyncProblem,
        pages = listOf(
            HelpPage(R.string.help_home_other_apps, listOf(R.string.help_home_other_apps_1, R.string.help_home_other_apps_2)) {
                OtherAppsSample()
            },
            HelpPage(R.string.help_home_pairing, listOf(R.string.help_home_pairing_1, R.string.help_home_pairing_2)) {
                PairingSample()
            },
        ),
    ),
    HelpTopic(
        title = R.string.help_home_links,
        summary = R.string.help_home_links_summary,
        icon = Icons.Filled.TwoWheeler,
        pages = listOf(
            HelpPage(R.string.vehicle_link_dash, listOf(R.string.help_home_links_dash_1)) {
                Marked(1) { Flow(Icons.Filled.PhoneAndroid, Icons.Filled.Map, Icons.Filled.TurnRight, Icons.Filled.Schedule) }
            },
            HelpPage(R.string.vehicle_link_ccu, listOf(R.string.help_home_links_ccu_1, R.string.help_home_links_ccu_2)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Marked(1) { Flow(Icons.Filled.TwoWheeler, Icons.Filled.Speed, Icons.Filled.Battery5Bar, Icons.Filled.Thermostat) }
                    Marked(2) {
                        Flow(
                            Icons.Filled.PhoneAndroid,
                            Icons.Filled.Call,
                            Icons.Filled.MusicNote,
                            Icons.Filled.Notifications,
                            Icons.Filled.WbSunny,
                        )
                    }
                }
            },
        ),
    ),
)

/** Connect gets tapped, then the chip plays through a connection; pauses on Showing map and starts over. */
@Composable
private fun ConnectSteps() {
    val steps = remember {
        listOf(
            BikeLinkState.Idle,
            BikeLinkState.Connecting(1),
            BikeLinkState.Handshaking,
            BikeLinkState.Ready(DisplayModel.IMWW23, streaming = false),
            BikeLinkState.Ready(DisplayModel.IMWW23, streaming = true),
        )
    }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(if (step == steps.lastIndex) 2_400 else 900)
            step = (step + 1) % steps.size
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Marked(1) { TapHint { Button(onClick = {}) { Text(stringResource(R.string.action_connect)) } } }
        Marked(2) { LinkStatusChip(steps[step]) }
    }
}

/** The app's notification with its Disconnect action, then the Auto connect switch. */
@Composable
private fun BackgroundSample() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.TwoWheeler, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge)
                }
                Marked(1) {
                    Text(
                        stringResource(R.string.action_disconnect),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        GroupedItems(count = 1) {
            GroupedRow(
                stringResource(R.string.settings_auto_connect),
                leading = { Icon(Icons.Filled.Autorenew, contentDescription = null) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = true, onCheckedChange = null)
                        HelpMarker(2)
                    }
                },
            )
        }
    }
}

/** Other bike apps crossed out (only one app can hold the link), then the self-retrying chip. */
@Composable
private fun OtherAppsSample() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Marked(1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Y-Connect", "StreetCross").forEach { name ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            name,
                            textDecoration = TextDecoration.LineThrough,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        Marked(2) { LinkStatusChip(BikeLinkState.Retrying(5_000, "")) }
    }
}

/** Bike ⇄ phone over Bluetooth, then the bike row in Settings where it can be changed. */
@Composable
private fun PairingSample() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Marked(1) { Flow(Icons.Filled.TwoWheeler, Icons.Filled.Bluetooth, Icons.Filled.PhoneAndroid, arrow = false) }
        GroupedItems(count = 1) {
            GroupedRow(
                "NMAX 155",
                supporting = stringResource(R.string.settings_bike_change),
                leading = { Icon(Icons.Filled.TwoWheeler, contentDescription = null) },
                trailing = { HelpMarker(2) },
            )
        }
    }
}

/** Source → what it carries: a filled circle, an arrow, then tonal circles. */
@Composable
private fun Flow(source: ImageVector, vararg carried: ImageVector, arrow: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Circle(source, filled = true)
        if (arrow) Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        carried.forEach { Circle(it, filled = false) }
    }
}

@Composable
private fun Circle(icon: ImageVector, filled: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(40.dp),
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) } }
}
