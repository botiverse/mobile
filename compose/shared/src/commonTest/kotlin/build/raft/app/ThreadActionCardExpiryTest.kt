package build.raft.app

import build.raft.app.SharedThreadStoreMembersAndActionCardsTest.RecordingActionCardsApi
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardClosedBy
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.expiredAsOf
import build.raft.app.data.isActionCardExpiredRefusal
import build.raft.app.data.isPastExpiry
import build.raft.app.data.markedExpired
import build.raft.app.data.parseActionCardSpec
import build.raft.app.data.stripActionCardSpec
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import build.raft.app.model.MessageId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Action card expiry (Tenny 11-05, #proj-apps task 40, slock #13254): the top-level `expiresAt`
 * of a prepared card, state `expired` (still carrying `expiresAt`), the client clock deciding only whether buttons show and
 * dialogs open, and the server's 409 drawn as an expired card.
 */
class ThreadActionCardExpiryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val deadline = "2026-12-05T10:00:00.000Z"
    private val deadlineMillis = assertNotNull(parseRaftTimestampEpochMillisOrNull(deadline))
    private val before = deadlineMillis - 60_000
    private val after = deadlineMillis + 60_000

    private fun wire(body: String): ThreadActionCard =
        assertNotNull(json.decodeFromString(WireActionCardMetadata.serializer(), body).toThreadActionCard())

    private fun card(type: String = "channel:create", state: String = "prepared", expiresAt: String? = deadline) =
        ThreadActionCard(type = type, state = state, name = "launch", expiresAt = expiresAt)

    @Test
    fun aPreparedCardReadsItsTopLevelExpiresAt() {
        val read =
            wire(
                """{"kind":"action-card","state":"prepared","expiresAt":"$deadline",
                   "action":{"type":"channel:create","name":"launch"}}""",
            )
        assertEquals(deadline, read.expiresAt)
        assertEquals("prepared", read.state)
        // Kept through the thread's message metadata, where every card is read from.
        assertEquals(read, actionCardFromMessageMetadata(read.toMessageMetadata()))
    }

    @Test
    fun aCardWithoutADeadlineHasNoExpiresAtAndNeverExpires() {
        val read = wire("""{"kind":"action-card","action":{"type":"channel:create","name":"launch"}}""")
        assertNull(read.expiresAt)
        assertFalse(read.isPastExpiry(Long.MAX_VALUE))
        assertEquals(read, read.expiredAsOf(Long.MAX_VALUE))
        assertNull(actionCardFromMessageMetadata(read.toMessageMetadata())?.expiresAt)
    }

    @Test
    fun anExpiredCardReadsItsStateAndKeepsItsExpiresAt() {
        // A 30-day card past its deadline is served `expired` with the same `expiresAt`, no `expiredAt`.
        val read =
            wire(
                """{"kind":"action-card","state":"expired","expiresAt":"$deadline",
                   "action":{"type":"agent:create","name":"Scout"}}""",
            )
        assertTrue(read.isExpired)
        assertTrue(read.hasEnded)
        assertEquals(deadline, read.expiresAt)
        assertNull(read.requesterUserId)
        // A person's request names who asked.
        assertEquals(
            "u-1",
            wire("""{"kind":"action-card","state":"expired","requesterUserId":"u-1","action":{"type":"seats:add"}}""").requesterUserId,
        )
        assertEquals(read, actionCardFromMessageMetadata(read.toMessageMetadata()))
    }

    @Test
    fun theClientClockExpiresOnlyAWaitingCardPastItsDeadline() {
        assertEquals("prepared", card().expiredAsOf(before).state)
        val expired = card().expiredAsOf(after)
        assertEquals("expired", expired.state)
        assertEquals(deadline, expired.expiresAt)
        // Only a prepared card runs out on the clock (web isActionCardShownExpired); the rest are as the server says.
        assertEquals("reconfirm_required", card(state = "reconfirm_required").expiredAsOf(after).state)
        assertEquals("executed", card(state = "executed").expiredAsOf(after).state)
        assertEquals("frozen", card(state = "frozen").expiredAsOf(after).state)
        assertEquals("withdrawn", card(type = "question:ask", state = "withdrawn").expiredAsOf(after).state)
        // An unreadable deadline is no deadline.
        assertEquals("prepared", card(expiresAt = "soon").expiredAsOf(after).state)
    }

    @Test
    fun theSpecStatusNamesExpiredWithdrawnClosedAndTreatsUnknownStatesAsEnded() {
        assertEquals(RaftActionCardStatus.Expired, card(state = "expired").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Expired, card().expiredAsOf(after).actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Prepared, card().expiredAsOf(before).actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Withdrawn, card(type = "question:ask", state = "withdrawn").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Expired, card(type = "question:ask", state = "expired").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Expired, card(type = "seats:add", state = "expired").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Closed, card(type = "question:ask", state = "closed").actionCardSpecStatus())
        assertEquals(
            RaftActionCardStatus.Closed,
            card(type = "question:ask", state = "expired").copy(closedBy = ThreadActionCardClosedBy(userId = "u-1", handle = "mei")).actionCardSpecStatus(),
        )
        // Unknown states (and question-only states on non-question cards) default to Ended (view-only, read-only).
        assertEquals(RaftActionCardStatus.Ended, card(state = "withdrawn").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Ended, card(state = "closed").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Ended, card(state = "someday").actionCardSpecStatus())
        assertTrue(card(state = "someday").hasEnded)
        assertTrue(card(state = "someday").isUnknownState)
        assertTrue(card(type = "question:ask", state = "someday").hasEnded)
        assertEquals(RaftActionCardStatus.Ended, card(type = "question:ask", state = "someday").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Executed, card(state = "executed").actionCardSpecStatus())
    }

    @Test
    fun aClosedQuestionCardReadsClosedByAndRoundTripsThroughMetadata() {
        // Server maps stored `closed` -> served `expired` with `closedAt` and `closedBy` for compatibility (slock #16473).
        val servedAsExpired =
            wire(
                """{"kind":"action-card","state":"expired","closedAt":"2026-11-23T18:00:00.000Z",
                   "closedBy":{"userId":"u-42","handle":"mei"},
                   "action":{"type":"question:ask"}}""",
            )
        assertTrue(servedAsExpired.isClosed)
        assertFalse(servedAsExpired.isExpired)
        assertTrue(servedAsExpired.hasEnded)
        assertEquals("2026-11-23T18:00:00.000Z", servedAsExpired.closedAt)
        assertEquals(ThreadActionCardClosedBy(userId = "u-42", handle = "mei"), servedAsExpired.closedBy)
        assertEquals(RaftActionCardStatus.Closed, servedAsExpired.actionCardSpecStatus())
        assertEquals(servedAsExpired, actionCardFromMessageMetadata(servedAsExpired.toMessageMetadata()))

        // Direct `closed` state (future server / agent view) also reads as Closed.
        val directClosed =
            wire(
                """{"kind":"action-card","state":"closed",
                   "closedBy":{"userId":"u-42","handle":"mei"},
                   "action":{"type":"question:ask"}}""",
            )
        assertTrue(directClosed.isClosed)
        assertFalse(directClosed.isExpired)
        assertTrue(directClosed.hasEnded)
        assertEquals(RaftActionCardStatus.Closed, directClosed.actionCardSpecStatus())

        // An expired question card without `closedBy` (or with non-object `closedBy`) stays Expired.
        val plainExpired = wire("""{"kind":"action-card","state":"expired","action":{"type":"question:ask"}}""")
        assertFalse(plainExpired.isClosed)
        assertTrue(plainExpired.isExpired)
        assertEquals(RaftActionCardStatus.Expired, plainExpired.actionCardSpecStatus())

        val malformedClosedBy = wire("""{"kind":"action-card","state":"expired","closedBy":"not-an-object","action":{"type":"question:ask"}}""")
        assertNull(malformedClosedBy.closedBy)
        assertFalse(malformedClosedBy.isClosed)
        assertTrue(malformedClosedBy.isExpired)
    }

    @Test
    fun anEndedSpecCardShowsNoButtonThatChangesItButKeepsItsLinks() {
        val read =
            assertNotNull(
                parseActionCardSpec(
                    Json.parseToJsonElement(
                        """
                        {"catalog":"action-card.v1","title":{"key":"actionCard.test.t","text":{"en":"Card"}},
                         "spec":{"root":"root","elements":{
                           "root":{"type":"Stack","props":{},"children":["go","site"]},
                           "go":{"type":"Button","props":{"label":{"key":"actionCard.test.go","text":{"en":"Go"}},"emphasis":"primary","action":{"kind":"execute"}}},
                           "site":{"type":"Button","props":{"label":{"key":"actionCard.test.site","text":{"en":"Docs"}},"emphasis":"secondary","action":{"kind":"navigate","url":"https://example.com/docs"}}}
                         }}}
                        """.trimIndent(),
                    ),
                ),
            )
        val spec = assertNotNull(stripActionCardSpec(read, RAFT_APP_ACTION_CARD_CAPABILITIES))
        val go = spec.spec.element("go") as ActionCardSpecButton
        val site = spec.spec.element("site") as ActionCardSpecButton
        for (status in listOf(RaftActionCardStatus.Expired, RaftActionCardStatus.Withdrawn, RaftActionCardStatus.Closed, RaftActionCardStatus.Ended)) {
            val frame = RaftActionCardFrameState(status = status)
            assertEquals(RaftCardButtonState.Hidden, actionCardFrameButtonState(frame, "go", go), status.name)
            assertEquals(RaftCardButtonState.Enabled, actionCardFrameButtonState(frame, "site", site), status.name)
        }
        assertEquals(RaftCardButtonState.Enabled, actionCardFrameButtonState(RaftActionCardFrameState(), "go", go))
    }

    @Test
    fun anEndedCardOpensNoDialogAndRunsNothing() {
        val read =
            assertNotNull(
                parseActionCardSpec(
                    Json.parseToJsonElement(
                        """
                        {"catalog":"action-card.v1","title":{"key":"actionCard.test.t","text":{"en":"Card"}},
                         "spec":{"root":"open","elements":{
                           "open":{"type":"Button","props":{"label":{"key":"actionCard.test.open","text":{"en":"Create Agent"}},"emphasis":"primary","action":{"kind":"open","intent":"create_agent"}}}
                         }}}
                        """.trimIndent(),
                    ),
                ),
            )
        val spec = assertNotNull(stripActionCardSpec(read, RAFT_APP_ACTION_CARD_CAPABILITIES))
        val agentCard = card(type = "agent:create")
        // In time: the typed path, which opens the create-agent dialog.
        assertEquals(ThreadActionCardSpecPress.Typed, threadActionCardSpecPress(agentCard.expiredAsOf(before), spec, "open"))
        assertFalse(threadActionCardCommitBlockedByEnd(agentCard, before))
        // Past `expiresAt` on this clock, expired by the server, or withdrawn: nothing opens.
        assertEquals(ThreadActionCardSpecPress.Ignore, threadActionCardSpecPress(agentCard.expiredAsOf(after), spec, "open"))
        for (type in listOf("agent:create", "channel:create", "channel:add_member")) {
            assertTrue(threadActionCardCommitBlockedByEnd(card(type = type), after), type)
            assertTrue(threadActionCardCommitBlockedByEnd(card(type = type, state = "expired", expiresAt = null), before), type)
        }
        assertTrue(threadActionCardCommitBlockedByEnd(card(type = "question:ask", state = "withdrawn"), before))
        assertTrue(threadActionCardCommitBlockedByEnd(card(type = "question:ask", state = "closed"), before))
        assertTrue(threadActionCardCommitBlockedByEnd(card(type = "question:ask", state = "someday"), before))
        assertTrue(threadActionCardCommitBlockedByEnd(card(state = "someday"), before))
        assertTrue(threadActionCardCommitBlockedByEnd(card(type = "seats:add", state = "expired"), before))
        assertFalse(threadActionCardCommitBlockedByEnd(card(state = "executed"), after))
    }

    @Test
    fun theServersExpiryRefusalReadsAsExpiry() = runTest {
        val refusal =
            HomeNetworkResponse(
                code = 409,
                bodyText = null,
                errorBodyText = """{"error":"This card expired; ask the agent to send a new one","errorCode":"ACTION_CARD_EXPIRED"}""",
            )
        val client = RaftIntegrationsActionsPushApiClient(RecordingActionCardsApi(executeResponse = refusal))
        val error = assertIs<DataResult.Error>(client.executeAction(MessageId("m-card"), "{}")).error
        assertTrue(error.isActionCardExpiredRefusal())
        assertTrue(ScreenError(code = "x", serverCode = "QUESTION_EXPIRED").isActionCardExpiredRefusal())
        // `seats:add` seat-request expiry refusal (`slock` seatRequestService.ts, #5610).
        assertTrue(ScreenError(code = "x", serverCode = "EXPIRED").isActionCardExpiredRefusal())
        assertFalse(ScreenError(code = "x", serverCode = "TOKEN_EXPIRED").isActionCardExpiredRefusal())
        assertFalse(ScreenError(code = "x", serverCode = "STATE_MISMATCH").isActionCardExpiredRefusal())
        assertFalse(ScreenError(code = "x").isActionCardExpiredRefusal())
        // The 409 carries no date: drawn expired with the card's own `expiresAt`, else undated.
        assertEquals("expired" to deadline, card().markedExpired().let { it.state to it.expiresAt })
        assertEquals("expired" to null, card(expiresAt = null).markedExpired().let { it.state to it.expiresAt })
        assertEquals("executed", card(state = "executed").markedExpired().state)
    }

    @Test
    fun theSpecStoreDrawsAnExpiryRefusalAsAnExpiredCard() = runTest {
        val applied = mutableListOf<Pair<String, ThreadActionCard>>()
        val store =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> DataResult.Error(ScreenError(code = "action_execute_contract_mismatch", retryable = false, serverCode = "ACTION_CARD_EXPIRED")) },
                applyCard = { messageId, card -> applied += messageId to card },
                nowMillis = { before },
            )
        val read =
            assertNotNull(
                parseActionCardSpec(
                    Json.parseToJsonElement(
                        """
                        {"catalog":"action-card.v1","title":{"key":"actionCard.test.t","text":{"en":"Card"}},
                         "spec":{"root":"go","elements":{
                           "go":{"type":"Button","props":{"label":{"key":"actionCard.test.go","text":{"en":"Go"}},"emphasis":"primary","action":{"kind":"execute"}}}
                         }}}
                        """.trimIndent(),
                    ),
                ),
            )
        val spec = assertNotNull(stripActionCardSpec(read, RAFT_APP_ACTION_CARD_CAPABILITIES))
        store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "go", card(type = "integration:register_app"), spec))
        runCurrent()
        assertEquals("expired", applied.single().second.state)
        assertNull(store.state.value.errors["m-1"])
        // A card the clock already expired runs nothing.
        val late =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> error("an expired card must not run") },
                applyCard = { _, _ -> },
                nowMillis = { after },
            )
        late.dispatch(ThreadActionCardSpecIntent.Press("m-1", "go", card(type = "integration:register_app"), spec))
        runCurrent()
        assertTrue(late.state.value.busy.isEmpty())
    }
}
