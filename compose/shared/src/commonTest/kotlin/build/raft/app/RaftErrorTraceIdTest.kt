package build.raft.app

import build.raft.app.data.AgentTurnStopFeedback
import build.raft.app.data.AgentTurnStopIntent
import build.raft.app.data.AgentTurnStopStore
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLoginCoordinator
import build.raft.app.data.AppLoginExchangeResult
import build.raft.app.data.AppLoginRuntime
import build.raft.app.data.AuthApiResult
import build.raft.app.data.AuthAppLoginStartResponse
import build.raft.app.data.AuthNetworkResponse
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAuthErrorCode
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedEmailVerificationResult
import build.raft.app.data.SharedTasksEvent
import build.raft.app.data.TaskConversationDetailsIntent
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationHeaderEvent
import build.raft.app.data.TaskDetailLoad
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.errorPresentation
import build.raft.app.data.failureTraceIdOrNull
import build.raft.app.data.parseAppLinkExecuteAnswer
import build.raft.app.data.parseAppLinkPrepareAnswer
import build.raft.app.data.parseSharedRailTabOrderAccount
import build.raft.app.data.reduce
import build.raft.app.data.toSharedAuthFailure
import build.raft.app.data.toThreadAttachmentCommentsFailureProjection
import build.raft.app.model.MemberId
import build.raft.app.model.TaskId
import build.raft.app.network.RaftFailedRequestTraceIds
import build.raft.app.network.RaftRecentFailedTraceId
import build.raft.app.network.raftValidTraceIdOrNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    @Test
    fun dataResultAndThreadApiFailuresPropagateTraceIdToSurfacePayloads() {
        assertEquals(traceId, DataResult.Error(ScreenError(code = "http_500", traceId = traceId)).failureTraceIdOrNull())
        assertEquals(traceId, DataResult.NoPermission(PermissionBlocker(code = "forbidden", traceId = traceId)).failureTraceIdOrNull())
        assertNull(DataResult.Content("ok").failureTraceIdOrNull())
        assertNull(DataResult.Empty(EmptyReason.NoMessages).failureTraceIdOrNull())

        try {
            RaftFailedRequestTraceIds.recordResponse(503, headers(traceId))
            val httpFailure = ThreadApiResult.Failure(code = 503, errorBodyText = """{"code":"unavailable","error":"busy"}""")
            assertEquals(traceId, httpFailure.traceId)
            assertEquals(traceId, httpFailure.toThreadAttachmentCommentsFailureProjection().traceId)
            assertEquals(traceId, httpFailure.toThreadDataError("fallback").failureTraceIdOrNull())
            assertNull(ThreadApiResult.Failure(code = 0).traceId, "local/unanswered failure must not borrow a recent server trace id")
            assertNull(
                ThreadApiResult.Failure(code = 0).toThreadDataError("fallback").failureTraceIdOrNull(),
                "toThreadDataError must not re-borrow a stale trace id for unanswered failures"
            )
            assertNull(
                ThreadApiResult.Failure(code = 200, contractCode = "contract_mismatch").traceId,
                "2xx contract mismatch without response header trace id must not borrow an unrelated failed request"
            )
            assertNull(
                ThreadApiResult.Failure(code = 401).traceId,
                "401 auth failure must not borrow an unrelated failed request"
            )
        } finally {
            RaftFailedRequestTraceIds.clear()
        }

        val base = RaftThreadScreenPayload(instanceId = "t-1", status = "loading")
        val failed = base.asRefreshFailed("http_500", traceId)
        assertEquals(traceId, failed.errorTraceId)
        assertEquals(traceId, failed.toThreadTimelineRenderConfig().errorTraceId)

        val noResponseFailure = failed.asRefreshFailed("network_error", traceId = null)
        assertNull(noResponseFailure.errorTraceId, "a subsequent no-response failure must clear any earlier trace id")
        assertNull(noResponseFailure.toThreadTimelineRenderConfig().errorTraceId)

        val blocked = base.asRefreshNoPermission(base, code = "forbidden", serverMessage = "denied", traceId = traceId)
        assertEquals(traceId, blocked.errorTraceId)
        assertEquals(traceId, blocked.toThreadTimelineRenderConfig().errorTraceId)

        val noTraceBlocked = blocked.asRefreshNoPermission(base, code = "forbidden", serverMessage = "denied", traceId = null)
        assertNull(noTraceBlocked.errorTraceId, "a subsequent permission failure without a trace id must clear the old one")

        val recovered = failed.withThreadContentProjectionFrom(RaftThreadScreenPayload(instanceId = "t-1", status = "content"))
        assertNull(recovered.errorTraceId, "successful content projection must clear any earlier trace id")

        val cleared = blocked.asRefreshEmpty(base)
        assertNull(cleared.errorTraceId)

        assertEquals(traceId, SharedTasksEvent.OperationFailed(code = "tasks_status_failed", traceId = traceId).traceId)
        assertEquals(traceId, RaftHomeInlineError(title = "t", detail = "d", code = "http_500", traceId = traceId).traceId)

        try {
            RaftFailedRequestTraceIds.recordResponse(503, headers(traceId))
            assertNull(
                ScreenError(code = "message_id_missing", retryable = false, traceId = null).traceId,
                "explicit null traceId on local/unanswered errors must never borrow a recent 503 trace id"
            )
        } finally {
            RaftFailedRequestTraceIds.clear()
        }
    }

    @Test
    fun authAndOAuthFailuresCarryOnlyTheirOwnResponseTraceId() = runTest {
        val responseTrace = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val authResponse = AuthNetworkResponse(code = 500, bodyText = null, responseHeaders = mapOf("x-trace-id" to listOf(responseTrace)))
        assertEquals(responseTrace, authResponse.traceId)
        assertNull(AuthNetworkResponse(code = 0, bodyText = null, errorBodyText = "timeout").traceId)

        val sharedFailure = AuthApiResult.Failure(code = 500, errorBodyText = """{"error":"boom"}""", traceId = responseTrace).toSharedAuthFailure()
        assertEquals(responseTrace, sharedFailure.traceId)

        val unanswered = AuthApiResult.Failure(code = 0, errorBodyText = """{"error":"network_timeout"}""", traceId = responseTrace).toSharedAuthFailure()
        assertNull(unanswered.traceId, "unanswered code 0 auth failure must clear traceId")

        val emailFailure = SharedEmailVerificationResult.Failure(SharedAuthErrorCode.Server, "boom", traceId = responseTrace)
        assertEquals(responseTrace, emailFailure.traceId)

        val formatted = raftFormatHostErrorWithTraceId("Sign in failed.", responseTrace) { "Trace ID: $it" }
        assertEquals("Sign in failed.\nTrace ID: aaaaaaaa…", formatted)
        assertEquals("Sign in failed.", raftFormatHostErrorWithTraceId("Sign in failed.", null) { "Trace ID: $it" })
        assertEquals("Sign in failed.", raftFormatHostErrorWithTraceId("Sign in failed.", "invalid") { "Trace ID: $it" })
        assertEquals("Trace ID: aaaaaaaa…", raftFormatHostErrorWithTraceId("", responseTrace) { "Trace ID: $it" })
        assertEquals(responseTrace, RaftHostStrings.validTraceIdOrNull(" ${responseTrace.uppercase()} "))

        val verifier = "a".repeat(43)
        val challenge = "b".repeat(43)
        var startResult: SharedAuthResult<AuthAppLoginStartResponse> =
            SharedAuthResult.Failure(SharedAuthErrorCode.Server, "unavailable", traceId = responseTrace)
        var exchangeResult: AppLoginExchangeResult = AppLoginExchangeResult.Failed(responseTrace)
        val runtime = object : AppLoginRuntime {
            override val environment: String = "prod"
            override suspend fun start(returnUri: String, challenge: String) = startResult
            override suspend fun complete(code: String, verifier: String): Boolean = exchangeResult == AppLoginExchangeResult.Succeeded
            override suspend fun completeWithResult(code: String, verifier: String): AppLoginExchangeResult = exchangeResult
        }
        val coordinator = AppLoginCoordinator(InMemoryRaftKeyValueStore(), { runtime }, { 1_000L })

        val failedStart = coordinator.startWithOutcome("gen-1", verifier, challenge, "raft://login/callback")
        assertNull(failedStart.url)
        assertEquals(responseTrace, failedStart.errorTraceId)

        startResult = SharedAuthResult.Success(AuthAppLoginStartResponse("req-1", "https://app.raft.build/login", "2099-01-01T00:00:00Z"))
        val okStart = coordinator.startWithOutcome("gen-1", verifier, challenge, "raft://login/callback")
        assertEquals("https://app.raft.build/login", okStart.url)
        assertNull(okStart.errorTraceId)

        val failedComplete = coordinator.completeWithOutcome("raft://login/callback", "req-1", "code-1", "")
        assertEquals("login_error", failedComplete.status)
        assertEquals(responseTrace, failedComplete.errorTraceId)

        // Local callback error has no server exchange response -> null traceId.
        coordinator.startWithOutcome("gen-2", verifier, challenge, "raft://login/callback")
        val localCallbackError = coordinator.completeWithOutcome("raft://login/callback", "req-1", "", "access_denied")
        assertEquals("login_error", localCallbackError.status)
        assertNull(localCallbackError.errorTraceId)
    }

    @Test
    fun appLinkActionCardRailOrderTaskAndAgentTurnStopPropagateOwnTraceIdOnly() = runTest {
        val responseTrace = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val prepFail = assertIs<AppLinkActionResult.Failed>(parseAppLinkPrepareAnswer(502, """{"error":"bad_gateway"}""", responseTrace))
        assertEquals(responseTrace, prepFail.traceId)
        val execUnanswered = assertIs<AppLinkActionResult.Failed>(parseAppLinkExecuteAnswer(0, null, responseTrace))
        assertNull(execUnanswered.traceId, "status 0 app-link failure must not keep a trace id")

        val specFailed = ScreenError(code = "http_500", serverMessage = "failed", traceId = responseTrace).toThreadActionCardSpecError()
        assertEquals(responseTrace, specFailed.traceIdOrNull())
        val specRateLimited =
            ScreenError(
                code = "http_429",
                serverCode = ACTION_CARD_FEEDBACK_RATE_LIMITED_CODE,
                retryAfterSeconds = 60,
                traceId = responseTrace,
            ).toThreadActionCardSpecError()
        assertEquals(responseTrace, specRateLimited.traceIdOrNull())
        assertNull(ThreadActionCardSpecError.FormRequired.traceIdOrNull())
        assertNull(ThreadActionCardSpecError.QuestionNotSent.traceIdOrNull())

        try {
            RaftFailedRequestTraceIds.recordResponse(503, headers(traceId))
            val railHttpFail = parseSharedRailTabOrderAccount(500, """{"error":"boom"}""", responseTrace)
            assertEquals(responseTrace, railHttpFail.failureTraceIdOrNull())
            val railUnanswered = parseSharedRailTabOrderAccount(0, null, responseTrace)
            assertNull(railUnanswered.failureTraceIdOrNull(), "status 0 rail tab order failure must not keep trace id")
            val railNoHeader = parseSharedRailTabOrderAccount(500, """{"error":"boom"}""", null)
            assertNull(railNoHeader.failureTraceIdOrNull(), "missing header must not borrow recent 503 trace id")

            var throwOnStop = false
            val turnStopStore = AgentTurnStopStore(
                interruptTurn = {
                    if (throwOnStop) error("socket closed")
                    DataResult.Error(ScreenError(code = "http_503", traceId = responseTrace))
                },
                scope = this,
            )
            turnStopStore.dispatch(AgentTurnStopIntent.Stop(MemberId("agent-1")))
            advanceUntilIdle()
            assertEquals(AgentTurnStopFeedback.Failed, turnStopStore.state.value.feedback)
            assertEquals(responseTrace, turnStopStore.state.value.feedbackTraceId)

            turnStopStore.dispatch(AgentTurnStopIntent.ConsumeFeedback)
            assertNull(turnStopStore.state.value.feedbackTraceId)

            throwOnStop = true
            turnStopStore.dispatch(AgentTurnStopIntent.Stop(MemberId("agent-1")))
            advanceUntilIdle()
            assertEquals(AgentTurnStopFeedback.Failed, turnStopStore.state.value.feedback)
            assertNull(turnStopStore.state.value.feedbackTraceId, "thrown exception must not borrow recent 503 trace id")
        } finally {
            RaftFailedRequestTraceIds.clear()
        }

        val taskId = TaskId("task-1")
        val failedDetails =
            TaskConversationDetailsState(taskId = taskId)
                .reduce(TaskConversationDetailsIntent.HistoryLoaded(taskId, DataResult.Error(ScreenError("http_500", traceId = responseTrace))))
        assertEquals(responseTrace, assertIs<TaskDetailLoad.Failed>(failedDetails.history).traceId)
        val recoveredDetails =
            failedDetails.reduce(TaskConversationDetailsIntent.HistoryLoaded(taskId, DataResult.Content(emptyList())))
        assertIs<TaskDetailLoad.Loaded<*>>(recoveredDetails.history)

        assertEquals(responseTrace, TaskConversationHeaderEvent.FollowFailed(true, "http_500", responseTrace).traceId)
        assertEquals(responseTrace, TaskConversationStart.Failed("boom", responseTrace).traceId)
    }
}
