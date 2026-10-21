package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A lost release can never park background prewarm longer than this. */
internal const val SharedForegroundLoadLeaseTimeoutMillis = 5_000L
private const val SharedForegroundRecentOpenLimit = 32

/**
 * The thread a foreground open is loading. Either the timeline id or the reply parent pair may be
 * the only identity a route carries, so a background prewarm matches on either.
 */
internal data class SharedForegroundThreadKey(
    val serverId: String,
    val threadId: String?,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
) {
    fun matches(
        serverId: String,
        threadId: String,
        parentChannelId: String,
        parentMessageId: String,
    ): Boolean {
        if (this.serverId != serverId) return false
        if (!this.threadId.isNullOrBlank() && this.threadId == threadId) return true
        return !this.parentChannelId.isNullOrBlank() &&
            !this.parentMessageId.isNullOrBlank() &&
            this.parentChannelId == parentChannelId &&
            this.parentMessageId == parentMessageId
    }
}

internal enum class SharedForegroundGateWait {
    /** No foreground load was in progress. */
    Idle,
    /** Every foreground lease was released. */
    Released,
    /** At least one foreground lease outlived its timeout and was dropped. */
    TimedOut,
}

/**
 * Process-wide "a conversation / thread page is loading its first content" signal. The visible
 * page acquires a lease when it starts and releases it when its first content is ready, it
 * fails, or it closes; background prewarm waits for the gate to be idle before STARTING a load.
 *
 * Leak-proofing: [Lease.release] is idempotent, and every lease expires [leaseTimeoutMillis] after
 * it was acquired even if nobody releases it, so a lost release only delays prewarm, never stalls it.
 */
internal class SharedForegroundLoadGate(
    private val leaseTimeoutMillis: Long = SharedForegroundLoadLeaseTimeoutMillis,
    val nowMillis: () -> Long = activityMonotonicClock(),
) {
    inner class Lease internal constructor(
        val id: Long,
        val thread: SharedForegroundThreadKey?,
        val acquiredAtMillis: Long,
    ) {
        internal val expiresAtMillis: Long = acquiredAtMillis + leaseTimeoutMillis

        /** Returns true only for the call that actually released a still-live lease. */
        fun release(): Boolean = this@SharedForegroundLoadGate.release(this)

        fun heldMillis(): Long = (nowMillis() - acquiredAtMillis).coerceAtLeast(0L)
    }

    private val lock = SynchronizedObject()
    private var nextLeaseId = 0L
    private val leases = MutableStateFlow<Map<Long, Lease>>(emptyMap())
    private val recentOpens = ArrayDeque<Lease>()

    fun acquire(thread: SharedForegroundThreadKey?): Lease =
        synchronized(lock) {
            val lease = Lease(id = ++nextLeaseId, thread = thread, acquiredAtMillis = nowMillis())
            leases.value = leases.value + (lease.id to lease)
            if (thread != null) {
                recentOpens.addLast(lease)
                while (recentOpens.size > SharedForegroundRecentOpenLimit) recentOpens.removeFirst()
            }
            lease
        }

    private fun release(lease: Lease): Boolean =
        synchronized(lock) {
            if (lease.id !in leases.value) return@synchronized false
            leases.value = leases.value - lease.id
            true
        }

    /** Live (released-or-expired excluded) foreground loads. */
    fun activeCount(): Int = synchronized(lock) { pruneExpiredLocked(nowMillis()); leases.value.size }

    /**
     * True when a foreground open of this thread was acquired strictly after [sinceMillis]: that open
     * owns its own load (or joins an in-flight prewarm), so a prewarm queued before it is redundant.
     */
    fun foregroundOpenedSince(
        sinceMillis: Long,
        serverId: String,
        threadId: String,
        parentChannelId: String,
        parentMessageId: String,
    ): Boolean =
        synchronized(lock) {
            recentOpens.any { lease ->
                lease.acquiredAtMillis > sinceMillis &&
                    lease.thread?.matches(serverId, threadId, parentChannelId, parentMessageId) == true
            }
        }

    /** Suspends until no live foreground lease remains; bounded by each lease's own timeout. */
    suspend fun awaitIdle(): SharedForegroundGateWait {
        var waited = false
        var timedOut = false
        while (true) {
            val (snapshot, earliestExpiry, expired) =
                synchronized(lock) {
                    val now = nowMillis()
                    val expiredAny = pruneExpiredLocked(now)
                    val current = leases.value
                    Triple(current, current.values.minOfOrNull { it.expiresAtMillis }, expiredAny)
                }
            if (expired) timedOut = true
            if (earliestExpiry == null) {
                return when {
                    !waited -> SharedForegroundGateWait.Idle
                    timedOut -> SharedForegroundGateWait.TimedOut
                    else -> SharedForegroundGateWait.Released
                }
            }
            waited = true
            val remaining = (earliestExpiry - nowMillis()).coerceAtLeast(1L)
            withTimeoutOrNull(remaining) { leases.first { it !== snapshot } }
        }
    }

    private fun pruneExpiredLocked(now: Long): Boolean {
        val current = leases.value
        val live = current.filterValues { lease -> lease.expiresAtMillis > now }
        if (live.size == current.size) return false
        leases.value = live
        return true
    }
}
