package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the agent page's runtime error notice for a failed start (web AgentDetailPanel,
 * botiverse/slock #12729), light and dark: the Computer confirmed the runtime is missing, so the
 * notice names it and the ways out; and an unconfirmed lookup, which never says "install it".
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h240dp-xxhdpi")
class AgentRuntimeLookupErrorScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun missingLight() = capture("agent.runtime-error.missing", RaftAppAppearance.Light, AgentRuntimeLookupDisplay.ConfirmedMissing)

    @Test
    fun missingDark() = capture("agent.runtime-error.missing.dark", RaftAppAppearance.Dark, AgentRuntimeLookupDisplay.ConfirmedMissing)

    @Test
    fun unconfirmedLight() =
        capture("agent.runtime-error.unconfirmed", RaftAppAppearance.Light, AgentRuntimeLookupDisplay.Unconfirmed)

    @Test
    fun unconfirmedDark() =
        capture("agent.runtime-error.unconfirmed.dark", RaftAppAppearance.Dark, AgentRuntimeLookupDisplay.Unconfirmed)

    private fun capture(name: String, appearance: RaftAppAppearance, display: AgentRuntimeLookupDisplay) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                    MembersAgentRuntimeErrorBanner(
                        errorText = agentRuntimeLookupErrorText(display, "Claude Code"),
                        canViewPrivate = true,
                        showViewLogs = true,
                        onViewLogs = {},
                        onCopyInfo = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
