package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftSlider
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** RaftSlider: semantics (value as state, steps, disabled), keys (mirrored ←/→, Page Up/Down) and right to left. */
@OptIn(ExperimentalTestApi::class)
class RaftSliderTest {
    private fun host(direction: LayoutDirection, content: @Composable () -> Unit): @Composable () -> Unit = {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.width(320.dp)) { content() }
            }
        }
    }

    @Test
    fun arrowsFollowTheLayoutDirectionAndPagesMoveATenth() {
        for (direction in LayoutDirection.entries) {
            runComposeUiTest {
                var value by mutableStateOf(600)
                setContent(
                    host(direction) {
                        RaftSlider(value = value, onValueChange = { value = it }, valueRange = 320..1080, step = 10, contentDescription = "Width")
                    },
                )
                val slider = sliderNode()
                slider.requestFocus()
                slider.performKeyInput { pressKey(Key.DirectionRight) }
                assertEquals(if (direction == LayoutDirection.Rtl) 590 else 610, value, "$direction: →")
                slider.performKeyInput { pressKey(Key.DirectionLeft) }
                slider.performKeyInput { pressKey(Key.DirectionLeft) }
                assertEquals(if (direction == LayoutDirection.Rtl) 610 else 590, value, "$direction: ← ←")
                value = 600
                waitForIdle()
                slider.performKeyInput { pressKey(Key.PageUp) }
                assertEquals(670, value, "$direction: Page Up, a tenth of 760 in whole steps")
                slider.performKeyInput { pressKey(Key.PageDown) }
                slider.performKeyInput { pressKey(Key.PageDown) }
                assertEquals(530, value, "$direction: Page Down twice")
                slider.performKeyInput { pressKey(Key.PageUp) }
                value = 1050
                waitForIdle()
                slider.performKeyInput { pressKey(Key.PageUp) }
                assertEquals(1080, value, "$direction: Page Up stops at the end")
            }
        }
    }

    @Test
    fun rightToLeftATapNearTheLeftEdgeIsTheEnd() {
        for (direction in LayoutDirection.entries) {
            runComposeUiTest {
                var value by mutableStateOf(600)
                setContent(host(direction) { RaftSlider(value = value, onValueChange = { value = it }, valueRange = 320..1080, step = 10, contentDescription = "Width") })
                sliderNode().performTouchInput { click(Offset(2f, center.y)) }
                assertEquals(if (direction == LayoutDirection.Rtl) 1080 else 320, value, "$direction: tap at the left edge")
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.sliderNode() =
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
}
