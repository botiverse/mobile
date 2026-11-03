package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.errorPresentation
import build.raft.app.network.RaftFailedRequestTraceIds
import build.raft.app.network.RaftRecentFailedTraceId
import build.raft.app.network.raftValidTraceIdOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** AGENTS.md "Errors carry the trace ID": header capture, the error's trace id, and how it is shown. */
class RaftErrorTraceIdTest {
    private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    private fun headers(value: String) = mapOf("X-Trace-Id" to listOf(value))

    @Test
    fun onlyAValidNonZeroTraceIdIsKept() {
        assertEquals(traceId, raftValidTraceIdOrNull(" ${traceId.uppercase()} "))
        for (invalid in listOf(null, "", "0".repeat(32), "abc", traceId + "0", "g".repeat(32))) {
            assertNull(raftValidTraceIdOrNull(invalid), "accepted $invalid")
        }
    }

    @Test
    fun aResponseCarriesItsTraceIdHeaderInAnyCase() {
        assertEquals(traceId, HomeNetworkResponse(500, null, responseHeaders = mapOf("x-trace-id" to listOf(traceId))).traceId)
        assertNull(HomeNetworkResponse(0, null, errorBodyText = "network_error").traceId)
    }

    @Test
    fun recorderKeepsAnsweredFailuresForTheRecentWindowOnly() {
        val time = TestTimeSource()
        val recorder = RaftRecentFailedTraceId(timeSource = time)
        for (ignored in listOf(200, 204, 401, 0)) recorder.recordResponse(ignored, headers(traceId))
        assertNull(recorder.recent(), "a success, a 401 or an unanswered request records nothing")

        recorder.recordResponse(404, headers(traceId))
        time += recorder.windowMillis.milliseconds
        assertEquals(traceId, recorder.recent())
        time += 1.milliseconds
        assertNull(recorder.recent(), "a failure older than the window is not the error's")
    }

    @Test
    fun anErrorMadeRightAfterAFailedResponseCarriesItsTraceId() {
        try {
            RaftFailedRequestTraceIds.recordResponse(500, headers(traceId))
            val error = ScreenError(code = "thread_markdown_ref_unavailable")
            val blocker = PermissionBlocker(code = "forbidden")
            assertEquals(traceId, error.traceId)
            assertEquals(traceId, blocker.traceId)
            // The error keeps its id after it is copied into state, whatever fails later.
            RaftFailedRequestTraceIds.clear()
            assertEquals(traceId, error.copy(retryable = false).traceId)
            assertNull(ScreenError(code = "local_failure").traceId)
        } finally {
            RaftFailedRequestTraceIds.clear()
        }
    }

    /** Guard: every presentation of an error with a trace id keeps it, whichever copy wins. */
    @Test
    fun everyErrorPresentationKeepsTheTraceId() {
        val codes = listOf("http_500", "not_found", "network_error", "auth_required", "some server words", "")
        for (code in codes) {
            assertEquals(traceId, ScreenError(code = code, traceId = traceId).errorPresentation().traceId, code)
            assertEquals(traceId, PermissionBlocker(code = code, traceId = traceId).errorPresentation().traceId, code)
        }
        assertEquals(traceId, ScreenError(code = "x", serverMessage = "edge_blocked", traceId = traceId).errorPresentation().traceId)
        assertEquals(traceId, PermissionBlocker(code = "x", serverMessage = "edge_blocked", traceId = traceId).errorPresentation().traceId)
        assertNull(ScreenError(code = "http_500", traceId = null).errorPresentation().traceId)
    }

    @Test
    fun shownTraceIdIsTheFirstEightDigits() {
        assertEquals("4bf92f35…", raftShortTraceId(traceId))
    }
}
