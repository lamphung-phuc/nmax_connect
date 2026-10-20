package com.lampp.nmaxconnect.feature.bike

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.OilBarrel
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.maintenance.Garage
import com.lampp.nmaxconnect.core.maintenance.ServiceItem
import com.lampp.nmaxconnect.core.maintenance.ServiceRecord
import com.lampp.nmaxconnect.ui.components.AppScaffold
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.HelpSection
import com.lampp.nmaxconnect.ui.components.HelpSheet
import com.lampp.nmaxconnect.ui.components.sectionHeader
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

@Composable
fun BikeRoute(modifier: Modifier = Modifier, viewModel: BikeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BikeScreen(uiState, viewModel::onAction, modifier)
}

@Composable
fun BikeScreen(uiState: BikeUiState, onAction: (BikeUiAction) -> Unit, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf<ServiceItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    fun reset(row: ServiceRow, odoKm: Double) {
        onAction(BikeUiAction.Reset(row.item, odoKm))
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss() // the newest reset wins the snackbar
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(R.string.bike_service_reset_done, resources.getString(row.item.label), km(odoKm)),
                actionLabel = resources.getString(R.string.action_undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction(BikeUiAction.UndoReset(row.item, row.record))
        }
    }

    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) HelpSheet(listOf(HelpSection(null, bikeHelpTopics)), onDismiss = { showHelp = false })
    AppScaffold(
        title = stringResource(R.string.tab_bike),
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        onHelp = { showHelp = true },
    ) {
        sectionHeader(R.string.bike_section_odo)
        item(key = "odo") { OdometerCard(uiState.odoKm, Modifier.animateItem()) }

        sectionHeader(R.string.bike_section_service)
        item(key = "services") {
            GroupedItems(uiState.services.size, Modifier.animateItem()) { i ->
                val row = uiState.services[i]
                ServiceCard(
                    row = row,
                    onClick = { editing = row.item },
                    onReset = uiState.odoKm?.let { odo -> { reset(row, odo) } },
                )
            }
        }
        item(key = "serviceHint") {
            Text(
                stringResource(R.string.bike_service_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }

    editing?.let { item ->
        val row = uiState.services.first { it.item == item }
        ServiceSettingsDialog(row, uiState.odoKm, onDismiss = { editing = null }) { lastChangeKm, interval ->
            onAction(BikeUiAction.SaveSettings(item, lastChangeKm, interval))
            editing = null
        }
    }
}

@Composable
internal fun OdometerCard(odoKm: Double?, modifier: Modifier = Modifier) {
    GroupedItems(1, modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = odoKm?.let { stringResource(R.string.bike_km, km(it)) } ?: stringResource(R.string.bike_odo_none_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
            )
            if (odoKm == null) {
                Text(
                    stringResource(R.string.bike_odo_none_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Two-line list item: icon, name and km left, then a bar from the last change (or 0 km) to the next one.
 * Tap opens the settings; the trailing button resets (disabled, [onReset] null, while the ODO is unknown).
 */
@Composable
internal fun ServiceCard(row: ServiceRow, onClick: () -> Unit, onReset: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(row.item.label), modifier = Modifier.weight(1f))
                Text(
                    text = when {
                        row.overdue -> stringResource(R.string.bike_service_overdue_badge)
                        row.kmLeft != null -> stringResource(R.string.bike_service_left, km(row.kmLeft))
                        else -> stringResource(R.string.bike_service_every, km(row.intervalKm.toDouble()))
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (row.overdue) scheme.error else scheme.onSurfaceVariant,
                )
            }
        },
        supportingContent = { ServiceProgress(row.progress, row.overdue, Modifier.padding(top = 8.dp)) },
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(if (row.overdue) scheme.errorContainer else scheme.secondaryContainer, CircleShape),
            ) {
                Icon(
                    row.item.icon,
                    contentDescription = null,
                    tint = if (row.overdue) scheme.onErrorContainer else scheme.onSecondaryContainer,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = { onReset?.invoke() }, enabled = onReset != null, modifier = Modifier.offset(x = 12.dp)) {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = stringResource(R.string.bike_service_reset, stringResource(row.item.label)),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // TalkBack: "double-tap to edit", not just "double-tap to activate".
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.bike_service_edit), onClick = onClick),
    )
}

/** Bar with the current ODO as a dot; no dot while the ODO is unknown. */
@Composable
private fun ServiceProgress(progress: Float?, overdue: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val color = if (overdue) scheme.error else scheme.primary
    Layout(
        modifier = modifier,
        content = {
            LinearProgressIndicator(
                progress = { progress ?: 0f },
                color = color,
                trackColor = scheme.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            if (progress != null) {
                Box(Modifier.size(16.dp).border(4.dp, scheme.surfaceContainerLow, CircleShape).background(color, CircleShape))
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val track = measurables[0].measure(Constraints.fixedWidth(width))
        val dot = measurables.getOrNull(1)?.measure(Constraints())
        val height = dot?.height ?: 16.dp.roundToPx()
        layout(width, height) {
            track.place(0, (height - track.height) / 2)
            dot?.place(((width * (progress ?: 0f)).roundToInt() - dot.width / 2).coerceIn(0, width - dot.width), 0)
        }
    }
}

@Composable
private fun ServiceSettingsDialog(
    row: ServiceRow,
    odoKm: Double?,
    onDismiss: () -> Unit,
    onSave: (lastChangeKm: Double, intervalKm: Int) -> Unit,
) {
    var interval by rememberSaveable { mutableStateOf(row.intervalKm.toString()) }
    var lastChange by rememberSaveable { mutableStateOf(row.lastChangeKm.roundToLong().toString()) }
    val intervalValue = interval.toIntOrNull()?.takeIf { it in 100..100_000 }
    // A change can't be ahead of the bike's own odometer.
    val lastChangeValue = lastChange.toLongOrNull()?.takeIf { odoKm == null || it <= odoKm.roundToLong() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(row.item.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KmField(interval, { interval = it }, R.string.bike_service_every_label, isError = intervalValue == null)
                KmField(lastChange, { lastChange = it }, R.string.bike_service_changed_at_label, isError = lastChangeValue == null)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(lastChangeValue!!.toDouble(), intervalValue!!) },
                enabled = lastChangeValue != null && intervalValue != null,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun KmField(value: String, onValueChange: (String) -> Unit, label: Int, isError: Boolean) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    var selectAll by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = field,
        onValueChange = {
            val digits = it.text.filter(Char::isDigit).take(7)
            // The tap that focuses the field also drops the cursor; keep the whole value selected so typing replaces it.
            field = if (selectAll && digits == field.text) {
                field.copy(selection = TextRange(0, digits.length))
            } else {
                TextFieldValue(digits, TextRange(minOf(it.selection.start, digits.length), minOf(it.selection.end, digits.length)))
            }
            selectAll = false
            onValueChange(digits)
        },
        label = { Text(stringResource(label)) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.onFocusChanged { focus ->
            selectAll = focus.isFocused
            if (focus.isFocused) field = field.copy(selection = TextRange(0, field.text.length))
        },
    )
}

private val ServiceItem.label: Int
    get() = when (this) {
        ServiceItem.ENGINE_OIL -> R.string.service_engine_oil
        ServiceItem.GEAR_OIL -> R.string.service_gear_oil
        ServiceItem.AIR_FILTER -> R.string.service_air_filter
        ServiceItem.CVT_BELT -> R.string.service_cvt_belt
        ServiceItem.SPARK_PLUG -> R.string.service_spark_plug
    }

private val ServiceItem.icon: ImageVector
    get() = when (this) {
        ServiceItem.ENGINE_OIL -> Icons.Filled.OilBarrel
        ServiceItem.GEAR_OIL -> Icons.Filled.Opacity
        ServiceItem.AIR_FILTER -> Icons.Filled.FilterAlt
        ServiceItem.CVT_BELT -> Icons.Filled.AllInclusive
        ServiceItem.SPARK_PLUG -> Icons.Filled.Bolt
    }

private fun km(value: Double) = "%,d".format(value.roundToLong())

@PreviewLightDark
@Composable
private fun BikeScreenPreview() {
    val garage = Garage(
        odoKm = 12_480.0,
        services = mapOf(
            ServiceItem.ENGINE_OIL to ServiceRecord(10_200.0),
            ServiceItem.GEAR_OIL to ServiceRecord(3_000.0),
        ),
    )
    NmaxConnectTheme { BikeScreen(bikeUiState(garage), onAction = {}) }
}

@PreviewLightDark
@Composable
private fun BikeScreenEmptyPreview() {
    NmaxConnectTheme { BikeScreen(bikeUiState(Garage()), onAction = {}) }
}
