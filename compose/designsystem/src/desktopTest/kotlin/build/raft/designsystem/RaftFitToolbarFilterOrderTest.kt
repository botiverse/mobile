package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftFitToolbar
import build.raft.designsystem.components.RaftFitToolbarChoice
import build.raft.designsystem.components.RaftFitToolbarOption
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A page puts its applied filters first (web ComputersRackToolbar): picking in a filter's menu
 * reorders the filters under it, and the open menu must stay that filter's (review of #4627: it
 * closed and the other filter's opened, its open state following the position).
 */
@OptIn(ExperimentalTestApi::class)
class RaftFitToolbarFilterOrderTest {
    @Test
    fun aReorderedFilterKeepsItsOpenMenu() =
        runComposeUiTest {
            var runtimeApplied by mutableStateOf(false)
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.width(1024.dp)) {
                        val status = choice("status", "Status")
                        val runtime = choice("runtime", "Runtime")
                        RaftFitToolbar(filters = if (runtimeApplied) listOf(runtime, status) else listOf(status, runtime))
                    }
                }
            }
            onNodeWithTag("runtime").performClick()
            waitForIdle()
            assertTrue(isShown("runtime-menu"), "the Runtime menu opens")

            // Picking in it applies Runtime, which moves it first.
            runtimeApplied = true
            waitForIdle()
            assertTrue(isShown("runtime-menu"), "the Runtime menu stays open after it moved")
            assertFalse(isShown("status-menu"), "Status does not take its open state")
        }

    private fun choice(tag: String, name: String) =
        RaftFitToolbarChoice(
            text = name,
            icon = { Spacer(Modifier.size(14.dp)) },
            options = listOf(RaftFitToolbarOption("a", "A")),
            selectedKey = null,
            onSelect = {},
            heading = name,
            testTag = tag,
            menu = { _ -> RaftText("$tag-menu") },
        )

    private fun androidx.compose.ui.test.ComposeUiTest.isShown(text: String): Boolean =
        onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
}
