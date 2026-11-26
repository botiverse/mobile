package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [RaftAnchoredPopover] single-pass measure-then-place (`contentSize = null`) and
 * [ThreadAppLinkCardPopoverFrame]: dynamic-height content anchored near the bottom of the viewport
 * must be placed above the anchor on its very first drawn frame without flashing below it first,
 * preserve inside/outside hit-testing, and reposition in the same pass if its height changes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftAnchoredPopoverSinglePassPlacementUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun appLinkCardPopoverNearBottomNeverDrawsBelowAnchorOnFirstFrame() =
        runRobolectricComposeUiTest {
            val anchorY = 760f
            val anchor = RaftPopoverAnchor.forRootOffset(Offset(x = 80f, y = anchorY))
            val drawnTops = mutableListOf<Float>()
            val drawnBottoms = mutableListOf<Float>()
            var lastHeightPx = 0f

            setContent {
                Box(Modifier.size(width = 400.dp, height = 840.dp)) {
                    ThreadAppLinkCardPopoverFrame(
                        anchor = anchor,
                        onDismiss = {},
                    ) { frameModifier ->
                        var currentTop = Float.NaN
                        Box(
                            modifier =
                                frameModifier
                                    .height(220.dp)
                                    .testTag(CardTag)
                                    .onGloballyPositioned { coords ->
                                        currentTop = coords.positionInRoot().y
                                        lastHeightPx = coords.size.height.toFloat()
                                    }
                                    .drawWithContent {
                                        if (!currentTop.isNaN()) {
                                            drawnTops += currentTop
                                            drawnBottoms += currentTop + size.height
                                        }
                                        drawContent()
                                    },
                        )
                    }
                }
            }

            waitForIdle()
            assertTrue(drawnTops.isNotEmpty(), "Expected popover card to draw at least once")
            assertTrue(lastHeightPx > 0f, "Expected popover card to have measured non-zero height")
            // Every drawn frame — including frame 1 — must already sit above the bottom anchor.
            drawnBottoms.forEachIndexed { index, bottom ->
                assertTrue(
                    bottom <= anchorY,
                    "Frame $index drew popover below anchor (bottom=$bottom, anchorY=$anchorY, top=${drawnTops[index]})",
                )
            }
        }

    @Test
    fun autoSizedPopoverKeepsInsideTapsAndDismissesOnOutsideTap() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            val anchor = RaftPopoverAnchor.forRootOffset(Offset(x = 80f, y = 760f))

            setContent {
                Box(Modifier.fillMaxSize().testTag(RootTag)) {
                    ThreadAppLinkCardPopoverFrame(
                        anchor = anchor,
                        onDismiss = { dismissed++ },
                    ) { frameModifier ->
                        Box(frameModifier.height(200.dp).testTag(CardTag))
                    }
                }
            }

            waitForIdle()
            // Tapping inside the auto-positioned card must not dismiss it.
            onNodeWithTag(CardTag).performTouchInput {
                down(center)
                up()
            }
            waitForIdle()
            assertEquals(0, dismissed, "Tapping inside the auto-sized popover must not dismiss")

            // Tapping near the top-left corner (far outside the bottom-anchored card) dismisses.
            onNodeWithTag(RootTag).performTouchInput {
                val outside = Offset(x = 20f, y = 20f)
                down(outside)
                up()
            }
            waitForIdle()
            assertEquals(1, dismissed, "Tapping outside the auto-sized popover must dismiss")
        }

    @Test
    fun autoSizedPopoverRepositionsImmediatelyWhenContentHeightGrows() =
        runRobolectricComposeUiTest {
            val anchorY = 620f
            val anchor = RaftPopoverAnchor.forRootOffset(Offset(x = 60f, y = anchorY))
            var cardHeight by mutableStateOf(60.dp)
            var latestTop = Float.NaN
            var latestBottom = Float.NaN

            setContent {
                Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                    RaftAnchoredPopover(
                        anchor = anchor,
                        contentSize = null,
                        onDismiss = {},
                        verticalPlacement = RaftFloatingVerticalPlacement.Auto,
                        dismissActivationDelayMillis = 0L,
                    ) {
                        Box(
                            Modifier
                                .width(200.dp)
                                .height(cardHeight)
                                .onGloballyPositioned { coords ->
                                    latestTop = coords.positionInRoot().y
                                    latestBottom = latestTop + coords.size.height
                                },
                        )
                    }
                }
            }

            waitForIdle()
            assertTrue(latestTop >= anchorY, "Short content fits below anchor (top=$latestTop, anchorY=$anchorY)")

            cardHeight = 260.dp
            waitForIdle()
            assertTrue(latestBottom <= anchorY, "Grown content flips above anchor (bottom=$latestBottom, anchorY=$anchorY)")
        }

    private companion object {
        const val RootTag = "auto-sized-popover-root"
        const val CardTag = "auto-sized-popover-card"
    }
}
