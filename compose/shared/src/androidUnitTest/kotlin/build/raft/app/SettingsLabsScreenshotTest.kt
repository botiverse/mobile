package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.ServerLab
import build.raft.app.data.ServerLabState
import build.raft.app.data.ServerLabsReadback
import build.raft.app.settings.SettingsLabsScreen
import build.raft.app.settings.SettingsLabsState
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Labs (web LabsSection, botiverse/slock staging aa270b415d44) on fixed fixture data:
 * - `settings.labs`, light and dark: the owner, Server labs access on, four labs (two open, one
 *   enrolled; one paused and one retired, read-only with the web's reason);
 * - `settings.labs.member`: a member, the master switch read-only with "Only server owners can
 *   change the master gate." and every lab "Only server owners and admins can change Lab enrollment.".
 * Goldens are CI's render (`test.yml` with `record_screenshots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class SettingsLabsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun ownerLight() = capture("settings.labs", RaftAppAppearance.Light) { LabsFixture(role = "owner") }

    @Test
    fun ownerDark() = capture("settings.labs.dark", RaftAppAppearance.Dark) { LabsFixture(role = "owner") }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            MigrationFixtureHost(content)
        }
}

internal val settingsLabsFixtureReadback =
    ServerLabsReadback(
        serverId = "srv-labs",
        version = 7,
        masterEnabled = true,
        canManageAccess = null,
        canManageEnrollments = null,
        labs =
            listOf(
                ServerLab("agent_lounge", "Agent Lounge", "One public conversation per agent for every human in the server.", ServerLabState.Open, enrolled = true, effective = true),
                ServerLab("prepared_requests", "Prepared requests", "Agents prepare an operation for a named person to confirm.", ServerLabState.Open, enrolled = false, effective = false),
                ServerLab("message_translation", "Message translation", "Translate messages into your language.", ServerLabState.Paused, enrolled = true, effective = false),
                ServerLab("legacy_threads", "Legacy threads", "The previous thread layout.", ServerLabState.Retired, enrolled = true, effective = false),
            ),
    )

@Composable
private fun LabsFixture(role: String) {
    SettingsLabsScreen(
        state = SettingsLabsState(serverId = "srv-labs", readback = settingsLabsFixtureReadback),
        role = role,
        labsUiEnabled = true,
        onBack = {},
        onMasterChange = {},
        onEnrollmentChange = { _, _ -> },
    )
}
