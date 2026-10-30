package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerWorkspaceSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.model.RaftTimeContext

const val SETTINGS_COMPUTERS_VISUAL_PAGE = "compose_settings_computers_visual"

/**
 * Native Settings > Computers with seeded store states and no network: `fixtureKey` is `list`
 * (rack cards: online, upgrade, low disk with many agents, offline; Disk / Memory meters and runtime
 * usage lines: normal, warning, used up, stale, "—"), `empty`, `error` or `detail` (agents and a
 * scanned workspace list). The snapshots go through the production state mapping
 * ([raftComputersListModel], [raftComputerDetailModel]) and content composables; the clock is
 * pinned so relative times are stable.
 */
@Page(SETTINGS_COMPUTERS_VISUAL_PAGE, supportInLocal = true)
internal class SettingsComputersVisualFixture : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsComputersVisualContent() }
    }
}

// 2026-10-20T12:00:00Z
private const val VisualNowEpochMillis = 1_792_497_600_000L
private const val VisualTodayEpochDay = 20_746L

@Composable
private fun SettingsComputersVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val fixtureKey = remember(pager.pageData.params.toString()) { pager.pageData.params.optString("fixtureKey").trim() }
    val timeContext =
        remember {
            RaftTimeContextProvider(
                RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = VisualTodayEpochDay, nowEpochMillis = VisualNowEpochMillis)
            )
        }
    CompositionLocalProvider(LocalRaftTimeContextProvider provides timeContext) {
        if (fixtureKey == "detail") {
            val detail = visualComputerDetail()
            SettingsDetailShell(
                title = detail.computer.name,
                subtitle = "",
                onBack = {},
            ) {
                RaftComputersTheme {
                    RaftSettingsComputerDetailContent(
                        model =
                            raftComputerDetailModel(
                                state = ScreenLoadState.Content(detail),
                                machineId = detail.computer.id,
                                targetServerId = "server-visual",
                                currentServerId = "server-visual",
                            ),
                        actionState = null,
                        onRetry = {},
                        onIntent = {},
                        onOpenAgent = {},
                    )
                }
            }
        } else {
            val state: ScreenLoadState<SettingsComputersSnapshot> =
                when (fixtureKey) {
                    "empty" -> ScreenLoadState.Content(SettingsComputersSnapshot(emptyList(), canAddComputer = true))
                    "error" -> ScreenLoadState.Error(ScreenError("server_unavailable", retryable = true))
                    else -> ScreenLoadState.Content(visualComputers())
                }
            SettingsDetailShell(
                title = stringResource(Res.string.settings_computers_title),
                subtitle = "",
                onBack = {},
            ) {
                RaftComputersTheme {
                    RaftSettingsComputersContent(
                        model = raftComputersListModel(state),
                        onRetry = {},
                        onAddComputer = {},
                        onOpenComputer = {},
                        agentsByMachine = visualAgentsByMachine(),
                        usageLoads = visualUsageLoads(),
                    )
                }
            }
        }
    }
}

private fun visualAgentsByMachine(): Map<String, List<RaftComputerCardAgent>> {
    val names = listOf("Wug", "Jony", "Lagoon", "Tenny", "Artea", "Kirby", "Mochi", "Pixel", "Nova", "Orbit", "Quill", "Rune")
    fun agents(machine: String, count: Int) = List(count) { RaftComputerCardAgent("$machine-a$it", names[it % names.size], avatarWireValue = null) }
    return mapOf("machine-studio" to agents("machine-studio", 3), "machine-build" to agents("machine-build", 12), "machine-laptop" to agents("machine-laptop", 2))
}

/** Studio: normal + warning; build-box: used up + stale Kimi; no memory report there ("—"). */
private fun visualUsageLoads(): Map<String, Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>> {
    fun window(ratio: Double?, status: RuntimeAccountUsageWindowStatus = RuntimeAccountUsageWindowStatus.Ok) =
        RuntimeAccountUsageWindow(id = "five_hour", label = "5h", status = status, usedRatio = ratio, resetsAt = null)
    fun loaded(provider: String, window: RuntimeAccountUsageWindow, stale: Boolean = false) =
        RuntimeAccountUsageLoad.Loaded(
            RuntimeAccountUsageRead.Available(
                stale = stale,
                snapshot =
                    RuntimeAccountUsageSnapshot(
                        provider,
                        null,
                        listOf(RuntimeAccountUsageAccount("acct", "Max", null, RuntimeAccountUsageHealth.Ok, listOf(window))),
                    ),
            ),
        )
    return mapOf(
        "machine-studio" to
            mapOf(
                RuntimeAccountUsageProvider.Claude to loaded("claude", window(0.42)),
                RuntimeAccountUsageProvider.Codex to loaded("codex", window(0.86)),
            ),
        "machine-build" to
            mapOf(
                RuntimeAccountUsageProvider.Claude to loaded("claude", window(null, RuntimeAccountUsageWindowStatus.LimitReached)),
                RuntimeAccountUsageProvider.Kimi to loaded("kimi", window(0.3), stale = true),
            ),
    )
}

