package com.lampp.nmaxconnect.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.bluetoothPermissions
import com.lampp.nmaxconnect.core.bike.hasBluetoothPermission

/** Map follow and navigation; either precision counts as granted. */
private val locationPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private val linkPermissions: Array<String> = buildList {
    addAll(bluetoothPermissions)
    addAll(locationPermissions)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

fun hasLocationPermission(context: Context): Boolean = locationPermissions.any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

fun hasNotificationPermission(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Returns a launcher that asks for everything the bike link needs; [onResult] gets whether Bluetooth was granted. */
@Composable
fun rememberLinkPermissionRequest(onResult: (bluetoothGranted: Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onResult(bluetoothPermissions.all { result[it] == true })
    }
    return { launcher.launch(linkPermissions) }
}

/**
 * Returns a function that asks for the missing ones of the given [AppPermission]s one at a time, in list order (one launch of
 * several groups lets Android pick the dialog order). A special-access one opens its settings page and ends the chain.
 * shortcut: the queue is not saved, so a configuration change mid-chain stops it; tapping Grant again resumes.
 */
@Composable
fun rememberPermissionChain(onResult: () -> Unit): (Collection<AppPermission>) -> Unit {
    val context = LocalContext.current
    val queue = remember { ArrayDeque<AppPermission>() }
    lateinit var launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>
    fun askNext() {
        val permission = queue.removeFirstOrNull() ?: return
        if (permission.runtime.isEmpty()) {
            queue.clear()
            openPermissionSettings(context, permission)
        } else {
            launcher.launch(permission.runtime.toTypedArray())
        }
    }
    launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onResult()
        askNext()
    }
    return { wanted ->
        queue.clear()
        queue.addAll(AppPermission.entries.filter { it in wanted } - grantedPermissions(context))
        askNext()
    }
}

/** Call state, caller number, contact name, answer / hang-up from the dash. */
private val callPermissions = listOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.ANSWER_PHONE_CALLS,
)

/** Everything the app asks for, in the order Onboarding and Settings list them. */
enum class AppPermission(val runtime: List<String>) {
    BLUETOOTH(bluetoothPermissions),
    LOCATION(locationPermissions),
    NOTIFICATIONS(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
    ),
    DASH_CALLS(callPermissions),

    /** Notification listener: special access granted in system settings, not by a dialog. */
    DASH_NOTIFICATIONS(emptyList()),
    ;

    companion object {
        /**
         * Without these the app can't link to the bike: onboarding asks for just these and won't continue until both are
         * granted. Notifications and the dash extras are optional, asked for only when the rider opts in.
         */
        val required = listOf(BLUETOOTH, LOCATION)
    }
}

fun grantedPermissions(context: Context): Set<AppPermission> = AppPermission.entries.filterTo(mutableSetOf()) {
    when (it) {
        AppPermission.BLUETOOTH -> hasBluetoothPermission(context)
        AppPermission.LOCATION -> hasLocationPermission(context)
        AppPermission.NOTIFICATIONS -> hasNotificationPermission(context)
        AppPermission.DASH_NOTIFICATIONS -> NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        // READ_PHONE_STATE alone gives call state; the rest only add the name and answer / hang-up.
        AppPermission.DASH_CALLS ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
}

/** Where [permission] is turned on or off by hand: the notification-access screen or the app's settings page. */
fun openPermissionSettings(context: Context, permission: AppPermission) {
    val intent = if (permission == AppPermission.DASH_NOTIFICATIONS) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    context.startActivity(intent)
}

/**
 * Returns a function that asks for one [AppPermission]. Once Android stops showing the dialog (denied twice),
 * the app's settings page opens instead. Callers re-read [grantedPermissions] in [onResult] and on resume.
 */
@Composable
fun rememberPermissionRequest(onResult: () -> Unit): (AppPermission) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val blocked = result.isNotEmpty() && result.values.none { it } && activity != null &&
            result.keys.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (blocked) openPermissionSettings(context, AppPermission.LOCATION) // any runtime permission: app settings page
        onResult()
    }
    return { permission ->
        if (permission.runtime.isEmpty()) {
            openPermissionSettings(context, permission)
        } else {
            launcher.launch(permission.runtime.toTypedArray())
        }
    }
}

/** One [AppPermission] as a [GroupedRow]: icon, name, what it is for, and the caller's [trailing] state. */
@Composable
fun PermissionRow(
    permission: AppPermission,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val (title, body, icon) = when (permission) {
        AppPermission.BLUETOOTH -> Triple(R.string.perm_bluetooth, R.string.perm_bluetooth_body, Icons.Filled.Bluetooth)
        AppPermission.LOCATION -> Triple(R.string.perm_location, R.string.perm_location_body, Icons.Filled.LocationOn)
        AppPermission.NOTIFICATIONS -> Triple(R.string.perm_notifications, R.string.perm_notifications_body, Icons.Filled.Notifications)
        AppPermission.DASH_NOTIFICATIONS ->
            Triple(R.string.settings_dash_notifications, R.string.settings_dash_notifications_on, Icons.AutoMirrored.Filled.Message)
        AppPermission.DASH_CALLS -> Triple(R.string.settings_dash_calls, R.string.settings_dash_calls_on, Icons.Filled.Call)
    }
    GroupedRow(
        headline = stringResource(title),
        supporting = stringResource(body),
        leading = { Icon(icon, contentDescription = null) },
        trailing = trailing,
        onClick = onClick,
        modifier = modifier,
    )
}
