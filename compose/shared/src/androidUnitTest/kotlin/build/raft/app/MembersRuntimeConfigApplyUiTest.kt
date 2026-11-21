package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.data.AgentConfigApply
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #15669 / #15714 on a real composition: a running agent's saved runtime config
 * shows "<name>'s settings saved" with a plain confirmation (never a manual Restart now button),
 * and `saved` shows no manual restart prompt even when active.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersRuntimeConfigApplyUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.showNotice(
        configApply: AgentConfigApply,
        newSession: Boolean = false
    ): RaftToastHostState {
        val state = RaftToastHostState()
        var copy: MembersRuntimeConfigApplyCopy? = null
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    copy = membersRuntimeConfigApplyCopy()
                    Box(Modifier.fillMaxSize()) { RaftToastHost(state) }
                }
            }
        }
        waitForIdle()
        val notice = checkNotNull(membersRuntimeConfigApplyNotice(configApply, agentActive = true, newSession = newSession))
        runOnIdle { showMembersRuntimeConfigApplyNotice(state, notice, "witty", checkNotNull(copy)) }
        waitForIdle()
        return state
    }

    @Test
    fun appliedAndRestartedShowPlainConfirmationWithNoRestartButton() = runRobolectricComposeUiTest {
        for (apply in listOf(AgentConfigApply.Applied, AgentConfigApply.Restarted)) {
            showNotice(apply)
            onNodeWithText("witty's settings saved").assertIsDisplayed()
            onNodeWithText("Applied — active for the next turn.").assertIsDisplayed()
            assertEquals(0, countText("Restart now"))
        }
    }

    @Test
    fun whenIdleShowsAutomaticSwitchConfirmationWithNoRestartButton() = runRobolectricComposeUiTest {
        showNotice(AgentConfigApply.WhenIdle, newSession = false)
        onNodeWithText("witty's settings saved").assertIsDisplayed()
        onNodeWithText("Switches automatically after this turn.").assertIsDisplayed()
        assertEquals(0, countText("Restart now"))
    }

    @Test
    fun whenIdleNewSessionShowsAutomaticNewSessionWithNoRestartButton() = runRobolectricComposeUiTest {
        showNotice(AgentConfigApply.WhenIdle, newSession = true)
        onNodeWithText("witty's settings saved").assertIsDisplayed()
        onNodeWithText("Starts a new session automatically after this turn.").assertIsDisplayed()
        assertEquals(0, countText("Restart now"))
    }

    @Test
    fun savedShowsNoRestartPromptEvenWhenActive() = runRobolectricComposeUiTest {
        assertNull(membersRuntimeConfigApplyNotice(AgentConfigApply.Saved, agentActive = true, newSession = false))
    }

    private fun ComposeUiTest.countText(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
