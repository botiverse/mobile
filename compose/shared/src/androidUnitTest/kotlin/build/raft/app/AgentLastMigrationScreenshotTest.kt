package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the agent profile's migration section after a move between computers ended
 * (`members.agent-migration.last`): a completed move above, a failed one below (web LastMigration,
 * botiverse/slock #10951). Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class AgentLastMigrationScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun lastMigrationLight() = capture("members.agent-migration.last", RaftAppAppearance.Light)

    @Test
    fun lastMigrationDark() = capture("members.agent-migration.last.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { AgentLastMigrationVisualContent() }
            }
        }
    }
}

private fun lastMigrationInputs(): AgentMigrationSectionInputs =
    AgentMigrationSectionInputs(
        sourceMachineId = "machine-studio",
        computers = listOf(
            AgentMigrationComputer(id = "machine-laptop", name = "artin-laptop", isComputer = true),
            AgentMigrationComputer(id = "machine-studio", name = "studio-mac", isComputer = true),
        ),
        hostedAvailable = false,
        proGate = RaftProGateDecision.Allowed,
        proGateChecking = false,
        onIntent = {},
        onOpenBilling = {},
        onCopy = {},
        formatTimestamp = { "Oct 21, 2:05 PM" },
        onAgentPlacementChanged = {},
    )

internal fun lastMigrationCompletedNotice(): AgentMigrationNotice =
    AgentMigrationNotice(
        agentId = "agent-1",
        migrationRef = "mig_01JB7Q2S9K",
        state = "completed",
        revision = 7,
        sourceMachineId = "machine-laptop",
        targetKind = "machine",
        targetMachineId = "machine-studio",
        sourceQuiescedAt = "2026-10-21T06:01:00.000Z",
        readyAt = "2026-10-21T06:03:00.000Z",
        flippedAt = "2026-10-21T06:04:00.000Z",
        arrivedAt = "2026-10-21T06:04:30.000Z",
        completedAt = "2026-10-21T06:05:00.000Z",
        updatedAt = "2026-10-21T06:05:00.000Z",
    )

internal fun lastMigrationFailedNotice(): AgentMigrationNotice =
    AgentMigrationNotice(
        agentId = "agent-1",
        migrationRef = "mig_01JB9X4T2M",
        state = "failed",
        revision = 4,
        sourceMachineId = "machine-studio",
        targetKind = "machine",
        targetMachineId = "machine-laptop",
        failureReason = "TRANSFER_FAILED",
        transportErrorMessage = "The target computer went offline during the transfer.",
        sourceQuiescedAt = "2026-10-22T09:01:00.000Z",
        transportControlRegisteredAt = "2026-10-22T09:02:00.000Z",
        abortedAt = "2026-10-22T09:05:00.000Z",
        updatedAt = "2026-10-22T09:05:00.000Z",
    )

@Composable
private fun AgentLastMigrationVisualContent() {
    val inputs = remember { lastMigrationInputs() }
    // Each section draws its own separator and padding (web profile section).
    Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        AgentMigrationSection(
            state = AgentMigrationState(agentId = MemberId("agent-1"), notice = lastMigrationCompletedNotice(), loaded = true),
            inputs = inputs,
            modifier = Modifier.fillMaxWidth(),
        )
        AgentMigrationSection(
            state = AgentMigrationState(agentId = MemberId("agent-1"), notice = lastMigrationFailedNotice(), loaded = true),
            inputs = inputs,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
