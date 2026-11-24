package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The waits a sync pull's answer asks for (inbox_row capability, botiverse/slock #15312), per scope:
 *
 * - `retryAfterMs` ([RaftSyncPullResult.Synced.retryAfterMillis]): the server holds an Activity row back
 *   until its read model has its newest message; pull again after that delay (not at once: the row is
 *   not ready, so pulling at once would loop). Only in the foreground ([isForeground] when it fires: a
 *   return to the foreground pulls anyway), and coalesced: one waiting re-pull per scope, cancelled by
 *   any other pull of it ([onTriggerPull]), whose own answer asks again while the row is still held.
 * - `inboxReset` with `retryAfterMs` ([RaftSyncPullResult.Synced.inboxReloadAfterMillis]): retake the
 *   inbox snapshot after that delay (it is read from the same lagging read model). One waiting reload
 *   per scope; a second one asked for meanwhile joins it. Not cancelled by a pull: the pull does not
 *   retake the snapshot.
 *
 * Both are gone with the server or the session ([cancelWhere], [cancelAll]).
 */
@RaftCoreInternalApi class RaftSyncRetryAfter(
    private val scope: CoroutineScope,
    private val isForeground: () -> Boolean = { true },
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val repulls = mutableMapOf<RaftSyncScope, Job>()
    private val inboxReloads = mutableMapOf<RaftSyncScope, Job>()

    /** Whether a re-pull of [key] is waiting. */
    fun isRepullPending(key: RaftSyncScope): Boolean = synchronized(lock) { key in repulls }

    /** Whether an inbox reload of [key] is waiting. */
    fun isInboxReloadPending(key: RaftSyncScope): Boolean = synchronized(lock) { key in inboxReloads }

    /** Another pull of [key] is starting: a waiting re-pull would only ask the same again. */
    fun onTriggerPull(key: RaftSyncScope) {
        val job = synchronized(lock) { repulls.remove(key) } ?: return
        job.cancel()
        logger("sync_changes_retry_after serverId=${key.serverId} kind=pull result=cancelled reason=trigger_pulled")
    }

    /** A pull of [key] ended with [result]: schedule what its answer asked to wait for. */
    fun onPullFinished(key: RaftSyncScope, result: RaftSyncPullResult, repull: suspend () -> Unit, reloadInbox: suspend () -> Unit) {
        if (result !is RaftSyncPullResult.Synced) return
        result.inboxReloadAfterMillis?.let { wait -> schedule(key, inboxReloads, wait, "inbox_reload", replace = false, run = reloadInbox) }
        result.retryAfterMillis?.let { wait ->
            schedule(key, repulls, wait, "pull", replace = true) {
                if (isForeground()) {
                    repull()
                } else {
                    logger("sync_changes_retry_after serverId=${key.serverId} kind=pull result=skipped reason=background")
                }
            }
        }
    }

    private fun schedule(
        key: RaftSyncScope,
        pending: MutableMap<RaftSyncScope, Job>,
        waitMillis: Long,
        kind: String,
        replace: Boolean,
        run: suspend () -> Unit,
    ) {
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(waitMillis)
                // Off the waiting list before it runs: from now on a trigger has nothing to cancel.
                val stillScheduled = synchronized(lock) { (pending[key] === job).also { if (it) pending.remove(key) } }
                if (!stillScheduled) return@launch
                logger("sync_changes_retry_after serverId=${key.serverId} kind=$kind result=running")
                run()
            }
        var previous: Job? = null
        val joined =
            synchronized(lock) {
                if (!replace && key in pending) {
                    true
                } else {
                    previous = pending.put(key, job)
                    false
                }
            }
        if (joined) {
            job.cancel()
            logger("sync_changes_retry_after serverId=${key.serverId} kind=$kind result=joined")
            return
        }
        previous?.cancel()
        job.start()
        logger("sync_changes_retry_after serverId=${key.serverId} kind=$kind result=scheduled delayMs=$waitMillis")
    }

    /** The server or binding of every waiting job [predicate] matches is gone. */
    fun cancelWhere(reason: String, predicate: (RaftSyncScope) -> Boolean) {
        val cancelled =
            synchronized(lock) {
                listOf(repulls, inboxReloads).flatMap { pending ->
                    pending.keys.filter(predicate).mapNotNull { key -> pending.remove(key)?.let { key to it } }
                }
            }
        cancelled.forEach { (key, job) ->
            job.cancel()
            logger("sync_changes_retry_after serverId=${key.serverId} result=cancelled reason=$reason")
        }
    }

    /** Sign-out, account or environment switch. */
    fun cancelAll(reason: String) = cancelWhere(reason) { true }
}
