package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputersRackView
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueComputersRackPreferenceRepository
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Computers page's toolbar on RaftFitToolbar (web ComputersRackToolbar / ComputersRackOverview,
 * slock #14149, 561b055ce6, 20eee4ecde): Cards / List and Add Computer at its end, the toolbar shown
 * while the list loads, with no computers and after a failed load (the empty state then has no
 * button of its own), and system Back closing a search that has given way, text first, only while
 * it is drawn over the line.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
class ComputersRackFitToolbarUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun computer(id: String, name: String) =
        SettingsComputerSnapshot(
            id = id,
            name = name,
            daemonVersion = "",
            computerVersion = "1.0.53",
            isComputer = true,
            online = true,
            latestComputerVersion = "1.0.53",
        )

    private val loaded =
        raftComputersListModel(
            ScreenLoadState.Content(SettingsComputersSnapshot(computers = listOf(computer("m-a", "alpha"), computer("m-b", "beta")), canAddComputer = true)),
        )

    private fun store() =
        RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(InMemoryRaftKeyValueStore())).apply { onServer("server-1") }

    /** System Back, and how many reached the page (a handler composed before the toolbar's). */
    private class Back(val press: () -> Unit, val pageBacks: () -> Int)

    private fun ComposeUiTest.show(
        model: RaftComputersListModel,
        store: RaftComputersRackToolbarStore = store(),
        width: Dp? = null,
        onAdd: () -> Unit = {},
    ): Back {
        var dispatcher: OnBackPressedDispatcher? = null
        var pageBacks = 0
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            // The page's own Back (closing it), under any the toolbar adds.
            RaftBackHandler(enabled = true, label = "test_page") { pageBacks++ }
            val toolbar by store.state.collectAsState()
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    Sized(width) {
                        RaftSettingsComputersContent(
                            model = model,
                            onRetry = {},
                            onAddComputer = onAdd,
                            onOpenComputer = {},
                            rackView = toolbar.view,
                            toolbar = toolbar,
                            onToolbarIntent = store::dispatch,
                            serverId = "server-1",
                        )
                    }
                }
            }
        }
        waitForIdle()
        return Back(
            press = {
                runOnIdle { assertNotNull(dispatcher, "the activity's Back dispatcher").onBackPressed() }
                waitForIdle()
            },
            pageBacks = { pageBacks },
        )
    }

    @Composable
    private fun Sized(width: Dp?, content: @Composable () -> Unit) {
        if (width == null) content() else Box(Modifier.width(width)) { content() }
    }

    private fun ComposeUiTest.has(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun cardsListAndAddComputerAreAtTheToolbarsEnd() = runRobolectricComposeUiTest {
        val store = store()
        var added = 0
        show(loaded, store, onAdd = { added++ })
        onNodeWithTag("${RaftComputersRackTags.View}-list").performClick()
        waitForIdle()
        assertEquals(ComputersRackView.List, store.state.value.view)
        onNodeWithTag(RaftComputersRackTags.Add).performClick()
        assertEquals(1, added)
    }

    @Test
    fun theToolbarStaysWhileLoadingWithNoComputersAndAfterAFailedLoad() = runRobolectricComposeUiTest {
        var model by mutableStateOf<RaftComputersListModel>(RaftComputersListModel.Loading)
        val store = store()
        setContent {
            val toolbar by store.state.collectAsState()
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftSettingsComputersContent(
                        model = model,
                        onRetry = {},
                        onAddComputer = {},
                        onOpenComputer = {},
                        toolbar = toolbar,
                        onToolbarIntent = store::dispatch,
                        serverId = "server-1",
                        skeletonAnimated = false,
                    )
                }
            }
        }
        waitForIdle()
        assertTrue(has(RaftComputersRackTags.Toolbar) && has(RaftComputersRackTags.Search), "the toolbar while the list loads")
        assertFalse(has(RaftComputersRackTags.Add), "no Add Computer before the list says who may add")

        model = RaftComputersListModel.Empty(canAddComputer = true)
        waitForIdle()
        assertTrue(has(RaftComputersRackTags.Toolbar), "the toolbar with no computers")
        assertTrue(has(RaftComputersRackTags.Add), "Add Computer at the toolbar's end with no computers")
        assertEquals(1, onAllNodes(hasText("Add Computer")).fetchSemanticsNodes().size, "the empty state has no button of its own")

        model = RaftComputersListModel.Failed(RaftComputersFailure(noAccess = false, retryable = true, code = "network"))
        waitForIdle()
        assertTrue(has(RaftComputersRackTags.Toolbar), "the toolbar after a failed load")

        model = RaftComputersListModel.Failed(RaftComputersFailure(noAccess = true, retryable = false, code = "forbidden"))
        waitForIdle()
        assertFalse(has(RaftComputersRackTags.Toolbar), "a permission wall has no toolbar")
    }

    @Test
    fun backClearsAGivenWaySearchThenLeavesBackAlone() = runRobolectricComposeUiTest {
        val store = store()
        // Narrow enough that the search gives way to its button: with text it stays drawn over the line.
        val back = show(loaded, store, width = 150.dp)
        runOnIdle { store.dispatch(RaftComputersRackToolbarIntent.Search("alp")) }
        // The measure reports the search over the line; its effect then turns the Back handler on.
        mainClock.advanceTimeByFrame()
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        back.press()
        assertEquals("", store.state.value.filters.query, "Back clears the text first")
        assertEquals(0, back.pageBacks(), "Back is the search's while it covers the line")
        back.press()
        assertEquals(1, back.pageBacks(), "a closed, empty search leaves Back to the page")
    }

    @Test
    fun anInlineSearchNeverTakesBack() = runRobolectricComposeUiTest {
        val store = store()
        val back = show(loaded, store)
        runOnIdle { store.dispatch(RaftComputersRackToolbarIntent.Search("alp")) }
        waitForIdle()
        assertTrue(has(RaftComputersRackTags.Search))
        back.press()
        assertEquals(1, back.pageBacks(), "a wide toolbar's search is inline: Back stays the page's")
        assertEquals("alp", store.state.value.filters.query)
    }
}
