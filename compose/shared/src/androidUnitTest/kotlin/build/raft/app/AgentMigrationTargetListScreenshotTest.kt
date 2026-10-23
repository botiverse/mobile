package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentMigrationState
import build.raft.app.data.ComputerSizeStatus
import build.raft.app.data.RaftProGateDecision
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.model.MemberId
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Move dialog on the phone sheet with its always-open target list (web TargetComputerList,
 * botiverse/slock 424c5203 #11038; raft-design #767 / ChoiceList), light and dark:
 * - three computers: each with its quota line for the agent's runtime (Claude Code; a used-up
 *   5-hour window in the warning colour), its free memory, free disk and agents, and an ⓘ;
 *   one is offline (no memory or disk, "Offline · 2 agents");
 * - twelve computers: the search field above the list, which scrolls inside its frame; and the
 *   same with "tyz" typed, the chosen computer staying first above the matches;
 * - a Computer older than the memory report: no memory part on its line;
 * - the details sheet of an online computer (its usage card, memory and disk) and of an
 *   offline one (how long it has been offline, no memory or disk data).
 * Quota lines come through the real usage store from a fake transport ([PickerUsageApi]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class AgentMigrationTargetListScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun threeLight() = dialog("agent.migration.targets.three", RaftAppAppearance.Light, threeComputers)

    @Test
    fun threeDark() = dialog("agent.migration.targets.three.dark", RaftAppAppearance.Dark, threeComputers)

    @Test
    fun manyLight() = dialog("agent.migration.targets.many", RaftAppAppearance.Light, manyComputers)

    @Test
    fun manyDark() = dialog("agent.migration.targets.many.dark", RaftAppAppearance.Dark, manyComputers)

    @Test
    fun manySearchingLight() = dialog("agent.migration.targets.many.search", RaftAppAppearance.Light, manyComputers, query = "tyz")

    @Test
    fun noMemoryLight() = dialog("agent.migration.targets.no_memory", RaftAppAppearance.Light, olderComputers)

    @Test
    fun noMemoryDark() = dialog("agent.migration.targets.no_memory.dark", RaftAppAppearance.Dark, olderComputers)

    @Test
    fun detailsLight() = details("agent.migration.targets.details", RaftAppAppearance.Light)

    @Test
    fun detailsDark() = details("agent.migration.targets.details.dark", RaftAppAppearance.Dark)

    private fun dialog(name: String, appearance: RaftAppAppearance, computers: List<AgentMigrationComputer>, query: String = "") =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            TargetsFixtureHost {
                val inputs = targetInputs(computers)
                val state = AgentMigrationState(agentId = MemberId("agent-ada"), loaded = true)
                val targets = agentMigrationTargets(inputs)
                val selected = agentMigrationResolvedTarget(null, targets, inputs.hostedAvailable)
                val usage = rememberPickerAccess(targetUsage)
                Column(Modifier.fillMaxSize().background(RaftTheme.colors.foreground.copy(alpha = 0.6f)).padding(top = 76.dp)) {
                    RaftDialogSheetSurface(
                        title = agentMigrationStartDialogTitle(inputs),
                        commitLabel = agentMigrationStartCommitLabel(state, inputs, selected),
                        onCommit = {},
                        onClose = {},
                        modifier = Modifier.fillMaxSize(),
                        commitVariant = RaftButtonVariant.Warning
                    ) {
                        AgentMigrationStartForm(state, inputs, targets, selected, onSelect = {}, usage = usage, initialQuery = query)
                    }
                }
            }
        }

    private fun details(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            TargetsFixtureHost {
                Column(Modifier.fillMaxSize().background(RaftTheme.colors.foreground.copy(alpha = 0.6f))) {
                    AgentMigrationTargetDetailsSurface(
                        computer = threeComputers[1],
                        agentRuntime = "claude",
                        usage = ComputerRuntimeUsageSheetState(studioUsage, loading = false, ComputerRuntimeUsageRefreshStatus.Idle, 0L),
                        timeContext = pickerTimeContext,
                        modifier = Modifier.padding(top = 120.dp)
                    )
                    AgentMigrationTargetDetailsSurface(
                        computer = threeComputers[3],
                        agentRuntime = "claude",
                        usage = null,
                        timeContext = pickerTimeContext,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
}

@Composable
private fun TargetsFixtureHost(content: @Composable () -> Unit) {
    MigrationFixtureHost {
        CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(pickerTimeContext)) { content() }
    }
}

private const val GB = 1024.0 * 1024.0 * 1024.0

private fun size(freeGb: Double, totalGb: Double) = ComputerSizeStatus(freeGb * GB, totalGb * GB)

/** The agent's own computer, then the targets; the viewer attached each target (usage visible). */
private fun target(
    id: String,
    name: String,
    memory: ComputerSizeStatus? = null,
    disk: ComputerSizeStatus? = null,
    agents: Int? = 0,
    online: Boolean = true,
    lastHeartbeat: String? = null
) = AgentMigrationComputer(
    id = id,
    name = name,
    isComputer = true,
    online = online,
    offline = !online,
    lastHeartbeat = lastHeartbeat,
    agentCount = agents,
    diskStatus = disk,
    memoryStatus = memory,
    attachedByCurrentUser = true
)

private const val SOURCE = "m-source"

private val threeComputers =
    listOf(
        target(SOURCE, "artin-debian-agent", agents = 4),
        target("m-1", "Studio Mac", memory = size(12.0, 32.0), disk = size(120.0, 500.0), agents = 3),
        target("m-2", "Lab Box", memory = size(3.4, 16.0), disk = size(48.2, 256.0), agents = 1),
        // Offline for three hours at the fixture's now (2026-10-22T02:10Z).
        target("m-3", "Build Box", memory = size(8.0, 16.0), disk = size(30.0, 120.0), agents = 2, online = false, lastHeartbeat = "2026-10-21T23:10:00.000Z")
    )

private val manyComputers =
    threeComputers +
        listOf(
            target("m-kopic", "KopiC", memory = size(5.1, 16.0), disk = size(210.0, 512.0), agents = 2),
            target("m-tyz-mini", "tyz mini", memory = size(9.8, 24.0), disk = size(77.3, 256.0), agents = 5),
            target("m-xxair", "xxair", disk = size(14.0, 128.0)),
            target("m-kabi", "kabi", memory = size(1.2, 8.0), disk = size(9.4, 128.0), agents = 1),
            target("m-erid", "Erid", memory = size(20.0, 64.0), disk = size(400.0, 1000.0)),
            target("m-tyz-win", "tyz-win", memory = size(6.0, 32.0), disk = size(150.0, 512.0), agents = 2),
            target("m-shared-ec2", "Shared-EC2", memory = size(2.0, 8.0), disk = size(20.0, 64.0), agents = 7),
            target("m-xxdev", "xxdev", memory = size(14.0, 32.0), disk = size(88.0, 256.0))
        )

/** Computers older than the memory report (slock #11032): disk and agents only. */
private val olderComputers =
    listOf(
        target(SOURCE, "artin-debian-agent", agents = 4),
        target("m-1", "Studio Mac", disk = size(120.0, 500.0), agents = 3),
        target("m-2", "Lab Box", disk = size(48.2, 256.0), agents = 1),
        target("m-4", "Spare Mini", disk = size(310.5, 512.0), agents = 0)
    )

// Labels as the Computer sends them for Claude (OAR); ids are opaque.
private val targetUsage =
    mapOf(
        "m-1" to
            claudeUsage(
                """{"id":"w0_a","label":"Current week (all models)","status":"ok","usedRatio":0.6,"resetsAt":"2026-10-27T04:10:00.000Z"}""",
                """{"id":"w1_b","label":"Current week (Opus)","status":"ok","usedRatio":0.9,"resetsAt":"2026-10-27T04:10:00.000Z"}""",
                """{"id":"w2_c","label":"Current session","status":"ok","usedRatio":0.28,"resetsAt":"2026-10-22T05:10:00.000Z"}"""
            ),
        "m-2" to
            claudeUsage(
                """{"id":"w0_a","label":"Current session","status":"limit_reached","usedRatio":1,"resetsAt":"2026-10-22T05:10:00.000Z"}""",
                """{"id":"w3_d","label":"Current week (all models)","status":"ok","usedRatio":0.45,"resetsAt":"2026-10-27T04:10:00.000Z"}"""
            ),
        "m-kopic" to
            claudeUsage(
                """{"id":"w0_a","label":"Current session","status":"ok","usedRatio":0.1,"resetsAt":"2026-10-22T05:10:00.000Z"}""",
                """{"id":"w1_b","label":"Current week (all models)","status":"ok","usedRatio":0.3,"resetsAt":"2026-10-27T04:10:00.000Z"}"""
            )
    )

private val studioUsage =
    RuntimeAccountUsageLoad.Loaded(
        RuntimeAccountUsageRead.Available(
            stale = false,
            snapshot =
                RuntimeAccountUsageSnapshot(
                    provider = "claude",
                    collectedAt = "2026-10-22T02:04:00.000Z",
                    accounts =
                        listOf(
                            RuntimeAccountUsageAccount(
                                "acct",
                                "Claude Max",
                                null,
                                RuntimeAccountUsageHealth.Ok,
                                listOf(
                                    RuntimeAccountUsageWindow("w2_c", "Current session", RuntimeAccountUsageWindowStatus.Ok, 0.28, "2026-10-22T05:10:00.000Z"),
                                    RuntimeAccountUsageWindow("w0_a", "Current week (all models)", RuntimeAccountUsageWindowStatus.Ok, 0.6, "2026-10-27T04:10:00.000Z"),
                                    RuntimeAccountUsageWindow("w1_b", "Current week (Opus)", RuntimeAccountUsageWindowStatus.Ok, 0.9, "2026-10-27T04:10:00.000Z")
                                )
                            )
                        )
                )
        )
    )

private fun targetInputs(computers: List<AgentMigrationComputer>) =
    AgentMigrationSectionInputs(
        sourceMachineId = SOURCE,
        computers = computers,
        hostedAvailable = false,
        proGate = RaftProGateDecision.Allowed,
        proGateChecking = false,
        onIntent = {},
        onOpenBilling = {},
        onCopy = {},
        formatTimestamp = { "Oct 22, 10:12" },
        onAgentPlacementChanged = {},
        agentRuntime = "claude"
    )
