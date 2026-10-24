package build.raft.app.network

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.TimeSource

private val RaftNetworkRequestLifetimeOrigin = TimeSource.Monotonic.markNow()

/**
 * App-owned classification for lifecycle retirement.
 *
 * Uploads and message sends are process-bound long-running work: a page or Activity visibility
 * edge must not cancel them and turn their server outcome unknown. So are the one-time auth code
 * exchanges: they run right as the app returns from the browser, i.e. exactly at a visibility edge,
 * and their code cannot be used twice (Android build 39: cancelled 18-34 ms after it was sent).
 * Other requests keep the existing visibility-scoped retirement contract. This coordinator never
 * replays any class.
 */
internal enum class RaftNetworkMethodClass {
    Read,
    Mutation,
    ProcessMutation
}

internal fun raftNetworkMethodClass(
    method: String,
    url: String,
    serverScoped: Boolean
): RaftNetworkMethodClass {
    val normalizedMethod = method.trim().uppercase()
    if (normalizedMethod == "GET" || normalizedMethod == "HEAD") {
        return RaftNetworkMethodClass.Read
    }
    val path = url.substringBefore('?').trimEnd('/')
    if (normalizedMethod == "POST" && ONE_TIME_AUTH_CODE_EXCHANGE_PATHS.any(path::endsWith)) {
        return RaftNetworkMethodClass.ProcessMutation
    }
    return if (
        serverScoped && normalizedMethod == "POST" &&
        (path.endsWith("/api/messages") || path.endsWith("/api/attachments/upload"))
    ) {
        RaftNetworkMethodClass.ProcessMutation
    } else {
        RaftNetworkMethodClass.Mutation
    }
}

/** Not server-scoped (no server yet); matched by suffix so a self-hosted API base path works too. */
private val ONE_TIME_AUTH_CODE_EXCHANGE_PATHS =
    listOf("/auth/app-login/complete")

internal fun interface RaftNetworkRequestCancelHandle {
    fun cancel()
}

internal data class RaftNetworkRequestLifetimeDescriptor(
    val internalRequestId: Long,
    val generation: Long,
    val methodClass: RaftNetworkMethodClass,
    val startedAtMillis: Long,
    /** Observational copy of the transport / request-scheduler timeout policy. */
    val configuredDeadlineAtMillis: Long
)

internal sealed interface RaftNetworkRequestLifetimeOutcome<out T> {
    data class Completed<T>(
        val value: T,
        val descriptor: RaftNetworkRequestLifetimeDescriptor
    ) : RaftNetworkRequestLifetimeOutcome<T>

    data class Retired(
        val descriptor: RaftNetworkRequestLifetimeDescriptor,
        val replacementGeneration: Long,
        val processForeground: Boolean
    ) : RaftNetworkRequestLifetimeOutcome<Nothing> {
        val mutationOutcomeUnknown: Boolean
            get() = descriptor.methodClass != RaftNetworkMethodClass.Read
    }
}

internal data class RaftNetworkLifecycleTransition(
    val changed: Boolean,
    val generation: Long,
    val retiredRequestCount: Int
)

/**
 * Process-global owner of ordinary API request lifetime.
 *
 * The Ktor transport owns execution and its physical pools. This owner retains only the
 * callback API's cancel handle plus an exactly-once terminal fence. A real process visibility
 * transition retires the previous generation's visibility-scoped work, completes those waiters
 * without depending on a frozen transport callback, and suppresses their late callbacks.
 * Process mutations remain owned by the live process and finish through their original transport
 * callback or configured transport timeout; they are never replayed at a visibility edge.
 *
 * This coordinator deliberately does not schedule a third timeout. NetworkKMM and the existing
 * request scheduler remain the authoritative timeout owners; this layer adds only process-edge
 * retirement and records their configured monotonic deadline for diagnostics/tests.
 */
