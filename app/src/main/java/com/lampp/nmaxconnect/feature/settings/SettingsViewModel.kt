package com.lampp.nmaxconnect.feature.settings

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lampp.nmaxconnect.BuildConfig
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.bike.BondedBike
import com.lampp.nmaxconnect.core.location.LocationSource
import com.lampp.nmaxconnect.core.map.AreaMap
import com.lampp.nmaxconnect.core.map.OfflineMaps
import com.lampp.nmaxconnect.core.settings.AppSettingsRepository
import com.lampp.nmaxconnect.core.settings.AppTheme
import com.lampp.nmaxconnect.core.update.AppUpdate
import com.lampp.nmaxconnect.core.update.UpdateChecker
import com.lampp.nmaxconnect.ui.components.AppPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val bike: BondedBike? = null,
    val autoConnect: Boolean = false,
    val version: String = "",
    /** Non-null while the bike picker dialog is open. */
    val bikeChoices: List<BondedBike>? = null,
    /** Read by the Route on resume and after a permission dialog (some are granted in system settings). */
    val granted: Set<AppPermission> = emptySet(),
    val areaMap: AreaMap = AreaMap.Unknown,
    /** The last Save area tap found no location. */
    val areaNoLocation: Boolean = false,
    /** The Developer section (bike link debug screen) only in debug builds. */
    val showDebug: Boolean = false,
    /** False until the saved bike is read, so the row doesn't flash "No bike chosen". */
    val bikeLoaded: Boolean = false,
    val theme: AppTheme = AppTheme.SYSTEM,
    /** Read by the Route from the system (per-app language); null hides the row (Android 12 and older). */
    val language: AppLanguage? = null,
    val update: UpdateState = UpdateState.Idle,
)

/** The Version row's update check; [Available] with [Available.open] shows the what's-new dialog. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Failed : UpdateState
    data class Available(val update: AppUpdate, val open: Boolean = true) : UpdateState
}

sealed interface SettingsUiAction {
    data object ChangeBike : SettingsUiAction
    data class SelectBike(val bike: BondedBike) : SettingsUiAction
    data object DismissBikePicker : SettingsUiAction

    /** Blank clears the nickname. */
    data class SetBikeNickname(val name: String) : SettingsUiAction
    data class SetAutoConnect(val on: Boolean) : SettingsUiAction

    /** Saves (or re-centres) the offline map around the current position. */
    data object SaveAreaMap : SettingsUiAction
    data object DeleteAreaMap : SettingsUiAction

    /** Handled by the Route (navigation). */
    data object OpenDebug : SettingsUiAction

    /** Handled by the Route: asks for [permission], or opens system settings to turn it off (apps cannot revoke their own). */
    data class TogglePermission(val permission: AppPermission) : SettingsUiAction

    data class SetTheme(val theme: AppTheme) : SettingsUiAction

    /** Handled by the Route: the system stores it and recreates the activity. */
    data class SetLanguage(val language: AppLanguage) : SettingsUiAction

    data object CheckUpdate : SettingsUiAction

    /** Closes the what's-new dialog; the row keeps showing that an update exists. */
    data object DismissUpdate : SettingsUiAction
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: AppSettingsRepository,
    private val linkRepository: BikeLinkRepository,
    private val offlineMaps: OfflineMaps,
    private val location: LocationSource,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    private val bikeChoices = MutableStateFlow<List<BondedBike>?>(null)
    private val areaNoLocation = MutableStateFlow(false)
    private val update = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** Kept after the dialog closes so tapping Version again reopens it without a new request. */
    private var found: AppUpdate? = null

    init {
        offlineMaps.refreshArea()
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        bikeChoices,
        offlineMaps.area,
        areaNoLocation,
        update,
    ) { settings, choices, area, noLocation, update ->
        SettingsUiState(
            bike = settings.bike,
            autoConnect = settings.autoConnect,
            version = BuildConfig.VERSION_NAME,
            bikeChoices = choices,
            areaMap = area,
            areaNoLocation = noLocation,
            showDebug = BuildConfig.DEBUG,
            bikeLoaded = true,
            theme = settings.theme,
            update = update,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(version = BuildConfig.VERSION_NAME, showDebug = BuildConfig.DEBUG),
    )

    fun onAction(action: SettingsUiAction) {
        when (action) {
            SettingsUiAction.ChangeBike -> bikeChoices.value = linkRepository.bondedCcus()
            is SettingsUiAction.SelectBike -> {
                bikeChoices.update { null }
                viewModelScope.launch { settingsRepository.setBike(action.bike) }
            }
            SettingsUiAction.DismissBikePicker -> bikeChoices.value = null
            is SettingsUiAction.SetBikeNickname -> viewModelScope.launch { settingsRepository.setBikeNickname(action.name) }
            is SettingsUiAction.SetAutoConnect -> viewModelScope.launch { settingsRepository.setAutoConnect(action.on) }
            SettingsUiAction.SaveAreaMap -> viewModelScope.launch {
                val here = location.current()
                areaNoLocation.value = here == null
                here?.let(offlineMaps::saveArea)
            }
            SettingsUiAction.DeleteAreaMap -> offlineMaps.deleteArea()
            is SettingsUiAction.SetTheme -> viewModelScope.launch { settingsRepository.setTheme(action.theme) }
            SettingsUiAction.CheckUpdate -> checkUpdate()
            SettingsUiAction.DismissUpdate -> update.update { (it as? UpdateState.Available)?.copy(open = false) ?: it }
            SettingsUiAction.OpenDebug, is SettingsUiAction.TogglePermission, is SettingsUiAction.SetLanguage -> Unit
        }
    }

    private fun checkUpdate() {
        found?.let { update.value = UpdateState.Available(it); return }
        if (update.value == UpdateState.Checking) return
        update.value = UpdateState.Checking
        viewModelScope.launch {
            update.value = try {
                updateChecker.check(BuildConfig.VERSION_NAME)
                    ?.also { found = it }
                    ?.let { UpdateState.Available(it) }
                    ?: UpdateState.UpToDate
            } catch (e: IOException) {
                Log.w(TAG, "Update check failed", e)
                UpdateState.Failed
            }
        }
    }

    private companion object {
        const val TAG = "Settings"
    }
}
