package build.raft.app.data

import build.raft.app.data.realtime.PerReceiverReadStateCheckpoint
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.mentionsUser
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home's followed-thread unread, mirroring the web sidebar (botiverse/slock
 * `packages/web/src/store/socketBridge.ts` `threadUpdated`, `store/threadStore.ts`
 * `applyServerThreadUnread` / `clearThreadUnreadLocally`, `utils/threadUnreadByParent.ts`).
 *
 * The web keeps the followed-thread list as the live source and derives each channel's mark from it.
 * Home keeps the followed threads WITH unread replies from `channels/unread?summary=1`
 * ([HomeSnapshot.followedThreadUnread]) and updates them the same way:
 *  - a `thread:updated` reply into a known thread adds one unread and lights its @ when the reply
 *    mentions the user;
 *  - a reply into a thread Home does not know (a read followed thread, an auto-follow) reloads the
 *    summary through [HomeCoalescedRefresh], as the web reloads its followed list;
 *  - a read-state push lowers a thread to the server's count, and a local read clears it
 *    (`withConversationUnread(threadId, 0)`, web `clearThreadUnreadLocally`).
 */

/** Followed-thread mark of one parent channel (web `summarizeParentThreadUnread`). */
fun HomeSnapshot.parentThreadUnread(parentChannelId: ChannelId): HomeParentThreadUnread? =
    followedThreadUnread.foldThreadUnreadByParent()[parentChannelId]

internal enum class HomeThreadReplyDecision {
    /** Own reply, or the thread is open: nothing new to count. */
    Ignore,

    /** A known followed thread got a reply: one more unread (and maybe a mention). */
    Bump,

    /** The thread is not in the snapshot: reload the summary to learn whether it is followed. */
    Refresh,
}

internal fun HomeSnapshot.decideThreadReply(
    event: ThreadUpdated,
    openConversationId: String?
): HomeThreadReplyDecision =
    decideThreadReply(
        event = event,
        openConversationIds = setOfNotNull(openConversationId),
        openParentMessageId = null,
    )

internal fun HomeSnapshot.decideThreadReply(
    event: ThreadUpdated,
    openConversationIds: Set<String>,
    openParentMessageId: String? = null,
): HomeThreadReplyDecision {
    val me = currentMember.id.value
    // The server never counts the user's own reply as unread; nothing to bump or reload.
    if (event.latestReply?.senderId == me) return HomeThreadReplyDecision.Ignore
    // A thread currently open on screen (main detail or side pane) is being read live:
    // neither bump its unread nor schedule a summary reload that could race its read receipt.
    if (
        event.threadChannelId.value in openConversationIds ||
        (openParentMessageId != null && event.parentMessageId.value == openParentMessageId)
    ) {
        return HomeThreadReplyDecision.Ignore
    }
    if (event.threadChannelId.value !in followedThreadUnread) return HomeThreadReplyDecision.Refresh
    return HomeThreadReplyDecision.Bump
}

/** Web `updateFollowedThread(..., { unreadCount: followed.unreadCount + 1, hasMention })`. */
internal fun HomeSnapshot.withFollowedThreadReply(event: ThreadUpdated): HomeSnapshot {
    val known = followedThreadUnread[event.threadChannelId.value] ?: return this
    val next =
        known.copy(
            unreadCount = known.unreadCount + 1,
            hasMention = known.hasMention || event.latestReplyMentions.mentionsUser(currentMember.id.value)
        )
    return copy(followedThreadUnread = followedThreadUnread + (event.threadChannelId.value to next))
}

/** Web `applyServerThreadUnread`: only ever lowers a known thread to the server's count. */
internal fun HomeSnapshot.withFollowedThreadServerUnread(
    threadChannelId: ChannelId,
    serverUnreadCount: Int
): HomeSnapshot {
    val known = followedThreadUnread[threadChannelId.value] ?: return this
    if (known.unreadCount <= serverUnreadCount) return this
    return if (serverUnreadCount <= 0) {
        copy(followedThreadUnread = followedThreadUnread - threadChannelId.value)
    } else {
        copy(
            followedThreadUnread =
                followedThreadUnread + (threadChannelId.value to known.copy(unreadCount = serverUnreadCount))
        )
    }
}

