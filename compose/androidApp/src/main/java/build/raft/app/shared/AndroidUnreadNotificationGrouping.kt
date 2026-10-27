package build.raft.app.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject

/**
 * How unread notifications stack in the Android tray: the Slack / WhatsApp / Telegram shape.
 *
 * - **One notification per conversation.** Every unread message of a channel, DM or thread lands in
 *   the same tray notification (same tag + id), which keeps the latest message as its collapsed text and
 *   lists the recent ones when expanded, with the total as its count. One notification has one tap
 *   target, so the conversation is the unit a tap opens: a thread is its own conversation (its own
 *   channel id), not part of its parent channel.
 * - **One group per server above that.** Every conversation notification of a server joins the
 *   server's group, which has one summary notification (Android needs a summary to bundle a group). The
 *   summary never alerts (`GROUP_ALERT_CHILDREN`); each new message alerts through its conversation.
 *
 * Android has only one level of grouping, so per-message notifications grouped per conversation would
 * leave no level for the server; folding the messages into the conversation notification keeps both.
 * The history lives in the posted notification's own extras ([UnreadConversationHistory]), so
 * dismissing a conversation also forgets it, and a process killed between pushes starts from what the
 * tray still shows.
 */
internal object AndroidUnreadNotificationGrouping {
    /** Tag of every conversation notification; ids are per conversation within it. */
    const val CONVERSATION_TAG = "raft-unread-conversation"

    /** Tag of the per-server group summaries; ids are per server within it. */
    const val SUMMARY_TAG = "raft-unread-summary"

    /** At most this many recent messages are listed in an expanded conversation (InboxStyle shows ~6). */
    const val MAX_LINES = 6

    internal const val EXTRA_SERVER_ID = "build.raft.app.unread.serverId"
    internal const val EXTRA_CONVERSATION_KEY = "build.raft.app.unread.conversationKey"
    internal const val EXTRA_MESSAGE_IDS = "build.raft.app.unread.messageIds"
    internal const val EXTRA_LINES = "build.raft.app.unread.lines"
    internal const val EXTRA_COUNT = "build.raft.app.unread.count"
    internal const val EXTRA_TITLE = "build.raft.app.unread.title"

