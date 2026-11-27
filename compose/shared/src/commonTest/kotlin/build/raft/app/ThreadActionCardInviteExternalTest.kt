package build.raft.app

import build.raft.app.actionCardIconName
import build.raft.app.data.ActionCardCloseOutcome
import build.raft.app.model.ChannelCapabilities
import build.raft.app.data.ChannelInviteExternalActionCardType
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAddressesOutcome
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.RaftIntegrationsActionsPushNetworkApi
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardClosedBy
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.buildMarkExternalInvitesActionExecutedBody
import build.raft.app.data.isGenericInlineCommitAction
import build.raft.app.data.isInviteExternalAction
import build.raft.app.data.isRegressingActionCardState
import build.raft.app.data.isTerminalState
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import build.raft.app.data.withExecutedExternalInvites
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.thread.ACTION_CARD_INVITE_EXTERNAL_OPEN_ELEMENT_ID
import build.raft.app.thread.ThreadActionCardCommands
import build.raft.app.thread.ThreadActionCardSpecEffect
import build.raft.app.thread.ThreadActionCardSpecError
import build.raft.app.thread.ThreadActionCardSpecIntent
import build.raft.app.thread.ThreadActionCardSpecStore
import build.raft.app.thread.actionCardIconName
import build.raft.app.thread.dispatch
import build.raft.app.thread.evaluateInviteExternalCardAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Native `channel:invite_external` action card (`external_invite_card_v0`, botiverse/slock #16530,
 * #16543, #16662, #16671): wire & metadata round-trip, API endpoints (`external-invite-addresses`,
 * `close`, `mark-executed`), `ThreadActionCardCommands`, `ThreadActionCardSpecStore`, access gate
 * (`federateChannels`), prefilled `ExternalInvitesStore`, and `sayWhenEmpty` connect-only store.
 */
class ThreadActionCardInviteExternalTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun wire(body: String): ThreadActionCard =
        assertNotNull(json.decodeFromString(WireActionCardMetadata.serializer(), body).toThreadActionCard())

    private fun inviteCard(
        state: String = "prepared",
        channel: String = "ch-1",
        addresses: Int? = 2,
        confirmationVersion: Int? = 3,
        sourceServerId: String? = "srv-1",
        closedBy: ThreadActionCardClosedBy? = null,
        closedAt: String? = null,
        resultKind: String? = null,
        resultAddresses: Int? = null,
        executedByUserName: String? = null,
    ) = ThreadActionCard(
        type = ChannelInviteExternalActionCardType,
        state = state,
        channel = channel,
        externalInviteAddresses = addresses,
        confirmationVersion = confirmationVersion,
        sourceServerId = sourceServerId,
        closedBy = closedBy,
        closedAt = closedAt,
        resultKind = resultKind,
        resultAddresses = resultAddresses,
        executedByUserName = executedByUserName,
    )

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
    fun wireParsingAndMetadataRoundTripPreserveZeroAndPositiveAddressesAndDeclinedState() {
        val prepared =
            wire(
                """{"kind":"action-card","state":"prepared","sourceServerId":"srv-1","confirmationVersion":4,
                   "action":{"type":"channel:invite_external","channel":"ch-launch","addresses":2,"draftHint":"Partner kickoff"}}""",
            )
        assertTrue(prepared.isInviteExternalAction())
        assertEquals("ch-launch", prepared.channel)
        assertEquals(2, prepared.externalInviteAddresses)
        assertEquals("Partner kickoff", prepared.draftHint)
        assertFalse(prepared.isGenericInlineCommitAction())
        assertEquals(RaftLucideIconName.UserPlus, prepared.actionCardIconName())
        assertEquals(prepared, actionCardFromMessageMetadata(prepared.toMessageMetadata()))

        val connectOnly =
            wire(
                """{"kind":"action-card","state":"executed","executedByUserName":"Ada",
                   "action":{"type":"channel:invite_external","channel":"ch-launch","addresses":0},
                   "result":{"kind":"external-invites","channelId":"ch-launch","addresses":0}}""",
            )
        assertEquals(0, connectOnly.externalInviteAddresses)
        assertEquals("external-invites", connectOnly.resultKind)
        assertEquals("ch-launch", connectOnly.resultId)
        assertEquals(0, connectOnly.resultAddresses)
        assertEquals(connectOnly, actionCardFromMessageMetadata(connectOnly.toMessageMetadata()))

        val declined =
            wire(
                """{"kind":"action-card","state":"expired","closedAt":"2026-11-23T10:00:00.000Z",
                   "closedBy":{"userId":"u-ada","handle":"ada"},
                   "action":{"type":"channel:invite_external","channel":"ch-launch","addresses":2}}""",
            )
        assertTrue(declined.isClosed)
        assertFalse(declined.isExpired)
        assertTrue(declined.hasEnded)
        assertTrue(declined.isTerminalState)
        assertTrue(isRegressingActionCardState(held = declined.toMessageMetadata(), incoming = prepared.toMessageMetadata()))
        assertEquals(declined, actionCardFromMessageMetadata(declined.toMessageMetadata()))

        val expiredWithoutCloser =
            wire(
                """{"kind":"action-card","state":"expired",
                   "action":{"type":"channel:invite_external","channel":"ch-launch","addresses":2}}""",
            )
        assertFalse(expiredWithoutCloser.isClosed)
        assertTrue(expiredWithoutCloser.isExpired)
    }

    @Test
    fun markExecutedBodySerializesExactExternalInvitesContract() {
        val withVersion = json.parseToJsonElement(buildMarkExternalInvitesActionExecutedBody("ch-1", 2, expectedConfirmationVersion = 7)).jsonObject
        assertEquals(7, withVersion["expectedConfirmationVersion"]?.jsonPrimitive?.int)
        val result = assertNotNull(withVersion["result"]?.jsonObject)
        assertEquals("external-invites", result["kind"]?.jsonPrimitive?.content)
        assertEquals("ch-1", result["channelId"]?.jsonPrimitive?.content)
        assertEquals(2, result["addresses"]?.jsonPrimitive?.int)

        val connectOnly = json.parseToJsonElement(buildMarkExternalInvitesActionExecutedBody("ch-1", 0)).jsonObject
        assertFalse("expectedConfirmationVersion" in connectOnly)
        assertEquals(0, connectOnly["result"]?.jsonObject?.get("addresses")?.jsonPrimitive?.int)
    }

    @Test
    fun apiClientReadsExternalInviteAddressesAndHandlesNotFoundAuthAndServerErrors() = runTest {
        val okNet = RecordingNetworkClient(HomeNetworkResponse(200, """{"emails":[" alice@example.com ","bob@example.com",""]}"""))
        val okClient = RaftIntegrationsActionsPushApiClient(RaftIntegrationsActionsPushNetworkApi(okNet))
        val loaded = assertIs<ExternalInviteAddressesOutcome.Loaded>(
            assertIs<DataResult.Content<ExternalInviteAddressesOutcome>>(okClient.externalInviteCardAddresses(MessageId("m-1"))).data,
        )
        assertEquals(listOf("alice@example.com", "bob@example.com"), loaded.emails)
        assertEquals(listOf(Triple<String, String, String?>("GET", "actions/m-1/external-invite-addresses", null)), okNet.requests)

        val emptyNet = RecordingNetworkClient(HomeNetworkResponse(200, """{"emails":[]}"""))
        val emptyLoaded = assertIs<ExternalInviteAddressesOutcome.Loaded>(
            assertIs<DataResult.Content<ExternalInviteAddressesOutcome>>(
                RaftIntegrationsActionsPushApiClient(RaftIntegrationsActionsPushNetworkApi(emptyNet)).externalInviteCardAddresses(MessageId("m-2")),
            ).data,
        )
        assertEquals(emptyList(), emptyLoaded.emails)

        val missingNet = RecordingNetworkClient(HomeNetworkResponse(404, null, """{"code":"NOT_FOUND","error":"Card message not found"}"""))
        val unavailable = assertIs<DataResult.Content<ExternalInviteAddressesOutcome>>(
            RaftIntegrationsActionsPushApiClient(RaftIntegrationsActionsPushNetworkApi(missingNet)).externalInviteCardAddresses(MessageId("m-3")),
        ).data
        assertIs<ExternalInviteAddressesOutcome.Unavailable>(unavailable)

        val unauthNet = RecordingNetworkClient(HomeNetworkResponse(401, null, """{"error":"Unauthorized"}"""))
        val unauth = assertIs<DataResult.NoPermission>(
            RaftIntegrationsActionsPushApiClient(RaftIntegrationsActionsPushNetworkApi(unauthNet)).externalInviteCardAddresses(MessageId("m-4")),
        )
        assertEquals("auth_required", unauth.blocker.code)

        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val errNet =
            RecordingNetworkClient(
                HomeNetworkResponse(
                    code = 500,
                    bodyText = null,
                    errorBodyText = """{"error":"Internal failure"}""",
                    responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                ),
            )
        val err = assertIs<DataResult.Error>(
            RaftIntegrationsActionsPushApiClient(RaftIntegrationsActionsPushNetworkApi(errNet)).externalInviteCardAddresses(MessageId("m-5")),
        ).error
        assertTrue(err.retryable)
        assertEquals("Internal failure", err.serverMessage)
        assertEquals(traceId, err.traceId)
    }

    @Test
    fun commandsGuardTypeAndFrozenAndPostDeclineAndMarkExecuted() = runTest {
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
        assertEquals("action_card_unsupported", assertIs<DataResult.Error>(commands.readExternalInviteCardAddresses("m-1", ThreadActionCard(type = "channel:create"))).error.code)
        assertEquals("action_card_frozen", assertIs<DataResult.Error>(commands.readExternalInviteCardAddresses("m-1", inviteCard(state = "frozen"))).error.code)
        assertEquals("action_card_frozen", assertIs<DataResult.Error>(commands.declineExternalInviteActionCard("m-1", inviteCard(state = "frozen"))).error.code)
        assertTrue(network.requests.isEmpty())

        val closed = assertIs<ActionCardCloseOutcome.Closed>(
            assertIs<DataResult.Content<ActionCardCloseOutcome>>(commands.declineExternalInviteActionCard("m-1", inviteCard())).data,
        )
        assertTrue(assertNotNull(closed.card).isClosed)
        assertEquals("POST", network.requests.last().first)
        assertEquals("actions/m-1/close", network.requests.last().second)

        val marked = assertIs<DataResult.Content<ThreadActionCard>>(
            commands.markExternalInvitesActionCardExecuted("m-1", inviteCard(addresses = 0), channelId = "ch-1", addresses = 0),
        ).data
        assertEquals("executed", marked.state)
        assertEquals("external-invites", marked.resultKind)
        assertEquals("ch-1", marked.resultId)
        assertEquals(0, marked.resultAddresses)
        assertEquals("actions/m-1/mark-executed", network.requests.last().second)
    }

    @Test
    fun specStoreOpensFetchesAddressesOnClickHandles404DeclinesMarksExecutedAndReconfirms() = runTest {
        val fetchGate = CompletableDeferred<DataResult<ExternalInviteAddressesOutcome>>()
        val fetchCalls = mutableListOf<String>()
        val declineCalls = mutableListOf<String>()
        val markCalls = mutableListOf<Triple<String, String, Int>>()
        val applied = mutableListOf<Pair<String, ThreadActionCard>>()
        var approvals = 0
        var fetchResponse: suspend () -> DataResult<ExternalInviteAddressesOutcome> = { fetchGate.await() }
        var declineResponse: DataResult<ActionCardCloseOutcome> =
            DataResult.Content(
                ActionCardCloseOutcome.Closed(
                    inviteCard(state = "expired", closedBy = ThreadActionCardClosedBy("u-ada", "ada"), closedAt = "2026-11-23T10:00:00.000Z"),
                ),
            )
        val store =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> error("execute not used") },
                readExternalInviteAddresses = { messageId, _ ->
                    fetchCalls += messageId
                    fetchResponse()
                },
                declineExternalInvite = { messageId, _ ->
                    declineCalls += messageId
                    declineResponse
                },
                markExternalInvitesExecuted = { messageId, card, channelId, addresses ->
                    markCalls += Triple(messageId, channelId, addresses)
                    DataResult.Content(card.withExecutedExternalInvites(channelId, addresses, executedByUserName = "Ada"))
                },
                reconfirmCard = { _, card -> DataResult.Content(card.copy(state = "prepared", confirmationVersion = 4)) },
                applyCard = { messageId, card -> applied += messageId to card },
                onApproved = { approvals++ },
            )

        val card = inviteCard(addresses = 2)
        store.dispatch(ThreadActionCardSpecIntent.OpenInviteExternal("m-1", card))
        runCurrent()
        assertEquals(ACTION_CARD_INVITE_EXTERNAL_OPEN_ELEMENT_ID, store.state.value.slice("m-1").busy)
        // Duplicate click while busy is ignored.
        store.dispatch(ThreadActionCardSpecIntent.OpenInviteExternal("m-1", card))
        runCurrent()
        assertEquals(listOf("m-1"), fetchCalls)

        fetchGate.complete(DataResult.Content(ExternalInviteAddressesOutcome.Loaded(listOf("a@example.com", "b@example.com"))))
        runCurrent()
        assertNull(store.state.value.slice("m-1").busy)
        val open = assertNotNull(store.state.value.inviteExternal)
        assertEquals(listOf("a@example.com", "b@example.com"), open.emails)

        // Marking executed from our own dialog keeps dialog open if caller chooses (e.g. partial server refusal),
        // applies executed card, and increments approvals.
        store.dispatch(ThreadActionCardSpecIntent.MarkInviteExternalExecuted("m-1", card))
        runCurrent()
        assertEquals(listOf(Triple("m-1", "ch-1", 2)), markCalls)
        assertEquals(1, approvals)
        assertTrue(applied.last().second.isExecuted)
        store.dispatch(ThreadActionCardSpecIntent.CardUpdated("m-1", applied.last().second))
        assertNotNull(store.state.value.inviteExternal, "own mark-executed must not force-close dialog mid-partial-refusal")

        // Dismiss clears emails from state; re-opening re-fetches.
        store.dispatch(ThreadActionCardSpecIntent.DismissInviteExternal)
        assertNull(store.state.value.inviteExternal)

        // 404 Unavailable sets InviteExternalUnavailable, opens nothing, and leaves Decline working.
        fetchResponse = { DataResult.Content(ExternalInviteAddressesOutcome.Unavailable) }
        store.dispatch(ThreadActionCardSpecIntent.OpenInviteExternal("m-2", card))
        runCurrent()
        assertNull(store.state.value.inviteExternal)
        assertEquals(ThreadActionCardSpecError.InviteExternalUnavailable, store.state.value.slice("m-2").error)

        store.dispatch(ThreadActionCardSpecIntent.DeclineInviteExternal("m-2", card))
        runCurrent()
        assertEquals(listOf("m-2"), declineCalls)
        assertNull(store.state.value.slice("m-2").error)
        assertTrue(applied.last().second.isClosed)
        assertEquals(1, approvals, "declining is not an approval")

        // Concurrent completion by someone else while dialog is open closes dialog and emits AlreadyExecuted.
        fetchResponse = { DataResult.Content(ExternalInviteAddressesOutcome.Loaded(listOf("c@example.com"))) }
        store.dispatch(ThreadActionCardSpecIntent.OpenInviteExternal("m-3", card))
        runCurrent()
        assertNotNull(store.state.value.inviteExternal)
        val received = mutableListOf<ThreadActionCardSpecEffect>()
        val collectJob = launch { received += store.effects.first() }
        runCurrent()
        store.dispatch(ThreadActionCardSpecIntent.CardUpdated("m-3", card.withExecutedExternalInvites("ch-1", 2, executedByUserName = "Bea")))
        runCurrent()
        assertNull(store.state.value.inviteExternal)
        val eff = assertIs<ThreadActionCardSpecEffect.AlreadyExecuted>(received.single())
        assertEquals("Bea", eff.executedByUserName)
        collectJob.cancel()

        // Reconfirm brings reconfirm_required card back to prepared.
        store.dispatch(ThreadActionCardSpecIntent.ReconfirmInviteExternal("m-4", inviteCard(state = "reconfirm_required")))
        runCurrent()
        assertEquals("prepared", applied.last().second.state)
        assertEquals(4, applied.last().second.confirmationVersion)
    }

    @Test
    fun accessGateHonorsChannelCapabilitiesFederateChannelsAndRoleFallbacks() {
        val card = inviteCard()
        assertNull(evaluateInviteExternalCardAccess(ThreadActionCard(type = "channel:create"), "owner", null))
        assertEquals(true, evaluateInviteExternalCardAccess(card, "owner", null)?.canInvite)
        assertEquals(true, evaluateInviteExternalCardAccess(card, "admin", null)?.canInvite)
        assertEquals(false, evaluateInviteExternalCardAccess(card, "member", null)?.canInvite)
        assertEquals(false, evaluateInviteExternalCardAccess(card, "guest", null)?.canInvite)
        assertEquals(false, evaluateInviteExternalCardAccess(card, null, null)?.canInvite)

        fun channelRow(federate: Boolean?, archived: Boolean = false) =
            HomeChannelRow(
                threadId = ThreadId("ch-1"),
                channelId = ChannelId("ch-1"),
                name = "launch",
                archived = archived,
                channelCapabilities = federate?.let { ChannelCapabilities(federateChannels = it) },
            )

        // Explicit channelCapabilities.federateChannels wins on live channels (except guest).
        assertEquals(true, evaluateInviteExternalCardAccess(card, "member", channelRow(federate = true))?.canInvite)
        assertEquals(false, evaluateInviteExternalCardAccess(card, "owner", channelRow(federate = false))?.canInvite)
        assertEquals(false, evaluateInviteExternalCardAccess(card, "guest", channelRow(federate = true))?.canInvite)
        // Archived channel falls back to server role rather than misreporting an owner as non-admin.
        assertEquals(true, evaluateInviteExternalCardAccess(card, "owner", channelRow(federate = false, archived = true))?.canInvite)
    }

    @Test
    fun externalInvitesStorePrefillsInitialTextAndFiresOnSent() = runTest {
        val source = FakeExternalInvitesDataSource()
        var sentCount = 0
        val store = ExternalInvitesStore(backgroundScope, source, onSent = { sentCount++ })
        store.dispatch(ExternalInvitesIntent.OpenDialog(joint = false, initialText = "a@example.com, b@example.com"))
        assertEquals("a@example.com, b@example.com", store.state.value.dialog?.text)
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        store.dispatch(ExternalInvitesIntent.Send(makePrivate = false))
        runCurrent()
        assertEquals(1, sentCount)
        assertNull(store.state.value.dialog)
    }

    @Test
    fun jointChannelConnectionsStoreOpensEmptySheetOnlyWhenSayWhenEmptyIsTrue() = runTest {
        val connections =
            JointChannelConnections(
                channelId = ChannelId("ch-priv"),
                channelName = "launch",
                isJoint = false,
                archived = false,
                servers = emptyList(),
                hasCurrentServerPendingInvites = false,
                isPrivate = true,
                joined = true,
            )
        val noOtherServersAccount =
            MemberConnectAccount(
                currentServerId = "srv-host",
                currentServerName = "Host Co",
                currentServerSlug = "host-co",
                currentServerRole = "owner",
                servers = listOf(MemberConnectServer("srv-host", "Host Co", "host-co", null, "owner")),
            )
        val store = JointChannelConnectionsStore(FakeJointConnectionsDataSource(connections), backgroundScope)
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(noOtherServersAccount))
        store.dispatch(JointChannelConnectionsIntent.SeedChannel(connections))

        // Without sayWhenEmpty, opening with 0 eligible servers is ignored.
        store.dispatch(JointChannelConnectionsIntent.OpenFromExternalInvite(sayWhenEmpty = false))
        assertNull(store.state.value.memberConnectSheet)

        // With sayWhenEmpty (connect-only invite card), sheet opens with empty display rows.
        store.dispatch(JointChannelConnectionsIntent.OpenFromExternalInvite(sayWhenEmpty = true))
        val sheet = assertNotNull(store.state.value.memberConnectSheet)
        assertTrue(sheet.sayWhenEmpty)
        assertTrue(sheet.eligibleOnly)
        assertTrue(sheet.hostAdmin)
        assertTrue(store.state.value.memberConnectDisplayRows.isEmpty())
    }
}
