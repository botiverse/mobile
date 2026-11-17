package build.raft.app

import androidx.compose.runtime.withFrameNanos
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal enum class AppShellChildPagePrewarmMode {
    Disabled,
    Canary,
    Enabled
}

internal data class AppShellChildPagePrewarmPolicy(
    val mode: AppShellChildPagePrewarmMode,
    val maxControllers: Int
)

internal fun appShellChildPagePrewarmPolicy(
    mode: AppShellChildPagePrewarmMode
): AppShellChildPagePrewarmPolicy =
    when (mode) {
        AppShellChildPagePrewarmMode.Disabled ->
            AppShellChildPagePrewarmPolicy(mode, maxControllers = 0)
        AppShellChildPagePrewarmMode.Canary ->
            AppShellChildPagePrewarmPolicy(mode, maxControllers = 1)
        AppShellChildPagePrewarmMode.Enabled ->
            AppShellChildPagePrewarmPolicy(mode, maxControllers = 3)
    }

/**
 * Computers prewarm is admitted only after auth/server resolution and Home content.
 * The caller still enters Kuikly's idle context before dispatching the Background
 * request, so this gate cannot move the request ahead of Home's first content paint.
 */
internal fun appShellComputersPrewarmServerId(
    readyServerId: String?,
    sessionReady: Boolean,
    homeContentReady: Boolean
): String? =
    readyServerId
        ?.trim()
        ?.takeIf { sessionReady && homeContentReady && it.isNotBlank() }

/**
 * Product-level admission gate for speculative prewarm work.
 *
 * Foreground interaction invalidates the current generation. A lease may
 * commit exactly one bounded mutation only while its generation is still
 * current, and only one speculative lease may be held process-wide.
 */
internal class PrewarmAdmissionGate {
    private val lock = SynchronizedObject()
    private var generation = 0L
    private var nextLeaseId = 0L
    private var activeLeaseId: Long? = null
    private var activeLeaseCommitted = false
    private var latestInvalidationReason = "initial"

    fun generation(): Long = synchronized(lock) { generation }

    fun invalidate(reason: String) {
        synchronized(lock) {
            generation += 1L
            latestInvalidationReason = reason
        }
    }

    fun tryAcquire(expectedGeneration: Long): Lease? =
        synchronized(lock) {
            if (generation != expectedGeneration || activeLeaseId != null) {
                null
            } else {
                val leaseId = ++nextLeaseId
                activeLeaseId = leaseId
                activeLeaseCommitted = false
                Lease(this, leaseId, expectedGeneration)
            }
        }

    private fun commit(leaseId: Long, leaseGeneration: Long, block: () -> Unit): Boolean =
        synchronized(lock) {
            if (
                activeLeaseId != leaseId ||
                activeLeaseCommitted ||
                generation != leaseGeneration
            ) {
                false
            } else {
                activeLeaseCommitted = true
                block()
                true
            }
        }

    private fun release(leaseId: Long) {
        synchronized(lock) {
            if (activeLeaseId == leaseId) {
                activeLeaseId = null
                activeLeaseCommitted = false
            }
        }
    }

    fun latestInvalidationReason(): String = synchronized(lock) { latestInvalidationReason }

    internal class Lease(
        private val gate: PrewarmAdmissionGate,
        private val leaseId: Long,
        private val generation: Long
    ) {
        fun commit(block: () -> Unit): Boolean = gate.commit(leaseId, generation, block)

        fun release() = gate.release(leaseId)
    }
}

private val GlobalPrewarmAdmissionGate = PrewarmAdmissionGate()

/** Shared speculative budget: every shell and future prewarm surface contends for one lease. */
internal fun sharedPrewarmAdmissionGate(): PrewarmAdmissionGate = GlobalPrewarmAdmissionGate

internal suspend fun awaitStablePrewarmGeneration(
    gate: PrewarmAdmissionGate,
    stableFrameCount: Int = 2
): Long {
    require(stableFrameCount > 0)
    while (true) {
        val observedGeneration = gate.generation()
        repeat(stableFrameCount) { withFrameNanos { } }
        if (gate.generation() == observedGeneration) {
            return observedGeneration
        }
    }
}
