package com.lampp.nmaxconnect.core.bike.ccu

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.lampp.nmaxconnect.BuildConfig
import com.lampp.nmaxconnect.core.bike.connectRfcommRace
import com.lampp.nmaxconnect.core.di.IoDispatcher
import com.lampp.nmaxconnect.core.protocol.ccu.CcuUuids
import com.lampp.nmaxconnect.core.settings.AppSettingsRepository
import com.lampp.nmaxconnect.core.settings.CcuBle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android side of the CCU link (docs/bike-protocol.md §6.1-6.2): the SPP bootstrap socket and
 * [openGatt] for [CcuSession]. Callers hold BLUETOOTH_CONNECT + BLUETOOTH_SCAN.
 */
class CcuConnector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val settings: AppSettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Dials the CCU (UUID chosen from its classic name, as Y-Connect) while listening on 8911 for it to dial us. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connectSpp(ccuMac: String, timeoutMs: Long = SPP_TIMEOUT_MS): BluetoothSocket = withContext(ioDispatcher) {
        val bt = adapter ?: throw IOException("No Bluetooth adapter")
        val name = bt.getRemoteDevice(ccuMac).name
        // Y-Connect refuses names under 16 chars; we still try 8911, the CCU's own UUID.
        val dialUuid = CcuUuids.sppFor(name) ?: CcuUuids.SPP_YAMAHA.also { Log.w(TAG, "Unexpected CCU name '$name'") }
        connectRfcommRace(bt, ccuMac, dialUuid, CcuUuids.SPP_YAMAHA, BuildConfig.APPLICATION_ID, timeoutMs)
    }

    /** Saved LE address first; on failure or none, scan for `YCCU_<ccuId>`. Bonds if needed. */
    @SuppressLint("MissingPermission")
    suspend fun openGatt(ccuId: String): CcuGatt = withContext(ioDispatcher) {
        val bt = adapter ?: throw IOException("No Bluetooth adapter")
        if (!bt.isEnabled) throw IOException("Bluetooth is off")
        settings.settings.first().ccuBle?.takeIf { it.ccuId == ccuId }?.let { saved ->
            runCatching { return@withContext connect(bt.getRemoteDevice(saved.mac)) }
                .onFailure { Log.w(TAG, "Saved CCU ${saved.mac} failed, rescanning", it) }
        }
        val device = scan(bt, ccuId)
        connect(device).also { settings.setCcuBle(CcuBle(ccuId, device.address)) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(device: BluetoothDevice): CcuGatt {
        bond(device)
        val gatt = AndroidCcuGatt()
        gatt.attach(device.connectGatt(context, false, gatt, BluetoothDevice.TRANSPORT_LE) ?: throw IOException("connectGatt refused"))
        var ready = false
        try {
            ready = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { runCatching { gatt.discovered.await() }.isSuccess } == true
        } finally {
            // Cancelled too (disconnect, link restart): leaked GATT clients make later connects fail with status 133.
            if (!ready) gatt.close()
        }
        if (!ready) throw IOException("CCU GATT not ready within $CONNECT_TIMEOUT_MS ms")
        return gatt
    }

    @SuppressLint("MissingPermission")
    private suspend fun scan(bt: BluetoothAdapter, ccuId: String): BluetoothDevice {
        val scanner = bt.bluetoothLeScanner ?: throw IOException("LE scanner unavailable")
        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (CcuUuids.ccuIdFromBleName(name) == ccuId) found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IOException("LE scan failed: $errorCode"))
            }
        }
        // Unfiltered scans get no results with the screen off (Android 8.1+): phone in pocket.
        val filters = listOf("YCCU_$ccuId", "YSCCU_$ccuId").map { ScanFilter.Builder().setDeviceName(it).build() }
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try {
            return withTimeoutOrNull(SCAN_TIMEOUT_MS) { found.await() } ?: throw IOException("CCU YCCU_$ccuId not advertising")
        } finally {
            scanner.stopScan(callback)
        }
    }

    /** Y-Connect bonds before connecting. The system may show a pairing dialog. */
    @SuppressLint("MissingPermission")
    private suspend fun bond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> done.complete(false)
                }
            }
        }
        // Protected system broadcast: only the OS can send it, so exporting is safe.
        val bondChanges = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, bondChanges, ContextCompat.RECEIVER_EXPORTED)
        try {
            if (device.bondState == BluetoothDevice.BOND_NONE && !device.createBond()) throw IOException("createBond refused")
            if (withTimeoutOrNull(BOND_TIMEOUT_MS) { done.await() } != true) throw IOException("CCU bonding failed")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private companion object {
        const val TAG = "CcuConnector"
        const val SPP_TIMEOUT_MS = 10_000L // Y-Connect BTC_CONNECTING
        const val SCAN_TIMEOUT_MS = 10_000L // BLE_SCANNING 5 s × 2
        const val BOND_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

/** [CcuGatt] over [BluetoothGatt]: one op in flight, each awaited via its callback. */
@SuppressLint("MissingPermission") // CcuConnector's callers hold BLUETOOTH_CONNECT
private class AndroidCcuGatt : BluetoothGattCallback(), CcuGatt {
    private lateinit var gatt: BluetoothGatt
    private val ops = Mutex()

    @Volatile private var pending: CompletableDeferred<Any>? = null
    val discovered = CompletableDeferred<Unit>()
    private val channel = Channel<GattNotification>(Channel.UNLIMITED)
    override val notifications: Flow<GattNotification> = channel.receiveAsFlow()

    fun attach(g: BluetoothGatt) { gatt = g }

    override suspend fun requestMtu(mtu: Int): Int = op { gatt.requestMtu(mtu) }

    override suspend fun enable(uuid: UUID, indicate: Boolean) {
        // Y-Connect only logs a missing characteristic; some CCUs may lack the spare indicate ones.
        val c = characteristic(uuid) ?: run {
            Log.w(TAG, "No characteristic $uuid")
            return
        }
        if (!gatt.setCharacteristicNotification(c, true)) throw IOException("Notify refused for $uuid")
        val d = c.getDescriptor(CcuUuids.CCCD) ?: throw IOException("No CCCD on $uuid")
        val v = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op<Unit> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = v
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(d)
            }
        }
    }

    override suspend fun write(uuid: UUID, value: ByteArray) {
        val c = characteristic(uuid) ?: throw IOException("No characteristic $uuid")
        op<Unit> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(c, value, c.writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(c)
            }
        }
    }

    override suspend fun read(uuid: UUID): ByteArray {
        val c = characteristic(uuid) ?: throw IOException("No characteristic $uuid")
        return op { gatt.readCharacteristic(c) }
    }

    override fun close() {
        channel.close()
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? = gatt.getService(CcuUuids.SERVICE)?.getCharacteristic(uuid)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> op(start: () -> Boolean): T = ops.withLock {
        val result = CompletableDeferred<Any>()
        pending = result
        if (!start()) throw IOException("GATT op refused")
        (withTimeoutOrNull(OP_TIMEOUT_MS) { result.await() } ?: throw IOException("GATT op timed out")) as T
    }

    private fun finish(status: Int, value: Any) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value) else p.completeExceptionally(IOException("GATT status $status"))
    }

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            g.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            val e = IOException("CCU BLE disconnected (status $status)")
            discovered.completeExceptionally(e)
            pending?.completeExceptionally(e)
            channel.close(e)
        }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS && g.getService(CcuUuids.SERVICE) != null) {
            discovered.complete(Unit)
        } else {
            discovered.completeExceptionally(IOException("CCU service missing (status $status)"))
        }
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = finish(status, mtu)
    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(status, Unit)
    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish(status, Unit)

    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
        finish(status, value)

    @Deprecated("API < 33 path")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
        finish(status, c.value ?: ByteArray(0))

    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        channel.trySend(GattNotification(c.uuid, value))
    }

    @Deprecated("API < 33 path")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        channel.trySend(GattNotification(c.uuid, c.value?.copyOf() ?: ByteArray(0)))
    }

    private companion object {
        const val TAG = "CcuGatt"
        const val OP_TIMEOUT_MS = 5_000L
    }
}
