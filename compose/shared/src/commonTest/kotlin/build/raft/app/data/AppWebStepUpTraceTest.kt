package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.RaftTraceIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

/**
 * `mobile.auth.web_step_up` (AppWebStepUpTrace.kt): the one span each web step-up attempt sends, for
 * a few representative attempts, and the span's trace on the attempt's two requests. Start and
 * complete go through [AppWebStepUpRepository] to a server answering as botiverse/slock does
 * (routes/appLogin.ts `/step-up/start` and `/step-up/complete`). The flows themselves:
 * AppWebStepUpCoordinatorTest.
 */
class AppWebStepUpTraceTest {
    private val legacyOnly =
        """{"error":"Confirm it's you with your second step before attaching a computer.","code":"second_factor_step_up_required","methods":["passkey"]}"""

    /** An authenticator app (and its backup codes) as well as older passkeys (#158). */
    private val withCode =
        """{"error":"Confirm it's you with your second step before attaching a computer.","code":"second_factor_step_up_required","methods":["totp","passkey","backup_code"]}"""
    private val returnUri = "raft://login/callback"
    private val requestId = "req-5f1e0c2a"
    private val stepUpUrl = "https://app.raft.build/login/app/verify?request=$requestId"
    private val callbackCode = "code-9b7d41"
    private val verifier = "v".repeat(43)

    /** Start's 201 and complete's 200. */
    private val started =
        """{"requestId":"$requestId","stepUpUrl":"$stepUpUrl","expiresAt":"2026-11-22T10:05:00Z","client":{"id":"raft-app","name":"Raft"}}"""
    private val verified = """{"verified":true,"verifiedAt":"2026-11-22T10:01:00Z"}"""

    private class Clock {
        var ms = 0L
    }

    private class Reply(val status: Int, val body: String, val tookMs: Long)

    /** Answers the step-up's calls in order, each taking [Reply.tookMs]; keeps each request's path and headers. */
    private class StepUpServer(private val clock: Clock, vararg replies: Reply) : RaftNetworkClient {
        private val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse = requestWithHeaders(priority, path, emptyMap(), method, query, serverIdOverride, bodyText)

