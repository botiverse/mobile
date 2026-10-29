package build.raft.designsystem

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftSideActionHost
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftSideAction
import build.raft.designsystem.components.RaftSideActionColumn
import build.raft.designsystem.components.RaftSideActionHost
import build.raft.designsystem.components.RaftSideActionHostColumn
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RaftSideActionColumn under a host that draws its buttons (LocalRaftSideActionHost). */
@OptIn(ExperimentalTestApi::class)
class RaftSideActionHostUiTest {
    private class RecordingHost : RaftSideActionHost {
        val presented = mutableListOf<RaftSideActionHostColumn>()
        var clears = 0
        var onAction: ((String) -> Unit)? = null

        override fun present(column: RaftSideActionHostColumn, onAction: (id: String) -> Unit): Boolean {
            presented += column
            this.onAction = onAction
            return true
        }

        override fun clear() {
            clears++
        }
    }

    private fun action(id: String, symbol: String?, onClick: () -> Unit) =
        RaftSideAction(id = id, label = id, onClick = onClick, systemSymbol = symbol) { RaftGlyphIcon(RaftGlyph.Search, size = it) }

    @Test
    fun aHandedOffColumnKeepsItsPlaceDrawsNoButtonsAndRunsTheLatestClick() = runComposeUiTest {
        val host = RecordingHost()
        var counter by mutableStateOf(0)
        var shown by mutableStateOf(true)
        var lastRun = -1
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                CompositionLocalProvider(LocalRaftSideActionHost provides host) {
                    if (shown) {
                        // A fresh lambda each recomposition, capturing the current counter.
                        val current = counter
                        RaftSideActionColumn(
                            primary = listOf(action("back", "chevron.backward") {}),
                            actions = listOf(action("search", "magnifyingglass") { lastRun = current }),
                            modifier = Modifier.height(300.dp).testTag("column"),
                            width = 60.dp,
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("column").assertWidthIsEqualTo(60.dp).assertHeightIsEqualTo(300.dp)
        onAllNodesWithContentDescription("search").assertCountEquals(0)
        assertEquals(1, host.presented.size)
        assertEquals(listOf("search"), host.presented.single().visibleActionIds)

        counter = 7
        waitForIdle()
        assertEquals(1, host.presented.size, "same content, new lambdas: not presented again")
        runOnIdle { host.onAction!!("search") }
        assertEquals(7, lastRun, "the tap runs the latest onClick")

        shown = false
        waitForIdle()
        assertEquals(1, host.clears)
    }

    @Test
    fun aMissingSymbolDrawsTheButtonsAndClearsTheHost() = runComposeUiTest {
        val host = RecordingHost()
        var symbol by mutableStateOf<String?>("magnifyingglass")
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                CompositionLocalProvider(LocalRaftSideActionHost provides host) {
                    RaftSideActionColumn(
                        primary = emptyList(),
                        actions = listOf(action("search", symbol) {}),
                        modifier = Modifier.height(300.dp),
                    )
                }
            }
        }
        waitForIdle()
        onAllNodesWithContentDescription("search").assertCountEquals(0)
        symbol = null
        waitForIdle()
        assertTrue(onAllNodesWithContentDescription("search").fetchSemanticsNodes().isNotEmpty())
        assertEquals(1, host.presented.size)
        assertEquals(1, host.clears)
    }
}
