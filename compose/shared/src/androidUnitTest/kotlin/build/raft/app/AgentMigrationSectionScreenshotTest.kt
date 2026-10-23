package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.AgentMigrationState
import build.raft.app.data.RaftProGateDecision
import build.raft.app.model.MemberId
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Agent migration section on an agent's profile (web AgentMigrationSection), light and dark:
 * idle (the Migrate entry), a move in progress (the Steps with the running step, its line and
 * Cancel), a canceled move whose cleanup needs attention (warning banner, migration ID with
 * copy), and a failed move between computers (Steps stopped at the failed one, the summary
 * banner). The spinner's motion is covered by [AgentMigrationSectionStepsTest]. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1800dp-xxhdpi")
class AgentMigrationSectionScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun sectionLight() = capture("agent.migration.section", RaftAppAppearance.Light)

    @Test
    fun sectionDark() = capture("agent.migration.section.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            MigrationFixtureHost {
                Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                    MigrationCase("Idle") { AgentMigrationSection(AgentMigrationState(agentId = agent, loaded = true), migrationInputs(), Modifier.padding(horizontal = 16.dp)) }
                    MigrationCase("In progress") {
                        AgentMigrationSection(AgentMigrationState(agentId = agent, loaded = true, notice = packing), migrationInputs(), Modifier.padding(horizontal = 16.dp))
                    }
                    MigrationCase("Canceled, needs attention") {
                        AgentMigrationSection(AgentMigrationState(agentId = agent, loaded = true, notice = canceledNeedsAttention), migrationInputs(), Modifier.padding(horizontal = 16.dp))
                    }
                    MigrationCase("Failed") {
                        AgentMigrationSection(AgentMigrationState(agentId = agent, loaded = true, notice = failed), migrationInputs(), Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
}

private val agent = MemberId("agent-yanyongyu")

private const val SOURCE = "m-artin-debian"

private val migrationComputers =
    listOf(
        AgentMigrationComputer(SOURCE, "artin-debian-agent", isComputer = true),
        AgentMigrationComputer("m-kopic", "KopiC", isComputer = true),
        AgentMigrationComputer("m-maria", "Maria", isComputer = true),
        AgentMigrationComputer("m-cindy", "Cindy MacAir", isComputer = true),
        AgentMigrationComputer("m-tyz-mini", "tyz mini", isComputer = true),
        AgentMigrationComputer("m-xxair", "xxair", isComputer = true),
        AgentMigrationComputer("m-kabi", "kabi", isComputer = true),
        AgentMigrationComputer("m-wenyi-pro", "wenyideMacBook-Pro.local", isComputer = true),
        AgentMigrationComputer("m-erid", "Erid", isComputer = true),
        AgentMigrationComputer("m-amidadada", "Amidadada", isComputer = true),
        AgentMigrationComputer("m-tyz-win", "tyz-win", isComputer = true),
        AgentMigrationComputer("m-shared-ec2", "Shared-EC2", isComputer = true),
        AgentMigrationComputer("m-xxdev", "xxdev", isComputer = true)
    )

private fun migrationInputs() =
    AgentMigrationSectionInputs(
        sourceMachineId = SOURCE,
        computers = migrationComputers,
        hostedAvailable = false,
        proGate = RaftProGateDecision.Allowed,
        proGateChecking = false,
        onIntent = {},
        onOpenBilling = {},
        onCopy = {},
        formatTimestamp = { "Oct 23, 10:12" },
        onAgentPlacementChanged = {}
    )

private fun migrationNotice(
    state: String,
    targetMachineId: String,
    sourceQuiescedAt: String? = "2026-10-23T02:30:05.000Z",
    cancelNeedsAttention: Boolean = false,
    transportErrorCode: String? = null,
    abortedAt: String? = null
) = AgentMigrationNotice(
    agentId = agent.value,
    migrationRef = "mig_7AvQxql9U9dIAD4L0kOH2w",
    state = state,
    revision = 4,
    sourceMachineId = SOURCE,
    targetKind = "computer",
    targetMachineId = targetMachineId,
    transportErrorCode = transportErrorCode,
    sourceQuiescedAt = sourceQuiescedAt,
    cancelNeedsAttention = cancelNeedsAttention,
    abortedAt = abortedAt,
    createdAt = "2026-10-23T02:30:00.000Z",
    updatedAt = "2026-10-23T02:31:00.000Z"
)

/** Packing the workspace on artin-debian-agent for KopiC (artin's phone, task #19). */
private val packing = migrationNotice("provisioning", targetMachineId = "m-kopic")

/** A move canceled before the switch whose cleanup did not finish; the target is no longer listed. */
private val canceledNeedsAttention =
    migrationNotice("canceled_pre_flip", targetMachineId = "2cb7c26b-58f1-4d1e-9a51-1f0c3e0d2b77", cancelNeedsAttention = true)

private val failed =
    migrationNotice(
        "failed",
        targetMachineId = "m-kopic",
        transportErrorCode = "TARGET_OFFLINE",
        abortedAt = "2026-10-23T02:40:00.000Z"
    )

@Composable
private fun MigrationFixtureHost(content: @Composable () -> Unit) {
    val pager =
        remember {
            object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
        RaftDesignSystemTheme(content)
    }
}

@Composable
private fun MigrationCase(label: String, content: @Composable () -> Unit) {
    RaftText(
        text = label,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        style = RaftTheme.typography.monoMeta,
        color = RaftTheme.colors.foregroundHint
    )
    content()
}
