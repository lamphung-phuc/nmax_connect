package com.lampp.nmaxconnect.feature.settings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.BondedBike
import com.lampp.nmaxconnect.core.map.AreaMap
import com.lampp.nmaxconnect.core.settings.AppTheme
import com.lampp.nmaxconnect.core.update.AppUpdate
import com.lampp.nmaxconnect.feature.bike.bikeHelpTopics
import com.lampp.nmaxconnect.feature.debug.debugHelpTopics
import com.lampp.nmaxconnect.feature.home.homeHelpTopics
import com.lampp.nmaxconnect.feature.navigate.navigateHelpTopics
import com.lampp.nmaxconnect.feature.vehicle.dashHelpTopics
import com.lampp.nmaxconnect.ui.components.AppPermission
import com.lampp.nmaxconnect.ui.components.AppScaffold
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.GroupedRow
import com.lampp.nmaxconnect.ui.components.HelpSection
import com.lampp.nmaxconnect.ui.components.HelpSheet
import com.lampp.nmaxconnect.ui.components.PermissionRow
import com.lampp.nmaxconnect.ui.components.bikeId
import com.lampp.nmaxconnect.ui.components.grantedPermissions
import com.lampp.nmaxconnect.ui.components.openPermissionSettings
import com.lampp.nmaxconnect.ui.components.rememberPermissionRequest
import com.lampp.nmaxconnect.ui.components.sectionHeader
import com.lampp.nmaxconnect.ui.theme.AppMotion
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme

