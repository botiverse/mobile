package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for #wg-mobile-sync:#63 (botiverse/slock #12882 / #14942 / #15305):
 * `othersAgentsApi.ts` + `useAgentOwnerNotify.ts` + `deleteRefusalReason` + agent ownership parsing.
 */
class ComputerOthersAgentsApiTest {
    private data class RecordedCall(
        val method: String,
        val path: String,
        val serverId: String?,
        val body: String?,
    )

    private class FakeNetworkClient(
        private val handler: (RecordedCall) -> HomeNetworkResponse,
    ) : RaftNetworkClient {
        val calls = mutableListOf<RecordedCall>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            val call = RecordedCall(method = method, path = path, serverId = serverIdOverride?.value, body = bodyText)
            calls += call
            return handler(call)
        }
    }

    @Test
    fun isOwnComputerAgentMatchesCreatorOrPersonalAssistantOwnerOnly() {
        assertTrue(isOwnComputerAgent(creatorUserId = "u-1", assistantOwnerUserId = null, currentUserId = "u-1"))
        assertTrue(isOwnComputerAgent(creatorUserId = null, assistantOwnerUserId = "u-1", currentUserId = "u-1"))
        assertFalse(isOwnComputerAgent(creatorUserId = "u-2", assistantOwnerUserId = null, currentUserId = "u-1"))
        assertFalse(isOwnComputerAgent(creatorUserId = null, assistantOwnerUserId = "u-2", currentUserId = "u-1"))
        assertFalse(isOwnComputerAgent(creatorUserId = "u-1", assistantOwnerUserId = "u-1", currentUserId = null))
        assertFalse(isOwnComputerAgent(creatorUserId = "u-1", assistantOwnerUserId = "u-1", currentUserId = "  "))
    }

    @Test
    fun deleteRefusalReasonMapsHttpStatusAndMachineDeleteConflictCodes() {
        assertEquals(ComputerDeleteRefusalReason.Forbidden, computerDeleteRefusalReason(403, "insufficient_role"))
        assertEquals(ComputerDeleteRefusalReason.Agents, computerDeleteRefusalReason(409, "MACHINE_HAS_ASSIGNED_AGENTS"))
        assertEquals(ComputerDeleteRefusalReason.Migration, computerDeleteRefusalReason(409, "MACHINE_HAS_ACTIVE_MIGRATION"))
        assertEquals(ComputerDeleteRefusalReason.Profile, computerDeleteRefusalReason(409, "MACHINE_HAS_ACTIVE_RUNTIME_PROFILE"))
        assertEquals(ComputerDeleteRefusalReason.Other, computerDeleteRefusalReason(500, "internal_error"))
        assertEquals(ComputerDeleteRefusalReason.Other, computerDeleteRefusalReason(409, null))
    }

    @Test
    fun parsesOthersAgentsConflictAndRejectsNonMatchingResponses() {
        val body =
            """
            {
              "code": "others_agents_on_computer",
              "error": "Other people's agents run on this computer.",
              "agents": [
                {
                  "id": "a-1",
                  "name": "Scout",
                  "owner": { "id": "u-alice", "name": "Alice" },
                  "ownerNotifiedAt": "2026-11-26T10:00:00.000Z",
                  "ownerNextNotifyAt": "2026-11-27T10:00:00.000Z",
                  "personalAssistant": false
                },
                {
                  "id": "a-2",
                  "name": "Bob's Assistant",
                  "owner": { "id": "u-bob", "name": "" },
                  "ownerNotifiedAt": "not-a-date",
                  "ownerNextNotifyAt": null,
                  "personalAssistant": true
                },
                {
                  "id": "a-3",
                  "owner": null,
                  "ownerNotifiedAt": null,
                  "ownerNextNotifyAt": null
                }
              ]
            }
            """.trimIndent()
        val parsed = parseOthersAgentsConflictResponse(409, body)
        assertEquals(3, parsed?.size)
        assertEquals(
            ComputerOthersAgent(
                id = "a-1",
                name = "Scout",
                owner = ComputerAgentOwner("u-alice", "Alice"),
                ownerNotifiedAt = "2026-11-26T10:00:00.000Z",
                ownerNextNotifyAt = "2026-11-27T10:00:00.000Z",
                personalAssistant = false,
            ),
            parsed?.get(0),
        )
        // Blank owner name falls back to owner id; invalid timestamps become null.
        assertEquals(ComputerAgentOwner("u-bob", "u-bob"), parsed?.get(1)?.owner)
        assertNull(parsed?.get(1)?.ownerNotifiedAt)
        assertTrue(parsed?.get(1)?.personalAssistant == true)
        // Missing name falls back to agent id; null owner stays null.
        assertEquals("a-3", parsed?.get(2)?.name)
        assertNull(parsed?.get(2)?.owner)

        // Wrong HTTP status, wrong code, or empty list returns null.
        assertNull(parseOthersAgentsConflictResponse(400, body))
        assertNull(parseOthersAgentsConflictResponse(409, """{"code":"other","agents":[{"id":"a-1"}]}"""))
        assertNull(parseOthersAgentsConflictResponse(409, """{"code":"others_agents_on_computer","agents":[]}"""))
    }

    @Test
    fun notifyStateReducerTracksNotifiedRateLimitedOwnerlessUndeliveredAndFailed() {
        val nowMs = 1_764_150_000_000L // 2025-11-26T09:46:40Z
        val futureIso = "2026-11-27T12:00:00.000Z"
        val pastIso = "2020-01-01T00:00:00.000Z"
        val aliceAgent =
            ComputerOthersAgent(
                id = "a-alice",
                name = "Scout",
                owner = ComputerAgentOwner("u-alice", "Alice"),
                ownerNotifiedAt = null,
                ownerNextNotifyAt = null,
                personalAssistant = false,
            )
        val bobAgent =
            ComputerOthersAgent(
                id = "a-bob",
                name = "Helper",
                owner = ComputerAgentOwner("u-bob", "Bob"),
                ownerNotifiedAt = pastIso,
                ownerNextNotifyAt = futureIso,
                personalAssistant = true,
            )

        var state = ComputerAgentOwnerNotifyState()
        assertTrue(state.canNotifyNow(aliceAgent, nowMs))
        assertFalse(state.canNotifyNow(bobAgent, nowMs))
        assertEquals(ComputerAgentNextReminder(limited = true, at = futureIso), state.nextReminder(bobAgent, nowMs))

        state = state.beginNotify(listOf("a-alice"))
        assertTrue("a-alice" in state.busy)
        assertFalse(state.canNotifyNow(aliceAgent, nowMs))

        // Failure sets failed and clears busy.
        state = state.applyNotifyFailure(listOf("a-alice"))
        assertFalse("a-alice" in state.busy)
        assertTrue("a-alice" in state.failed)

        // Retry clears failed, then applies notified + skipped reasons.
        state = state.beginNotify(listOf("a-alice", "a-rl", "a-none", "a-undeliv"))
        assertFalse("a-alice" in state.failed)
        state =
            state.applyNotifyResult(
                listOf("a-alice", "a-rl", "a-none", "a-undeliv"),
                ComputerNotifyAgentOwnersResult(
                    notified = listOf(ComputerNotifiedOwnerEntry("a-alice", "2026-11-26T10:00:00.000Z")),
                    skipped =
                        listOf(
                            ComputerSkippedOwnerEntry("a-rl", ComputerNotifySkipReason.RateLimited, null),
                            ComputerSkippedOwnerEntry("a-none", ComputerNotifySkipReason.NoOwner, null),
                            ComputerSkippedOwnerEntry("a-undeliv", ComputerNotifySkipReason.NotDelivered, null),
                        ),
                ),
            )
        assertEquals("2026-11-26T10:00:00.000Z", state.latestNotifiedAt(aliceAgent))
        val rlAgent = aliceAgent.copy(id = "a-rl")
        assertEquals(ComputerAgentNextReminder(limited = true, at = null), state.nextReminder(rlAgent, nowMs))
        assertFalse(state.canNotifyNow(rlAgent, nowMs))
        val noneAgent = aliceAgent.copy(id = "a-none")
        assertNull(state.ownerNow(noneAgent))
        assertFalse(state.canNotifyNow(noneAgent, nowMs))
        assertTrue("a-undeliv" in state.undelivered)
    }

    @Test
    fun repositoryCallsExactRoutesHandlesRevokeDeleteVariantsAndBatchesNotifyOver200() = runBlocking {
        // 1. revokeKeyAndDeleteComputer 200 { deleted: true }
        val client1 =
            FakeNetworkClient { call ->
                assertEquals("POST", call.method)
                assertEquals("servers/srv-1/machines/m-1/revoke-computer-key", call.path)
                assertEquals("""{"delete":true}""", call.body)
                HomeNetworkResponse(code = 200, bodyText = """{"ok":true,"deleted":true}""")
            }
        assertEquals(
            ComputerRevokeAndDeleteResult.Deleted,
            NetworkComputerOthersAgentsRepository(client1).revokeKeyAndDeleteComputer(ServerId("srv-1"), "m-1"),
        )

        // 2. revokeKeyAndDeleteComputer 409 no_live_computer_key -> Revoked
        val client2 =
            FakeNetworkClient {
                HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = """{"code":"no_live_computer_key","error":"No live key"}""")
            }
        assertEquals(
            ComputerRevokeAndDeleteResult.Revoked,
            NetworkComputerOthersAgentsRepository(client2).revokeKeyAndDeleteComputer(ServerId("srv-1"), "m-1"),
        )

        // 3. revokeKeyAndDeleteComputer 409 others_agents_on_computer with forceAgentIds payload
        val client3 =
            FakeNetworkClient { call ->
                assertEquals("""{"delete":true,"forceAgentIds":["a-1"]}""", call.body)
                HomeNetworkResponse(
                    code = 409,
                    bodyText = null,
                    errorBodyText = """{"code":"others_agents_on_computer","agents":[{"id":"a-1","name":"Scout","owner":{"id":"u-2","name":"Bob"},"ownerNotifiedAt":null,"ownerNextNotifyAt":null,"personalAssistant":false}]}""",
                )
            }
        val conflict =
            NetworkComputerOthersAgentsRepository(client3).revokeKeyAndDeleteComputer(
                ServerId("srv-1"),
                "m-1",
                forceAgentIds = listOf("a-1"),
            )
        assertIs<ComputerRevokeAndDeleteResult.OthersAgents>(conflict)
        assertEquals(listOf("a-1"), conflict.agents.map { it.id })

        // 4. notifyAgentOwners skips empty list and chunks >200 deduplicated ids
        val client4 =
            FakeNetworkClient { call ->
                assertEquals("POST", call.method)
                assertEquals("servers/srv-1/machines/m-1/notify-agent-owners", call.path)
                HomeNetworkResponse(
                    code = 200,
                    bodyText = """{"notified":[{"agentId":"a-0","notifiedAt":"2026-11-26T10:00:00.000Z"}],"skipped":[]}""",
                )
            }
        val repo4 = NetworkComputerOthersAgentsRepository(client4)
        assertIs<DataResult.Content<ComputerNotifyAgentOwnersResult>>(repo4.notifyAgentOwners(ServerId("srv-1"), "m-1", emptyList()))
        assertEquals(0, client4.calls.size)

        val ids = (0 until 205).map { "a-$it" } + listOf("a-0", "  ")
        val notified = repo4.notifyAgentOwners(ServerId("srv-1"), "m-1", ids)
        assertIs<DataResult.Content<ComputerNotifyAgentOwnersResult>>(notified)
        assertEquals(2, client4.calls.size)
        assertEquals(2, notified.data.notified.size)
    }

    @Test
    fun settingsComputerAgentsParserPreservesCreatorUserIdAndAssistantOwnerUserId() {
        val json =
            """
            [
              {
                "id": "a-1",
                "name": "mine",
                "machineId": "m-1",
                "creatorType": "user",
                "creatorId": "u-me"
              },
              {
                "id": "a-2",
                "name": "bot-made",
                "machineId": "m-1",
                "creatorType": "agent",
                "creatorId": "a-parent",
                "assistantOwnerUserId": "u-me"
              },
              {
                "id": "a-3",
                "name": "theirs",
                "machineId": "m-1",
                "creatorType": "user",
                "creatorId": "u-other"
              }
            ]
            """.trimIndent()
        val agents = requireNotNull(parseSharedSettingsComputerAgents(json, machineId = "m-1"))
        assertEquals(3, agents.size)
        assertEquals("u-me", agents[0].creatorUserId)
        assertNull(agents[0].assistantOwnerUserId)
        assertNull(agents[1].creatorUserId)
        assertEquals("u-me", agents[1].assistantOwnerUserId)
        assertEquals("u-other", agents[2].creatorUserId)
    }
}
