package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** The shortest wait before the one retry after a first transient pull failure (#proj-mobile:#112). */
internal const val SYNC_CHANGES_TRANSIENT_RETRY_MIN_MILLIS: Long = 30_000L

/** The longest wait before that retry. */
internal const val SYNC_CHANGES_TRANSIENT_RETRY_MAX_MILLIS: Long = 60_000L

/** Uniform in [SYNC_CHANGES_TRANSIENT_RETRY_MIN_MILLIS]..[SYNC_CHANGES_TRANSIENT_RETRY_MAX_MILLIS]. */
internal fun syncTransientRetryDelayMillis(random: Random): Long =
    random.nextLong(SYNC_CHANGES_TRANSIENT_RETRY_MIN_MILLIS, SYNC_CHANGES_TRANSIENT_RETRY_MAX_MILLIS + 1L)

/**
 * The one retry pull after a first transient failure kept the cursor (CTO, #proj-mobile:#112;
 * the "Pull failures" section of slock packages/shared/src/syncChanges.ts, web #13765: "After a
 * kept failure, pull once more 30–60 s later (random jitter), unless another pull starts first.
 * Never retry at once, never loop").
 *
 * Without it a kept cursor waits for the next normal trigger, which may be a long time on a
 * screen that stays open. The retry waits 30–60 s (random, so the apps that failed together in a
 * deploy do not come back together) and pulls once. If it also fails transiently it is the second
 * failure in a row on the same cursor: the engine forgets the cursor and the original caller's
 * fallback runs, as before #112. A retry that keeps the cursor again schedules nothing: one retry
 * per first failure.
 *
 * A normal trigger that pulls first ([onTriggerPull]) cancels the waiting retry (its pull does the
 * same job), and so does the server or the session going away ([cancelWhere], [cancelAll]).
 */
internal class RaftSyncTransientRetry(
    private val scope: CoroutineScope,
    private val delayMillis: () -> Long = { syncTransientRetryDelayMillis(Random.Default) },
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val pending = mutableMapOf<RaftSyncScope, Job>()

    /** Whether a retry of [key] is waiting. */
    fun isPending(key: RaftSyncScope): Boolean = synchronized(lock) { key in pending }

    /** A normal trigger is pulling [key]: a waiting retry would only ask the same again. */
    fun onTriggerPull(key: RaftSyncScope) {
        val job = synchronized(lock) { pending.remove(key) } ?: return
        job.cancel()
        logger("sync_changes_transient_retry serverId=${key.serverId} result=cancelled reason=trigger_pulled")
    }

    /**
     * A pull of [key] ended with [result]. A first transient failure that kept the cursor, in a
     * pull that was not itself the retry ([wasRetry]), schedules [retry] once after [delayMillis].
     */
    fun onPullFinished(key: RaftSyncScope, result: RaftSyncPullResult, wasRetry: Boolean, retry: suspend () -> Unit) {
        if (wasRetry || result !is RaftSyncPullResult.Failed || !result.cursorKept) return
        val waitMillis = delayMillis()
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(waitMillis)
                // Off the waiting list before it pulls: a trigger from now on has nothing to cancel.
                val stillScheduled = synchronized(lock) { (pending[key] === job).also { if (it) pending.remove(key) } }
                if (!stillScheduled) return@launch
                logger("sync_changes_transient_retry serverId=${key.serverId} result=pulling")
                retry()
            }
        val previous = synchronized(lock) { pending.put(key, job) }
        previous?.cancel()
        job.start()
        logger("sync_changes_transient_retry serverId=${key.serverId} result=scheduled delayMs=$waitMillis code=${result.code}")
    }

    /** The server or binding of every waiting retry [predicate] matches is gone. */
    fun cancelWhere(reason: String, predicate: (RaftSyncScope) -> Boolean) {
        val cancelled =
            synchronized(lock) {
                val keys = pending.keys.filter(predicate)
                keys.mapNotNull { key -> pending.remove(key)?.let { key to it } }
            }
        cancelled.forEach { (key, job) ->
            job.cancel()
            logger("sync_changes_transient_retry serverId=${key.serverId} result=cancelled reason=$reason")
        }
    }

    /** Sign-out, account or environment switch. */
    fun cancelAll(reason: String) = cancelWhere(reason) { true }
}
