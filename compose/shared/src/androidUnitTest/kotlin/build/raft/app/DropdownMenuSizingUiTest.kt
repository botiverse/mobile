package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftMetrics
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies command menu sizing (`botiverse/slock#17084`, `raft-ui 0.106.1`):
 * - [RaftUiControlledMenu] grows past its 240dp minimum on a 390dp phone to keep long items on
 *   one line, while staying inside the viewport gaps.
 * - On a narrow 220dp viewport, [RaftUiControlledMenu] caps at the available screen width without
 *   overflowing and wraps the label (up to `maxLines = 2`).
 * - [RaftBaseMenuOverlay] grows past its legacy 280dp cap to keep long items single-line within
 *   the viewport.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-mdpi")
class DropdownMenuSizingUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = config[SemanticsActions.GetTextLayoutResult].action
        assertTrue(action?.invoke(layouts) == true && layouts.isNotEmpty(), "expected text layout result")
        return layouts.first()
    }

    @Test
    fun controlledMenuGrowsPastMinWidthForLongItemOnPhoneSingleLine() =
        runRobolectricComposeUiTest {
            val longLabel = "Channel details and settings — extra"
            var scale = 1f
            setContent {
                scale = LocalDensity.current.density
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        RaftUiControlledMenu(
                            expanded = true,
                            anchor = remember { RaftPopoverAnchor.forRootOffset(Offset(370f, 56f)) },
                            items =
                                listOf(
                                    RaftUiMenuItem(id = "files", label = "Open files", icon = RaftLucideIconName.Folder),
                                    RaftUiMenuItem(
                                        id = "settings",
                                        label = longLabel,
                                        icon = RaftLucideIconName.Settings,
                                        testTag = LongItemTag,
                                    ),
                                ),
                            onSelect = {},
                            onDismiss = {},
                        )
                    }
                }
            }

            waitForIdle()
            val rowBounds = onNodeWithTag(LongItemTag).fetchSemanticsNode().boundsInRoot
            val surfaceWidthDp = (rowBounds.width / scale) + (RaftMetrics.PopupPadding.value * 2f)
            assertTrue(
                surfaceWidthDp > 240f,
                "expected menu surface (${surfaceWidthDp}dp) to grow beyond 240dp minWidth",
            )
            assertTrue(
                surfaceWidthDp <= 390f - 16f + 1f,
                "expected menu surface (${surfaceWidthDp}dp) to stay within 390dp viewport minus 8dp gaps",
            )
            assertTrue(
                rowBounds.left / scale >= 8f,
                "expected menu row left (${rowBounds.left / scale}dp) to clear left viewport gap",
            )
            assertTrue(
                rowBounds.right / scale <= 390f - 8f + 1f,
                "expected menu row right (${rowBounds.right / scale}dp) to clear right viewport gap",
            )
            val layout = onNodeWithText(longLabel, useUnmergedTree = true).fetchSemanticsNode().textLayout()
            assertEquals(1, layout.lineCount, "expected long label to render on 1 line when phone viewport fits it")
        }

    @Test
    @Config(sdk = [34], qualifiers = "w220dp-h640dp-mdpi")
    fun controlledMenuCapsAtAvailableViewportWidthOnNarrowScreenAndWraps() =
        runRobolectricComposeUiTest {
            val longLabel = "Channel details and settings for this workspace"
            var scale = 1f
            setContent {
                scale = LocalDensity.current.density
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        RaftUiControlledMenu(
                            expanded = true,
                            anchor = remember { RaftPopoverAnchor.forRootOffset(Offset(200f, 56f)) },
                            items =
                                listOf(
                                    RaftUiMenuItem(
                                        id = "settings",
                                        label = longLabel,
                                        icon = RaftLucideIconName.Settings,
                                        testTag = LongItemTag,
                                    ),
                                ),
                            onSelect = {},
                            onDismiss = {},
                        )
                    }
                }
            }

            waitForIdle()
            val rowBounds = onNodeWithTag(LongItemTag).fetchSemanticsNode().boundsInRoot
            val surfaceWidthDp = (rowBounds.width / scale) + (RaftMetrics.PopupPadding.value * 2f)
            assertTrue(
                surfaceWidthDp <= 220f - 16f + 1f,
                "expected narrow menu surface (${surfaceWidthDp}dp) to cap at available viewport width (204dp)",
            )
            assertTrue(
                rowBounds.left / scale >= 8f,
                "expected narrow menu row left (${rowBounds.left / scale}dp) to clear left viewport gap",
            )
            assertTrue(
                rowBounds.right / scale <= 220f - 8f + 1f,
                "expected narrow menu row right (${rowBounds.right / scale}dp) to clear right viewport gap",
            )
            val layout = onNodeWithText(longLabel, useUnmergedTree = true).fetchSemanticsNode().textLayout()
            assertEquals(2, layout.lineCount, "expected long label to wrap to 2 lines on narrow 220dp viewport")
        }

    @Test
    fun baseMenuOverlayGrowsBeyondLegacy280DpCapForLongItemSingleLine() =
        runRobolectricComposeUiTest {
            val longLabel = "Move channel to a longer custom section"
            var scale = 1f
            setContent {
                scale = LocalDensity.current.density
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        RaftBaseMenuOverlay(
                            anchor = remember { RaftPopoverAnchor.forRootOffset(Offset(370f, 56f)) },
                            items =
                                listOf(
                                    RaftBaseMenuItem(id = "pin", label = "Pin", icon = RaftLucideIconName.Pin),
                                    RaftBaseMenuItem(id = "move", label = longLabel, icon = RaftLucideIconName.Settings),
                                ),
                            selectedId = "",
                            onDismiss = {},
                            onSelect = {},
                        )
                    }
                }
            }

            waitForIdle()
            val rowBounds = onNodeWithText(longLabel).fetchSemanticsNode().boundsInRoot
            val surfaceWidthDp = (rowBounds.width / scale) + (RaftMetrics.PopupPadding.value * 2f)
            assertTrue(
                surfaceWidthDp > 280f,
                "expected base menu surface (${surfaceWidthDp}dp) to grow beyond legacy 280dp cap",
            )
            assertTrue(
                surfaceWidthDp <= 390f - 16f + 1f,
                "expected base menu surface (${surfaceWidthDp}dp) to stay within 390dp viewport minus 8dp gaps",
            )
            val layout = onNodeWithText(longLabel, useUnmergedTree = true).fetchSemanticsNode().textLayout()
            assertEquals(1, layout.lineCount, "expected base menu label to render on 1 line when phone viewport fits it")
        }

    private companion object {
        const val LongItemTag = "dropdown-menu-sizing-long-item"
    }
}
