package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftFitToolbar
import build.raft.designsystem.components.RaftFitToolbarAction
import build.raft.designsystem.components.RaftFitToolbarChoice
import build.raft.designsystem.components.RaftFitToolbarOption
import build.raft.designsystem.components.RaftFitToolbarPrimary
import build.raft.designsystem.components.RaftFitToolbarSearch
import build.raft.designsystem.components.RaftFitToolbarView
import build.raft.designsystem.components.RaftFitToolbarViewOption
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The RaftFitToolbar parts the pages use beyond the search and the scope (review of #4627):
 * - `filtersEnd` (web Computers' and Tasks' Clear all): its words while the filters have theirs,
 *   its × once the filters give way, and still × beside the filters wrapped to their own line;
 * - `view` (web `ListViewSwitch`): the first part to give way (its icons alone), and folded into
 *   ⋯ More under its heading ("View as");
 * - a choice's own `menu`: shown instead of its options, open until it calls `close`;
 * - a choice's `onOpen`: the trigger calls it and opens no menu.
 */
@OptIn(ExperimentalTestApi::class)
class RaftFitToolbarPartsTest {
    @Test
    fun clearAllHasItsWordsThenItsIconOnceTheFiltersGiveWay() =
        runComposeUiTest {
            var width by mutableStateOf(1024.dp)
            var cleared = 0
            setContent {
                Sized(width) {
                    RaftFitToolbar(
                        filters = listOf(filter("status", "Status", applied = true), filter("runtime", "Runtime")),
                        filtersEnd = RaftFitToolbarAction(text = "Clear all", icon = { Dot() }, onClick = { cleared++ }, testTag = "clear"),
                        primary = RaftFitToolbarPrimary(text = "Add computer", onClick = {}, testTag = "add"),
                    )
                }
            }
            waitForIdle()
            assertTrue(tagged("clear", "Clear all"), "Clear all with its words while the filters have theirs")

            // Narrow until the filters show their icons alone: Clear all is × at the same step.
            assertNotNull(narrowUntil({ width = it }) { !has(hasText("Status")) }, "the filters give way")
            assertTrue(has(hasTestTag("clear")), "Clear all stays")
            assertFalse(tagged("clear", "Clear all"), "as its icon alone")
            assertTrue(has(hasTestTag("clear") and hasContentDescription("Clear all")), "its words its name")
            onNodeWithTag("clear").performClick()
            assertEquals(1, cleared)
        }

    @Test
    fun clearAllStaysItsIconBesideTheWrappedFilters() =
        runComposeUiTest {
            setContent {
                Sized(300.dp) {
                    RaftFitToolbar(
                        search = RaftFitToolbarSearch(value = "", onValueChange = {}, label = "Search computers"),
                        filters = listOf(filter("status", "Status", applied = true), filter("runtime", "Runtime")),
                        filtersEnd = RaftFitToolbarAction(text = "Clear all", icon = { Dot() }, onClick = {}, testTag = "clear"),
                        primary = RaftFitToolbarPrimary(text = "Add computer", onClick = {}, testTag = "add"),
                        wrap = true,
                    )
                }
            }
            waitForIdle()
            val status = onNodeWithTag("status").fetchSemanticsNode().boundsInRoot
            val add = onNodeWithTag("add").fetchSemanticsNode().boundsInRoot
            assertTrue(status.top >= add.bottom, "the filters moved to the second line")
            assertTrue(has(hasText("Status")), "with their words back")
            assertTrue(has(hasTestTag("clear")))
            assertFalse(tagged("clear", "Clear all"), "Clear all stays ×")
        }

    @Test
    fun theViewGivesWayFirstThenFoldsIntoMoreUnderItsHeading() =
        runComposeUiTest {
            var width by mutableStateOf(1024.dp)
            var view by mutableStateOf("cards")
            setContent {
                Sized(width) {
                    RaftFitToolbar(
                        filters = listOf(filter("status", "Status")),
                        view =
                            RaftFitToolbarView(
                                options = listOf(RaftFitToolbarViewOption("cards", "Cards") { Dot() }, RaftFitToolbarViewOption("list", "List") { Dot() }),
                                selectedKey = view,
                                onSelect = { view = it },
                                label = "Computers view",
                                heading = "View as",
                                testTag = "view",
                            ),
                        primary = RaftFitToolbarPrimary(text = "Add computer", onClick = {}),
                        moreLabel = "More",
                    )
                }
            }
            waitForIdle()
            assertTrue(has(hasText("Cards")) && has(hasText("List")), "the views with their words")

            val iconsAt = assertNotNull(narrowUntil({ width = it }) { !has(hasText("Cards")) }, "the view gives way")
            assertTrue(has(hasTestTag("view-cards")), "still on the line, as its icons")
            assertTrue(has(hasText("Status")), "before the filters give way")
            assertTrue(has(hasText("Add computer")), "and before the primary becomes +")
            assertFalse(has(hasContentDescription("More")), "no More yet")

            assertNotNull(narrowUntil({ width = it }, from = iconsAt) { has(hasContentDescription("More")) }, "the view folds into More")
            assertFalse(has(hasTestTag("view-cards")), "off the line")
            onNode(hasContentDescription("More")).performClick()
            waitForIdle()
            assertTrue(has(hasText("View as")), "under its heading")
            onNodeWithTag("view-more-list").performClick()
            waitForIdle()
            assertEquals("list", view)
        }

    @Test
    fun aChoicesOwnMenuShowsInsteadOfItsOptionsAndStaysOpenUntilItCloses() =
        runComposeUiTest {
            var picks = 0
            setContent {
                Sized(1024.dp) {
                    RaftFitToolbar(
                        filters =
                            listOf(
                                RaftFitToolbarChoice(
                                    text = "Runtime",
                                    icon = { Dot() },
                                    options = listOf(RaftFitToolbarOption("a", "Option A")),
                                    selectedKey = null,
                                    onSelect = {},
                                    testTag = "runtime",
                                    menu = { close ->
                                        RaftMenuItem("Pick one", onClick = { picks++ })
                                        RaftMenuItem("Done picking", onClick = close)
                                    },
                                ),
                            ),
                    )
                }
            }
            onNodeWithTag("runtime").performClick()
            waitForIdle()
            assertTrue(has(hasText("Pick one")), "its own menu")
            assertFalse(has(hasText("Option A")), "not its options")
            onNode(hasText("Pick one")).performClick()
            waitForIdle()
            assertEquals(1, picks)
            assertTrue(has(hasText("Pick one")), "open while picking")
            onNode(hasText("Done picking")).performClick()
            waitForIdle()
            assertFalse(has(hasText("Pick one")), "closed once it calls close")
        }

    @Test
    fun aChoiceWithOnOpenCallsItAndOpensNoMenu() =
        runComposeUiTest {
            var opened = 0
            setContent {
                Sized(1024.dp) {
                    RaftFitToolbar(
                        filters =
                            listOf(
                                RaftFitToolbarChoice(
                                    text = "Creator",
                                    icon = { Dot() },
                                    options = listOf(RaftFitToolbarOption("a", "Option A")),
                                    selectedKey = null,
                                    onSelect = {},
                                    testTag = "creator",
                                    onOpen = { opened++ },
                                ),
                            ),
                    )
                }
            }
            onNodeWithTag("creator").performClick()
            waitForIdle()
            assertEquals(1, opened)
            assertFalse(has(hasText("Option A")), "no dropdown of its own")
        }

    @androidx.compose.runtime.Composable
    private fun Sized(width: Dp, content: @androidx.compose.runtime.Composable () -> Unit) {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
            Box(Modifier.width(width)) { content() }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Dot() {
        Spacer(Modifier.size(14.dp))
    }

    private fun filter(tag: String, name: String, applied: Boolean = false) =
        RaftFitToolbarChoice(
            text = name,
            icon = { Dot() },
            options = listOf(RaftFitToolbarOption("any", "Any")),
            selectedKey = "any",
            onSelect = {},
            applied = applied,
            testTag = tag,
        )

    private fun ComposeUiTest.has(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** A node tagged [tag] that shows [text] (not only names it). */
    private fun ComposeUiTest.tagged(tag: String, text: String): Boolean = has(hasTestTag(tag) and hasText(text))

    /** Narrows the toolbar 4dp at a time from [from] until [done]; the width it holds then, or null. */
    private fun ComposeUiTest.narrowUntil(setWidth: (Dp) -> Unit, from: Dp = 1024.dp, done: () -> Boolean): Dp? {
        var width = from
        while (width > 80.dp) {
            width -= 4.dp
            setWidth(width)
            waitForIdle()
            if (done()) return width
        }
        return null
    }
}
