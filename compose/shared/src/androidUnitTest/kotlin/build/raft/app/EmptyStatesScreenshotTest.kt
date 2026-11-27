package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.members.AgentUsageEmptyState
import build.raft.app.members.MembersAgentActivityOverviewEmpty
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app's empty states drawn by the design system's `RaftEmptyState` (web `EmptyState`), one
 * above the other on a 390dp phone: search without results (a long query wraps), the channel
 * members page when nobody matches and when the roster did not load, agent usage with its
 * computer button, and agent activity with its two buttons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1300dp-xhdpi")
class EmptyStatesScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun light() = capture("empty-states.light", RaftAppAppearance.Light)

    @Test
    fun dark() = capture("empty-states.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                    RaftSearchEmptyState(
                        title = "No results for \"the release checklist we agreed on before the October launch review\"",
                        detail = "Try another word, or check the spelling.",
                    )
                    ChannelMembersEmptyState(RaftLucideIconName.Search, "No members match \"ada\"")
                    ChannelMembersEmptyState(RaftLucideIconName.TriangleAlert, "Something went wrong", actionLabel = "Retry")
                    AgentUsageEmptyState(
                        title = "Update this agent's computer to see its usage",
                        action = { RaftButton("Open Computer", onClick = {}) },
                    )
                    MembersAgentActivityOverviewEmpty(
                        title = "No turns recorded yet",
                        detail = "Update the computer to record this agent's turns, or read its raw log.",
                    ) {
                        RaftButton("Open Computer", onClick = {})
                        RaftButton("Show Raw Log", onClick = {})
                    }
                }
            }
        }
}
