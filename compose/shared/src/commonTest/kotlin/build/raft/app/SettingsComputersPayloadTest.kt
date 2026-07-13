package build.raft.app

import build.raft.app.data.ScreenLoadState
import build.raft.app.data.EmptyReason
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerWorkspaceSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.ScreenError
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsComputersPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun computerDetailAgentsDefaultToReadOnlyForLegacyPayloads() {
        val payload =
            json.decodeFromString(
                KuiklySettingsComputerDetailPayload.serializer(),
                """
                {
                  "instanceId": "computer-detail",
                  "agents": [
                    {"id": "agent-1", "name": "Settings Agent"}
                  ]
                }
                """.trimIndent()
            )

        assertEquals(false, payload.canOpenAgents)
        assertEquals("agent-1", payload.agents.single().id)
    }

    @Test
    fun computersListStatusPresentationDistinguishesPageLoadingErrorPermissionAndEmpty() {
        val loadingState: ScreenLoadState<SettingsComputersSnapshot> = ScreenLoadState.Loading
        val loading = loadingState.toComputersPayload(refreshing = false, timeContext = null)
        assertEquals("loading", loading.status)
        assertEquals(true, loading.isPageLevelLoading())
        assertEquals("Loading computers", loading.pageLevelLoadingMessage())

        val errorState: ScreenLoadState<SettingsComputersSnapshot> =
            ScreenLoadState.Error(ScreenError(code = "server_unavailable", retryable = true))
        val error = errorState.toComputersPayload(refreshing = false, timeContext = null)
        assertEquals("error", error.status)
        assertEquals(false, error.isPageLevelLoading())
        assertEquals(SlockLucideIconName.TriangleAlert, error.statusPresentation().iconName)
        assertEquals("Unable to load computers", error.statusPresentation().title)

        val noPermissionState: ScreenLoadState<SettingsComputersSnapshot> =
            ScreenLoadState.NoPermission(PermissionBlocker(code = "server_forbidden"))
        val noPermission = noPermissionState.toComputersPayload(refreshing = false, timeContext = null)
        assertEquals("blocked", noPermission.status)
        assertEquals(SlockLucideIconName.Lock, noPermission.statusPresentation().iconName)

        val emptyState: ScreenLoadState<SettingsComputersSnapshot> =
            ScreenLoadState.Empty(EmptyReason.NoSettingsAvailable)
        val empty = emptyState.toComputersPayload(refreshing = false, timeContext = null)
        assertEquals("empty", empty.status)
        assertEquals(SlockLucideIconName.Monitor, empty.statusPresentation().iconName)
    }

    @Test
    fun computerDetailCanOpenAgentsFlagRoundTrips() {
        val payload =
            KuiklySettingsComputerDetailPayload(
                instanceId = "computer-detail",
                agents =
                    listOf(
                        KuiklySettingsAgentItem(
                            id = "agent-1",
                            name = "Settings Agent",
                            runtimeLabel = "Codex CLI",
                            avatarWireValue = "pixel:paint",
                            activityLabel = "Working"
                        )
                    ),
                canOpenAgents = true
            )

        val decoded =
            json.decodeFromString(
                KuiklySettingsComputerDetailPayload.serializer(),
                json.encodeToString(payload)
            )

        assertEquals(true, decoded.canOpenAgents)
        assertEquals("Codex CLI", decoded.agents.single().runtimeLabel)
        assertEquals("pixel:paint", decoded.agents.single().avatarWireValue)
        assertEquals("Working", decoded.agents.single().activityLabel)
    }

    @Test
    fun computerDetailPayloadMapperSurfacesAgentAvatarRuntimeAndActivity() {
        val snapshot =
            SettingsComputerDetailSnapshot(
                computer =
                    SettingsComputerSnapshot(
                        id = "machine-1",
                        name = "Builder Mac",
                        daemonVersion = "0.53.0",
                        online = true
                    ),
                workspaces = emptyList(),
                agents =
                    listOf(
                        SettingsComputerAgentSnapshot(
                            id = "agent-1",
                            name = "Settings Agent",
                            statusLabel = "working",
                            runtime = "codex",
                            avatarUrl = "pixel:robot"
                        )
                    ),
                sections = emptyList()
            )

        val payload = ScreenLoadState.Content(snapshot).toComputerDetailPayload("machine-1", null)
        val agent = payload.agents.single()

        assertEquals("Codex CLI", agent.runtimeLabel)
        assertEquals("pixel:robot", agent.avatarWireValue)
        assertEquals("Working", agent.activityLabel)
    }

    @Test
    fun computerDetailPayloadMapperSurfacesDescriptionAndReadableWorkspaceRows() {
        val snapshot =
            SettingsComputerDetailSnapshot(
                computer =
                    SettingsComputerSnapshot(
                        id = "machine-1",
                        name = "Builder Mac",
                        daemonVersion = "0.53.0",
                        online = true,
                        description = "Build farm runner"
                    ),
                workspaces =
                    listOf(
                        SettingsComputerWorkspaceSnapshot(
                            id = "027ca19e-d020-4054-bed5-0f9173ea5891",
                            name = "Pi QA Perf Profiler",
                            path = "~/.slock/agents/027ca19e-d020-4054-bed5-0f9173ea5891/",
                            statusLabel = "active",
                            lastActiveLabel = "2026-05-07T01:01:00Z",
                            totalSizeBytes = 4096L,
                            fileCount = 12
                        )
                    ),
                workspacesScanned = true,
                agents = emptyList(),
                sections = emptyList()
            )

        val payload = ScreenLoadState.Content(snapshot).toComputerDetailPayload("machine-1", null)
        val workspace = payload.workspaces.single()

        assertEquals("Build farm runner", payload.computer.description)
        assertEquals(true, payload.workspacesScanned)
        assertEquals("Pi QA Perf Profiler", workspace.name)
        assertEquals("~/.slock/agents/027ca19e-d020-4054-bed5-0f9173ea5891/", workspace.path)
        assertEquals("4.0 KB", workspace.totalSizeLabel)
        assertEquals(12, workspace.fileCount)
        assertEquals("12 files", workspace.fileCountLabel)
    }

    @Test
    fun computerDetailCanManageMachinesFlagRoundTrips() {
        // #78: thread canManageMachines through the page payload so the
        // ComputerDetail page knows whether to render the rename / delete
        // / rotate-key actions. Defaults to false (matches the existing
        // canAddComputer hardcode in SettingsParser) so a non-admin viewer
        // doesn't see the actions. The server still enforces 403 on the
        // actual API endpoints, so a permissive client cannot bypass.
        val withPermission =
            KuiklySettingsComputerDetailPayload(
                instanceId = "computer-detail",
                canManageMachines = true
            )
        val withoutPermission =
            KuiklySettingsComputerDetailPayload(
                instanceId = "computer-detail",
                canManageMachines = false
            )

        val withDecoded =
            json.decodeFromString(
                KuiklySettingsComputerDetailPayload.serializer(),
                json.encodeToString(withPermission)
            )
        val withoutDecoded =
            json.decodeFromString(
                KuiklySettingsComputerDetailPayload.serializer(),
                json.encodeToString(withoutPermission)
            )

        assertEquals(true, withDecoded.canManageMachines)
        assertEquals(false, withoutDecoded.canManageMachines)
    }

    @Test
    fun computerDetailLoadStateCarriesSemanticStatusForLocalizedRendering() {
        val errorState: ScreenLoadState<SettingsComputerDetailSnapshot> =
            ScreenLoadState.Error(ScreenError(code = "server_unavailable", retryable = true))
        val blockedState: ScreenLoadState<SettingsComputerDetailSnapshot> =
            ScreenLoadState.NoPermission(PermissionBlocker(code = "server_forbidden"))
        val emptyState: ScreenLoadState<SettingsComputerDetailSnapshot> =
            ScreenLoadState.Empty(EmptyReason.NoSettingsAvailable)
        val loadingState: ScreenLoadState<SettingsComputerDetailSnapshot> = ScreenLoadState.Loading

        val error = errorState.toComputerDetailPayload("machine-1", null)
        val blocked = blockedState.toComputerDetailPayload("machine-1", null)
        val empty = emptyState.toComputerDetailPayload("machine-1", null)
        val loading = loadingState.toComputerDetailPayload("machine-1", null)

        assertEquals("error", error.status)
        assertEquals("blocked", blocked.status)
        assertEquals("empty", empty.status)
        assertEquals("loading", loading.status)
    }

    @Test
    fun computerDetailPayloadMapperThreadsCanManageMachines() {
        val snapshot =
            SettingsComputerDetailSnapshot(
                computer =
                    SettingsComputerSnapshot(
                        id = "machine-1",
                        name = "Builder Mac",
                        daemonVersion = "0.53.0",
                        online = true
                    ),
                workspaces = emptyList(),
                agents = emptyList(),
                sections = emptyList(),
                canManageMachines = true
            )

        val payload = ScreenLoadState.Content(snapshot).toComputerDetailPayload("machine-1", null)

        assertEquals(true, payload.canManageMachines)
    }

    @Test
    fun computerItemSurfacesRuntimeAndCreatedFields() {
        // The new upstream read-model fields (statusVersion / apiKeyPrefix /
        // runtimes / lastHeartbeat / createdAt) must round-trip through the
        // page payload so the React-aligned Settings ComputerDetail page can
        // render them — see #74 task description and machineStore Machine
        // interface.
        val payload =
            KuiklySettingsComputerDetailPayload(
                instanceId = "computer-detail",
                computer =
                    KuiklySettingsComputerItem(
                        id = "machine-1",
                        name = "Maria Laptop",
                        daemonVersion = "0.53.0",
                        online = true,
                        hostname = "maria.local",
                        platform = "darwin",
                        lastSeenLabel = "2026-06-14T02:00:00.000Z",
                        runtimes = listOf("claude", "codex"),
                        createdAt = "2026-05-01T00:00:00.000Z",
                        apiKeyPrefix = "sk_machine_abc",
                        statusVersion = 7L
                    )
            )

        val decoded =
            json.decodeFromString(
                KuiklySettingsComputerDetailPayload.serializer(),
                json.encodeToString(payload)
            )

        val c = decoded.computer
        assertEquals(listOf("claude", "codex"), c.runtimes)
        assertEquals("2026-05-01T00:00:00.000Z", c.createdAt)
        assertEquals("sk_machine_abc", c.apiKeyPrefix)
        assertEquals(7L, c.statusVersion)
        assertEquals("2026-06-14T02:00:00.000Z", c.lastSeenLabel)
    }

    @Test
    fun computerRuntimePillsUseSharedDisplayNamesAndDoNotDropPi() {
        val pills = settingsComputerRuntimePills(listOf("claude", "codex", "pi"))

        assertEquals(true, pills.first { it.id == "claude" }.detected)
        assertEquals("Claude Code", pills.first { it.id == "claude" }.label)
        assertEquals(true, pills.first { it.id == "codex" }.detected)
        assertEquals("Codex CLI", pills.first { it.id == "codex" }.label)
        assertEquals(true, pills.first { it.id == "pi" }.detected)
        assertEquals("Pi", pills.first { it.id == "pi" }.label)
        assertEquals(false, pills.first { it.id == "kimi-sdk" }.detected)
        assertEquals("Kimi Code (not installed)", pills.first { it.id == "kimi-sdk" }.missingLabel)
        assertEquals("Kimi CLI (deprecated) (not installed)", pills.first { it.id == "kimi" }.missingLabel)
    }

    @Test
    fun computerRuntimePillsAcceptLegacyDisplayNamesFromVisualFixtures() {
        val pills = settingsComputerRuntimePills(listOf("Claude Code", "Codex CLI", "Pi"))

        assertEquals(true, pills.first { it.id == "claude" }.detected)
        assertEquals(true, pills.first { it.id == "codex" }.detected)
        assertEquals(true, pills.first { it.id == "pi" }.detected)
    }

    @Test
    fun computersListPayloadRoundTripsRunVersionForOnlineAndOfflineMachines() {
        // #77/#268: list card renders name + status + run-kind version line,
        // matching React MobileComputersPanel.tsx + Sidebar.tsx:1248-1290. The
        // payload must carry daemonVersion/computerVersion on the per-item level
        // so the page can fall back to "daemon/computer offline" when blank, without
        // pulling in fields the React list doesn't show (createdAt /
        // runtimes / lastHeartbeat / hostname / os live on the detail
        // payload, see #74 commit cf01a0a4).
        val payload =
            KuiklySettingsComputersPayload(
                instanceId = "computers-list",
                latestDaemonVersion = "0.53.0",
                latestComputerVersion = "0.0.62",
                computers =
                    listOf(
                        KuiklySettingsComputerItem(
                            id = "machine-1",
                            name = "Maria Laptop",
                            daemonVersion = "0.53.0",
                            computerVersion = "0.0.61",
                            isComputer = true,
                            online = true
                        ),
                        KuiklySettingsComputerItem(
                            id = "machine-2",
                            name = "Build host",
                            daemonVersion = "",
                            online = false
                        )
                    )
            )

        val decoded =
            json.decodeFromString(
                KuiklySettingsComputersPayload.serializer(),
                json.encodeToString(payload)
            )

        assertEquals(2, decoded.computers.size)
        val (online, offline) = decoded.computers
        assertEquals("0.53.0", online.daemonVersion)
        assertEquals("0.0.61", online.computerVersion)
        assertEquals(true, online.isComputer)
        assertEquals("0.0.62", decoded.latestComputerVersion)
        assertEquals(true, online.online)
        assertEquals("", offline.daemonVersion)
        assertEquals(false, offline.online)
        // List payload should NOT carry detail-only fields even if the data
        // class definition permits them — we deliberately keep the list
        // contract minimal so the page can't accidentally fall through to a
        // detail-style render.
        assertEquals(null, online.hostname)
        assertEquals(null, online.platform)
        assertEquals(null, online.createdAt)
        assertEquals(null, online.apiKeyPrefix)
    }
}
