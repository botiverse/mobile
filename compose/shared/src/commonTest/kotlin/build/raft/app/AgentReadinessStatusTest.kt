package build.raft.app

import build.raft.app.data.AgentBlockedState
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.PresenceNoopMemberRepository
import build.raft.app.data.ProcessMemberPresenceRegistry
import build.raft.app.data.ProcessPresenceTransportEpoch
import build.raft.app.data.ProcessServerMembersOwner
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedSyncedAgentRow
import build.raft.app.data.forDurableRoster
import build.raft.app.data.likelyOnlineMemberStatusOrNull
import build.raft.app.data.parseSharedAgentRow
import build.raft.app.data.parseSharedSettingsAgentsAsMembers
import build.raft.app.data.toStoredChannelMembers
import build.raft.app.model.AgentReadiness
import build.raft.app.model.AgentReadinessFact
import build.raft.app.model.AgentReadinessLight
import build.raft.app.model.AgentReadinessResource
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.RaftRelativeTimeUnit
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.agentReadinessResetCountdown
import build.raft.app.model.attentionReadinessOrNull
import build.raft.app.model.holds
import build.raft.app.model.isModelUnavailableAdvisory
import build.raft.app.model.light
import build.raft.app.model.resolvedModel
import build.raft.app.model.resolvedRuntimeId
import build.raft.app.resources.*
import build.raft.app.thread.key
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentReadinessStatusTest {
    @Test
    fun parsesQuotaExhaustedAndRateLimitedReadinessFromGetAgents() {
        val body =
            """
            [
              {
                "id": "agent-1",
                "name": "Antigravity",
                "status": "active",
                "runtime": "antigravity",
                "activity": "online",
                "activityDetail": "Idle",
                "readiness": {
                  "state": "waiting",
                  "primary": {
                    "resource": { "kind": "runtime", "runtime": "antigravity" },
                    "code": "quota_exhausted",
                    "behaviour": "waiting",
                    "since": 1795245291000,
                    "until": 1795259909000
                  },
                  "scope": "computer",
                  "actor": "computer_owner",
                  "also": []
                }
              },
              {
                "id": "agent-2",
                "name": "TrimmedAgent",
                "status": "active",
                "runtime": "claude",
                "readiness": {
                  "state": "blocked",
                  "primary": null,
                  "scope": null,
                  "actor": "computer_owner",
                  "actorName": "Olive",
                  "action": "verify_account",
                  "also": []
                }
              },
              {
                "id": "agent-3",
                "name": "DesktopOldComputer",
                "status": "active",
                "runtime": "codex",
                "readiness": {
                  "state": "blocked",
                  "primary": {
                    "resource": { "kind": "computer" },
                    "code": "computer_too_old",
                    "behaviour": "blocked",
                    "since": 1795245000000,
                    "params": { "managedByDesktop": true }
                  },
                  "scope": "computer",
                  "actor": "computer_owner",
                  "also": []
                }
              },
              {
                "id": "agent-4",
                "name": "MalformedReadinessDoesNotDropAgent",
                "status": "active",
                "runtime": "codex",
                "readiness": "unexpected-shape"
              }
            ]
            """.trimIndent()

        val members = assertNotNull(parseSharedSettingsAgentsAsMembers(body))
        assertEquals(4, members.size)

        val quota = assertNotNull(members[0].agentReadiness)
        assertEquals("waiting", quota.state)
        assertEquals("quota_exhausted", quota.primary?.code)
        assertEquals("runtime", quota.primary?.resource?.kind)
        assertEquals("antigravity", quota.primary?.resource?.runtime)
        assertEquals(1795259909000L, quota.primary?.until)
        assertTrue(quota.holds())
        assertEquals(AgentReadinessLight.Error, quota.light())

        val trimmed = assertNotNull(members[1].agentReadiness)
        assertEquals("blocked", trimmed.state)
        assertNull(trimmed.primary)
        assertEquals("Olive", trimmed.actorName)
        assertEquals("verify_account", trimmed.action)
        assertTrue(trimmed.holds())
        assertEquals(AgentReadinessLight.Error, trimmed.light())

        val desktopOld = assertNotNull(members[2].agentReadiness)
        assertEquals("computer_too_old", desktopOld.primary?.code)
        assertEquals(true, desktopOld.primary?.managedByDesktop)

        assertNull(members[3].agentReadiness)
    }

    @Test
    fun resetCountdownMatchesWebRackResetBoundaries() {
        val now = 1_000_000L
        assertNull(agentReadinessResetCountdown(null, now))
        assertNull(agentReadinessResetCountdown(now, now))
        assertNull(agentReadinessResetCountdown(now - 1_000L, now))

        assertEquals(RaftRelativeTimeUnit.Minute to 1, agentReadinessResetCountdown(now + 30_000L, now)?.let { it.unit to it.count })
        assertEquals(RaftRelativeTimeUnit.Minute to 59, agentReadinessResetCountdown(now + 59L * 60_000L + 59_000L, now)?.let { it.unit to it.count })
        assertEquals(RaftRelativeTimeUnit.Hour to 1, agentReadinessResetCountdown(now + 60L * 60_000L, now)?.let { it.unit to it.count })
        assertEquals(RaftRelativeTimeUnit.Hour to 47, agentReadinessResetCountdown(now + 47L * 3_600_000L + 3_599_000L, now)?.let { it.unit to it.count })
        assertEquals(RaftRelativeTimeUnit.Day to 2, agentReadinessResetCountdown(now + 48L * 3_600_000L, now)?.let { it.unit to it.count })
        assertEquals(RaftRelativeTimeUnit.Day to 2, agentReadinessResetCountdown(now + 71L * 3_600_000L, now)?.let { it.unit to it.count })
    }

    @Test
    fun quotaExhaustedAndRateLimitedUseGenericDetailAndCountdownOrGenericTitle() {
        val now = 1_795_250_000_000L
        val quotaWithUntil =
            AgentReadiness(
                state = "waiting",
                primary =
                    AgentReadinessFact(
                        resource = AgentReadinessResource(kind = "runtime", runtime = "antigravity"),
                        code = "quota_exhausted",
                        behaviour = "waiting",
                        since = now - 60_000L,
                        until = now + 3L * 3_600_000L + 20L * 60_000L,
                    ),
                scope = "computer",
                actor = "computer_owner",
            )
        val withUntilSpec = resolveAgentReadinessCopySpec(quotaWithUntil, "antigravity", now)
        assertEquals(AgentReadinessLight.Error, withUntilSpec.light)
        assertEquals(Res.string.settings_computers_rack_resets_in_hours, withUntilSpec.title.res)
        assertEquals(3, withUntilSpec.title.intArg)
        // Must use generic detail (never claims this specific agent's model quota is exhausted).
        assertEquals(Res.string.agent_readiness_generic_detail, withUntilSpec.detail.res)
        assertNull(withUntilSpec.scope)
        assertNull(withUntilSpec.actor)
        assertNull(withUntilSpec.verificationPortalUrl)
        assertFalse(withUntilSpec.offersRetry)

        val rateLimitedNoUntil =
            AgentReadiness(
                state = "waiting",
                primary = AgentReadinessFact(code = "rate_limited", behaviour = "waiting", until = null),
            )
        val noUntilSpec = resolveAgentReadinessCopySpec(rateLimitedNoUntil, "codex", now)
        assertEquals(AgentReadinessLight.Error, noUntilSpec.light)
        assertEquals(Res.string.agent_readiness_generic_title, noUntilSpec.title.res)
        assertEquals(Res.string.agent_readiness_generic_detail, noUntilSpec.detail.res)

        val expiredUntil =
            AgentReadiness(
                state = "waiting",
                primary = AgentReadinessFact(code = "quota_exhausted", behaviour = "waiting", until = now - 1L),
            )
        val expiredSpec = resolveAgentReadinessCopySpec(expiredUntil, "antigravity", now)
        assertEquals(Res.string.agent_readiness_generic_title, expiredSpec.title.res)
    }

    @Test
    fun effectiveHoldingReadinessRespectsPrecedenceAndNonHoldingStates() {
        val held = AgentReadiness(state = "waiting", primary = AgentReadinessFact(code = "quota_exhausted"))
        val ready = AgentReadiness(state = "ready")
        val advisory = AgentReadiness(state = "advisory", primary = AgentReadinessFact(code = "computer_reconnecting"))

        assertEquals(held, effectiveHoldingReadiness(held, blocked = null, external = false, paused = false, appBot = false))
        assertNull(effectiveHoldingReadiness(ready, blocked = null, external = false, paused = false, appBot = false))
        assertNull(effectiveHoldingReadiness(advisory, blocked = null, external = false, paused = false, appBot = false))
        assertNull(effectiveHoldingReadiness(held, blocked = AgentBlockedState.ComputerAccessRevoked, external = false, paused = false, appBot = false))
        assertNull(effectiveHoldingReadiness(held, blocked = null, external = true, paused = false, appBot = false))
        assertNull(effectiveHoldingReadiness(held, blocked = null, external = false, paused = true, appBot = false))
        assertNull(effectiveHoldingReadiness(held, blocked = null, external = false, paused = false, appBot = true))
    }

    @Test
    fun incrementalSyncAgentRowCarriesReadiness() {
        val row =
            Json.parseToJsonElement(
                """{"id":"agent-1","name":"Antigravity","status":"active","runtime":"antigravity","readiness":{"state":"waiting","primary":{"code":"rate_limited","behaviour":"waiting","until":1795259909000}}}"""
            ) as JsonObject
        val synced = parseSharedAgentRow(row) as SharedSyncedAgentRow.Member
        assertEquals("waiting", synced.profile.agentReadiness?.state)
        assertEquals("rate_limited", synced.profile.agentReadiness?.primary?.code)
        assertEquals(1795259909000L, synced.profile.agentReadiness?.primary?.until)
    }

    @Test
    fun membersStorePreservesReadinessAcrossIdlePresenceAndClearsWhenServerReportsReady() = runTest {
        val serverId = ServerId("srv-1")
        val server = ServerSummary(id = serverId, name = "Test", slug = "test")
        val owner = ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = serverId.value)
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        val registry = ProcessMemberPresenceRegistry()
        var currentAgentsBody =
            """
            [
              {
                "id": "agent-1",
                "serverId": "srv-1",
                "name": "Antigravity",
                "status": "active",
                "runtime": "antigravity",
                "activity": "online",
                "activityDetail": "Idle",
                "readiness": {
                  "state": "waiting",
                  "primary": {
                    "resource": { "kind": "runtime", "runtime": "antigravity" },
                    "code": "quota_exhausted",
                    "behaviour": "waiting",
                    "until": 1795259909000
                  }
                }
              }
            ]
            """.trimIndent()
        val repo =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId,
                ): DataResult<MemberSnapshot> =
                    DataResult.Content(
                        MemberSnapshot(
                            server = server,
                            members = requireNotNull(parseSharedSettingsAgentsAsMembers(currentAgentsBody)),
                        ),
                    )
            }
        val store = SharedServerMembersStore(memberRepository = repo, presenceRegistry = registry)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "test")

        // Even after a live "Message received" / Idle presence update, the holding readiness stays on the status.
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Online,
                statusLabel = "Message received",
                serverSeq = null,
            ),
            registry.writeGeneration,
            transport,
        )
        val heldStatus = assertNotNull(store.currentMemberStatus(serverId, "agent-1"))
        assertEquals("quota_exhausted", heldStatus.readiness?.primary?.code)
        assertTrue(heldStatus.readiness.holds())
        val heldIndicator = heldStatus.toRaftAgentStatusIndicatorState()
        assertEquals("quota_exhausted", heldIndicator.readiness?.primary?.code)
        assertEquals("quota_exhausted", heldIndicator.withoutActivityDetail().readiness?.primary?.code)

        // Once GET /api/agents reports readiness=ready, the hold clears.
        currentAgentsBody =
            """
            [
              {
                "id": "agent-1",
                "serverId": "srv-1",
                "name": "Antigravity",
                "status": "active",
                "runtime": "antigravity",
                "activity": "online",
                "activityDetail": "Idle",
                "readiness": { "state": "ready", "primary": null, "scope": null, "actor": null, "also": [] }
              }
            ]
            """.trimIndent()
        store.refreshMembers(serverId, force = true, reason = "test")
        val readyStatus = assertNotNull(store.currentMemberStatus(serverId, "agent-1"))
        assertEquals("ready", readyStatus.readiness?.state)
        assertFalse(readyStatus.readiness.holds())

        // A later GET /api/agents that omits readiness clears any prior hold in both the
        // authoritative roster and the fallback profile cache.
        currentAgentsBody =
            """
            [
              {
                "id": "agent-1",
                "serverId": "srv-1",
                "name": "Antigravity",
                "status": "active",
                "runtime": "antigravity",
                "activity": "online",
                "activityDetail": "Idle",
                "readiness": {
                  "state": "waiting",
                  "primary": {
                    "resource": { "kind": "runtime", "runtime": "antigravity" },
                    "code": "quota_exhausted",
                    "behaviour": "waiting"
                  }
                }
              }
            ]
            """.trimIndent()
        store.refreshMembers(serverId, force = true, reason = "test")
        assertTrue(store.currentMemberStatus(serverId, "agent-1")?.readiness.holds())

        currentAgentsBody =
            """
            [
              {
                "id": "agent-1",
                "serverId": "srv-1",
                "name": "Antigravity",
                "status": "active",
                "runtime": "antigravity",
                "activity": "online",
                "activityDetail": "Idle"
              }
            ]
            """.trimIndent()
        store.refreshMembers(serverId, force = true, reason = "test")
        val omittedStatus = assertNotNull(store.currentMemberStatus(serverId, "agent-1"))
        assertNull(omittedStatus.readiness)
        assertNull(store.state.value.profileCacheByServerId[serverId.value]?.get("agent-1")?.agentReadiness)
    }

    @Test
    fun durableCachesStripAgentReadiness() {
        val heldAgent =
            requireNotNull(
                parseSharedSettingsAgentsAsMembers(
                    """
                    [
                      {
                        "id": "agent-1",
                        "serverId": "srv-1",
                        "name": "Antigravity",
                        "status": "active",
                        "runtime": "antigravity",
                        "activity": "online",
                        "readiness": {
                          "state": "waiting",
                          "primary": {
                            "resource": { "kind": "runtime", "runtime": "antigravity" },
                            "code": "quota_exhausted",
                            "behaviour": "waiting"
                          }
                        }
                      }
                    ]
                    """.trimIndent(),
                ),
            ).single()
        assertTrue(heldAgent.agentReadiness.holds())

        val durableMember = heldAgent.forDurableRoster()
        assertEquals(Presence.Offline, durableMember.presence)
        assertNull(durableMember.agentReadiness)

        val storedChannel =
            SharedServerChannelMembersSlice(
                channelId = "ch-1",
                members = listOf(heldAgent),
            ).toStoredChannelMembers()
        assertEquals(Presence.Offline, storedChannel.members.single().presence)
        assertNull(storedChannel.members.single().agentStatus)
        assertNull(storedChannel.members.single().agentReadiness)
    }

    @Test
    fun pausedPersonalAssistantPreservesPausedFlagAcrossLikelyOnlineAndNeutralReconnect() = runTest {
        val heldReadiness =
            AgentReadiness(
                state = "waiting",
                primary =
                    AgentReadinessFact(
                        resource = AgentReadinessResource(kind = "runtime", runtime = "antigravity"),
                        code = "quota_exhausted",
                        behaviour = "waiting",
                    ),
            )
        val baseAgent =
            requireNotNull(
                parseSharedSettingsAgentsAsMembers(
                    """
                    [
                      {
                        "id": "agent-paused",
                        "serverId": "srv-1",
                        "name": "Assistant",
                        "status": "active",
                        "runtime": "antigravity"
                      }
                    ]
                    """.trimIndent(),
                ),
            ).single().copy(
                assistantPausedAt = "2026-11-21T00:00:00Z",
                agentStatus = null,
                agentReadiness = heldReadiness,
            )

        val likelyOnline = assertNotNull(baseAgent.likelyOnlineMemberStatusOrNull())
        assertTrue(likelyOnline.paused)
        assertNull(effectiveHoldingReadiness(likelyOnline.toRaftAgentStatusIndicatorState()))

        // Neutral reconnect branch (live transport, no presence fact, lifecycle != active):
        val serverId = ServerId("srv-1")
        val server = ServerSummary(id = serverId, name = "Test", slug = "test")
        val neutralAgent = baseAgent.copy(agentLifecycleStatus = "stopped")
        val registry = ProcessMemberPresenceRegistry()
        val owner = ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = serverId.value)
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        val repo =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId,
                ): DataResult<MemberSnapshot> =
                    DataResult.Content(MemberSnapshot(server = server, members = listOf(neutralAgent)))
            }
        val store = SharedServerMembersStore(memberRepository = repo, presenceRegistry = registry)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "test")
        val neutralStatus = assertNotNull(store.currentMemberStatus(serverId, "agent-paused"))
        assertEquals("—", neutralStatus.statusLabel)
        assertTrue(neutralStatus.paused)
        assertNull(effectiveHoldingReadiness(neutralStatus.toRaftAgentStatusIndicatorState()))
    }

    @Test
    fun parsesModelUnavailableReadinessGuardsMissingResourceKeyAndResolvesCopySpec() {
        val body =
            """
            [
              {
                "id": "agent-blocked",
                "name": "Muse",
                "status": "active",
                "runtime": "antigravity",
                "model": "gemini-4-neon",
                "readiness": {
                  "state": "blocked",
                  "primary": {
                    "resource": { "kind": "model", "key": "shared:antigravity", "model": "gemini-4-neon" },
                    "code": "model_unavailable",
                    "behaviour": "blocked",
                    "since": 1795245291000,
                    "params": { "model": "gemini-4-neon" }
                  },
                  "scope": "agent",
                  "actor": "agent_owner",
                  "actorName": "Agnes",
                  "action": "change_model",
                  "also": []
                }
              },
              {
                "id": "agent-advisory",
                "name": "WarmMuse",
                "status": "active",
                "runtime": "antigravity",
                "model": "gemini-4-neon",
                "readiness": {
                  "state": "advisory",
                  "primary": {
                    "resource": { "kind": "model", "key": "shared:antigravity", "model": "gemini-4-neon" },
                    "code": "model_unavailable",
                    "behaviour": "advisory",
                    "since": 1795245291000,
                    "params": { "model": "gemini-4-neon" }
                  },
                  "scope": "agent",
                  "actor": "agent_owner",
                  "actorName": "Agnes",
                  "action": "change_model",
                  "also": []
                }
              }
            ]
            """.trimIndent()

        val members = assertNotNull(parseSharedSettingsAgentsAsMembers(body))
        assertEquals(2, members.size)

        val blocked = assertNotNull(members[0].agentReadiness)
        assertTrue(blocked.holds())
        assertFalse(blocked.isModelUnavailableAdvisory())
        assertEquals(blocked, blocked.attentionReadinessOrNull())
        assertEquals("antigravity", blocked.resolvedRuntimeId(fallbackRuntimeId = "claude"))
        assertEquals("gemini-4-neon", blocked.primary?.resolvedModel())

        val blockedSpec = resolveAgentReadinessCopySpec(blocked, fallbackRuntimeId = null, nowEpochMillis = 1_795_250_000_000L)
        assertEquals(AgentReadinessLight.Error, blockedSpec.light)
        assertEquals(Res.string.agent_readiness_model_unavailable_title, blockedSpec.title.res)
        assertEquals(Res.string.agent_readiness_model_unavailable_detail, blockedSpec.detail.res)
        assertEquals(raftAgentRuntimeDisplayName("antigravity"), blockedSpec.detail.stringArg)
        assertEquals("gemini-4-neon", blockedSpec.detail.stringArg2)
        assertTrue(blockedSpec.offersChangeModel)
        assertFalse(blockedSpec.offersRetry)

        val advisory = assertNotNull(members[1].agentReadiness)
        assertFalse(advisory.holds())
        assertTrue(advisory.isModelUnavailableAdvisory())
        assertNull(advisory.attentionReadinessOrNull())
        assertNull(effectiveHoldingReadiness(advisory, blocked = null, external = false, paused = false))

        // Guard missing resource.key on login / login_window / model (#17133): never throws and falls back cleanly.
        val missingKeyModel =
            AgentReadiness(
                state = "blocked",
                primary =
                    AgentReadinessFact(
                        resource = AgentReadinessResource(kind = "model", key = null, model = "gemini-4-neon"),
                        code = "model_unavailable",
                        behaviour = "blocked",
                    ),
            )
        assertEquals("antigravity", missingKeyModel.resolvedRuntimeId("antigravity"))
        assertEquals("gemini-4-neon", missingKeyModel.primary?.resolvedModel())

        val missingKeyLogin =
            AgentReadiness(
                state = "blocked",
                primary =
                    AgentReadinessFact(
                        resource = AgentReadinessResource(kind = "login", key = null),
                        code = "verification_required",
                        behaviour = "blocked",
                    ),
            )
        assertEquals("claude", missingKeyLogin.resolvedRuntimeId("claude"))

        // Trimmed readiness with change_model action (named actor, role-only actor, unknown future action).
        val trimmedNamed =
            AgentReadiness(
                state = "blocked",
                primary = null,
                actor = "agent_owner",
                actorName = "Agnes",
                action = "change_model",
            )
        val trimmedNamedSpec = resolveAgentReadinessCopySpec(trimmedNamed, null, 0L)
        assertEquals(Res.string.agent_readiness_can_change_model, trimmedNamedSpec.actor?.res)
        assertEquals("Agnes", trimmedNamedSpec.actor?.stringArg)
        assertTrue(trimmedNamedSpec.wrapActorInSentence)

        val trimmedRole =
            AgentReadiness(
                state = "blocked",
                primary = null,
                actor = "agent_owner",
                actorName = null,
                action = "change_model",
            )
        assertEquals(
            Res.string.agent_readiness_owner_can_change_model_agent_owner,
            resolveAgentReadinessCopySpec(trimmedRole, null, 0L).actor?.res,
        )

        val trimmedFutureAction =
            AgentReadiness(
                state = "blocked",
                primary = null,
                actor = "agent_owner",
                actorName = "Agnes",
                action = "future_unknown_action",
            )
        assertEquals(
            Res.string.agent_readiness_can_fix,
            resolveAgentReadinessCopySpec(trimmedFutureAction, null, 0L).actor?.res,
        )
    }
}