@Composable
fun SettingsRoute(
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
    openBikePicker: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { if (openBikePicker) viewModel.onAction(SettingsUiAction.ChangeBike) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(grantedPermissions(context)) }
    val requestPermission = rememberPermissionRequest { granted = grantedPermissions(context) }
    LifecycleResumeEffect(Unit) {
        granted = grantedPermissions(context)
        onPauseOrDispose {}
    }
    SettingsScreen(
        uiState = uiState.copy(granted = granted, language = currentLanguage(context).takeIf { languageSupported() }),
        onAction = {
            when (it) {
                SettingsUiAction.OpenDebug -> onOpenDebug()
                is SettingsUiAction.SetLanguage -> setLanguage(context, it.language)
                is SettingsUiAction.TogglePermission ->
                    if (it.permission in granted) openPermissionSettings(context, it.permission) else requestPermission(it.permission)
                else -> viewModel.onAction(it)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showDonate by rememberSaveable { mutableStateOf(false) }
    if (showDonate) DonateDialog(onDismiss = { showDonate = false })
    (uiState.update as? UpdateState.Available)?.takeIf { it.open }?.let {
        UpdateDialog(it.update, onDismiss = { onAction(SettingsUiAction.DismissUpdate) })
    }
    AppScaffold(title = stringResource(R.string.tab_settings), modifier = modifier, onHelp = { showHelp = true }) {
        sectionHeader(R.string.settings_section_bike)
        item { BikeGroup(uiState, onAction) }
        sectionHeader(R.string.settings_section_map)
        item { GroupedItems(count = 1) { AreaMapRow(uiState, onAction) } }
        sectionHeader(R.string.settings_section_display)
        item { DisplayGroup(uiState, onAction) }
        sectionHeader(R.string.onboarding_permissions_title)
        item {
            // Closed on each visit: one summary row, tapped open to the per-permission switches.
            var expanded by remember { mutableStateOf(false) }
            val rows = if (expanded) AppPermission.entries.size else 0
            GroupedItems(count = 1 + rows, modifier = Modifier.animateContentSize(AppMotion.spatial())) { index ->
                if (index == 0) {
                    GroupedRow(
                        headline = stringResource(
                            R.string.settings_permissions_summary,
                            AppPermission.entries.count { it in uiState.granted },
                            AppPermission.entries.size,
                        ),
                        leading = { Icon(Icons.Filled.Security, contentDescription = null) },
                        trailing = {
                            Icon(
                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(
                                    if (expanded) R.string.settings_permissions_hide else R.string.settings_permissions_show,
                                ),
                            )
                        },
                        onClick = { expanded = !expanded },
                    )
                    return@GroupedItems
                }
                val permission = AppPermission.entries[index - 1]
                val granted = permission in uiState.granted
                // A tap opens a dialog or the system screen rather than flipping anything here, so a check, not a switch.
                PermissionRow(
                    permission = permission,
                    trailing = if (granted) {
                        {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = stringResource(R.string.perm_granted),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = { onAction(SettingsUiAction.TogglePermission(permission)) },
                )
            }
        }
        if (uiState.showDebug) {
            sectionHeader(R.string.settings_section_developer)
            item {
                GroupedItems(count = 1) {
                    GroupedRow(
                        headline = stringResource(R.string.settings_debug),
                        supporting = stringResource(R.string.settings_debug_body),
                        leading = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                        onClick = { onAction(SettingsUiAction.OpenDebug) },
                    )
                }
            }
        }
        sectionHeader(R.string.settings_section_credits)
        item { CreditsGroup() }
        sectionHeader(R.string.settings_section_about)
        item {
            AboutGroup(
                uiState.version,
                uiState.update,
                onHelp = { showHelp = true },
                onDonate = { showDonate = true },
                onCheckUpdate = { onAction(SettingsUiAction.CheckUpdate) },
            )
        }
    }

    if (showHelp) {
        val sections = listOfNotNull(
            HelpSection(R.string.tab_home, homeHelpTopics),
            HelpSection(R.string.tab_bike, bikeHelpTopics),
            HelpSection(R.string.tab_navigate, navigateHelpTopics),
            HelpSection(R.string.tab_vehicle, dashHelpTopics),
            HelpSection(R.string.settings_section_developer, debugHelpTopics).takeIf { uiState.showDebug },
        )
        HelpSheet(sections, onDismiss = { showHelp = false })
    }

    uiState.bikeChoices?.let { choices -> BikePickerDialog(choices, uiState.bike, onAction) }
}

/** Bike (by ID), then its options indented under it: name (once there is a bike) and auto connect. */
@Composable
private fun BikeGroup(uiState: SettingsUiState, onAction: (SettingsUiAction) -> Unit) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    val bike = uiState.bike
    val nameRow = if (bike != null) 1 else -1
    GroupedItems(count = if (bike != null) 3 else 2) { index ->
        when (index) {
            0 -> GroupedRow(
                headline = bike?.let { bikeId(it) } ?: if (uiState.bikeLoaded) stringResource(R.string.home_no_bike) else "",
                supporting = stringResource(R.string.settings_bike_change),
                leading = { Icon(Icons.Filled.TwoWheeler, contentDescription = null) },
                onClick = { onAction(SettingsUiAction.ChangeBike) },
            )
            nameRow -> GroupedRow(
                headline = stringResource(R.string.settings_bike_name),
                supporting = bike?.nickname ?: stringResource(R.string.settings_bike_name_none),
                leading = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { renaming = true },
                modifier = Modifier.padding(start = 16.dp),
            )
            else -> GroupedRow(
                headline = stringResource(R.string.settings_auto_connect),
                supporting = stringResource(R.string.settings_auto_connect_body),
                leading = { Icon(Icons.Filled.Autorenew, contentDescription = null) },
                trailing = { Switch(checked = uiState.autoConnect, onCheckedChange = null) },
                onClick = { onAction(SettingsUiAction.SetAutoConnect(!uiState.autoConnect)) },
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
    if (renaming && bike != null) {
        BikeNameDialog(bike, onDismiss = { renaming = false }) {
            onAction(SettingsUiAction.SetBikeNickname(it))
            renaming = false
        }
    }
}

/** Help, donate, Zalo support group, version (tap checks for an update). */
@Composable
private fun AboutGroup(version: String, update: UpdateState, onHelp: () -> Unit, onDonate: () -> Unit, onCheckUpdate: () -> Unit) {
    val context = LocalContext.current
    GroupedItems(count = 4) { index ->
        when (index) {
            0 -> GroupedRow(
                headline = stringResource(R.string.help),
                supporting = stringResource(R.string.settings_help_body),
                leading = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                onClick = onHelp,
            )
            1 -> GroupedRow(
                headline = stringResource(R.string.settings_donate),
                supporting = stringResource(R.string.settings_donate_body),
                leading = { Icon(Icons.Filled.VolunteerActivism, contentDescription = null) },
                onClick = onDonate,
            )
            2 -> GroupedRow(
                headline = stringResource(R.string.settings_zalo),
                supporting = stringResource(if (ZALO_GROUP_URL.isEmpty()) R.string.settings_zalo_soon else R.string.settings_zalo_body),
                leading = { Icon(Icons.Filled.Forum, contentDescription = null) },
                enabled = ZALO_GROUP_URL.isNotEmpty(),
                // Zalo opens its own zalo.me links; without the app the browser shows the invite.
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, ZALO_GROUP_URL.toUri())) } },
            )
            else -> GroupedRow(
                headline = stringResource(R.string.settings_version),
                supporting = when (update) {
                    UpdateState.Idle -> stringResource(R.string.settings_update_tap, version)
                    UpdateState.Checking -> stringResource(R.string.settings_update_checking, version)
                    UpdateState.UpToDate -> stringResource(R.string.settings_update_latest, version)
                    UpdateState.Failed -> stringResource(R.string.settings_update_failed, version)
                    is UpdateState.Available -> stringResource(R.string.settings_update_available, version, update.update.version)
                },
                leading = {
                    Icon(if (update is UpdateState.Available) Icons.Filled.SystemUpdate else Icons.Filled.Info, contentDescription = null)
                },
                trailing = if (update == UpdateState.Checking) {
                    { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp) }
                } else {
                    null
                },
                onClick = onCheckUpdate,
            )
        }
    }
}

/** What's new in [update], with Download opening the APK link (the browser downloads it, then the system installs it). */
@Composable
private fun UpdateDialog(update: AppUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
        title = { Text(stringResource(R.string.update_title, update.version)) },
        text = {
            Text(
                update.notes.ifEmpty { stringResource(R.string.update_no_notes) },
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, update.url.toUri())) }
                onDismiss()
            }) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
    )
}

