package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Covers the full 0–20 s reconnect jitter window plus small foreground/reconnect skew so a
 * foreground reconcile and its companion reconnect refetch (or 10-minute status reconcile) do not
 * pull the same `/agents` (814 KB) and `/machines` (108 KB) payloads back-to-back within ~20 s
 * (Hands 45eccbd9, #wg-mobile-feedback:#257).
 */
@RaftCoreInternalApi val RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW: Duration = 25.seconds

/**
 * Process-scoped per-server coalescer for live-status refreshes (`/machines` sync refresh):
 * - Joins an already in-flight refresh for the same server instead of superseding/duplicating it.
 * - Optionally skips a fresh refresh when a recent one for the same server succeeded within
 *   [coalesceFreshWithin] and no invalidation occurred since.
 * - Never caches failures (neither thrown exceptions nor `false` outcomes), and drops freshness on
 *   [invalidate] / [invalidateAll] (server loss, sign-out, account or environment switch).
 */
@RaftCoreInternalApi class RaftRecentServerRefreshCoalescer(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 16,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val logger: (String) -> Unit = {},
) {
    init {
        require(maxEntries > 0)
    }

    @RaftCoreInternalApi enum class Outcome {
        Executed,
        JoinedInFlight,
        FreshCoalesced,
    }

    @RaftCoreInternalApi data class RunResult(
        val outcome: Outcome,
        val succeeded: Boolean,
    )

    private data class Generation(
        val global: Long,
        val server: Long,
    )

    private class Flight(
        val token: Any,
        val startedAt: ComparableTimeMark,
        val deferred: Deferred<Result<Boolean>>,
    )

    private data class CompletedEntry(
        val generation: Generation,
        val startedAt: ComparableTimeMark,
        val completedAt: ComparableTimeMark,
    )

    private val mutex = Mutex()
    private val activeFlights = mutableMapOf<String, Flight>()
    private val completed = linkedMapOf<String, CompletedEntry>()
    private val serverGenerations = mutableMapOf<String, Long>()
    private var globalGeneration: Long = 0L

    /**
     * Runs [block] or coalesces it against an active/recent refresh for [serverId].
     * [block] returns `true` when the refresh succeeded and may satisfy coalescing callers within
     * [coalesceFreshWithin], or `false` when it failed/skipped and must not be cached as fresh.
     *
     * When [notStartedBefore] is given (e.g. a transport reconnect timestamp), only an in-flight or
     * recently completed refresh that STARTED at or after [notStartedBefore] may satisfy this call;
     * any earlier in-flight refresh is detached so a post-connect refill starts fresh instead of
     * inheriting pre-disconnect state.
     */
    suspend fun runOrCoalesce(
        serverId: String,
        reason: String,
        coalesceFreshWithin: Duration = Duration.ZERO,
        notStartedBefore: ComparableTimeMark? = null,
        block: suspend () -> Boolean,
    ): Outcome =
        runOrCoalesceWithResult(
            serverId = serverId,
            reason = reason,
            coalesceFreshWithin = coalesceFreshWithin,
            notStartedBefore = notStartedBefore,
            block = block,
        ).outcome

    /**
     * Same as [runOrCoalesce], returning both how this call was coalesced and whether the
     * underlying refresh succeeded (`true` for [Outcome.FreshCoalesced], or the joined/executed
     * flight's [block] outcome).
     */
    suspend fun runOrCoalesceWithResult(
        serverId: String,
        reason: String,
        coalesceFreshWithin: Duration = Duration.ZERO,
        notStartedBefore: ComparableTimeMark? = null,
        block: suspend () -> Boolean,
    ): RunResult {
        val key = serverId.trim()
        if (key.isEmpty()) return RunResult(Outcome.FreshCoalesced, succeeded = true)
        val decision =
            mutex.withLock {
                val active = activeFlights[key]
                if (active != null) {
                    if (notStartedBefore == null || active.startedAt >= notStartedBefore) {
                        logger("sync_machines_coalesce serverId=$key result=join reason=$reason")
                        return@withLock Decision.Join(active)
                    }
                    activeFlights.remove(key)
                    serverGenerations[key] = (serverGenerations[key] ?: 0L) + 1L
                    logger("sync_machines_coalesce serverId=$key result=replace_pre_boundary reason=$reason")
                }
                val last = completed[key]
                if (last != null && notStartedBefore != null && last.startedAt < notStartedBefore) {
                    // Retire pre-boundary completed entries even if this replacement flight fails,
                    // so a later retry without a newer boundary cannot reuse pre-background data.
                    completed.remove(key)
                } else if (coalesceFreshWithin > Duration.ZERO) {
                    val currentGen = generationLocked(key)
                    if (
                        last != null &&
                        last.generation == currentGen &&
                        (notStartedBefore == null || last.startedAt >= notStartedBefore)
                    ) {
                        val age = last.completedAt.elapsedNow()
                        if (age <= coalesceFreshWithin) {
                            logger(
                                "sync_machines_coalesce serverId=$key result=fresh_coalesced " +
                                    "ageMs=${age.inWholeMilliseconds} reason=$reason"
                            )
                            return@withLock Decision.Fresh
                        }
                    }
                }
                val token = Any()
                val gen = generationLocked(key)
                val startedAt = timeSource.markNow()
                val deferred =
                    scope.async(start = CoroutineStart.LAZY) {
                        var succeeded = false
                        try {
                            val ok = block()
                            succeeded = ok
                            Result.success(ok)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            Result.failure(error)
                        } finally {
                            withContext(NonCancellable) {
                                mutex.withLock {
                                    if (activeFlights[key]?.token === token) {
                                        activeFlights.remove(key)
                                    }
                                    if (succeeded && generationLocked(key) == gen) {
                                        completed.remove(key)
                                        completed[key] =
                                            CompletedEntry(
                                                generation = gen,
                                                startedAt = startedAt,
                                                completedAt = timeSource.markNow(),
                                            )
                                        while (completed.size > maxEntries) {
                                            completed.remove(completed.keys.first())
                                        }
                                    }
                                }
                            }
                        }
                    }
                val flight = Flight(token = token, startedAt = startedAt, deferred = deferred)
                activeFlights[key] = flight
                Decision.Start(flight)
            }
        return when (decision) {
            Decision.Fresh -> RunResult(Outcome.FreshCoalesced, succeeded = true)
            is Decision.Join -> {
                val ok = decision.flight.deferred.await().getOrThrow()
                RunResult(Outcome.JoinedInFlight, succeeded = ok)
            }
            is Decision.Start -> {
                decision.flight.deferred.start()
                val ok = decision.flight.deferred.await().getOrThrow()
                RunResult(Outcome.Executed, succeeded = ok)
            }
        }
    }

    suspend fun invalidate(serverId: String) {
        val key = serverId.trim()
        if (key.isEmpty()) return
        mutex.withLock {
            activeFlights.remove(key)
            completed.remove(key)
            // Keep per-server generations strictly monotonic so an orphaned in-flight read can
            // never match a generation that wrapped back to 0 across a second invalidate.
            serverGenerations[key] = (serverGenerations[key] ?: 0L) + 1L
        }
    }

    suspend fun invalidateAll() {
        mutex.withLock {
            globalGeneration += 1L
            activeFlights.clear()
            completed.clear()
            serverGenerations.clear()
        }
    }

    private fun generationLocked(serverId: String): Generation =
        Generation(global = globalGeneration, server = serverGenerations[serverId] ?: 0L)

    private sealed interface Decision {
        data object Fresh : Decision
        data class Join(val flight: Flight) : Decision
        data class Start(val flight: Flight) : Decision
    }
}