        override suspend fun requestWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            requests += path to headers
            val reply = queue.removeFirst()
            clock.ms += reply.tookMs
            return if (reply.status in 200..299) {
                HomeNetworkResponse(reply.status, reply.body)
            } else {
                HomeNetworkResponse(reply.status, null, errorBodyText = reply.body)
            }
        }
    }

    /** The refused action: refused at first; once the step-up passed, it answers in [tookMs]. */
    private class Action(private val clock: Clock, private val refusal: DataResult<String>, private val tookMs: Long = 0L) {
        private var calls = 0

        suspend fun invoke(): DataResult<String> {
            calls += 1
            if (calls == 1) return refusal
            clock.ms += tookMs
            return DataResult.Content("attached")
        }
    }

    private class Harness(server: StepUpServer, clock: Clock) {
        val spans = mutableListOf<RaftWebSpanRecord>()
        val logs = mutableListOf<String>()
        val store = InMemoryRaftKeyValueStore()
        val pending = AppWebStepUpPendingStore({ store }, now = { EPOCH_MS + clock.ms })
        private val repository = AppWebStepUpRepository(server)
        val coordinator =
            SecondFactorStepUpCoordinator(
                stepUp = { error("no step-up answer: the web verifies this sign-in") },
                // iOS asks for the current RP ID; the person has only older passkeys.
                passkeyOptions = {
                    SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.PasskeyRpUnavailable("current", listOf("legacy")))
                },
                nativePasskeys = true,
                passkeyRpGroup = "current",
                webStepUp =
                    SecondFactorWebStepUp(
                        start = repository::start,
                        complete = repository::complete,
                        returnUri = "raft://login/callback",
                        pending = pending,
                        pkce = { AppWebStepUpPkce("v".repeat(43), "c".repeat(43)) },
                        generation = { "gen-1" },
                        log = logs::add,
                        tracer =
                            AppWebStepUpTracer(
                                nowMs = { clock.ms },
                                nowEpochMs = { EPOCH_MS + clock.ms },
                                newTraceIds = { IDS },
                                emit = spans::add,
                            ),
                    ),
                webStepUpAction = "computer_attach",
            )
    }

    private fun refusal(challenge: String): DataResult<String> =
        DataResult.Error(
            ScreenError(
                code = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
                retryable = false,
                secondFactorStepUp = parseSharedSecondFactorStepUpRequired(403, challenge),
            )
        )

    /** Continue in browser, or the link beside the code: the page the dialog is asked to open. */
    private suspend fun Harness.openPage(): SecondFactorWebStepUpSession {
        coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)
        return assertNotNull(coordinator.state.value?.webStepUpSession)
    }

    private fun RaftWebSpanRecord.attr(key: String): String? = attrs[key]?.jsonPrimitive?.content

    /** Codes, statuses and durations only: nothing of the request, its page, its code or its verifier. */
    private fun assertNothingOfTheRequest(spans: List<RaftWebSpanRecord>, logs: List<String>) {
        val secrets = listOf(requestId, stepUpUrl, callbackCode, verifier)
        for (span in spans) {
            for ((key, value) in span.attrs) {
                secrets.forEach { secret -> assertFalse(secret in value.toString(), "attribute $key carries $secret") }
            }
        }
        for (line in logs) {
            secrets.forEach { secret -> assertFalse(secret in line, "log line carries $secret: $line") }
        }
    }

    @Test
    fun aVerifiedAttemptIsOneSpanThatEndsWithTheActionsAnswer() = runTest {
        val clock = Clock()
        val server = StepUpServer(clock, Reply(201, started, tookMs = 120), Reply(200, verified, tookMs = 80))
        val harness = Harness(server, clock)
        val outcome = async { harness.coordinator.run(Action(clock, refusal(legacyOnly), tookMs = 40)::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        val session = harness.openPage()
        clock.ms += 9_000
        harness.coordinator.accept(
            SecondFactorStepUpIntent.WebStepUpBrowserFinished(
                session.id,
                RaftAppWebStepUpBrowserResult.Callback("$returnUri?requestId=$requestId&code=$callbackCode"),
            )
        )
        assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("attached")), outcome.await())

        val span = harness.spans.single()
        assertEquals("mobile.auth.web_step_up", span.name)
        assertEquals("ok", span.status)
        assertEquals("legacy_only", span.attr("trigger"))
        assertEquals("computer_attach", span.attr("action"))
        assertEquals("completed", span.attr("outcome"))
        assertEquals("120", span.attr("start_ms"))
        assertEquals("9000", span.attr("browser_ms"))
        assertEquals("80", span.attr("complete_ms"))
        assertEquals(1.0, span.attrs.getValue("sample_rate").jsonPrimitive.double, "never sampled")
        assertNull(span.attr("failed_step"))
        // From Continue in browser to the retried action's answer.
        assertEquals(raftEpochMillisUtcIso(EPOCH_MS), span.startTime)
        assertEquals(raftEpochMillisUtcIso(EPOCH_MS + 120 + 9_000 + 80 + 40), span.endTime)
        // Both calls went out under the span, so the server's request spans join its trace.
        assertEquals(IDS.traceId, span.traceId)
        assertEquals(IDS.spanId, span.spanId)
        assertEquals(listOf(APP_WEB_STEP_UP_START_PATH, APP_WEB_STEP_UP_COMPLETE_PATH), server.requests.map { it.first })
        assertEquals(listOf(IDS.traceparent, IDS.traceparent), server.requests.map { it.second["traceparent"] })
        assertNothingOfTheRequest(harness.spans, harness.logs)
    }

    @Test
    fun aRefusedStartFailsAtStartWithTheServersStatusAndCode() = runTest {
        val clock = Clock()
        // Not refuse()'s envelope: `{ error, code }` only (routes/appLogin.ts).
        val unavailable = """{"error":"App login URL is not configured","code":"app_login_url_unavailable"}"""
        val server = StepUpServer(clock, Reply(503, unavailable, tookMs = 60))
        val harness = Harness(server, clock)
        val outcome = async { harness.coordinator.run(Action(clock, refusal(legacyOnly))::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        harness.coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)

        val span = harness.spans.single()
        assertEquals("error", span.status)
        assertEquals("failed", span.attr("outcome"))
        assertEquals("start", span.attr("failed_step"))
        assertEquals("503", span.attr("http_status"))
        assertEquals("app_login_url_unavailable", span.attr("error_code"))
        assertEquals("60", span.attr("start_ms"))
        assertNull(span.attr("browser_ms"))
        assertEquals(IDS.traceparent, server.requests.single().second["traceparent"])

        // Closing the dialog then sends nothing more: one span per attempt.
        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
        assertEquals(1, harness.spans.size)
    }

    @Test
    fun closingThePageCancelsTheAttemptStartedBesideTheCode() = runTest {
        val clock = Clock()
        val harness = Harness(StepUpServer(clock, Reply(201, started, tookMs = 90)), clock)
        val outcome = async { harness.coordinator.run(Action(clock, refusal(withCode))::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertTrue(assertNotNull(harness.coordinator.state.value).continuesInBrowser)
        harness.coordinator.accept(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.Totp))
        assertTrue(assertNotNull(harness.coordinator.state.value).webStepUpBesideCode)
        val session = harness.openPage()
        clock.ms += 4_000
        harness.coordinator.accept(SecondFactorStepUpIntent.WebStepUpBrowserFinished(session.id, RaftAppWebStepUpBrowserResult.Closed))

        val span = harness.spans.single()
        assertEquals("beside_code", span.attr("trigger"))
        assertEquals("cancelled", span.attr("outcome"))
        assertEquals("90", span.attr("start_ms"))
        assertEquals("4000", span.attr("browser_ms"))
        assertNull(span.attr("failed_step"))
        assertNull(span.attr("complete_ms"))

        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }

    @Test
    fun anAttemptWhoseActionGoesAwayIsAbandoned() = runTest {
        val clock = Clock()
        val harness = Harness(StepUpServer(clock, Reply(201, started, tookMs = 100)), clock)
        val outcome = async { harness.coordinator.run(Action(clock, refusal(legacyOnly))::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        harness.openPage()
        clock.ms += 2_000
        // The page that asked goes away while the browser is up: no answer will come.
        outcome.cancel()
        outcome.join()

        val span = harness.spans.single()
        assertEquals("abandoned", span.attr("outcome"))
        assertEquals("100", span.attr("start_ms"))
        assertNull(span.attr("browser_ms"))
        assertNull(span.attr("failed_step"))
        assertEquals(raftEpochMillisUtcIso(EPOCH_MS + 2_100), span.endTime)
        assertNull(harness.pending.read(), "its request goes with the dialog")
    }

    @Test
    fun anAttemptTheAppIsKilledInIsReportedOnceByTheNextLaunch() = runTest {
        val clock = Clock()
        val harness = Harness(StepUpServer(clock, Reply(201, started, tookMs = 150)), clock)
        backgroundScope.launch { harness.coordinator.run(Action(clock, refusal(legacyOnly))::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        harness.openPage()
        val reported = mutableListOf<RaftWebSpanRecord>()
        val tracer = AppWebStepUpTracer(emit = reported::add)
        // While this launch waits on the page, the request on file is its own.
        reportAppWebStepUpLeftover(harness.pending, tracer)
        assertTrue(reported.isEmpty())

        // Killed with the page up, it sent nothing; the next launch finds the request on file.
        val nextLaunch = AppWebStepUpPendingStore({ harness.store })
        reportAppWebStepUpLeftover(nextLaunch, tracer)
        reportAppWebStepUpLeftover(nextLaunch, tracer)

        assertTrue(harness.spans.isEmpty())
        val span = reported.single()
        assertEquals("abandoned", span.attr("outcome"))
        assertEquals(IDS.traceId, span.traceId, "the trace its start request went out in")
        assertEquals(IDS.spanId, span.spanId)
        assertEquals("legacy_only", span.attr("trigger"))
        assertEquals("computer_attach", span.attr("action"))
        assertEquals("150", span.attr("start_ms"))
        assertNull(span.attr("browser_ms"))
        assertEquals(raftEpochMillisUtcIso(EPOCH_MS), span.startTime)
        assertEquals(raftEpochMillisUtcIso(EPOCH_MS + 150), span.endTime, "last seen: its page opening")
        assertNull(nextLaunch.read(), "its request is dropped")
        assertNothingOfTheRequest(reported, emptyList())
    }

    private companion object {
        const val EPOCH_MS = 1_795_000_000_000L
        val IDS = RaftTraceIds(traceId = "4bf92f3577b34da6a3ce929d0e0e4736", spanId = "00f067aa0ba902b7")
    }
}
