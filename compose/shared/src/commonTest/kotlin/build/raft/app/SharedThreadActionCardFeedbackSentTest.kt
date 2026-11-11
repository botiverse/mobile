package build.raft.app

import build.raft.app.SharedThreadStoreMembersAndActionCardsTest.RecordingActionCardsApi
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftFeedbackAgentDraftSent
import build.raft.app.data.RaftFeedbackAgentDraftSentRepository
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.SyncTestPreferences
import build.raft.app.data.ThreadActionCard
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A `feedback:submit` card sent through its form sheet (POST /actions/:id/execute answered with the
 * executed card) retires Settings › Feedback's agent tip for the signed-in person, as the web's
 * executeActionCard does (botiverse/slock 0d4ec9fd4f58, #14076 `actionCardCommon.tsx`); any other
 * card's execute does not.
 */
class SharedThreadActionCardFeedbackSentTest {
    private val original = RaftFeedbackAgentDraftSent.repository
    private val drafts = RaftFeedbackAgentDraftSentRepository { SyncTestPreferences() }

    @BeforeTest
    fun swap() {
        RaftFeedbackAgentDraftSent.repository = drafts
    }

    @AfterTest
    fun restore() {
        RaftFeedbackAgentDraftSent.repository = original
    }

    // routes/actions.ts execute: `{ messageId, metadata }`, the stored card done (actionCards feedback:submit).
    private fun executed(type: String, action: String) =
        HomeNetworkResponse(
            code = 200,
            bodyText = """{"messageId":"m-card","metadata":{"kind":"action-card","state":"executed","executedByUserId":"u1","action":{"type":"$type",$action}}}""",
        )

    private suspend fun kotlinx.coroutines.test.TestScope.execute(answer: HomeNetworkResponse, card: ThreadActionCard): DataResult<ActionCardExecuteOutcome> {
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(RecordingActionCardsApi(executeResponse = answer)),
            )
        return dataSource.executeActionCardSpec("m-card", card)
    }

    @Test
    fun anExecutedFeedbackCardRetiresTheTipAnotherCardDoesNot() = runTest {
        val other = execute(executed("channel:create", """"name":"room","visibility":"public""""), ThreadActionCard(type = "channel:create", name = "room"))
        assertIs<ActionCardExecuteOutcome.Executed>(assertIs<DataResult.Content<ActionCardExecuteOutcome>>(other).data)
        assertFalse(drafts.isSent("u1"))

        val feedback =
            execute(
                executed("feedback:submit", """"feedbackType":"problem","message":"Search is slow""""),
                ThreadActionCard(type = "feedback:submit"),
            )
        val card = assertIs<ActionCardExecuteOutcome.Executed>(assertIs<DataResult.Content<ActionCardExecuteOutcome>>(feedback).data).card
        assertEquals("feedback:submit", card?.type)
        assertTrue(drafts.isSent("u1"))
    }
}
