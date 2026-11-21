package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Covers the full 0–20 s reconnect jitter window plus small foreground/reconnect skew so a
 * foreground reconcile and its companion reconnect refetch (or 10-minute status reconcile) do not
 * pull the same `/agents` (814 KB) and `/machines` (108 KB) payloads back-to-back within ~20 s
 * (Hands 45eccbd9, #wg-mobile-feedback:#257).
 */
internal val RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW: Duration = 25.seconds

/**
 * Process-scoped per-server coalescer for live-status refreshes (`/machines` sync refresh):
 * - Joins an already in-flight refresh for the same server instead of superseding/duplicating it.
 * - Optionally skips a fresh refresh when a recent one for the same server succeeded within
 *   [coalesceFreshWithin] and no invalidation occurred since.
 * - Never caches failures (neither thrown exceptions nor `false` outcomes), and drops freshness on
 *   [invalidate] / [invalidateAll] (server loss, sign-out, account or environment switch).
 */
internal class RaftRecentServerRefreshCoalescer(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 16,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val logger: (String) -> Unit = {},
) {
    init {
        require(maxEntries > 0)
    }

    internal enum class Outcome {
        Executed,
        JoinedInFlight,
        FreshCoalesced,
    }

    private data class Generation(
        val global: Long,
        val server: Long,
    )

    private class Flight(
        val token: Any,
        val deferred: Deferred<Result<Boolean>>,
    )

    private data class CompletedEntry(
        val generation: Generation,
        val completedAt: TimeMark,
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
     */
    suspend fun runOrCoalesce(
        serverId: String,
        reason: String,
        coalesceFreshWithin: Duration = Duration.ZERO,
        block: suspend () -> Boolean,
    ): Outcome {
        val key = serverId.trim()
        if (key.isEmpty()) return Outcome.FreshCoalesced
        val decision =
            mutex.withLock {
                activeFlights[key]?.let { existing ->
                    logger("sync_machines_coalesce serverId=$key result=join reason=$reason")
                    return@withLock Decision.Join(existing)
                }
                if (coalesceFreshWithin > Duration.ZERO) {
                    val last = completed[key]
                    val currentGen = generationLocked(key)
                    if (last != null && last.generation == currentGen) {
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
                                        completed[key] = CompletedEntry(generation = gen, completedAt = timeSource.markNow())
                                        while (completed.size > maxEntries) {
                                            completed.remove(completed.keys.first())
                                        }
                                    }
                                }
                            }
                        }
                    }
                val flight = Flight(token = token, deferred = deferred)
                activeFlights[key] = flight
                Decision.Start(flight)
            }
        return when (decision) {
            Decision.Fresh -> Outcome.FreshCoalesced
            is Decision.Join -> {
                decision.flight.deferred.await().getOrThrow()
                Outcome.JoinedInFlight
            }
            is Decision.Start -> {
                decision.flight.deferred.start()
                decision.flight.deferred.await().getOrThrow()
                Outcome.Executed
            }
        }
    }

    suspend fun invalidate(serverId: String) {
        val key = serverId.trim()
        if (key.isEmpty()) return
        mutex.withLock {
            val hadActiveFlight = activeFlights.remove(key) != null
            completed.remove(key)
            if (hadActiveFlight) {
                serverGenerations[key] = (serverGenerations[key] ?: 0L) + 1L
            } else {
                serverGenerations.remove(key)
            }
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
