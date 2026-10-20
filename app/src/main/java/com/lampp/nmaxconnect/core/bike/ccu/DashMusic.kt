package com.lampp.nmaxconnect.core.bike.ccu

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import com.lampp.nmaxconnect.BuildConfig
import com.lampp.nmaxconnect.core.protocol.ccu.CcuEvent
import com.lampp.nmaxconnect.core.protocol.ccu.CcuMessages
import com.lampp.nmaxconnect.core.protocol.ccu.CcuMusicState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/** The phone's active player in dash terms (cmd 278). */
internal data class NowPlaying(
    val artist: String,
    val album: String,
    val title: String,
    val durationSec: Int,
    val state: Int,
    val rate: Float,
    val elapsedSec: Int,
) {
    fun payload() = CcuMessages.musicMetaData(artist, album, title, durationSec, state, rate, elapsedSec)

    /** Elapsed moves every second; the dash extrapolates it, so only a track/state change is worth a resend. */
    fun sameAs(other: NowPlaying?) = other != null && copy(elapsedSec = 0) == other.copy(elapsedSec = 0)

    companion object {
        val NOTHING = NowPlaying("", "", "", 0, CcuMusicState.UNAVAILABLE, 1f, 0)
    }
}

/** Android PlaybackState → dash state, as Y-Connect's MusicMetaDataEntity.convertPlaybackState. */
internal fun dashMusicState(androidState: Int): Int = when (androidState) {
    PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    -> CcuMusicState.PAUSED
    PlaybackState.STATE_PLAYING -> CcuMusicState.PLAYING
    PlaybackState.STATE_FAST_FORWARDING -> CcuMusicState.FAST_FORWARDING
    PlaybackState.STATE_REWINDING -> CcuMusicState.REWINDING
    else -> CcuMusicState.UNAVAILABLE
}

/**
 * Now playing on the dash (278) and the dash's music buttons (279) while the CCU is up.
 * Reading media sessions needs notification access (same switch as dash notifications).
 */
@Singleton
class DashMusic @Inject constructor(
    @ApplicationContext context: Context,
    private val ccu: CcuLinkRepository,
) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listener = ComponentName(context, DashNotificationListener::class.java)

    /** Y-Connect's cadence: check once a second, send only when something changed. */
    suspend fun run() = coroutineScope {
        launch { ccu.events.filterIsInstance<CcuEvent.MusicControl>().collect { press(it.button) } }
        launch { ccu.events.filterIsInstance<CcuEvent.VolumeControl>().collect { volume(it.op) } }
        // Bike-test aid: everything the dash sends except the 100 ms data pushes and keep-alives.
        if (BuildConfig.DEBUG) {
            launch {
                ccu.events.collect {
                    if (it !is CcuEvent.VehicleInformation && it !is CcuEvent.AliveCheck) Log.d(TAG, "CCU event: $it")
                }
            }
        }
        var sent: NowPlaying? = null
        while (true) {
            delay(TICK_MS)
            if ((ccu.status.value as? CcuStatus.Linked)?.state !is CcuLinkState.Ready) {
                sent = null // a new link gets the current track again
                continue
            }
            val now = snapshot()
            if (!now.sameAs(sent)) sent = now.takeIf { ccu.send(now.payload()) }
        }
    }

    /** Y-Connect toggles play/pause for both buttons; we toggle from the player's real state. */
    private fun press(button: Int) {
        val code = when (button) {
            CcuEvent.MusicControl.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            CcuEvent.MusicControl.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> if (controller()?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                KeyEvent.KEYCODE_MEDIA_PAUSE
            } else {
                KeyEvent.KEYCODE_MEDIA_PLAY
            }
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Dash volume buttons; DashPhoneStatus pushes the new level on its next tick. */
    private fun volume(op: Int) {
        val (stream, direction) = when (op) {
            CcuEvent.VolumeControl.CALL_UP -> callStream(audio) to AudioManager.ADJUST_RAISE
            CcuEvent.VolumeControl.CALL_DOWN -> callStream(audio) to AudioManager.ADJUST_LOWER
            CcuEvent.VolumeControl.MUTE -> AudioManager.STREAM_MUSIC to AudioManager.ADJUST_TOGGLE_MUTE
            CcuEvent.VolumeControl.MEDIA_UP -> mediaStream() to AudioManager.ADJUST_RAISE
            CcuEvent.VolumeControl.MEDIA_DOWN -> mediaStream() to AudioManager.ADJUST_LOWER
            CcuEvent.VolumeControl.MIC_MUTE -> {
                // Honoured by most phones during a call; Android reserves it for telephony apps, so some ignore it.
                audio.isMicrophoneMute = !audio.isMicrophoneMute
                return
            }
            else -> return
        }
        audio.adjustStreamVolume(stream, direction, 0)
        val level = "${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)}"
        Log.d(TAG, "Volume op $op (audio mode ${audio.mode}): stream $stream now $level")
    }

    /**
     * The dash only knows about calls we announce (280/308), so during a Zalo/Messenger call it still sends
     * media ± (bike log 2026-10-17). In a call or VoIP call those go to the call stream, as the phone's own keys do.
     */
    private fun mediaStream() = when (audio.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> callStream(audio)
        else -> AudioManager.STREAM_MUSIC
    }

    /** First playing session, else the first one (Y-Connect's choice); null without notification access. */
    private fun controller(): MediaController? = try {
        val active = sessions.getActiveSessions(listener)
        active.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: active.firstOrNull()
    } catch (e: SecurityException) {
        null
    }

    private fun snapshot(): NowPlaying {
        val c = controller() ?: return NowPlaying.NOTHING
        val m = c.metadata
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val durationSec = ((m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000).toInt().coerceAtLeast(0)
        if (artist == null && album == null && title == null && durationSec == 0) return NowPlaying.NOTHING
        val ps = c.playbackState
        return NowPlaying(
            artist = artist.orEmpty(),
            album = album.orEmpty(),
            title = title.orEmpty(),
            durationSec = durationSec,
            state = dashMusicState(ps?.state ?: PlaybackState.STATE_NONE),
            rate = ps?.playbackSpeed ?: 1f,
            elapsedSec = ((ps?.position ?: 0L) / 1000).toInt().coerceAtLeast(0),
        )
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val TAG = "DashMusic"
    }
}

/**
 * Y-Connect's call volume stream: Bluetooth SCO while a headset carries call audio, else voice call. One rule for the
 * dash volume buttons (DashMusic) and the level shown on the dash (DashPhoneStatus), so the two agree.
 */
@Suppress("DEPRECATION")
internal fun callStream(audio: AudioManager) = if (audio.isBluetoothScoOn) STREAM_BLUETOOTH_SCO else AudioManager.STREAM_VOICE_CALL

private const val STREAM_BLUETOOTH_SCO = 6 // AudioManager's hidden STREAM_BLUETOOTH_SCO, as Y-Connect uses it
