package com.lampp.nmaxconnect.feature.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.ui.components.AppScaffold
import com.lampp.nmaxconnect.ui.components.HelpSection
import com.lampp.nmaxconnect.ui.components.HelpSheet
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme

@Composable
fun VehicleRoute(modifier: Modifier = Modifier, viewModel: VehicleViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    VehicleScreen(uiState, viewModel::onAction, modifier)
}

@Composable
fun VehicleScreen(uiState: VehicleUiState, onAction: (VehicleUiAction) -> Unit, modifier: Modifier = Modifier) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) HelpSheet(listOf(HelpSection(null, dashHelpTopics)), onDismiss = { showHelp = false })
    AppScaffold(title = stringResource(R.string.tab_vehicle), modifier = modifier, onHelp = { showHelp = true }) {
        dashStreamSection(uiState, onAction)
    }
}

@PreviewLightDark
@Composable
private fun VehicleScreenPreview() {
    NmaxConnectTheme {
        VehicleScreen(
            VehicleUiState(tftLink = BikeLinkState.Ready(DisplayModel.IMWW23, streaming = true), dashStats = true, dashLoaded = true),
            onAction = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun VehicleScreenDisconnectedPreview() {
    NmaxConnectTheme { VehicleScreen(VehicleUiState(dashLoaded = true), onAction = {}) }
}
