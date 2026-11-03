package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftTooltipTouchTimer
import build.raft.designsystem.components.RaftTopBar
import build.raft.designsystem.components.RaftWorkspaceStrip
import build.raft.designsystem.components.RaftWorkspaceStripEntry
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred

/** The top bar's workspace strip names each server in a tooltip (web WorkspaceStripButton). */
@OptIn(ExperimentalTestApi::class)
class RaftWorkspaceStripUiTest {
    private val servers =
        listOf(
            RaftWorkspaceStripEntry("b", "Botiverse"),
            RaftWorkspaceStripEntry("p", "Bearproof"),
            RaftWorkspaceStripEntry("q", "Quiet"),
        )

    @Composable
    private fun Bar(onPick: (RaftWorkspaceStripEntry) -> Unit = {}) {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
            Column(Modifier.height(300.dp)) {
                RaftTopBar(
                    modifier = Modifier.width(900.dp),
                    leading = {
                        RaftWorkspaceStrip(
                            entries = servers,
                            currentKey = "b",
                            onPick = onPick,
                            avatar = {},
                            modifier = Modifier.weight(1f),
                        )
                    },
                )
                Box(Modifier.height(200.dp))
            }
        }
    }

    @Test
    fun aHoveredAvatarShowsItsServersName() =
        runComposeUiTest {
            setContent { Bar() }
            mainClock.autoAdvance = false
            val avatar = onNodeWithContentDescription("Bearproof")
            onNode(hasText("Bearproof")).assertDoesNotExist()
            avatar.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(500)
            onNode(hasText("Bearproof")).assertExists()
            avatar.performMouseInput { moveTo(bottomRight + Offset(400f, 400f)) }
            mainClock.advanceTimeBy(500)
            onNode(hasText("Bearproof")).assertDoesNotExist()
        }

    @Test
    fun aLongPressShowsTheNameWithoutSwitching() =
        runComposeUiTest {
            val picks = mutableListOf<String>()
            val timeUp = CompletableDeferred<Unit>()
            setContent {
                CompositionLocalProvider(LocalRaftTooltipTouchTimer provides { timeUp.await() }) {
                    Bar(onPick = { picks += it.key })
                }
            }
            mainClock.autoAdvance = false
            onNodeWithContentDescription("Bearproof").performTouchInput { longClick() }
            mainClock.advanceTimeBy(300)
            onNode(hasText("Bearproof")).assertExists()
            assertEquals(emptyList(), picks, "the long press that opens the tooltip must not switch servers")
            timeUp.complete(Unit)
            mainClock.advanceTimeBy(1_000)
            onNode(hasText("Bearproof")).assertDoesNotExist()

            onNodeWithContentDescription("Bearproof").performClick()
            mainClock.advanceTimeBy(300)
            assertEquals(listOf("p"), picks, "a tap still switches")
        }
}
