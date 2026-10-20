package com.lampp.nmaxconnect.feature.debug

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.bike.BondedBike
import com.lampp.nmaxconnect.core.bike.hasBluetoothPermission
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.ui.components.AppScaffold
import com.lampp.nmaxconnect.ui.components.HelpSection
import com.lampp.nmaxconnect.ui.components.HelpSheet
import com.lampp.nmaxconnect.ui.components.rememberLinkPermissionRequest
import com.lampp.nmaxconnect.ui.components.sectionHeader
import com.lampp.nmaxconnect.ui.theme.AppMotion
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme

@Composable
fun DebugLinkRoute(
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DebugLinkViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestPermissions = rememberLinkPermissionRequest { granted ->
        viewModel.onAction(DebugLinkUiAction.PermissionsResult(granted))
    }
    LaunchedEffect(Unit) { viewModel.onAction(DebugLinkUiAction.PermissionsResult(hasBluetoothPermission(context))) }
    DebugLinkScreen(
        uiState = uiState,
        onAction = { action ->
            if (action == DebugLinkUiAction.RequestPermissions) requestPermissions() else viewModel.onAction(action)
        },
        navigateUp = navigateUp,
        modifier = modifier,
    )
}

@Composable
fun DebugLinkScreen(
    uiState: DebugLinkUiState,
    onAction: (DebugLinkUiAction) -> Unit,
    modifier: Modifier = Modifier,
    navigateUp: (() -> Unit)? = null,
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) HelpSheet(listOf(HelpSection(null, debugHelpTopics)), onDismiss = { showHelp = false })
    AppScaffold(
        title = stringResource(R.string.debug_title),
        navigateUp = navigateUp,
        modifier = modifier,
        onHelp = { showHelp = true },
    ) {
        if (!uiState.permissionsGranted) permissionsSection(onAction)
        bikeSection(uiState, onAction)
        linkSection(uiState, onAction)
        streamSection(uiState, onAction)
        eventsSection(uiState.log)
    }
}

private fun LazyListScope.permissionsSection(onAction: (DebugLinkUiAction) -> Unit) {
    sectionHeader(R.string.debug_section_permissions)
    item {
        TonalGroup {
            ListItem(
                headlineContent = { Text(stringResource(R.string.debug_permissions_needed)) },
                trailingContent = {
                    Button(onClick = { onAction(DebugLinkUiAction.RequestPermissions) }) {
                        Text(stringResource(R.string.debug_grant))
                    }
                },
                colors = transparentListItem(),
            )
        }
    }
}

private fun LazyListScope.bikeSection(uiState: DebugLinkUiState, onAction: (DebugLinkUiAction) -> Unit) {
    sectionHeader(R.string.debug_section_bike)
    item {
        TonalGroup {
            if (uiState.bondedBikes.isEmpty()) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.debug_no_bikes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = transparentListItem(),
                )
            }
            uiState.bondedBikes.forEach { bike ->
                val selected = bike.mac == uiState.selectedMac
                ListItem(
                    headlineContent = { Text(bike.name) },
                    supportingContent = { Text(bike.mac) },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    colors = transparentListItem(),
                    modifier = Modifier.selectable(selected, role = Role.RadioButton) {
                        onAction(DebugLinkUiAction.SelectBike(bike.mac))
                    },
                )
            }
        }
    }
}

