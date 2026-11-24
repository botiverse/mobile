package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long realtime hints for one server gather before their one pull. */
@RaftCoreInternalApi const val SYNC_CHANGES_REALTIME_DEBOUNCE_MILLIS: Long = 200L

/** The `sync_changes_pull trigger=` of a pull realtime events asked for. */
@RaftCoreInternalApi const val SYNC_CHANGES_REALTIME_TRIGGER: String = "realtime"

/**
 * One debounced incremental sync pull for realtime events the Activity list cannot settle from its
 * own read (inbox_row capability):
 *
 * - a live message or thread reply for a row the list lacks (`activity_inplace result=absent`): the
 *   list's re-read reads the same inbox read model, which may not have the row yet. The pull brings
 *   the row, or the server holds it and answers `retryAfterMs`, which [RaftSyncRetryAfter] honours.
 * - `notification:retract`: a row done or read in another session; its change comes through the pull.
 *
 * Per scope: hints within [debounceMillis] make one pull (trigger [SYNC_CHANGES_REALTIME_TRIGGER]).
 * A hint while that pull runs asks for one more, debounced again once it ends (the running pull may
 * have read before the change). Any other pull of the scope starting meanwhile ([onTriggerPull])
 * reads after the hint: the waiting pull is cancelled. Only in the foreground, checked when the hint
 * comes and again when the pull would start (a return to the foreground pulls anyway). Gone with the
 * server or the session ([cancelWhere], [cancelAll]).
 *
 * [pull] starts the pull (single-flight per scope with every other trigger) and returns its job, or
 * null when it was skipped (the scope is no longer current, the server no longer selected).
 */
@RaftCoreInternalApi class RaftSyncRealtimePull(
    private val scope: CoroutineScope,
    private val pull: (key: RaftSyncScope, trigger: String) -> Job?,
    private val debounceMillis: Long = SYNC_CHANGES_REALTIME_DEBOUNCE_MILLIS,
    private val isForeground: () -> Boolean = { true },
    private val logger: (String) -> Unit = {},
) {
    private class Entry {
        /** The debounce wait, before the pull starts. */
        var waiting: Job? = null

        /** The pull this class started, until it ends. */
        var running: Job? = null

        /** A hint came while [running]: one more pull once it ends. */
        var again: Boolean = false

        /** The hints the next pull answers (diagnostics only). */
        val reasons: MutableSet<String> = linkedSetOf()
    }

    private val lock = SynchronizedObject()
    private val entries = mutableMapOf<RaftSyncScope, Entry>()

    /** Whether a pull of [key] is waiting out the debounce. */
    fun isPending(key: RaftSyncScope): Boolean = synchronized(lock) { entries[key]?.waiting != null }

    /** A realtime event ([reason]) asks for a pull of [key]. */
    fun request(key: RaftSyncScope, reason: String) {
        if (!isForeground()) {
            logger("sync_changes_realtime serverId=${key.serverId} result=skipped reason=background hint=$reason")
            return
        }
        enqueue(key, reason)
    }

    private fun enqueue(key: RaftSyncScope, reason: String?) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            delay(debounceMillis)
            fire(key, job)
        }
        val outcome =
            synchronized(lock) {
                val entry = entries.getOrPut(key) { Entry() }
                if (reason != null) entry.reasons += reason
                when {
                    entry.waiting != null -> "joined"
                    entry.running != null -> {
                        entry.again = true
                        "queued"
                    }
                    else -> {
                        entry.waiting = job
                        "scheduled"
                    }
                }
            }
        if (outcome == "scheduled") job.start() else job.cancel()
        logger(
            "sync_changes_realtime serverId=${key.serverId} result=$outcome hint=${reason ?: "after_pull"}" +
                if (outcome == "scheduled") " delayMs=$debounceMillis" else ""
        )
    }

    private fun fire(key: RaftSyncScope, job: Job) {
        // Off the waiting list before it pulls: from now on a trigger has nothing to cancel.
        val reasons =
            synchronized(lock) {
                val entry = entries[key]
                if (entry == null || entry.waiting !== job) return
                entry.waiting = null
                entry.reasons.toList().also {
                    entry.reasons.clear()
                    if (entry.running == null) entries.remove(key)
                }
            }
        val hints = reasons.joinToString(",").ifEmpty { "after_pull" }
        if (!isForeground()) {
            logger("sync_changes_realtime serverId=${key.serverId} result=skipped reason=background hints=$hints")
            return
        }
        logger("sync_changes_realtime serverId=${key.serverId} result=pulling hints=$hints")
        val pullJob = pull(key, SYNC_CHANGES_REALTIME_TRIGGER)
        if (pullJob == null) {
            logger("sync_changes_realtime serverId=${key.serverId} result=skipped reason=not_pulled hints=$hints")
            return
        }
        synchronized(lock) { entries.getOrPut(key) { Entry() }.running = pullJob }
        pullJob.invokeOnCompletion { onPullEnded(key, pullJob) }
    }

    private fun onPullEnded(key: RaftSyncScope, pullJob: Job) {
        val again =
            synchronized(lock) {
                val entry = entries[key]
                if (entry == null || entry.running !== pullJob) return
                entry.running = null
                val again = entry.again
                entry.again = false
                if (!again && entry.waiting == null) entries.remove(key)
                again
            }
        if (!again) return
        if (!isForeground()) {
            synchronized(lock) { entries[key]?.takeIf { it.waiting == null && it.running == null }?.let { entries.remove(key) } }
            logger("sync_changes_realtime serverId=${key.serverId} result=skipped reason=background hint=after_pull")
            return
        }
        enqueue(key, reason = null)
    }

    /** Another pull of [key] is starting: it reads after every hint so far. */
    fun onTriggerPull(key: RaftSyncScope) {
        val waiting =
            synchronized(lock) {
                val entry = entries[key] ?: return
                entry.again = false
                val waiting = entry.waiting
                entry.waiting = null
                entry.reasons.clear()
                if (entry.running == null) entries.remove(key)
                waiting
            } ?: return
        waiting.cancel()
        logger("sync_changes_realtime serverId=${key.serverId} result=cancelled reason=trigger_pulled")
    }

    /** The server or binding of every scope [predicate] matches is gone. */
    fun cancelWhere(reason: String, predicate: (RaftSyncScope) -> Boolean) {
        val cancelled =
            synchronized(lock) {
                entries.keys.filter(predicate).mapNotNull { key -> entries.remove(key)?.let { key to it.waiting } }
            }
        cancelled.forEach { (key, waiting) ->
            waiting?.cancel()
            logger("sync_changes_realtime serverId=${key.serverId} result=cancelled reason=$reason")
        }
    }

    /** Sign-out, account or environment switch. */
    fun cancelAll(reason: String) = cancelWhere(reason) { true }
}
