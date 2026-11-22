package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftTooltipTouchTimer
import build.raft.designsystem.components.RaftAppRail
import build.raft.designsystem.components.RaftAppRailFooterItem
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred

/** The rail's web LeftRail behaviours: tooltips, the footer's toggle, the compact size. */
@OptIn(ExperimentalTestApi::class)
class RaftAppRailUiTest {
    @Composable
    private fun Rail(
        compact: Boolean? = false,
        sidebarShown: Boolean = true,
        onSidebar: (Boolean) -> Unit = {},
        onHome: () -> Unit = {},
    ) {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
            Row(Modifier.height(500.dp)) {
                RaftAppRail(
                    compact = compact,
                    footerItems =
                        listOf(
                            RaftAppRailFooterItem.Toggle(
                                key = "sidebar",
                                label = if (sidebarShown) "Collapse sidebar" else "Expand sidebar",
                                checked = sidebarShown,
                                onCheckedChange = onSidebar,
                                icon = { _, _ -> },
                                shortcutLabel = "Ctrl+\\",
                            ),
                            RaftAppRailFooterItem.Action(key = "settings", label = "Settings", onClick = {}, icon = {}),
                        ),
                ) {
                    RaftAppRailItem(label = "Home", selected = false, onClick = onHome, icon = {})
                }
                Box(Modifier.width(240.dp))
            }
        }
    }

    @Test
    fun aHoveredItemNamesItselfAndAToggleShowsItsShortcut() =
        runComposeUiTest {
            setContent { Rail() }
            mainClock.autoAdvance = false
            val home = onNodeWithContentDescription("Home")
            home.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(500)
            onNode(hasText("Home")).assertExists()
            home.performMouseInput { moveTo(bottomRight + Offset(400f, 400f)) }
            mainClock.advanceTimeBy(500)
            onNode(hasText("Home")).assertDoesNotExist()

            onNodeWithContentDescription("Collapse sidebar").performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(500)
            onNode(hasText("Collapse sidebar")).assertExists()
            onNode(hasText("Ctrl+\\")).assertExists()
        }

    @Test
    fun aLongPressShowsTheNameWithoutOpeningTheItem() =
        runComposeUiTest {
            var clicks = 0
            val timeUp = CompletableDeferred<Unit>()
            setContent {
                CompositionLocalProvider(LocalRaftTooltipTouchTimer provides { timeUp.await() }) {
                    Rail(onHome = { clicks++ })
                }
            }
            mainClock.autoAdvance = false
            onNodeWithContentDescription("Home").performTouchInput { longClick() }
            mainClock.advanceTimeBy(300)
            onNode(hasText("Home")).assertExists()
            assertEquals(0, clicks, "the long press that opens the tooltip must not open the item")
            timeUp.complete(Unit)
            mainClock.advanceTimeBy(1_000)
            onNode(hasText("Home")).assertDoesNotExist()
        }
}
