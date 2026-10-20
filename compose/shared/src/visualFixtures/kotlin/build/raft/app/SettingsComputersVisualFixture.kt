package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerWorkspaceSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource

const val SETTINGS_COMPUTERS_VISUAL_PAGE = "compose_settings_computers_visual"

/**
 * Native Settings > Computers with seeded store states and no network: `fixtureKey` is `list`
 * (mixed online / offline / update-available rows), `empty`, `error` or `detail` (agents and a
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
                trailingContent = { RaftComputersRefreshAction(refreshing = false, onRefresh = {}) },
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
                title = stringResource(MR.strings.settings_computers_title),
                subtitle = "",
                onBack = {},
                trailingContent = { RaftComputersRefreshAction(refreshing = false, onRefresh = {}) },
            ) {
                RaftComputersTheme {
                    RaftSettingsComputersContent(
                        model = raftComputersListModel(state),
                        onRetry = {},
                        onAddComputer = {},
                        onOpenComputer = {},
                    )
                }
            }
        }
    }
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
                    platform = "macOS 15.1",
                    runtimes = listOf("claude", "codex"),
                    createdAt = "2026-08-01T09:00:00Z",
                ),
                SettingsComputerSnapshot(
                    id = "machine-build",
                    name = "build-box",
                    daemonVersion = "0.64.1",
                    online = true,
                    hostname = "build-01",
                    platform = "Ubuntu 24.04",
                    runtimes = listOf("claude"),
                ),
                SettingsComputerSnapshot(
                    id = "machine-laptop",
                    name = "Travel laptop",
                    daemonVersion = "0.65.0",
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
                SettingsComputerAgentSnapshot(id = "agent-jony", name = "Jony", runtime = "claude", description = "Designs the app."),
                SettingsComputerAgentSnapshot(id = "agent-wug", name = "Wug", runtime = "codex"),
            ),
        sections = emptyList(),
        workspacesScanned = true,
        canManageMachines = true,
    )
