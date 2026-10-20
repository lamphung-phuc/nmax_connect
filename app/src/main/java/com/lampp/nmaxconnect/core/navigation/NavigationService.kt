package com.lampp.nmaxconnect.core.navigation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lampp.nmaxconnect.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service (type location) that keeps GPS and the process alive while navigating with the
 * screen off. The work itself runs in [NavigationRepository]; this only holds the foreground state.
 */
@AndroidEntryPoint
class NavigationService : Service() {
    @Inject lateinit var navigator: Navigator

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            navigator.stop() // also stops this service
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.nav_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.nav_notification, intent?.getStringExtra(EXTRA_DESTINATION).orEmpty()))
            .setOngoing(true)
            .addAction(
                0,
                getString(R.string.nav_stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType())
        } catch (e: SecurityException) {
            stopSelf() // location permission revoked; the repository reports NO_LOCATION
        }
        return START_NOT_STICKY // the repository restarts it with the next trip
    }

    private fun fgsType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_DESTINATION = "destination"
        private const val ACTION_STOP = "com.lampp.nmaxconnect.STOP_NAVIGATION"

        fun start(context: Context, destination: String) = ContextCompat.startForegroundService(
            context,
            Intent(context, NavigationService::class.java).putExtra(EXTRA_DESTINATION, destination),
        )

        /** stopService works from the background too (arrival can happen with the screen off). */
        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
