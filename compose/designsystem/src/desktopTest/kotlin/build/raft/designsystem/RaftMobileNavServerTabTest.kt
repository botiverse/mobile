package build.raft.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftMobileNavDefaults
import build.raft.designsystem.components.RaftMobileNavServerPopoverItem
import build.raft.designsystem.components.RaftMobileNavServerPopoverState
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The phone bar's server tab ([RaftMobileNavServerPopoverItem]) from a keyboard and under a cancelled touch. */
@OptIn(ExperimentalTestApi::class)
class RaftMobileNavServerTabTest {
    private val tag = "server-tab"

    private fun themed(content: @Composable () -> Unit): @Composable () -> Unit = {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content)
    }

    @Composable
    private fun Tab(state: RaftMobileNavServerPopoverState) {
        RaftMobileNavServerPopoverItem(
            label = "Botiverse",
            state = state,
            avatar = { size -> Box(Modifier.size(size).background(Color.Black)) },
            modifier = Modifier.testTag(tag),
            contentDescription = "Current server: Botiverse",
        )
    }

    /** sindresorhus on #3509: a focused server tab opens the panel with Enter or Space, as a tap does. */
    @Test
    fun enterOrSpaceOnTheFocusedTabOpensTheServerPanel() =
        runComposeUiTest {
            val state = RaftMobileNavServerPopoverState()
            setContent(themed { Tab(state) })
            val tab = onNodeWithTag(tag)
            tab.requestFocus()
            tab.assertIsFocused()
            listOf(Key.Enter, Key.Spacebar, Key.NumPadEnter).forEach { key ->
                state.dismiss()
                tab.performKeyInput { pressKey(key) }
                waitForIdle()
                assertTrue(state.open, "$key on the focused tab opened the panel")
                // The same key again closes it, like a second tap.
                tab.performKeyInput { pressKey(key) }
                waitForIdle()
                assertFalse(state.open, "$key again closed the panel")
            }
        }

    /**
     * The keyboard's click must not make a touch click twice: a tap toggles once, a touch slid
     * past the slop does nothing, a 500ms hold opens the panel and its lift leaves it open, and a
     * hold over an already open panel leaves it open too.
     */
    @Test
    fun aTouchStillTogglesTheServerPanelOnce() =
        runComposeUiTest {
            val state = RaftMobileNavServerPopoverState()
            setContent(themed { Tab(state) })
            val tab = onNodeWithTag(tag)
            tab.performTouchInput { click() }
            waitForIdle()
            assertTrue(state.open, "a tap opened the panel")
            tab.performTouchInput { click() }
            waitForIdle()
            assertFalse(state.open, "a second tap closed it")

            tab.performTouchInput {
                down(center)
                moveBy(Offset(0f, RaftMobileNavDefaults.LongPressSlop.toPx() + 4.dp.toPx()))
                up()
            }
            waitForIdle()
            assertFalse(state.open, "a touch slid past the slop is not a tap")

            tab.performTouchInput { down(center) }
            // The hold's timer runs on the composition's clock.
            mainClock.advanceTimeBy(RaftMobileNavDefaults.LongPressMillis + 100)
            assertTrue(state.open && state.holding, "a 500ms hold opened the panel and is held")
            tab.performTouchInput { up() }
            waitForIdle()
            assertTrue(state.open, "lifting the hold left the panel open")
            assertFalse(state.holding)

            tab.performTouchInput { down(center) }
            mainClock.advanceTimeBy(RaftMobileNavDefaults.LongPressMillis + 100)
            tab.performTouchInput { up() }
            waitForIdle()
            assertTrue(state.open, "a hold over the open panel left it open")
        }

    /**
     * sindresorhus on #3509: a gesture cancelled midway (here the tab's state changes under the
     * finger, which restarts its pointer input) must not leave the tab washed as pressed.
     */
    @Test
    fun aCancelledGestureResetsThePressedState() =
        runComposeUiTest {
            var state by mutableStateOf(RaftMobileNavServerPopoverState())
            setContent(themed { Tab(state) })
            val tab = onNodeWithTag(tag)
            val rest = tab.pixels()
            mainClock.autoAdvance = false
            tab.performTouchInput { down(center) }
            mainClock.advanceTimeByFrame()
            assertFalse(rest.contentEquals(tab.pixels()), "the finger down washes the tab")

            // Cancel the gesture before the hold or the lift.
            state = RaftMobileNavServerPopoverState()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            assertTrue(rest.contentEquals(tab.pixels()), "the cancelled gesture left the tab at rest, not pressed")
            assertFalse(state.open)
            mainClock.autoAdvance = true
        }

    private fun SemanticsNodeInteraction.pixels(): IntArray {
        val map = captureToImage().toPixelMap()
        return IntArray(map.width * map.height) { i -> map[i % map.width, i / map.width].hashCode() }
    }
}
