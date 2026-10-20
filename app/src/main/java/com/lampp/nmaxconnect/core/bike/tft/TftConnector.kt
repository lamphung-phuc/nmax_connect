package com.lampp.nmaxconnect.core.bike.tft

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import androidx.annotation.RequiresPermission
import com.lampp.nmaxconnect.core.bike.connectRfcommRace
import com.lampp.nmaxconnect.core.di.IoDispatcher
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Opens the TFT RFCOMM link (UUID 0x7220). Like Garmin StreetCross, it both listens
 * ("NaviBluetoothSecure", secure only) and dials the bike, and keeps whichever connects first.
 * Only the paired bike's MAC is accepted on the listening side.
 */
class TftConnector @Inject constructor(
    private val adapter: BluetoothAdapter?,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(bikeMac: String, timeoutMs: Long = CONNECT_TIMEOUT_MS): BluetoothSocket =
        withContext(ioDispatcher) {
            val bt = adapter ?: throw IOException("No Bluetooth adapter")
            connectRfcommRace(bt, bikeMac, TFT_UUID, TFT_UUID, SERVICE_NAME, timeoutMs)
        }

    companion object {
        val TFT_UUID: UUID = UUID.fromString("00007220-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "NaviBluetoothSecure"
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
