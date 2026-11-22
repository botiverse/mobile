package build.raft.app.data

import build.raft.app.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The Apps tab's Revoke (web AgentAppAccessTab `confirmRevoke`, botiverse/slock 31abbdefb #13293,
 * the cases of its agentAppGrantsPerApp.behavior test): one call per grant after the confirmation,
 * revoked and gone grants leave the agent's list at once, the list is read again, and what still
 * stands of the failed ones stays to confirm, never retried without Try again. The list lives in a
 * real [SharedAgentDetailStore], as on the profile.
 */
class AgentAppGrantsRevokeStoreTest {
    @Test
    fun revokeAccessAsksFirstThenRevokesEveryGrantOfTheAppOnePostEach() =
        runTest {
            val server = FakeIntegrationsServer(listOf(STAMP_SIGN_IN, STAMP_NOTIFY, REMINDER))
            val detail = detailStoreShowing(server.grants)
            val store = revokeStore(server, detail)

            store.dispatch(startStampRevokeAccess())
            runCurrent()
            assertEquals(emptyList(), server.posts, "nothing before the confirmation")
            assertEquals(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id), store.state.value.revoking?.grantIds)

            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            advanceUntilIdle()

            assertEquals(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id), server.posts, "one call per grant, in the App's order")
            assertNull(store.state.value.revoking, "nothing failed: the dialog closes")
            assertFalse(store.state.value.inFlight)
            assertEquals(listOf(REMINDER.id), detail.shownGrantIds(), "the other App stays")
            assertEquals(1, server.loads, "the list is read again once")
        }

    @Test
    fun aPartialFailureKeepsTheGrantThatStandsWithWhyAndOnlyTryAgainRetriesIt() =
        runTest {
            val server = FakeIntegrationsServer(listOf(STAMP_SIGN_IN, STAMP_NOTIFY, REMINDER))
            server.answers[STAMP_SIGN_IN.id] = ArrayDeque(listOf(FakeAnswer.ServerError))
            val detail = detailStoreShowing(server.grants)
            val store = revokeStore(server, detail)

            store.dispatch(startStampRevokeAccess())
            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            advanceUntilIdle()

            val left = assertNotNull(store.state.value.revoking, "the dialog stays on what didn't revoke")
            assertEquals(listOf(STAMP_SIGN_IN.id), left.grantIds)
            assertEquals(
                AgentAppGrantRevokeFailure.Failed(reason = "Failed to revoke integration grant", traceId = TRACE_ID),
                left.failures?.get(STAMP_SIGN_IN.id)
            )
            assertFalse(left.onlyRefused, "a server failure is worth trying again")
            assertEquals(listOf(STAMP_SIGN_IN.id, REMINDER.id), detail.shownGrantIds(), "the revoked grant left the list")
            assertEquals(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id), server.posts, "no automatic retry")

            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            advanceUntilIdle()

            assertEquals(listOf(STAMP_SIGN_IN.id), server.posts.drop(2), "Try again revokes only what still stands")
            assertNull(store.state.value.revoking)
            assertEquals(listOf(REMINDER.id), detail.shownGrantIds())
        }

    @Test
    fun aWriteWithNoAnswerIsNeverRetriedAndTheReReadTellsWhetherItLanded() =
        runTest {
            listOf(true, false).forEach { landed ->
                val server = FakeIntegrationsServer(listOf(STAMP_SIGN_IN, STAMP_NOTIFY, REMINDER))
                server.answers[STAMP_NOTIFY.id] = ArrayDeque(listOf(FakeAnswer.NoAnswer))
                if (landed) server.landedDespiteNoAnswer += STAMP_NOTIFY.id
                val detail = detailStoreShowing(server.grants)
                val store = revokeStore(server, detail)

                store.dispatch(startStampRevokeAccess())
                store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
                advanceUntilIdle()

                assertEquals(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id), server.posts, "each grant asked once (landed=$landed)")
                if (landed) {
                    assertNull(store.state.value.revoking, "the re-read no longer lists it: it simply disappears")
                    assertEquals(listOf(REMINDER.id), detail.shownGrantIds())
                } else {
                    val left = assertNotNull(store.state.value.revoking)
                    assertEquals(mapOf(STAMP_NOTIFY.id to AgentAppGrantRevokeFailure.NoAnswer), left.failures)
                    assertEquals(listOf(STAMP_NOTIFY.id, REMINDER.id), detail.shownGrantIds())
                }
            }
        }

    @Test
    fun aRefusalShowsTheServersReasonIsNeverAskedAgainAndAloneLeavesOnlyClose() =
        runTest {
            val server = FakeIntegrationsServer(listOf(STAMP_SIGN_IN, STAMP_NOTIFY, REMINDER))
            server.answers[STAMP_SIGN_IN.id] = ArrayDeque(listOf(FakeAnswer.Forbidden))
            val detail = detailStoreShowing(server.grants)
            val store = revokeStore(server, detail)

            store.dispatch(startStampRevokeAccess())
            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            advanceUntilIdle()

            val left = assertNotNull(store.state.value.revoking)
            assertEquals(
                AgentAppGrantRevokeFailure.Refused(reason = "Only server owners and admins can manage integrations", traceId = TRACE_ID),
                left.failures?.get(STAMP_SIGN_IN.id)
            )
            assertTrue(left.onlyRefused, "every grant left was refused")

            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            advanceUntilIdle()

            assertNull(store.state.value.revoking, "Close closes")
            assertEquals(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id), server.posts, "Close revokes nothing more")
        }

    @Test
    fun tryAgainSkipsARefusedGrantAndRetriesTheRest() =
        runTest {
            val server = FakeIntegrationsServer(listOf(STAMP_SIGN_IN, STAMP_NOTIFY, STAMP_EVENTS, REMINDER))
            server.answers[STAMP_SIGN_IN.id] = ArrayDeque(listOf(FakeAnswer.Forbidden))
            server.answers[STAMP_NOTIFY.id] = ArrayDeque(listOf(FakeAnswer.ServerError))
            server.answers[STAMP_EVENTS.id] = ArrayDeque(listOf(FakeAnswer.NoAnswer))
            val detail = detailStoreShowing(server.grants)
            val store = revokeStore(server, detail)

            store.dispatch(startStampRevokeAccess(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id, STAMP_EVENTS.id)))
            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            advanceUntilIdle()

            val first = assertNotNull(store.state.value.revoking)
            assertEquals(
                mapOf(
                    STAMP_SIGN_IN.id to AgentAppGrantRevokeFailure.Refused("Only server owners and admins can manage integrations", TRACE_ID),
                    STAMP_NOTIFY.id to AgentAppGrantRevokeFailure.Failed("Failed to revoke integration grant", TRACE_ID),
                    STAMP_EVENTS.id to AgentAppGrantRevokeFailure.NoAnswer,
                ),
                first.failures
            )
            assertFalse(first.onlyRefused)

            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            advanceUntilIdle()

            assertEquals(listOf(STAMP_NOTIFY.id, STAMP_EVENTS.id), server.posts.drop(3), "the refused grant is not asked again")
            val second = assertNotNull(store.state.value.revoking)
            assertEquals(listOf(STAMP_SIGN_IN.id), second.grantIds)
            assertTrue(second.onlyRefused)
            assertEquals(listOf(STAMP_SIGN_IN.id, REMINDER.id), detail.shownGrantIds())
        }

    @Test
    fun aGrantTheServerNoLongerHasIsGoneEvenWhenTheReReadFails() =
        runTest {
            val server = FakeIntegrationsServer(listOf(STAMP_SIGN_IN, STAMP_NOTIFY, REMINDER))
            server.answers[STAMP_SIGN_IN.id] = ArrayDeque(listOf(FakeAnswer.NotFound))
            server.failingLoads = 1
            val detail = detailStoreShowing(server.grants)
            val store = revokeStore(server, detail)

            store.dispatch(startStampRevokeAccess())
            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            advanceUntilIdle()

            assertNull(store.state.value.revoking, "nothing to try again: Try again could only 404")
            assertEquals(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id), server.posts, "nothing retried")
            val appLogins = assertNotNull(detail.state.value.detail?.appLogins)
            assertEquals(listOf(REMINDER.id), appLogins.items.map { it.id }, "both grants left the list without the re-read")
            assertEquals(AgentAppLoginLoadState.Content, appLogins.loadState, "the list stays shown")
            assertEquals("Failed to load agent integrations", appLogins.refreshError?.serverMessage, "the failed re-read is reported")
            assertEquals(TRACE_ID, appLogins.refreshError?.traceId)
        }

    @Test
    fun aSecondConfirmOrACloseWhileATryIsOutDoesNothing() =
        runTest {
            val server = FakeIntegrationsServer(listOf(STAMP_SIGN_IN, STAMP_NOTIFY, REMINDER))
            val gate = CompletableDeferred<Unit>()
            server.holdFirstPost = gate
            val store = revokeStore(server, detailStoreShowing(server.grants))

            store.dispatch(startStampRevokeAccess())
            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            runCurrent()
            assertTrue(store.state.value.inFlight)

            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            store.dispatch(AgentAppGrantsRevokeIntent.Close)
            store.dispatch(AgentAppGrantsRevokeIntent.Start(AGENT, "reminder", "Reminder App", whole = true, grantIds = listOf(REMINDER.id)))
            runCurrent()
            assertEquals(listOf(STAMP_SIGN_IN.id), server.posts, "one try at a time")
            assertEquals("stamp", store.state.value.revoking?.clientId, "the open confirmation stays")

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id), server.posts)
            assertNull(store.state.value.revoking)
            assertFalse(store.state.value.inFlight)
        }

    @Test
    fun aRevokeThatFinishesAfterThePageMovedToAnotherAgentWritesNothingThere() =
        runTest {
            val server = FakeIntegrationsServer(listOf(STAMP_SIGN_IN, STAMP_NOTIFY, REMINDER))
            val gate = CompletableDeferred<Unit>()
            server.holdFirstPost = gate
            val detail = detailStoreShowing(server.grants)
            val store = revokeStore(server, detail)

            store.dispatch(startStampRevokeAccess())
            store.dispatch(AgentAppGrantsRevokeIntent.Confirm)
            runCurrent()
            val otherAgent = MemberId("agent-2")
            val othersGrant = STAMP_SIGN_IN.copy(id = "g-other-login")
            detail.dispatch(SharedAgentDetailIntent.SelectMember(otherAgent))
            detail.dispatch(SharedAgentDetailIntent.SetDetail(otherAgent, agentDetail(otherAgent, listOf(othersGrant))))

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(otherAgent, detail.state.value.detail?.agentId)
            assertEquals(listOf(othersGrant.id), detail.shownGrantIds(), "another agent's list is untouched")
        }

    private fun TestScope.revokeStore(server: FakeIntegrationsServer, detail: SharedAgentDetailStore) =
        AgentAppGrantsRevokeStore(
            revokeGrant = server::revoke,
            loadAppLogins = server::load,
            applyToDetail = detail::dispatch,
            scope = backgroundScope,
        )

    private fun startStampRevokeAccess(grantIds: List<String> = listOf(STAMP_SIGN_IN.id, STAMP_NOTIFY.id)) =
        AgentAppGrantsRevokeIntent.Start(agentId = AGENT, clientId = "stamp", appName = "Stamp", whole = true, grantIds = grantIds)

    private fun detailStoreShowing(items: List<AgentAppLoginItem>): SharedAgentDetailStore =
        SharedAgentDetailStore().apply {
            dispatch(SharedAgentDetailIntent.SelectMember(AGENT))
            dispatch(SharedAgentDetailIntent.SetDetail(AGENT, agentDetail(AGENT, items)))
        }

    private fun agentDetail(agentId: MemberId, items: List<AgentAppLoginItem>) =
        AgentDetailSnapshot(
            agentId = agentId,
            directMessages = emptyList(),
            reminders = emptyList(),
            activity = emptyList(),
            appLogins = AgentAppLoginSnapshot(agentId = agentId, items = items)
        )

    private fun SharedAgentDetailStore.shownGrantIds(): List<String>? = state.value.detail?.appLogins?.items?.map { it.id }

    private enum class FakeAnswer { Ok, ServerError, Forbidden, NotFound, NoAnswer }

    /**
     * What the server lists for the agent and how its next revokes answer (web test `stubApi`): a
     * revoke that lands removes the grant; a write with no answer may still have landed.
     */
    private class FakeIntegrationsServer(var grants: List<AgentAppLoginItem>) {
        val answers = mutableMapOf<String, ArrayDeque<FakeAnswer>>()
        val landedDespiteNoAnswer = mutableSetOf<String>()
        var failingLoads = 0
        var holdFirstPost: CompletableDeferred<Unit>? = null
        val posts = mutableListOf<String>()
        var loads = 0

        suspend fun revoke(grantId: String): AgentAppGrantRevokeOutcome {
            posts += grantId
            if (posts.size == 1) holdFirstPost?.await()
            return when (answers[grantId]?.removeFirstOrNull() ?: FakeAnswer.Ok) {
                FakeAnswer.Ok -> {
                    drop(grantId)
                    AgentAppGrantRevokeOutcome.Revoked
                }
                FakeAnswer.NotFound -> {
                    // The server no longer has it (gone some other way).
                    drop(grantId)
                    AgentAppGrantRevokeOutcome.Gone
                }
                FakeAnswer.ServerError ->
                    AgentAppGrantRevokeOutcome.NotRevoked(AgentAppGrantRevokeFailure.Failed("Failed to revoke integration grant", TRACE_ID))
                FakeAnswer.Forbidden ->
                    AgentAppGrantRevokeOutcome.NotRevoked(
                        AgentAppGrantRevokeFailure.Refused("Only server owners and admins can manage integrations", TRACE_ID)
                    )
                FakeAnswer.NoAnswer -> {
                    if (grantId in landedDespiteNoAnswer) drop(grantId)
                    AgentAppGrantRevokeOutcome.NotRevoked(AgentAppGrantRevokeFailure.NoAnswer)
                }
            }
        }

        /** `GET /integrations/agents/:agentId`; a failing read answers `sendJsonServerError`'s 500. */
        suspend fun load(agentId: MemberId): DataResult<AgentAppLoginSnapshot> {
            loads += 1
            if (failingLoads > 0) {
                failingLoads -= 1
                return DataResult.Error(
                    ScreenError(code = "server_unavailable", serverMessage = "Failed to load agent integrations", traceId = TRACE_ID)
                )
            }
            return DataResult.Content(
                AgentAppLoginSnapshot(
                    agentId = agentId,
                    items = grants,
                    loadState = if (grants.isEmpty()) AgentAppLoginLoadState.Empty else AgentAppLoginLoadState.Content
                )
            )
        }

        private fun drop(grantId: String) {
            grants = grants.filterNot { it.id == grantId }
        }
    }
}

private val AGENT = MemberId("agent-1")
private const val TRACE_ID = "0af7651916cd43dd8448eb211c80319c"

/** Web test fixtures: Stamp holds the agent's sign-in and a notifications grant; Reminder App one grant. */
private fun grant(id: String, clientId: String, clientName: String, scopes: List<String>, grantSource: String?) =
    AgentAppLoginItem(
        id = id,
        type = AgentAppLoginType.Active,
        clientId = clientId,
        clientName = clientName,
        scopes = scopes,
        createdAt = "2026-10-05T01:00:00.000Z",
        grantSource = grantSource
    )

private val STAMP_SIGN_IN = grant("g-stamp-login", "stamp", "Stamp", listOf("identity", "openid", "profile"), "agent_login")
private val STAMP_NOTIFY = grant("g-stamp-notify", "stamp", "Stamp", listOf("agent:notification:write"), "app_request")
private val STAMP_EVENTS = grant("g-stamp-extra", "stamp", "Stamp", listOf("agent:event:write"), "person")
private val REMINDER = grant("g-reminder", "reminder", "Reminder App", listOf("agent:notification:write"), "person")
