package build.raft.app.network

import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.atomicfu.atomic

/**
 * The server's trace id for a request, so an error the app shows can carry an id a person can
 * send us and we find the request end to end (web `utils/requestTraceId.ts`, #proj-o11y:#18).
 * The server returns the trace it recorded as `X-Trace-Id` on every response
 * (`requestObservability.ts`, slock #12392).
 */
const val RAFT_TRACE_ID_RESPONSE_HEADER: String = "X-Trace-Id"

private val raftTraceIdPattern = Regex("^[0-9a-f]{32}$")
private val raftAllZeroPattern = Regex("^0+$")

/** A 32-hex, non-zero W3C trace id (lower-cased), else null; as the web's `validTraceId`. */
fun raftValidTraceIdOrNull(value: String?): String? {
    val id = value?.trim()?.lowercase() ?: return null
    return id.takeIf { raftTraceIdPattern.matches(it) && !raftAllZeroPattern.matches(it) }
}

/** The response's `X-Trace-Id` (any header-name case), validated; null when it carried none. */
fun Map<String, List<String>>.raftResponseTraceIdOrNull(): String? =
    entries
        .firstOrNull { (name, _) -> name.equals(RAFT_TRACE_ID_RESPONSE_HEADER, ignoreCase = true) }
        ?.value
        ?.firstNotNullOfOrNull(::raftValidTraceIdOrNull)

/**
 * The trace id of the most recent failed API response (web `recordFailedRequestTraceId` /
 * `recentFailedRequestTraceId`). Repositories turn a failed response into a [build.raft.app.data.ScreenError]
 * right after it arrives, in hundreds of places; rather than thread the id through each, an error
 * made within [windowMillis] of a failure carries that failure's id (`ScreenError.traceId`'s default).
 *
 * Recorded by the bottom HTTP layer ([KtorRaftHttpExecutor]) for every answered request that
 * failed, except a 401 (the auth middleware refreshes and retries it; the web skips it too).
 * A request that got no response (code 0) has no server trace and records nothing. Thread-safe.
 */
class RaftRecentFailedTraceId(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    val windowMillis: Long = RAFT_RECENT_FAILURE_WINDOW_MILLIS,
) {
    private class Failure(val traceId: String, val at: TimeMark)

    private val last = atomic<Failure?>(null)

    /** Records [code]'s failure when it was answered with a trace id; a success or a 401 is ignored. */
    fun recordResponse(code: Int, responseHeaders: Map<String, List<String>>) {
        if (code in 200..299 || code == 0 || code == 401) return
        val traceId = responseHeaders.raftResponseTraceIdOrNull() ?: return
        last.value = Failure(traceId, timeSource.markNow())
    }

    /** The last failure's trace id while it is recent, else null. */
    fun recent(): String? {
        val failure = last.value ?: return null
        return failure.traceId.takeIf { failure.at.elapsedNow().inWholeMilliseconds <= windowMillis }
    }

    /** Test seam: forget the last failure. */
    fun clear() {
        last.value = null
    }
}

/** Web `RECENT_FAILURE_MS`. */
const val RAFT_RECENT_FAILURE_WINDOW_MILLIS: Long = 5_000

/** The app's one recorder: the HTTP layer writes it, `ScreenError`/`PermissionBlocker` read it. */
val RaftFailedRequestTraceIds: RaftRecentFailedTraceId = RaftRecentFailedTraceId()