/**
 * Projects a served `channels/unread?summary=1` followed-thread map against locally known read
 * state before applying it to Home (web `applyKnownReadStateProjectionsToFollowedThreads` /
 * `isServedThreadRowRead`, `#wg-mobile-sync:#257`), so a lagging read-model summary cannot briefly
 * resurrect a thread the user just read or unfollowed.
 */
internal fun projectFollowedThreadUnread(
    served: Map<String, HomeFollowedThreadUnread>,
    servedLatestActivitySeqs: Map<String, Long> = emptyMap(),
    servedReadFrontiers: Map<String, ReadReceiptFrontier> = emptyMap(),
    servedMarkedUnreadIds: Set<String> = emptySet(),
    pendingUnfollows: Set<String> = emptySet(),
    pendingReadSeqs: Map<ChannelId, Long> = emptyMap(),
    acknowledgedReadSeqs: Map<ChannelId, Long> = emptyMap(),
    currentFollowedThreadUnread: Map<String, HomeFollowedThreadUnread> = emptyMap(),
    currentLatestActivitySeqs: Map<String, Long> = emptyMap(),
    acceptedCheckpoint: (threadId: String) -> PerReceiverReadStateCheckpoint? = { null },
): Map<String, HomeFollowedThreadUnread> {
    if (served.isEmpty()) return emptyMap()
    val filtered = LinkedHashMap<String, HomeFollowedThreadUnread>(served.size)
    for ((threadId, row) in served) {
        if (threadId in pendingUnfollows) continue
        val latestSeq = servedLatestActivitySeqs[threadId]
        val markedUnread = threadId in servedMarkedUnreadIds || servedReadFrontiers[threadId]?.markedUnread == true
        if (latestSeq != null) {
            val channelId = ChannelId(threadId)
            val pendingRead = pendingReadSeqs[channelId]
            if (pendingRead != null && pendingRead >= latestSeq) continue
            val acknowledgedRead = acknowledgedReadSeqs[channelId]
            if (!markedUnread && acknowledgedRead != null && acknowledgedRead >= latestSeq) continue
            val checkpoint = acceptedCheckpoint(threadId)
            val servedVersion = servedReadFrontiers[threadId]?.readStateVersion ?: 0L
            if (checkpoint != null && checkpoint.version > servedVersion && checkpoint.maxReadSeq >= latestSeq) {
                continue
            }
            if (
                !markedUnread &&
                threadId !in currentFollowedThreadUnread &&
                currentLatestActivitySeqs[threadId] == latestSeq
            ) {
                continue
            }
        }
        filtered[threadId] = row
    }
    return filtered
}

/** A reloaded summary replaces the followed threads wholesale (web `loadFollowedThreads`). */
internal fun HomeSnapshot.withFollowedThreadUnread(
    threads: Map<String, HomeFollowedThreadUnread>,
    latestActivitySeqs: Map<String, Long> = emptyMap(),
    readFrontiers: Map<String, ReadReceiptFrontier> = emptyMap(),
): HomeSnapshot {
    val nextLatestSeqs =
        if (latestActivitySeqs.isEmpty()) {
            unreadLatestActivitySeqs
        } else {
            val merged = LinkedHashMap(unreadLatestActivitySeqs)
            for ((scopeId, seq) in latestActivitySeqs) {
                val existing = merged[scopeId]
                if (existing == null || seq > existing) merged[scopeId] = seq
            }
            if (merged == unreadLatestActivitySeqs) unreadLatestActivitySeqs else merged
        }
    val nextFrontiers =
        if (readFrontiers.isEmpty()) {
            unreadReadFrontiers
        } else {
            val merged = LinkedHashMap(unreadReadFrontiers)
            for ((scopeId, frontier) in readFrontiers) {
                val existing = merged[scopeId]
                if (existing == null || (frontier.readStateVersion ?: -1L) >= (existing.readStateVersion ?: -1L)) {
                    merged[scopeId] = frontier
                }
            }
            if (merged == unreadReadFrontiers) unreadReadFrontiers else merged
        }
    if (threads == followedThreadUnread && nextLatestSeqs === unreadLatestActivitySeqs && nextFrontiers === unreadReadFrontiers) {
        return this
    }
    return copy(
        followedThreadUnread = threads,
        unreadLatestActivitySeqs = nextLatestSeqs,
        unreadReadFrontiers = nextFrontiers,
    )
}

