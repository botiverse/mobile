package build.raft.app

import build.raft.app.data.COMPUTER_ACTION_DELETE
import build.raft.app.data.COMPUTER_ACTION_RENAME
import build.raft.app.data.EmptyReason
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.SettingsSectionErrorSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsRoute
import build.raft.app.data.SharedSettingsState
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftSettingsComputersModelTest {
    @Test
    fun loadingAndFailuresMapToTheirOwnStates() {
        assertEquals(RaftComputersListModel.Loading, raftComputersListModel(ScreenLoadState.Loading))

        val error = assertIs<RaftComputersListModel.Failed>(
            raftComputersListModel(ScreenLoadState.Error(ScreenError("server_unavailable", retryable = true)))
        ).failure
        assertFalse(error.noAccess)
        assertTrue(error.retryable)
        assertEquals("server_unavailable", error.code)

        val forbidden = assertIs<RaftComputersListModel.Failed>(
            raftComputersListModel(ScreenLoadState.Error(ScreenError("server_forbidden", retryable = false)))
        ).failure
        assertTrue(forbidden.noAccess)

        val blocked = assertIs<RaftComputersListModel.Failed>(
            raftComputersListModel(ScreenLoadState.NoPermission(PermissionBlocker("server_forbidden")))
        ).failure
        assertTrue(blocked.noAccess)
        assertFalse(blocked.retryable)

        val sessionExpired = assertIs<RaftComputersListModel.Failed>(
            raftComputersListModel(ScreenLoadState.NoPermission(PermissionBlocker("auth_required")))
        ).failure
        assertFalse(sessionExpired.noAccess)
    }

    @Test
    fun emptyListKeepsTheAddComputerGateOfTheSnapshot() {
        assertEquals(
            RaftComputersListModel.Empty(canAddComputer = true),
            raftComputersListModel(ScreenLoadState.Content(SettingsComputersSnapshot(emptyList(), canAddComputer = true))),
        )
        assertEquals(
            RaftComputersListModel.Empty(canAddComputer = false),
            raftComputersListModel(ScreenLoadState.Empty(EmptyReason.NoSettingsAvailable)),
        )
    }

    @Test
    fun contentRowsCarryStatusKindVersionAndHostInServerOrder() {
        val model =
            assertIs<RaftComputersListModel.Content>(
                raftComputersListModel(
                    ScreenLoadState.Content(
                        SettingsComputersSnapshot(
                            computers =
                                listOf(
                                    // A daemon never shows an update, even if a stale flag came along.
                                    computer(
                                        "m-1",
                                        online = false,
                                        daemonVersion = "0.40.0",
                                        lastHeartbeat = "2026-10-19T10:00:00Z",
                                        upgradeAvailable = true,
                                    ),
                                    computer(
                                        "m-2",
                                        online = true,
                                        isComputer = true,
                                        computerVersion = "1.2.0",
                                        hostname = "studio.local",
                                        platform = "darwin",
                                        runtimes = listOf("claude", " codex ", "claude", ""),
                                    ),
                                ),
                            canAddComputer = false,
                            latestDaemonVersion = "0.41.2",
                            latestComputerVersion = "1.2.0",
                        )
                    )
                )
            )

        assertEquals(listOf("m-1", "m-2"), model.rows.map { it.id })
        assertEquals(1, model.onlineCount)
        assertFalse(model.canAddComputer)

        val daemon = model.rows[0]
        assertEquals(RaftComputerKind.Daemon, daemon.kind)
        assertEquals("0.40.0", daemon.version)
        assertFalse(daemon.updateAvailable)
        assertNull(daemon.hostDetail)
        assertEquals("2026-10-19T10:00:00Z", daemon.lastSeenAt)

        val computer = model.rows[1]
        assertEquals(RaftComputerKind.Computer, computer.kind)
        assertEquals("1.2.0", computer.version)
        assertFalse(computer.updateAvailable)
        assertEquals("studio.local · darwin", computer.hostDetail)
        assertEquals(listOf("claude", "codex"), computer.runtimes)
        assertNull(computer.lastSeenAt)
    }

    @Test
    fun updateAvailableFollowsTheServerPolicyFlagForComputersOnly() {
        // Version strings no longer matter: an older Computer without the flag is not flagged,
        // and the list row and the detail header read the same flag.
        val cases =
            listOf(
                Triple(computer("c-flag", isComputer = true, computerVersion = "1.0.0", upgradeAvailable = true), true, "flagged computer"),
                Triple(computer("c-none", isComputer = true, computerVersion = "0.1.0"), false, "unflagged old computer"),
                Triple(computer("d-flag", daemonVersion = "0.1.0", upgradeAvailable = true), false, "daemon"),
            )
        cases.forEach { (snapshot, expected, label) ->
            assertEquals(expected, snapshot.toRaftComputerRow().updateAvailable, label)
            val detail =
                assertIs<RaftComputerDetailModel.Content>(
                    raftComputerDetailModel(
                        ScreenLoadState.Content(detail(snapshot.id).copy(computer = snapshot)),
                        machineId = snapshot.id,
                        targetServerId = null,
                        currentServerId = "server-1",
                    )
                )
            assertEquals(expected, detail.computer.updateAvailable, "$label (detail)")
        }
    }

    @Test
    fun assignedAgentCountUsesTheLoadedAgentsAndFallsBackToTheServerCount() {
        val agent = SettingsComputerAgentSnapshot(id = "a-1", name = "Jony")
        val withAgents = detail("m-1").copy(agents = listOf(agent), computer = computer("m-1", agentCount = 3))
        assertEquals(1, raftComputerAssignedAgentCount(withAgents))
        assertEquals(
            3,
            raftComputerAssignedAgentCount(withAgents.copy(agents = emptyList(), agentsError = SettingsSectionErrorSnapshot("http_500"))),
        )
        assertEquals(0, raftComputerAssignedAgentCount(detail("m-1")))
    }

    @Test
    fun refreshErrorShowsOnlyOverSettledContent() {
        assertEquals("server_unavailable", raftComputersRefreshErrorCode(hasContent = true, routeErrorCode = "server_unavailable", refreshing = false))
        assertNull(raftComputersRefreshErrorCode(hasContent = false, routeErrorCode = "server_unavailable", refreshing = false))
        assertNull(raftComputersRefreshErrorCode(hasContent = true, routeErrorCode = "server_unavailable", refreshing = true))
        assertNull(raftComputersRefreshErrorCode(hasContent = true, routeErrorCode = " ", refreshing = false))
    }

    @Test
    fun editableTextKeepsUnsavedEditsAcrossABackgroundReload() {
        assertEquals("Renamed", raftComputerEditableTextAfterReload(current = "Studio", baseline = "Studio", incoming = "Renamed"))
        assertEquals("My draft", raftComputerEditableTextAfterReload(current = "My draft", baseline = "Studio", incoming = "Renamed"))
    }

    @Test
    fun editorClosesOnlyWhenItsSaveFinishesWithoutAnError() {
        assertTrue(raftComputerEditorClosesAfterSave(wasSaving = true, saving = false, failed = false))
        assertFalse(raftComputerEditorClosesAfterSave(wasSaving = true, saving = false, failed = true))
        assertFalse(raftComputerEditorClosesAfterSave(wasSaving = false, saving = false, failed = false))
        assertFalse(raftComputerEditorClosesAfterSave(wasSaving = false, saving = true, failed = false))
    }

    @Test
    fun agentIdentityLabelJoinsRuntimeAndModelLikeTheWebMachineAgentRow() {
        assertEquals("Claude Code · Claude Opus", raftComputerAgentIdentityLabel(runtime = "claude", model = "opus"))
        // A model the bundled catalog does not know reads as its raw id (web `agentModelLabel`).
        assertEquals("Claude Code · my-custom-model", raftComputerAgentIdentityLabel(runtime = "claude", model = "my-custom-model"))
        assertEquals("Codex CLI", raftComputerAgentIdentityLabel(runtime = "codex", model = " "))
        assertEquals("opus", raftComputerAgentIdentityLabel(runtime = null, model = "opus"))
        assertNull(raftComputerAgentIdentityLabel(runtime = "", model = null))
    }

    @Test
    fun realtimeFilterAdmitsOnlyMachineEventsOfTheCurrentServer() {
        fun machines(serverId: String?, subjectId: String? = "m-1") =
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.Machines,
                reason = "machine:status",
                serverId = serverId?.let(::ServerId),
                subjectId = subjectId,
            )
        assertTrue(raftComputersRealtimeEventApplies(machines("server-1"), "server-1"))
        assertTrue(raftComputersRealtimeEventApplies(machines(null), "server-1"))
        assertFalse(raftComputersRealtimeEventApplies(machines("server-2"), "server-1"))
        assertFalse(raftComputersRealtimeEventApplies(machines("server-1", subjectId = " "), "server-1"))
        assertFalse(
            raftComputersRealtimeEventApplies(
                RealtimeRefreshRequired(RealtimeRefreshScope.Agents, "agent:created", ServerId("server-1"), subjectId = "a-1"),
                "server-1",
            )
        )
    }

    @Test
    fun blankNameFallsBackToTheMachineId() {
        assertEquals("m-9", computer("m-9", name = "  ").toRaftComputerRow().name)
    }

    @Test
    fun detailRendersOnlyTheRequestedMachineOfTheTargetServer() {
        val content = ScreenLoadState.Content(detail("m-1"))

        val model = assertIs<RaftComputerDetailModel.Content>(
            raftComputerDetailModel(content, machineId = "m-1", targetServerId = "server-1", currentServerId = "server-1")
        )
        assertEquals("m-1", model.computer.id)
        assertEquals("2026-01-02T03:04:05Z", model.createdAt)

        assertEquals(
            RaftComputerDetailModel.Unavailable,
            raftComputerDetailModel(content, machineId = "m-1", targetServerId = "server-2", currentServerId = "server-1"),
        )
        assertEquals(
            RaftComputerDetailModel.Loading,
            raftComputerDetailModel(content, machineId = "m-2", targetServerId = null, currentServerId = "server-1"),
        )
    }

    @Test
    fun detailMapsMissingMachineAndFailures() {
        assertEquals(
            RaftComputerDetailModel.Unavailable,
            raftComputerDetailModel(ScreenLoadState.Empty(EmptyReason.NoSettingsAvailable), "m-1", null, "server-1"),
        )
        assertEquals(
            RaftComputerDetailModel.Unavailable,
            raftComputerDetailModel(
                ScreenLoadState.Error(ScreenError("settings_computer_required", retryable = false)),
                "m-1",
                null,
                "server-1",
            ),
        )
        val offline = assertIs<RaftComputerDetailModel.Failed>(
            raftComputerDetailModel(ScreenLoadState.Error(ScreenError("machine_offline", retryable = true)), "m-1", null, "server-1")
        )
        assertTrue(offline.failure.retryable)
        assertFalse(offline.failure.noAccess)
        assertIs<RaftComputerDetailModel.Failed>(
            raftComputerDetailModel(ScreenLoadState.NoPermission(PermissionBlocker("server_forbidden")), "m-1", null, "server-1")
        ).also { assertTrue(it.failure.noAccess) }
    }

    @Test
    fun deleteCompletesOnlyForAFinishedErrorFreeDeleteOfThisMachine() {
        val popped = SharedSettingsRoute(SharedSettingsDestination.Computers)
        val done =
            SharedSettingsState(
                route = popped,
                actionState = SharedSettingsActionState(COMPUTER_ACTION_DELETE, itemId = "m-1", inProgress = false),
            )
        assertTrue(raftComputerDeleteCompleted(done, "m-1"))
        assertFalse(raftComputerDeleteCompleted(done, "m-2"))
        assertFalse(
            raftComputerDeleteCompleted(
                done.copy(actionState = SharedSettingsActionState(COMPUTER_ACTION_DELETE, itemId = "m-1", inProgress = true)),
                "m-1",
            )
        )
        assertFalse(
            raftComputerDeleteCompleted(
                done.copy(
                    route = SharedSettingsRoute(SharedSettingsDestination.ComputerDetail, "m-1"),
                    actionState =
                        SharedSettingsActionState(
                            COMPUTER_ACTION_DELETE,
                            itemId = "m-1",
                            screenError = ScreenError("computer_has_agents", retryable = false),
                        ),
                ),
                "m-1",
            )
        )
        assertFalse(
            raftComputerDeleteCompleted(
                done.copy(actionState = SharedSettingsActionState(COMPUTER_ACTION_RENAME, itemId = "m-1")),
                "m-1",
            )
        )
    }

    @Test
    fun computerErrorCodesMapToComputersCopy() {
        assertEquals(RaftComputerErrorCopy.Offline, raftComputerErrorCopy("machine_offline"))
        assertEquals(RaftComputerErrorCopy.Replica, raftComputerErrorCopy("machine_offline_replay"))
        assertEquals(RaftComputerErrorCopy.HasAgents, raftComputerErrorCopy("computer_has_agents"))
        assertEquals(RaftComputerErrorCopy.BadRequest, raftComputerErrorCopy("computer_bad_request"))
        assertEquals(RaftComputerErrorCopy.Forbidden, raftComputerErrorCopy("server_forbidden"))
        assertEquals(RaftComputerErrorCopy.Forbidden, raftComputerErrorCopy("server_admin_required"))
        assertEquals(RaftComputerErrorCopy.SessionExpired, raftComputerErrorCopy("auth_required"))
        assertEquals(RaftComputerErrorCopy.ServerUnavailable, raftComputerErrorCopy("server_unavailable"))
        assertEquals(RaftComputerErrorCopy.Generic, raftComputerErrorCopy("http_404"))
        assertEquals(RaftComputerErrorCopy.Generic, raftComputerErrorCopy(null))

        val action =
            SharedSettingsActionState(
                COMPUTER_ACTION_DELETE,
                itemId = "m-1",
                errorCode = "settings_action_failed",
                screenError = ScreenError("computer_has_agents", retryable = false),
            )
        assertEquals("computer_has_agents", action.computerErrorCode)
        assertTrue(action.hasComputerActionError())
    }

    @Test
    fun workspaceSizeUsesTheSharedByteLabel() {
        assertEquals("1.5 MB", raftComputerWorkspaceSize(1_572_864L))
        assertNull(raftComputerWorkspaceSize(null))
        assertNull(raftComputerWorkspaceSize(-1L))
    }

    private fun computer(
        id: String,
        name: String = "Computer $id",
        online: Boolean = true,
        isComputer: Boolean = false,
        daemonVersion: String = "",
        computerVersion: String? = null,
        hostname: String? = null,
        platform: String? = null,
        runtimes: List<String> = emptyList(),
        lastHeartbeat: String? = null,
        upgradeAvailable: Boolean = false,
        agentCount: Int = 0,
    ) = SettingsComputerSnapshot(
        id = id,
        name = name,
        daemonVersion = daemonVersion,
        computerVersion = computerVersion,
        isComputer = isComputer,
        online = online,
        hostname = hostname,
        platform = platform,
        runtimes = runtimes,
        createdAt = "2026-01-02T03:04:05Z",
        lastHeartbeat = lastHeartbeat,
        upgradeAvailable = upgradeAvailable,
        agentCount = agentCount,
    )

    private fun detail(id: String) =
        SettingsComputerDetailSnapshot(
            computer = computer(id),
            workspaces = emptyList(),
            agents = emptyList(),
            sections = emptyList(),
        )
}
