package build.raft.app.shared

import android.app.Application
import android.app.Notification
import androidx.core.app.NotificationCompat
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.buildSystemUnreadNotificationRequestFromPush
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.ServerId
import build.raft.app.push.raftFcmUnreadNotificationRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Builds the real notification through [AndroidUnreadNotificationPoster.buildUnreadNotification] for
 * both entry points, the FCM data message and the local realtime request of the no-GMS path, and reads
 * back what Android would display: title, collapsed text, expanded big text and the lock-screen public
 * version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidUnreadNotificationPosterPreviewTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val redactedModes = listOf(PushPreviewMode.SenderOnly, PushPreviewMode.Hidden)

    @Test
    fun senderOnlyAndHiddenNeverShowMessageContentOnAnyPathOrSurface() {
        for (mode in redactedModes) {
            for (mentioned in listOf(false, true)) {
                for ((shape, request) in requestsFor(mode, mentioned)) {
                    val shown = render(request, mode)
                    val where = "mode=${mode.wireName} mentioned=$mentioned shape=$shape"
                    for (surface in shown.all) {
                        assertFalse("$where leaked the message: $surface", surface.contains(MESSAGE_TEXT))
                        if (mode == PushPreviewMode.Hidden) {
                            for (name in listOf(SENDER, CHANNEL, SERVER)) {
                                assertFalse("$where leaked '$name': $surface", surface.contains(name))
                            }
                        }
                    }
                    for (surface in shown.public) {
                        for (name in listOf(MESSAGE_TEXT, SENDER, CHANNEL, SERVER)) {
                            assertFalse("$where public version leaked '$name': $surface", surface.contains(name))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun redactedTextIsTheLocalizedPreviewStringOnBothPaths() {
        val serverHidden = render(fcmRedacted(PushPreviewMode.Hidden, mentioned = true), PushPreviewMode.Full)
        assertEquals("Raft", serverHidden.title)
        assertEquals("You were mentioned", serverHidden.text)
        assertEquals("You were mentioned", serverHidden.bigText)

        val serverSenderOnly = render(fcmRedacted(PushPreviewMode.SenderOnly, mentioned = false), PushPreviewMode.Full)
        assertEquals("#$CHANNEL", serverSenderOnly.title)
        assertEquals("$SENDER sent a message", serverSenderOnly.text)
        assertEquals("$SENDER sent a message", serverSenderOnly.bigText)

        val localSenderOnly = render(localRequest(mentioned = true), PushPreviewMode.SenderOnly)
        assertEquals("#$CHANNEL", localSenderOnly.title)
        assertEquals("$SENDER mentioned you", localSenderOnly.text)
        assertEquals("$SENDER mentioned you", localSenderOnly.bigText)

        val localHidden = render(localRequest(mentioned = false), PushPreviewMode.Hidden)
        assertEquals("Raft", localHidden.title)
        assertEquals("You have a new message", localHidden.text)
        assertEquals("You have a new message", localHidden.bigText)
    }

    @Test
    fun aHiddenDeviceOverridesASenderOnlyPushTheServerSentBeforeItCaughtUp() {
        for (mentioned in listOf(false, true)) {
            val shown = render(fcmRedacted(PushPreviewMode.SenderOnly, mentioned), PushPreviewMode.Hidden)
            assertEquals("Raft", shown.title)
            val expected = if (mentioned) "You were mentioned" else "You have a new message"
            assertEquals(expected, shown.text)
            assertEquals(expected, shown.bigText)
            for (surface in shown.all + shown.public) {
                for (name in listOf(MESSAGE_TEXT, SENDER, CHANNEL, SERVER)) {
                    assertFalse("mentioned=$mentioned leaked '$name': $surface", surface.contains(name))
                }
            }
        }
    }

    @Test
    fun aSenderOnlyDeviceRedactsAFullContentPush() {
        // FCM data has no senderName, so the local sender_only branch cannot name anyone: content-free body.
        val fcm = render(raftFcmUnreadNotificationRequest(fcmFull()), PushPreviewMode.SenderOnly)
        assertEquals("#$CHANNEL", fcm.title)
        assertEquals("You have a new message", fcm.text)
        assertEquals("You have a new message", fcm.bigText)
        // A full-content request that names its sender gets the sender_only wording.
        val named = render(raftFcmUnreadNotificationRequest(fcmFull()).put("senderName", SENDER), PushPreviewMode.SenderOnly)
        assertEquals("#$CHANNEL", named.title)
        assertEquals("$SENDER sent a message", named.text)
        assertEquals("$SENDER sent a message", named.bigText)
        for (surface in fcm.all + named.all) {
            assertFalse(surface, surface.contains(MESSAGE_TEXT))
        }
    }

    @Test
    fun aStricterServerRedactionWinsOverAFullDevice() {
        val shown = render(fcmRedacted(PushPreviewMode.Hidden, mentioned = false), PushPreviewMode.Full)
        assertEquals("Raft", shown.title)
        assertEquals("You have a new message", shown.text)
        assertEquals("You have a new message", shown.bigText)
        val senderOnly = render(fcmRedacted(PushPreviewMode.SenderOnly, mentioned = false), PushPreviewMode.Full)
        assertEquals("$SENDER sent a message", senderOnly.text)
        for (surface in shown.all + senderOnly.all) {
            assertFalse(surface, surface.contains(MESSAGE_TEXT))
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun redactedKeysResolveInTheDeviceLanguage() {
        val shown = render(fcmRedacted(PushPreviewMode.SenderOnly, mentioned = true), PushPreviewMode.Full)
        assertEquals("$SENDER 提到了你", shown.text)
        assertEquals("$SENDER 提到了你", shown.bigText)
    }

    @Test
    fun fullModeShowsTheRequestTextUnchanged() {
        val fcm = fcmFull()
        for ((shape, request) in listOf("fcm" to raftFcmUnreadNotificationRequest(fcm), "local" to localRequest(false))) {
            val shown = render(request, PushPreviewMode.Full)
            assertEquals(shape, request.getString("title"), shown.title)
            assertEquals(shape, request.getString("body"), shown.text)
            assertEquals(shape, request.getString("body"), shown.bigText)
            assertTrue(shape, shown.text.contains(MESSAGE_TEXT))
        }
    }

    @Test
    fun unknownKeysFallBackToTheServerText() {
        val data =
            fcmRedacted(PushPreviewMode.Hidden, mentioned = false).let { request ->
                JSONObject(request.toString())
                    .put("titleKey", "push.preview.future.title")
                    .put("bodyKey", "push.preview.future.body")
            }
        val shown = render(data, PushPreviewMode.Full)
        assertEquals("Raft", shown.title)
        assertEquals("You have a new message", shown.text)
    }

    @Test
    fun perCategoryKeysRenderTheirOwnWording() {
        // slock docs/contracts/notifications.md §1.5: task / agent / reminder / notice keys.
        val cases =
            listOf(
                Triple("task_status", "push.preview.hidden.task", "You have a task update"),
                Triple("agent_attention", "push.preview.hidden.agent", "An agent needs your attention"),
                Triple("reminder", "push.preview.hidden.reminder", "A reminder is due"),
                Triple("system_notice", "push.preview.hidden.notice", "You have a new notice"),
            )
        for ((category, key, expected) in cases) {
            val data =
                fcmFull().toMutableMap().apply {
                    put("category", category)
                    put("previewMode", "hidden")
                    put("title", "Raft")
                    put("body", "English fallback")
                    put("titleKey", "push.preview.hidden.title")
                    put("bodyKey", key)
                    put("bodyArgs", "[]")
                }
            val shown = render(raftFcmUnreadNotificationRequest(data), PushPreviewMode.Full)
            assertEquals(category, expected, shown.text)
            assertEquals(category, expected, shown.publicText)
        }
        val senderOnly =
            fcmFull().toMutableMap().apply {
                put("category", "agent_attention")
                put("previewMode", "sender_only")
                put("body", "$SENDER needs your attention")
                put("bodyKey", "push.preview.sender_only.agent")
                put("bodyArgs", """["$SENDER"]""")
            }
        assertEquals("$SENDER needs your attention", render(raftFcmUnreadNotificationRequest(senderOnly), PushPreviewMode.Full).text)
    }

    @Test
    fun aRawReminderRedactedOnTheDeviceUsesTheReminderWording() {
        val raw = fcmFull().toMutableMap().apply { put("category", "reminder") }
        val shown = render(raftFcmUnreadNotificationRequest(raw), PushPreviewMode.Hidden)
        assertEquals("A reminder is due", shown.text)
        assertFalse(shown.text.contains(MESSAGE_TEXT))
    }

    @Test
    fun aDirectMessageIsNeverMentionedYou() {
        val raw = fcmFull().toMutableMap().apply { put("category", "dm") }
        val request = raftFcmUnreadNotificationRequest(raw).put("mentioned", true)
        assertEquals("You have a new message", render(request, PushPreviewMode.Hidden).text)
    }

    @Test
    fun theNotificationStaysPrivateWithAPublicVersion() {
        val notification = build(localRequest(false), PushPreviewMode.Full)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
    }

    private data class Shown(
        val title: String,
        val text: String,
        val bigText: String,
        val publicTitle: String,
        val publicText: String
    ) {
        val all get() = listOf(title, text, bigText)
        val public get() = listOf(publicTitle, publicText)
    }

    private fun build(request: JSONObject, mode: PushPreviewMode): Notification =
        AndroidUnreadNotificationPoster.buildUnreadNotification(
            context = context,
            channelId = "test-channel",
            request = request,
            previewMode = mode,
            contentIntent = null,
            whenMillis = 0L
        )

    private fun render(request: JSONObject, mode: PushPreviewMode): Shown {
        val notification = build(request, mode)
        val publicVersion = notification.publicVersion ?: error("no public version")
        return Shown(
            title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
            text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            bigText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
            publicTitle = publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
            publicText = publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
    }

    /** The same mode, as each path delivers it. */
    private fun requestsFor(mode: PushPreviewMode, mentioned: Boolean): List<Pair<String, JSONObject>> =
        listOf(
            // FCM after the server learned this device's choice: already redacted, with keys.
            "fcm_server_redacted" to fcmRedacted(mode, mentioned),
            // FCM sent before the re-registration landed: raw content, the device choice redacts.
            "fcm_raw" to raftFcmUnreadNotificationRequest(fcmFull()),
            // No-GMS realtime path: built on the device from the notification:push event.
            "local_no_gms" to localRequest(mentioned)
        )

    private fun fcmFull(): Map<String, String> =
        mapOf(
            "serverId" to "server-1",
            "channelId" to "channel-1",
            "messageId" to "message-1",
            "kind" to "channel",
            "category" to "RAFT_MESSAGE",
            "title" to "#$CHANNEL",
            "body" to "$SENDER: $MESSAGE_TEXT",
            "notificationKey" to "server-1:channel-1"
        )

    /** What the server's fcmMessageForMobilePush sends for a redacted registration (slock PR #10483). */
    private fun fcmRedacted(mode: PushPreviewMode, mentioned: Boolean): JSONObject {
        val data = fcmFull().toMutableMap()
        data["previewMode"] = mode.wireName
        when (mode) {
            PushPreviewMode.SenderOnly -> {
                data["body"] = if (mentioned) "$SENDER mentioned you" else "$SENDER sent a message"
                data["bodyKey"] =
                    if (mentioned) "push.preview.sender_only.mention" else "push.preview.sender_only.message"
                data["bodyArgs"] = """["$SENDER"]"""
            }
            PushPreviewMode.Hidden -> {
                data["title"] = "Raft"
                data["body"] = if (mentioned) "You were mentioned" else "You have a new message"
                data["titleKey"] = "push.preview.hidden.title"
                data["bodyKey"] = if (mentioned) "push.preview.hidden.mention" else "push.preview.hidden.message"
                data["bodyArgs"] = "[]"
            }
            PushPreviewMode.Full -> Unit
        }
        return raftFcmUnreadNotificationRequest(data)
    }

    private fun localRequest(mentioned: Boolean): JSONObject {
        val request =
            buildSystemUnreadNotificationRequestFromPush(
                push =
                    NotificationPushReceived(
                        serverId = ServerId("server-1"),
                        kind = ConversationKind.Channel,
                        channelId = ChannelId("channel-1"),
                        messageId = MessageId("message-1"),
                        title = "#$CHANNEL",
                        body = "$SENDER: $MESSAGE_TEXT",
                        tag = "tag",
                        serverName = SERVER,
                        channelName = CHANNEL,
                        senderName = SENDER,
                        messagePreview = MESSAGE_TEXT,
                        mentioned = mentioned
                    ),
                isVisibleThread = false,
                isAppInForeground = false
            )!!
        return with(AndroidUnreadNotificationPoster) { request.toJson() }
    }

    private companion object {
        const val MESSAGE_TEXT = "SECRET launch code 4242"
        const val SENDER = "Alice"
        const val CHANNEL = "secret-channel"
        const val SERVER = "Secret Server"
    }
}
