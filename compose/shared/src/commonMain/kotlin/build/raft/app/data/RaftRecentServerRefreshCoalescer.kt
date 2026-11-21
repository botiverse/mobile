package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - Never caches failures, and drops freshness on [invalidate] / [invalidateAll] (server loss,
 *   sign-out, account or environment switch).
 */
internal class RaftRecentServerRefreshCoalescer(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val logger: (String) -> Unit = {},
) {
    internal enum class Outcome {
        Executed,
        JoinedInFlight,
        FreshCoalesced,
    }

    private class Flight(
        val token: Any,
        val generation: Long,
        val deferred: Deferred<Boolean>,
    )

    private data class CompletedEntry(
        val generation: Long,
        val completedAt: TimeMark,
    )

    private val mutex = Mutex()
    private val activeFlights = mutableMapOf<String, Flight>()
    private val completed = mutableMapOf<String, CompletedEntry>()
    private val serverGenerations = mutableMapOf<String, Long>()
    private var globalGeneration: Long = 0L

    suspend fun runOrCoalesce(
        serverId: String,
        reason: String,
        coalesceFreshWithin: Duration = Duration.ZERO,
        block: suspend () -> Unit,
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
                            block()
                            succeeded = true
                            true
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } finally {
                            mutex.withLock {
                                if (activeFlights[key]?.token === token) {
                                    activeFlights.remove(key)
                                }
                                if (succeeded && generationLocked(key) == gen) {
                                    completed[key] = CompletedEntry(generation = gen, completedAt = timeSource.markNow())
                                }
                            }
                        }
                    }
                val flight = Flight(token = token, generation = gen, deferred = deferred)
                activeFlights[key] = flight
                Decision.Start(flight)
            }
        return when (decision) {
            Decision.Fresh -> Outcome.FreshCoalesced
            is Decision.Join -> {
                decision.flight.deferred.await()
                Outcome.JoinedInFlight
            }
            is Decision.Start -> {
                decision.flight.deferred.start()
                decision.flight.deferred.await()
                Outcome.Executed
            }
        }
    }

    suspend fun invalidate(serverId: String) {
        val key = serverId.trim()
        if (key.isEmpty()) return
        mutex.withLock {
            activeFlights.remove(key)
            completed.remove(key)
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

    private fun generationLocked(serverId: String): Long =
        globalGeneration * 1_000_000L + (serverGenerations[serverId] ?: 0L)

    private sealed interface Decision {
        data object Fresh : Decision
        data class Join(val flight: Flight) : Decision
        data class Start(val flight: Flight) : Decision
    }
}