private fun visualComputers() =
    SettingsComputersSnapshot(
        computers =
            listOf(
                SettingsComputerSnapshot(
                    id = "machine-studio",
                    name = "Studio Mac",
                    daemonVersion = "",
                    computerVersion = "1.4.2",
                    isComputer = true,
                    online = true,
                    hostname = "studio.local",
                    platform = "darwin arm64",
                    runtimes = listOf("claude", "codex", "cursor"),
                    createdAt = "2026-08-01T09:00:00Z",
                    diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 310.0 * 1024 * 1024 * 1024, totalBytes = 994.0 * 1024 * 1024 * 1024),
                    memoryStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 18.0 * 1024 * 1024 * 1024, totalBytes = 64.0 * 1024 * 1024 * 1024),
                    canViewRuntimeUsage = true,
                ),
                SettingsComputerSnapshot(
                    id = "machine-build",
                    name = "build-box",
                    daemonVersion = "",
                    computerVersion = "1.4.0",
                    isComputer = true,
                    upgradeAvailable = true,
                    online = true,
                    hostname = "build-01",
                    platform = "linux x64",
                    osRelease = "Ubuntu 24.04",
                    runtimes = listOf("claude", "kimi-sdk", "opencode"),
                    latestComputerVersion = "1.4.2",
                    diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 6.0 * 1024 * 1024 * 1024, totalBytes = 500.0 * 1024 * 1024 * 1024),
                    canViewRuntimeUsage = true,
                ),
                SettingsComputerSnapshot(
                    id = "machine-laptop",
                    name = "Travel laptop",
                    daemonVersion = "",
                    computerVersion = "1.3.0",
                    isComputer = true,
                    upgradeAvailable = true,
                    online = false,
                    lastHeartbeat = "2026-10-18T12:00:00Z",
                ),
            ),
        canAddComputer = true,
        latestDaemonVersion = "0.65.0",
        latestComputerVersion = "1.4.2",
    )

private fun visualComputerDetail() =
    SettingsComputerDetailSnapshot(
        computer =
            SettingsComputerSnapshot(
                id = "machine-studio",
                name = "Studio Mac",
                daemonVersion = "",
                computerVersion = "1.4.2",
                isComputer = true,
                online = true,
                hostname = "studio.local",
                platform = "macOS 15.1",
                description = "Design and build agents.",
                runtimes = listOf("claude", "codex"),
                createdAt = "2026-08-01T09:00:00Z",
            ),
        workspaces =
            listOf(
                SettingsComputerWorkspaceSnapshot(
                    id = "agent-jony",
                    name = "Jony",
                    path = "~/.slock/agents/agent-jony/",
                    statusLabel = "active",
                    lastActiveLabel = "2026-10-20T10:00:00Z",
                    totalSizeBytes = 12_897_484L,
                    fileCount = 214,
                ),
                SettingsComputerWorkspaceSnapshot(
                    id = "agent-old",
                    name = "agent-old",
                    path = "~/.slock/agents/agent-old/",
                    statusLabel = "orphan",
                    lastActiveLabel = "2026-09-30T12:00:00Z",
                    totalSizeBytes = 1_048_576L,
                    fileCount = 1,
                ),
            ),
        agents =
            listOf(
                SettingsComputerAgentSnapshot(
                    id = "agent-jony",
                    name = "Jony",
                    statusLabel = "active",
                    runtime = "claude",
                    model = "opus",
                    description = "Designs the app.",
                ),
                SettingsComputerAgentSnapshot(id = "agent-wug", name = "Wug", statusLabel = "inactive", runtime = "codex"),
            ),
        sections = emptyList(),
        workspacesScanned = true,
        canManageMachines = true,
        canEditMachine = true,
        canRemoveMachine = true,
    )
