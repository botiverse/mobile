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
    fun theLockScreenSaysRaftAndTheCount() {
        val notification = build(fcm())
        val public = notification.publicVersion!!
        assertEquals("Raft", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("1 new message", public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(public.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
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

    private fun titleOf(notification: Notification): String =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.let { it.conversationTitle ?: it.messages.last().person?.name }
            ?.toString()
            ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
}
