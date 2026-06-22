package ai.slock.compose.data

import ai.slock.compose.MembersAgentDetailTab
import ai.slock.compose.MembersAgentLifecycleAction
import ai.slock.compose.model.AgentDetailSnapshot
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedAgentDetailStoreTest {
    private fun store() = SharedAgentDetailStore(logger = {})

    private val testMemberId = MemberId("agent-1")
    private val testServerId = ServerId("server-1")
    private val testDetail = AgentDetailSnapshot(
        serverId = testServerId,
        agentId = testMemberId,
        name = "Cindy",
        handle = "cindy",
        status = null,
        activity = null,
        activityDetail = null,
        creatorId = "artin",
        creatorLabel = "artin",
        creatorType = "human",
        machineId = "machine-1",
        machineName = "machine-1",
        machineAssignment = null,
        daemonVersion = null,
        runtime = null,
        model = null,
        reasoning = null,
        executionMode = null,
        envVarNames = emptyList(),
        skills = emptyList(),
        deletedAt = null,
        summary = null
    )

    @Test
    fun initialStateIsEmpty() {
        val state = store().state.value
        assertNull(state.agentId)
        assertNull(state.detail)
        assertEquals(SharedAgentDetailLoadStatus.Idle, state.detailLoadStatus)
        assertEquals(MembersAgentDetailTab.Profile, state.selectedTab)
        assertFalse(state.managementMenuOpen)
        assertNull(state.managementMenuAnchor)
        assertNull(state.lifecycleActionInFlight)
        assertNull(state.diagnosticActionInFlight)
        assertNull(state.pendingConfirmation)
        assertFalse(state.displayNameEditing)
        assertFalse(state.roleEditing)
    }

    @Test
    fun selectMemberSetsAgentId() = runTest {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        assertEquals(testMemberId, s.state.value.agentId)
    }

    @Test
    fun deselectMemberResetsState() = runTest {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectMember(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetailLoading(testMemberId))
        s.dispatch(SharedAgentDetailIntent.SetDetail(testMemberId, testDetail))
        s.dispatch(SharedAgentDetailIntent.SelectTab(MembersAgentDetailTab.Permissions))
        s.dispatch(SharedAgentDetailIntent.DeselectMember)
        val state = s.state.value
        assertNull(state.agentId)
        assertNull(state.detail)
        assertEquals(SharedAgentDetailLoadStatus.Idle, state.detailLoadStatus)
        assertEquals(MembersAgentDetailTab.Profile, state.selectedTab)
    }

    @Test
    fun detailLoadingTransitionsAreTracked() = runTest {
        val s = store()
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
    fun tabSelectionSwitches() = runTest {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.SelectTab(MembersAgentDetailTab.Permissions))
        assertEquals(MembersAgentDetailTab.Permissions, s.state.value.selectedTab)
        s.dispatch(SharedAgentDetailIntent.SelectTab(MembersAgentDetailTab.Activity))
        assertEquals(MembersAgentDetailTab.Activity, s.state.value.selectedTab)
    }

    @Test
    fun managementMenuLifecycle() = runTest {
        val s = store()
        val anchor = SharedAgentDetailMenuAnchor(x = 100f, y = 200f)
        s.dispatch(SharedAgentDetailIntent.OpenManagementMenu(anchor))
        assertTrue(s.state.value.managementMenuOpen)
        assertEquals(anchor, s.state.value.managementMenuAnchor)
        s.dispatch(SharedAgentDetailIntent.CloseManagementMenu)
        assertFalse(s.state.value.managementMenuOpen)
        assertNull(s.state.value.managementMenuAnchor)
    }

    @Test
    fun lifecycleActionInFlightTransitions() = runTest {
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
    fun lifecycleActionErrorRecordsErrorCode() = runTest {
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
    fun startLifecycleClearsPendingConfirmation() = runTest {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.ShowConfirmation(MembersAgentLifecycleAction.ResetFull))
        assertEquals(MembersAgentLifecycleAction.ResetFull, s.state.value.pendingConfirmation)
        s.dispatch(SharedAgentDetailIntent.StartLifecycleAction("lifecycle:reset_full"))
        assertNull(s.state.value.pendingConfirmation)
    }

    @Test
    fun diagnosticActionInFlightTransitions() = runTest {
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
    fun displayNameEditLifecycle() = runTest {
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
        s.dispatch(SharedAgentDetailIntent.FinishSaveDisplayName)
        assertFalse(s.state.value.displayNameSaving)
        s.dispatch(SharedAgentDetailIntent.CancelEditDisplayName)
        assertFalse(s.state.value.displayNameEditing)
        assertNull(s.state.value.displayNameDraft)
        assertNull(s.state.value.displayNameError)
    }

    @Test
    fun roleEditLifecycle() = runTest {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.BeginEditRole("description one"))
        assertTrue(s.state.value.roleEditing)
        s.dispatch(SharedAgentDetailIntent.UpdateRoleDraft("description two"))
        s.dispatch(SharedAgentDetailIntent.StartSaveRole("description two"))
        s.dispatch(SharedAgentDetailIntent.SetRoleError("role_too_long"))
        s.dispatch(SharedAgentDetailIntent.FinishSaveRole)
        s.dispatch(SharedAgentDetailIntent.CancelEditRole)
        assertFalse(s.state.value.roleEditing)
        assertNull(s.state.value.roleDraft)
        assertNull(s.state.value.roleError)
    }

    @Test
    fun confirmationLifecycle() = runTest {
        val s = store()
        s.dispatch(SharedAgentDetailIntent.ShowConfirmation(MembersAgentLifecycleAction.Delete))
        assertEquals(MembersAgentLifecycleAction.Delete, s.state.value.pendingConfirmation)
        s.dispatch(SharedAgentDetailIntent.DismissConfirmation)
        assertNull(s.state.value.pendingConfirmation)
    }

    @Test
    fun actionResultReportingAndClearing() = runTest {
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
}
