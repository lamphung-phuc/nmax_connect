package com.lampp.nmaxconnect.feature.vehicle

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.settings.AppSettingsRepository
import com.lampp.nmaxconnect.core.settings.DashStat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class VehicleUiState(
    /** The dash's model sizes the preview. */
    val tftLink: BikeLinkState = BikeLinkState.Idle,
    val dashTurns: Boolean = true,
    val dashStats: Boolean = false,
    val dashStatItems: Set<DashStat> = DashStat.entries.toSet(),
    /** False until the saved dash switches are read, so they don't flash their defaults. */
    val dashLoaded: Boolean = false,
)

sealed interface VehicleUiAction {
    data class SetDashTurns(val on: Boolean) : VehicleUiAction
    data class SetDashStats(val on: Boolean) : VehicleUiAction
    data class SetDashStat(val stat: DashStat, val on: Boolean) : VehicleUiAction
}

@HiltViewModel
class VehicleViewModel @Inject constructor(
    linkRepository: BikeLinkRepository,
    private val settingsRepository: AppSettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<VehicleUiState> = combine(linkRepository.state, settingsRepository.settings) { tft, settings ->
        VehicleUiState(
            tftLink = tft,
            dashTurns = settings.showTurns,
            dashStats = settings.dashStats,
            dashStatItems = settings.dashStatItems,
            dashLoaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleUiState())

    fun onAction(action: VehicleUiAction) {
        viewModelScope.launch {
            when (action) {
                is VehicleUiAction.SetDashTurns -> settingsRepository.setDashTurns(action.on)
                is VehicleUiAction.SetDashStats -> settingsRepository.setDashStats(action.on)
                is VehicleUiAction.SetDashStat -> settingsRepository.setDashStat(action.stat, action.on)
            }
        }
    }
}
