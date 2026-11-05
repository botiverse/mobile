package build.raft.app

import build.raft.app.SharedThreadStoreMembersAndActionCardsTest.RecordingActionCardsApi
import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ChannelRepository
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.CreateChannelResult
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
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
    fun aQuestionCardFromAnOlderServerFallsBackToItsActionExpiresAt() {
        val read =
            wire(
                """{"kind":"action-card","state":"prepared",
                   "action":{"type":"question:ask","expiresAt":"$deadline"}}""",
            )
        assertEquals(deadline, read.expiresAt)
        // The top-level value wins when both are there.
        val both =
            wire(
                """{"kind":"action-card","state":"prepared","expiresAt":"2026-12-01T00:00:00.000Z",
                   "action":{"type":"question:ask","expiresAt":"$deadline"}}""",
            )
        assertEquals("2026-12-01T00:00:00.000Z", both.expiresAt)
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
    fun theSpecStatusNamesExpiredAndWithdrawnAndKeepsUnknownStatesPrepared() {
        assertEquals(RaftActionCardStatus.Expired, card(state = "expired").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Expired, card().expiredAsOf(after).actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Prepared, card().expiredAsOf(before).actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Withdrawn, card(type = "question:ask", state = "withdrawn").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Expired, card(type = "question:ask", state = "expired").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Expired, card(type = "seats:add", state = "expired").actionCardSpecStatus())
        // `withdrawn` is a question card's state only; any other unknown state stays as before.
        assertEquals(RaftActionCardStatus.Prepared, card(state = "withdrawn").actionCardSpecStatus())
        assertEquals(RaftActionCardStatus.Prepared, card(state = "someday").actionCardSpecStatus())
        assertFalse(card(state = "someday").hasEnded)
        assertEquals(RaftActionCardStatus.Executed, card(state = "executed").actionCardSpecStatus())
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
        for (status in listOf(RaftActionCardStatus.Expired, RaftActionCardStatus.Withdrawn)) {
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
        assertTrue(threadActionCardCommitBlockedByEnd(card(type = "seats:add", state = "expired"), before))
        assertFalse(threadActionCardCommitBlockedByEnd(card(state = "executed"), after))
    }

    @Test
    fun anExpiredChannelCardCreatesNoChannel() = runTest {
        val created = mutableListOf<CreateChannelRequest>()
        val channels =
            object : ChannelRepository by FakeChannelRepository() {
                override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> {
                    created += request
                    error("an expired card must not create a channel")
                }
            }
        val actions = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = channels,
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actions),
            )
        // `expiresAt` already behind the real clock.
        val expired = card(expiresAt = "2020-01-01T00:00:00.000Z")
        val result = assertIs<DataResult.Error>(dataSource.commitThreadActionCard(basePayload(), "m-card", expired))
        assertEquals(ActionCardEndedErrorCode, result.error.code)
        assertEquals(emptyList(), created)
        assertEquals(emptyList(), actions.calls)
    }

    @Test
    fun theExpiredLineSaysWhoToAskAsTheWebDoes() {
        val date: (String) -> String? = { "10-30 09:30" }
        val expired = card(type = "agent:create", state = "expired")
        // An agent's card: ask the agent.
        assertEquals(ActionCardExpiredLine.FromAgent("10-30 09:30"), expired.actionCardExpiredLine("u-viewer", null, date))
        // A person sent it under the 30-day rule: ask them by name; to the sender, send a new one.
        val fromPerson = expired.copy(requesterUserId = "u-wendy")
        assertEquals(ActionCardExpiredLine.FromPerson("10-30 09:30", "Wendy"), fromPerson.actionCardExpiredLine("u-viewer", "Wendy", date))
        assertEquals(ActionCardExpiredLine.Own("10-30 09:30"), fromPerson.actionCardExpiredLine("u-wendy", "Wendy", date))
        // No longer a member here: as an agent's card.
        assertEquals(ActionCardExpiredLine.FromAgent("10-30 09:30"), fromPerson.actionCardExpiredLine("u-viewer", null, date))
        // A person's request with its own deadline keeps its own line, dated or not.
        for (type in listOf("seats:add", "plan:upgrade")) {
            val request = card(type = type, state = "expired", expiresAt = null).copy(requesterUserId = "u-wendy")
            assertEquals(ActionCardExpiredLine.PersonRequest, request.actionCardExpiredLine("u-viewer", "Wendy", date), type)
        }
        // No date, a question card, or not expired: no line.
        assertNull(card(state = "expired", expiresAt = null).actionCardExpiredLine("u-viewer", null, date))
        assertNull(expired.actionCardExpiredLine("u-viewer", null) { null })
        assertNull(card(type = "question:ask", state = "expired").actionCardExpiredLine("u-viewer", null, date))
        assertNull(card().actionCardExpiredLine("u-viewer", null, date))
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
        assertFalse(ScreenError(code = "x", serverCode = "EXPIRED").isActionCardExpiredRefusal())
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
