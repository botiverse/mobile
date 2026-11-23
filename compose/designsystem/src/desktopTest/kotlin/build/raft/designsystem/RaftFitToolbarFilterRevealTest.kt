package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftFitToolbar
import build.raft.designsystem.components.RaftFitToolbarAction
import build.raft.designsystem.components.RaftFitToolbarChoice
import build.raft.designsystem.components.RaftFitToolbarOption
import build.raft.designsystem.components.RaftFitToolbarSearch
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [RaftFitToolbar]'s scrolling filter strip keeps an applied filter in view like web raft-ui
 * FitToolbar `useScrollGroup` (`RaftScrollIntoViewGroup`):
 * - on open: the first applied filter is scrolled into view, clear of the edge fade, while
 *   subsequent applied filters do not pull the strip away; default (no applied filter) stays at start;
 * - on change: a filter that turns applied (or an applied filter that reorders off-screen) scrolls
 *   into view;
 * - after the user manually scrolls the strip, a resize does not jump back to the previously
 *   revealed filter.
 */
@OptIn(ExperimentalTestApi::class)
class RaftFitToolbarFilterRevealTest {
    @Test
    fun opensWithOffscreenAppliedFilterInViewAndKeepsDefaultAtStart() =
        runComposeUiTest {
            // Default: nothing applied -> stays at start, 4th filter past the right edge.
            setContent {
                Toolbar(width = 240.dp, filters = fourFilters(applied = emptySet()))
            }
            waitForIdle()
            assertAtStart("f1")
            assertTrue(bounds("f4").right > strip().right, "f4 overflows past the right edge before scrolling")

            // Re-mount opened with the 4th filter applied -> 4th filter scrolls into view on open.
            setContent {
                Toolbar(width = 240.dp, filters = fourFilters(applied = setOf("f4")))
            }
            waitForIdle()
            assertInStrip("f4")
            assertTrue(bounds("f1").left < strip().left, "strip scrolled past f1 on open to reveal f4")
        }

    @Test
    fun opensWithMultipleAppliedFiltersShowingOnlyTheFirst() =
        runComposeUiTest {
            setContent {
                Toolbar(width = 240.dp, filters = fourFilters(applied = setOf("f2", "f4")))
            }
            waitForIdle()
            assertInStrip("f2")
            assertTrue(bounds("f4").right > strip().right, "second applied filter does not steal initial reveal from first")
        }

    @Test
    fun scrollsAppliedFilterIntoViewOnChangeAndWhenReorderedOffScreenLeft() =
        runComposeUiTest {
            var applied by mutableStateOf(emptySet<String>())
            var reordered by mutableStateOf(false)
            setContent {
                val base = fourFilters(applied = applied)
                val ordered = if (reordered) listOf(base[3], base[0], base[1], base[2]) else base
                Toolbar(
                    width = 240.dp,
                    filters = ordered,
                    clearAll = applied.isNotEmpty(),
                )
            }
            waitForIdle()
            assertAtStart("f1")
            assertTrue(bounds("f4").right > strip().right, "f4 starts off-screen to the right")

            // Applying f4 scrolls it into view (clear of the Clear all button and fade).
            applied = setOf("f4")
            waitForIdle()
            assertInStrip("f4")
            assertTrue(bounds("f1").left < strip().left, "strip scrolled right to reveal f4")

            // Reordering the already-applied f4 to index 0 (off-screen left) without changing its
            // text/size brings it back into view at the start.
            reordered = true
            waitForIdle()
            assertInStrip("f4")
            assertAtStart("f4")
        }

    @Test
    fun manualUserScrollIsNotUndoneWhenStripResizes() =
        runComposeUiTest {
            var width by mutableStateOf(240.dp)
            var applied by mutableStateOf(emptySet<String>())
            setContent {
                Toolbar(width = width, filters = fourFilters(applied = applied))
            }
            waitForIdle()

            // Apply f4 -> strip jumps right to reveal f4.
            applied = setOf("f4")
            waitForIdle()
            assertInStrip("f4")
            assertTrue(bounds("f1").left < strip().left)

            // User drags the strip back toward the start so f1 is visible and f4 is off-screen.
            onNodeWithTag(StripTag).performTouchInput { swipeRight() }
            waitForIdle()
            assertTrue(bounds("f1").left >= strip().left - 1.dp, "user scrolled back to f1")
            val scrolledLeft = bounds("f1").left

            // Resizing the strip must respect the user's scroll instead of jumping back to f4.
            width = 260.dp
            waitForIdle()
            assertTrue(
                bounds("f1").left >= strip().left - 1.dp && bounds("f4").right > strip().right,
                "resize after manual scroll keeps f1 in view (f1.left=${bounds("f1").left}, was $scrolledLeft; f4=${bounds("f4")})",
            )
        }

