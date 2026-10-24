package com.lampp.nmaxconnect.core.bike.ccu

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.lampp.nmaxconnect.core.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One phone notification as the dash shows it (DDT 303). */
data class DashNotification(val id: Int, val isCall: Boolean, val title: String, val detail: String)

/**
 * Forwards chat/call notifications to the dash while the CCU link is up. Needs the user to grant
 * notification access (Settings → "Notifications on the dash").
 */
@AndroidEntryPoint
class DashNotificationListener : NotificationListenerService() {

    @Inject lateinit var ccu: CcuLinkRepository

    @Inject @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    /** Y-Connect sends each (notification, post time) once; chat apps repost the same key per message. */
    private val sent = LinkedHashSet<String>()

    override fun onListenerConnected() {
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        _connected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val dash = toDashNotification(
            packageName = sbn.packageName,
            ownPackage = packageName,
            category = n.category,
            flags = n.flags,
            id = sbn.id,
            title = n.extras.getCharSequence(Notification.EXTRA_TITLE),
            text = n.extras.getCharSequence(Notification.EXTRA_TEXT),
            bigText = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ) ?: return
        synchronized(sent) {
            if (!sent.add("${sbn.key}:${n.`when`}")) return
            if (sent.size > SENT_MEMORY) sent.remove(sent.first())
        }
        scope.launch { ccu.sendNotification(dash) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SENT_MEMORY = 64

        private val _connected = MutableStateFlow(false)

        /** Android has bound the listener. False with access granted = an OEM autostart block (MIUI, ColorOS, …). */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** Asks Android to bind the listener again, e.g. after the rider lifted the block; no-op when bound or without access. */
        fun rebind(context: Context) {
            if (_connected.value) return
            if (!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) return
            // requestRebind only undoes requestUnbind; toggling the component makes Android re-scan and bind it. While our
            // process runs, OEM autostart blocks allow the bind.
            val component = ComponentName(context, DashNotificationListener::class.java)
            val pm = context.packageManager
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }
}

// shortcut: fixed messenger list instead of Y-Connect's per-app picker (6 apps); add a picker if users ask.
private val MESSENGERS = setOf(
    "com.zing.zalo",
    "com.facebook.orca",
    "com.facebook.mlite",
    "org.telegram.messenger",
    "com.whatsapp",
    "com.viber.voip",
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.android.mms",
)

private val FORWARDED_CATEGORIES = setOf(Notification.CATEGORY_MESSAGE, Notification.CATEGORY_CALL, Notification.CATEGORY_EMAIL)

/**
 * Y-Connect's mapping: category CALL for `call`, else MESSAGE; title = EXTRA_TITLE; detail = BIG_TEXT, else TEXT.
 * Skips our own, ongoing (except a ringing call), group-summary and media (`transport`) notifications. Null = don't forward.
 */
internal fun toDashNotification(
    packageName: String,
    ownPackage: String,
    category: String?,
    flags: Int,
    id: Int,
    title: CharSequence?,
    text: CharSequence?,
    bigText: CharSequence?,
): DashNotification? {
    if (packageName == ownPackage || category == Notification.CATEGORY_TRANSPORT) return null
    if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
    // VoIP ringing (Zalo, Messenger…) is ongoing; the answered call moves to a foreground-service notification.
    val ringing = category == Notification.CATEGORY_CALL && flags and Notification.FLAG_FOREGROUND_SERVICE == 0
    if (flags and Notification.FLAG_ONGOING_EVENT != 0 && !ringing) return null
    if (category !in FORWARDED_CATEGORIES && packageName !in MESSENGERS) return null
    val detail = bigText?.toString()?.takeIf { it.isNotBlank() } ?: text?.toString()?.takeIf { it.isNotBlank() }
    if (title.isNullOrBlank() && detail == null) return null
    return DashNotification(
        id = id,
        isCall = category == Notification.CATEGORY_CALL,
        title = title?.toString() ?: " ",
        detail = detail ?: " ",
    )
}
