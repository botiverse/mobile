package build.raft.app.assistant

import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.data.RaftAssistantOtterHosts
import build.raft.app.runRobolectricComposeUiTest
import build.raft.designsystem.text.RaftText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Product report: Ottie open on a conversation, a task opened from its DM, then Back to the
 * conversation: Ottie had closed. The conversation page owns the panel's open state
 * ([rememberThreadAssistantPanelState]): the pushed page only hides the panel's window (before the
 * push, so it never sits over the new page), and Back to the conversation shows it again, on a
 * phone (the drawer) and a wide window (the popover). Closing it is still the person's: collapse,
 * or Back while the panel is on top.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ThreadAssistantPanelStateCoverUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun otter(state: ThreadAssistantPanelState) =
        ThreadAssistantOtter(
            hostId = PAGE,
            hosts = RaftAssistantOtterHosts(),
            mood = RaftOtterMood.Idle,
            scale = RaftAssistantOtterDefaults.PhoneScale,
            endInset = RaftAssistantOtterDefaults.PhoneEndInset,
            onOpen = { state.requestOpen(true) },
            assistantName = "Ottie",
            agentId = "agent-ottie",
            serverId = "server-1",
            dmChannelId = "dm-ottie",
            panelOpen = state.open,
            panelShown = state.shown,
            onPanelOpenChange = state::requestOpen,
            panelEmbed = ThreadAssistantPanelEmbed(agentId = "agent-ottie", onWaitingActionCard = {}),
        )

    /**
     * The conversation page as ThreadPage composes it: the panel's state remembered by the page
     * (listening to the page's cover signal), then the panel; a task ref in the panel's DM opens
     * its page through the page host's navigator. Returns the state and the page's `pageActive`.
     */
    private fun ComposeUiTest.showConversation(): Pair<() -> ThreadAssistantPanelState, (Boolean) -> Unit> {
        var pageActive by mutableStateOf(true)
        var state: ThreadAssistantPanelState? = null
        val navigator = ThreadAssistantPanelTestNavigator { state!!.shown }
        pushes = navigator.pushes
        setContent {
            ThreadAssistantPanelTestPage(navigator) {
                val panel = rememberThreadAssistantPanelState("server-1", "dm-ottie", pageActive).also { state = it }
                ThreadAssistantPanelSheet(otter(panel)) {
                    val navigatorInPanel = LocalRaftHost.current.navigator
                    RaftText(
                        "#task-7",
                        Modifier.testTag(PANEL_DM).clickable { navigatorInPanel.openPage(THREAD_PAGE_NAME) },
                    )
                }
            }
        }
        return Pair({ state!! }, { active: Boolean -> pageActive = active })
    }

    private var pushes: List<Pair<String, Boolean>> = emptyList()

    private fun ComposeUiTest.idleFrames() =
        repeat(2) {
            mainClock.advanceTimeByFrame()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }

    /** Opens the panel, opens a task from its DM, comes back; the panel is open and on screen again. */
    private fun ComposeUiTest.openTaskFromPanelAndComeBack(panelTag: String): () -> ThreadAssistantPanelState {
        val (state, setPageActive) = showConversation()
        runOnIdle { state().requestOpen(true) }
        mainClock.advanceTimeBy(600)
        onNode(hasTestTag(panelTag)).assertExists()

        mainClock.autoAdvance = false
        onNode(hasTestTag(PANEL_DM)).performClick()
        assertEquals(listOf(THREAD_PAGE_NAME to false), pushes, "the panel's window went before the push")
        idleFrames()
        onNode(hasTestTag(panelTag)).assertDoesNotExist()
        runOnIdle { assertTrue(state().open, "a page opened from the panel does not close it") }

        // The task's page covers the conversation; Back pops it and the conversation is on top again.
        runOnIdle { setPageActive(false) }
        idleFrames()
        onNode(hasTestTag(panelTag)).assertDoesNotExist()
        runOnIdle { assertTrue(state().open, "still open while the conversation is covered") }
        runOnIdle { setPageActive(true) }
        mainClock.autoAdvance = true
        mainClock.advanceTimeBy(600)
        onNode(hasTestTag(panelTag)).assertExists()
        onNode(hasTestTag(PANEL_DM)).assertExists()
        runOnIdle { assertTrue(state().shown, "back on the conversation, the panel shows again") }
        return state
    }

    @Test
    fun phoneDrawerComesBackWithTheConversationAndBackStillClosesIt() =
        runRobolectricComposeUiTest {
            val state = openTaskFromPanelAndComeBack(ThreadAssistantPanelSheetTestTag)
            // Back while the drawer is on top closes the panel.
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog
            runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
            mainClock.advanceTimeBy(1_000)
            runOnIdle { assertFalse(state().open, "Back on the panel closes it") }
            onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).assertDoesNotExist()
        }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun widePopoverComesBackWithTheConversationAndCollapseStillClosesIt() =
        runRobolectricComposeUiTest {
            val state = openTaskFromPanelAndComeBack(ThreadAssistantPanelPopoverTestTag)
            onNode(hasTestTag(ThreadAssistantPanelCollapseTestTag)).performClick()
            mainClock.advanceTimeBy(600)
            runOnIdle { assertFalse(state().open, "collapse closes the panel") }
            onNode(hasTestTag(ThreadAssistantPanelPopoverTestTag)).assertDoesNotExist()
        }

    private companion object {
        const val PAGE = "page"
        const val PANEL_DM = "panel-dm"
        const val THREAD_PAGE_NAME = "compose_thread"
    }
}
