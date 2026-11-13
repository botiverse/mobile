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
    // An app the server does not have would add a count nothing ever clears.
    event.source in FeedbackTicketSources &&
        (event.source != ProductFeedbackUnreadUpdated.WebSource || event.userId == ownerUserId)

/**
 * The signed-in person's unread feedback replies summed over every Hands app they file into (web,
 * Raft Desktop, Android, iOS, HarmonyOS): the dot on Settings and its Feedback row
 * ([build.raft.app.RaftMoreAttention.FeedbackReplies]), as the one Feedback list holds every app's
 * tickets (slock #14722; design settings.md "Feedback": "its pink pixel counts new replies on all of
 * them"). Process-wide, one per app.
 *
 * It holds each app's own total and shows their sum:
 * - [readTotal] reads the merged list's first page (`GET
 *   /api/product-feedback/tickets?sources=all&limit=1`): `unread_by_source` (each app's; an app that
 *   could not be read has no key, and keeps the value held) and `unread_total` (the sum). Read on start
 *   / sign-in and after a realtime (re)connect. No periodic read. The Feedback list's own first page
 *   says the same ([applyRead]).
 * - A push ([applyRealtime]) carries one app's whole total: it replaces that app's (`product_feedback:unread`
 *   is the web app's, `product_feedback:app_unread` its `source`'s).
 * - A ticket read here ([onTicketRead]) answers its own app's total: it replaces that app's.
 * Without `unread_by_source` (slock #14732; a server with only #14722 does not send it) only the
 * sum is known: it is `unread_total`, a "not known now" answer (null: an
 * app unavailable, or degraded) or a failure keeps the value held, and a push or a ticket read reads
 * the sum again ([refresh]).
 * A newer value always wins over a read started before it, and a refresh asked for while a read is
 * on its way reads once more after it (that read may have left before the change). Signed out: 0,
 * and no request.
 */
class RaftFeedbackUnreadStore(
    /** `android` / `ios` / `web` (desktop): `raftFeedbackWebViewClientKind()`; for logs. */
    private val clientKind: String,
    private val scope: CoroutineScope,
    private val readTotal: suspend () -> FeedbackResult<FeedbackUnreadRead>,
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

    /** Each app's own total (by source); null: only the sum is known. */
    private var bySource: Map<String, Int>? = null

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
            bySource = null
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
     * One app's pushed total replaces that app's (or, with only the sum known, the sum is read again).
     * False when it is not the signed-in person's ([principalId], or the web event's `userId`).
     */
    fun applyRealtime(principalId: String, event: ProductFeedbackUnreadUpdated): Boolean {
        var replaced = false
        val taken =
            synchronized(lock) {
                val owner = ownerUserId
                (owner != null && principalId.trim() == owner && feedbackUnreadEventIsMine(owner, event)).also { mine ->
                    if (mine) replaced = replaceSource(event.source, event.unreadTotal)
                }
            }
        logger("feedback_unread_realtime client=$clientKind source=${event.source} taken=$taken replaced=$replaced")
        if (taken && !replaced) refresh(reason = "realtime")
        return taken
    }

    /**
     * A ticket of [source] (null: this app's) was read here and answered its app's [total]: it replaces
     * that app's (or, with only the sum known or no total sent, the sum is read again).
     */
    fun onTicketRead(source: String?, total: Int?) {
        val app = source?.trim()?.takeIf { it.isNotEmpty() } ?: feedbackOwnSource(clientKind)
        val replaced = total != null && synchronized(lock) { ownerUserId != null && replaceSource(app, total) }
        logger("feedback_unread_ticket_read source=$app replaced=$replaced")
        if (!replaced) refresh(reason = "ticket_read")
    }

    /** Under [lock]: [source]'s total replaced and the sum shown; false when only the sum is known. */
    private fun replaceSource(source: String, total: Int): Boolean {
        if (source !in FeedbackTicketSources) return false
        val held = bySource ?: return false
        val next = held + (source to total.coerceAtLeast(0))
        bySource = next
        write(next.values.sum())
        return true
    }

    /**
     * Under [lock]: a first page's answer. Each app it names replaces that app's (one it does not
     * name keeps what it had); without names, its sum. False when it says nothing.
     */
    private fun applyCounts(read: FeedbackUnreadRead): Boolean {
        val named = read.bySource
        val total = read.total
        return when {
            named != null -> {
                val next = bySource.orEmpty() + named
                bySource = next
                write(next.values.sum())
                true
            }
            total != null -> {
                bySource = null
                write(total)
                true
            }
            else -> false
        }
    }

    /** Reads the sum again; one asked for while a read is on its way runs after it. */
    fun refresh(reason: String = "refresh") {
        read(reason = reason, coalesce = false, rereadWhenBusy = true)
    }

    /**
     * For a Feedback list read about to be sent: what to call with its first page's figures. They are
     * dropped when a push, a ticket read or another read landed after it was sent (or the account
     * changed), as those are newer (web `captureFeedbackUnreadListRead`).
     */
    fun captureListRead(): (FeedbackUnreadRead) -> Unit {
        val sentAt = synchronized(lock) { generation }
        return { read -> applyRead(read, sentAt) }
    }

    /**
     * This app's own Feedback list's first page answered [read] (`unread_by_source` / `unread_total`);
     * [sentAt]: the generation when its request was sent ([captureListRead]), null: apply as is.
     */
    fun applyRead(read: FeedbackUnreadRead, sentAt: Long? = null) {
        val outcome =
            synchronized(lock) {
                when {
                    ownerUserId == null -> return
                    sentAt != null && generation != sentAt -> "stale"
                    applyCounts(read) -> "applied"
                    else -> "unknown"
                }
            }
        logger("feedback_unread_local outcome=$outcome total=${mutableTotal.value}")
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
                val counts = (result as? FeedbackResult.Ok)?.value
                if (ownerUserId == owner && rereadPending) {
                    rereadPending = false
                    reread = true
                    readJob = null
                }
                when {
                    ownerUserId != owner || generation != startedAt -> "stale"
                    result is FeedbackResult.Failed -> "failed_${result.failure.kind}"
                    // Not known now (an app unavailable, or `degraded`), never 0: keep the value held.
                    counts == null || !applyCounts(counts) -> "unknown"
                    else -> "applied"
                }
            }
        logger("feedback_unread_read reason=$reason outcome=$outcome reread=$reread")
        if (reread) read(reason = "reread", coalesce = false)
    }
}
