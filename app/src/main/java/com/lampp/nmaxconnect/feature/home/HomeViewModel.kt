package com.lampp.nmaxconnect.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.bike.BondedBike
import com.lampp.nmaxconnect.core.bike.ccu.CcuLinkRepository
import com.lampp.nmaxconnect.core.bike.ccu.CcuStatus
import com.lampp.nmaxconnect.core.settings.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

@Immutable
data class HomeUiState(
    val bike: BondedBike? = null,
    val link: BikeLinkState = BikeLinkState.Idle,
    val ccuLink: CcuStatus = CcuStatus.Idle,
    /** Shown after a few failed attempts: another app probably holds the bike. */
    val showConflictTip: Boolean = false,
    /** False until the saved bike is read, so the card doesn't flash "choose a bike". */
    val loaded: Boolean = false,
)

sealed interface HomeUiAction {
    /** The Route checks Bluetooth permission first and asks for it if needed. */
    data object Connect : HomeUiAction
    data object Disconnect : HomeUiAction

    /** Handled by the Route (permission dialog). */
    data object RequestPermission : HomeUiAction

    /** Handled by the Route (navigates to Settings). */
    data object ChooseBike : HomeUiAction
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val linkRepository: BikeLinkRepository,
    ccuRepository: CcuLinkRepository,
    settingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val failedAttempts = linkRepository.state.scan(0) { failures, state ->
        when (state) {
            is BikeLinkState.Connecting -> state.attempt - 1
            is BikeLinkState.Ready, BikeLinkState.Idle -> 0
            else -> failures
        }
    }

    val uiState: StateFlow<HomeUiState> =
        combine(settingsRepository.settings, linkRepository.state, ccuRepository.status, failedAttempts) { settings, link, ccu, failures ->
            HomeUiState(bike = settings.bike, link = link, ccuLink = ccu, showConflictTip = failures >= CONFLICT_TIP_AFTER, loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onAction(action: HomeUiAction) {
        when (action) {
            HomeUiAction.Connect -> uiState.value.bike?.let { linkRepository.start(it.mac) }
            HomeUiAction.Disconnect -> linkRepository.stop()
            HomeUiAction.RequestPermission, HomeUiAction.ChooseBike -> Unit
        }
    }

    private companion object {
        const val CONFLICT_TIP_AFTER = 3
    }
}