/** The apps NmaxConnect is based on, each opening its store page. */
@Composable
private fun CreditsGroup() {
    val context = LocalContext.current
    GroupedItems(count = CREDITS.size) { index ->
        val credit = CREDITS[index]
        GroupedRow(
            headline = credit.name,
            supporting = stringResource(R.string.settings_credit_body, credit.author, credit.store),
            leading = { Icon(Icons.Filled.Handshake, contentDescription = null) },
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, credit.url.toUri())) } },
        )
    }
}

/** Zalo support group invite (`https://zalo.me/g/...`); the row stays disabled ("coming soon") while empty. */
private const val ZALO_GROUP_URL = ""

/** Apps this one is based on; names, authors and stores are proper nouns, so not translated. */
private class Credit(val name: String, val author: String, val store: String, val url: String)

private val CREDITS = listOf(
    Credit("SDMV DashConnect", "SDMV", "Google Play", "https://play.google.com/store/apps/details?id=com.sdmv.qjnavigation"),
    Credit("NConnect", "Phạm Minh Nam", "GitHub", "https://github.com/dmina97-cpu/nconnect-releases"),
)

/** The author's MoMo VietQR; any Vietnamese bank app scans it, or imports it from Photos after Save. */
@Composable
private fun DonateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_donate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_donate_dialog_body))
                Image(
                    painterResource(R.drawable.donate_qr),
                    contentDescription = stringResource(R.string.settings_donate_qr),
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        dismissButton = {
            // MediaStore needs no permission from Android 10; older phones just show the QR.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                TextButton(onClick = {
                    val message = if (saveDonateQr(context)) R.string.settings_donate_saved else R.string.settings_donate_save_failed
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.settings_donate_save)) }
            }
        },
    )
}

/** Copies the bundled QR into Pictures/NmaxConnect so a bank app can pick it from the gallery (160 KB: main thread is fine). */
@RequiresApi(Build.VERSION_CODES.Q)
private fun saveDonateQr(context: Context): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "nmaxconnect_donate_qr.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/NmaxConnect")
    }
    val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    context.contentResolver.openOutputStream(uri).use { out ->
        context.resources.openRawResource(R.drawable.donate_qr).use { it.copyTo(checkNotNull(out)) }
    }
}.isSuccess

private const val MAX_BIKE_NAME = 24 // fits the Home card's headline on one line

