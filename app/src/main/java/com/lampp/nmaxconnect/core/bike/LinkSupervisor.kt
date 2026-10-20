package com.lampp.nmaxconnect.core.bike

import android.util.Log
import com.lampp.nmaxconnect.core.bike.ccu.CcuAuthRejected
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The reconnect loop both bike links share: [connectAndServe] (true = reached Ready, which resets the backoff), then on
 * its end or an IOException back off ([BikeLinkRepository.reconnectDelayMs]) and go again, until cancelled.
 * Returns only when Bluetooth permission is missing; a [CcuAuthRejected] is final and propagates.
 */
@Suppress("SwallowedException") // no permission: the caller shows MissingPermission
internal suspend fun superviseLink(
    tag: String,
    hasPermission: () -> Boolean,
    onConnecting: (attempt: Int) -> Unit,
    onRetrying: (inMs: Long, reason: String) -> Unit,
    connectAndServe: suspend () -> Boolean,
) {
    var failures = 0
    while (true) {
        if (!hasPermission()) return
        onConnecting(failures + 1)
        val reason = try {
            if (connectAndServe()) failures = 0
            "link closed"
        } catch (e: CcuAuthRejected) {
            throw e
        } catch (e: IOException) {
            e.message ?: e.javaClass.simpleName
        } catch (e: SecurityException) {
            return
        }
        failures++
        val wait = BikeLinkRepository.reconnectDelayMs(failures)
        Log.i(tag, "link down ($reason); retry #$failures in $wait ms")
        onRetrying(wait, reason)
        delay(wait)
    }
}

/**
 * Runs [serve] (blocking socket reads) on [io] next to the coroutines [alongside] launches (state mirrors), and closes
 * [socket] when it ends or is cancelled: a blocking RFCOMM read ignores cancellation; closing the socket unblocks it.
 */
internal suspend fun serveSocket(
    socket: Closeable,
    io: CoroutineDispatcher,
    alongside: CoroutineScope.() -> Unit,
    serve: suspend () -> Unit,
) {
    try {
        coroutineScope {
            val closeOnCancel = launch {
                try { awaitCancellation() } finally { runCatching { socket.close() } }
            }
            val side = launch { alongside() }
            try {
                withContext(io) { serve() }
            } finally {
                side.cancel()
                closeOnCancel.cancel()
            }
        }
    } finally {
        runCatching { socket.close() }
    }
}
