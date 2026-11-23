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
        val serverName: String?,
        val publicTitle: String,
        val publicText: String
    ) {
        val all get() = listOfNotNull(title, text, bigText, serverName)
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
        // A conversation with a known sender is a MessagingStyle: its title is the conversation (a
        // DM's: the sender), its expanded text the message lines "Sender: text".
        val messaging = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        return Shown(
            title =
                messaging?.let { it.conversationTitle ?: it.messages.last().person?.name }?.toString()
                    ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
            text = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString(),
            bigText =
                messaging?.messages?.joinToString("\n") { message ->
                    if (messaging.isGroupConversation) "${message.person?.name}: ${message.text}" else message.text.toString()
                } ?: notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
            serverName = notification.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME),
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