    /**
     * `<serverId>:<conversation channel id>`. Both entry points name the conversation in `channelId`:
     * the FCM payload's channelId is the thread's own channel for a thread push and the parent channel
     * for a task push (which opens there), and the local realtime request sets it the same way.
     * `threadId` and then `messageId` only stand in for an older request without a channelId.
     */
    fun conversationKey(request: JSONObject): String {
        val serverId = request.optString("serverId").trim()
        val conversation =
            listOf("channelId", "threadId", "messageId")
                .map { request.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: request.optString("notificationKey").trim()
        return "$serverId:$conversation"
    }

    /** The `setGroup` key of a server: every conversation notification of that server bundles under it. */
    fun groupKey(serverId: String): String = "raft-unread:${serverId.trim()}"

    fun conversationNotificationId(conversationKey: String): Int = stableNotificationId("conversation:$conversationKey")

    fun summaryNotificationId(serverId: String): Int = stableNotificationId("summary:${serverId.trim()}")

    internal fun stableNotificationId(key: String): Int {
        val hash = key.hashCode()
        return if (hash == Int.MIN_VALUE) {
            Int.MAX_VALUE
        } else {
            kotlin.math.abs(hash).coerceAtLeast(1)
        }
    }
}

/**
 * The messages one conversation notification stands for: [count] unread messages, of which the most
 * recent [lines] (oldest first, at most [AndroidUnreadNotificationGrouping.MAX_LINES]) are listed, each
 * with its message id in [messageIds] at the same index.
 *
 * The same message can arrive twice, through FCM and through the local realtime path, so a message id
 * already listed replaces its line instead of counting again. A request without a message id always
 * counts as new.
 */
internal data class UnreadConversationHistory(
    val messageIds: List<String>,
    val lines: List<String>,
    val count: Int
) {
    fun appended(messageId: String, line: String): UnreadConversationHistory {
        val id = messageId.trim()
        val existing = if (id.isEmpty()) -1 else messageIds.indexOf(id)
        if (existing >= 0) {
            return copy(lines = lines.toMutableList().also { it[existing] = line })
        }
        val keep = AndroidUnreadNotificationGrouping.MAX_LINES - 1
        return UnreadConversationHistory(
            messageIds = messageIds.takeLast(keep) + id,
            lines = lines.takeLast(keep) + line,
            count = count + 1
        )
    }

    fun writeTo(extras: Bundle) {
        extras.putStringArrayList(AndroidUnreadNotificationGrouping.EXTRA_MESSAGE_IDS, ArrayList(messageIds))
        extras.putStringArrayList(AndroidUnreadNotificationGrouping.EXTRA_LINES, ArrayList(lines))
        extras.putInt(AndroidUnreadNotificationGrouping.EXTRA_COUNT, count)
    }

    companion object {
        val EMPTY = UnreadConversationHistory(emptyList(), emptyList(), 0)

        /** The history a posted conversation notification carries; [EMPTY] for anything else. */
        fun readFrom(extras: Bundle?): UnreadConversationHistory {
            extras ?: return EMPTY
            val lines = extras.getStringArrayList(AndroidUnreadNotificationGrouping.EXTRA_LINES) ?: return EMPTY
            val ids = extras.getStringArrayList(AndroidUnreadNotificationGrouping.EXTRA_MESSAGE_IDS).orEmpty()
            if (ids.size != lines.size) return EMPTY
            val count = extras.getInt(AndroidUnreadNotificationGrouping.EXTRA_COUNT, lines.size)
            return UnreadConversationHistory(ids, lines, count.coerceAtLeast(lines.size))
        }
    }
}

/**
 * One conversation as its server's group summary lists it. [notificationId] identifies the
 * conversation; [channelId] is the Android notification channel it was posted on, which the summary
 * reuses (see [AndroidUnreadNotificationPoster.summaryChannelId]).
 */
internal data class UnreadSummaryEntry(
    val notificationId: Int,
    val channelId: String,
    val title: String,
    val latestLine: String,
    val count: Int,
    val postedAtMillis: Long
)

/**
 * Conversations posted, and conversations cleared, by this process in the last [freshWindowMillis].
 *
 * `NotificationManager.notify` and `cancel` are asynchronous: the notification posted a moment ago may
 * not be in `activeNotifications` yet, and the one cancelled a moment ago may still be. A burst of
 * pushes (FCM flushing what queued while the phone was offline) would then read a stale history and
 * lose messages, the summary would miss the newest conversation, and a conversation just read in the
 * app would start again from the messages the user already read. Within the window this process's
 * own record wins; after it the tray is the truth, so a conversation the user dismissed starts over.
 */
internal class UnreadRecentPosts(
    private val freshWindowMillis: Long = 5_000L,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private data class Post(
        val serverId: String,
        val history: UnreadConversationHistory,
        val entry: UnreadSummaryEntry,
        val atMillis: Long
    )

    private val posts = mutableMapOf<Int, Post>()
    private val cleared = mutableMapOf<Int, Long>()

    fun record(notificationId: Int, serverId: String, history: UnreadConversationHistory, entry: UnreadSummaryEntry) {
        prune()
        cleared.remove(notificationId)
        posts[notificationId] = Post(serverId, history, entry, clock())
    }

    /**
     * What the conversation shows according to this process: its recent post, [UnreadConversationHistory.EMPTY]
     * when it was just cleared (whatever the tray still lists), or null to ask the tray.
     */
    fun history(notificationId: Int): UnreadConversationHistory? {
        prune()
        if (notificationId in cleared) return UnreadConversationHistory.EMPTY
        return posts[notificationId]?.history
    }

    fun has(notificationId: Int): Boolean {
        prune()
        return notificationId in posts
    }

    /** The conversation was just cancelled or dismissed: a tray that still lists it is stale. */
    fun isCleared(notificationId: Int): Boolean {
        prune()
        return notificationId in cleared
    }

    fun entries(serverId: String): Map<Int, UnreadSummaryEntry> {
        prune()
        return posts.filterValues { it.serverId == serverId }.mapValues { it.value.entry }
    }

    /** The conversation was read in the app or dismissed: forget it and ignore the tray's copy for a while. */
    fun clear(notificationId: Int) {
        prune()
        posts.remove(notificationId)
        cleared[notificationId] = clock()
    }

    /**
     * Drops every conversation not in [notificationIds] (the ones the tray no longer shows), except one
     * posted in the last [graceMillis]: `notify` may not have reached the tray yet, and dropping it would
     * restart its count. Returns the servers of the conversations kept only by that grace: the caller
     * must check them again once it is over, or a conversation tapped open (auto-cancel, no delete
     * intent) right after its post would keep its summary forever.
     */
    fun retainOnly(notificationIds: Set<Int>, graceMillis: Long = RETAIN_GRACE_MILLIS): Set<String> {
        val now = clock()
        val keptByGrace = mutableSetOf<String>()
        posts.entries.removeAll { (id, post) ->
            if (id in notificationIds) return@removeAll false
            val young = now - post.atMillis <= graceMillis
            if (young) keptByGrace += post.serverId
            !young
        }
        return keptByGrace
    }

    /** Forgets everything (tests: one test's posts are not the next one's). */
    fun reset() {
        posts.clear()
        cleared.clear()
    }

    private fun prune() {
        val now = clock()
        posts.entries.removeAll { now - it.value.atMillis > freshWindowMillis }
        cleared.entries.removeAll { now - it.value > freshWindowMillis }
    }

    companion object {
        const val RETAIN_GRACE_MILLIS = 1_000L
    }
}

/**
 * The delete intent of every conversation notification: the user swiped one away, so it is forgotten
 * and its server's summary is recounted, or removed with the last conversation instead of lingering on
 * its own.
 */
class RaftUnreadNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, 0) ?: 0
        AndroidUnreadNotificationPoster.onConversationDismissed(context, notificationId.takeIf { it != 0 })
    }

    internal companion object {
        const val EXTRA_NOTIFICATION_ID = "build.raft.app.unread.dismissedNotificationId"
    }
}
