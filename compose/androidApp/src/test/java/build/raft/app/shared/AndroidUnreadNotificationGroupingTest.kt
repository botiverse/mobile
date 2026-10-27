package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
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

        // The user swipes one conversation away: its delete intent names it.
        val (dismissed, kept) = conversations().partition { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Bob" }
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
        assertEquals(listOf("Bob"), conversations().map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() })
        assertEquals(1, summaryOf("server-1")!!.notification.number)

        // The 4th push is one new message, not "4 new messages" listing the read ones.
        post(fcm(channelId = "channel-1", messageId = "m-4", body = "Alice: four"))
        val general = conversations().single { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "#general" }
        assertEquals(1, general.notification.number)
        assertEquals("Alice: four", general.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertNull(general.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))
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
    fun aBurstInOneConversationUpdatesTheSummaryOnceInsteadOfPerMessage() {
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: one"))
        // The first conversation posts its summary at once: the group needs it to bundle.
        assertEquals(1, summaryOf("server-1")!!.notification.number)
        assertTrue(deferredSummaries.isEmpty())

        post(fcm(channelId = "channel-1", messageId = "m-2", body = "Alice: two"))
        post(fcm(channelId = "channel-1", messageId = "m-3", body = "Alice: three"))
        // Same conversations: the summary waits, with one deferred update for the whole burst.
        assertEquals(1, summaryOf("server-1")!!.notification.number)
        assertEquals(listOf(AndroidUnreadNotificationPoster.SUMMARY_COALESCE_MILLIS), deferredSummaries.map { it.first })
        runDeferredSummaries()
        assertEquals(3, summaryOf("server-1")!!.notification.number)

        // Another message defers again; a new conversation then posts at once and the deferred update,
        // already covered, leaves the summary as it is.
        post(fcm(channelId = "channel-1", messageId = "m-4", body = "Alice: four"))
        post(fcm(channelId = "dm-1", messageId = "m-5", body = "yo", title = "Bob"))
        assertEquals(5, summaryOf("server-1")!!.notification.number)
        manager.cancel(AndroidUnreadNotificationGrouping.SUMMARY_TAG, AndroidUnreadNotificationGrouping.summaryNotificationId("server-1"))
        runDeferredSummaries()
        assertNull(summaryOf("server-1"))
    }

    @Test
    fun theSummaryIsPostedOnAnEnabledChannelOfItsConversations() {
        val general = AndroidUnreadNotificationPoster.UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID
        val mention = AndroidUnreadNotificationPoster.UNREAD_MENTION_NOTIFICATION_CHANNEL_ID
        val dm = AndroidUnreadNotificationPoster.UNREAD_DM_NOTIFICATION_CHANNEL_ID
        // The user turned "general" off and kept "mentions".
        manager.deleteNotificationChannel(general)
        manager.createNotificationChannel(NotificationChannel(general, "General", NotificationManager.IMPORTANCE_NONE))
        try {
            assertTrue(AndroidUnreadNotificationPoster.isNotificationChannelBlocked(context, general))
            post(fcm(channelId = "channel-1", messageId = "m-1", body = "@you"), channelId = mention)
            assertEquals(mention, summaryOf("server-1")!!.notification.channelId)

            // The newest conversation's channel, skipping a blocked one; general only as the last resort.
            fun entry(channelId: String, postedAtMillis: Long) =
                UnreadSummaryEntry(postedAtMillis.toInt(), channelId, "t", "l", 1, postedAtMillis)
            assertEquals(dm, AndroidUnreadNotificationPoster.summaryChannelId(context, listOf(entry(dm, 2), entry(mention, 1))))
            assertEquals(mention, AndroidUnreadNotificationPoster.summaryChannelId(context, listOf(entry(general, 2), entry(mention, 1))))
            assertEquals(general, AndroidUnreadNotificationPoster.summaryChannelId(context, listOf(entry("", 1))))
        } finally {
            manager.deleteNotificationChannel(general)
            AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(context)
        }
    }

    @Test
    fun recentPostsKeepAJustPostedConversationTheTrayDoesNotListYetAndHideAJustClearedOne() {
        var now = 10_000L
        val posts = UnreadRecentPosts(freshWindowMillis = 5_000L, clock = { now })
        val history = UnreadConversationHistory.EMPTY.appended("m-1", "one").appended("m-2", "two")
        fun entry(id: Int) = UnreadSummaryEntry(id, "c", "t", "two", 2, 0L)
        posts.record(1, "server-1", history, entry(1))

        // A summary refresh right after the post: the tray does not list it yet, the count survives,
        // and the caller is told to look at its server again.
        now += UnreadRecentPosts.RETAIN_GRACE_MILLIS
        assertEquals(setOf("server-1"), posts.retainOnly(emptySet()))
        assertEquals(history, posts.history(1))
        // Later, still absent from the tray: dismissed, forgotten.
        now += 1
        assertEquals(emptySet<String>(), posts.retainOnly(emptySet()))
        assertNull(posts.history(1))

        // Cleared (read in the app): empty whatever the tray still lists, until a new post or the window ends.
        posts.record(2, "server-1", history, entry(2))
        posts.clear(2)
        assertEquals(UnreadConversationHistory.EMPTY, posts.history(2))
        assertTrue(posts.isCleared(2))
        assertTrue(posts.entries("server-1").isEmpty())
        now += 5_001L
        assertNull(posts.history(2))
        posts.clear(3)
        posts.record(3, "server-1", history, entry(3))
        assertEquals(history, posts.history(3))
        assertEquals(setOf(3), posts.entries("server-1").keys)
    }

    @Test
    fun aConversationTappedOpenRightAfterItsPostLeavesNoSummaryBehind() {
        // #general gets two messages: the second defers the summary update.
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: one"))
        post(fcm(channelId = "channel-1", messageId = "m-2", body = "Alice: two"))
        assertEquals(1, deferredSummaries.size)

        // Half a second later the user taps it: auto-cancel, no delete intent, no read event. The
        // refresh on return is inside the grace and cannot tell a tap from a post still on its way.
        now += 500
        val general = conversations().single()
        manager.cancel(general.tag, general.id)
        AndroidUnreadNotificationPoster.refreshUnreadGroupSummaries(context)

        // The deferred update looks at the tray again: no conversation, no summary.
        now += 500
        runDeferredSummaries()
        assertNull(summaryOf("server-1"))
        assertTrue(manager.activeNotifications.isEmpty())

        // Same with a single message, where nothing was deferred: the refresh itself asks for another look.
        post(fcm(channelId = "dm-1", messageId = "m-3", body = "yo", title = "Bob"))
        assertTrue(deferredSummaries.isEmpty())
        now += 300
        val dm = conversations().single()
        manager.cancel(dm.tag, dm.id)
        AndroidUnreadNotificationPoster.refreshUnreadGroupSummaries(context)
        assertNotNull(summaryOf("server-1"))
        assertEquals(1, deferredSummaries.size)
        now += AndroidUnreadNotificationPoster.SUMMARY_COALESCE_MILLIS
        runDeferredSummaries()
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(deferredSummaries.isEmpty())
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
    fun aDeferredSummaryUpdateAfterAClearCountsOnlyWhatIsLeft() {
        post(fcm(channelId = "channel-1", messageId = "m-1", body = "Alice: one"))
        post(fcm(channelId = "dm-1", messageId = "m-2", body = "yo", title = "Bob"))
        post(fcm(channelId = "channel-1", messageId = "m-3", body = "Alice: two"))
        assertEquals(1, deferredSummaries.size)

        // #general is read while its summary update waits.
        AndroidUnreadNotificationPoster.clearConversation(context, "server-1", "channel-1")
        assertEquals(1, summaryOf("server-1")!!.notification.number)
        now += AndroidUnreadNotificationPoster.SUMMARY_COALESCE_MILLIS
        runDeferredSummaries()
        assertEquals(1, summaryOf("server-1")!!.notification.number)

        // The last one is read with another update pending: the update does not bring the summary back.
        post(fcm(channelId = "dm-1", messageId = "m-4", body = "again", title = "Bob"))
        assertEquals(1, deferredSummaries.size)
        AndroidUnreadNotificationPoster.clearConversation(context, "server-1", "dm-1")
        now += AndroidUnreadNotificationPoster.SUMMARY_COALESCE_MILLIS
        runDeferredSummaries()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    private fun runBackgroundTasks() {
        val tasks = backgroundTasks.toList()
        backgroundTasks.clear()
        tasks.forEach { it() }
    }

    private fun runDeferredSummaries() {
        val tasks = deferredSummaries.toList()
        deferredSummaries.clear()
        tasks.forEach { (_, task) -> task() }
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
