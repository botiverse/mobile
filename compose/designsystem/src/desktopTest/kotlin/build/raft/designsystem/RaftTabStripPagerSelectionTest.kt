package build.raft.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabStrip
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A channel's Chat / Tasks / Files strip above a swipeable pager: however the pager is swiped,
 * exactly one tab is selected and only that tab paints the yellow sticker (a mobile bug left
 * every visited tab yellow).
 */
@OptIn(ExperimentalTestApi::class)
class RaftTabStripPagerSelectionTest {
    private val tabs = listOf("Chat", "Tasks", "Files")

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content)

    @Test
    fun swipingThePagerBackAndForthLeavesOneStickerTab() =
        runComposeUiTest {
            var selected by mutableStateOf(0)
            setContent {
                Themed {
                    Column(Modifier.size(360.dp, 400.dp)) {
                        RaftTabStrip {
                            tabs.forEachIndexed { index, label ->
                                RaftTab(label, selected = index == selected, onClick = { selected = index })
                            }
                        }
                        val pager = rememberPagerState(pageCount = { tabs.size })
                        LaunchedEffect(selected) { if (pager.currentPage != selected) pager.animateScrollToPage(selected) }
                        LaunchedEffect(pager.currentPage) { selected = pager.currentPage }
                        HorizontalPager(pager, Modifier.testTag("pager").fillMaxSize(), beyondViewportPageCount = tabs.size) { page ->
                            Box(Modifier.fillMaxSize()) { RaftText("Page ${tabs[page]}") }
                        }
                    }
                }
            }

            fun assertOnlySelected(expected: Int, step: String) {
                waitForIdle()
                assertEquals(expected, selected, "$step: settled page")
                val image = onRoot().captureToImage().toPixelMap()
                tabs.forEachIndexed { index, label ->
                    val node = onNodeWithText(label)
                    val isSelectedTab = isSelected().matches(node.fetchSemanticsNode())
                    assertEquals(index == expected, isSelectedTab, "$step: $label selected")
                    val bounds = node.fetchSemanticsNode().boundsInRoot
                    // Inside the 1.5dp edge, left of the label: the sticker fill, or the canvas.
                    val x = (bounds.left + 5 * density.density).toInt()
                    val y = bounds.center.y.toInt()
                    val pixel = image[x, y]
                    val brand = RaftUiPalette.brand
                    val isYellow = kotlin.math.abs(pixel.red - brand.red) < 0.02f && kotlin.math.abs(pixel.green - brand.green) < 0.02f && kotlin.math.abs(pixel.blue - brand.blue) < 0.02f
                    assertEquals(index == expected, isYellow, "$step: $label paints the yellow sticker")
                }
            }

            assertOnlySelected(0, "start")
            val path = listOf(1, 2, 1, 0)
            path.forEach { target ->
                onNodeWithTag("pager").performTouchInput { if (target > selected) swipeLeft() else swipeRight() }
                assertOnlySelected(target, "swiped to $target")
            }
        }
}
