package build.raft.app.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftNotificationLaunchTest {

    @Test
    fun handsUpdateDownloadKeyUsesReleaseIdentityOnly() {
        val firstKey =
            handsUpdateDownloadKey(
                slug = "raft-android",
                channel = "preview",
                versionCode = 1_000_100L,
                arch = "arm64-v8a"
            )
        val secondKey =
            handsUpdateDownloadKey(
                slug = "raft-android",
                channel = "preview",
                versionCode = 1_000_100L,
                arch = "arm64-v8a"
            )

        assertEquals("raft-android|preview|1000100|arm64-v8a", firstKey)
        assertEquals(firstKey, secondKey)
    }

    @Test
    fun androidHostNavigationExclusionStaysOffForEdgeToEdgeHost() {
        assertFalse(RaftCmpActivity.androidHostExcludesNavigationBarForMode(2))
        assertFalse(RaftCmpActivity.androidHostExcludesNavigationBarForMode(1))
        assertFalse(RaftCmpActivity.androidHostExcludesNavigationBarForMode(0))
        assertFalse(RaftCmpActivity.androidHostExcludesNavigationBarForMode(null))
    }

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
    fun feedbackShareEnvironmentOmitsDisplayAndSocketDetails() {
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
                        "socketStatusLabel" to "active"
                    ),
                recentLogs = "",
                hasFallbackDiagnosticsLogs = false
            )

        val feedbackEnvironment = settingsFeedbackShareEnvironmentSection(exportText)

        listOf("App:", "Version:", "Version code:", "Build:", "Source:", "Platform:", "SDK:", "Device:", "Model:")
            .forEach { label -> assertTrue("missing $label", feedbackEnvironment.contains(label)) }
        listOf("Package:", "Install time:", "Screen:", "Density:", "Font scale:", "Session:", "Signed in user:", "Selected server:", "Socket:")
            .forEach { label -> assertFalse("feedback copy included $label", feedbackEnvironment.contains(label)) }
    }

    @Test
    fun feedbackShareTextListsEachSubmittedAttachmentOnItsOwnLine() {
        val shareText =
            formatHandsFeedbackShareText(
                ticketId = "ticket-1234567890",
                description = "The app froze.",
                environment = "App: Raft",
                utcTime = "2026-07-13T15:00:00Z",
                localTime = "2026-07-13 23:00:00 +0800",
                feedbackAppSlug = "raft-android",
                feedbackChannel = "main",
                updateRoute =
                    HandsUpdateRoute(
                        mode = HANDS_UPDATE_MODE_FULL_APK,
                        appSlug = "raft-android",
                        channel = "main",
                        deltaEnabled = false
                    ),
                deviceId = "install-123",
                attachmentNames =
                    listOf(
                        "/tmp/logcat.txt",
                        "screenshots\\home.png",
                        "bad\r\nname.jpg"
                    )
            )

        assertTrue(
            shareText.contains(
                "Ticket: ticket-1234567890\n" +
                    "attachments:\n" +
                    "logcat.txt\n" +
                    "home.png\n" +
                    "bad name.jpg\n" +
                    "Submitted: 2026-07-13T15:00:00Z"
            )
        )
        assertEquals(
            listOf("logcat.txt", "home.png", "bad name.jpg"),
            handsFeedbackAttachmentNames(
                listOf("/tmp/logcat.txt", "screenshots\\home.png", "bad\r\nname.jpg")
            )
        )
    }

    @Test
    fun feedbackShareTextKeepsLegacyShapeWhenNoAttachmentsWereSubmitted() {
        val shareText =
            formatHandsFeedbackShareText(
                ticketId = "ticket-1234567890",
                description = "No file attached.",
                environment = "App: Raft",
                utcTime = "2026-07-13T15:00:00Z",
                localTime = "2026-07-13 23:00:00 +0800",
                feedbackAppSlug = "raft-android",
                feedbackChannel = "main",
                updateRoute =
                    HandsUpdateRoute(
                        mode = HANDS_UPDATE_MODE_FULL_APK,
                        appSlug = "raft-android",
                        channel = "main",
                        deltaEnabled = false
                    ),
                deviceId = "install-123",
                attachmentNames = emptyList()
            )

        assertFalse(shareText.contains("attachments:"))
        assertTrue(shareText.contains("Ticket: ticket-1234567890\nSubmitted:"))
    }

    @Test
    fun feedbackShareTextDistinguishesFeedbackAndUpdateRoutes() {
        val shareText =
            formatHandsFeedbackShareText(
                ticketId = "ticket-1234567890",
                description = "The update page failed.",
                environment = "App: Raft",
                utcTime = "2026-07-13T15:00:00Z",
                localTime = "2026-07-13 23:00:00 +0800",
                feedbackAppSlug = "raft-android",
                feedbackChannel = "debug",
                updateRoute =
                    HandsUpdateRoute(
                        mode = HANDS_UPDATE_MODE_ALPHA_DELTA,
                        appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                        channel = HANDS_DELTA_ALPHA_CHANNEL,
                        deltaEnabled = true
                    ),
                deviceId = "device-123",
                attachmentNames = emptyList()
            )

        assertTrue(shareText.contains("Hands feedback route: slug=raft-android, channel=debug"))
        assertTrue(
            shareText.contains(
                "SlockHandsUpdate request: mode=alpha_delta, " +
                    "slug=raft-android-alpha-delta, channel=alpha-delta, device_id=device-123"
            )
        )
        assertFalse(shareText.contains("\nHands: slug="))
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
    fun unreadNotificationLaunchesDmThreadFocusedOnMessage() {
        val pageData =
            RaftCmpActivity.buildUnreadNotificationThreadPageData(
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
    fun unreadNotificationAppShellLaunchCarriesThreadIntent() {
        val pageData =
            RaftCmpActivity.buildUnreadNotificationAppShellPageData(
                JSONObject()
                    .put("serverId", "server-1")
                    .put("threadId", "channel-1")
                    .put("channelId", "channel-1")
                    .put("messageId", "msg-42")
                    .put("kind", "channel")
                    .put("category", "channel_unread")
            )

        assertNotNull(pageData)
        assertEquals(RaftCmpActivity.PAGE_APP_SHELL, pageData!!.getString(RaftCmpActivity.KEY_PAGE_NAME))
        assertEquals("notification-app-shell-channel-1-msg-42", pageData.getString("instanceId"))
        assertEquals("server-1", pageData.getString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID))
        assertEquals("google,github", pageData.getString("slockSocialAuthProviders"))
        assertEquals("channel-1", pageData.getString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID))
        assertEquals("channel-1", pageData.getString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_CHANNEL_ID))
        assertEquals("msg-42", pageData.getString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID))
        assertEquals("channel", pageData.getString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_KIND))
        assertEquals("channel_unread", pageData.getString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_CATEGORY))
        assertFalse(pageData.has("slockNotificationThreadLaunchPayload"))
        assertFalse(pageData.has("slockNativeTabBarRenderer"))
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
