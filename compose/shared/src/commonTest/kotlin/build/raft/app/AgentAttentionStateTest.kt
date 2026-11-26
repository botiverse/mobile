package build.raft.app

import build.raft.app.data.AgentActivityDetailKindRuntimeUnavailable
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.PresenceNoopMemberRepository
import build.raft.app.data.ProcessMemberPresenceRegistry
import build.raft.app.data.ProcessPresenceTransportEpoch
import build.raft.app.data.ProcessServerMembersOwner
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.forDurableRoster
import build.raft.app.data.parseSharedSettingsAgentsAsMembers
import build.raft.app.data.toStoredChannelMembers
import build.raft.app.model.AgentAttention
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentReadiness
import build.raft.app.model.AgentReadinessFact
import build.raft.app.model.AgentReadinessResource
import build.raft.app.model.AgentSpawnFailure
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.resources.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity tests with `botiverse/slock` `packages/web/tests/agentAttentionState.test.ts`
 * (#17124, #17127, #17133): every status surface resolves blocked readiness (except
 * `computer_offline`) and non-retryable stored failed starts on offline non-stopped agents to
 * the error light with consistent short titles and start-failure Retry rules.
 */
class AgentAttentionStateTest {
    private val serverId = ServerId("srv-1")
    private val server = ServerSummary(id = serverId, name = "Test", slug = "test")
    private val owner = ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = serverId.value)
    private val transport = ProcessPresenceTransportEpoch("family-1", 1L)

    private fun storeWithAgents(body: String): Pair<SharedServerMembersStore, ProcessMemberPresenceRegistry> {
        val registry = ProcessMemberPresenceRegistry()
        val repo =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId,
                ): DataResult<MemberSnapshot> =
                    DataResult.Content(
                        MemberSnapshot(
                            server = server,
                            members = requireNotNull(parseSharedSettingsAgentsAsMembers(body)),
                        ),
                    )
            }
        val store = SharedServerMembersStore(memberRepository = repo, presenceRegistry = registry)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        return store to registry
    }

    @Test
    fun storedModelUnsupportedStartFailureResolvesErrorAttentionWithAndWithoutActivity() = runTest {
        val modelErrorText =
            "Antigravity CLI on this Computer no longer offers the model gemini-4-neon. Available: gemini-4-hydrogen."
        val body =
            """
            [
              {
                "id": "agent-muse",
                "serverId": "srv-1",
                "name": "muse",
                "displayName": "Muse",
                "status": "inactive",
                "runtime": "antigravity",
                "model": "gemini-4-neon",
                "lastRuntimeError": {
                  "message": "$modelErrorText",
                  "actionRequired": true,
                  "spawnFailureReason": "model_unsupported"
                }
              },
              {
                "id": "agent-scout",
                "serverId": "srv-1",
                "name": "scout",
                "displayName": "Scout",
                "status": "inactive",
                "runtime": "antigravity",
                "model": "gemini-4-neon",
                "lastRuntimeError": {
                  "message": "$modelErrorText",
                  "actionRequired": true,
                  "spawnFailureReason": "model_unsupported"
                }
              }
            ]
            """.trimIndent()
        val (store, registry) = storeWithAgents(body)
        store.refreshMembers(serverId, force = true, reason = "test")

        // Muse has the served offline + runtime_unavailable activity; Scout has no activity loaded yet.
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = MemberId("agent-muse"),
                presence = Presence.Offline,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline,
                statusLabel = modelErrorText,
                detailKind = AgentActivityDetailKindRuntimeUnavailable,
                serverSeq = null,
            ),
            registry.writeGeneration,
            transport,
        )

        for (agentId in listOf("agent-muse", "agent-scout")) {
            val status = assertNotNull(store.currentMemberStatus(serverId, agentId))
            assertEquals(AgentStatus.Error, status.status, agentId)
            assertEquals(AgentActivityDetailKindRuntimeUnavailable, status.detailKind, agentId)
            val attention = assertIs<AgentAttention.StartFailure>(status.attention, agentId)
            assertEquals("model_unsupported", attention.spawnFailure.reason)
            assertEquals("gemini-4-neon", attention.spawnFailure.model)
            assertTrue(attention.spawnFailure.autoRestartPaused)

            val indicator = status.toRaftAgentStatusIndicatorState()
            assertEquals(AgentStatus.Error, indicator.status, agentId)
            assertEquals(RaftColor.AccentOrange, indicator.color, agentId)
            assertEquals(AgentActivityDetailKindRuntimeUnavailable, indicator.detailKind, agentId)
            assertEquals(attention, indicator.attention, agentId)
            assertEquals(RaftColor.AccentOrange, indicator.withoutActivityDetail().color, agentId)
            assertEquals(attention, indicator.withoutActivityDetail().attention, agentId)
        }
    }

    @Test
    fun liveSpawnFailureCarrierWithSameReasonWinsOverStoredModel() = runTest {
        val body =
            """
            [
              {
                "id": "agent-muse",
                "serverId": "srv-1",
                "name": "muse",
                "status": "inactive",
                "runtime": "antigravity",
                "model": "gemini-4-neon",
                "lastRuntimeError": {
                  "message": "model gone",
                  "spawnFailureReason": "model_unsupported"
                }
              }
            ]
            """.trimIndent()
        val (store, registry) = storeWithAgents(body)
        store.refreshMembers(serverId, force = true, reason = "test")
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = MemberId("agent-muse"),
                presence = Presence.Offline,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline,
                statusLabel = "model gone",
                detailKind = AgentActivityDetailKindRuntimeUnavailable,
                spawnFailure = AgentSpawnFailure(reason = "model_unsupported", model = "gemini-4-neon-live", autoRestartPaused = true),
                serverSeq = null,
            ),
            registry.writeGeneration,
            transport,
        )

        val status = assertNotNull(store.currentMemberStatus(serverId, "agent-muse"))
        val attention = assertIs<AgentAttention.StartFailure>(status.attention)
        assertEquals("gemini-4-neon-live", attention.spawnFailure.model)
        assertEquals(AgentActivityDetailKindRuntimeUnavailable, status.detailKind)
    }

    @Test
    fun startFailureAttentionShortTitlesMapKnownAndFallbackReasons() {
        assertEquals(Res.string.agent_status_attention_model_unavailable, agentStartFailureAttentionShortRes("model_unsupported"))
        assertEquals(Res.string.agent_status_runtime_sign_in_short, agentStartFailureAttentionShortRes("runtime_login_required"))
        assertEquals(Res.string.agent_status_attention_needs_restart, agentStartFailureAttentionShortRes("session_needs_reset"))
        assertEquals(Res.string.agent_readiness_generic_title, agentStartFailureAttentionShortRes("env_var_not_allowed"))
        assertEquals(Res.string.agent_readiness_generic_title, agentStartFailureAttentionShortRes("runtime_config_invalid"))
        assertEquals(Res.string.agent_readiness_generic_title, agentStartFailureAttentionShortRes(null))
    }

    @Test
    fun stoppedAgentRetryableFailureWorkingAgentAndClearedErrorDoNotGetAttention() = runTest {
        val body =
            """
            [
              {
                "id": "agent-stopped",
                "serverId": "srv-1",
                "name": "stopped",
                "status": "stopped",
                "runtime": "antigravity",
                "model": "gemini-4-neon",
                "lastRuntimeError": { "message": "model gone", "spawnFailureReason": "model_unsupported" }
              },
              {
                "id": "agent-timeout",
                "serverId": "srv-1",
                "name": "timeout",
                "status": "inactive",
                "runtime": "antigravity",
                "lastRuntimeError": { "message": "timed out", "spawnFailureReason": "runtime_start_timeout" }
              },
              {
                "id": "agent-working",
                "serverId": "srv-1",
                "name": "working",
                "status": "active",
                "runtime": "antigravity",
                "lastRuntimeError": { "message": "model gone", "spawnFailureReason": "model_unsupported" }
              },
              {
                "id": "agent-cleared",
                "serverId": "srv-1",
                "name": "cleared",
                "status": "inactive",
                "runtime": "antigravity"
              }
            ]
            """.trimIndent()
        val (store, registry) = storeWithAgents(body)
        store.refreshMembers(serverId, force = true, reason = "test")
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = MemberId("agent-working"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working,
                statusLabel = "Working",
                serverSeq = null,
            ),
            registry.writeGeneration,
            transport,
        )
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = MemberId("agent-timeout"),
                presence = Presence.Offline,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline,
                detailKind = AgentActivityDetailKindRuntimeUnavailable,
                serverSeq = null,
            ),
            registry.writeGeneration,
            transport,
        )

        val stopped = assertNotNull(store.currentMemberStatus(serverId, "agent-stopped"))
        assertNull(stopped.attention)
        assertEquals(AgentStatus.Offline, stopped.toRaftAgentStatusIndicatorState().status)

        val timeout = assertNotNull(store.currentMemberStatus(serverId, "agent-timeout"))
        assertNull(timeout.attention)
        assertEquals(AgentStatus.Offline, timeout.status)

        val working = assertNotNull(store.currentMemberStatus(serverId, "agent-working"))
        assertNull(working.attention)
        assertEquals(AgentStatus.Working, working.status)

        val cleared = assertNotNull(store.currentMemberStatus(serverId, "agent-cleared"))
        assertNull(cleared.attention)
        assertNotNull(cleared)
    }

    @Test
    fun blockedReadinessResolvesAttentionOverOnlineActivityAndLikelyOnline() = runTest {
        val body =
            """
            [
              {
                "id": "agent-model-blocked",
                "serverId": "srv-1",
                "name": "Muse",
                "status": "active",
                "runtime": "antigravity",
                "readiness": {
                  "state": "blocked",
                  "primary": {
                    "resource": { "kind": "model", "key": "shared:antigravity", "model": "gemini-4-neon" },
                    "code": "model_unavailable",
                    "behaviour": "blocked",
                    "since": 1000,
                    "params": { "model": "gemini-4-neon" }
                  },
                  "scope": "agent",
                  "actor": "agent_owner"
                }
              },
              {
                "id": "agent-unknown-blocked",
                "serverId": "srv-1",
                "name": "Future",
                "status": "active",
                "runtime": "antigravity",
                "readiness": {
                  "state": "blocked",
                  "primary": {
                    "resource": { "kind": "model", "key": "shared:antigravity" },
                    "code": "future_unknown_code",
                    "behaviour": "blocked",
                    "since": 1000
                  },
                  "scope": "agent",
                  "actor": "agent_owner"
                }
              },
              {
                "id": "agent-trimmed-blocked",
                "serverId": "srv-1",
                "name": "Trimmed",
                "status": "active",
                "runtime": "antigravity",
                "readiness": {
                  "state": "blocked",
                  "primary": null,
                  "scope": null,
                  "actor": "agent_owner",
                  "actorName": "Agnes",
                  "action": "change_model"
                }
              }
            ]
            """.trimIndent()
        val (store, registry) = storeWithAgents(body)
        store.refreshMembers(serverId, force = true, reason = "test")

        // Before activity arrives, blocked readiness beats likelyOnline.
        for (agentId in listOf("agent-model-blocked", "agent-unknown-blocked", "agent-trimmed-blocked")) {
            val status = assertNotNull(store.currentMemberStatus(serverId, agentId))
            assertEquals(AgentStatus.Error, status.status, agentId)
            assertFalse(status.likelyOnline, agentId)
            assertIs<AgentAttention.Readiness>(status.attention, agentId)
            assertEquals(RaftColor.AccentOrange, status.toRaftAgentStatusIndicatorState().color, agentId)
        }

        // Even with a live Online presence update, blocked readiness stays Error orange.
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = MemberId("agent-model-blocked"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Online,
                statusLabel = "Online",
                serverSeq = null,
            ),
            registry.writeGeneration,
            transport,
        )
        val afterOnline = assertNotNull(store.currentMemberStatus(serverId, "agent-model-blocked"))
        assertEquals(AgentStatus.Error, afterOnline.status)
        assertIs<AgentAttention.Readiness>(afterOnline.attention)
        assertEquals(RaftColor.AccentOrange, afterOnline.toRaftAgentStatusIndicatorState().color)
    }

    @Test
    fun nonBlockedReadinessAndComputerOfflineDoNotTriggerAttention() = runTest {
        val body =
            """
            [
              {
                "id": "agent-advisory",
                "serverId": "srv-1",
                "name": "Advisory",
                "status": "active",
                "runtime": "antigravity",
                "readiness": {
                  "state": "advisory",
                  "primary": {
                    "resource": { "kind": "model", "key": "shared:antigravity", "model": "gemini-4-neon" },
                    "code": "model_unavailable",
                    "behaviour": "advisory",
                    "since": 1000
                  }
                }
              },
              {
                "id": "agent-waiting",
                "serverId": "srv-1",
                "name": "Waiting",
                "status": "active",
                "runtime": "antigravity",
                "readiness": {
                  "state": "waiting",
                  "primary": {
                    "resource": { "kind": "computer" },
                    "code": "computer_restarting",
                    "behaviour": "waiting",
                    "since": 1000
                  }
                }
              },
              {
                "id": "agent-computer-offline",
                "serverId": "srv-1",
                "name": "OfflineComp",
                "status": "inactive",
                "runtime": "antigravity",
                "model": "gemini-4-neon",
                "lastRuntimeError": { "message": "model gone", "spawnFailureReason": "model_unsupported" },
                "readiness": {
                  "state": "blocked",
                  "primary": {
                    "resource": { "kind": "computer" },
                    "code": "computer_offline",
                    "behaviour": "blocked",
                    "since": 1000
                  }
                }
              }
            ]
            """.trimIndent()
        val (store, registry) = storeWithAgents(body)
        store.refreshMembers(serverId, force = true, reason = "test")
        for (agentId in listOf("agent-advisory", "agent-waiting")) {
            registry.apply(
                owner,
                MemberPresenceUpdated(
                    memberId = MemberId(agentId),
                    presence = Presence.Active,
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Online,
                    serverSeq = null,
                ),
                registry.writeGeneration,
                transport,
            )
            val status = assertNotNull(store.currentMemberStatus(serverId, agentId))
            assertEquals(AgentStatus.Online, status.status, agentId)
            assertNull(status.attention, agentId)
        }
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = MemberId("agent-computer-offline"),
                presence = Presence.Offline,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline,
                serverSeq = null,
            ),
            registry.writeGeneration,
            transport,
        )
        val offlineComp = assertNotNull(store.currentMemberStatus(serverId, "agent-computer-offline"))
        assertEquals(AgentStatus.Offline, offlineComp.status)
        assertNull(offlineComp.attention)
        assertEquals(RaftColor.PresenceOffline, offlineComp.toRaftAgentStatusIndicatorState().color)
    }

    @Test
    fun agentNeedsManualRestartEnforcesStartFailureRulesOnErrorAttention() {
        assertFalse(
            agentNeedsManualRestart(
                status = AgentStatus.Error,
                detailKind = AgentActivityDetailKindRuntimeUnavailable,
                spawnFailure = AgentSpawnFailure(reason = "session_needs_reset", autoRestartPaused = true),
                runtimeErrorClass = null,
                external = false,
                blocked = null,
                likelyOnline = false,
            ),
            "session_needs_reset must not offer Retry even when attention paints the error light",
        )
        assertTrue(
            agentNeedsManualRestart(
                status = AgentStatus.Error,
                detailKind = AgentActivityDetailKindRuntimeUnavailable,
                spawnFailure = AgentSpawnFailure(reason = "model_unsupported", autoRestartPaused = true),
                runtimeErrorClass = null,
                external = false,
                blocked = null,
                likelyOnline = false,
            ),
        )
        assertFalse(
            agentNeedsManualRestart(
                status = AgentStatus.Error,
                detailKind = "runtime_error",
                spawnFailure = null,
                runtimeErrorClass = "RateLimitError",
                external = false,
                blocked = null,
                likelyOnline = false,
            ),
        )
        assertTrue(
            agentNeedsManualRestart(
                status = AgentStatus.Error,
                detailKind = "runtime_error",
                spawnFailure = null,
                runtimeErrorClass = null,
                external = false,
                blocked = null,
                likelyOnline = false,
            ),
        )
    }

    @Test
    fun spawnFailureFallbackOfflineComputerPrecedenceDeliveryHoldAndLoginClear() {
        val attentionIndicator =
            SharedServerMemberStatus(
                status = AgentStatus.Offline,
                presence = Presence.Offline,
                runtime = "antigravity",
                model = "gemini-4-neon",
                external = false,
                lastRuntimeErrorSpawnFailureReason = "model_unsupported",
                lastRuntimeErrorMessage = "model gone",
                lifecycleStatus = "inactive",
                deliveryHold = AgentDeliveryHold(reason = AgentDeliveryHoldReason.CredentialInvalid),
            ).toRaftAgentStatusIndicatorState()

        assertIs<AgentAttention.StartFailure>(attentionIndicator.attention)
        assertEquals(RaftColor.AccentOrange, attentionIndicator.color)
        assertNull(attentionIndicator.activeDeliveryHold(nowMs = 1_000L))

        val resolvedSpawnFailure =
            assertNotNull(
                membersAgentStatusSpawnFailure(
                    indicator = attentionIndicator,
                    summaryDetailKind = null,
                    summaryActivityDetail = null,
                    summarySpawnFailure = null,
                ),
            )
        assertEquals("model_unsupported", resolvedSpawnFailure.spawnFailure.reason)
        assertEquals("gemini-4-neon", resolvedSpawnFailure.spawnFailure.model)
        assertEquals("model gone", resolvedSpawnFailure.daemonDetail)

        // Computer offline still wins over a stored start-failure attention, but not over a real live error.
        assertTrue(
            membersAgentHeldByOfflineComputer(
                computerOffline = true,
                indicator = attentionIndicator,
                lifecycleStatus = "inactive",
                external = false,
            ),
        )
        val liveError =
            raftAgentStatusIndicatorState(
                status = AgentStatus.Error,
                presence = Presence.Offline,
                external = false,
            )
        assertFalse(
            membersAgentHeldByOfflineComputer(
                computerOffline = true,
                indicator = liveError,
                lifecycleStatus = "active",
                external = false,
            ),
        )

        // Live login clears synthesized runtime_login_required attention but preserves model_unsupported.
        val loginAttention =
            SharedServerMemberStatus(
                status = AgentStatus.Offline,
                presence = Presence.Offline,
                runtime = "kimi-sdk",
                external = false,
                lastRuntimeErrorSpawnFailureReason = "runtime_login_required",
                lifecycleStatus = "inactive",
            ).toRaftAgentStatusIndicatorState()
        assertIs<AgentAttention.StartFailure>(loginAttention.attention)
        val cleared = loginAttention.withoutClearedLoginRequiredAttention(clearedByLiveLogin = true)
        assertNull(cleared.attention)
        assertEquals(AgentStatus.Offline, cleared.status)
        assertEquals(RaftColor.PresenceOffline, cleared.color)
        assertEquals(attentionIndicator, attentionIndicator.withoutClearedLoginRequiredAttention(clearedByLiveLogin = true))
    }

    @Test
    fun durableCachesStripStoredStartFailureFieldsAndDotKindMatchesAttention() {
        val member =
            requireNotNull(
                parseSharedSettingsAgentsAsMembers(
                    """
                    [
                      {
                        "id": "agent-1",
                        "serverId": "srv-1",
                        "name": "Muse",
                        "status": "inactive",
                        "runtime": "antigravity",
                        "lastRuntimeError": {
                          "message": "model gone",
                          "spawnFailureReason": "model_unsupported"
                        }
                      }
                    ]
                    """.trimIndent(),
                ),
            ).single()
        assertEquals("model_unsupported", member.lastRuntimeErrorSpawnFailureReason)
        assertEquals("model gone", member.lastRuntimeErrorMessage)

        val durable = member.forDurableRoster()
        assertNull(durable.lastRuntimeErrorSpawnFailureReason)
        assertNull(durable.lastRuntimeErrorMessage)

        val storedSlice =
            SharedServerChannelMembersSlice(
                channelId = "ch-1",
                members = listOf(member),
            ).toStoredChannelMembers()
        assertNull(storedSlice.members.single().lastRuntimeErrorSpawnFailureReason)
        assertNull(storedSlice.members.single().lastRuntimeErrorMessage)

        val modelUnavailable =
            AgentReadiness(
                state = "blocked",
                primary = AgentReadinessFact(code = "model_unavailable", behaviour = "blocked"),
            )
        val computerOffline =
            AgentReadiness(
                state = "blocked",
                primary = AgentReadinessFact(code = "computer_offline", behaviour = "blocked"),
            )
        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind(statusWireValue = "online", external = false, readiness = modelUnavailable),
        )
        assertEquals(
            RaftAgentStatusDotKind.Gray,
            raftAgentStatusDotKind(
                statusWireValue = "offline",
                external = false,
                readiness = computerOffline,
                lastRuntimeErrorSpawnFailureReason = "model_unsupported",
                lifecycleStatus = "inactive",
            ),
        )
        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind(
                statusWireValue = "offline",
                external = false,
                lastRuntimeErrorSpawnFailureReason = "model_unsupported",
                lifecycleStatus = "inactive",
            ),
        )
        assertEquals(
            RaftAgentStatusDotKind.Gray,
            raftAgentStatusDotKind(
                statusWireValue = "offline",
                external = false,
                lastRuntimeErrorSpawnFailureReason = "model_unsupported",
                lifecycleStatus = "stopped",
            ),
        )
    }
}
