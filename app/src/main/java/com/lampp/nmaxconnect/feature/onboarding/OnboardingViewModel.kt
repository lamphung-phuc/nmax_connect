package com.lampp.nmaxconnect.feature.onboarding

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.bike.BondedBike
import com.lampp.nmaxconnect.core.settings.AppSettingsRepository
import com.lampp.nmaxconnect.ui.components.AppPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { WELCOME, PERMISSIONS, BIKE }

@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val granted: Set<AppPermission> = emptySet(),
    val bikes: List<BondedBike> = emptyList(),
    val selectedMac: String? = null,
)

sealed interface OnboardingUiAction {
    data object Next : OnboardingUiAction
    data object Back : OnboardingUiAction

    /** Handled by the Route (Activity result launcher): what the bike link needs, or also the [optional] dash extras. */
    data class RequestPermissions(val optional: Boolean) : OnboardingUiAction

    /** Handled by the Route: one optional permission (dialog or system settings). */
    data class RequestPermission(val permission: AppPermission) : OnboardingUiAction

    /** Handled by the Route (opens system settings). */
    data object OpenBluetoothSettings : OnboardingUiAction

    /** Sent on resume and after the permission dialog: re-reads permissions and paired devices. */
    data class Refresh(val granted: Set<AppPermission>) : OnboardingUiAction
    data class SelectBike(val mac: String) : OnboardingUiAction
    data class Finish(val withBike: Boolean) : OnboardingUiAction
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val linkRepository: BikeLinkRepository,
    private val settingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val state = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value)

    fun onAction(action: OnboardingUiAction) {
        when (action) {
            OnboardingUiAction.Next -> state.update { s ->
                s.copy(step = OnboardingStep.entries.getOrElse(s.step.ordinal + 1) { s.step })
            }
            OnboardingUiAction.Back -> state.update { s ->
                s.copy(step = OnboardingStep.entries.getOrElse(s.step.ordinal - 1) { s.step })
            }
            is OnboardingUiAction.Refresh -> state.update {
                val bikes = if (AppPermission.BLUETOOTH in action.granted) linkRepository.bondedCcus() else emptyList()
                it.copy(
                    granted = action.granted,
                    bikes = bikes,
                    selectedMac = it.selectedMac?.takeIf { mac -> bikes.any { b -> b.mac == mac } } ?: bikes.singleOrNull()?.mac,
                )
            }
            is OnboardingUiAction.SelectBike -> state.update { it.copy(selectedMac = action.mac) }
            is OnboardingUiAction.Finish -> viewModelScope.launch {
                val s = state.value
                val bike = if (action.withBike) s.bikes.firstOrNull { it.mac == s.selectedMac } else null
                settingsRepository.completeOnboarding(bike) // app root switches to the main screens
                bike?.let { linkRepository.start(it.mac) } // Home opens already connecting, not on a Connect button
            }
            is OnboardingUiAction.RequestPermissions,
            is OnboardingUiAction.RequestPermission,
            OnboardingUiAction.OpenBluetoothSettings,
            -> Unit
        }
    }
}
