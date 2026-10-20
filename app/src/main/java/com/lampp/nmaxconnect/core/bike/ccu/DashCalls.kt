package com.lampp.nmaxconnect.core.bike.ccu

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.lampp.nmaxconnect.core.di.IoDispatcher
import com.lampp.nmaxconnect.core.protocol.ccu.CcuEvent
import com.lampp.nmaxconnect.core.protocol.ccu.CcuMessages
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Phone call state (TelephonyManager.CALL_STATE_*) plus the caller's digits when known. */
internal data class PhoneCall(val state: Int = TelephonyManager.CALL_STATE_IDLE, val number: String = "")

/** What the dash should hear about: caller info (DDT 280) or pick-up / hang-up (control 308). */
internal sealed interface DashCallUpdate {
    data class Incoming(val number: String, val missed: Boolean) : DashCallUpdate
    data class Change(val started: Boolean) : DashCallUpdate
}

/**
 * Y-Connect's call state machine (LinkCardActionCreator.onCallStateChanged): ringing → 280, off-hook → 308
 * started, idle → 308 ended plus a missed-call 280 if it was still ringing. Android sends the ringing broadcast
 * twice when we can read the call log (with and without the number), so a number arriving late resends 280.
 */
internal fun PhoneCall.next(now: PhoneCall): Pair<PhoneCall, List<DashCallUpdate>> {
    if (now.state == state) {
        val lateNumber = state == TelephonyManager.CALL_STATE_RINGING && number.isEmpty() && now.number.isNotEmpty()
        return if (lateNumber) now to listOf(DashCallUpdate.Incoming(now.number, missed = false)) else this to emptyList()
    }
    return now to when (now.state) {
        TelephonyManager.CALL_STATE_RINGING -> listOf(DashCallUpdate.Incoming(now.number, missed = false))
        TelephonyManager.CALL_STATE_OFFHOOK -> listOf(DashCallUpdate.Change(started = true))
        else -> listOfNotNull(
            DashCallUpdate.Change(started = false),
            DashCallUpdate.Incoming(now.number.ifEmpty { number }, missed = true).takeIf { state == TelephonyManager.CALL_STATE_RINGING },
        )
    }
}

/**
 * Incoming calls on the dash (280/308) and its answer / reject / hang-up buttons (281) while the CCU is up.
 * Needs READ_PHONE_STATE; the number needs READ_CALL_LOG, the name READ_CONTACTS, the buttons ANSWER_PHONE_CALLS
 * (Settings → "Calls on the dash"). Without the number the dash still shows that a call is ringing.
 */
@Singleton
class DashCalls @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ccu: CcuLinkRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    suspend fun run() = coroutineScope {
        launch { ccu.events.filterIsInstance<CcuEvent.CallControl>().collect { press(it.action) } }
        val ready = ccu.status.map { (it as? CcuStatus.Linked)?.state is CcuLinkState.Ready }.distinctUntilChanged()
        var last = PhoneCall()
        // A new link starts from idle, so a call already ringing or in progress is announced (Y-Connect's initCurrentCallState).
        combine(phoneCalls(), ready) { call, isReady -> call.takeIf { isReady } }.collect { call ->
            if (call == null) {
                last = PhoneCall()
                return@collect
            }
            val (next, updates) = last.next(call)
            last = next
            updates.forEach { send(it) }
        }
    }

    private suspend fun send(update: DashCallUpdate) {
        when (update) {
            is DashCallUpdate.Change -> ccu.sendControl(CcuMessages.callChangeNotification(update.started))
            is DashCallUpdate.Incoming -> {
                val name = contactName(update.number) ?: update.number
                ccu.send(CcuMessages.incomingCallInformation(name, update.number, update.missed))
            }
        }
    }

    /** Ringing broadcasts; delivered only while READ_PHONE_STATE is granted (checked by the system per broadcast). */
    @SuppressLint("MissingPermission")
    private fun phoneCalls(): Flow<PhoneCall> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    else -> TelephonyManager.CALL_STATE_IDLE
                }

                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty().filter(Char::isDigit)
                trySend(PhoneCall(state, number))
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED, // system broadcast
        )
        @Suppress("DEPRECATION")
        send(PhoneCall(if (granted(Manifest.permission.READ_PHONE_STATE)) telephony.callState else TelephonyManager.CALL_STATE_IDLE))
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /** Contact display name for [number], null when unknown or not permitted (Y-Connect then shows the number). */
    private suspend fun contactName(number: String): String? {
        if (number.isEmpty() || !granted(Manifest.permission.READ_CONTACTS)) return null
        return withContext(ioDispatcher) {
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
        }
    }

    /** Reject and end are the same for Telecom: endCall drops a ringing call or the active one. */
    @SuppressLint("MissingPermission")
    private fun press(action: Int) {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return
        try {
            when (action) {
                CcuEvent.CallControl.ANSWER -> telecom.acceptRingingCall()
                // shortcut: endCall needs API 28; on 26-27 the dash's reject / hang-up does nothing.
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    telecom.endCall()
                }
            }
        } catch (e: SecurityException) {
            // permission revoked between the check and the call
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
