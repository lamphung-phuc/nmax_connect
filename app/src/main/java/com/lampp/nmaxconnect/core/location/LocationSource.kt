package com.lampp.nmaxconnect.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lampp.nmaxconnect.core.di.MainDispatcher
import com.lampp.nmaxconnect.core.navigation.Fix
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint(val lat: Double, val lng: Double) {
    /** VietMap REST format: `lat,lng`. */
    fun toQuery() = "$lat,$lng"
}

interface LocationSource {
    /**
     * Best-effort position for ranking search results: the cached fix if any, otherwise one fresh fix
     * (≤ [FRESH_FIX_TIMEOUT_MS]). Null without permission or signal.
     */
    suspend fun current(): GeoPoint?

    /** Fine or coarse location granted. */
    fun hasPermission(): Boolean

    /** Continuous GPS fixes (~1 Hz) for active guidance; fails with SecurityException without permission. */
    fun updates(): Flow<Fix> = emptyFlow()

    companion object {
        const val FRESH_FIX_TIMEOUT_MS = 2_000L
    }
}

private const val UPDATE_INTERVAL_MS = 1_000L

class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : LocationSource {
    private val manager get() = context.getSystemService(LocationManager::class.java)

    @Suppress("MissingPermission") // hasPermission() checked first
    override suspend fun current(): GeoPoint? {
        if (!hasPermission()) return null
        val lm = manager ?: return null
        val cached = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        // Without a cached fix VietMap gets no `focus`: results aren't ranked by proximity and distance is 0.
        val fix = cached ?: freshFix(lm)
        return fix?.let { GeoPoint(it.latitude, it.longitude) }
    }

    @Suppress("MissingPermission")
    private suspend fun freshFix(lm: LocationManager): Location? {
        // shortcut: API 26-29 rely on the cached fix only; add requestSingleUpdate if those devices matter.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.firstOrNull { lm.isProviderEnabled(it) } ?: return null
        return withContext(mainDispatcher) {
            withTimeoutOrNull(LocationSource.FRESH_FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, Executor(Runnable::run)) { cont.resume(it) }
                }
            }
        }
    }

    @Suppress("MissingPermission") // hasPermission() checked first
    override fun updates(): Flow<Fix> = callbackFlow {
        val lm = manager
        if (!hasPermission() || lm == null) throw SecurityException("Location permission missing")
        val listener = LocationListener { loc ->
            trySend(
                Fix(
                    GeoPoint(loc.latitude, loc.longitude),
                    loc.bearing.takeIf { loc.hasBearing() },
                    loc.time,
                    loc.speed.takeIf { loc.hasSpeed() },
                    loc.accuracy.takeIf { loc.hasAccuracy() },
                ),
            )
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }.conflate() // a slow reroute must not leave a backlog of stale fixes

    override fun hasPermission() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}
