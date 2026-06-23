package ai.slock.compose.data

import ai.slock.compose.MembersAgentLifecycleAction
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.Presence
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
        activityDetail: String? = null
    ) = testDetail.copy(
        summary = AgentDetailSummary(
            id = testMemberId.value,
            name = "Agent One",
            status = status,
            activity = activity,
            activityDetail = activityDetail
        )
    )

    private fun activityEvent(
        status: AgentStatus?,
        detail: String? = null,
        serverSeq: Long? = null
    ) = MemberPresenceUpdated(
        memberId = testMemberId,
        presence = status?.presence ?: Presence.Offline,
        kind = MemberKind.Agent,
        agentStatus = status,
        statusLabel = detail,
        serverSeq = serverSeq
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
        s.dispatch(SharedAgentDetailIntent.SetDetailError(testMemberId, "test_error"))
        assertEquals(SharedAgentDetailLoadStatus.Error, s.state.value.detailLoadStatus)
        assertEquals("test_error", s.state.value.detailErrorCode)
        s.dispatch(SharedAgentDetailIntent.SetDetailNoPermission(testMemberId, "forbidden"))
        assertEquals(SharedAgentDetailLoadStatus.NoPermission, s.state.value.detailLoadStatus)
        assertEquals("forbidden", s.state.value.detailErrorCode)
        s.dispatch(SharedAgentDetailIntent.SetDetailEmpty(testMemberId))
        assertEquals(SharedAgentDetailLoadStatus.Empty, s.state.value.detailLoadStatus)
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
    fun lifecycleActionErrorRecordsErrorCode() {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.StartLifecycleAction("lifecycle:start"))
        s.dispatch(
            SharedAgentDetailIntent.FinishLifecycleAction(
                actionKey = "lifecycle:start",
                errorCode = "agent_start_failed"
            )
        )
        assertNull(s.state.value.lifecycleActionInFlight)
        assertEquals("agent_start_failed", s.state.value.lastActionResult?.errorCode)
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
    }
}
