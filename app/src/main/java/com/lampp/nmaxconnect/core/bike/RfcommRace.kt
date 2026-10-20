package com.lampp.nmaxconnect.core.bike

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import androidx.annotation.RequiresPermission
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Both listens on [listenUuid] and dials [dialUuid], keeping whichever RFCOMM socket connects first.
 * Only [mac] is accepted on the listening side. Blocking calls inside: run on the IO dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class) // getCompleted
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
suspend fun connectRfcommRace(
    bt: BluetoothAdapter,
    mac: String,
    dialUuid: UUID,
    listenUuid: UUID,
    serviceName: String,
    timeoutMs: Long,
): BluetoothSocket {
    if (!bt.isEnabled) throw IOException("Bluetooth is off")
    val device = bt.getRemoteDevice(mac)
    val server = bt.listenUsingRfcommWithServiceRecord(serviceName, listenUuid)
    val client = device.createRfcommSocketToServiceRecord(dialUuid)
    val winner = CompletableDeferred<BluetoothSocket>()

    try {
        return coroutineScope {
            // Losers fail silently; only the overall timeout counts as an error.
            val accept = launch { runCatching { acceptFrom(server, mac) }.onSuccess { claim(winner, it) } }
            val dial = launch { runCatching { client.connect(); client }.onSuccess { claim(winner, it) } }
            var socket: BluetoothSocket? = null
            try {
                socket = withTimeoutOrNull(timeoutMs) { winner.await() }
            } finally {
                // Closing the sockets unblocks accept()/connect() so the launches can finish, also when the
                // caller is cancelled (Disconnect while connecting): coroutineScope waits for them either way.
                winner.cancel()
                server.close()
                if (socket !== client) client.close()
                if (socket == null) runCatching { winner.getCompleted().close() } // won but cancelled: don't leak it
            }
            accept.join()
            dial.join()
            socket ?: throw IOException("$serviceName: no RFCOMM link within $timeoutMs ms")
        }
    } finally {
        server.close()
    }
}

private fun acceptFrom(server: BluetoothServerSocket, mac: String): BluetoothSocket {
    while (true) {
        val socket = server.accept()
        if (socket.remoteDevice.address.equals(mac, ignoreCase = true)) return socket
        socket.close() // not our bike
    }
}

/** First socket wins; a late second one is closed instead of leaking. */
private fun claim(winner: CompletableDeferred<BluetoothSocket>, socket: BluetoothSocket) {
    if (!winner.complete(socket)) runCatching { socket.close() }
}
