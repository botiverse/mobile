package build.raft.app.data

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
): HomeThreadReplyDecision {
    val me = currentMember.id.value
    // The server never counts the user's own reply as unread; nothing to bump or reload.
    if (event.latestReply?.senderId == me) return HomeThreadReplyDecision.Ignore
    if (event.threadChannelId.value !in followedThreadUnread) return HomeThreadReplyDecision.Refresh
    if (openConversationId == event.threadChannelId.value) return HomeThreadReplyDecision.Ignore
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

/** A reloaded summary replaces the followed threads wholesale (web `loadFollowedThreads`). */
internal fun HomeSnapshot.withFollowedThreadUnread(threads: Map<String, HomeFollowedThreadUnread>): HomeSnapshot =
    if (threads == followedThreadUnread) this else copy(followedThreadUnread = threads)

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
