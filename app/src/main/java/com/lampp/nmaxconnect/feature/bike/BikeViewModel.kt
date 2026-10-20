package com.lampp.nmaxconnect.feature.bike

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lampp.nmaxconnect.core.maintenance.Garage
import com.lampp.nmaxconnect.core.maintenance.MaintenanceRepository
import com.lampp.nmaxconnect.core.maintenance.ServiceItem
import com.lampp.nmaxconnect.core.maintenance.ServiceRecord
import com.lampp.nmaxconnect.core.maintenance.kmLeft
import com.lampp.nmaxconnect.core.maintenance.lastChangeKm
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class BikeUiState(
    /** False until the saved log is read, so the empty states don't flash. */
    val loaded: Boolean = false,
    val odoKm: Double? = null,
    val services: List<ServiceRow> = ServiceItem.entries.map { ServiceRow(it, it.defaultKm) },
)

data class ServiceRow(
    val item: ServiceItem,
    val intervalKm: Int,
    val record: ServiceRecord? = null,
    /** Km of the last change; 0 when never recorded. */
    val lastChangeKm: Double = 0.0,
    val kmLeft: Double? = null,
    val overdue: Boolean = false,
    /** Share of the interval ridden since the last change, 0..1; null while the ODO is unknown. */
    val progress: Float? = null,
)

sealed interface BikeUiAction {
    /** Changed now, at [odoKm]. */
    data class Reset(val item: ServiceItem, val odoKm: Double) : BikeUiAction

    /** Puts back what a reset replaced (null = never recorded). */
    data class UndoReset(val item: ServiceItem, val previous: ServiceRecord?) : BikeUiAction

    data class SaveSettings(val item: ServiceItem, val lastChangeKm: Double, val intervalKm: Int) : BikeUiAction
}

fun bikeUiState(garage: Garage) = BikeUiState(
    loaded = true,
    odoKm = garage.odoKm,
    services = ServiceItem.entries.map { item ->
        val intervalKm = garage.intervalKm(item)
        val kmLeft = garage.kmLeft(item)
        ServiceRow(
            item = item,
            intervalKm = intervalKm,
            record = garage.services[item],
            lastChangeKm = garage.lastChangeKm(item),
            kmLeft = kmLeft,
            overdue = (kmLeft ?: 0.0) < 0,
            progress = kmLeft?.let { (1 - it / intervalKm).toFloat().coerceIn(0f, 1f) },
        )
    },
)

@HiltViewModel
class BikeViewModel @Inject constructor(
    private val repository: MaintenanceRepository,
) : ViewModel() {
    val uiState: StateFlow<BikeUiState> = repository.garage
        .map(::bikeUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BikeUiState())

    fun onAction(action: BikeUiAction) {
        viewModelScope.launch {
            when (action) {
                is BikeUiAction.Reset -> repository.setRecord(action.item, ServiceRecord(action.odoKm))
                is BikeUiAction.UndoReset -> repository.setRecord(action.item, action.previous)
                is BikeUiAction.SaveSettings ->
                    repository.saveService(action.item, ServiceRecord(action.lastChangeKm), action.intervalKm)
            }
        }
    }
}
