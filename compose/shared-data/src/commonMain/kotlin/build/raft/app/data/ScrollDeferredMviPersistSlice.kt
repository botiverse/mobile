package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.RaftScrollFrameSignal
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Control surface for fencing deferred persist writes on server-access loss or sign-out. */
@RaftCoreInternalApi interface RaftScrollDeferredPersistControl {
    /** Cancels and fences any pending deferred writes for [serverId] (and optional [userId]). */
    fun dropServer(serverId: String, userId: String? = null)

    /** Cancels and fences every pending deferred write (sign-out / account switch). */
    fun discardPending()
}

/**
 * Defers and coalesces non-urgent MVI persistence writes while Home is actively scrolling
 * (#wg-mobile-feedback:#271), keeping JSON encoding and MMKV/index writes off the hot scroll
 * window without altering hydration or immediate removal semantics.
 *
 * - When [isScrollActive] is false, writes run inline identically to [delegate].
 * - Removals (`willRemove`) always run immediately and supersede any deferred write for that key.
 * - No-op intents (`!willPersist && !willRemove`) return without disturbing pending writes.
 * - Multiple writes for the same persist key during a scroll coalesce to the latest transition and
 *   execute once on [persistScope] after [awaitScrollSettled] returns.
 * - Lock ordering is strictly `writeLock -> stateLock` (never inverted) so hydration, synchronous
 *   writes, deferred flushes, and [dropServer]/[discardPending] barriers are deadlock-free and
 *   monotonic: an older or evicted deferred write can never overwrite newer or deleted storage.
 */
