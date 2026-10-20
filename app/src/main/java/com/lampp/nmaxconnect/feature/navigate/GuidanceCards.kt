package com.lampp.nmaxconnect.feature.navigate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.location.GeoPoint
import com.lampp.nmaxconnect.core.navigation.Congestion
import com.lampp.nmaxconnect.core.navigation.Maneuver
import com.lampp.nmaxconnect.core.navigation.NavProgress
import com.lampp.nmaxconnect.core.navigation.NavState
import com.lampp.nmaxconnect.core.navigation.RawInstruction
import com.lampp.nmaxconnect.core.navigation.Route
import com.lampp.nmaxconnect.core.navigation.TrafficSpan
import com.lampp.nmaxconnect.core.navigation.dashDistance
import com.lampp.nmaxconnect.core.places.SavedPlace
import com.lampp.nmaxconnect.ui.theme.AppMotion
import com.lampp.nmaxconnect.ui.theme.GroupedListShapes
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Bottom-panel content for every non-Idle state: routing, route options, trip bar, arrived, failed. */
@Composable
fun NavPanel(nav: NavState, onAction: (NavigateUiAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        when (nav) {
            NavState.Idle -> Unit
            is NavState.Routing -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Title(nav.destination.name)
                    Supporting(stringResource(R.string.nav_routing))
                }
                CircularProgressIndicator()
            }
            is NavState.Preview -> RouteOptions(nav, onAction)
            is NavState.Active -> TripBar(nav, onAction)
            is NavState.Arrived -> {
                Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Title(stringResource(R.string.nav_arrived))
                Supporting(nav.destination.name)
                PrimaryButton(R.string.nav_done) { onAction(NavigateUiAction.DismissNavigation) }
            }
            is NavState.Failed -> {
                Title(nav.destination.name)
                val reason = when (nav.reason) {
                    NavState.Reason.NO_LOCATION -> R.string.nav_failed_location
                    NavState.Reason.NETWORK -> R.string.nav_failed_network
                    NavState.Reason.NOT_CONFIGURED -> R.string.navigate_unavailable_body
                }
                Supporting(stringResource(reason))
                PrimaryButton(R.string.nav_retry) { onAction(NavigateUiAction.RetryRoute) }
                TextButton(
                    onClick = { onAction(NavigateUiAction.DismissNavigation) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

/** Google-style route chooser: one row per option, the picked one tonal, fastest tagged. Tapping a grey line on the map picks too. */
@Composable
private fun RouteOptions(nav: NavState.Preview, onAction: (NavigateUiAction) -> Unit) {
    // From / To and the way back live in the directions header above the map.
    val fastest = nav.routes.indices.minBy { nav.routes[it].timeMs }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        nav.routes.forEachIndexed { i, route ->
            val picked = i == nav.selected
            val container by animateColorAsState(
                if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                AppMotion.effects(),
                label = "routeContainer",
            )
            val content by animateColorAsState(
                if (picked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                AppMotion.effects(),
                label = "routeContent",
            )
            Surface(
                onClick = { onAction(NavigateUiAction.SelectRoute(i)) },
                shape = GroupedListShapes.forIndex(i, nav.routes.size),
                color = container,
                contentColor = content,
            ) {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.TwoWheeler, contentDescription = null) },
                    headlineContent = { Text(durationText(route.timeMs), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        val via = viaStreets(route).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.nav_via, it) }
                        Text(listOfNotNull(distanceText(route.distanceMeters), via).joinToString(" · "))
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            if (i == fastest && nav.routes.size > 1) {
                                Text(stringResource(R.string.nav_fastest), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                            }
                            trafficLabel(route)?.let { Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
    }
    if (nav.origin != null) {
        // Guidance follows GPS, so a trip can only start from the rider's own position (as in Google Maps).
        Spacer(Modifier.height(12.dp))
        Supporting(stringResource(R.string.nav_start_needs_here))
        return
    }
    Button(
        onClick = { onAction(NavigateUiAction.StartNavigation) },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.nav_start))
    }
}

/** Shown only when a real share of the route is slow, so one red block at a light doesn't flag every city route. */
private fun trafficLabel(route: Route): Int? = when {
    route.trafficShare(Congestion.HEAVY) >= 0.15 -> R.string.nav_traffic_heavy
    route.trafficShare(Congestion.MODERATE) >= 0.3 -> R.string.nav_traffic_moderate
    else -> null
}

/** Next turn on top of the map while guiding (Google's banner), in the strong `primary` role so it reads at a glance. */
@Composable
fun ManeuverBanner(nav: NavState.Active, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Each new maneuver pushes the last one up (fast: this is read while riding); distance ticks update in place.
        AnimatedContent(targetState = nav, contentKey = ::bannerKey, transitionSpec = {
            AppMotion.sharedAxisYFast()
        }, label = "maneuver") { state ->
            val p = state.progress
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                when {
                    p == null -> Text(stringResource(R.string.nav_waiting_gps), style = MaterialTheme.typography.titleMedium)
                    state.rerouting -> Text(stringResource(R.string.nav_rerouting), style = MaterialTheme.typography.titleMedium)
                    state.offline -> Text(stringResource(R.string.nav_offline), style = MaterialTheme.typography.titleMedium)
                    else -> {
                        val target = p.next ?: p.current
                        Icon(maneuverIcon(target), contentDescription = null, modifier = Modifier.size(48.dp))
                        Column {
                            Text(
                                distanceText(p.distanceToNextMeters),
                                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                            )
                            Text(
                                target.street.ifBlank { target.text.replaceFirstChar { it.uppercase() } },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What the banner is about: waiting, rerouting, offline, or the maneuver ahead (not its live distance). */
private fun bannerKey(nav: NavState.Active): Any = nav.progress.let { p ->
    when {
        p == null -> "gps"
        nav.rerouting -> "rerouting"
        nav.offline -> "offline"
        else -> p.next ?: p.current
    }
}

/** Bottom of the map while guiding: arrival time, what's left, and stop. */
@Composable
private fun TripBar(nav: NavState.Active, onAction: (NavigateUiAction) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val p = nav.progress
            val left = p?.remainingTimeMs ?: nav.route.timeMs
            val eta = LocalTime.now().plusSeconds(left / 1000).format(DateTimeFormatter.ofPattern("HH:mm"))
            Text(
                durationText(left),
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.tertiary,
            )
            Supporting(stringResource(R.string.nav_eta, eta, distanceText(p?.remainingMeters ?: nav.route.distanceMeters)))
        }
        FilledTonalIconButton(
            onClick = { onAction(NavigateUiAction.StopNavigation) },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.size(56.dp),
        ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.nav_stop)) }
    }
}

/** App-side icon for a maneuver; the dash uses TurnIcons instead. */
fun maneuverIcon(m: Maneuver): ImageVector = when {
    m.text.contains("quay đầu", ignoreCase = true) -> Icons.Filled.UTurnLeft
    else -> when (m.sign) {
        -3 -> Icons.Filled.TurnSharpLeft
        -2 -> Icons.Filled.TurnLeft
        -1 -> Icons.Filled.TurnSlightLeft
        1 -> Icons.Filled.TurnSlightRight
        2 -> Icons.Filled.TurnRight
        3 -> Icons.Filled.TurnSharpRight
        4, 5 -> Icons.Filled.Flag
        6 -> Icons.Filled.RoundaboutRight
        else -> Icons.Filled.Straight
    }
}

/** The two longest legs' streets: a quick "which way does it go" hint, like Google's "via". */
private fun viaStreets(route: Route): String = route.maneuvers
    .filter { it.street.isNotBlank() && !it.isFinish }
    .sortedByDescending { it.lengthMeters }
    .map { it.street }
    .distinct()
    .take(2)
    .joinToString(", ")

@Composable
private fun distanceText(meters: Double): String {
    val (value, unit) = dashDistance(meters)
    return if (unit == "m") {
        stringResource(R.string.navigate_distance_m, value.toInt())
    } else {
        stringResource(R.string.navigate_distance_km, value)
    }
}

@Composable
private fun durationText(ms: Long): String {
    val minutes = ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)
    return if (minutes < 60) stringResource(R.string.nav_minutes, minutes)
    else stringResource(R.string.nav_hours_minutes, minutes / 60, minutes % 60)
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = modifier)

@Composable
private fun Supporting(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PrimaryButton(label: Int, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) { Text(stringResource(label)) }

internal val previewDestination = SavedPlace("Nhà Thờ Đức Bà", "Phường Bến Nghé, Quận 1", GeoPoint(10.7798, 106.699))
internal val previewRoute = Route.of(
    geometry = listOf(GeoPoint(10.7722, 106.6975), GeoPoint(10.7760, 106.6983), GeoPoint(10.7799, 106.6991)),
    distanceMeters = 1767.6,
    timeMs = 213_318,
    instructions = listOf(
        RawInstruction(2, "rẽ phải vào Lý Tự Trọng", "Lý Tự Trọng", 667.0, 80_040),
        RawInstruction(-2, "rẽ trái vào Đường Pasteur", "Đường Pasteur", 1100.6, 133_278),
        RawInstruction(4, "Kết thúc!", "", 0.0, 0),
    ),
)
private val previewSlowRoute = Route.of(
    geometry = previewRoute.geometry,
    distanceMeters = 2140.0,
    timeMs = 402_000,
    instructions = listOf(
        RawInstruction(0, "đi thẳng vào Nam Kỳ Khởi Nghĩa", "Nam Kỳ Khởi Nghĩa", 2140.0, 402_000),
        RawInstruction(4, "Kết thúc!", "", 0.0, 0),
    ),
    traffic = listOf(TrafficSpan(0, 2, Congestion.HEAVY)),
)
internal val previewRoutes = NavState.Preview(previewDestination, listOf(previewRoute, previewSlowRoute))
internal val previewActive = previewRoute.maneuvers.let { m ->
    NavState.Active(
        previewDestination,
        previewRoute,
        NavProgress(m[0], m[1], 40.0, 1_100.0, 130_000, 2.0, offRoute = false, arrived = false, snapped = previewDestination.point),
        rerouting = false,
    )
}

@PreviewLightDark
@Composable
private fun RouteOptionsPreview() {
    NmaxConnectTheme { Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { NavPanel(previewRoutes, onAction = {}) } }
}

@PreviewLightDark
@Composable
private fun GuidancePreview() {
    NmaxConnectTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp)) {
                ManeuverBanner(previewActive)
                NavPanel(previewActive, onAction = {})
            }
        }
    }
}