/** Nickname for the saved bike;the ID is the placeholder, and saving it empty goes back to the ID. */
@Composable
private fun BikeNameDialog(bike: BondedBike, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    // Whole name selected, so typing replaces it (a String field would put the cursor at the start).
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val current = bike.nickname.orEmpty()
        mutableStateOf(TextFieldValue(current, TextRange(0, current.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() } // keyboard up with the dialog
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_bike_name)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.text.length <= MAX_BIKE_NAME) name = it },
                placeholder = { Text(bikeId(bike)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(name.text) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Offline map of the rider's area: save, progress, size, re-centre on tap, delete. */
@Composable
private fun AreaMapRow(uiState: SettingsUiState, onAction: (SettingsUiAction) -> Unit, modifier: Modifier = Modifier) {
    val area = uiState.areaMap
    val context = LocalContext.current
    GroupedRow(
        headline = stringResource(R.string.settings_area_map),
        supporting = when {
            area == AreaMap.Unknown -> "" // keeps the row's height until the saved state is read
            uiState.areaNoLocation && area == AreaMap.None -> stringResource(R.string.settings_area_map_no_location)
            area is AreaMap.Downloading -> stringResource(R.string.settings_area_map_downloading, area.percent)
            area is AreaMap.Saved -> stringResource(R.string.settings_area_map_saved, Formatter.formatShortFileSize(context, area.bytes))
            else -> stringResource(R.string.settings_area_map_body)
        },
        leading = { Icon(Icons.Filled.DownloadForOffline, contentDescription = null) },
        trailing = if (area == AreaMap.None || area == AreaMap.Unknown) {
            null
        } else {
            {
                // The 48 dp button centres its icon 12 dp in from the edge: shift it so it lines up with plain trailing icons.
                IconButton(onClick = { onAction(SettingsUiAction.DeleteAreaMap) }, modifier = Modifier.offset(x = 12.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_area_map_delete))
                }
            }
        },
        onClick = { onAction(SettingsUiAction.SaveAreaMap) }.takeUnless { area is AreaMap.Downloading || area == AreaMap.Unknown },
        modifier = modifier,
    )
}

/** Language (Android 13+) and theme rows; each opens a one-of-N dialog. */
@Composable
private fun DisplayGroup(uiState: SettingsUiState, onAction: (SettingsUiAction) -> Unit) {
    var picking by remember { mutableStateOf<Picker?>(null) }
    val language = uiState.language
    GroupedItems(count = if (language != null) 2 else 1) { index ->
        if (language != null && index == 0) {
            GroupedRow(
                headline = stringResource(R.string.settings_language),
                supporting = stringResource(language.label),
                leading = { Icon(Icons.Filled.Language, contentDescription = null) },
                onClick = { picking = Picker.LANGUAGE },
            )
        } else {
            GroupedRow(
                headline = stringResource(R.string.settings_theme),
                supporting = stringResource(uiState.theme.label()),
                leading = { Icon(Icons.Filled.Contrast, contentDescription = null) },
                onClick = { picking = Picker.THEME },
            )
        }
    }
    when (picking) {
        Picker.LANGUAGE -> ChoiceDialog(
            title = R.string.settings_language,
            options = AppLanguage.entries.map { it.label },
            selected = AppLanguage.entries.indexOf(language),
            onSelect = { onAction(SettingsUiAction.SetLanguage(AppLanguage.entries[it])) },
            onDismiss = { picking = null },
        )
        Picker.THEME -> ChoiceDialog(
            title = R.string.settings_theme,
            options = AppTheme.entries.map { it.label() },
            selected = uiState.theme.ordinal,
            onSelect = { onAction(SettingsUiAction.SetTheme(AppTheme.entries[it])) },
            onDismiss = { picking = null },
        )
        null -> Unit
    }
}

private enum class Picker { LANGUAGE, THEME }

private fun AppTheme.label() = when (this) {
    AppTheme.SYSTEM -> R.string.settings_option_system
    AppTheme.LIGHT -> R.string.settings_theme_light
    AppTheme.DARK -> R.string.settings_theme_dark
}

/** One-of-N choice: a tap selects and closes. */
@Composable
private fun ChoiceDialog(title: Int, options: List<Int>, selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    GroupedRow(
                        headline = stringResource(label),
                        leading = { RadioButton(selected = index == selected, onClick = null) },
                        modifier = Modifier.selectable(index == selected, role = Role.RadioButton) {
                            onDismiss()
                            onSelect(index)
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun BikePickerDialog(choices: List<BondedBike>, current: BondedBike?, onAction: (SettingsUiAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(SettingsUiAction.DismissBikePicker) },
        title = { Text(stringResource(R.string.onboarding_bike_title)) },
        text = {
            if (choices.isEmpty()) {
                Text(stringResource(R.string.bike_empty_body))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    choices.forEach { bike ->
                        val selected = bike.mac == current?.mac
                        GroupedRow(
                            headline = bikeId(bike),
                            supporting = bike.name,
                            leading = { RadioButton(selected = selected, onClick = null) },
                            modifier = Modifier.selectable(selected, role = Role.RadioButton) {
                                onAction(SettingsUiAction.SelectBike(bike))
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onAction(SettingsUiAction.DismissBikePicker) }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@PreviewLightDark
@Composable
private fun SettingsScreenPreview() {
    NmaxConnectTheme {
        SettingsScreen(
            uiState = SettingsUiState(
                bike = BondedBike("NMAX 155", "00:11:22:33:44:55"),
                version = "1.0",
                granted = setOf(AppPermission.BLUETOOTH, AppPermission.LOCATION, AppPermission.DASH_NOTIFICATIONS),
                areaMap = AreaMap.Saved(42_000_000),
                language = AppLanguage.SYSTEM,
            ),
            onAction = {},
        )
    }
}
