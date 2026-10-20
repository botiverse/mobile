package build.raft.app.data

import build.raft.app.MembersAgentLifecycleAction
import build.raft.app.seedRemoteAgentProfile
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeAgentActivityDetail
import build.raft.app.model.RealtimeAgentActivityEntry
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
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
    fun initialStateIsEmpty() {
        val state = store().state.value
        assertNull(state.agentId)
        assertNull(state.detail)
        assertEquals(SharedAgentDetailLoadStatus.Idle, state.detailLoadStatus)
        assertNull(state.lifecycleActionInFlight)
        assertNull(state.diagnosticActionInFlight)
        assertNull(state.pendingConfirmation)
        assertFalse(state.displayNameEditing)
        assertFalse(state.roleEditing)
    }

    @Test
    fun selectMemberSetsAgentId() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        assertEquals(testMemberId, s.state.value.agentId)
    }

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
    fun newerProfileGenerationCanReplaceSavedRuntimeProjection() {
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
                detailWithSummary(),
                serverId = owner.serverId,
                owner = owner,
                profileGeneration = 4L
            )
        )
        s.dispatch(
            SharedAgentDetailIntent.ApplyRuntimeConfigProjection(
                owner,
                testMemberId,
                saved,
                profileGeneration = 4L
            )
        )

        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId, owner, profileGeneration = 5L))
        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                detailWithSummary().copy(
                    summary = detailWithSummary().summary?.copy(runtime = "claude", model = "server-new")
                ),
                serverId = owner.serverId,
                owner = owner,
                profileGeneration = 5L
            )
        )

        assertEquals("server-new", s.state.value.detail?.summary?.model)
        assertNull(s.state.value.runtimeConfigProjection)
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
                    avatarUrl = "https://example.com/avatar.png",
                    avatar = AvatarReference.fromWireValue("pixel:agent-one"),
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
        assertEquals("pixel:agent-one", summary?.avatarUrl)
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
    fun seedDetailFromMemberDoesNotReplaceExistingMatchingDetail() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Online")))

        s.dispatch(
            SharedAgentDetailIntent.SeedDetailFromMember(
                MemberProfile(
                    id = testMemberId,
                    displayName = "Stale Agent",
                    handle = "stale-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Offline
                )
            )
        )

        assertEquals("Agent One", s.state.value.detail?.summary?.name)
        assertEquals("Online", s.state.value.detail?.summary?.status)
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
    fun remoteProfileSeedReplacesSameIdDetailFromAnotherServer() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Online")))

        seedRemoteAgentProfile(
            s,
            MemberProfile(
                id = testMemberId,
                displayName = "Remote Agent",
                handle = "remote-agent",
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline
            )
        )

        assertEquals("remote-agent", s.state.value.detail?.summary?.name)
        assertEquals("Remote Agent", s.state.value.detail?.summary?.displayName)
        assertNull(s.state.value.detail?.summary?.status)
        assertNull(s.state.value.detail?.summary?.activity)
    }

    @Test
    fun seedDetailFromMemberUsesCachedAgentActivity() {
        val s = store()
        s.dispatch(
            SharedAgentDetailIntent.SetAgentActivity(
                testMemberId,
                listOf(
                    AgentActivityEntry(
                        id = "activity-1",
                        type = "tool_start",
                        title = "Run command",
                        timestamp = "200"
                    )
                )
            )
        )

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

        assertEquals(listOf("activity-1"), s.state.value.detail?.activity?.map { it.id })
    }

    @Test
    fun deselectMemberResetsState() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetailLoading(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, testDetail))
        s.dispatch(SharedAgentDetailIntent.DeselectMember)
        val state = s.state.value
        assertNull(state.agentId)
        assertNull(state.detail)
        assertEquals(SharedAgentDetailLoadStatus.Idle, state.detailLoadStatus)
    }

    @Test
    fun detailLoadingTransitionsAreTracked() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetailLoading(testMemberId))
        assertEquals(SharedAgentDetailLoadStatus.Loading, s.state.value.detailLoadStatus)
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, testDetail))
        assertEquals(SharedAgentDetailLoadStatus.Content, s.state.value.detailLoadStatus)
        val detailError = ScreenError(
            code = "http_409",
            retryable = false,
            serverMessage = "Agent profile is being updated"
        )
        s.dispatch(SharedAgentDetailIntent.SetDetailError(testMemberId, detailError))
        assertEquals(SharedAgentDetailLoadStatus.Error, s.state.value.detailLoadStatus)
        assertEquals(detailError, s.state.value.detailError)
        assertNull(s.state.value.detailPermissionBlocker)
        s.dispatch(SharedAgentDetailIntent.SetDetailNoPermission(testMemberId, "forbidden"))
        assertEquals(SharedAgentDetailLoadStatus.NoPermission, s.state.value.detailLoadStatus)
        assertNull(s.state.value.detailError)
        assertEquals("forbidden", s.state.value.detailPermissionBlocker?.code)
        s.dispatch(SharedAgentDetailIntent.SetDetailEmpty(testMemberId))
        assertEquals(SharedAgentDetailLoadStatus.Empty, s.state.value.detailLoadStatus)
        assertNull(s.state.value.detailError)
        assertNull(s.state.value.detailPermissionBlocker)
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
    fun lifecycleActionInFlightTransitions() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.StartLifecycleAction("lifecycle:start"))
        assertEquals("lifecycle:start", s.state.value.lifecycleActionInFlight)
        assertNull(s.state.value.lastActionResult)
        s.dispatch(
            SharedAgentDetailIntent.FinishLifecycleAction(
                actionKey = "lifecycle:start",
                successLabel = "Started"
            )
        )
        assertNull(s.state.value.lifecycleActionInFlight)
        assertEquals("Started", s.state.value.lastActionResult?.successLabel)
    }

    @Test
    fun lifecycleActionErrorPreservesCompleteScreenError() {
        val s = store()
        val error = ScreenError(
            code = "http_409",
            retryable = false,
            serverMessage = "Agent is already running"
        )
        s.dispatch(SharedAgentDetailIntent.StartLifecycleAction("lifecycle:start"))
        s.dispatch(
            SharedAgentDetailIntent.FinishLifecycleAction(
                actionKey = "lifecycle:start",
                error = error
            )
        )
        assertNull(s.state.value.lifecycleActionInFlight)
        assertEquals(error, s.state.value.lastActionResult?.error)
        assertEquals("Agent is already running", s.state.value.lastActionResult?.error?.serverMessage)
    }

    @Test
    fun startLifecycleClearsPendingConfirmation() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.ShowConfirmation(MembersAgentLifecycleAction.ResetFull))
        assertEquals(MembersAgentLifecycleAction.ResetFull, s.state.value.pendingConfirmation)
        s.dispatch(SharedAgentDetailIntent.StartLifecycleAction("lifecycle:reset_full"))
        assertNull(s.state.value.pendingConfirmation)
    }

    @Test
    fun diagnosticActionInFlightTransitions() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.StartDiagnosticAction("diagnostic:copy"))
        assertEquals("diagnostic:copy", s.state.value.diagnosticActionInFlight)
        s.dispatch(
            SharedAgentDetailIntent.FinishDiagnosticAction(
                actionKey = "diagnostic:copy",
                successLabel = "Diagnostics copied"
            )
        )
        assertNull(s.state.value.diagnosticActionInFlight)
        assertEquals("Diagnostics copied", s.state.value.lastActionResult?.successLabel)
    }

    @Test
    fun reportIssueForwardsTypedRequestAndRecordsSuccess() = runBlocking {
        val s = store()
        val request = SharedAgentIssueReportRequest(
            description = "Agent stopped responding",
            diagnostics = "agentId=agent-1\nstatus=Offline"
        )
        var forwarded: SharedAgentIssueReportRequest? = null
        val actions = object : SharedAgentDetailActions {
            override suspend fun runLifecycleAction(
                agentId: MemberId,
                action: MembersAgentLifecycleAction
            ): DataResult<AgentLifecycleActionResult> = DataResult.Empty(EmptyReason.NoMembers)

            override fun copyDiagnostics(diagnostics: String) = Unit

            override suspend fun reportIssue(
                request: SharedAgentIssueReportRequest
            ): DataResult<AgentIssueReportResult> {
                forwarded = request
                assertEquals("report", s.state.value.diagnosticActionInFlight)
                return DataResult.Content(
                    AgentIssueReportResult(
                        reportId = "report-12345678",
                        artifactId = "artifact-12345678",
                        serverId = "server-1",
                        issueDescription = "Agent stopped responding",
                        transcriptRequested = true
                    )
                )
            }

            override suspend fun updateAgentProfile(request: AgentProfileUpdateRequest): DataResult<MemberProfile> =
                DataResult.Empty(EmptyReason.NoMembers)
        }

        val result = s.reportIssue(request, actions)
        assertTrue(result is DataResult.Content)
        assertEquals("report-12345678", (result as DataResult.Content).data.reportId)
        assertEquals(request, forwarded)
        assertNull(s.state.value.diagnosticActionInFlight)
        assertEquals("Issue reported", s.state.value.lastActionResult?.successLabel)
        assertNull(s.state.value.lastActionResult?.error)
    }

    @Test
    fun displayNameEditLifecycle() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.BeginEditDisplayName("Cindy"))
        assertTrue(s.state.value.displayNameEditing)
        assertEquals("Cindy", s.state.value.displayNameDraft)
        s.dispatch(SharedAgentDetailIntent.UpdateDisplayNameDraft("Cindy Two"))
        assertEquals("Cindy Two", s.state.value.displayNameDraft)
        s.dispatch(SharedAgentDetailIntent.StartSaveDisplayName("Cindy Two"))
        assertTrue(s.state.value.displayNameSaving)
        s.dispatch(SharedAgentDetailIntent.SetDisplayNameError("save_failed"))
        assertEquals("save_failed", s.state.value.displayNameError)
        assertFalse(s.state.value.displayNameSaving)
        s.dispatch(SharedAgentDetailIntent.UpdateDisplayNameDraft("Cindy Three"))
        assertNull(s.state.value.displayNameError)
        s.dispatch(SharedAgentDetailIntent.StartSaveDisplayName("Cindy Three"))
        s.dispatch(SharedAgentDetailIntent.FinishSaveDisplayName)
        assertFalse(s.state.value.displayNameSaving)
        assertFalse(s.state.value.displayNameEditing)
        assertNull(s.state.value.displayNameDraft)
        assertNull(s.state.value.displayNameError)
    }

    @Test
    fun roleEditLifecycle() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.BeginEditRole("description one"))
        assertTrue(s.state.value.roleEditing)
        s.dispatch(SharedAgentDetailIntent.UpdateRoleDraft("description two"))
        s.dispatch(SharedAgentDetailIntent.StartSaveRole("description two"))
        s.dispatch(SharedAgentDetailIntent.SetRoleError("role_too_long"))
        assertFalse(s.state.value.roleSaving)
        s.dispatch(SharedAgentDetailIntent.UpdateRoleDraft("description three"))
        assertNull(s.state.value.roleError)
        s.dispatch(SharedAgentDetailIntent.StartSaveRole("description three"))
        s.dispatch(SharedAgentDetailIntent.FinishSaveRole)
        assertFalse(s.state.value.roleEditing)
        assertNull(s.state.value.roleDraft)
        assertNull(s.state.value.roleError)
    }

    @Test
    fun confirmationLifecycle() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.ShowConfirmation(MembersAgentLifecycleAction.Delete))
        assertEquals(MembersAgentLifecycleAction.Delete, s.state.value.pendingConfirmation)
        s.dispatch(SharedAgentDetailIntent.DismissConfirmation)
        assertNull(s.state.value.pendingConfirmation)
    }

    @Test
    fun actionResultReportingAndClearing() {
        val s = store()
        s.dispatch(
            SharedAgentDetailIntent.ReportActionResult(
                SharedAgentDetailActionResult(
                    actionKey = "lifecycle:delete",
                    successLabel = "Deleted"
                )
            )
        )
        assertEquals("Deleted", s.state.value.lastActionResult?.successLabel)
        s.dispatch(SharedAgentDetailIntent.ClearActionResult)
        assertNull(s.state.value.lastActionResult)
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

        s.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
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
    fun unsequencedAgentActivityAppliesBeforeSequenceBaseline() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Offline", activity = "Offline")))

        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(AgentStatus.Thinking, detail = "Reading context"),
                observedAtMillis = 100L
            )
        )

        val state = s.state.value
        assertEquals("Thinking", state.detail?.summary?.status)
        assertEquals("Thinking", state.detail?.summary?.activity)
        assertEquals("Reading context", state.detail?.summary?.activityDetail)
        assertNull(state.agentActivitySeq[testMemberId])
        assertEquals(1L, state.agentActivityVersions[testMemberId])
        assertEquals("Thinking", state.detail?.activity?.single()?.title)
        assertEquals("Reading context", state.detail?.activity?.single()?.body)
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
    fun agentActivitySnapshotMergesIntoVisibleDetailAndCache() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                detailWithSummary(
                    status = "Working",
                    activityEntries = listOf(
                        AgentActivityEntry(
                            id = "activity-1",
                            type = "status",
                            title = "Working",
                            timestamp = "100"
                        )
                    )
                )
            )
        )

        s.dispatch(
            SharedAgentDetailIntent.SetAgentActivity(
                testMemberId,
                listOf(
                    AgentActivityEntry(
                        id = "activity-2",
                        type = "tool_start",
                        title = "Run command",
                        timestamp = "200"
                    )
                )
            )
        )

        assertEquals(listOf("activity-1", "activity-2"), s.state.value.detail?.activity?.map { it.id })
        assertEquals(listOf("activity-1", "activity-2"), s.state.value.agentActivityLogs[testMemberId]?.map { it.id })
    }

    @Test
    fun cachedAgentActivitySurvivesMemberSwitchAndRestRefresh() {
        val s = store()
        s.dispatch(
            SharedAgentDetailIntent.SetAgentActivity(
                testMemberId,
                listOf(
                    AgentActivityEntry(
                        id = "activity-1",
                        type = "tool_start",
                        title = "Run command",
                        timestamp = "200"
                    )
                )
            )
        )
        s.dispatch(SharedAgentDetailIntent.SelectMember(MemberId("agent-2")))
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, detailWithSummary(status = "Working")))

        assertEquals(listOf("activity-1"), s.state.value.detail?.activity?.map { it.id })
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

    @Test
    fun agentActivityUpdateAppendsSocketTrajectoryEntries() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(
            SharedAgentDetailIntent.SetDetail(
                testMemberId,
                detailWithSummary(status = "Offline", activity = "Offline")
            )
        )

        s.dispatch(
            SharedAgentDetailIntent.ApplyAgentActivityUpdate(
                event = activityEvent(
                    AgentStatus.Working,
                    detail = "Run command",
                    serverSeq = 11L,
                    activityEntries = listOf(
                        RealtimeAgentActivityEntry(
                            id = "realtime:agent-1:seq:11:0:tool_start",
                            type = "tool_start",
                            title = "Run command",
                            body = "Structured input: cmd",
                            timestamp = "1782367200000",
                            details = listOf(
                                RealtimeAgentActivityDetail("Tool", "run_command"),
                                RealtimeAgentActivityDetail("Input", "Structured input: cmd")
                            ),
                            toolName = "run_command",
                            toolInput = "Structured input: cmd",
                            isSubagent = true,
                            subagentType = "Plan"
                        )
                    )
                ),
                observedAtMillis = 200L
            )
        )

        val rows = s.state.value.detail?.activity.orEmpty()
        assertEquals(listOf("realtime:agent-1:seq:11:0:tool_start"), rows.map { it.id })
        assertEquals("tool_start", rows.single().type)
        assertEquals("Run command", rows.single().title)
        assertEquals("Structured input: cmd", rows.single().body)
        assertEquals(listOf("Tool" to "run_command", "Input" to "Structured input: cmd"), rows.single().details)
        assertEquals("run_command", rows.single().toolName)
        assertEquals(true, rows.single().isSubagent)
        assertEquals("Plan", rows.single().subagentType)
        assertEquals("1782367200000", rows.single().timestamp)
    }
}
