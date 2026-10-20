package com.lampp.nmaxconnect.core.bike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions the bike links need: the one rule the UI asks for and both links check. Android 12+: nearby
 * devices. Android 10–11: Bluetooth is install-time, but a BLE scan (finding the CCU) returns nothing without precise
 * location, so approximate doesn't count.
 */
val bluetoothPermissions: List<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    else -> emptyList()
}

fun hasBluetoothPermission(context: Context): Boolean = bluetoothPermissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
