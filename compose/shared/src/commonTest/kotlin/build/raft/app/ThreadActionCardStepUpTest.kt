package build.raft.app

import build.raft.app.SharedThreadStoreMembersAndActionCardsTest.RecordingActionCardsApi
import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.SecondFactorStepUpAnswer
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpIntent
import build.raft.app.data.SecondFactorStepUpMethod
import build.raft.app.data.SecondFactorStepUpOutcome
import build.raft.app.data.SecondFactorStepUpResult
import build.raft.app.data.ThreadActionCard
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * An Approve Login card (`integration:approve_agent_login`) refused for a fresh second step:
 * "Verify it's you", then the execute runs exactly once more; closing the dialog runs nothing.
 * Pending contract verification for the card path: at botiverse/slock staging 0dd599ab68 (#12297)
 * only POST /integrations/requests/:id/approve is gated, not the card's POST /actions/:id/execute.
 * The refusal is that SHA's approve body (routes/integrations.ts `refuseUnverifiedApprover`, as
 * asserted by routes/secondFactorStepUp.api.test.ts).
 */
class ThreadActionCardStepUpTest {
    private val refusal =
        HomeNetworkResponse(
            code = 403,
            bodyText = null,
            errorBodyText =
                """{"error":"Confirm it's you with your second step before approving this app's access.","code":"second_factor_step_up_required","methods":["totp","backup_code"]}"""
        )
    private val card =
        ThreadActionCard(
            type = "integration:approve_agent_login",
            requestId = "request-1",
            agentId = "agent-1",
            agentName = "KMP-专家",
            clientId = "client-1",
            clientKey = "hands",
            clientName = "Hands",
            scopes = listOf("identity", "profile")
        )
    private val execute = """POST actions/m-card/execute body={"expectedState":"prepared"}"""

    private class Harness(scope: TestScope, firstExecuteResponses: List<HomeNetworkResponse>) {
        val actionCardsApi = RecordingActionCardsApi(firstExecuteResponses = firstExecuteResponses)
        val answers = mutableListOf<SecondFactorStepUpAnswer>()
        val stepUp =
            SecondFactorStepUpCoordinator(stepUp = { answer ->
                answers += answer
                SecondFactorStepUpResult.Verified()
            })
        val dataSource =
            testSharedThreadDataSource(
                storeScope = scope.backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )
    }

    @Test
    fun refusedApproveVerifiesThenRunsOnceMore() = runTest {
        val harness = Harness(this, listOf(refusal))
        val commit = async { harness.dataSource.commitThreadActionCard(basePayload(), "m-card", card, harness.stepUp) }
        runCurrent()

        val dialog = assertNotNull(harness.stepUp.state.value, "the refusal opens Verify it's you")
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), dialog.methods)
        assertEquals(listOf(execute), harness.actionCardsApi.calls)

        harness.stepUp.accept(SecondFactorStepUpIntent.InputChanged("123 456"))
        harness.stepUp.accept(SecondFactorStepUpIntent.Submit)
        val outcome = assertIs<SecondFactorStepUpOutcome.Completed<ThreadActionCard>>(commit.await())

        assertEquals(listOf<SecondFactorStepUpAnswer>(SecondFactorStepUpAnswer.AuthenticatorCode("123456")), harness.answers)
        assertEquals("executed", assertIs<DataResult.Content<ThreadActionCard>>(outcome.result).data.state)
        assertEquals(listOf(execute, execute), harness.actionCardsApi.calls)
        assertNull(harness.stepUp.state.value)
    }

    @Test
    fun aSecondRefusalIsTheCallersErrorWithoutAnotherRetry() = runTest {
        val harness = Harness(this, listOf(refusal, refusal))
        val commit = async { harness.dataSource.commitThreadActionCard(basePayload(), "m-card", card, harness.stepUp) }
        runCurrent()
        harness.stepUp.accept(SecondFactorStepUpIntent.InputChanged("123456"))
        harness.stepUp.accept(SecondFactorStepUpIntent.Submit)

        val outcome = assertIs<SecondFactorStepUpOutcome.Completed<ThreadActionCard>>(commit.await())
        assertIs<DataResult.Error>(outcome.result)
        assertEquals(listOf(execute, execute), harness.actionCardsApi.calls)
        assertNull(harness.stepUp.state.value)
    }

    @Test
    fun closingVerifyItsYouRunsNothingMore() = runTest {
        val harness = Harness(this, listOf(refusal))
        val commit = async { harness.dataSource.commitThreadActionCard(basePayload(), "m-card", card, harness.stepUp) }
        runCurrent()
        assertNotNull(harness.stepUp.state.value, "the refusal opens Verify it's you")

        harness.stepUp.accept(SecondFactorStepUpIntent.Cancel)

        assertEquals(SecondFactorStepUpOutcome.Cancelled, commit.await())
        assertEquals(emptyList(), harness.answers)
        assertEquals(listOf(execute), harness.actionCardsApi.calls)
        assertNull(harness.stepUp.state.value)
    }

    @Test
    fun anApproveWithoutRefusalNeverOpensTheDialog() = runTest {
        val harness = Harness(this, emptyList())

        val outcome = harness.dataSource.commitThreadActionCard(basePayload(), "m-card", card, harness.stepUp)

        assertEquals("executed", assertIs<DataResult.Content<ThreadActionCard>>(assertIs<SecondFactorStepUpOutcome.Completed<ThreadActionCard>>(outcome).result).data.state)
        assertEquals(listOf(execute), harness.actionCardsApi.calls)
        assertNull(harness.stepUp.state.value)
    }
}
