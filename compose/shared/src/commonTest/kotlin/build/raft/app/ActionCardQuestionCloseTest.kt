package build.raft.app

import build.raft.app.data.ActionCardCloseOutcome
import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftIntegrationsActionsPushApi
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.RaftIntegrationsActionsPushNetworkApi
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardClosedBy
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.isRegressingActionCardState
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.thread.ACTION_CARD_CLOSE_ELEMENT_ID
import build.raft.app.thread.ThreadActionCardCommands
import build.raft.app.thread.ThreadActionCardSpecError
import build.raft.app.thread.ThreadActionCardSpecIntent
import build.raft.app.thread.ThreadActionCardSpecStore
import build.raft.app.thread.dispatch
import build.raft.app.thread.questionAnswererCanClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Answerer close on `question:ask` cards (botiverse/slock #16473, `#wg-mobile-sync:#273`):
 * `answererCanClose` wire/metadata round-trip, visibility gate, terminal-state regression guard,
 * `POST /api/actions/:messageId/close` API client & commands, and `ThreadActionCardSpecStore`.
 */
class ActionCardQuestionCloseTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val deadline = "2026-12-05T10:00:00.000Z"
    private val deadlineMillis = assertNotNull(parseRaftTimestampEpochMillisOrNull(deadline))
    private val before = deadlineMillis - 60_000
    private val after = deadlineMillis + 60_000

    private fun wire(body: String): ThreadActionCard =
        assertNotNull(json.decodeFromString(WireActionCardMetadata.serializer(), body).toThreadActionCard())

    private fun questionCard(
        state: String = "prepared",
        answererCanClose: Boolean = true,
        expiresAt: String? = deadline,
        type: String = "question:ask",
    ) = ThreadActionCard(
        type = type,
        state = state,
        expiresAt = expiresAt,
        answererCanClose = answererCanClose,
    )

    @Test
    fun answererCanCloseParsesLenientlyAndRoundTripsThroughMetadata() {
        val closable =
            wire(
                """{"kind":"action-card","state":"prepared","answererCanClose":true,
                   "action":{"type":"question:ask"}}""",
            )
        assertTrue(closable.answererCanClose)
        assertEquals(closable, actionCardFromMessageMetadata(closable.toMessageMetadata()))

        // Older server omits `answererCanClose`, or sends false / malformed values -> false.
        for (raw in listOf(
            """{"kind":"action-card","state":"prepared","action":{"type":"question:ask"}}""",
            """{"kind":"action-card","state":"prepared","answererCanClose":false,"action":{"type":"question:ask"}}""",
            """{"kind":"action-card","state":"prepared","answererCanClose":"true","action":{"type":"question:ask"}}""",
            """{"kind":"action-card","state":"prepared","answererCanClose":1,"action":{"type":"question:ask"}}""",
        )) {
            val parsed = wire(raw)
            assertFalse(parsed.answererCanClose, raw)
            assertFalse(actionCardFromMessageMetadata(parsed.toMessageMetadata())!!.answererCanClose, raw)
        }
    }

    @Test
    fun questionAnswererCanCloseRequiresPreparedQuestionCardWithServerFlagAndAnswererEligibility() {
        assertTrue(questionCard().questionAnswererCanClose(canAnswer = true, nowMillis = before))
        assertFalse(questionCard(answererCanClose = false).questionAnswererCanClose(canAnswer = true, nowMillis = before))
        assertFalse(questionCard().questionAnswererCanClose(canAnswer = false, nowMillis = before))
        assertFalse(questionCard().questionAnswererCanClose(canAnswer = true, nowMillis = after))
        assertFalse(questionCard(type = "channel:create").questionAnswererCanClose(canAnswer = true, nowMillis = before))
        for (state in listOf("executed", "closed", "withdrawn", "expired", "frozen", "reconfirm_required", "someday")) {
            assertFalse(questionCard(state = state).questionAnswererCanClose(canAnswer = true, nowMillis = before), state)
        }
    }

    @Test
    fun aClosedOrEndedCardNeverRegressesToAnOpenState() {
        val prepared = questionCard(state = "prepared").toMessageMetadata()
        val frozen = questionCard(state = "frozen").toMessageMetadata()
        val closedDirect = questionCard(state = "closed").toMessageMetadata()
        val closedServed =
            questionCard(state = "expired")
                .copy(closedAt = "2026-11-23T10:00:00.000Z", closedBy = ThreadActionCardClosedBy("u-mei", "mei"))
                .toMessageMetadata()
        val executed = questionCard(state = "executed").toMessageMetadata()
        val withdrawn = questionCard(state = "withdrawn").toMessageMetadata()
        val expired = questionCard(state = "expired").toMessageMetadata()

        for (terminal in listOf(closedDirect, closedServed, executed, withdrawn, expired)) {
            assertTrue(isRegressingActionCardState(held = terminal, incoming = prepared))
            assertTrue(isRegressingActionCardState(held = terminal, incoming = frozen))
            assertFalse(isRegressingActionCardState(held = prepared, incoming = terminal))
        }
        assertFalse(isRegressingActionCardState(held = frozen, incoming = prepared))
    }

    private class RecordingNetworkClient(private val response: HomeNetworkResponse) : RaftNetworkClient {
        val requests = mutableListOf<Triple<String, String, String?>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            requests += Triple(method, path, bodyText)
            return response
        }
    }

    @Test
    fun closeQuestionActionPostsToActionsMessageIdCloseWithoutBodyAndMapsResponses() = runTest {
        val closedMetadata =
            """{"kind":"action-card","state":"expired","closedAt":"2026-11-23T10:00:00.000Z",
               "closedBy":{"userId":"u-mei","handle":"mei"},"action":{"type":"question:ask"}}"""
        val network = RecordingNetworkClient(HomeNetworkResponse(200, """{"messageId":"m-1","metadata":$closedMetadata}"""))
        val client = RaftIntegrationsActionsPushApiClient(RaftIntegrationsActionsPushNetworkApi(network))
        val ok = assertIs<DataResult.Content<ActionCardCloseOutcome>>(client.closeQuestionActionCard(MessageId("m-1"))).data
        val closed = assertIs<ActionCardCloseOutcome.Closed>(ok)
        assertEquals(listOf(Triple<String, String, String?>("POST", "actions/m-1/close", null)), network.requests)
        assertTrue(assertNotNull(closed.card).isClosed)
        assertEquals(ThreadActionCardClosedBy("u-mei", "mei"), closed.card?.closedBy)

        // 409 ALREADY_EXECUTED returns the answered card as typed content.
        val answeredMetadata =
            """{"kind":"action-card","state":"executed","executedByUserName":"Ada","action":{"type":"question:ask"}}"""
        val answeredClient =
            RaftIntegrationsActionsPushApiClient(
                RaftIntegrationsActionsPushNetworkApi(
                    RecordingNetworkClient(
                        HomeNetworkResponse(
                            code = 409,
                            bodyText = null,
                            errorBodyText = """{"error":"This question was already answered; it was not closed","errorCode":"ALREADY_EXECUTED","metadata":$answeredMetadata}""",
                        ),
                    ),
                ),
            )
        val already =
            assertIs<ActionCardCloseOutcome.AlreadyExecuted>(
                assertIs<DataResult.Content<ActionCardCloseOutcome>>(answeredClient.closeQuestionActionCard(MessageId("m-1"))).data,
            )
        assertEquals("executed" to "Ada", already.card?.state to already.card?.executedByUserName)

        // 409 QUESTION_WITHDRAWN keeps the server's message, errorCode and trace id.
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val withdrawnClient =
            RaftIntegrationsActionsPushApiClient(
                RaftIntegrationsActionsPushNetworkApi(
                    RecordingNetworkClient(
                        HomeNetworkResponse(
                            code = 409,
                            bodyText = null,
                            errorBodyText = """{"error":"This question was already withdrawn; it was not closed","errorCode":"QUESTION_WITHDRAWN"}""",
                            responseHeaders = mapOf("X-Trace-Id" to listOf(traceId.uppercase())),
                        ),
                    ),
                ),
            )
        val withdrawnErr = assertIs<DataResult.Error>(withdrawnClient.closeQuestionActionCard(MessageId("m-1"))).error
        assertEquals("QUESTION_WITHDRAWN", withdrawnErr.serverCode)
        assertEquals("This question was already withdrawn; it was not closed", withdrawnErr.serverMessage)
        assertEquals(traceId, withdrawnErr.traceId)
        assertFalse(withdrawnErr.retryable)

        // 401 maps to auth_required NoPermission.
        val unauthClient =
            RaftIntegrationsActionsPushApiClient(
                RaftIntegrationsActionsPushNetworkApi(RecordingNetworkClient(HomeNetworkResponse(401, null, """{"error":"Unauthorized"}"""))),
            )
        assertEquals("auth_required", assertIs<DataResult.NoPermission>(unauthClient.closeQuestionActionCard(MessageId("m-1"))).blocker.code)
    }

    @Test
    fun commandsFallbackToClosedWhenResponseHasNoMetadataAndGuardFrozenCards() = runTest {
        val network = RecordingNetworkClient(HomeNetworkResponse(200, """{"messageId":"m-1"}"""))
        val commands =
            ThreadActionCardCommands(
                agentsApiClient = null,
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(RaftIntegrationsActionsPushNetworkApi(network)),
                createAgentCoordinator = null,
                channelRepository = null,
                memberRepository = null,
                serverMembersStore = null,
                readSession = { null },
            )
        val frozen = assertIs<DataResult.Error>(commands.closeQuestionActionCard("m-1", questionCard(state = "frozen")))
        assertEquals("action_card_frozen", frozen.error.code)
        assertTrue(network.requests.isEmpty())

        val fallback =
            assertIs<ActionCardCloseOutcome.Closed>(
                assertIs<DataResult.Content<ActionCardCloseOutcome>>(commands.closeQuestionActionCard("m-1", questionCard())).data,
            )
        assertEquals("closed", fallback.card?.state)
        assertTrue(assertNotNull(fallback.card).isClosed)
    }

    @Test
    fun storeClosesOnceAppliesResultAndHandlesAlreadyExecutedRefusalsAndNonPreparedGuards() = runTest {
        val calls = mutableListOf<String>()
        val applied = mutableListOf<Pair<String, ThreadActionCard>>()
        var approvals = 0
        val pending = CompletableDeferred<DataResult<ActionCardCloseOutcome>>()
        val store =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> error("execute must not run") },
                closeQuestion = { messageId, _ ->
                    calls += messageId
                    pending.await()
                },
                applyCard = { messageId, card -> applied += messageId to card },
                nowMillis = { before },
                onApproved = { approvals++ },
            )

        // Inline choices get cleared on close; concurrent close while busy is ignored; closing is not an approval.
        store.dispatch(ThreadActionCardSpecIntent.InlineValuesChanged("m-1", mapOf("db" to ActionCardFormValue.Choice(listOf("postgres")))))
        store.dispatch(ThreadActionCardSpecIntent.CloseQuestion("m-1", questionCard()))
        runCurrent()
        assertEquals(ACTION_CARD_CLOSE_ELEMENT_ID, store.state.value.slice("m-1").busy)
        store.dispatch(ThreadActionCardSpecIntent.CloseQuestion("m-1", questionCard()))
        runCurrent()
        assertEquals(listOf("m-1"), calls)

        val closed = questionCard(state = "expired").copy(closedAt = "2026-11-23T10:00:00.000Z", closedBy = ThreadActionCardClosedBy("u-mei", "mei"))
        pending.complete(DataResult.Content(ActionCardCloseOutcome.Closed(closed)))
        runCurrent()
        assertNull(store.state.value.slice("m-1").busy)
        assertNull(store.state.value.slice("m-1").error)
        assertNull(store.state.value.slice("m-1").inlineValues)
        assertEquals(listOf("m-1" to closed), applied)
        assertEquals(0, approvals, "closing without answering is not an approval")

        // Non-prepared cards send nothing.
        for (state in listOf("executed", "closed", "withdrawn", "expired", "frozen", "reconfirm_required")) {
            store.dispatch(ThreadActionCardSpecIntent.CloseQuestion("m-$state", questionCard(state = state)))
        }
        runCurrent()
        assertEquals(listOf("m-1"), calls)

        // Past deadline on client clock sends nothing and marks the card expired locally.
        val lateApplied = mutableListOf<ThreadActionCard>()
        val lateStore =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> error("not used") },
                closeQuestion = { _, _ -> error("expired card must not post close") },
                applyCard = { _, card -> lateApplied += card },
                nowMillis = { after },
            )
        lateStore.dispatch(ThreadActionCardSpecIntent.CloseQuestion("m-late", questionCard()))
        runCurrent()
        assertEquals("expired", lateApplied.single().state)
    }

    @Test
    fun storeAppliesAnsweredCardOnAlreadyExecutedAndShowsErrorOnOtherRefusals() = runTest {
        val answers = ArrayDeque<DataResult<ActionCardCloseOutcome>>()
        val applied = mutableListOf<Pair<String, ThreadActionCard>>()
        var authRouted = false
        val store =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> error("not used") },
                closeQuestion = { _, _ -> answers.removeFirst() },
                applyCard = { messageId, card -> applied += messageId to card },
                onNoPermission = { authRouted = true; true },
                nowMillis = { before },
            )

        // 409 ALREADY_EXECUTED: applies answered card, sets no error.
        val answered = questionCard(state = "executed").copy(executedByUserName = "Ada")
        answers += DataResult.Content(ActionCardCloseOutcome.AlreadyExecuted(answered, "Already answered"))
        store.dispatch(ThreadActionCardSpecIntent.CloseQuestion("m-1", questionCard()))
        runCurrent()
        assertEquals(listOf("m-1" to answered), applied)
        assertNull(store.state.value.slice("m-1").error)

        // 409 QUESTION_WITHDRAWN: shows server message under card, clears busy for recovery.
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        answers +=
            DataResult.Error(
                ScreenError(
                    code = "action_close_contract_mismatch",
                    retryable = false,
                    serverCode = "QUESTION_WITHDRAWN",
                    serverMessage = "This question was already withdrawn; it was not closed",
                    traceId = traceId,
                ),
            )
        store.dispatch(ThreadActionCardSpecIntent.CloseQuestion("m-2", questionCard()))
        runCurrent()
        assertNull(store.state.value.slice("m-2").busy)
        assertEquals(
            ThreadActionCardSpecError.Failed("This question was already withdrawn; it was not closed", traceId),
            store.state.value.slice("m-2").error,
        )

        // 409 QUESTION_EXPIRED: marks expired locally, no banner.
        answers +=
            DataResult.Error(
                ScreenError(
                    code = "action_close_contract_mismatch",
                    retryable = false,
                    serverCode = "QUESTION_EXPIRED",
                    serverMessage = "This question already expired; it was not closed",
                ),
            )
        store.dispatch(ThreadActionCardSpecIntent.CloseQuestion("m-3", questionCard()))
        runCurrent()
        assertEquals("expired", applied.last().second.state)
        assertNull(store.state.value.slice("m-3").error)

        // 401 NoPermission routes to onNoPermission without error banner.
        answers += DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))
        store.dispatch(ThreadActionCardSpecIntent.CloseQuestion("m-4", questionCard()))
        runCurrent()
        assertTrue(authRouted)
        assertNull(store.state.value.slice("m-4").error)
    }
}
