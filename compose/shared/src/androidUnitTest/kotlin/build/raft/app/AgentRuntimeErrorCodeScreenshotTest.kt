package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentRuntimeErrorCode
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the agent page's runtime error notice for a coded error (web
 * AgentRuntimeErrorNotice, botiverse/slock #13985), light and dark: a member reads the code's
 * sentence and no actions (no error text reaches them); a manager reads the managers' sentence with
 * the scrubbed detail under it, and keeps Copy Info and View Logs. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h240dp-xxhdpi")
class AgentRuntimeErrorCodeScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun memberLight() = capture("agent.runtime-error.code-member", RaftAppAppearance.Light, Member, canViewPrivate = false)

    @Test
    fun memberDark() = capture("agent.runtime-error.code-member.dark", RaftAppAppearance.Dark, Member, canViewPrivate = false)

    @Test
    fun managerLight() = capture("agent.runtime-error.code-manager", RaftAppAppearance.Light, Manager, canViewPrivate = true)

    @Test
    fun managerDark() = capture("agent.runtime-error.code-manager.dark", RaftAppAppearance.Dark, Manager, canViewPrivate = true)

    private fun capture(name: String, appearance: RaftAppAppearance, display: AgentRuntimeErrorDisplay.Coded, canViewPrivate: Boolean) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                    MembersAgentRuntimeErrorBanner(
                        errorText = agentRuntimeErrorDisplayText(display),
                        canViewPrivate = canViewPrivate,
                        showViewLogs = true,
                        onViewLogs = {},
                        onCopyInfo = {},
                        modifier = Modifier.fillMaxWidth(),
                        coded = display,
                    )
                }
            }
        }
    }
}

private val Member = AgentRuntimeErrorDisplay.Coded(AgentRuntimeErrorCode.UsageLimit, forManager = false, detail = "")

private val Manager =
    AgentRuntimeErrorDisplay.Coded(
        AgentRuntimeErrorCode.UsageLimit,
        forManager = true,
        detail = "You've hit your usage limit for [REDACTED_EMAIL]. Try again at 3:00 PM.",
    )
