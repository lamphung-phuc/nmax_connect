package com.lampp.nmaxconnect.core.bike.ccu

import android.media.session.PlaybackState
import com.lampp.nmaxconnect.core.protocol.ccu.CcuMusicState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashMusicTest {
    @Test fun mapsAndroidStatesLikeYConnect() {
        assertEquals(CcuMusicState.PLAYING, dashMusicState(PlaybackState.STATE_PLAYING))
        assertEquals(CcuMusicState.PAUSED, dashMusicState(PlaybackState.STATE_PAUSED))
        assertEquals(CcuMusicState.PAUSED, dashMusicState(PlaybackState.STATE_BUFFERING))
        assertEquals(CcuMusicState.PAUSED, dashMusicState(PlaybackState.STATE_SKIPPING_TO_NEXT))
        assertEquals(CcuMusicState.FAST_FORWARDING, dashMusicState(PlaybackState.STATE_FAST_FORWARDING))
        assertEquals(CcuMusicState.REWINDING, dashMusicState(PlaybackState.STATE_REWINDING))
        assertEquals(CcuMusicState.UNAVAILABLE, dashMusicState(PlaybackState.STATE_STOPPED))
        assertEquals(CcuMusicState.UNAVAILABLE, dashMusicState(PlaybackState.STATE_NONE))
    }

    @Test fun elapsedTimeAloneIsNotAChange() {
        val song = NowPlaying("Sơn Tùng", "Album", "Lạc Trôi", 240, CcuMusicState.PLAYING, 1f, 10)
        assertTrue(song.sameAs(song.copy(elapsedSec = 11)))
        assertFalse(song.sameAs(song.copy(state = CcuMusicState.PAUSED)))
        assertFalse(song.sameAs(song.copy(title = "Next")))
        assertFalse(song.sameAs(null))
    }
}
