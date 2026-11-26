package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.components.RaftPopupInsets
import build.raft.designsystem.components.RaftPopupMargins
import build.raft.designsystem.components.RaftSafeArea
import build.raft.designsystem.components.raftMenuViewportCap
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies raft-ui 0.106.1 dropdown menu sizing on [RaftMenuSurface] + [raftMenuViewportCap]:
 * items stay single-line while the menu grows up to available viewport width, and only wrap
 * (up to `maxLines = 2`) when the screen truly cannot fit the label.
 */
@OptIn(ExperimentalTestApi::class)
class RaftDropdownMenuSizingTest {
    private fun textLayout(node: androidx.compose.ui.semantics.SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue(action?.invoke(layouts) == true && layouts.isNotEmpty(), "expected text layout result")
        return layouts.first()
    }

    @Test
    fun menuGrowsPastMinWidthToKeepLongLabelSingleLineWithinPhoneViewport() =
        runComposeUiTest {
            val longLabel = "Channel details and settings — extra"
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    val density = LocalDensity.current
                    val windowMargin = with(density) { 8.dp.roundToPx() }
                    val insets =
                        with(density) {
                            RaftPopupMargins(
                                RaftPopupInsets.Horizontal.roundToPx(),
                                RaftPopupInsets.Top.roundToPx(),
                                RaftPopupInsets.Bottom.roundToPx(),
                            )
                        }
                    Box(Modifier.size(width = 390.dp, height = 600.dp)) {
                        Box(
                            Modifier
                                .raftMenuViewportCap(windowMargin, insets, RaftSafeArea())
                                .padding(
                                    start = RaftPopupInsets.Horizontal,
                                    end = RaftPopupInsets.Horizontal,
                                    top = RaftPopupInsets.Top,
                                    bottom = RaftPopupInsets.Bottom,
                                ),
                        ) {
                            RaftMenuSurface(modifier = Modifier.testTag("menu")) {
                                RaftMenuItem(text = "Open files", onClick = {})
                                RaftMenuItem(
                                    text = longLabel,
                                    onClick = {},
                                    leadingIcon = { RaftGlyphIcon(RaftGlyph.Check, size = 16.dp) },
                                )
                            }
                        }
                    }
                }
            }

            val menuBounds = onNodeWithTag("menu").fetchSemanticsNode().boundsInRoot
            val minWidthPx = 240f * density.density
            val maxSurfacePx = (390f - 32f) * density.density
            assertTrue(
                menuBounds.width > minWidthPx,
                "expected menu (${menuBounds.width}px) to grow beyond minWidth (${minWidthPx}px)",
            )
            assertTrue(
                menuBounds.width <= maxSurfacePx + 1f,
                "expected menu (${menuBounds.width}px) to stay within available width (${maxSurfacePx}px)",
            )
            val layout = textLayout(onNodeWithText(longLabel, useUnmergedTree = true).fetchSemanticsNode())
            assertEquals(1, layout.lineCount, "expected long label to stay on 1 line when phone viewport fits it")
        }

    @Test
    fun menuCapsAtAvailableViewportWidthOnNarrowScreenAndWraps() =
        runComposeUiTest {
            val longLabel = "Channel details and settings for this workspace"
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    val density = LocalDensity.current
                    val windowMargin = with(density) { 8.dp.roundToPx() }
                    val insets =
                        with(density) {
                            RaftPopupMargins(
                                RaftPopupInsets.Horizontal.roundToPx(),
                                RaftPopupInsets.Top.roundToPx(),
                                RaftPopupInsets.Bottom.roundToPx(),
                            )
                        }
                    Box(Modifier.size(width = 220.dp, height = 600.dp)) {
                        Box(
                            Modifier
                                .raftMenuViewportCap(windowMargin, insets, RaftSafeArea())
                                .padding(
                                    start = RaftPopupInsets.Horizontal,
                                    end = RaftPopupInsets.Horizontal,
                                    top = RaftPopupInsets.Top,
                                    bottom = RaftPopupInsets.Bottom,
                                ),
                        ) {
                            RaftMenuSurface(modifier = Modifier.testTag("menu")) {
                                RaftMenuItem(
                                    text = longLabel,
                                    onClick = {},
                                    leadingIcon = { RaftGlyphIcon(RaftGlyph.Check, size = 16.dp) },
                                )
                            }
                        }
                    }
                }
            }

            val menuBounds = onNodeWithTag("menu").fetchSemanticsNode().boundsInRoot
            val maxSurfacePx = (220f - 32f) * density.density
            assertTrue(
                menuBounds.width <= maxSurfacePx + 1f,
                "expected menu (${menuBounds.width}px) to cap at available width (${maxSurfacePx}px)",
            )
            val layout = textLayout(onNodeWithText(longLabel, useUnmergedTree = true).fetchSemanticsNode())
            assertEquals(2, layout.lineCount, "expected label wider than narrow viewport to wrap to 2 lines")
        }
}
