package build.raft.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftChoiceListSearch
import build.raft.designsystem.components.RaftMultiChoiceList
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * raft-ui ChoiceList as a question card's options (web ActionCardChoiceField): a tap on the
 * chosen row still reaches the caller, `search="auto"` shows the field only above 5 items, and
 * the plain-column list sits in a form that scrolls.
 */
@OptIn(ExperimentalTestApi::class)
class RaftChoiceListUiTest {
    private fun options(count: Int) = (1..count).map { RaftChoiceListItem("o$it", "Option $it") }

    @Test
    fun aTapOnTheChosenRowReachesOnItemClickButNotOnValueChange() =
        runComposeUiTest {
            var value by mutableStateOf<String?>("o1")
            val changes = mutableListOf<String>()
            val taps = mutableListOf<String>()
            setContent {
                RaftTheme {
                    RaftChoiceList(
                        items = options(3),
                        value = value,
                        onValueChange = {
                            changes += it
                            value = it
                        },
                        searchPlaceholder = "Search",
                        clearSearchLabel = "Clear",
                        noMatchLabel = "No matches",
                        emptyLabel = "Nothing",
                        onItemClick = { taps += it },
                    )
                }
            }
            onNodeWithText("Option 1").performClick()
            onNodeWithText("Option 2").performClick()
            waitForIdle()
            assertEquals(listOf("o2"), changes, "only a new choice changes the value")
            assertEquals(listOf("o1", "o2"), taps, "every tap is reported, the chosen row's too")
        }

    @Test
    fun autoSearchShowsOnlyAboveFiveItemsAndClearsItsTextWhenItGoes() =
        runComposeUiTest {
            var items by mutableStateOf(options(5))
            var query by mutableStateOf("")
            setContent {
                RaftTheme {
                    RaftMultiChoiceList(
                        items = items,
                        values = emptySet(),
                        onValuesChange = {},
                        query = query,
                        onQueryChange = { query = it },
                        searchPlaceholder = "Search options",
                        clearSearchLabel = "Clear",
                        noMatchLabel = "No matches",
                        emptyLabel = "Nothing",
                        search = RaftChoiceListSearch.Auto,
                        lazy = false,
                    )
                }
            }
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            items = options(6)
            waitForIdle()
            onAllNodes(hasSetTextAction()).assertCountEquals(1)
            query = "Option 6"
            waitForIdle()
            onNodeWithText("Option 1").assertDoesNotExist()
            items = options(5)
            waitForIdle()
            assertEquals("", query, "the field goes, and its text with it")
            onNodeWithText("Option 1").assertExists()
        }

    @Test
    fun aPlainColumnListTicksRowsInsideAScrollingForm() =
        runComposeUiTest {
            var values by mutableStateOf(setOf<String>())
            setContent {
                RaftTheme {
                    // A lazy list here would be measured with an unbounded height; the plain column is not lazy.
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        RaftMultiChoiceList(
                            items = options(4),
                            values = values,
                            onValuesChange = { values = it },
                            query = "",
                            onQueryChange = {},
                            searchPlaceholder = "Search options",
                            clearSearchLabel = "Clear",
                            noMatchLabel = "No matches",
                            emptyLabel = "Nothing",
                            search = RaftChoiceListSearch.Auto,
                            lazy = false,
                        )
                    }
                }
            }
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            onNodeWithText("Option 3").performClick()
            onNodeWithText("Option 4").performClick()
            waitForIdle()
            assertEquals(setOf("o3", "o4"), values)
        }
}
