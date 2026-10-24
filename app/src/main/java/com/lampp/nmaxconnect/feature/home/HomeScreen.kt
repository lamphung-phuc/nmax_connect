package com.lampp.nmaxconnect.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.GroupedRow
import com.lampp.nmaxconnect.ui.components.HelpSection
import com.lampp.nmaxconnect.ui.components.HelpSheet
import com.lampp.nmaxconnect.ui.components.InfoCard
import com.lampp.nmaxconnect.ui.components.LinkStatusChip
import com.lampp.nmaxconnect.ui.components.bikeId
import com.lampp.nmaxconnect.ui.components.bikeLabel
import com.lampp.nmaxconnect.ui.components.rememberLinkPermissionRequest
import com.lampp.nmaxconnect.ui.components.sectionHeader
import com.lampp.nmaxconnect.ui.theme.AppMotion
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme

@Composable
fun HomeRoute(
    onChooseBike: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestPermission = rememberLinkPermissionRequest { granted ->
        if (granted) viewModel.onAction(HomeUiAction.Connect)
    }
    HomeScreen(
        uiState = uiState,
        onAction = { action ->
            when (action) {
                HomeUiAction.Connect -> if (hasBluetoothPermission(context)) viewModel.onAction(action) else requestPermission()
                HomeUiAction.RequestPermission -> requestPermission()
                HomeUiAction.ChooseBike -> onChooseBike()
                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onAction: (HomeUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) HelpSheet(listOf(HelpSection(null, homeHelpTopics)), onDismiss = { showHelp = false })
    AppScaffold(title = stringResource(R.string.app_name), modifier = modifier, onHelp = { showHelp = true }) {
        if (!uiState.loaded) return@AppScaffold
        item(key = "link") { LinkCard(uiState, onAction) }
        if (uiState.link == BikeLinkState.MissingPermission) {
            item(key = "permission") {
                InfoCard(
                    title = stringResource(R.string.home_permission_title),
                    body = stringResource(R.string.home_permission_body),
                    actionLabel = stringResource(R.string.action_grant),
                    onAction = { onAction(HomeUiAction.RequestPermission) },
                    modifier = Modifier.animateItem().padding(top = 16.dp),
                )
            }
        }
        if (uiState.showConflictTip) {
            item(key = "tip") {
                InfoCard(
                    title = stringResource(R.string.home_tip_title),
                    body = stringResource(R.string.home_tip_body),
                    modifier = Modifier.animateItem().padding(top = 16.dp),
                )
            }
        }
        sectionHeader(R.string.vehicle_links)
        item(key = "links") {
            GroupedItems(count = 2, modifier = Modifier.animateItem()) { index ->
                if (index == 0) {
                    GroupedRow(stringResource(R.string.vehicle_link_dash), trailing = { LinkStatusChip(uiState.link) })
                } else {
                    GroupedRow(stringResource(R.string.vehicle_link_ccu), trailing = { LinkStatusChip(uiState.ccuLink) })
                }
            }
        }
    }
}

@Composable
private fun LinkCard(uiState: HomeUiState, onAction: (HomeUiAction) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.animateContentSize(AppMotion.spatial()).padding(16.dp)) {
            LinkStatusChip(uiState.link)
            Spacer(Modifier.height(12.dp))
            Text(
                text = uiState.bike?.let { bikeLabel(it) } ?: stringResource(R.string.home_no_bike),
                style = MaterialTheme.typography.headlineSmall,
            )
            // A nickname replaces the ID above; keep the ID in small print.
            uiState.bike?.takeIf { it.nickname != null }?.let {
                Text(bikeId(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            val action = when {
                uiState.bike == null -> HomeUiAction.ChooseBike
                uiState.link == BikeLinkState.Idle || uiState.link == BikeLinkState.MissingPermission -> HomeUiAction.Connect
                else -> HomeUiAction.Disconnect
            }
            AnimatedContent(targetState = action, transitionSpec = { AppMotion.fadeThrough() }, label = "linkAction") {
                when (it) {
                    HomeUiAction.ChooseBike -> Button(onClick = { onAction(it) }, modifier = buttonModifier) {
                        Text(stringResource(R.string.home_choose_bike))
                    }
                    HomeUiAction.Connect -> Button(onClick = { onAction(it) }, modifier = buttonModifier) {
                        Text(stringResource(R.string.action_connect))
                    }
                    else -> FilledTonalButton(onClick = { onAction(it) }, modifier = buttonModifier) {
                        Text(stringResource(R.string.action_disconnect))
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun HomeConnectedPreview() {
    NmaxConnectTheme {
        HomeScreen(
            uiState = HomeUiState(
                bike = BondedBike("NMAX 155", "00:11:22:33:44:55"),
                loaded = true,
                link = BikeLinkState.Ready(DisplayModel.IMWW23, streaming = true),
            ),
            onAction = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun HomeRetryingPreview() {
    NmaxConnectTheme {
        HomeScreen(
            uiState = HomeUiState(
                bike = BondedBike("NMAX 155", "00:11:22:33:44:55"),
                loaded = true,
                link = BikeLinkState.Retrying(5_000, "TFT did not connect"),
                showConflictTip = true,
            ),
            onAction = {},
        )
    }
}