    @Test
    fun applyingFilterAfterManualScrollStillRevealsNewFilter() =
        runComposeUiTest {
            var applied by mutableStateOf(emptySet<String>())
            setContent {
                Toolbar(width = 240.dp, filters = fourFilters(applied = applied))
            }
            waitForIdle()

            // User manually scrolls right, then applies f1 (now off-screen left).
            onNodeWithTag(StripTag).performTouchInput { swipeLeft() }
            waitForIdle()
            assertTrue(bounds("f1").left < strip().left, "user scrolled past f1")

            applied = setOf("f1")
            waitForIdle()
            assertInStrip("f1")
            assertAtStart("f1")
        }

    @Test
    fun wideAppliedFilterStaysInStripWithoutBouncingPastEndEdge() =
        runComposeUiTest {
            var applied by mutableStateOf(false)
            setContent {
                // 230dp toolbar -> 158dp filter strip beside Clear all: "Claude Code" (~139dp)
                // fits in the strip but cannot clear 24dp on both sides at once (139 + 48 > 158).
                Toolbar(
                    width = 230.dp,
                    filters =
                        listOf(
                            choice("f1", "All statuses", applied = true),
                            choice("f2", if (applied) "Claude Code" else "Runtime", applied = applied),
                        ),
                    clearAll = true,
                )
            }
            waitForIdle()
            assertTrue(bounds("f2").right > strip().right, "f2 overflows past the right edge before apply")

            applied = true
            waitForIdle()
            assertInStrip("f2")
        }

    @Test
    fun appliedFilterWiderThanTheStripShowsItsStart() =
        runComposeUiTest {
            // Search's long sender name: the applied chip is wider than the strip, so it cannot be
            // all in view; its start shows, rather than the strip scrolling to its end.
            setContent {
                Toolbar(
                    width = 230.dp,
                    filters =
                        listOf(
                            choice("f1", "Bartholomew Featherstonehaugh-Wolfeschlegelstein", applied = true),
                            choice("f2", "Status", applied = false),
                        ),
                    clearAll = true,
                )
            }
            waitForIdle()
            assertTrue(bounds("f1").let { it.right - it.left } > strip().let { it.right - it.left }, "f1 is wider than the strip")
            assertAtStart("f1")
        }

    @Composable
    private fun Toolbar(
        width: Dp,
        filters: List<RaftFitToolbarChoice>,
        clearAll: Boolean = false,
    ) {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
            Box(Modifier.width(width)) {
                RaftFitToolbar(
                    search = RaftFitToolbarSearch(value = "", onValueChange = {}, label = "Search"),
                    filters = filters,
                    filtersEnd =
                        if (clearAll) {
                            RaftFitToolbarAction(text = "Clear all", icon = { Spacer(Modifier.size(14.dp)) }, onClick = {})
                        } else {
                            null
                        },
                    wrap = true,
                    filtersTestTag = StripTag,
                )
            }
        }
    }

    private fun fourFilters(applied: Set<String>): List<RaftFitToolbarChoice> =
        listOf(
            choice("f1", "Channel", "f1" in applied),
            choice("f2", "Status", "f2" in applied),
            choice("f3", "Creator", "f3" in applied),
            choice("f4", "Assignee", "f4" in applied),
        )

    private fun choice(tag: String, name: String, applied: Boolean) =
        RaftFitToolbarChoice(
            text = name,
            icon = { Spacer(Modifier.size(14.dp)) },
            options = listOf(RaftFitToolbarOption("a", "A")),
            selectedKey = if (applied) "a" else null,
            onSelect = {},
            applied = applied,
            testTag = tag,
            key = tag,
        )

    private fun ComposeUiTest.strip(): DpRect = onNodeWithTag(StripTag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.assertInStrip(tag: String) {
        val s = strip()
        val c = bounds(tag)
        assertTrue(
            c.left >= s.left - 0.5.dp && c.right <= s.right + 0.5.dp,
            "$tag is in view: chip ${c.left}..${c.right}, strip ${s.left}..${s.right}",
        )
    }

    private fun ComposeUiTest.assertAtStart(tag: String) {
        val s = strip()
        val c = bounds(tag)
        assertTrue(
            c.left >= s.left - 0.5.dp && c.left <= s.left + 1.dp,
            "$tag stays at strip start: chip.left=${c.left}, strip.left=${s.left}",
        )
    }

    private companion object {
        const val StripTag = "fit-toolbar-filters"
    }
}