internal class RaftNetworkRequestLifetimeCoordinator(
    private val nowMillis: () -> Long = {
        RaftNetworkRequestLifetimeOrigin.elapsedNow().inWholeMilliseconds
    }
) {
    private val lock = SynchronizedObject()
    private val nextRequestId = atomic(0L)
    private var generation = 1L
    private var processForeground: Boolean? = null
    private val active = mutableMapOf<Long, ActiveAttemptBase>()

    suspend fun <T> execute(
        methodClass: RaftNetworkMethodClass,
        timeoutMillis: Long,
        start: ((T) -> Unit) -> RaftNetworkRequestCancelHandle
    ): RaftNetworkRequestLifetimeOutcome<T> {
        val attempt =
            synchronized(lock) {
                val startedAtMillis = nowMillis()
                val boundedTimeout = timeoutMillis.coerceAtLeast(1L)
                val configuredDeadlineAtMillis =
                    if (Long.MAX_VALUE - startedAtMillis < boundedTimeout) {
                        Long.MAX_VALUE
                    } else {
                        startedAtMillis + boundedTimeout
                    }
                ActiveAttempt<T>(
                    descriptor =
                        RaftNetworkRequestLifetimeDescriptor(
                            internalRequestId = nextRequestId.incrementAndGet(),
                            generation = generation,
                            methodClass = methodClass,
                            startedAtMillis = startedAtMillis,
                            configuredDeadlineAtMillis = configuredDeadlineAtMillis
                        )
                ).also { candidate -> active[candidate.descriptor.internalRequestId] = candidate }
            }

        try {
            val handle = start { value -> complete(attempt, value) }
            attachCancelHandle(attempt, handle)
        } catch (error: Throwable) {
            if (removeForStartFailure(attempt)) throw error
            // A synchronous callback or lifecycle retirement may have won before a buggy starter
            // threw. Preserve that already-published terminal instead of reopening the attempt.
        }

        return try {
            attempt.completion.await()
        } catch (cancellation: CancellationException) {
            cancelFromCaller(attempt)
            throw cancellation
        }
    }

    fun processForegroundOrNull(): Boolean? = synchronized(lock) { processForeground }

    /**
     * The first observed visibility establishes process state without retiring startup work.
     * Every subsequent foreground/background edge advances the generation exactly once.
     */
    fun onProcessVisibilityChanged(foreground: Boolean): RaftNetworkLifecycleTransition {
        val retirement = synchronized(lock) {
            val previous = processForeground
            if (previous == null) {
                processForeground = foreground
                return RaftNetworkLifecycleTransition(
                    changed = false,
                    generation = generation,
                    retiredRequestCount = 0
                )
            }
            if (previous == foreground) {
                return RaftNetworkLifecycleTransition(
                    changed = false,
                    generation = generation,
                    retiredRequestCount = 0
                )
            }
            processForeground = foreground
            generation += 1L
            val retired =
                active.values.filter { attempt ->
                    attempt.descriptor.methodClass != RaftNetworkMethodClass.ProcessMutation
                }
            retired.forEach { attempt ->
                active.remove(attempt.descriptor.internalRequestId)
            }
            val cancelHandles =
                retired.mapNotNull { attempt ->
                    attempt.terminal = true
                    attempt.takeCancelHandleLocked()
                }
            RetirementBatch(retired, cancelHandles, generation)
        }
        retirement.attempts.forEach { attempt ->
            attempt.retire(retirement.replacementGeneration, foreground)
        }
        retirement.cancelHandles.forEach(RaftNetworkRequestCancelHandle::cancelSafely)
        return RaftNetworkLifecycleTransition(
            changed = true,
            generation = retirement.replacementGeneration,
            retiredRequestCount = retirement.attempts.size
        )
    }

    internal fun activeRequestCountForTest(): Int = synchronized(lock) { active.size }

    private fun <T> complete(attempt: ActiveAttempt<T>, value: T) {
        val won =
            synchronized(lock) {
                if (active[attempt.descriptor.internalRequestId] !== attempt || attempt.terminal) {
                    false
                } else {
                    active.remove(attempt.descriptor.internalRequestId)
                    attempt.terminal = true
                    true
                }
            }
        if (won) {
            attempt.completion.complete(
                RaftNetworkRequestLifetimeOutcome.Completed(value, attempt.descriptor)
            )
        }
    }

    private fun attachCancelHandle(
        attempt: ActiveAttemptBase,
        handle: RaftNetworkRequestCancelHandle
    ) {
        val cancelHandle =
            synchronized(lock) {
                attempt.cancelHandle = handle
                if (attempt.cancelWhenAttached && !attempt.cancelIssued) {
                    attempt.cancelIssued = true
                    handle
                } else {
                    null
                }
            }
        cancelHandle?.cancelSafely()
    }

    private fun removeForStartFailure(attempt: ActiveAttemptBase): Boolean =
        synchronized(lock) {
            if (active[attempt.descriptor.internalRequestId] !== attempt || attempt.terminal) {
                false
            } else {
                active.remove(attempt.descriptor.internalRequestId)
                attempt.terminal = true
                true
            }
        }

    private fun cancelFromCaller(attempt: ActiveAttemptBase) {
        val cancelHandle =
            synchronized(lock) {
                if (active[attempt.descriptor.internalRequestId] === attempt && !attempt.terminal) {
                    active.remove(attempt.descriptor.internalRequestId)
                    attempt.terminal = true
                    attempt.takeCancelHandleLocked()
                } else {
                    null
                }
            }
        cancelHandle?.cancelSafely()
    }

    private abstract class ActiveAttemptBase(
        val descriptor: RaftNetworkRequestLifetimeDescriptor
    ) {
        var terminal: Boolean = false
        var cancelWhenAttached: Boolean = false
        var cancelIssued: Boolean = false
        var cancelHandle: RaftNetworkRequestCancelHandle? = null

        fun takeCancelHandleLocked(): RaftNetworkRequestCancelHandle? {
            cancelWhenAttached = true
            val handle = cancelHandle
            if (handle == null || cancelIssued) return null
            cancelIssued = true
            return handle
        }

        abstract fun retire(replacementGeneration: Long, processForeground: Boolean)
    }

    private class ActiveAttempt<T>(
        descriptor: RaftNetworkRequestLifetimeDescriptor
    ) : ActiveAttemptBase(descriptor) {
        val completion = CompletableDeferred<RaftNetworkRequestLifetimeOutcome<T>>()

        override fun retire(replacementGeneration: Long, processForeground: Boolean) {
            completion.complete(
                RaftNetworkRequestLifetimeOutcome.Retired(
                    descriptor = descriptor,
                    replacementGeneration = replacementGeneration,
                    processForeground = processForeground
                )
            )
        }
    }

    private data class RetirementBatch(
        val attempts: List<ActiveAttemptBase>,
        val cancelHandles: List<RaftNetworkRequestCancelHandle>,
        val replacementGeneration: Long
    )
}

private fun RaftNetworkRequestCancelHandle.cancelSafely() {
    try {
        cancel()
    } catch (_: Throwable) {
        // One transport cancellation failure must not prevent other retired waiters completing.
    }
}

internal object RaftProcessNetworkRequestLifetime {
    val coordinator = RaftNetworkRequestLifetimeCoordinator()
}

/** Platform hosts call this only from their process/application-level visibility owner. */
fun updateRaftProcessNetworkVisibility(foreground: Boolean): Int {
    // Task #164: stale unread / Activity reads recover only in the foreground, and at once on return.
    build.raft.app.data.RaftStaleReadRecoverySignals.onProcessVisibility(foreground)
    return RaftProcessNetworkRequestLifetime.coordinator
        .onProcessVisibilityChanged(foreground)
        .retiredRequestCount
}

/** True only after a platform host has reported the process as visible. */
fun raftProcessIsForeground(): Boolean =
    RaftProcessNetworkRequestLifetime.coordinator.processForegroundOrNull() == true
