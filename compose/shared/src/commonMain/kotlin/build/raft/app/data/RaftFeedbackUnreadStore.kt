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
 * Whether [event] moved one of the signed-in person's ([ownerUserId]) feedback totals (slock
 * `docs/development/feedback-native-entry.md` "Unread replies"). Since the dot counts every app
 * (slock #14722, one list across apps), every app's event counts:
 * - `product_feedback:app_unread` (android / ios / ohos): sent only to the person's own room.
 * - `product_feedback:unread` (the web app's, which Raft Desktop shares): names whose total it is.
 */
internal fun feedbackUnreadEventIsMine(ownerUserId: String, event: ProductFeedbackUnreadUpdated): Boolean =
    event.source != ProductFeedbackUnreadUpdated.WebSource || event.userId == ownerUserId

/**
 * The signed-in person's unread feedback replies summed over every Hands app they file into (web,
 * Raft Desktop, Android, iOS, HarmonyOS): the dot on Settings and its Feedback row
 * ([build.raft.app.RaftMoreAttention.FeedbackReplies]), as the one Feedback list holds every app's
 * tickets (slock #14722; design settings.md "Feedback": "its pink pixel counts new replies on all of
 * them"). Process-wide, one per app.
 *
 * - Its value is [readTotal]: `unread_total` of the merged list's first page (`GET
 *   /api/product-feedback/tickets?sources=all&limit=1`), the only place the server sums the apps. Read
 *   on start / sign-in and after a realtime (re)connect. A "not known now" answer (null: an app
 *   unavailable, or degraded) or a failure keeps the value held. No periodic read.
 * - A push ([applyRealtime]) carries one app's whole total, not the sum, so any app's push reads the
 *   sum again ([refresh]); so does a ticket read here ([onTicketRead]: its `unread_total` is that
 *   ticket's app's only).
 * - This app's own Feedback list ([applyRead]): its first page's summed `unread_total`.
 * A newer value always wins over a read started before it, and a refresh asked for while a read is
 * on its way reads once more after it (that read may have left before the change). Signed out: 0,
 * and no request.
 */
class RaftFeedbackUnreadStore(
    /** `android` / `ios` / `web` (desktop): `raftFeedbackWebViewClientKind()`; for logs. */
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

    /** A refresh came while a read was on its way: read once more when it ends. */
    private var rereadPending = false

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
            rereadPending = false
            mutableTotal.value = 0
        }
        logger("feedback_unread_session signedIn=${id != null}")
        if (id != null) read(reason = "start", coalesce = false)
    }

    /** The realtime socket (re)joined its rooms: pushes sent while it was down are not replayed. */
    fun onReconnected() {
        read(reason = "reconnect", coalesce = true)
    }

    /**
     * One app's pushed total: the sum is read again. False when it is not the signed-in person's
     * ([principalId], or the web event's `userId`).
     */
    fun applyRealtime(principalId: String, event: ProductFeedbackUnreadUpdated): Boolean {
        val taken =
            synchronized(lock) {
                val owner = ownerUserId
                owner != null && principalId.trim() == owner && feedbackUnreadEventIsMine(owner, event)
            }
        logger("feedback_unread_realtime client=$clientKind source=${event.source} taken=$taken")
        if (taken) refresh(reason = "realtime")
        return taken
    }

    /** A ticket was read here: its app's total moved, so the sum is read again. */
    fun onTicketRead() {
        refresh(reason = "ticket_read")
    }

    /** Reads the sum again; one asked for while a read is on its way runs after it. */
    fun refresh(reason: String = "refresh") {
        read(reason = reason, coalesce = false, rereadWhenBusy = true)
    }

    /** This app's own Feedback list's first page answered [total] (its summed `unread_total`). */
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

    private fun read(reason: String, coalesce: Boolean, rereadWhenBusy: Boolean = false) {
        val job =
            synchronized(lock) {
                val owner = ownerUserId ?: return
                // A read not finished (or not started yet): a reconnect's would read the same; a
                // change's runs after it, as that read may have left before the change.
                if (readJob?.isCompleted == false) {
                    if (rereadWhenBusy) rereadPending = true
                    logger("feedback_unread_read_skip reason=$reason cause=in_flight reread=$rereadWhenBusy")
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
        var reread = false
        val outcome =
            synchronized(lock) {
                val total = (result as? FeedbackResult.Ok)?.value
                if (ownerUserId == owner && rereadPending) {
                    rereadPending = false
                    reread = true
                    readJob = null
                }
                when {
                    ownerUserId != owner || generation != startedAt -> "stale"
                    result is FeedbackResult.Failed -> "failed_${result.failure.kind}"
                    // Not known now (an app unavailable, or `degraded`), never 0: keep the value held.
                    total == null -> "unknown"
                    else -> {
                        write(total)
                        "applied"
                    }
                }
            }
        logger("feedback_unread_read reason=$reason outcome=$outcome reread=$reread")
        if (reread) read(reason = "reread", coalesce = false)
    }
}
