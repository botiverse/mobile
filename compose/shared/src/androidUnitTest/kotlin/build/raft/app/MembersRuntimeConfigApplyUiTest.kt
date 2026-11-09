package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AgentConfigApply
import build.raft.app.data.AgentResetMode
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #13372 / #13373 on a real composition: a running agent's saved runtime config
 * shows "<name>'s settings saved" with when it applies and Restart now (which closes the toast and
 * restarts, or resets the session of, the agent), and the Model row says what still runs.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersRuntimeConfigApplyUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.showNotice(
        configApply: AgentConfigApply,
        newSession: Boolean,
        canRestart: Boolean = true,
        restarts: MutableList<AgentResetMode>
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
        val notice = checkNotNull(membersRuntimeConfigApplyNotice(configApply, agentActive = true, newSession, canRestart))
        runOnIdle { showMembersRuntimeConfigApplyNotice(state, notice, "witty", checkNotNull(copy)) { restarts += it } }
        waitForIdle()
        return state
    }

    @Test
    fun aWhenIdleSaveSaysWhenItAppliesAndRestartNowRestarts() = runRobolectricComposeUiTest {
        val restarts = mutableListOf<AgentResetMode>()
        val state = showNotice(AgentConfigApply.WhenIdle, newSession = false, restarts = restarts)
        onNodeWithText("witty's settings saved").assertIsDisplayed()
        onNodeWithText("They apply when it's next idle.").assertIsDisplayed()

        onNodeWithText("Restart now").performClick()
        waitForIdle()
        assertEquals(listOf(AgentResetMode.Restart), restarts)
        assertTrue(state.visible.isEmpty(), "the action closes the toast")
    }

    @Test
    fun aNewSessionChangeSaysSoAndRestartNowResetsTheSession() = runRobolectricComposeUiTest {
        val restarts = mutableListOf<AgentResetMode>()
        showNotice(AgentConfigApply.WhenIdle, newSession = true, restarts = restarts)
        onNodeWithText("It starts a new session when it's next idle.").assertIsDisplayed()
        onNodeWithText("Restart now").performClick()
        waitForIdle()
        assertEquals(listOf(AgentResetMode.Session), restarts)
    }

    @Test
    fun anOlderDaemonsSaveAppliesAtTheNextStart() = runRobolectricComposeUiTest {
        showNotice(AgentConfigApply.Saved, newSession = false, restarts = mutableListOf())
        onNodeWithText("They apply the next time it starts.").assertIsDisplayed()
        onNodeWithText("Restart now").assertIsDisplayed()
    }

    @Test
    fun withoutRestartPermissionThereIsNoRestartNow() = runRobolectricComposeUiTest {
        showNotice(AgentConfigApply.WhenIdle, newSession = false, canRestart = false, restarts = mutableListOf())
        onNodeWithText("witty's settings saved").assertIsDisplayed()
        assertEquals(0, countText("Restart now"))
    }

    @Test
    fun theModelRowSaysWhatTheRunningAgentStillRuns() = runRobolectricComposeUiTest {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Column {
                    MembersAgentRuntimeConfigRows(
                        resolution = grokConfig(),
                        runtimeModelsCatalog = null,
                        machineId = null,
                        runningHint = MembersRuntimeRunningHint.Model(running = "grok-3", saved = "grok-4")
                    )
                }
            }
        }
        onNodeWithTag(MEMBERS_RUNTIME_RUNNING_HINT_TAG).assertTextEquals("Running grok-3 · switches to grok-4 when idle")
    }

    @Test
    fun withoutAPendingChangeTheModelRowHasNoHint() = runRobolectricComposeUiTest {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Column { MembersAgentRuntimeConfigRows(resolution = grokConfig(), runtimeModelsCatalog = null, machineId = null) }
            }
        }
        onNodeWithTag(MEMBERS_RUNTIME_RUNNING_HINT_TAG).assertDoesNotExist()
    }

    private fun ComposeUiTest.countText(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private fun grokConfig(): MembersAgentRuntimeConfigResolution =
        MembersAgentRuntimeConfigResolution.Content(
            RuntimeConfig(
                runtime = "grok",
                provider = null,
                model = membersRuntimeModelConfig("grok", "grok-4", "preset"),
                mode = RuntimeModeConfig.Default,
                reasoningEffort = null,
                envVars = null
            )
        )
}
