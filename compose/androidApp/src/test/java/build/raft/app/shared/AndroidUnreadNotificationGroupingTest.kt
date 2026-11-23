package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import build.raft.app.R
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftConversationReadHost
import build.raft.app.push.raftFcmUnreadNotificationRequest
import org.json.JSONObject
import org.junit.After
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

    /** Deferred summary updates, run when a test chooses ([runDeferredSummaries]). */
    private val deferredSummaries = mutableListOf<Pair<Long, () -> Unit>>()
    private val productionSummaryScheduler = AndroidUnreadNotificationPoster.summaryScheduler
    private val productionClock = AndroidUnreadNotificationPoster.clock
    private val productionBackgroundRunner = AndroidUnreadNotificationPoster.backgroundRunner

    /** The grouping state's clock: only the test moves it. */
    private var now = 1_000_000L

    /** Clears of read conversations queued for the background thread, run when a test chooses. */
    private val backgroundTasks = mutableListOf<() -> Unit>()

    @Before
    fun clearTray() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(context)
        manager.cancelAll()
        // Forgets the previous test's recent posts and summaries: none of them is in the tray any more.
        AndroidUnreadNotificationPoster.resetGroupingState()
        AndroidUnreadNotificationPoster.summaryScheduler = { delayMillis, task -> deferredSummaries += delayMillis to task }
        AndroidUnreadNotificationPoster.clock = { now }
        AndroidUnreadNotificationPoster.backgroundRunner = { task -> backgroundTasks += task }
    }

    @After
    fun restoreProductionHooks() {
        AndroidUnreadNotificationPoster.summaryScheduler = productionSummaryScheduler
        AndroidUnreadNotificationPoster.clock = productionClock
        AndroidUnreadNotificationPoster.backgroundRunner = productionBackgroundRunner
        RaftConversationReadHost.listener = null
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
        assertEquals("#general", titleOf(notification))
        // Expanded: every message, oldest first, each with its sender (MessagingStyle), not "3 new messages".
        assertEquals(listOf("Alice: first", "Bob: second", "Alice: third"), linesOf(notification))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertTrue(style.isGroupConversation)
        assertEquals(listOf("first", "second", "third"), style.messages.map { it.text.toString() })
        // The lock screen: "Raft · 3 new messages".
        assertEquals("Raft", notification.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
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
        // Still a single message line.
        assertEquals(listOf("Alice: first"), linesOf(notification))
    }

    @Test
    fun theSummaryIsRecountedAndThenRemovedAsItsConversationsAreCleared() {
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: hi"))
        post(fcm(channelId = "dm-1", messageId = "m-2", body = "yo", title = "Bob"))
        assertEquals(2, summaryOf("server-1")!!.notification.number)

        // The user swipes one conversation away: its delete intent names it.
        val (dismissed, kept) = conversations().partition { titleOf(it.notification) == "Bob" }
        manager.cancel(dismissed.single().tag, dismissed.single().id)
        AndroidUnreadNotificationPoster.onConversationDismissed(context, dismissed.single().id)
        assertEquals(1, summaryOf("server-1")!!.notification.number)

        // The last one is tapped later (auto-cancel: no delete intent, no read event); the refresh on
        // return removes the summary instead of leaving it on its own.
        now += UnreadRecentPosts.RETAIN_GRACE_MILLIS + 1
        manager.cancel(kept.single().tag, kept.single().id)
        AndroidUnreadNotificationPoster.refreshUnreadGroupSummaries(context)
        assertNull(summaryOf("server-1"))
        assertTrue(manager.activeNotifications.isEmpty())

        // A conversation dismissed and messaged again starts its count over.
        post(fcm(channelId = "channel-1", messageId = "m-3", body = "Alice: back"))
        assertEquals(1, conversations().single().notification.number)
        assertNotNull(summaryOf("server-1"))
    }

    @Test
    fun readingAConversationInTheAppClearsItsNotificationAndStartsItsCountOver() {
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: one"))
        post(fcm(channelId = "channel-1", messageId = "m-2", body = "Alice: two"))
        post(fcm(channelId = "channel-1", messageId = "m-3", body = "Alice: three"))
        post(fcm(channelId = "dm-1", messageId = "m-9", body = "yo", title = "Bob"))
        assertEquals(4, summaryOf("server-1")!!.notification.number)

        // #general read in the app (opened from the channel list, not from the notification).
        AndroidUnreadNotificationPoster.clearConversation(context, "server-1", "channel-1")
        assertEquals(listOf("Bob"), conversations().map { titleOf(it.notification) })
        assertEquals(1, summaryOf("server-1")!!.notification.number)

        // The 4th push is one new message, not "4 new messages" listing the read ones.
        post(fcm(channelId = "channel-1", messageId = "m-4", body = "Alice: four"))
        val general = conversations().single { titleOf(it.notification) == "#general" }
        assertEquals(1, general.notification.number)
        assertEquals(listOf("Alice: four"), linesOf(general.notification))
        assertEquals(2, summaryOf("server-1")!!.notification.number)

        // Reading a conversation without a notification (another server's, too) changes nothing.
        AndroidUnreadNotificationPoster.clearConversation(context, "server-1", "channel-2")
        AndroidUnreadNotificationPoster.clearConversation(context, "server-2", "channel-1")
        assertEquals(2, conversations().size)

        // Reading the last ones removes the summary with them.
        AndroidUnreadNotificationPoster.clearConversation(context, "server-1", "channel-1")
        AndroidUnreadNotificationPoster.clearConversation(context, "server-1", "dm-1")
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun aReadEventClearsItsConversationOnTheBackgroundThreadUnlessAMessageArrivedAfterIt() {
        AndroidUnreadNotificationPoster.installConversationReadListener(context)
        val listener = RaftConversationReadHost.listener!!
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: one"))
        post(fcm(channelId = "channel-1", messageId = "m-2", body = "Alice: two"))

        // The read is queued, not run on the reader's thread.
        listener.onConversationRead("server-1", "channel-1")
        assertEquals(2, conversations().single().notification.number)
        assertEquals(1, backgroundTasks.size)

        // Message 3 arrives and posts before the queued clear runs: the read did not cover it, so
        // the conversation stays, with every message.
        post(fcm(channelId = "channel-1", messageId = "m-3", body = "Alice: three"))
        runBackgroundTasks()
        assertEquals(3, conversations().single().notification.number)
        assertNotNull(summaryOf("server-1"))

        // A read after message 3 clears it.
        listener.onConversationRead("server-1", "channel-1")
        runBackgroundTasks()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun groupSummaryDisplaysServerNameAndUsesRaftSmallIconWithoutLeakingOnLockScreen() {
        post(fcm(serverId = "server-1", channelId = "channel-1", messageId = "m-1", body = "Alice: hi", extra = mapOf("subtitle" to "Raft")))
        post(fcm(serverId = "server-1", channelId = "dm-1", messageId = "m-2", title = "Bob", body = "yo", extra = mapOf("category" to "dm", "kind" to "dm", "subtitle" to "Raft")))

        val summary = summaryOf("server-1")!!.notification
        assertEquals("Raft", summary.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Raft", summary.extras.getCharSequence(Notification.EXTRA_TITLE_BIG).toString())
        assertEquals("Raft · 2 new messages", summary.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
        assertEquals("Raft", summary.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME))
        assertEquals(R.drawable.ic_stat_raft, summary.smallIcon.resId)

        // Public (lock-screen) summary must stay redacted and also use the Raft small icon.
        val publicSummary = summary.publicVersion!!
        assertEquals("Raft", publicSummary.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("2 new messages", publicSummary.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(R.drawable.ic_stat_raft, publicSummary.smallIcon.resId)

        // Conversation notifications and their lock-screen versions also use the Raft small icon.
        for (conversation in conversations()) {
            assertEquals(R.drawable.ic_stat_raft, conversation.notification.smallIcon.resId)
            assertEquals(R.drawable.ic_stat_raft, conversation.notification.publicVersion!!.smallIcon.resId)
        }

        // Recounting after a dismiss preserves the server name from the remaining tray notification's extras.
        now += 10_000L // past recentPosts fresh window so recount reads from tray extras
        val dismissed = conversations().first { titleOf(it.notification) == "Bob" }
        manager.cancel(dismissed.tag, dismissed.id)
        AndroidUnreadNotificationPoster.onConversationDismissed(context, dismissed.id)
        val updatedSummary = summaryOf("server-1")!!.notification
        assertEquals("Raft · 1 new message", updatedSummary.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
        assertEquals("Raft", updatedSummary.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME))
    }

    private fun runBackgroundTasks() {
        val tasks = backgroundTasks.toList()
        backgroundTasks.clear()
        tasks.forEach { it() }
    }

    private fun post(request: JSONObject, channelId: String = AndroidUnreadNotificationPoster.UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID) {
        val result =
            AndroidUnreadNotificationPoster.postGroupedUnreadNotification(
                context = context,
                channelId = channelId,
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
    /** The title Android shows: a MessagingStyle's conversation (a DM's: its sender), else the content title. */
    private fun titleOf(notification: Notification): String =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.let { it.conversationTitle ?: it.messages.last().person?.name }
            ?.toString()
            ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    /** The listed messages as "Sender: text" (MessagingStyle), or the big text / inbox lines. */
    private fun linesOf(notification: Notification): List<String> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (style != null) return style.messages.map { "${it.person?.name}: ${it.text}" }
        notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines -> return lines.map { it.toString() } }
        return listOfNotNull(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
    }

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
