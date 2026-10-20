package com.lampp.nmaxconnect.feature.bike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.maintenance.ServiceItem
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.HelpPage
import com.lampp.nmaxconnect.ui.components.HelpTopic
import com.lampp.nmaxconnect.ui.components.Marked
import com.lampp.nmaxconnect.ui.components.TapHint

val bikeHelpTopics = listOf(
    HelpTopic(
        title = R.string.help_bike_odo,
        summary = R.string.help_bike_odo_summary,
        icon = Icons.Filled.Speed,
        pages = listOf(
            HelpPage(R.string.help_bike_odo, listOf(R.string.help_bike_odo_1, R.string.help_bike_odo_2)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Marked(1) { OdometerCard(SAMPLE_ODO) }
                    Marked(2) { OdometerCard(null) }
                }
            },
        ),
    ),
    HelpTopic(
        title = R.string.help_bike_service,
        summary = R.string.help_bike_service_summary,
        icon = Icons.Filled.Build,
        pages = listOf(
            HelpPage(R.string.help_bike_read, listOf(R.string.help_bike_read_1, R.string.help_bike_read_2)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Marked(1) { SampleService(ServiceRow(ServiceItem.ENGINE_OIL, 1_500, kmLeft = 600.0, progress = 0.6f)) }
                    Marked(2) {
                        SampleService(ServiceRow(ServiceItem.CVT_BELT, 20_000, kmLeft = -300.0, overdue = true, progress = 1f))
                    }
                }
            },
            HelpPage(R.string.help_bike_reset, listOf(R.string.help_bike_reset_1, R.string.help_bike_reset_2)) {
                // The row's own reset button is inside ServiceCard, so it's repeated big with the ripple.
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Marked(1) {
                        TapHint { ResetIcon() }
                    }
                    Marked(2) { SampleService(ServiceRow(ServiceItem.ENGINE_OIL, 1_500, kmLeft = 1_500.0, progress = 0f)) }
                }
            },
        ),
    ),
)

private const val SAMPLE_ODO = 12_345.0

@Composable
private fun ResetIcon() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun SampleService(row: ServiceRow) {
    GroupedItems(1) { ServiceCard(row, onClick = {}, onReset = {}) }
}
