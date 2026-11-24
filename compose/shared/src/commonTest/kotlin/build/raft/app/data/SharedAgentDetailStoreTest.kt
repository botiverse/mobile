package build.raft.app.data

import build.raft.app.MembersAgentLifecycleAction
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeAgentActivityEntry
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedAgentDetailStoreTest {
    private fun store() = SharedAgentDetailStore(logger = {})

    private val testMemberId = MemberId("agent-1")
    private val testDetail = AgentDetailSnapshot(
        agentId = testMemberId,
        directMessages = emptyList(),
        reminders = emptyList(),
        activity = emptyList()
    )

    private fun detailWithSummary(
        status: String? = null,
        activity: String? = null,
        activityDetail: String? = null,
        activityEntries: List<AgentActivityEntry> = emptyList()
    ) = testDetail.copy(
        summary = AgentDetailSummary(
            id = testMemberId.value,
            name = "Agent One",
            status = status,
            activity = activity,
            activityDetail = activityDetail
        ),
        activity = activityEntries
    )

    private fun activityEvent(
        status: AgentStatus?,
        detail: String? = null,
        serverSeq: Long? = null,
        activityEntries: List<RealtimeAgentActivityEntry> = emptyList()
    ) = MemberPresenceUpdated(
        memberId = testMemberId,
        presence = status?.presence ?: Presence.Offline,
        kind = MemberKind.Agent,
        agentStatus = status,
        statusLabel = detail,
        serverSeq = serverSeq,
        activityEntries = activityEntries
    )

    @Test
    fun runtimeConfigProjectionUpdatesMountedDetailAndRejectsSameGenerationRollback() {
        val s = store()
        val owner =
            SharedAgentDetailOwner(
                environmentId = "https://api.raft.build/api",
                accountId = "account-1",
                serverId = ServerId("server-1")
            )
        val initial =
            detailWithSummary().copy(
                summary = detailWithSummary().summary?.copy(runtime = "claude", model = "fable")
            )
        val saved = RuntimeConfig(runtime = "claude", model = RuntimeModelConfig.Preset("claude-opus-5"))
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId, owner, profileGeneration = 4L))
        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                initial,
                serverId = owner.serverId,
                owner = owner,
                profileGeneration = 4L
            )
        )

        s.dispatch(
            SharedAgentDetailIntent.ApplyRuntimeConfigProjection(
                owner = owner,
                agentId = testMemberId,
                config = saved,
                profileGeneration = 4L
            )
        )
        assertEquals("claude-opus-5", s.state.value.detail?.summary?.model)

        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                initial,
                serverId = owner.serverId,
                owner = owner,
                profileGeneration = 4L
            )
        )
        assertEquals("claude-opus-5", s.state.value.detail?.summary?.model)
    }

    @Test
    fun runtimeConfigSaveCarriesTheSavedConfigsModelLimitsSignal() {
        // botiverse/slock #13492: the answer to a save carries the signal for the saved config, never
        // the old one, so a warning shown for the old config goes away with it.
        val s = store()
        val owner = SharedAgentDetailOwner("https://api.raft.build/api", "account-1", ServerId("server-1"))
        val warned =
            detailWithSummary().copy(
                summary = detailWithSummary().summary?.copy(runtime = "builtin", modelLimitsDefaultsReported = true)
            )
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId, owner, profileGeneration = 1L))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, warned, owner.serverId, owner = owner, profileGeneration = 1L))
        val claude = RuntimeConfig(runtime = "claude", model = RuntimeModelConfig.Preset("claude-opus-5"))

        // No read-back value: what is shown stays.
        s.dispatch(SharedAgentDetailIntent.ApplyRuntimeConfigProjection(owner, testMemberId, claude, 1L))
        assertEquals(true, s.state.value.detail?.summary?.modelLimitsDefaultsReported)

        s.dispatch(
            SharedAgentDetailIntent.ApplyRuntimeConfigProjection(owner, testMemberId, claude, 1L, modelLimitsDefaultsReported = false)
        )
        assertEquals(false, s.state.value.detail?.summary?.modelLimitsDefaultsReported)

        // A later fresh read (agent:updated) decides again; the saved projection does not pin it.
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, warned, owner.serverId, owner = owner, profileGeneration = 1L))
        assertEquals(true, s.state.value.detail?.summary?.modelLimitsDefaultsReported)
    }

    @Test
    fun runtimeConfigProjectionIsOwnerAndGenerationFenced() {
        val s = store()
        val owner =
            SharedAgentDetailOwner(
                environmentId = "https://api.raft.build/api",
                accountId = "account-1",
                serverId = ServerId("server-1")
            )
        val saved = RuntimeConfig(runtime = "claude", model = RuntimeModelConfig.Preset("claude-opus-5"))
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId, owner, profileGeneration = 4L))
        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                detailWithSummary().copy(
                    summary = detailWithSummary().summary?.copy(runtime = "claude", model = "fable")
                ),
                serverId = owner.serverId,
                owner = owner,
                profileGeneration = 4L
            )
        )

        s.dispatch(
            SharedAgentDetailIntent.ApplyRuntimeConfigProjection(
                owner = owner.copy(accountId = "account-2"),
                agentId = testMemberId,
                config = saved,
                profileGeneration = 4L
            )
        )
        s.dispatch(
            SharedAgentDetailIntent.ApplyRuntimeConfigProjection(
                owner = owner,
                agentId = testMemberId,
                config = saved,
                profileGeneration = 3L
            )
        )
        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                detailWithSummary().copy(
                    summary = detailWithSummary().summary?.copy(runtime = "claude", model = "cross-owner")
                ),
                serverId = owner.serverId,
                owner = owner.copy(environmentId = "https://staging.raft.build/api"),
                profileGeneration = 4L
            )
        )

        assertEquals("fable", s.state.value.detail?.summary?.model)
        assertNull(s.state.value.runtimeConfigProjection)
    }

    @Test
    fun seedDetailFromAgentMemberSetsContentSnapshot() {
        val s = store()
        s.dispatch(
            SharedAgentDetailIntent.SeedDetailFromMember(
                MemberProfile(
                    id = testMemberId,
                    displayName = "Agent One",
                    handle = "agent-one",
                    kind = MemberKind.Agent,
                    description = "Building Android",
                    roleDescription = "gpt-5-codex",
                    avatar = AvatarReference.Url("https://example.com/avatar.png"),
                    presence = Presence.Active,
                    agentStatus = AgentStatus.Working,
                    serverId = ServerId("server-1"),
                    permissionBadges = listOf("computer:Jiachengs-MacBook-Pro", "computer-id:machine-1")
                )
            )
        )

        val state = s.state.value
        val summary = state.detail?.summary
        assertEquals(testMemberId, state.agentId)
        assertEquals(ServerId("server-1"), state.detailServerId)
        assertEquals(testMemberId, state.detail?.agentId)
        assertEquals(SharedAgentDetailLoadStatus.Content, state.detailLoadStatus)
        assertEquals("agent-one", summary?.name)
        assertEquals("Agent One", summary?.displayName)
        assertNull(summary?.status)
        assertNull(summary?.activity)
        assertEquals("Building Android", summary?.activityDetail)
        assertEquals("gpt-5-codex", summary?.runtime)
        assertEquals("https://example.com/avatar.png", summary?.avatarUrl)
        assertEquals("Jiachengs-MacBook-Pro", summary?.machineName)
        assertEquals("machine-1", summary?.machineId)
    }

    @Test
    fun seedDetailFromHumanMemberIsIgnored() {
        val s = store()
        s.dispatch(
            SharedAgentDetailIntent.SeedDetailFromMember(
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Artin",
                    handle = "artin",
                    kind = MemberKind.Human
                )
            )
        )

        val state = s.state.value
        assertNull(state.agentId)
        assertNull(state.detail)
        assertEquals(SharedAgentDetailLoadStatus.Idle, state.detailLoadStatus)
    }

    @Test
    fun seedDetailFromSameAgentOnAnotherServerReplacesTheDisplaySeed() {
        val s = store()
        s.dispatch(
            SharedAgentDetailIntent.SeedDetailFromMember(
                MemberProfile(
                    id = testMemberId,
                    displayName = "Server One Agent",
                    handle = "server-one-agent",
                    kind = MemberKind.Agent,
                    serverId = ServerId("server-1")
                )
            )
        )
        s.dispatch(
            SharedAgentDetailIntent.SeedDetailFromMember(
                MemberProfile(
                    id = testMemberId,
                    displayName = "Server Two Agent",
                    handle = "server-two-agent",
                    kind = MemberKind.Agent,
                    serverId = ServerId("server-2")
                )
            )
        )

        assertEquals(ServerId("server-2"), s.state.value.detailServerId)
        assertEquals("Server Two Agent", s.state.value.detail?.summary?.displayName)
        assertEquals("server-two-agent", s.state.value.detail?.summary?.name)
    }

    @Test
    fun serverBoundActivitySnapshotCannotMutateAnotherServerDetail() {
        val s = store()
        s.dispatch(
            SharedAgentDetailIntent.SeedDetailFromMember(
                MemberProfile(
                    id = testMemberId,
                    displayName = "Server One Agent",
                    handle = "server-one-agent",
                    kind = MemberKind.Agent,
                    serverId = ServerId("server-1")
                )
            )
        )
        s.dispatch(
            SharedAgentDetailIntent.SetAgentActivity(
                agentId = testMemberId,
                activity =
                    listOf(
                        AgentActivityEntry(
                            id = "wrong-server-activity",
                            type = "status",
                            title = "Wrong server",
                            timestamp = "100"
                        )
                    ),
                serverId = ServerId("server-2")
            )
        )

        assertEquals(emptyList(), s.state.value.detail?.activity)
        assertNull(s.state.value.agentActivityLogs[testMemberId])
    }

    @Test
    fun setDetailLoadingKeepsSeedDetailVisibleDuringRefresh() {
        val s = store()
        s.dispatch(
            SharedAgentDetailIntent.SeedDetailFromMember(
                MemberProfile(
                    id = testMemberId,
                    displayName = "Agent One",
                    handle = "agent-one",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working
                )
            )
        )

        s.dispatch(SharedAgentDetailIntent.SetDetailLoading(testMemberId))

        val state = s.state.value
        assertEquals(SharedAgentDetailLoadStatus.Loading, state.detailLoadStatus)
        assertEquals(testMemberId, state.detail?.agentId)
        assertNull(state.detail?.summary?.status)
        assertNull(state.detail?.summary?.activity)
    }

    @Test
    fun setDetailPreservesExistingSectionsWhenRefreshIsPartial() {
        val s = store()
        val existing =
            detailWithSummary(
                status = "Working",
                activityEntries = listOf(
                    AgentActivityEntry(
                        id = "activity-1",
                        type = "status",
                        title = "Building"
                    )
                )
            ).copy(
                directMessages = listOf(
                    AgentDmSummary(
                        id = "dm-1",
                        peerId = "user-1",
                        peerName = "Artin"
                    )
                ),
                reminders = listOf(
                    AgentReminderSummary(
                        id = "reminder-1",
                        title = "Follow up"
                    )
                ),
                skills = AgentSkillsSnapshot(
                    agentId = testMemberId,
                    groups = listOf(AgentSkillGroup(title = "Tools", count = 1))
                ),
                appLogins = AgentAppLoginSnapshot(
                    agentId = testMemberId,
                    items = listOf(
                        AgentAppLoginItem(
                            id = "login-1",
                            type = AgentAppLoginType.Active,
                            clientId = "client-1",
                            clientName = "GitHub"
                        )
                    )
                )
            )
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, existing))

        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                detailWithSummary(status = "Thinking", activity = "Thinking")
            )
        )

        val detail = s.state.value.detail
        assertEquals("Thinking", detail?.summary?.status)
        assertEquals(listOf("dm-1"), detail?.directMessages?.map { it.id })
        assertEquals(listOf("reminder-1"), detail?.reminders?.map { it.id })
        assertEquals(listOf("activity-1"), detail?.activity?.map { it.id })
        assertEquals(listOf("Tools"), detail?.skills?.groups?.map { it.title })
        assertEquals(listOf("login-1"), detail?.appLogins?.items?.map { it.id })
    }

    @Test
    fun selectMemberResetsTransientState() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, testDetail))
        s.dispatch(SharedAgentDetailIntent.ShowConfirmation(MembersAgentLifecycleAction.Delete))
        s.dispatch(SharedAgentDetailIntent.BeginEditDisplayName("Cindy"))

        s.dispatch(SharedAgentDetailIntent.SelectMember(MemberId("agent-2")))

        val state = s.state.value
        assertEquals(MemberId("agent-2"), state.agentId)
        assertNull(state.detail)
        assertEquals(SharedAgentDetailLoadStatus.Idle, state.detailLoadStatus)
        assertNull(state.pendingConfirmation)
        assertFalse(state.displayNameEditing)
    }

    @Test
    fun staleDetailResultIsIgnoredAfterDeselect() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.DeselectMember)
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, testDetail))

        assertNull(s.state.value.agentId)
        assertNull(s.state.value.detail)
        assertEquals(SharedAgentDetailLoadStatus.Idle, s.state.value.detailLoadStatus)
    }

    @Test
    fun staleAgentActivitySeqIsIgnored() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Offline", activity = "Offline")))

        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Working, detail = "Building", serverSeq = 10L),
                observedAtMillis = 100L
            )
        )
        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Offline, detail = "Stopped", serverSeq = 9L),
                observedAtMillis = 200L
            )
        )

        val state = s.state.value
        assertEquals("Working", state.detail?.summary?.status)
        assertEquals("Working", state.detail?.summary?.activity)
        assertEquals("Building", state.detail?.summary?.activityDetail)
        assertEquals(10L, state.agentActivitySeq[testMemberId])
        assertEquals(100L, state.agentActivityObservedAt[testMemberId])
        assertEquals(1L, state.agentActivityVersions[testMemberId])
        assertEquals(1, state.detail?.activity?.size)
        assertEquals("realtime:agent-1:seq:10", state.detail?.activity?.single()?.id)
        assertEquals(
            listOf("realtime:agent-1:seq:10"),
            state.agentRealtimeActivityLogs[testMemberId]?.map { it.id }
        )
        assertEquals("Working", state.detail?.activity?.single()?.title)
        assertEquals("Building", state.detail?.activity?.single()?.body)
    }

    @Test
    fun theHeldReasonRidesTheIdleFrameAndTheNextFrameWithoutItDropsIt() {
        // Web credentialHoldProjection.test.ts (botiverse/slock #13276): the latest frame is the state.
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Online", activity = "Online")))
        fun push(seq: Long, status: AgentStatus, hold: AgentCredentialHoldReason?, coarse: Boolean = false) =
            s.dispatch(
                SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                    event =
                        activityEvent(status, detail = if (coarse) null else "held notice", serverSeq = seq)
                            .copy(detailKind = "idle", credentialHold = hold, coarseState = coarse),
                    observedAtMillis = seq * 100L
                )
            )
        push(1L, AgentStatus.Online, AgentCredentialHoldReason.UserReauth)
        assertEquals(AgentCredentialHoldReason.UserReauth, s.state.value.detail?.summary?.credentialHold)
        push(2L, AgentStatus.Online, AgentCredentialHoldReason.Billing)
        assertEquals(AgentCredentialHoldReason.Billing, s.state.value.detail?.summary?.credentialHold, "a new reason with the same text")
        push(3L, AgentStatus.Online, null, coarse = true)
        assertEquals(AgentCredentialHoldReason.Billing, s.state.value.detail?.summary?.credentialHold, "a coarse state keeps it")
        push(4L, AgentStatus.Online, null)
        assertNull(s.state.value.detail?.summary?.credentialHold, "the release's idle frame clears it")
    }

    @Test
    fun deliveryHoldPersistsAcrossWorkingFramesAndUpdatesOnCoarseFrames() {
        // Web deliveryHoldProjection.test.ts (botiverse/slock #16333): hold survives working frames,
        // updates on coarse agent:activity_state, and drops with the first frame without it.
        val signIn =
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.CredentialInvalid,
                credentialDetail = AgentCredentialHoldReason.UserReauth,
                since = 1_000L,
            )
        val quota =
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
                untilMs = 10_000L,
                since = 1_000L,
            )
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Online", activity = "Online")))
        fun push(seq: Long, status: AgentStatus, detailKind: String?, hold: AgentDeliveryHold?, coarse: Boolean = false) =
            s.dispatch(
                SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                    event =
                        activityEvent(status, detail = if (coarse) null else "Working", serverSeq = seq)
                            .copy(detailKind = detailKind, deliveryHold = hold, coarseState = coarse),
                    observedAtMillis = seq * 100L,
                )
            )

        push(1L, AgentStatus.Online, "idle", signIn)
        assertEquals(signIn, s.state.value.detail?.summary?.deliveryHold)
        push(2L, AgentStatus.Working, "running_command", signIn)
        assertEquals(signIn, s.state.value.detail?.summary?.deliveryHold, "a working frame keeps it")
        push(3L, AgentStatus.Working, null, quota, coarse = true)
        assertEquals(quota, s.state.value.detail?.summary?.deliveryHold, "coarse frame updates deliveryHold")
        push(4L, AgentStatus.Working, "running_command", null)
        assertNull(s.state.value.detail?.summary?.deliveryHold, "release clears it")
    }

    @Test
    fun coarseActivityStateMovesTheStatusButWritesNoLogEntry() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Offline", activity = "Offline")))
        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Working, detail = "Building", serverSeq = 10L),
                observedAtMillis = 100L
            )
        )

        // agent:activity_state (botiverse/slock 968d102d): status only, never a log entry.
        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Offline, detail = null, serverSeq = 12L).copy(coarseState = true),
                observedAtMillis = 200L
            )
        )

        val state = s.state.value
        assertEquals("Offline", state.detail?.summary?.status)
        assertEquals(12L, state.agentActivitySeq[testMemberId])
        assertEquals(listOf("realtime:agent-1:seq:10"), state.detail?.activity?.map { it.id })
        assertEquals(listOf("realtime:agent-1:seq:10"), state.agentActivityLogs[testMemberId]?.map { it.id })
        assertEquals(listOf("realtime:agent-1:seq:10"), state.agentRealtimeActivityLogs[testMemberId]?.map { it.id })
    }

    @Test
    fun sequencedAgentActivitySurvivesUnsequencedRestDetail() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Offline", activity = "Offline")))
        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Working, detail = "Building", serverSeq = 10L),
                observedAtMillis = 100L
            )
        )

        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                detailWithSummary(status = "Offline", activity = "Offline", activityDetail = "Old REST")
            )
        )

        val summary = s.state.value.detail?.summary
        assertEquals("Working", summary?.status)
        assertEquals("Working", summary?.activity)
        assertEquals("Building", summary?.activityDetail)
        assertEquals("realtime:agent-1:seq:10", s.state.value.detail?.activity?.single()?.id)
        assertEquals("Building", s.state.value.detail?.activity?.single()?.body)
    }

    @Test
    fun resetAgentActivitySeqAllowsNewSocketSequence() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Offline", activity = "Offline")))
        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Working, serverSeq = 10L),
                observedAtMillis = 100L
            )
        )

        assertEquals(0L, s.state.value.agentActivitySeqEpoch)
        s.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
        assertEquals(1L, s.state.value.agentActivitySeqEpoch)
        s.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
        assertEquals(2L, s.state.value.agentActivitySeqEpoch, "a rebaseline of an already-empty seq map is still observable")
        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Offline, detail = "Disconnected", serverSeq = 1L),
                observedAtMillis = 200L
            )
        )

        val state = s.state.value
        assertEquals("Offline", state.detail?.summary?.status)
        assertEquals("Offline", state.detail?.summary?.activity)
        assertEquals("Disconnected", state.detail?.summary?.activityDetail)
        assertEquals(1L, state.agentActivitySeq[testMemberId])
        assertEquals(2L, state.agentActivityVersions[testMemberId])
    }

    @Test
    fun agentActivityUpdateAppendsToVisibleActivityLog() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                detailWithSummary(
                    status = "Offline",
                    activity = "Offline",
                    activityEntries = listOf(
                        AgentActivityEntry(
                            id = "rest:older",
                            type = "status",
                            title = "Idle",
                            body = "Waiting",
                            timestamp = "100"
                        )
                    )
                )
            )
        )

        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Working, detail = "Building", serverSeq = 10L),
                observedAtMillis = 200L
            )
        )

        val rows = s.state.value.detail?.activity.orEmpty()
        assertEquals(listOf("rest:older", "realtime:agent-1:seq:10"), rows.map { it.id })
        assertEquals("Working", rows.last().title)
        assertEquals("Building", rows.last().body)
        assertEquals("working", rows.last().statusActivity)
        assertEquals("200", rows.last().timestamp)
        assertEquals(listOf("rest:older", "realtime:agent-1:seq:10"), s.state.value.agentActivityLogs[testMemberId]?.map { it.id })
    }

    @Test
    fun duplicateAgentActivityUpdateWithinOneSecondDoesNotAppend() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Offline", activity = "Offline")))

        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Working, detail = "Building"),
                observedAtMillis = 200L
            )
        )
        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Working, detail = "Building"),
                observedAtMillis = 500L
            )
        )

        assertEquals(1, s.state.value.detail?.activity?.size)
    }

    // Hands 11c0ed95: A's profile -> its computer -> B's profile -> Back -> Back. Each profile route
    // keeps its own store, so B's load never touches what A's (still composed) page shows.
    @Test
    fun anotherAgentsProfileRouteLeavesTheCoveredProfileUntouched() {
        val shell = store()
        val agentB = MemberId("agent-b")
        val routeA = shell.openRouteStore()
        routeA.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        routeA.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Online")))
        val shownByA = routeA.state.value

        val routeB = shell.openRouteStore()
        routeB.dispatch(SharedAgentDetailIntent.DeselectMember)
        routeB.dispatch(SharedAgentDetailIntent.SelectMember(agentB))
        routeB.dispatch(SharedAgentDetailIntent.SetDetailLoading(agentB))
        routeB.dispatch(SharedAgentDetailIntent.SetDetail(agentB, testDetail.copy(agentId = agentB)))
        shell.closeRouteStore(routeB)

        assertTrue(shownByA === routeA.state.value)
        assertEquals(testMemberId, routeA.state.value.agentId)
        assertEquals(SharedAgentDetailLoadStatus.Content, routeA.state.value.detailLoadStatus)
        assertEquals(agentB, routeB.state.value.agentId)
        // The route's own dispatches stay in the route.
        assertNull(shell.state.value.agentId)
    }

    @Test
    fun shellRealtimeReachesOpenProfileRoutesUntilTheyClose() {
        val shell = store()
        val route = shell.openRouteStore()
        route.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        route.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Offline", activity = "Offline")))

        shell.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Working, detail = "Building", serverSeq = 10L),
                observedAtMillis = 100L
            )
        )
        assertEquals("Working", route.state.value.detail?.summary?.status)
        assertEquals(10L, route.state.value.agentActivitySeq[testMemberId])
        // The shell has no agent selected, so it ignores the event (applyAgentActivityUpdate); only the open page applied it.
        assertEquals(null, shell.state.value.agentActivitySeq[testMemberId])

        shell.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
        assertEquals(1L, route.state.value.agentActivitySeqEpoch)
        assertTrue(route.state.value.agentActivitySeq.isEmpty())

        shell.closeRouteStore(route)
        shell.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
        assertEquals(1L, route.state.value.agentActivitySeqEpoch)
        assertEquals(2L, shell.state.value.agentActivitySeqEpoch)
    }
}
