package build.raft.app.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftNotificationLaunchTest {

    @Test
    fun mergedDiagnosticsExportIncludesDeviceFactsWithoutSensitiveFields() {
        val exportText =
            formatMergedSettingsDiagnosticsExportText(
                about =
                    mapOf(
                        "appName" to "Raft",
                        "versionLabel" to "1.2.3",
                        "versionCodeLabel" to "123",
                        "buildLabel" to "123",
                        "sourceLabel" to "abc123",
                        "packageName" to "build.raft.app",
                        "platformLabel" to "Android 16 / SDK 36",
                        "sdkLabel" to "36",
                        "deviceLabel" to "Google Pixel",
                        "modelLabel" to "Pixel",
                        "installTimeLabel" to "2026-07-09T00:00:00Z",
                        "screenLabel" to "1080x2400 px",
                        "densityLabel" to "2.75 (440 dpi)",
                        "fontScaleLabel" to "1.0",
                        "sessionStatusLabel" to "Signed in",
                        "signedInUserLabel" to "Artin",
                        "selectedServerLabel" to "Botiverse",
                        "socketStatusLabel" to "Host active"
                    ),
                recentLogs = "",
                hasFallbackDiagnosticsLogs = false
            )

        listOf(
            "Version code:",
            "SDK:",
            "Install time:",
            "Screen:",
            "Density:",
            "Font scale:",
            "Model:"
        ).forEach { label ->
            assertTrue("missing $label", exportText.contains(label))
        }
        listOf("token", "credential", "authorization", "bearer", "sessionToken").forEach { sensitive ->
            assertFalse("export leaked $sensitive", exportText.contains(sensitive, ignoreCase = true))
        }
    }

    @Test
    fun unreadNotificationLaunchesChannelThreadFocusedOnMessage() {
        val pageData =
            RaftCmpActivity.buildUnreadNotificationThreadPageData(
                JSONObject()
                    .put("threadId", "channel-1")
                    .put("channelId", "channel-1")
                    .put("messageId", "msg-42")
                    .put("kind", "channel")
                    .put("category", "channel_unread")
            )

        assertNotNull(pageData)
        val payload = JSONObject(pageData!!.getString("payload"))
        assertEquals(RaftCmpActivity.PAGE_THREAD, pageData.getString(RaftCmpActivity.KEY_PAGE_NAME))
        assertEquals("notification-thread-channel-1-msg-42", pageData.getString("instanceId"))
        assertEquals("channel", payload.getString("surfaceKind"))
        assertEquals("channel-1", payload.getString("sourceThreadId"))
        assertEquals("channel-1", payload.getString("streamChannelId"))
        assertEquals("msg-42", payload.getString("focusedMessageId"))
        assertTrue(pageData.has("nowEpochMillis"))
    }

    @Test
    fun unreadNotificationLaunchesReplyThreadWithParentIdentity() {
        val pageData =
            RaftCmpActivity.buildUnreadNotificationThreadPageData(
                JSONObject()
                    .put("threadId", "thread-message-1")
                    .put("channelId", "thread-message-1")
                    .put("parentChannelId", "channel-1")
                    .put("parentMessageId", "parent-msg")
                    .put("messageId", "reply-msg")
                    .put("category", "channel_unread")
            )

        assertNotNull(pageData)
        val payload = JSONObject(pageData!!.getString("payload"))
        assertEquals("thread", payload.getString("surfaceKind"))
        assertEquals("thread-message-1", payload.getString("threadId"))
        assertEquals("thread-message-1", payload.getString("sourceThreadId"))
        assertEquals("thread-message-1", payload.getString("streamChannelId"))
        assertEquals("channel-1", payload.getString("parentChannelId"))
        assertEquals("parent-msg", payload.getString("parentMessageId"))
        assertEquals("parent-msg", payload.getString("originParentMessageId"))
        assertEquals("reply-msg", payload.getString("focusedMessageId"))
    }

    @Test
    fun unreadNotificationWithoutThreadIdentityFallsBackToDefaultLaunch() {
        val pageData =
            RaftCmpActivity.buildUnreadNotificationThreadPageData(
                JSONObject()
                    .put("messageId", "msg-42")
                    .put("category", "channel_unread")
            )

        assertEquals(null, pageData)
    }
}