private fun LazyListScope.linkSection(uiState: DebugLinkUiState, onAction: (DebugLinkUiAction) -> Unit) {
    sectionHeader(R.string.debug_section_link)
    item {
        TonalGroup {
            ListItem(
                headlineContent = { Text(linkText(uiState.link), style = MaterialTheme.typography.titleMedium) },
                colors = transparentListItem(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                val running = uiState.link != BikeLinkState.Idle && uiState.link != BikeLinkState.MissingPermission
                Button(
                    onClick = { onAction(DebugLinkUiAction.StartLink) },
                    enabled = !running && uiState.permissionsGranted && uiState.selectedMac != null,
                ) { Text(stringResource(R.string.debug_start)) }
                OutlinedButton(onClick = { onAction(DebugLinkUiAction.StopLink) }, enabled = running) {
                    Text(stringResource(R.string.debug_stop))
                }
            }
        }
    }
}

private fun LazyListScope.streamSection(uiState: DebugLinkUiState, onAction: (DebugLinkUiAction) -> Unit) {
    sectionHeader(R.string.debug_section_stream)
    item {
        TonalGroup {
            val sources = listOf(
                StreamSource.OFF to R.string.debug_source_off,
                StreamSource.TEST_PATTERN to R.string.debug_source_pattern,
                StreamSource.VIETMAP to R.string.debug_source_vietmap,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                sources.forEachIndexed { index, (source, label) ->
                    SegmentedButton(
                        selected = uiState.source == source,
                        onClick = { onAction(DebugLinkUiAction.SelectSource(source)) },
                        enabled = source != StreamSource.VIETMAP || uiState.vietMapAvailable,
                        shape = SegmentedButtonDefaults.itemShape(index, sources.size),
                    ) { Text(stringResource(label)) }
                }
            }
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(if (uiState.vietMapAvailable) R.string.debug_stream_hint else R.string.debug_no_tile_key),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = transparentListItem(),
            )
            uiState.preview?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = stringResource(R.string.debug_preview_cd),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .aspectRatio(frame.width.toFloat() / frame.height)
                        .clip(MaterialTheme.shapes.medium),
                )
            }
            val s = uiState.stats
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.debug_stats, s.framesSent, s.fps, s.lastAckMs, s.lastFrameBytes / 1024),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = transparentListItem(),
            )
        }
    }
}

private fun LazyListScope.eventsSection(log: List<String>) {
    sectionHeader(R.string.debug_section_events)
    items(log) { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun TonalGroup(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize(AppMotion.spatial())) { content() }
    }
}

@Composable
private fun transparentListItem() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
private fun linkText(link: BikeLinkState): String = when (link) {
    BikeLinkState.Idle -> stringResource(R.string.debug_state_idle)
    is BikeLinkState.Connecting -> stringResource(R.string.debug_state_connecting, link.attempt)
    BikeLinkState.Handshaking -> stringResource(R.string.debug_state_handshaking)
    is BikeLinkState.Ready -> stringResource(
        if (link.streaming) R.string.debug_state_streaming else R.string.debug_state_ready,
        link.display.width,
        link.display.height,
    )
    is BikeLinkState.Retrying -> stringResource(R.string.debug_state_retrying, link.inMs / 1000, link.reason)
    BikeLinkState.MissingPermission -> stringResource(R.string.debug_state_missing_permission)
}

@PreviewLightDark
@Composable
private fun DebugLinkScreenPreview() {
    NmaxConnectTheme {
        DebugLinkScreen(
            uiState = DebugLinkUiState(
                permissionsGranted = true,
                bondedBikes = listOf(BondedBike("NMAX 155", "00:11:22:33:44:55"), BondedBike("Pixel Buds", "AA:BB:CC:DD:EE:FF")),
                selectedMac = "00:11:22:33:44:55",
                link = BikeLinkState.Ready(DisplayModel.IMWW23, streaming = true),
                source = StreamSource.VIETMAP,
                vietMapAvailable = true,
                stats = StreamStats(framesSent = 412, lastAckMs = 96, fps = 9f, lastFrameBytes = 18_432),
                log = listOf(
                    "21:04:12 ← StartContent(content=NAVI_IMAGE)",
                    "21:04:11 link: Ready",
                    "21:04:11 ← AuthAck(ok=true)",
                    "21:04:10 ← SecData part=006-B4160 key=8B display=480x240",
                    "21:04:10 ← Esn(esn=1234567890)",
                ),
            ),
            onAction = {},
        )
    }
}
