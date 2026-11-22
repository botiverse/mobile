package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.data.AgentMigrationLog
import build.raft.app.data.AgentMigrationLogEntry
import build.raft.app.data.AgentMigrationLogFailureKind
import build.raft.app.data.AgentMigrationLogFilters
import build.raft.app.data.AgentMigrationLogInitiator
import build.raft.app.data.AgentMigrationLogLoad
import build.raft.app.data.AgentMigrationLogMachine
import build.raft.app.data.AgentMigrationLogOutcome
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext

const val AGENT_MIGRATION_LOG_VISUAL_PAGE = "compose_agent_migration_log_visual"

/** The params the cases open with: the view, and the one agent it is filtered to. */
const val AGENT_MIGRATION_LOG_VISUAL_VIEW = "view"
const val AGENT_MIGRATION_LOG_VISUAL_AGENT = "agent"

/**
 * The agent migration log over the web case's own log (slock
 * packages/web/visual-testing/MigrationLogCases.tsx): eight moves of three agents, a computer renamed
 * since (studio-mac, now mac-studio-2) and one no longer on the server (old-runner). No network.
 */
@Page(AGENT_MIGRATION_LOG_VISUAL_PAGE, supportInLocal = true)
internal class AgentMigrationLogVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AgentMigrationLogVisualContent() }
    }
}

private class VisualMigrationAgent(val name: String, val face: String, val machineId: String)

private val VisualMigrationAgents =
    mapOf(
        "visual-mig-kirby" to VisualMigrationAgent("Kirby", "pixel:robot", "mig-hx1"),
        "visual-mig-atlas" to VisualMigrationAgent("Atlas", "pixel:fox", "mig-studio"),
        "visual-mig-mira" to VisualMigrationAgent("Mira", "pixel:catbot", "mig-oak"),
    )

private val VisualMigrationMachines =
    listOf(
        AgentMigrationLogMachine("mig-hx1", "hetzner-ax42-1", hostProvider = "hetzner"),
        AgentMigrationLogMachine("mig-hx2", "hetzner-ax42-2", hostProvider = "hetzner"),
        AgentMigrationLogMachine("mig-studio", "mac-studio-2", deviceClass = "desktop"),
        AgentMigrationLogMachine("mig-oak", "oak-linux", deviceClass = "server"),
    )

private fun visualMove(
    ref: Int,
    agent: String,
    from: Pair<String, String>,
    to: Pair<String, String>?,
    startedAt: String,
    endedAt: String?,
    outcome: AgentMigrationLogOutcome = AgentMigrationLogOutcome.Completed,
) = AgentMigrationLogEntry(
    migrationRef = "visual-mig-$ref",
    agentId = "visual-mig-$agent",
    sourceMachineId = from.first,
    sourceMachineName = from.second,
    toCloud = to == null,
    targetMachineId = to?.first,
    targetMachineName = to?.second,
    initiatedBy = AgentMigrationLogInitiator("visual-owner", "artin", "artin"),
    outcome = outcome,
    startedAt = startedAt,
    endedAt = endedAt,
    failureKind = if (outcome == AgentMigrationLogOutcome.Failed) AgentMigrationLogFailureKind.Network else null,
)

private val VisualMigrationLog: AgentMigrationLog =
    run {
        val hx1 = "mig-hx1" to "hetzner-ax42-1"
        val hx2 = "mig-hx2" to "hetzner-ax42-2"
        val studioThen = "mig-studio" to "studio-mac"
        val studio = "mig-studio" to "mac-studio-2"
        val oak = "mig-oak" to "oak-linux"
        val old = "mig-old" to "old-runner"
        AgentMigrationLog(
            listOf(
                visualMove(1, "kirby", old, studioThen, "2026-10-02T09:12:00.000Z", "2026-10-02T09:15:30.000Z"),
                visualMove(2, "atlas", old, hx2, "2026-10-05T14:40:00.000Z", "2026-10-05T14:46:00.000Z"),
                visualMove(3, "kirby", studioThen, hx1, "2026-10-11T08:03:00.000Z", "2026-10-11T08:05:00.000Z", AgentMigrationLogOutcome.Failed),
                visualMove(4, "kirby", studioThen, hx1, "2026-10-11T08:20:00.000Z", "2026-10-11T08:24:15.000Z"),
                visualMove(5, "mira", hx2, null, "2026-10-19T16:30:00.000Z", "2026-10-19T16:31:30.000Z", AgentMigrationLogOutcome.Canceled),
                visualMove(6, "atlas", hx2, studio, "2026-10-27T11:45:00.000Z", "2026-10-27T11:50:00.000Z"),
                visualMove(7, "mira", hx2, oak, "2026-11-03T10:05:00.000Z", "2026-11-03T10:08:00.000Z"),
                visualMove(8, "kirby", hx1, oak, "2026-11-18T07:55:00.000Z", null, AgentMigrationLogOutcome.InProgress),
            ),
            truncated = false,
        )
    }

@Composable
private fun AgentMigrationLogVisualContent() {
    val timeContext =
        remember {
            RaftTimeContextProvider(
                RaftTimeContext(
                    localOffsetMinutes = VisualFixtureData.Locale.UTC_OFFSET_MINUTES,
                    todayEpochDay = VisualFixtureData.Locale.TODAY_EPOCH_DAY.toLong(),
                    nowEpochMillis = VisualFixtureData.Locale.NOW_EPOCH_MILLIS,
                ),
            )
        }
    val params = (LocalActivity.current.getPager() as Pager).pageData.params
    var state by
        remember {
            mutableStateOf(
                AgentMigrationLogState(
                    load = AgentMigrationLogLoad.Loaded(VisualMigrationLog),
                    view = if (params.optString(AGENT_MIGRATION_LOG_VISUAL_VIEW) == "table") AgentMigrationLogView.Table else AgentMigrationLogView.Chart,
                    filters = AgentMigrationLogFilters(agents = listOfNotNull(params.optString(AGENT_MIGRATION_LOG_VISUAL_AGENT).takeIf { it.isNotBlank() })),
                ),
            )
        }
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    CompositionLocalProvider(LocalRaftTimeContextProvider provides timeContext) {
        AgentMigrationLogScreen(
            state = state,
            machines = VisualMigrationMachines,
            agents = VisualMigrationAgents.mapValues { (id, agent) -> AgentMigrationLogAgent(id, agent.name, agent.machineId) },
            wide = windowWidth.value >= DualPaneMinimumWidthDp,
            onIntent = { intent ->
                state =
                    when (intent) {
                        is AgentMigrationLogIntent.SetFilters -> state.copy(filters = intent.filters)
                        is AgentMigrationLogIntent.SetView -> state.copy(view = intent.view)
                        is AgentMigrationLogIntent.Search -> state.copy(query = intent.query)
                        else -> state
                    }
            },
            onOpenAgent = {},
            onOpenPerson = {},
            onCopy = {},
            onBack = {},
            avatar = { id, size ->
                RaftAppAvatar(
                    kind = raftAvatarKind(MemberKind.Agent),
                    avatarWireValue = VisualMigrationAgents[id]?.face,
                    label = "",
                    avatarSize = size,
                )
            },
            nowMillis = { VisualFixtureData.Locale.NOW_EPOCH_MILLIS },
        )
    }
}
