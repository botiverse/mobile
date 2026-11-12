package build.raft.app.data

import build.raft.app.model.ProductFeedbackUnreadUpdated
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Reconnects of several server sockets at once (or a flapping network) share one read. */
internal const val FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS: Long = 10_000L

/**
 * Whether [event] is this app's own feedback unread total (slock `docs/development/feedback-native-entry.md`
 * "Unread replies"): every connection of the person receives every app's event.
 * - The Android / iOS app ([clientKind] `android` / `ios`): only `product_feedback:app_unread` of its
 *   own platform; never the web's `product_feedback:unread`.
 * - The desktop app files into the web app (client kind `web`): only `product_feedback:unread`, and
 *   only for the signed-in person ([ownerUserId]).
 */
internal fun feedbackUnreadEventIsOwn(
    clientKind: String,
    ownerUserId: String,
    event: ProductFeedbackUnreadUpdated,
): Boolean {
    val kind = clientKind.trim().ifEmpty { ProductFeedbackUnreadUpdated.WebSource }
    return if (kind == ProductFeedbackUnreadUpdated.WebSource) {
        event.source == ProductFeedbackUnreadUpdated.WebSource && event.userId == ownerUserId
    } else {
        event.source == kind
    }
}

/**
 * The signed-in person's unread feedback replies in this app's Hands app: the dot on Settings and
 * its Feedback row ([build.raft.app.RaftMoreAttention.FeedbackReplies]). Process-wide, one per app.
 *
 * - Its first value, and the value after a realtime (re)connect, is [readTotal] (`GET
 *   /api/product-feedback/tickets?limit=1` with this app's client kind). A "not known now" answer
 *   (null) or a failure keeps the value held. No periodic read.
 * - Then the server's push of the whole total ([applyRealtime], this app's event only).
 * - And this app's own Feedback reads ([applyRead]): the list's and an opened ticket's
 *   `unread_total`, so reading a reply clears the dot at once.
 * A newer value always wins over a read started before it. Signed out: 0, and no request.
 */
class RaftFeedbackUnreadStore(
    /** `android` / `ios` / `web` (desktop): `raftFeedbackWebViewClientKind()`. */
    private val clientKind: String,
    private val scope: CoroutineScope,
    private val readTotal: suspend () -> FeedbackResult<Int?>,
    private val reconnectWindowMillis: Long = FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val logger: (String) -> Unit = {},
) {
    private val mutableTotal = MutableStateFlow(0)

    /** The unread total; 0 while signed out or not read yet. */
    val unreadTotal: StateFlow<Int> = mutableTotal.asStateFlow()

    private val lock = SynchronizedObject()
    private var ownerUserId: String? = null

    /** Bumped by every write and account change: a read that started before it does not land. */
    private var generation = 0L
    private var readJob: Job? = null
    private var lastReadStarted: TimeMark? = null

    /** Follows the signed-in person (null: signed out) for the store's lifetime. */
    fun follow(userIds: Flow<String?>): Job =
        scope.launch { userIds.distinctUntilChanged().collect { onSession(it) } }

    /** Another (or no) person signed in: their total starts at 0 and is read; signed out: no read. */
    fun onSession(userId: String?) {
        val id = userId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            if (id == ownerUserId) return
            ownerUserId = id
            generation++
            readJob?.cancel()
            readJob = null
            lastReadStarted = null
            mutableTotal.value = 0
        }
        logger("feedback_unread_session signedIn=${id != null}")
        if (id != null) read(reason = "start", coalesce = false)
    }

    /** The realtime socket (re)joined its rooms: pushes sent while it was down are not replayed. */
    fun onReconnected() {
        read(reason = "reconnect", coalesce = true)
    }

    /** A pushed total; false when it is not this app's or not the signed-in person's ([principalId]). */
    fun applyRealtime(principalId: String, event: ProductFeedbackUnreadUpdated): Boolean {
        val applied =
            synchronized(lock) {
                val owner = ownerUserId
                if (owner == null || principalId.trim() != owner || !feedbackUnreadEventIsOwn(clientKind, owner, event)) {
                    false
                } else {
                    write(event.unreadTotal)
                    true
                }
            }
        logger("feedback_unread_realtime source=${event.source} applied=$applied total=${if (applied) event.unreadTotal else -1}")
        return applied
    }

    /** This app's own Feedback list or ticket read answered [total] (its `unread_total`). */
    fun applyRead(total: Int) {
        synchronized(lock) {
            if (ownerUserId == null) return
            write(total)
        }
        logger("feedback_unread_local total=$total")
    }

    private fun write(total: Int) {
        generation++
        mutableTotal.value = total.coerceAtLeast(0)
    }

    private fun read(reason: String, coalesce: Boolean) {
        val job =
            synchronized(lock) {
                val owner = ownerUserId ?: return
                // A read not finished (or not started yet): this one would read the same.
                if (readJob?.isCompleted == false) {
                    logger("feedback_unread_read_skip reason=$reason cause=in_flight")
                    return
                }
                val last = lastReadStarted
                if (coalesce && last != null && last.elapsedNow().inWholeMilliseconds < reconnectWindowMillis) {
                    logger("feedback_unread_read_skip reason=$reason cause=coalesced")
                    return
                }
                lastReadStarted = timeSource.markNow()
                val startedAt = generation
                scope.launch(start = CoroutineStart.LAZY) { runRead(owner, startedAt, reason) }.also { readJob = it }
            }
        job.start()
    }

    private suspend fun runRead(owner: String, startedAt: Long, reason: String) {
        val result =
            try {
                readTotal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logger("feedback_unread_read_failed reason=$reason error=${error::class.simpleName ?: "unknown"}")
                return
            }
        val outcome =
            synchronized(lock) {
                val total = (result as? FeedbackResult.Ok)?.value
                when {
                    ownerUserId != owner || generation != startedAt -> "stale"
                    result is FeedbackResult.Failed -> "failed_${result.failure.kind}"
                    // `degraded`: not known now, never 0; keep the value held.
                    total == null -> "unknown"
                    else -> {
                        write(total)
                        "applied"
                    }
                }
            }
        logger("feedback_unread_read reason=$reason outcome=$outcome")
    }
}
