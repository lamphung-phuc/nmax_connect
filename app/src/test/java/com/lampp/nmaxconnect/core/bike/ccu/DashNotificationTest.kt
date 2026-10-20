package com.lampp.nmaxconnect.core.bike.ccu

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashNotificationTest {
    private fun map(
        pkg: String = "com.zing.zalo",
        category: String? = null,
        flags: Int = 0,
        title: String? = "Lan",
        text: String? = "Tối nay đi cafe?",
        bigText: String? = null,
    ) = toDashNotification(pkg, "com.lampp.nmaxconnect", category, flags, 7, title, text, bigText)

    @Test fun messengerTextBecomesDashMessage() {
        assertEquals(DashNotification(7, isCall = false, title = "Lan", detail = "Tối nay đi cafe?"), map())
    }

    @Test fun bigTextWinsAndCallCategoryIsCall() {
        val n = map(pkg = "com.example.dialer", category = Notification.CATEGORY_CALL, bigText = "Full text")
        assertEquals(DashNotification(7, isCall = true, title = "Lan", detail = "Full text"), n)
    }

    @Test fun anyAppWithMessageCategoryIsForwarded() {
        assertEquals("Lan", map(pkg = "com.example.chat", category = Notification.CATEGORY_MESSAGE)?.title)
    }

    @Test fun skipsNoiseOwnAndMedia() {
        assertNull("unknown app, no chat category", map(pkg = "com.example.shop"))
        assertNull("our own notification", map(pkg = "com.lampp.nmaxconnect", category = Notification.CATEGORY_MESSAGE))
        assertNull("media player", map(category = Notification.CATEGORY_TRANSPORT))
        assertNull("ongoing", map(flags = Notification.FLAG_ONGOING_EVENT))
        assertNull("group summary", map(flags = Notification.FLAG_GROUP_SUMMARY))
        assertNull("nothing to show", map(title = " ", text = null))
    }
}
