package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentMigrationLogEntry
import build.raft.app.data.AgentMigrationLogFailureKind
import build.raft.app.data.AgentMigrationLogFilters
import build.raft.app.data.AgentMigrationLogInitiator
import build.raft.app.data.AgentMigrationLogMachine
import build.raft.app.data.AgentMigrationLogOutcome
import build.raft.app.data.agentMigrationLogNodes
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What goes with the migration chart (its own pictures are the `screens.agent-migration-log.chart.*`
 * visual cases): a computer's and a route's details (the sheet's content), several selected agents,
 * one of them lit, and the failed moves a filter leaves. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp-xxhdpi")
class AgentMigrationLogChartScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun sidesLight() =
        RaftRobolectricScreenshot.capture("agent.migration.log.sides", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            MigrationFixtureHost {
                Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                    LogCase("A computer's details") { AgentMigrationComputerDetails("hx1", log, names()) }
                    LogCase("A route's details") { AgentMigrationRouteDetails(AgentMigrationChordTarget.Route("studio", "hx1"), log, names()) }
                    LogCase("Two agents, one lit") {
                        val filters = AgentMigrationLogFilters(agents = listOf("kirby", "gone-0123456789"))
                        AgentMigrationLogSide(filters, filters.apply(log, 0), single = null, names(), litAgent = "kirby", onLit = {}, onFilters = {})
                    }
                    LogCase("A filter's failed moves") {
                        val filters = AgentMigrationLogFilters(from = listOf("studio"))
                        AgentMigrationLogSide(filters, filters.apply(log, 0), single = null, names(), litAgent = null, onLit = {}, onFilters = {})
                    }
                }
            }
        }
}

private fun move(
    ref: Int,
    agent: String,
    from: String,
    to: String,
    startedAt: String,
    endedAt: String,
    outcome: AgentMigrationLogOutcome = AgentMigrationLogOutcome.Completed,
) = AgentMigrationLogEntry(
    migrationRef = "mig-$ref",
    agentId = agent,
    sourceMachineId = from,
    sourceMachineName = null,
    toCloud = false,
    targetMachineId = to,
    targetMachineName = null,
    initiatedBy = if (ref % 2 == 0) AgentMigrationLogInitiator("artin", "artin", "artin") else null,
    outcome = outcome,
    startedAt = startedAt,
    endedAt = endedAt,
    failureKind = if (outcome == AgentMigrationLogOutcome.Failed) AgentMigrationLogFailureKind.TooLarge else null,
)

private val log =
    listOf(
        move(1, "kirby", "oak", "studio", "2026-10-02T09:12:00.000Z", "2026-10-02T09:15:30.000Z"),
        move(2, "gone-0123456789", "studio", "hx1", "2026-10-05T14:40:00.000Z", "2026-10-05T14:46:00.000Z"),
        move(3, "kirby", "studio", "hx1", "2026-10-11T08:03:00.000Z", "2026-10-11T08:05:00.000Z", AgentMigrationLogOutcome.Failed),
        move(4, "kirby", "studio", "hx1", "2026-10-11T08:20:00.000Z", "2026-10-11T08:24:15.000Z"),
        move(5, "kirby", "hx1", "oak", "2026-10-12T08:20:00.000Z", "2026-10-12T08:22:00.000Z", AgentMigrationLogOutcome.Failed),
    )

private val nodes =
    agentMigrationLogNodes(
        log,
        listOf(
            AgentMigrationLogMachine("hx1", "hetzner-ax42-1", hostProvider = "hetzner"),
            AgentMigrationLogMachine("studio", "mac-studio-2", deviceClass = "desktop"),
            AgentMigrationLogMachine("oak", "oak-linux", deviceClass = "server"),
        ),
    )

private fun names() =
    AgentMigrationLogNames(
        nodeById = nodes.associateBy { it.id },
        agents =
            listOf(AgentMigrationLogAgent("kirby", "Kirby", "hx1"), AgentMigrationLogAgent("mira", "Mira", "hx1")).associateBy { it.id },
        cloud = "Cloud",
        avatar = { _, size -> Box(Modifier.size(size).background(RaftTheme.colors.fillMuted)) },
        onOpenAgent = {},
        onOpenPerson = {},
    )

@Composable
private fun LogCase(label: String, content: @Composable () -> Unit) {
    RaftText(
        text = label,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        style = RaftTheme.typography.monoMeta,
        color = RaftTheme.colors.foregroundHint,
    )
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() }
}