/**
 * Where a thread's Unfollow is (web #11687 `markThreadUnfollowed`: unfollowing a thread clears its
 * unread at once; the server counts none for a thread the person doesn't follow).
 */
enum class HomeThreadUnfollowPhase {
    /** Unfollow chosen (sent, or waiting for its undo toast): the thread's unread leaves Home now. */
    Unfollowed,

    /** The server accepted it: nothing to put back any more. */
    Confirmed,

    /** Undo, or the request failed: the unread it had comes back. */
    Restored,
}

/** The optimistic half of an Unfollow: the thread's unread leaves its channel's mark. */
internal fun HomeSnapshot.withThreadUnfollowed(threadChannelId: String): HomeSnapshot =
    if (threadChannelId !in followedThreadUnread) this else copy(followedThreadUnread = followedThreadUnread - threadChannelId)

/**
 * Undo / failure of an Unfollow: puts back what [cleared] held, unless the thread has been listed
 * again since (a newer count stands).
 */
internal fun HomeSnapshot.withThreadUnfollowRestored(threadChannelId: String, cleared: HomeFollowedThreadUnread?): HomeSnapshot =
    if (cleared == null || threadChannelId in followedThreadUnread) {
        this
    } else {
        copy(followedThreadUnread = followedThreadUnread + (threadChannelId to cleared))
    }

/**
 * Sends an Unfollow through [unfollow] with its Home projection around it: [project] clears the
 * thread's unread first, then confirms it when the server accepts or restores it when it doesn't
 * (or the request is cancelled).
 */
internal suspend fun unfollowThreadProjectingHome(
    threadChannelId: ChannelId,
    project: (SharedHomeIntent.ProjectThreadUnfollow) -> Unit,
    unfollow: suspend (ChannelId) -> DataResult<ThreadActionResult>,
): DataResult<ThreadActionResult> {
    val threadId = threadChannelId.value
    project(SharedHomeIntent.ProjectThreadUnfollow(threadId, HomeThreadUnfollowPhase.Unfollowed))
    var settled = false
    try {
        val result = unfollow(threadChannelId)
        val accepted = result is DataResult.Content || result is DataResult.Empty
        project(
            SharedHomeIntent.ProjectThreadUnfollow(
                threadId,
                if (accepted) HomeThreadUnfollowPhase.Confirmed else HomeThreadUnfollowPhase.Restored,
            )
        )
        settled = true
        return result
    } finally {
        if (!settled) project(SharedHomeIntent.ProjectThreadUnfollow(threadId, HomeThreadUnfollowPhase.Restored))
    }
}

/** Web `FOLLOWED_THREADS_REFRESH_DELAY_MS`. */
internal const val HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS: Long = 1_500L

/**
 * Web `createCoalescedRefresh`, single-flight: the first [request] opens a [delayMillis] window that
 * every later request joins; one [run] follows the window. A request that arrives while [run] is in
 * flight schedules exactly one more window after it, so a reply that raced the response is not lost.
 * Hidden-page deferral is not needed here: a hidden Home marks itself dirty and reloads everything.
 */
internal class HomeCoalescedRefresh(
    private val scope: CoroutineScope,
    private val delayMillis: Long = HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS,
    private val run: suspend () -> Unit
) {
    private val lock = SynchronizedObject()
    private var job: Job? = null
    private var requested = false

    fun request() {
        synchronized(lock) {
            requested = true
            if (job?.isActive == true) return
            job = scope.launch { drain() }
        }
    }

    fun cancel() {
        synchronized(lock) {
            requested = false
            job?.cancel()
            job = null
        }
    }

    private suspend fun drain() {
        while (true) {
            delay(delayMillis)
            val proceed =
                synchronized(lock) {
                    if (!requested) {
                        job = null
                        false
                    } else {
                        requested = false
                        true
                    }
                }
            if (!proceed) return
            run()
        }
    }
}
