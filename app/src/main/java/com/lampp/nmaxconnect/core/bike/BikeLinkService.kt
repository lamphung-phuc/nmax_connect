package com.lampp.nmaxconnect.core.bike

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.ccu.CcuLinkRepository
import com.lampp.nmaxconnect.core.bike.ccu.DashCalls
import com.lampp.nmaxconnect.core.bike.ccu.DashMusic
import com.lampp.nmaxconnect.core.bike.ccu.DashPhoneStatus
import com.lampp.nmaxconnect.core.bike.ccu.DashWeather
import com.lampp.nmaxconnect.core.bike.tft.DashPlaces
import com.lampp.nmaxconnect.core.di.MainImmediateDispatcher
import com.lampp.nmaxconnect.core.map.DashStreamer
import com.lampp.nmaxconnect.core.navigation.NavigationService
import com.lampp.nmaxconnect.core.settings.AppSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the bike links (TFT + CCU) and the map stream alive in the background
 * (FGS type connectedDevice, plus location so the map can follow GPS with the screen off).
 */
@AndroidEntryPoint
class BikeLinkService : Service() {

    @Inject lateinit var repository: BikeLinkRepository

    @Inject lateinit var ccuRepository: CcuLinkRepository

    @Inject lateinit var dashCalls: DashCalls

    @Inject lateinit var dashMusic: DashMusic

    @Inject lateinit var dashPhoneStatus: DashPhoneStatus

    @Inject lateinit var dashWeather: DashWeather

    @Inject lateinit var dashPlaces: DashPlaces

    @Inject lateinit var streamer: DashStreamer

    @Inject lateinit var settingsRepository: AppSettingsRepository

    @Inject @MainImmediateDispatcher
    lateinit var mainDispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + mainDispatcher) }
    private var linkJob: Job? = null
    private var ccuJob: Job? = null
    private var notifyJob: Job? = null
    private var streamJob: Job? = null
    private var callsJob: Job? = null
    private var musicJob: Job? = null
    private var phoneStatusJob: Job? = null
    private var weatherJob: Job? = null
    private var placesJob: Job? = null
    private var autoExitJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            exitApp()
            return START_NOT_STICKY
        }
        val bikeMac = intent?.getStringExtra(EXTRA_BIKE_MAC)
        if (intent?.action == ACTION_STOP || bikeMac == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(withLocation = hasLocationPermission())
        } catch (e: SecurityException) {
            try {
                startForeground(withLocation = false) // location FGS can't start from the background (redelivery)
            } catch (e: SecurityException) {
                stopSelf() // Bluetooth permission missing; the UI must request it first
                return START_NOT_STICKY
            }
        }

        linkJob?.cancel()
        linkJob = scope.launch {
            repository.run(bikeMac)
            stopSelf()
        }
        // Separate job under the SupervisorJob: a CCU failure never touches the TFT link, or the reverse.
        // shortcut: assumes the CCU answers on the same paired MAC as the TFT; revisit after the bike test (roadmap Phase 6).
        ccuJob?.cancel()
        ccuJob = scope.launch { ccuRepository.run(bikeMac) }
        if (streamJob == null) streamJob = scope.launch { streamer.run() }
        if (callsJob == null) callsJob = scope.launch { dashCalls.run() }
        if (musicJob == null) musicJob = scope.launch { dashMusic.run() }
        if (phoneStatusJob == null) phoneStatusJob = scope.launch { dashPhoneStatus.run() }
        if (weatherJob == null) weatherJob = scope.launch { dashWeather.run() }
        if (placesJob == null) placesJob = scope.launch { dashPlaces.run() }
        if (autoExitJob == null) autoExitJob = scope.launch { disconnectWhenIdle() }
        if (notifyJob == null) {
            notifyJob = scope.launch {
                repository.state.collect { state ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(state.messageRes()))
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Auto-connect on: drop the bike link (the app stays open) once the TFT link has not been ready for [AUTO_EXIT_AFTER_MS]. */
    private suspend fun disconnectWhenIdle() {
        combine(
            settingsRepository.settings.map { it.autoConnect }.distinctUntilChanged(),
            repository.state.map { it is BikeLinkState.Ready }.distinctUntilChanged(),
        ) { auto, ready -> auto && !ready }
            .distinctUntilChanged()
            .collectLatest { idle ->
                if (idle) {
                    delay(AUTO_EXIT_AFTER_MS)
                    stopSelf()
                }
            }
    }

    /** Notification "Disconnect": quit like a force stop. Services are stopped first so nothing gets restarted. */
    private fun exitApp() {
        NavigationService.stop(this)
        stopSelf()
        getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        Process.killProcess(Process.myPid())
    }

    private fun notification(textRes: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.bike_link_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(textRes))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                getString(R.string.action_disconnect),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, BikeLinkService::class.java).setAction(ACTION_EXIT),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun BikeLinkState.messageRes(): Int = when (this) {
        BikeLinkState.Idle, is BikeLinkState.Connecting, BikeLinkState.Handshaking -> R.string.bike_link_connecting
        is BikeLinkState.Ready -> if (streaming) R.string.bike_link_streaming else R.string.bike_link_connected
        is BikeLinkState.Retrying -> R.string.bike_link_retrying
        BikeLinkState.MissingPermission -> R.string.bike_link_missing_permission
    }

    private fun startForeground(withLocation: Boolean) {
        val type = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            withLocation -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(R.string.bike_link_connecting), type)
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "bike_link"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_BIKE_MAC = "bike_mac"
        private const val ACTION_STOP = "com.lampp.nmaxconnect.STOP_BIKE_LINK"
        private const val ACTION_EXIT = "com.lampp.nmaxconnect.EXIT_APP"

        /** Auto-connect: disconnect after this long without a ready TFT link, to save battery. */
        private const val AUTO_EXIT_AFTER_MS = 60_000L

        /** Caller must hold BLUETOOTH_CONNECT (API 31+) before starting. */
        fun start(context: Context, bikeMac: String) = ContextCompat.startForegroundService(
            context,
            Intent(context, BikeLinkService::class.java).putExtra(EXTRA_BIKE_MAC, bikeMac),
        )

        fun stop(context: Context) {
            context.startService(Intent(context, BikeLinkService::class.java).setAction(ACTION_STOP))
        }
    }
}
