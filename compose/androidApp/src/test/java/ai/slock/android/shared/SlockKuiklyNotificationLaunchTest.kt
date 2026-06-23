package ai.slock.android.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockKuiklyNotificationLaunchTest {

    @Test
    fun androidHostNavigationExclusionFollowsNavigationMode() {
        assertFalse(SlockKuiklyActivity.androidHostExcludesNavigationBarForMode(2))
        assertTrue(SlockKuiklyActivity.androidHostExcludesNavigationBarForMode(1))
        assertTrue(SlockKuiklyActivity.androidHostExcludesNavigationBarForMode(0))
        assertFalse(SlockKuiklyActivity.androidHostExcludesNavigationBarForMode(null))
    }

    @Test
    fun unreadNotificationLaunchesChannelThreadFocusedOnMessage() {
        val pageData =
            SlockKuiklyActivity.buildUnreadNotificationThreadPageData(
                JSONObject()
                    .put("threadId", "channel-1")
                    .put("channelId", "channel-1")
                    .put("messageId", "msg-42")
                    .put("category", "channel_unread")
            )

        assertNotNull(pageData)
        val payload = JSONObject(pageData!!.getString("payload"))
        assertEquals("notification-thread-channel-1-msg-42", pageData.getString("instanceId"))
        assertEquals("channel", payload.getString("surfaceKind"))
        assertEquals("channel-1", payload.getString("sourceThreadId"))
        assertEquals("channel-1", payload.getString("streamChannelId"))
        assertEquals("msg-42", payload.getString("focusedMessageId"))
        assertTrue(pageData.has("nowEpochMillis"))
    }

    @Test
    fun unreadNotificationLaunchesDmThreadFocusedOnMessage() {
        val pageData =
            SlockKuiklyActivity.buildUnreadNotificationThreadPageData(
                JSONObject()
                    .put("threadId", "dm-1")
                    .put("messageId", "msg-dm")
                    .put("category", "direct_message")
            )

        assertNotNull(pageData)
        val payload = JSONObject(pageData!!.getString("payload"))
        assertEquals("dm", payload.getString("surfaceKind"))
        assertEquals("dm-1", payload.getString("threadId"))
        assertEquals("dm-1", payload.getString("sourceThreadId"))
        assertEquals("dm-1", payload.getString("streamChannelId"))
        assertEquals("msg-dm", payload.getString("focusedMessageId"))
    }

    @Test
    fun unreadNotificationLaunchesReplyThreadWithParentIdentity() {
        val pageData =
            SlockKuiklyActivity.buildUnreadNotificationThreadPageData(
                JSONObject()
                    .put("threadId", "thread-parent-msg")
                    .put("channelId", "channel-1")
                    .put("parentChannelId", "channel-1")
                    .put("parentMessageId", "parent-msg")
                    .put("messageId", "reply-msg")
                    .put("category", "channel_unread")
            )

        assertNotNull(pageData)
        val payload = JSONObject(pageData!!.getString("payload"))
        assertEquals("thread", payload.getString("surfaceKind"))
        assertEquals("thread-parent-msg", payload.getString("threadId"))
        assertEquals("thread-parent-msg", payload.getString("sourceThreadId"))
        assertEquals("thread-parent-msg", payload.getString("streamChannelId"))
        assertEquals("channel-1", payload.getString("parentChannelId"))
        assertEquals("parent-msg", payload.getString("parentMessageId"))
        assertEquals("parent-msg", payload.getString("originParentMessageId"))
        assertEquals("reply-msg", payload.getString("focusedMessageId"))
    }

    @Test
    fun unreadNotificationWithoutThreadIdentityFallsBackToDefaultLaunch() {
        val pageData =
            SlockKuiklyActivity.buildUnreadNotificationThreadPageData(
                JSONObject()
                    .put("messageId", "msg-42")
                    .put("category", "channel_unread")
            )

        assertEquals(null, pageData)
    }
}
