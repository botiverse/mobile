package build.raft.app.data

import build.raft.app.data.RuntimeLimitResetTest.Companion.KEY
import build.raft.app.data.RuntimeLimitResetTest.Companion.ok
import build.raft.app.data.RuntimeLimitResetTest.FakeLimitResetApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A request that waits on the Computer past the edge's 30 s (botiverse/slock #14842 / #14843 at
 * ae2c99ce1aac7b495e8c0646aa67d41862a90572): it asks for `respondAsync`, gets 202 `{operationId}`
 * and reads `GET …/machine-operations/:operationId` until done. Cases follow web
 * `tests/runtimeAuthClientMachineOperation.test.ts`; payloads are the server's
 * (`runtimeClaudeLimitReset.api.test.ts`: `{state:"pending"}`, `{state:"done",status,body}`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineOperationsTest {
    private fun started(id: String = OP) =
        HomeNetworkResponse(code = 202, bodyText = """{"operationId":"$id"}""", responseHeaders = mapOf("X-Trace-Id" to listOf(TRACE)))

    private fun pending() = ok("""{"state":"pending"}""")

    private fun done(status: Int, body: String) = ok("""{"state":"done","status":$status,"body":$body}""")

    private fun reads(api: FakeLimitResetApi) = api.calls.count { it == "GET servers/s1/machine-operations/$OP" }

    private fun answers(vararg steps: () -> HomeNetworkResponse): () -> HomeNetworkResponse {
        val queue = ArrayDeque(steps.toList())
        return { queue.removeFirstOrNull()?.invoke() ?: error("no more machine-operation answers") }
    }

    @Test
    fun aClaudeReadAsksForRespondAsyncAndReadsTheOperationUntilDone() = runTest {
        val api =
            FakeLimitResetApi(
                readClaude = { started() },
                operation =
                    answers(
                        { pending() },
                        { HomeNetworkResponse(code = 502, bodyText = null) },
                        { throw IllegalStateException("socket closed") },
                        { done(200, READ_BODY) },
                    ),
            )
        val result = RaftComputersApiClient(api).readClaudeLimitResets(ServerId("s1"), "m1", consent = false)

        assertEquals(RuntimeLimitResetsReadResult.Unsupported("consent_required"), assertIs<RuntimeLimitResetsReadLoad.Done>(result).result)
        assertEquals(
            """POST servers/s1/machines/m1/runtime-account-usage/claude/limit-resets/read {"onboardingRequiredReason":true,"respondAsync":true}""",
            api.calls.first(),
        )
        assertEquals(4, reads(api), "a pending read, a 502 and a dropped read are all read again")
        assertEquals(4 * MACHINE_OPERATION_POLL_MILLIS, currentTime, "one read every 1.5 s")
    }

    @Test
    fun anOlderServersDirectAnswerIsUsedAsIs() = runTest {
        val api = FakeLimitResetApi(readClaude = { ok(READ_BODY) })
        val result = RaftComputersApiClient(api).readClaudeLimitResets(ServerId("s1"), "m1", consent = true)

        assertIs<RuntimeLimitResetsReadLoad.Done>(result)
        assertEquals(
            listOf("""POST servers/s1/machines/m1/runtime-account-usage/claude/limit-resets/read {"consent":true,"onboardingRequiredReason":true,"respondAsync":true}"""),
            api.calls,
            "no operation is read",
        )
        assertEquals(0L, currentTime)
    }

    @Test
    fun aRefusalBeforeTheWaitStillAnswersAtOnce() = runTest {
        val api =
            FakeLimitResetApi(
                redeem = {
                    HomeNetworkResponse(
                        code = 409,
                        bodyText = null,
                        errorBodyText = """{"code":"computer_offline","error":"This Computer is offline."}""",
                        responseHeaders = mapOf("x-trace-id" to listOf(TRACE)),
                    )
                },
            )
        val load = RaftComputersApiClient(api).redeemRuntimeLimitReset(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Codex, key())
        assertEquals(
            RuntimeLimitResetRefusal(409, "computer_offline", TRACE, "This Computer is offline."),
            assertIs<RuntimeLimitResetLoad.Failed>(load).refusal,
        )
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aFailedOperationReadsAsTheHttpErrorWouldWithTheStartingRequestsTraceId() = runTest {
        val api =
            FakeLimitResetApi(
                redeem = { started() },
                operation = answers({ done(504, """{"code":"computer_timeout","error":"late"}""") }),
            )
        val load =
            RaftComputersApiClient(api)
                .redeemRuntimeLimitReset(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Claude, key(), RuntimeLimitResetKind.Weekly)

        val refusal = assertIs<RuntimeLimitResetLoad.Failed>(load).refusal
        assertEquals(RuntimeLimitResetRefusal(504, "computer_timeout", TRACE, "late"), refusal)
        assertTrue(api.calls.first().endsWith("""{"idempotencyKey":"$KEY","kind":"weekly","onboardingRequiredReason":true,"respondAsync":true}"""), api.calls.first())
    }

    @Test
    fun noAnswerInTimeOrAGoneOperationIsAnOutcomeNeverLearned() = runTest {
        val late = FakeLimitResetApi(readClaude = { started() }, operation = { pending() })
        val never = RaftComputersApiClient(late).readClaudeLimitResets(ServerId("s1"), "m1", consent = false)
        val refusal = assertIs<RuntimeLimitResetsReadLoad.Failed>(never).refusal
        assertNull(refusal.status, "no status: the outcome was never learned")
        assertEquals(TRACE, refusal.traceId)
        val deadline = RUNTIME_LIMIT_RESET_CLAUDE_RELAY_BUDGET_MILLIS + MACHINE_OPERATION_SLACK_MILLIS
        assertEquals(deadline, currentTime, "the Claude relay budget plus 10 s")
        assertEquals((deadline / MACHINE_OPERATION_POLL_MILLIS).toInt(), reads(late))

        val gone = FakeLimitResetApi(readClaude = { started() }, operation = { HomeNetworkResponse(404, """{"code":"operation_not_found"}""") })
        val read = RaftComputersApiClient(gone).readClaudeLimitResets(ServerId("s1"), "m1", consent = false)
        assertNull(assertIs<RuntimeLimitResetsReadLoad.Failed>(read).refusal.status)
        assertEquals(1, reads(gone), "a 404 is not read again")
    }

    @Test
    fun eachRouteWaitsForItsOwnRelayBudget() = runTest {
        suspend fun deadlineOf(call: suspend (RaftComputersApiClient) -> Unit): Long {
            val start = currentTime
            call(
                RaftComputersApiClient(
                    FakeLimitResetApi(logout = { started() }, redeem = { started() }, operation = { pending() })
                )
            )
            return currentTime - start
        }
        assertEquals(
            RUNTIME_AUTH_LOGOUT_RELAY_BUDGET_MILLIS + MACHINE_OPERATION_SLACK_MILLIS,
            deadlineOf { it.logoutRuntime(ServerId("s1"), "m1", RuntimeAuthRuntime.Codex) },
        )
        assertEquals(
            RUNTIME_LIMIT_RESET_RELAY_BUDGET_MILLIS + MACHINE_OPERATION_SLACK_MILLIS,
            deadlineOf { it.redeemRuntimeLimitReset(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Codex, key()) },
        )
        assertEquals(
            RUNTIME_LIMIT_RESET_CLAUDE_RELAY_BUDGET_MILLIS + MACHINE_OPERATION_SLACK_MILLIS,
            deadlineOf { it.redeemRuntimeLimitReset(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Claude, key(), RuntimeLimitResetKind.FiveHour) },
        )
    }

    @Test
    fun onlyAUuidIsAnOperationId() {
        assertEquals(OP, started().machineOperationIdOrNull())
        assertNull(started("op-1").machineOperationIdOrNull())
        // The server mints `randomUUID()` (lowercase) and slock `asMachineOperationId` takes only that.
        val minted = "3f2b8c1d-9e4a-4b7f-a2c6-d1e0f9a8b7c6"
        assertEquals(minted, started(minted).machineOperationIdOrNull())
        assertNull(started(minted.uppercase()).machineOperationIdOrNull())
        assertNull(HomeNetworkResponse(409, """{"operationId":"$OP"}""").machineOperationIdOrNull(), "a refusal never started one")
        assertNull(ok(READ_BODY).machineOperationIdOrNull())
    }

    @Test
    fun theViewParsesPendingAndDoneAndReadsAnUnknownStateAsPendingWithATrace() {
        val logged = mutableListOf<String>()
        assertEquals(MachineOperationView.Pending, parseMachineOperationView("""{"state":"pending"}"""))
        val done = assertIs<MachineOperationView.Done>(parseMachineOperationView("""{"state":"done","status":200,"body":$READ_BODY}"""))
        assertEquals(200, done.status)
        assertEquals(MachineOperationView.Pending, parseMachineOperationView("""{"state":"cancelled"}""") { logged += it })
        assertTrue(logged.single().startsWith("projection_value_unknown source=machine_operation field=state"), logged.toString())
        assertNull(parseMachineOperationView("""{"state":"done","body":{}}"""), "done without a status")
        assertNull(parseMachineOperationView("""{"operationId":"$OP"}"""))
        assertNull(parseMachineOperationView(null))
    }

    private fun key() = assertNotNull(RuntimeLimitResetIdempotencyKey.of(KEY))

    private companion object {
        /** web runtimeAuthClientMachineOperation.test.ts `OP`. */
        const val OP = "11111111-2222-4333-8444-555555555555"
        const val TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"

        /** slock runtimeClaudeLimitReset.api.test.ts: the done body of a read that needs consent. */
        const val READ_BODY = """{"result":{"kind":"unsupported","reason":"consent_required"}}"""
    }
}
