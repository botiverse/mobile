package build.raft.app.data

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Single flight for identical GETs (feedback fb-52cc0499: Home, Activity and the thread page asked for
 * the same `channels/<id>`, `channels`, `channels/unread`, `servers/unread-summary` within a few
 * hundred ms of each other, each as its own request).
 *
 * A GET whose [Key] matches one that started at most [joinWindowMillis] ago and has not answered yet
 * waits for that request's answer instead of sending its own. The priority is not part of the key:
 * a Foreground ask joins a Visible request already on the wire (the shared scheduler's priority gate
 * is off, so a request never queues and there is nothing left to raise once it is sent).
 *
 * The window is short on purpose: an ask is usually made because something changed, and a request sent
 * well before that change may answer without it. Only requests started together are shared.
 *
 * Cancellation: the request runs in the coroutine of the caller that sent it. If that caller is
 * cancelled, the callers waiting on it do not inherit the cancellation; the first of them sends the
 * request again. A waiter's own cancellation only cancels its wait.
 */
internal class RaftInFlightGetDedupe(
    private val joinWindowMillis: Long = RaftInFlightGetJoinWindowMillis,
    private val nowMillis: () -> Long = monotonicMillisClock(),
) {
    data class Key(
        val baseUrl: String,
        val path: String,
        val query: List<Pair<String, String>>,
        val serverIdOverride: String?,
        val headers: List<Pair<String, String>>,
        val authority: Any?,
        /** Whose request it is when the server comes from the session (no override). */
        val sessionUserId: String?,
        val sessionServerId: String?,
    )

    private class Flight(val startedAtMillis: Long) {
        val answer = CompletableDeferred<HomeNetworkResponse>()
    }

    /** The sender went away before an answer; a waiter sends the request itself. */
    private class SenderGone : CancellationException("in-flight GET sender cancelled")

    private val lock = SynchronizedObject()
    private val flights = mutableMapOf<Key, Flight>()

    /** GETs sent / GETs that took another request's answer, for tests and diagnostics. */
    var sent: Long = 0L
        private set
    var joined: Long = 0L
        private set

    suspend fun run(key: Key, send: suspend () -> HomeNetworkResponse): HomeNetworkResponse {
        while (true) {
            val now = nowMillis()
            var mine: Flight? = null
            val joinedFlight =
                synchronized(lock) {
                    val existing = flights[key]
                    if (existing != null && !existing.answer.isCompleted && now - existing.startedAtMillis <= joinWindowMillis) {
                        joined += 1
                        existing
                    } else {
                        Flight(now).also { flight ->
                            flights[key] = flight
                            mine = flight
                            sent += 1
                        }
                        null
                    }
                }
            if (joinedFlight != null) {
                try {
                    return joinedFlight.answer.await()
                } catch (cancellation: CancellationException) {
                    // This waiter's own cancellation rethrows here; otherwise the sender went away.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            val flight = mine ?: continue
            try {
                val response = send()
                flight.answer.complete(response)
                return response
            } catch (cancellation: CancellationException) {
                flight.answer.completeExceptionally(SenderGone())
                throw cancellation
            } catch (error: Throwable) {
                flight.answer.completeExceptionally(error)
                throw error
            } finally {
                synchronized(lock) {
                    if (flights[key] === flight) flights.remove(key)
                }
            }
        }
    }
}

/** See [RaftInFlightGetDedupe]: how long after it was sent a GET can still be joined. */
internal const val RaftInFlightGetJoinWindowMillis: Long = 500L

private fun monotonicMillisClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
