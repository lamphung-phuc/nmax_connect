package com.lampp.nmaxconnect.feature.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.ui.components.HelpPage
import com.lampp.nmaxconnect.ui.components.HelpTopic
import com.lampp.nmaxconnect.ui.components.Marked
import com.lampp.nmaxconnect.ui.components.TapHint

val debugHelpTopics = listOf(
    HelpTopic(
        title = R.string.help_debug,
        summary = R.string.help_debug_summary,
        icon = Icons.Filled.BugReport,
        pages = listOf(
            HelpPage(R.string.help_debug_start, listOf(R.string.help_debug_start_1, R.string.help_debug_start_2)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Marked(1) { TapHint { Button(onClick = {}) { Text(stringResource(R.string.nav_start)) } } }
                    Marked(2) { SourcesSample() }
                }
            },
            HelpPage(R.string.help_debug_stats, listOf(R.string.help_debug_stats_1, R.string.help_debug_stats_2)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Marked(1) { SampleText(stringResource(R.string.debug_stats, 1240, 4.8f, 180, 18)) }
                    Marked(2) { SampleText("CONNECTED · HANDSHAKE · NAVI_IMAGE") }
                }
            },
        ),
    ),
)

@Composable
private fun SampleText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SourcesSample() {
    val labels = listOf(R.string.debug_source_off, R.string.debug_source_pattern, R.string.debug_source_vietmap)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == 1,
                onClick = {},
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
            ) { Text(stringResource(label)) }
        }
    }
}
