package com.lampp.nmaxconnect.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.places.SharedLocationInbox
import com.lampp.nmaxconnect.core.settings.AppSettingsRepository
import com.lampp.nmaxconnect.feature.bike.BikeRoute
import com.lampp.nmaxconnect.feature.debug.DebugLinkRoute
import com.lampp.nmaxconnect.feature.home.HomeRoute
import com.lampp.nmaxconnect.feature.navigate.NavigateRoute
import com.lampp.nmaxconnect.feature.navigate.WarmNavigateMap
import com.lampp.nmaxconnect.feature.navigate.rememberNavigateMapHolder
import com.lampp.nmaxconnect.feature.onboarding.OnboardingRoute
import com.lampp.nmaxconnect.feature.settings.SettingsRoute
import com.lampp.nmaxconnect.feature.vehicle.VehicleRoute
import com.lampp.nmaxconnect.ui.theme.AppMotion
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

@Serializable data object HomeDestination

@Serializable data object NavigateDestination

@Serializable data object BikeDestination

@Serializable data object VehicleDestination

@Serializable data object SettingsDestination

@Serializable data object DebugDestination

private enum class TopLevel(
    val route: Any,
    val routeClass: KClass<*>,
    val label: Int,
    val icon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(HomeDestination, HomeDestination::class, R.string.tab_home, Icons.Filled.Home, Icons.Outlined.Home),
    BIKE(BikeDestination, BikeDestination::class, R.string.tab_bike, Icons.Filled.TwoWheeler, Icons.Outlined.TwoWheeler),
    NAVIGATE(NavigateDestination, NavigateDestination::class, R.string.tab_navigate, Icons.Filled.Directions, Icons.Outlined.Directions),
    VEHICLE(VehicleDestination, VehicleDestination::class, R.string.tab_vehicle, Icons.Filled.Cast, Icons.Outlined.Cast),
    SETTINGS(SettingsDestination, SettingsDestination::class, R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

enum class AppStage { LOADING, ONBOARDING, MAIN }

@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: AppSettingsRepository,
    sharedInbox: SharedLocationInbox,
    linkRepository: BikeLinkRepository,
) : ViewModel() {
    /** The dash is linked, so it shows the map while guiding and the phone can leave its own out. */
    val dashConnected: StateFlow<Boolean> = linkRepository.state
        .map { it is BikeLinkState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** A shared place is waiting; the Navigate tab takes it once shown. */
    val sharePending: StateFlow<Boolean> = sharedInbox.text
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val stage: StateFlow<AppStage> = settingsRepository.settings
        .map { if (it.onboardingDone) AppStage.MAIN else AppStage.ONBOARDING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppStage.LOADING)
}

/** App root: onboarding until it's done, then the tabbed main area. */
@Composable
fun NmaxApp(viewModel: AppViewModel = hiltViewModel()) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val sharePending by viewModel.sharePending.collectAsStateWithLifecycle()
    val dashConnected by viewModel.dashConnected.collectAsStateWithLifecycle()
    AnimatedContent(targetState = stage, transitionSpec = { AppMotion.fadeThrough() }, label = "stage") {
        when (it) {
            AppStage.LOADING -> Unit // DataStore answers within a frame or two; avoids flashing onboarding
            AppStage.ONBOARDING -> OnboardingRoute()
            AppStage.MAIN -> MainArea(sharePending, dashConnected)
        }
    }
}

@Composable
private fun MainArea(sharePending: Boolean, dashConnected: Boolean) {
    val navController = rememberNavController()
    LaunchedEffect(sharePending) { if (sharePending) navController.navigateToTab(NavigateDestination) }
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val onTab = TopLevel.entries.any { tab -> destination?.hasRoute(tab.routeClass) == true }
    // Outside the NavHost: tab switches dispose the Navigate screen, not its map.
    val navigateMap = rememberNavigateMapHolder()

    Scaffold(
        bottomBar = {
            // Before the first entry is known, keep the bar shown rather than sliding it in on launch.
            AnimatedVisibility(visible = onTab || entry == null, enter = AppMotion.enterFromBottom, exit = AppMotion.exitToBottom) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    TopLevel.entries.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(if (selected) tab.icon else tab.unselectedIcon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        WarmNavigateMap(navigateMap) // under the NavHost, which draws over it
        // Screens draw their own top bar; only the bottom bar's space is taken here.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        NavHost(
            navController = navController,
            startDestination = HomeDestination,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
            // Tabs are peers (fade through); Debug is a level below Settings (shared axis X, also on predictive back).
            enterTransition = { if (targetState.isDebug()) AppMotion.forwardEnter else AppMotion.fadeThroughEnter },
            exitTransition = { if (targetState.isDebug()) AppMotion.forwardExit else AppMotion.fadeThroughExit },
            popEnterTransition = { if (initialState.isDebug()) AppMotion.backwardEnter else AppMotion.fadeThroughEnter },
            popExitTransition = { if (initialState.isDebug()) AppMotion.backwardExit else AppMotion.fadeThroughExit },
        ) {
            composable<HomeDestination> {
                HomeRoute(onChooseBike = {
                    navController.navigateToTab(SettingsDestination)
                    navController.currentBackStackEntry?.savedStateHandle?.set(PICK_BIKE, true)
                })
            }
            composable<NavigateDestination> {
                StayOnTab()
                NavigateRoute(navigateMap, dashConnected)
            }
            composable<BikeDestination> {
                StayOnTab()
                BikeRoute()
            }
            composable<VehicleDestination> {
                StayOnTab()
                VehicleRoute()
            }
            composable<SettingsDestination> { entry ->
                StayOnTab()
                // One shot: Home's "Choose bike" lands here with the picker already open.
                val pickBike = remember { entry.savedStateHandle.remove<Boolean>(PICK_BIKE) == true }
                SettingsRoute(onOpenDebug = { navController.navigate(DebugDestination) }, openBikePicker = pickBike)
            }
            composable<DebugDestination> { DebugLinkRoute(navigateUp = navController::navigateUp) }
        }
    }
}

/**
 * Back on a non-Home tab does nothing (tabs aren't a history to step back through). Registered before
 * the screen's own BackHandlers, so those (close search, close a card, leave routes) still win.
 */
@Composable
private fun StayOnTab() = BackHandler { }

private const val PICK_BIKE = "pickBike"

private fun NavBackStackEntry.isDebug() = destination.hasRoute(DebugDestination::class)

/** Standard tab switch: one copy of each tab, state saved/restored, back exits from the start tab. */
private fun NavHostController.navigateToTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
