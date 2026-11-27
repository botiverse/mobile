package build.raft.app.thread

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.data.DataResult
import build.raft.app.data.InboxFetchReason
import build.raft.app.data.RaftStaleReadRecovery
import build.raft.app.data.ThreadListSnapshot
import build.raft.app.data.isRwOverloadedError
import build.raft.app.data.rwRetryAfterMillis
import build.raft.app.data.withInboxFetchReason
import build.raft.app.resolve
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.thread.resolveThreadRouteIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Looks up the parent messages of unread threads that the loaded window does not show, for
 * [SharedThreadStore].
 *
 * The store dispatches into it on its own scope; store state is read and written only through the
 * accessors (the store's `_state` and `reduceState`), so stale checks run at the same points as before.
 */
internal class ThreadUnreadThreadParents(
    private val dataSource: SharedThreadDataSource,
    scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)?,
    private val currentState: () -> SharedThreadState,
    private val reduceState: (SharedThreadState) -> Boolean,
) {
    /** Unread-thread ids already asked of the followed-threads list on this page (never re-asked). */
    private val unreadThreadParentsRequested = mutableSetOf<String>()
    private var unreadThreadParentsInFlight = false
    /**
     * Task #164: while the followed-threads list is overloaded or stale, the ids it could not resolve
     * are asked again with the shared recovery backoff (foreground only; at once on return or reconnect).
     */
    private val unreadThreadParentsRecovery =
        RaftStaleReadRecovery(
            scope = scope,
            label = "followed_threads",
            diagnosticsLogger = { line -> diagnosticsLogger?.invoke(line) },
        )

    /** Ids asked for while a lookup was in flight: looked up right after it instead of dropped. */
    private val unreadThreadParentsQueued = LinkedHashSet<String>()

    suspend fun resolve(
        threadChannelIds: Set<String>,
        fetchReason: InboxFetchReason = InboxFetchReason.Initial,
    ) {
        val routeIdentity = currentState().payload.resolveThreadRouteIdentity()
        val channelId = routeIdentity.timelineChannelId?.takeIf { routeIdentity.surfaceKind == RaftThreadSurfaceKind.Channel }
        if (channelId == null) {
            unreadThreadParentsRecovery.reset()
            return
        }
        if (unreadThreadParentsInFlight) {
            // The page asks again only when its id set changes, so a request dropped here was lost
            // for good and its threads stayed without a target (artin: the pill worked once).
            unreadThreadParentsQueued += threadChannelIds
            return
        }
        val pending =
            threadChannelIds.filterTo(LinkedHashSet()) { id ->
                id.isNotBlank() && id !in unreadThreadParentsRequested && id !in currentState().unreadThreadParents
            }
        if (pending.isEmpty()) {
            unreadThreadParentsRecovery.reset()
            return
        }
        unreadThreadParentsInFlight = true
        unreadThreadParentsRequested += pending
        try {
            val result = withInboxFetchReason(fetchReason) { dataSource.fetchUnreadFollowedThreads() }
            if (currentState().payload.resolveThreadRouteIdentity() != routeIdentity) {
                diagnosticsLogger?.invoke("thread_unread_thread_parents_drop reason=route_changed")
                unreadThreadParentsRecovery.reset()
                return
            }
            val threads = (result as? DataResult.Content)?.data?.threads
            // Task #164: the read warehouse being behind is temporary. Ids it could not resolve
            // (an overload, or a remembered list that may predate them) may be asked again by the
            // next trigger; every other answer stays final for this page, as before.
            val rwStale = result?.isRwOverloadedError == true ||
                (result as? DataResult.Content)?.data?.staleReason != null
            if (threads == null) {
                if (rwStale) {
                    unreadThreadParentsRequested -= pending
                    scheduleUnreadThreadParentsRecovery(pending, result)
                }
                diagnosticsLogger?.invoke(
                    "thread_unread_thread_parents_result result=${result?.let { it::class.simpleName } ?: "unavailable"} " +
                        "requested=${pending.size} rwStale=$rwStale"
                )
                return
            }
            val resolved = threads.toThreadUnreadThreadParents(channelId)
            val unresolved = pending.filterNotTo(LinkedHashSet()) { it in resolved }
            if (rwStale && unresolved.isNotEmpty()) {
                unreadThreadParentsRequested -= unresolved
                scheduleUnreadThreadParentsRecovery(unresolved, result)
            } else {
                unreadThreadParentsRecovery.reset()
            }
            diagnosticsLogger?.invoke(
                "thread_unread_thread_parents_result result=content requested=${pending.size} " +
                    "resolved=${pending.count { it in resolved }} channelThreads=${resolved.size}"
            )
            val latest = currentState()
            val merged = latest.unreadThreadParents + resolved
            if (merged != latest.unreadThreadParents) {
                reduceState(latest.copy(unreadThreadParents = merged))
            }
        } finally {
            unreadThreadParentsInFlight = false
            if (unreadThreadParentsQueued.isNotEmpty() && currentCoroutineContext().isActive) {
                val queued = unreadThreadParentsQueued.toSet()
                unreadThreadParentsQueued.clear()
                resolve(queued)
            }
        }
    }

    private fun scheduleUnreadThreadParentsRecovery(ids: Set<String>, result: DataResult<ThreadListSnapshot>?) {
        val retryAfterMillis =
            (result as? DataResult.Content)?.data?.staleRetryAfterMillis
                ?: (result as? DataResult.Error)?.error?.rwRetryAfterMillis
        unreadThreadParentsRecovery.schedule(retryAfterMillis) {
            resolve(ids, fetchReason = InboxFetchReason.Visibility)
            true
        }
    }
}
