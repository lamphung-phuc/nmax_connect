package com.lampp.nmaxconnect.feature.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.BondedBike
import com.lampp.nmaxconnect.ui.components.AppPermission
import com.lampp.nmaxconnect.ui.components.AppScaffold
import com.lampp.nmaxconnect.ui.components.EmptyState
import com.lampp.nmaxconnect.ui.components.GroupedItems
import com.lampp.nmaxconnect.ui.components.GroupedRow
import com.lampp.nmaxconnect.ui.components.PermissionRow
import com.lampp.nmaxconnect.ui.components.bikeId
import com.lampp.nmaxconnect.ui.components.grantedPermissions
import com.lampp.nmaxconnect.ui.components.rememberPermissionChain
import com.lampp.nmaxconnect.ui.components.rememberPermissionRequest
import com.lampp.nmaxconnect.ui.components.sectionHeader
import com.lampp.nmaxconnect.ui.theme.AppMotion
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme

@Composable
fun OnboardingRoute(modifier: Modifier = Modifier, viewModel: OnboardingViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val refresh = { viewModel.onAction(OnboardingUiAction.Refresh(grantedPermissions(context))) }
    val requestPermissions = rememberPermissionChain { refresh() }
    val requestPermission = rememberPermissionRequest { refresh() }
    // Re-check after returning from the permission dialog or Bluetooth settings.
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    BackHandler(enabled = uiState.step != OnboardingStep.WELCOME) { viewModel.onAction(OnboardingUiAction.Back) }
    OnboardingScreen(
        uiState = uiState,
        onAction = { action ->
            when (action) {
                is OnboardingUiAction.RequestPermissions ->
                    requestPermissions(if (action.optional) AppPermission.entries else AppPermission.required)
                is OnboardingUiAction.RequestPermission -> requestPermission(action.permission)
                OnboardingUiAction.OpenBluetoothSettings -> context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
    )
}

@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onAction: (OnboardingUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Steps are a sequence: shared axis X, sliding back when the step goes back.
    AnimatedContent(
        targetState = uiState,
        contentKey = { it.step },
        transitionSpec = { AppMotion.sharedAxisX(forward = targetState.step > initialState.step) },
        modifier = modifier,
        label = "onboardingStep",
    ) { state ->
        val title = when (state.step) {
            OnboardingStep.WELCOME -> null // the wordmark is the title
            OnboardingStep.PERMISSIONS -> R.string.onboarding_permissions_title
            OnboardingStep.BIKE -> R.string.onboarding_bike_title
        }
        AppScaffold(
            title = title?.let { stringResource(it) },
            navigateUp = if (state.step == OnboardingStep.WELCOME) null else ({ onAction(OnboardingUiAction.Back) }),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME -> welcomeStep(onAction)
                OnboardingStep.PERMISSIONS -> permissionsStep(state, onAction)
                OnboardingStep.BIKE -> bikeStep(state, onAction)
            }
        }
    }
}

private fun LazyListScope.welcomeStep(onAction: (OnboardingUiAction) -> Unit) {
    // One item filling the viewport so logo, text and button sit together in the middle.
    item {
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillParentMaxHeight().padding(horizontal = 16.dp)) {
            Image(
                painterResource(R.drawable.ic_wordmark),
                contentDescription = stringResource(R.string.app_name),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().height(40.dp), // ~200 dp wide; 72 dp filled the screen
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(R.string.action_continue) { onAction(OnboardingUiAction.Next) }
        }
    }
}

private fun LazyListScope.permissionsStep(uiState: OnboardingUiState, onAction: (OnboardingUiAction) -> Unit) {
    sectionHeader(R.string.perm_required)
    permissionGroup(AppPermission.required, uiState, onAction)
    sectionHeader(R.string.perm_optional)
    permissionGroup(AppPermission.entries - AppPermission.required.toSet(), uiState, onAction)
    item {
        val requiredGranted = uiState.granted.containsAll(AppPermission.required)
        val allGranted = uiState.granted.containsAll(AppPermission.entries)
        if (requiredGranted) {
            PrimaryButton(R.string.action_continue) { onAction(OnboardingUiAction.Next) }
            if (!allGranted) SecondaryButton(R.string.action_grant) { onAction(OnboardingUiAction.RequestPermissions(optional = true)) }
        } else {
            PrimaryButton(R.string.action_grant) { onAction(OnboardingUiAction.RequestPermissions(optional = false)) }
        }
    }
}

private fun LazyListScope.permissionGroup(
    permissions: List<AppPermission>,
    uiState: OnboardingUiState,
    onAction: (OnboardingUiAction) -> Unit,
) {
    item {
        GroupedItems(count = permissions.size) { index ->
            val permission = permissions[index]
            val granted = permission in uiState.granted
            PermissionRow(
                permission = permission,
                trailing = {
                    AnimatedVisibility(
                        granted,
                        enter = scaleIn(AppMotion.spatial()) + fadeIn(AppMotion.effects()),
                        exit = fadeOut(AppMotion.effectsFast()),
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.perm_granted),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = if (granted) null else ({ onAction(OnboardingUiAction.RequestPermission(permission)) }),
            )
        }
    }
}

private fun LazyListScope.bikeStep(uiState: OnboardingUiState, onAction: (OnboardingUiAction) -> Unit) {
    item {
        Text(
            stringResource(R.string.onboarding_bike_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
    if (uiState.bikes.isEmpty()) {
        item {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                title = stringResource(R.string.bike_empty_title),
                body = stringResource(R.string.bike_empty_body),
                actionLabel = stringResource(R.string.action_open_bluetooth),
                onAction = { onAction(OnboardingUiAction.OpenBluetoothSettings) },
            )
        }
    } else {
        item {
            GroupedItems(count = uiState.bikes.size) { index ->
                val bike = uiState.bikes[index]
                val selected = bike.mac == uiState.selectedMac
                GroupedRow(
                    headline = bikeId(bike),
                    supporting = bike.name,
                    leading = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier.selectable(selected, role = Role.RadioButton) { onAction(OnboardingUiAction.SelectBike(bike.mac)) },
                )
            }
        }
    }
    item {
        if (uiState.bikes.isNotEmpty()) {
            PrimaryButton(R.string.action_use_bike, enabled = uiState.selectedMac != null) {
                onAction(OnboardingUiAction.Finish(withBike = true))
            }
        }
        SecondaryButton(R.string.action_skip) { onAction(OnboardingUiAction.Finish(withBike = false)) }
    }
}

@Composable
private fun PrimaryButton(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 48.dp),
    ) { Text(stringResource(label)) }
}

@Composable
private fun SecondaryButton(label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
        Text(stringResource(label))
    }
}

@PreviewLightDark
@Composable
private fun OnboardingBikePreview() {
    NmaxConnectTheme {
        OnboardingScreen(
            uiState = OnboardingUiState(
                step = OnboardingStep.BIKE,
                granted = setOf(AppPermission.BLUETOOTH),
                bikes = listOf(BondedBike("NMAX 155", "00:11:22:33:44:55"), BondedBike("Pixel Buds", "AA:BB:CC:DD:EE:FF")),
                selectedMac = "00:11:22:33:44:55",
            ),
            onAction = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun OnboardingPermissionsPreview() {
    NmaxConnectTheme {
        OnboardingScreen(
            uiState = OnboardingUiState(
                step = OnboardingStep.PERMISSIONS,
                granted = setOf(AppPermission.BLUETOOTH, AppPermission.LOCATION),
            ),
            onAction = {},
        )
    }
}
