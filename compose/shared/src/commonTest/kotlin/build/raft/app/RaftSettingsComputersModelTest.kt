package build.raft.app

import build.raft.app.data.COMPUTER_ACTION_DELETE
import build.raft.app.data.COMPUTER_ACTION_RENAME
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.SettingsSectionErrorSnapshot
import build.raft.app.data.sameMachinePeerNames
import build.raft.app.data.sameMachinePeers
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

    @Test
    fun computerAgentRowShowsTheLiveStatus() {
        // SSOT audit: the row reads the members store's live activity, like Home.
        val agent = SettingsComputerAgentSnapshot(id = "agent-1", name = "Lagoon", statusLabel = "active")
        val working = raftAgentStatusIndicatorState(
            build.raft.app.model.AgentStatus.Working, build.raft.app.model.Presence.Active, external = false,
        )
        assertEquals(working, raftComputerAgentStatusIndicator(agent, working))
    }

    @Test
    fun computerAgentRowIsNeutralUntilTheLiveStatusIsObserved() {
        // Same agent, lifecycle status "active"; only the live observation is missing. The
        // lifecycle column used to paint Online green (and "inactive" grey Offline) here.
        val agent = SettingsComputerAgentSnapshot(id = "agent-1", name = "Lagoon", statusLabel = "active")
        assertNull(raftComputerAgentStatusIndicator(agent, live = null))
        assertNull(raftComputerAgentStatusIndicator(agent.copy(statusLabel = "inactive"), live = null))
    }

    @Test
    fun runtimeChipsListEveryRuntimeAComputerCanRunAndWhyOneIsMissing() {
        val chips = raftComputerRuntimeChips(listOf("claude", "builtin", "kimi-sdk", "kimi", "pi"), build.raft.app.data.BundledRuntimeCatalog)
        // The web's display options: supported and not deprecated, in catalog order.
        assertEquals(
            listOf("claude", "codex", "grok", "builtin", "antigravity", "kimi-sdk", "cursor", "opencode", "pi", "qwen", "qoder", "dsh"),
            chips.map { it.id },
        )
        // slock #15551: a chip names the runtime only, whatever the catalog's experimental flag.
        assertEquals("Qwen Code", chips.single { it.id == "qwen" }.name)
        val byId = chips.associateBy { it.id }
        assertEquals("Claude Code", byId.getValue("claude").name)
        assertEquals(RaftComputerRuntimeAvailability.Detected, byId.getValue("claude").availability)
        assertEquals(RaftComputerRuntimeAvailability.NotInstalled, byId.getValue("codex").availability)
        // Built-in Pi and Kimi Code ship inside the Computer: missing means it predates them.
        assertEquals(RaftComputerRuntimeAvailability.Detected, byId.getValue("kimi-sdk").availability)
        assertEquals(RaftComputerRuntimeAvailability.Detected, byId.getValue("builtin").availability)
        assertEquals(
            RaftComputerRuntimeAvailability.UpdateComputer,
            raftComputerRuntimeChips(emptyList(), build.raft.app.data.BundledRuntimeCatalog).first { it.id == "builtin" }.availability,
        )
    }

    @Test
    fun sameMachinePeersMatchGroupInListOrderAndHideWhenAloneOrFlagOff() {
        val alpha = computer("a", "Alpha").copy(sameMachineGroup = "machine-1")
        val beta = computer("b", "Beta").copy(sameMachineGroup = "machine-1")
        val alone = computer("c", "Alone").copy(sameMachineGroup = null)
        val flagOff = computer("d", "FlagOff")
        val delta = computer("e", "Delta").copy(sameMachineGroup = "machine-1")
        val otherGroup = computer("f", "Other").copy(sameMachineGroup = "machine-2")
        val blankGroup = computer("g", "Blank").copy(sameMachineGroup = "   ")
        val blankNamePeer = computer("h", "  ").copy(sameMachineGroup = "machine-1")
        val machines = listOf(alpha, beta, alone, flagOff, delta, otherGroup, blankGroup, blankNamePeer)

        assertEquals(listOf("b", "e", "h"), sameMachinePeers(alpha, machines).map { it.id })
        assertEquals(listOf("Beta", "Delta", "h"), sameMachinePeerNames(alpha, machines))
        assertEquals(emptyList(), sameMachinePeers(alone, machines))
        assertEquals(emptyList(), sameMachinePeers(flagOff, machines))
        assertEquals(emptyList(), sameMachinePeers(otherGroup, machines))
        assertEquals(emptyList(), sameMachinePeers(blankGroup, machines))

        assertEquals("machine-1", alpha.toRaftComputerRow().sameMachineGroup)
        assertNull(alone.toRaftComputerRow().sameMachineGroup)
        assertNull(blankGroup.toRaftComputerRow().sameMachineGroup)

        val detailSnapshot =
            detail("a").copy(
                computer = alpha,
                sameMachinePeerNames = sameMachinePeerNames(alpha, listOf(alpha, beta, delta)),
            )
        val detailModel =
            assertIs<RaftComputerDetailModel.Content>(
                raftComputerDetailModel(
                    ScreenLoadState.Content(detailSnapshot),
                    machineId = "a",
                    targetServerId = "server-1",
                    currentServerId = "server-1",
                )
            )
        assertEquals(listOf("Beta", "Delta"), detailModel.sameMachinePeerNames)
    }
}