@RaftCoreInternalApi class ScrollDeferredMviPersistSlice<State, Intent, Snapshot : Any>(
    private val delegate: MviPersistMiddleware<State, Intent, Snapshot>,
    private val persistScope: CoroutineScope,
    private val isScrollActive: () -> Boolean = { RaftScrollFrameSignal.isHomeScrolling },
    private val awaitScrollSettled: suspend () -> Unit = { RaftScrollFrameSignal.awaitHomeScrollSettled() },
) : MviPersistSlice<State, Intent>, RaftScrollDeferredPersistControl {
    private data class PendingWrite<State, Intent>(
        val seq: Long,
        val previous: State,
        val intent: Intent,
        val next: State,
    )

    private val stateLock = SynchronizedObject()
    private val writeLock = SynchronizedObject()
    private var seqCounter = 0L
    private val latestSeqByKey = mutableMapOf<String, Long>()
    private val pendingByKey = LinkedHashMap<String, PendingWrite<State, Intent>>()
    private var flushJob: Job? = null

    override fun captureTransitionState(
        previous: State,
        intent: Intent,
        reduced: State,
    ): MviPersistSliceTransitionSnapshot? =
        synchronized(writeLock) { delegate.captureTransitionState(previous, intent, reduced) }

    override fun reduce(previous: State, intent: Intent, reduced: State): State {
        if (!delegate.willHydrate(previous, intent, reduced)) return reduced
        return synchronized(writeLock) {
            val key = delegate.persistKey(previous, intent, reduced)
            if (key != null) {
                val pending = synchronized(stateLock) { pendingByKey.remove(key) }
                if (pending != null) {
                    val stillLatest = synchronized(stateLock) { latestSeqByKey[key] == pending.seq }
                    if (stillLatest) {
                        delegate.onTransition(pending.previous, pending.intent, pending.next)
                    }
                }
            }
            delegate.reduce(previous, intent, reduced)
        }
    }

    override fun reduceWithTransition(
        previous: State,
        intent: Intent,
        reduced: State,
        snapshot: MviPersistSliceTransitionSnapshot?,
    ): State =
        synchronized(writeLock) {
            delegate.reduceWithTransition(previous, intent, reduced, snapshot)
        }

    override fun applyTransition(
        previous: State,
        intent: Intent,
        next: State,
        snapshot: MviPersistSliceTransitionSnapshot?,
    ) {
        synchronized(writeLock) {
            delegate.applyTransition(previous, intent, next, snapshot)
        }
    }

    override fun onTransition(previous: State, intent: Intent, next: State) {
        val key = delegate.persistKey(previous, intent, next) ?: return
        val shouldRemove = delegate.willRemove(previous, intent, next)
        val shouldPersist = !shouldRemove && delegate.willPersist(previous, intent, next)
        if (!shouldRemove && !shouldPersist) return

        if (shouldRemove || !isScrollActive()) {
            val seq =
                synchronized(stateLock) {
                    val nextSeq = ++seqCounter
                    latestSeqByKey[key] = nextSeq
                    pendingByKey.remove(key)
                    nextSeq
                }
            synchronized(writeLock) {
                val stillLatest = synchronized(stateLock) { latestSeqByKey[key] == seq }
                if (stillLatest) {
                    delegate.onTransition(previous, intent, next)
                }
            }
            return
        }

        val jobToStart =
            synchronized(stateLock) {
                val nextSeq = ++seqCounter
                latestSeqByKey[key] = nextSeq
                pendingByKey[key] = PendingWrite(seq = nextSeq, previous = previous, intent = intent, next = next)
                if (flushJob?.isActive != true) {
                    persistScope.launch(start = CoroutineStart.LAZY) { flushPending() }.also { flushJob = it }
                } else {
                    null
                }
            }
        jobToStart?.start()
    }

    override fun dropServer(serverId: String, userId: String?) {
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val user = userId?.trim()?.takeIf { it.isNotEmpty() }
        // Acquire writeLock first so any in-flight flush completes before caller deletes from disk,
        // while bumping seq under stateLock fences all matching queued/in-flight transitions.
        synchronized(writeLock) {
            synchronized(stateLock) {
                val matchingKeys =
                    (pendingByKey.keys + latestSeqByKey.keys).filter { key ->
                        keyMatchesServerScope(key, serverId = server, userId = user)
                    }
                for (key in matchingKeys) {
                    latestSeqByKey[key] = ++seqCounter
                    pendingByKey.remove(key)
                }
            }
        }
    }

    override fun discardPending() {
        synchronized(writeLock) {
            synchronized(stateLock) {
                val keys = (pendingByKey.keys + latestSeqByKey.keys).toList()
                for (key in keys) {
                    latestSeqByKey[key] = ++seqCounter
                }
                pendingByKey.clear()
            }
        }
    }

    private suspend fun flushPending() {
        val currentJob = coroutineContext[Job]
        try {
            while (true) {
                val hasWork =
                    synchronized(stateLock) {
                        if (pendingByKey.isEmpty()) {
                            if (flushJob === currentJob) flushJob = null
                            false
                        } else {
                            true
                        }
                    }
                if (!hasWork) return
                if (isScrollActive()) {
                    awaitScrollSettled()
                }
                val batch =
                    synchronized(stateLock) {
                        if (pendingByKey.isEmpty()) {
                            if (flushJob === currentJob) flushJob = null
                            return
                        }
                        pendingByKey.entries.map { it.key to it.value }.also { pendingByKey.clear() }
                    }
                for ((key, pending) in batch) {
                    synchronized(writeLock) {
                        val stillLatest = synchronized(stateLock) { latestSeqByKey[key] == pending.seq }
                        if (stillLatest) {
                            delegate.onTransition(pending.previous, pending.intent, pending.next)
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            synchronized(stateLock) {
                if (flushJob === currentJob) flushJob = null
            }
            throw cancellation
        } catch (error: Throwable) {
            synchronized(stateLock) {
                if (flushJob === currentJob) flushJob = null
            }
            throw error
        }
    }

    private companion object {
        val ScopePattern = Regex(""":user:([^:]+):server:([^:]+)""")

        fun keyMatchesServerScope(key: String, serverId: String, userId: String?): Boolean {
            val match = ScopePattern.find(key) ?: return false
            val (keyUser, keyServer) = match.destructured
            return keyServer == serverId && (userId == null || keyUser == userId)
        }
    }
}
