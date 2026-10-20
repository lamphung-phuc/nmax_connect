package com.lampp.nmaxconnect.core.bike.ccu

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import com.lampp.nmaxconnect.core.protocol.ccu.CcuMessages
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/** Signal bars (0..4) → 316 wire value; 0 = no service (airplane mode or SIM not ready), as Y-Connect. */
internal fun dashSignal(level: Int?, inService: Boolean): Int = if (!inService || level == null) 0 else level.coerceIn(0, 4) + 1

/** PowerManager thermal status → 0 low (0-2), 1 middle (3), 2 high (4-6), as Y-Connect. */
internal fun dashThermal(status: Int): Int = when (status) {
    3 -> 1
    in 4..6 -> 2
    else -> 0
}

internal fun percent(value: Int, max: Int): Int = if (max <= 0) 0 else value * 100 / max

/** Phone battery, volumes, signal, heat and headset on the dash (316), sent when they change. */
@Singleton
class DashPhoneStatus @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ccu: CcuLinkRepository,
) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    /** Y-Connect's cadence: check once a second, send only what changed. */
    suspend fun run() {
        var sent: ByteArray? = null
        while (true) {
            delay(TICK_MS)
            if ((ccu.status.value as? CcuStatus.Linked)?.state !is CcuLinkState.Ready) {
                sent = null // a new link gets the current state again
                continue
            }
            val now = snapshot()
            if (!now.contentEquals(sent)) sent = now.takeIf { ccu.sendControl(now) }
        }
    }

    private fun snapshot(): ByteArray {
        val callStream = callStream(audio)
        val inService = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 0 &&
            telephony.simState == TelephonyManager.SIM_STATE_READY
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) telephony.signalStrength?.level else null
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else 0
        val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        return CcuMessages.phoneStateCombined(
            batteryPct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = battery.isCharging,
            callVolumePct = percent(audio.getStreamVolume(callStream), audio.getStreamMaxVolume(callStream)),
            mediaVolumePct = percent(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
            signal = dashSignal(level, inService),
            thermal = dashThermal(thermal),
            headset = headset,
        )
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
