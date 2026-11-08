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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftSlider
import build.raft.designsystem.components.raftSliderFraction
import build.raft.designsystem.components.raftSliderPageStep
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun theValueReadsAsItsStateWithItsStepsAndNoHeader() =
        runComposeUiTest {
            setContent(
                host(LayoutDirection.Ltr) {
                    RaftSlider(
                        value = 620,
                        onValueChange = {},
                        valueRange = 320..1080,
                        step = 10,
                        contentDescription = "Image width",
                        stateDescription = "620",
                    )
                },
            )
            val slider = sliderNode()
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "620"))
            // 76 steps of 10 from 320 to 1080: 75 stops between the ends.
            slider.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(620f, 320f..1080f, steps = 75),
                ),
            )
            slider.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
        }

    @Test
    fun aDisabledSliderIsDisabledInSemantics() =
        runComposeUiTest {
            setContent(host(LayoutDirection.Ltr) { RaftSlider(value = 600, onValueChange = {}, valueRange = 320..1080, enabled = false, contentDescription = "Off") })
            sliderNode().assertIsNotEnabled()
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

    @Test
    fun rightToLeftTheTrackFillsFromTheRight() {
        for (direction in LayoutDirection.entries) {
            runComposeUiTest {
                setContent(host(direction) { RaftSlider(value = 420, onValueChange = {}, valueRange = 320..1080, contentDescription = "Width") })
                val bounds = sliderNode().fetchSemanticsNode().boundsInRoot
                val image = onRoot().captureToImage().toPixelMap()
                val y = bounds.center.y.toInt()
                // A little past the 22dp thumb's half from each end: the fill near the start only.
                val inset = (30 * density.density).toInt()
                fun yellow(x: Int): Boolean {
                    val pixel = image[x, y]
                    val brand = RaftUiPalette.selected
                    return abs(pixel.red - brand.red) < 0.03f && abs(pixel.green - brand.green) < 0.03f && abs(pixel.blue - brand.blue) < 0.03f
                }
                val left = yellow(bounds.left.toInt() + inset)
                val right = yellow(bounds.right.toInt() - inset)
                if (direction == LayoutDirection.Rtl) {
                    assertTrue(right && !left, "RTL: the fill is at the right (left=$left right=$right)")
                } else {
                    assertTrue(left && !right, "LTR: the fill is at the left (left=$left right=$right)")
                }
            }
        }
    }

    @Test
    fun pointerAndPageMath() {
        // 200px track, 10px in from each end: the start is the left LTR and the right RTL.
        assertEquals(0f, raftSliderFraction(10f, 200f, 10f, 180f, rtl = false))
        assertEquals(1f, raftSliderFraction(10f, 200f, 10f, 180f, rtl = true))
        assertEquals(0.25f, raftSliderFraction(55f, 200f, 10f, 180f, rtl = false))
        assertEquals(0.75f, raftSliderFraction(55f, 200f, 10f, 180f, rtl = true))
        assertEquals(70, raftSliderPageStep(320, 1080, 10))
        assertEquals(1, raftSliderPageStep(0, 5, 1), "at least one step")
        assertEquals(10, raftSliderPageStep(0, 100, 1))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.sliderNode() =
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
}
