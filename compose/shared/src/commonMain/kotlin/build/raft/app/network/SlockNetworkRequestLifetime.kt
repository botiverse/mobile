package build.raft.app.network

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.TimeSource

private val SlockNetworkRequestLifetimeOrigin = TimeSource.Monotonic.markNow()

/**
 * App-owned classification for lifecycle retirement.
 *
 * Only GET/HEAD are replay-safe, and this coordinator itself never replays either class. The
 * distinction is carried to callers so an interrupted mutation is reported as outcome-unknown.
 */
internal enum class SlockNetworkMethodClass {
    Read,
    Mutation
}

internal fun String.toSlockNetworkMethodClass(): SlockNetworkMethodClass =
    when (trim().uppercase()) {
        "GET", "HEAD" -> SlockNetworkMethodClass.Read
        else -> SlockNetworkMethodClass.Mutation
    }

internal fun interface SlockNetworkRequestCancelHandle {
    fun cancel()
}

internal fun interface SlockNetworkRequestStarter {
    fun start(
        request: com.tencent.kmm.network.export.NetworkRequest,
        onComplete: (com.tencent.kmm.network.export.NetworkResponse) -> Unit
    ): SlockNetworkRequestCancelHandle
}

internal data class SlockNetworkRequestLifetimeDescriptor(
    val internalRequestId: Long,
    val generation: Long,
    val methodClass: SlockNetworkMethodClass,
    val startedAtMillis: Long,
    /** Observational copy of the existing NetworkKMM/request-scheduler timeout policy. */
    val configuredDeadlineAtMillis: Long
)

internal sealed interface SlockNetworkRequestLifetimeOutcome<out T> {
    data class Completed<T>(
        val value: T,
        val descriptor: SlockNetworkRequestLifetimeDescriptor
    ) : SlockNetworkRequestLifetimeOutcome<T>

    data class Retired(
        val descriptor: SlockNetworkRequestLifetimeDescriptor,
        val replacementGeneration: Long,
        val processForeground: Boolean
    ) : SlockNetworkRequestLifetimeOutcome<Nothing> {
        val mutationOutcomeUnknown: Boolean
            get() = descriptor.methodClass == SlockNetworkMethodClass.Mutation
    }
}

internal data class SlockNetworkLifecycleTransition(
    val changed: Boolean,
    val generation: Long,
    val retiredRequestCount: Int
)

/**
 * Process-global owner of ordinary API request lifetime.
 *
 * NetworkKMM still owns transport execution and its physical pools. This owner retains only the
 * callback API's cancel handle plus an exactly-once terminal fence. A real process visibility
 * transition retires the previous generation, completes every waiter without depending on a
 * frozen transport callback, and suppresses any late callback from becoming the terminal result.
 *
 * This coordinator deliberately does not schedule a third timeout. NetworkKMM and the existing
 * request scheduler remain the authoritative timeout owners; this layer adds only process-edge
 * retirement and records their configured monotonic deadline for diagnostics/tests.
 */
internal class SlockNetworkRequestLifetimeCoordinator(
    private val nowMillis: () -> Long = {
        SlockNetworkRequestLifetimeOrigin.elapsedNow().inWholeMilliseconds
    }
) {
    private val lock = SynchronizedObject()
    private val nextRequestId = atomic(0L)
    private var generation = 1L
    private var processForeground: Boolean? = null
    private val active = mutableMapOf<Long, ActiveAttemptBase>()

    suspend fun <T> execute(
        methodClass: SlockNetworkMethodClass,
        timeoutMillis: Long,
        start: ((T) -> Unit) -> SlockNetworkRequestCancelHandle
    ): SlockNetworkRequestLifetimeOutcome<T> {
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
                        SlockNetworkRequestLifetimeDescriptor(
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

    /**
     * The first observed visibility establishes process state without retiring startup work.
     * Every subsequent foreground/background edge advances the generation exactly once.
     */
    fun onProcessVisibilityChanged(foreground: Boolean): SlockNetworkLifecycleTransition {
        val retirement = synchronized(lock) {
            val previous = processForeground
            if (previous == null) {
                processForeground = foreground
                return SlockNetworkLifecycleTransition(
                    changed = false,
                    generation = generation,
                    retiredRequestCount = 0
                )
            }
            if (previous == foreground) {
                return SlockNetworkLifecycleTransition(
                    changed = false,
                    generation = generation,
                    retiredRequestCount = 0
                )
            }
            processForeground = foreground
            generation += 1L
            val retired = active.values.toList()
            active.clear()
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
        retirement.cancelHandles.forEach(SlockNetworkRequestCancelHandle::cancelSafely)
        return SlockNetworkLifecycleTransition(
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
                SlockNetworkRequestLifetimeOutcome.Completed(value, attempt.descriptor)
            )
        }
    }

    private fun attachCancelHandle(
        attempt: ActiveAttemptBase,
        handle: SlockNetworkRequestCancelHandle
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
        val descriptor: SlockNetworkRequestLifetimeDescriptor
    ) {
        var terminal: Boolean = false
        var cancelWhenAttached: Boolean = false
        var cancelIssued: Boolean = false
        var cancelHandle: SlockNetworkRequestCancelHandle? = null

        fun takeCancelHandleLocked(): SlockNetworkRequestCancelHandle? {
            cancelWhenAttached = true
            val handle = cancelHandle
            if (handle == null || cancelIssued) return null
            cancelIssued = true
            return handle
        }

        abstract fun retire(replacementGeneration: Long, processForeground: Boolean)
    }

    private class ActiveAttempt<T>(
        descriptor: SlockNetworkRequestLifetimeDescriptor
    ) : ActiveAttemptBase(descriptor) {
        val completion = CompletableDeferred<SlockNetworkRequestLifetimeOutcome<T>>()

        override fun retire(replacementGeneration: Long, processForeground: Boolean) {
            completion.complete(
                SlockNetworkRequestLifetimeOutcome.Retired(
                    descriptor = descriptor,
                    replacementGeneration = replacementGeneration,
                    processForeground = processForeground
                )
            )
        }
    }

    private data class RetirementBatch(
        val attempts: List<ActiveAttemptBase>,
        val cancelHandles: List<SlockNetworkRequestCancelHandle>,
        val replacementGeneration: Long
    )
}

private fun SlockNetworkRequestCancelHandle.cancelSafely() {
    try {
        cancel()
    } catch (_: Throwable) {
        // One transport cancellation failure must not prevent other retired waiters completing.
    }
}

internal object SlockProcessNetworkRequestLifetime {
    val coordinator = SlockNetworkRequestLifetimeCoordinator()
}

/** Platform hosts call this only from their process/application-level visibility owner. */
fun updateSlockProcessNetworkVisibility(foreground: Boolean): Int =
    SlockProcessNetworkRequestLifetime.coordinator
        .onProcessVisibilityChanged(foreground)
        .retiredRequestCount
