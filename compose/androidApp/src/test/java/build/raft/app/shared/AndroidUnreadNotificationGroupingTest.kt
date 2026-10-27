package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import build.raft.app.data.PushPreviewMode
import build.raft.app.push.raftFcmUnreadNotificationRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The tray shape of [AndroidUnreadNotificationGrouping]: one notification per conversation that
 * stacks its messages with a count, one group and summary per server, and the summary removed with
 * its last conversation. Posts go through [AndroidUnreadNotificationPoster.postGroupedUnreadNotification]
 * (the poster after its server / permission gates) and are read back from the notification manager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidUnreadNotificationGroupingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun clearTray() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(context)
        manager.cancelAll()
        // Forgets the previous test's recent posts: none of them is in the tray any more.
        AndroidUnreadNotificationPoster.refreshUnreadGroupSummaries(context)
    }

    @Test
    fun theConversationKeyIsTheServerAndTheConversationChannel() {
        val cases =
            listOf(
                // A channel or DM push names its conversation in channelId.
                fcm(channelId = "channel-1") to "server-1:channel-1",
                // A thread push: channelId is the thread's own channel, so a thread is its own conversation.
                fcm(channelId = "thread-9", extra = mapOf("threadId" to "thread-9", "parentChannelId" to "channel-1")) to
                    "server-1:thread-9",
                // An older request without channelId falls back to threadId, then messageId.
                JSONObject().put("serverId", "server-1").put("threadId", "thread-9") to "server-1:thread-9",
                JSONObject().put("serverId", "server-1").put("messageId", "m-1") to "server-1:m-1"
            )
        for ((request, expected) in cases) {
            assertEquals(request.toString(), expected, AndroidUnreadNotificationGrouping.conversationKey(request))
        }
        assertEquals("raft-unread:server-1", AndroidUnreadNotificationGrouping.groupKey("server-1"))
        assertNotEquals(
            AndroidUnreadNotificationGrouping.conversationNotificationId("server-1:channel-1"),
            AndroidUnreadNotificationGrouping.conversationNotificationId("server-2:channel-1")
        )
    }

    @Test
    fun theHistoryCountsNewMessagesReplacesARepeatedOneAndKeepsTheRecentLines() {
        var history = UnreadConversationHistory.EMPTY
        for (index in 1..8) history = history.appended("m-$index", "line $index")
        assertEquals(8, history.count)
        assertEquals((3..8).map { "line $it" }, history.lines)
        assertEquals((3..8).map { "m-$it" }, history.messageIds)

        // The same message again (FCM and the local realtime path both delivered it): no new count.
        val repeated = history.appended("m-8", "line 8 again")
        assertEquals(8, repeated.count)
        assertEquals("line 8 again", repeated.lines.last())

        // No message id: always a new message.
        assertEquals(10, repeated.appended("", "a").appended(" ", "b").count)

        val extras = Bundle().also { history.writeTo(it) }
        assertEquals(history, UnreadConversationHistory.readFrom(extras))
        assertEquals(UnreadConversationHistory.EMPTY, UnreadConversationHistory.readFrom(Bundle()))
    }

    @Test
    fun messagesOfOneConversationStackIntoOneNotificationWithTheirCount() {
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: first"))
        post(fcm(channelId = "channel-1", messageId = "m-2", body = "Bob: second"))
        post(fcm(channelId = "channel-1", messageId = "m-3", body = "Alice: third"))

        val conversations = conversations()
        assertEquals(1, conversations.size)
        val notification = conversations.single().notification
        assertEquals(3, notification.number)
        assertEquals("#general", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Collapsed: the newest message. Expanded: every message, oldest first, and the count.
        assertEquals("Alice: third", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(
            listOf("Alice: first", "Bob: second", "Alice: third"),
            notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        )
        assertEquals("3 new messages", notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
        assertEquals("3 new messages", notification.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("raft-unread:server-1", notification.group)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, notification.groupAlertBehavior)
    }

    @Test
    fun theSameMessageDeliveredTwiceCountsOnce() {
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: first"))
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: first"))

        val notification = conversations().single().notification
        assertEquals(1, notification.number)
        // Still a single message: the big-text shape, no count line.
        assertEquals("Alice: first", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertNull(notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))
    }

    @Test
    fun eachServerHasOneSummaryCountingItsConversations() {
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: hi", title = "#general"))
        post(fcm(channelId = "channel-1", messageId = "m-2", body = "Alice: again", title = "#general"))
        post(fcm(channelId = "dm-1", messageId = "m-3", body = "yo", title = "Bob"))
        post(fcm(serverId = "server-2", channelId = "channel-1", messageId = "m-4", body = "Carol: elsewhere"))

        assertEquals(3, conversations().size)
        val summaries = summaries()
        assertEquals(2, summaries.size)
        val first = summaryOf("server-1")!!.notification
        assertTrue((first.flags and Notification.FLAG_GROUP_SUMMARY) != 0)
        assertEquals("raft-unread:server-1", first.group)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, first.groupAlertBehavior)
        assertEquals(3, first.number)
        assertEquals("3 new messages", first.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
        assertEquals(
            setOf("#general: Alice: again", "Bob: yo"),
            first.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }.toSet()
        )
        val second = summaryOf("server-2")!!.notification
        assertEquals("raft-unread:server-2", second.group)
        assertEquals(1, second.number)
    }

    @Test
    fun theSummaryIsRecountedAndThenRemovedAsItsConversationsAreCleared() {
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: hi"))
        post(fcm(channelId = "dm-1", messageId = "m-2", body = "yo", title = "Bob"))
        assertEquals(2, summaryOf("server-1")!!.notification.number)

        // The user opens or swipes one conversation away.
        val (dismissed, kept) = conversations().partition { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Bob" }
        manager.cancel(dismissed.single().tag, dismissed.single().id)
        AndroidUnreadNotificationPoster.refreshUnreadGroupSummaries(context)
        assertEquals(1, summaryOf("server-1")!!.notification.number)

        // The last one goes: no summary is left behind on its own.
        manager.cancel(kept.single().tag, kept.single().id)
        AndroidUnreadNotificationPoster.refreshUnreadGroupSummaries(context)
        assertNull(summaryOf("server-1"))
        assertTrue(manager.activeNotifications.isEmpty())

        // A conversation dismissed and messaged again starts its count over.
        post(fcm(channelId = "channel-1", messageId = "m-3", body = "Alice: back"))
        assertEquals(1, conversations().single().notification.number)
        assertNotNull(summaryOf("server-1"))
    }

    private fun post(request: JSONObject) {
        val result =
            AndroidUnreadNotificationPoster.postGroupedUnreadNotification(
                context = context,
                channelId = AndroidUnreadNotificationPoster.UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID,
                request = request,
                previewMode = PushPreviewMode.Full,
                contentIntent = { null }
            )
        assertEquals(result.toString(), "posted", result["code"])
    }

    private fun conversations(): List<StatusBarNotification> =
        manager.activeNotifications.filter { it.tag == AndroidUnreadNotificationGrouping.CONVERSATION_TAG }

    private fun summaries(): List<StatusBarNotification> =
        manager.activeNotifications.filter { it.tag == AndroidUnreadNotificationGrouping.SUMMARY_TAG }

    private fun summaryOf(serverId: String): StatusBarNotification? =
        summaries().singleOrNull { it.id == AndroidUnreadNotificationGrouping.summaryNotificationId(serverId) }

    /** The FCM data message shape of slock's `fcmMessageForMobilePush`. */
    private fun fcm(
        serverId: String = "server-1",
        channelId: String,
        messageId: String = "m-1",
        title: String = "#general",
        body: String = "Alice: hello",
        extra: Map<String, String> = emptyMap()
    ): JSONObject =
        raftFcmUnreadNotificationRequest(
            mapOf(
                "serverId" to serverId,
                "channelId" to channelId,
                "messageId" to messageId,
                "kind" to "channel",
                "category" to "RAFT_MESSAGE",
                "title" to title,
                "body" to body,
                "notificationKey" to "collapse-$serverId-$channelId"
            ) + extra
        )
}
