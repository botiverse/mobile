package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.data.AgentMigrationState
import build.raft.app.model.MemberId
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The migration section's newer lines (botiverse/slock staging 0e8b4fab), light and dark: a source
 * waiting for its agent's current turn (#14230), the agent's own retry while it packs (the retry
 * line above the failed attempt's size, #14275), and that retry failed again (the banner says the
 * next move is a person's; both sizes). Reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp-xxhdpi")
class AgentMigrationDrainRetryScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun drainRetryLight() = capture("agent.migration.drain-retry", RaftAppAppearance.Light)

    @Test fun drainRetryDark() = capture("agent.migration.drain-retry.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            MigrationFixtureHost {
                Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                    val f = AgentMigrationDrainRetryFixtures
                    val agent = MemberId(f.AGENT)
                    listOf(f.draining(), f.retryPacking, f.retryFailed).forEach { notice ->
                        AgentMigrationSection(AgentMigrationState(agentId = agent, loaded = true, notice = notice), f.inputs())
                    }
                }
            }
        }
}
