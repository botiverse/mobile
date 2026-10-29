package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wide supporting pane vs the task overlay (Tenny, #3953 review): the shell composes the detail
 * pane twice while the overlay is open, the main detail (which hosts the pane) and the overlay's
 * copy (`allowSideColumn = false`), and disposes the copy when the overlay closes. Back must then
 * still close the pane, and must not close it while the overlay covers it. Drives a real
 * composition with [runRobolectricComposeUiTest]; see AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftSupportingPaneBackUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /** The shell state SettingsHomePage keeps for this, and its `navigateWideHistory` Back. */
    private class Shell {
        var panes by mutableStateOf(RaftPaneStack())
        var paneShown by mutableStateOf(false)
        var overlayOpen by mutableStateOf(false)
        var historyBacks = 0

        fun back() {
            if (raftWideBackStep(panes, paneShown, overlayOpen = overlayOpen) == RaftWideBackStep.PopSupportingPane) {
                panes = panes.pop()
            } else {
                historyBacks++
            }
        }
    }

    /** SettingsHomePage's `adaptiveDetailPane` reduced to the pane's host / shown wiring (navRailActive, detail accepted). */
    @Composable
    private fun DetailPane(shell: Shell, allowSideColumn: Boolean, tag: String) {
        val supportingPaneHost = allowSideColumn
        val supportingOpen = supportingPaneHost && shell.panes.isOpen
        RaftSupportingPaneShownReport(host = supportingPaneHost, shown = supportingOpen) { shell.paneShown = it }
        Box(Modifier.testTag(tag)) {
            if (supportingOpen) Box(Modifier.testTag(PANE))
        }
    }

    private fun ComposeUiTest.showShell(shell: Shell) =
        setContent {
            DetailPane(shell, allowSideColumn = true, tag = "main")
            if (shell.overlayOpen) DetailPane(shell, allowSideColumn = false, tag = OVERLAY)
        }

    private fun ComposeUiTest.openPaneThenOverlay(shell: Shell) {
        showShell(shell)
        runOnIdle { shell.panes = shell.panes.replace("channel_settings", JsonObject(emptyMap())) }
        onNodeWithTag(PANE).assertExists()
        assertTrue(shell.paneShown, "pane opened but not reported shown")
        runOnIdle { shell.overlayOpen = true }
        onNodeWithTag(OVERLAY).assertExists()
    }

    @Test
    fun overlayClosedBackClosesPaneNotHistory() =
        runRobolectricComposeUiTest {
            val shell = Shell()
            openPaneThenOverlay(shell)
            runOnIdle { shell.overlayOpen = false }
            onNodeWithTag(OVERLAY).assertDoesNotExist()
            assertTrue(shell.paneShown, "the overlay copy's dispose reset the pane's shown state")

            runOnIdle { shell.back() }
            onNodeWithTag(PANE).assertDoesNotExist()
            assertEquals(0, shell.historyBacks, "Back stepped history instead of closing the pane")
            assertEquals(false, shell.paneShown)

            runOnIdle { shell.back() }
            assertEquals(1, shell.historyBacks, "with the pane closed Back is history's")
        }

    @Test
    fun overlayOpenBackLeavesPane() =
        runRobolectricComposeUiTest {
            val shell = Shell()
            openPaneThenOverlay(shell)
            runOnIdle { shell.back() }
            assertEquals(1, shell.historyBacks, "Back under the overlay is not the pane's")
            assertTrue(shell.panes.isOpen, "Back closed the pane hidden under the task overlay")
            onNodeWithTag(PANE).assertExists()
        }

    private companion object {
        const val PANE = "supporting-pane"
        const val OVERLAY = "overlay"
    }
}
