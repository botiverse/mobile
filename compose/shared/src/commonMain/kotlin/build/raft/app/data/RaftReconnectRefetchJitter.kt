package build.raft.app.data

import kotlin.random.Random
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The most a full-list refetch waits after a socket reconnect (web: botiverse/slock #11453). */
internal const val RECONNECT_REFETCH_JITTER_MAX_MILLIS: Long = 20_000L

/**
 * How long after the user selects a server its next socket connect still counts as "the switch"
 * ([RaftReconnectRefetchDecision.ImmediateAfterSwitch]): the socket connects after the selection.
 */
internal val SERVER_SWITCH_CONNECT_WINDOW: Duration = 15.seconds

/** A uniformly random wait in `0..maxMillis` (both ends included). */
internal fun reconnectRefetchJitterMillis(random: Random, maxMillis: Long = RECONNECT_REFETCH_JITTER_MAX_MILLIS): Long =
    if (maxMillis <= 0L) 0L else random.nextLong(0L, maxMillis + 1L)

/** The signed-in socket identity a (re)connect belongs to: one per environment, account and server. */
internal data class RaftReconnectSession(
    val environmentId: String,
    val principalId: String,
    val serverId: String,
)

internal enum class RaftReconnectRefetchDecision {
    /** First connect of this session (app launch, sign-in, a newly joined server): the caller fetches now. */
    Immediate,

    /** A reconnect: the refetch runs once the client's jitter delay ends. */
    Delayed,

    /** A reconnect while a delay is already running: joins it (the newest refetch per source and server wins). */
    Coalesced,

    /**
     * A reconnect of the server the user just switched (back) to (Hands 81ad5dae): the caller
     * fetches now, keeping a reconnect's scope. A switch is one user, not a deploy storm.
     */
    ImmediateAfterSwitch,
}

/**
 * Spreads the full-list refetches that follow a socket RE-connect over 0–20 s (Tenny 10-27, web
 * botiverse/slock #11453 `scheduleReconnectReload`). After a server deploy every client reconnects
 * at once, and these refetches (on mobile: the members roster = `/servers/:id/members` + `/agents`
 * + `/servers/:id/machines`, plus peer-agent channel slices) were most of the server's load.
 * Goes away with incremental sync.
 *
 * - A session's first connect since the process started or since [reset] (sign-out, account or
 *   environment switch) is [RaftReconnectRefetchDecision.Immediate]: the caller fetches at once.
 * - Every later connect of the same session is a reconnect: its refetch waits one uniform
 *   0–[maxMillis] delay. There is one delay per client: reconnects of other servers or sources,
 *   and further reconnects while it runs, join the same wait (no stacking, one refetch per
 *   source and server, the newest one).
 * - [onServerSelected]: the user switched to that server, so its waiting refetches run now
 *   ([flushServer]), and its next connect within [switchConnectWindow] fetches at once
 *   ([RaftReconnectRefetchDecision.ImmediateAfterSwitch]): the switch happens before the socket
 *   connects, so on a switch back to a server visited before, the flush alone finds nothing.
 * - [dropServer]: the server is gone (access lost); its waiting refetches are dropped.
 * - [reset] cancels everything waiting and forgets the sessions.
 *
 * NOT routed through here (stay immediate): message catch-up (`sync:resume`, the visible
 * conversation's catch-up, catch-up-required Home reloads), unread counts, Activity, and the
 * presence/epoch bookkeeping that must precede the new socket's frames.
 */
internal class RaftReconnectRefetchJitter(
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val maxMillis: Long = RECONNECT_REFETCH_JITTER_MAX_MILLIS,
    private val logger: (String) -> Unit = {},
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val switchConnectWindow: Duration = SERVER_SWITCH_CONNECT_WINDOW,
) {
    private class Entry(val serverId: String, val source: String, val refetch: suspend () -> Unit)

    private class Batch(val delayMillis: Long) {
        val entries = LinkedHashMap<String, Entry>()
        var timer: Job? = null
    }

    private val lock = SynchronizedObject()
    private val connectedSessions = mutableSetOf<RaftReconnectSession>()
    private var batch: Batch? = null
    /** Servers the user selected, and when; consumed by that server's next connect. */
    private val selectedAt = mutableMapOf<String, TimeMark>()

    /**
     * A socket of [session] connected. On [RaftReconnectRefetchDecision.Immediate] the caller runs
     * its refetch itself; otherwise [refetch] runs here once the delay ends.
     */
    fun onConnected(
        session: RaftReconnectSession,
        source: String,
        refetch: suspend () -> Unit,
    ): RaftReconnectRefetchDecision {
        val serverId = session.serverId.trim()
        val entryKey = "$source|$serverId"
        var started: Batch? = null
        var delayMillis = 0L
        var sources = source
        var emptied: Batch? = null
        val decision =
            synchronized(lock) {
                val selection = selectedAt.remove(serverId)
                if (connectedSessions.add(session)) return@synchronized RaftReconnectRefetchDecision.Immediate
                if (selection != null && selection.elapsedNow() <= switchConnectWindow) {
                    // The caller fetches now: an older reconnect's refetch for this server is superseded.
                    batch?.let { current ->
                        current.entries.remove(entryKey)
                        if (current.entries.isEmpty()) {
                            batch = null
                            emptied = current
                        }
                    }
                    return@synchronized RaftReconnectRefetchDecision.ImmediateAfterSwitch
                }
                val existing = batch
                val target = existing ?: Batch(reconnectRefetchJitterMillis(random, maxMillis))
                target.entries[entryKey] = Entry(serverId, source, refetch)
                delayMillis = target.delayMillis
                sources = target.sourcesLabelLocked()
                if (existing != null) return@synchronized RaftReconnectRefetchDecision.Coalesced
                batch = target
                started = target
                RaftReconnectRefetchDecision.Delayed
            }
        // One line per (re)connect: what was decided and what the client's one wait now covers.
        logger(
            "reconnect_refetch_jitter decision=${decision.name.lowercase()} serverId=$serverId " +
                "delayMs=$delayMillis sources=$sources"
        )
        emptied?.timer?.cancel()
        started?.let(::startTimer)
        return decision
    }

    /**
     * The user selected [serverId] (a server switch, or the first selection at launch): what waits
     * for it runs now, and its next connect within [switchConnectWindow] fetches at once.
     */
    fun onServerSelected(serverId: String, reason: String) {
        val id = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) { selectedAt[id] = timeSource.markNow() }
        flushServer(id, reason)
    }

    /**
     * A full-list refetch that must never run at once, whatever the session: the fallback of an
     * incremental pull that failed or timed out (every client fails together during a server
     * incident, Tenny 10-27). Joins the client's running wait like a reconnect, or starts one.
     */
    fun scheduleDelayed(serverId: String, source: String, refetch: suspend () -> Unit): RaftReconnectRefetchDecision {
        val id = serverId.trim()
        val entryKey = "$source|$id"
        var started: Batch? = null
        var delayMillis = 0L
        var sources = source
        val decision =
            synchronized(lock) {
                val existing = batch
                val target = existing ?: Batch(reconnectRefetchJitterMillis(random, maxMillis))
                target.entries[entryKey] = Entry(id, source, refetch)
                delayMillis = target.delayMillis
                sources = target.sourcesLabelLocked()
                if (existing != null) return@synchronized RaftReconnectRefetchDecision.Coalesced
                batch = target
                started = target
                RaftReconnectRefetchDecision.Delayed
            }
        logger(
            "reconnect_refetch_jitter decision=${decision.name.lowercase()} serverId=$id " +
                "delayMs=$delayMillis sources=$sources trigger=fallback"
        )
        started?.let(::startTimer)
        return decision
    }

    /** The user switched to [serverId]: a server switch fetches at once, so its waiting refetches run now. */
    fun flushServer(serverId: String, reason: String) {
        val id = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val flushed = takeServerEntries(id) ?: return
        logger("reconnect_refetch_jitter_flush serverId=$id reason=$reason sources=${flushed.joinToString(",") { it.source }}")
        scope.launch { runEntries(flushed) }
    }

    /** [serverId] is gone for this account: nothing waiting for it may run. */
    fun dropServer(serverId: String, reason: String) {
        val id = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            connectedSessions.removeAll { it.serverId.trim() == id }
            selectedAt.remove(id)
        }
        val dropped = takeServerEntries(id) ?: return
        logger("reconnect_refetch_jitter_drop serverId=$id reason=$reason sources=${dropped.joinToString(",") { it.source }}")
    }

    /** Sign-out, account or environment switch: cancel what waits and start over (next connect is a first one). */
    fun reset(reason: String) {
        val cancelled =
            synchronized(lock) {
                connectedSessions.clear()
                selectedAt.clear()
                batch.also { batch = null }
            }
        cancelled?.timer?.cancel()
        if (cancelled != null) {
            val sources = synchronized(lock) { cancelled.sourcesLabelLocked() }
            logger("reconnect_refetch_jitter_cancel reason=$reason sources=$sources")
        }
    }

    private fun takeServerEntries(serverId: String): List<Entry>? {
        var emptied: Batch? = null
        val taken =
            synchronized(lock) {
                val current = batch ?: return null
                val keys = current.entries.filterValues { it.serverId == serverId }.keys.toList()
                if (keys.isEmpty()) return null
                val entries = keys.mapNotNull { current.entries.remove(it) }
                if (current.entries.isEmpty()) {
                    batch = null
                    emptied = current
                }
                entries
            }
        emptied?.timer?.cancel()
        return taken
    }

    private fun startTimer(started: Batch) {
        val job =
            scope.launch {
                delay(started.delayMillis)
                val entries =
                    synchronized(lock) {
                        if (batch !== started) return@launch
                        batch = null
                        started.entries.values.toList()
                    }
                runEntries(entries)
            }
        synchronized(lock) {
            // Flushed or reset before the timer was attached: the batch is no longer current.
            if (batch === started) started.timer = job else job.cancel()
        }
    }

    private suspend fun runEntries(entries: List<Entry>) {
        entries.forEach { entry ->
            try {
                entry.refetch()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger(
                    "reconnect_refetch_jitter_failed serverId=${entry.serverId} source=${entry.source} " +
                        "type=${error::class.simpleName ?: "unknown"}"
                )
            }
        }
    }

    private fun Batch.sourcesLabelLocked(): String =
        entries.values.joinToString(",") { "${it.source}:${it.serverId}" }
}
