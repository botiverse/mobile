package build.raft.app.shared

import android.app.Application
import android.app.Notification
import androidx.core.app.NotificationCompat
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftNotificationLocalization
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Banner copy in the Android tray (slock docs/contracts/notifications.md §1.5, Android row of the copy
 * spec; FCM transport fcm.ts and mobile socket mobileSocket.ts @ fbc091209976f3d22d58f0cc65e330599c45bccf):
 * title = the conversation, subtitle = `setSubText`, body in a MessagingStyle line per message,
 * localization keys in the device language with the English fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidNotificationBannerCopyTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun aJoinedTitleIsSplitAndTheSubtitleIsTheSubText() {
        // FCM to an app of key set 1 / 2, and the mobile socket: `title · subtitle`.
        val joined = build(fcm("title" to "Thread in #general · Acme", "subtitle" to "Acme"))
        assertEquals("Thread in #general", titleOf(joined))
        assertEquals("Acme", joined.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        // FCM to key set 3: the plain title and the subtitle on its own.
        val plain = build(fcm("title" to "Thread in #general", "subtitle" to "Acme"))
        assertEquals("Thread in #general", titleOf(plain))
        assertEquals("Acme", plain.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        // A receiver of one server: no subtitle at all.
        assertNull(build(fcm()).extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun keyedPartsRenderInTheDeviceLanguage() {
        val notification =
            build(
                fcm(
                    "title" to "Thread in #general",
                    "titleKey" to "push.title.thread_in_channel",
                    "titleArgs" to """["general"]""",
                    "subtitle" to "DM · Acme",
                    "subtitleKey" to "push.subtitle.dm_server",
                    "subtitleArgs" to """["Acme"]""",
                    "body" to "Alice: Sent 2 attachments",
                    "bodyKey" to "push.body.sender_attachments",
                    "bodyArgs" to """["Alice","2"]"""
                )
            )
        assertEquals("#general 中的消息列", titleOf(notification))
        assertEquals("私信 · Acme", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        // A `push.body.*` key is a full preview's phrase, not a redaction: the sender's line stays.
        val line = messagesOf(notification).single()
        assertEquals("Alice", line.person?.name.toString())
        assertEquals("发送了 2 个附件", line.text.toString())
    }

    @Test
    fun anUnknownKeyFallsBackToThePayloadsEnglish() {
        val notification =
            build(
                fcm(
                    "title" to "Thread in #general",
                    "titleKey" to "push.title.from_a_newer_server",
                    "subtitle" to "Acme",
                    "subtitleKey" to "push.subtitle.from_a_newer_server",
                    "body" to "Alice: hello",
                    "bodyKey" to "push.body.from_a_newer_server",
                    "bodyArgs" to """["Alice"]"""
                )
            )
        assertEquals("Thread in #general", titleOf(notification))
        assertEquals("Acme", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("Alice: hello", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }

    @Test
    @Config(qualifiers = "ja")
    fun theSocketLocalizationRendersLikeFcm() {
        val request =
            buildSystemUnreadNotificationRequestFromPush(
                push =
                    NotificationPushReceived(
                        serverId = ServerId("server-1"),
                        kind = ConversationKind.Thread,
                        channelId = ChannelId("thread-1"),
                        threadId = ChannelId("thread-1"),
                        parentChannelId = ChannelId("general-id"),
                        parentMessageId = MessageId("parent-1"),
                        messageId = MessageId("m-1"),
                        title = "Thread in #general · Acme",
                        subtitle = "Acme",
                        body = "Alice: (no text)",
                        tag = "n-1",
                        senderName = "Alice",
                        localization =
                            RaftNotificationLocalization(
                                titleKey = "push.title.thread_in_channel",
                                titleArgs = listOf("general"),
                                bodyKey = "push.body.sender_no_text",
                                bodyArgs = listOf("Alice")
                            )
                    ),
                isVisibleThread = false,
                isAppInForeground = false
            )!!
        val notification = build(with(AndroidUnreadNotificationPoster) { request.toJson() })
        assertEquals("#general のスレッド", titleOf(notification))
        assertEquals("Acme", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("（テキストなし）", messagesOf(notification).single().text.toString())
    }

    @Test
    fun stackedMessagesAreListedOneLinePerSender() {
        var history = UnreadConversationHistory.EMPTY
        history = history.appended("m-1", "Alice: first", "Alice", 1_000L)
        history = history.appended("m-2", "Bob: second", "Bob", 2_000L)
        history = history.appended("m-3", "an older build's line", null, 0L)
        val style =
            AndroidUnreadNotificationPoster.unreadMessagingStyle(
                selfName = "Raft",
                conversationTitle = "#general",
                directMessage = false,
                history = history,
                fallbackMillis = 3_000L
            )
        assertEquals("#general", style.conversationTitle.toString())
        assertTrue(style.isGroupConversation)
        assertEquals(
            listOf("Alice: first @1000", "Bob: second @2000", "#general: an older build's line @3000"),
            style.messages.map { "${it.person?.name}: ${it.text} @${it.timestamp}" }
        )

        // A DM is one-to-one: its sender is the title, no conversation title of its own.
        val dm =
            AndroidUnreadNotificationPoster.unreadMessagingStyle(
                selfName = "Raft",
                conversationTitle = "Alice",
                directMessage = true,
                history = UnreadConversationHistory.EMPTY.appended("m-1", "hi", "Alice", 1L),
                fallbackMillis = 1L
            )
        assertNull(dm.conversationTitle)
        assertFalse(dm.isGroupConversation)
        assertEquals("hi", dm.messages.single().text.toString())
    }

    @Test
    fun theSenderPrefixIsNotRepeatedInItsLine() {
        assertEquals("hello", AndroidUnreadNotificationPoster.unreadMessageLineText("Alice: hello", "Alice"))
        assertEquals("发送了附件", AndroidUnreadNotificationPoster.unreadMessageLineText("Alice：发送了附件", "Alice"))
        assertEquals("Bob: hello", AndroidUnreadNotificationPoster.unreadMessageLineText("Bob: hello", "Alice"))
        assertEquals("Alice:", AndroidUnreadNotificationPoster.unreadMessageLineText("Alice:", "Alice"))
    }

    @Test
    fun theSenderComesFromTheBodyOnlyForMessages() {
        fun sender(vararg pairs: Pair<String, String>): String? {
            val request = raftFcmUnreadNotificationRequest(baseFcm() + pairs.toMap())
            val category = request.optString("category").takeIf { it.isNotBlank() }
            val bodyKey = request.optString("bodyKey").takeIf { it.isNotBlank() }
            return unreadNotificationSender(request, category, request.optString("title"), request.optString("body"), bodyKey, emptyList())
        }
        assertEquals("Alice", sender())
        // A DM's title is its sender; its body is the text alone.
        assertEquals("Bob", sender("category" to "dm", "kind" to "dm", "title" to "Bob", "body" to "hi: there"))
        // A task, agent or reminder notice has no message sender.
        assertNull(sender("category" to "task_status", "body" to "Alice: moved to done"))
        assertNull(sender("category" to "reminder", "body" to "Standup: now"))
    }

    @Test
    fun theLockScreenSaysRaftAndTheCount() {
        val notification = build(fcm())
        val public = notification.publicVersion!!
        assertEquals("Raft", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("1 new message", public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(public.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
    }

    @Test
    fun aSenderOnlyDeviceDoesNotLeakATaskTitle() {
        // A full task-conversation push that reached a sender_only device before the server caught up.
        val task =
            fcm(
                "title" to "#12 Fix login · #general · Acme",
                "subtitle" to "#general · Acme",
                "taskId" to "task-12",
                "body" to "Alice: the secret plan"
            ).put("senderName", "Alice")
        val shown = build(task, PushPreviewMode.SenderOnly)
        assertEquals("Task", titleOf(shown))
        for (surface in surfacesOf(shown)) assertFalse(surface, surface.contains("Fix login") || surface.contains("secret plan"))
        // A task notice is titled by its task too.
        val notice = fcm("category" to "task_status", "title" to "#12 Fix login", "body" to "moved to done")
        assertEquals("Task", titleOf(build(notice, PushPreviewMode.SenderOnly)))
        // Hidden: no title, no subtitle.
        val hidden = build(task, PushPreviewMode.Hidden)
        assertEquals("Raft", titleOf(hidden))
        assertNull(hidden.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
    }

    private fun baseFcm(): Map<String, String> =
        mapOf(
            "serverId" to "server-1",
            "channelId" to "channel-1",
            "messageId" to "message-1",
            "kind" to "channel",
            "category" to "channel",
            "title" to "#general",
            "body" to "Alice: hello",
            "notificationKey" to "server-1:channel-1",
            "groupKey" to "server-1:channel-1"
        )

    private fun fcm(vararg pairs: Pair<String, String>): JSONObject = raftFcmUnreadNotificationRequest(baseFcm() + pairs.toMap())

    private fun build(request: JSONObject, mode: PushPreviewMode = PushPreviewMode.Full): Notification =
        AndroidUnreadNotificationPoster.buildUnreadNotification(
            context = context,
            channelId = "test-channel",
            request = request,
            previewMode = mode,
            contentIntent = null,
            whenMillis = 5_000L
        )

    private fun messagesOf(notification: Notification): List<NotificationCompat.MessagingStyle.Message> =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.messages.orEmpty()

    private fun titleOf(notification: Notification): String =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.let { it.conversationTitle ?: it.messages.last().person?.name }
            ?.toString()
            ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun surfacesOf(notification: Notification): List<String> =
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
            .mapNotNull { notification.extras.getCharSequence(it)?.toString() } +
            messagesOf(notification).map { "${it.person?.name}: ${it.text}" }
}
