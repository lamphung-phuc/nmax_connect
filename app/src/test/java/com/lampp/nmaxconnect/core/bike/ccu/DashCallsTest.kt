package com.lampp.nmaxconnect.core.bike.ccu

import android.telephony.TelephonyManager.CALL_STATE_IDLE
import android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
import android.telephony.TelephonyManager.CALL_STATE_RINGING
import com.lampp.nmaxconnect.core.bike.ccu.DashCallUpdate.Change
import com.lampp.nmaxconnect.core.bike.ccu.DashCallUpdate.Incoming
import org.junit.Assert.assertEquals
import org.junit.Test

class DashCallsTest {
    /** Feeds broadcasts from idle and collects what the dash gets. */
    private fun run(vararg calls: PhoneCall): List<DashCallUpdate> {
        var last = PhoneCall()
        return calls.flatMap { call -> last.next(call).also { last = it.first }.second }
    }

    @Test fun answeredCallLikeYConnect() {
        assertEquals(
            listOf(Incoming("0901", missed = false), Change(started = true), Change(started = false)),
            run(PhoneCall(CALL_STATE_RINGING, "0901"), PhoneCall(CALL_STATE_OFFHOOK), PhoneCall(CALL_STATE_IDLE)),
        )
    }

    @Test fun unansweredCallIsMissed() {
        assertEquals(
            listOf(Incoming("0901", missed = false), Change(started = false), Incoming("0901", missed = true)),
            run(PhoneCall(CALL_STATE_RINGING, "0901"), PhoneCall(CALL_STATE_IDLE)),
        )
    }

    @Test fun numberArrivingInTheSecondBroadcastResends() {
        assertEquals(
            listOf(Incoming("", missed = false), Incoming("0901", missed = false)),
            run(PhoneCall(CALL_STATE_RINGING), PhoneCall(CALL_STATE_RINGING, "0901"), PhoneCall(CALL_STATE_RINGING)),
        )
    }

    @Test fun outgoingCallOnlyStartsAndEnds() {
        assertEquals(
            listOf(Change(started = true), Change(started = false)),
            run(PhoneCall(CALL_STATE_IDLE), PhoneCall(CALL_STATE_OFFHOOK), PhoneCall(CALL_STATE_IDLE)),
        )
    }
}
