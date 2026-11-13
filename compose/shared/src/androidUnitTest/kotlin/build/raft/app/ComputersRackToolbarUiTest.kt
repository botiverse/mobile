package build.raft.app

import build.raft.app.data.ComputersRackSortKey
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.ComputersRackGroupBy
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
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Computers page's toolbar and list on a real composition, driven through the real toolbar
 * store (web features/computer/tests/computersRackToolbar.behavior.test.tsx, phone): the default
 * Mine first order (web #14479) and a chosen Name sort, with the offline computers open at the end
 * until their title folds them, the search and Status narrowing the list, Clear all and the no-match
 * state, and Group's fold titles.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputersRackToolbarUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun computer(id: String, name: String, online: Boolean = true, owner: String? = null) =
        SettingsComputerSnapshot(
            id = id,
            name = name,
            daemonVersion = "",
            computerVersion = "1.0.53",
            isComputer = true,
            online = online,
            latestComputerVersion = "1.0.53",
            ownerUserId = owner,
        )

    // Server order on purpose: alpha-1 is the viewer's (owned), last in the server's order.
    private val fleet =
        listOf(
            computer("m-beta", "beta"),
            computer("m-a2", "alpha-2"),
            computer("m-zeta", "zeta", online = false),
            computer("m-a1", "alpha-1", owner = "user-1"),
        )

    private fun ComposeUiTest.show(view: ComputersRackView = ComputersRackView.Cards): RaftComputersRackToolbarStore {
        val store = RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(InMemoryRaftKeyValueStore()))
        store.onServer("server-1")
        val model = raftComputersListModel(ScreenLoadState.Content(SettingsComputersSnapshot(computers = fleet)))
        setContent {
            val toolbar by store.state.collectAsState()
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftSettingsComputersContent(
                        model = model,
                        onRetry = {},
                        onAddComputer = {},
                        onOpenComputer = {},
                        rackView = view,
                        toolbar = toolbar,
                        onToolbarIntent = store::dispatch,
                        serverId = "server-1",
                        currentUserId = "user-1",
                    )
                }
            }
        }
        waitForIdle()
        return store
    }

    private fun ComposeUiTest.shown(name: String): Boolean = onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.top(name: String): Float = onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun theListPutsMineFirstUntilANameSortIsChosenAndOfflineComputersWaitOpenAtTheEndUntilFolded() = runRobolectricComposeUiTest {
        val store = show()
        assertTrue(top("alpha-1") < top("beta") && top("beta") < top("alpha-2"), "Mine first, then the server's order")
        runOnIdle { store.dispatch(RaftComputersRackToolbarIntent.ChooseSortKey(ComputersRackSortKey.Name, null)) }
        waitForIdle()
        assertTrue(top("alpha-1") < top("alpha-2") && top("alpha-2") < top("beta"), "Name, A to Z")
        onNodeWithText("zeta").assertIsDisplayed()
        assertTrue(top("beta") < top("zeta"), "the Offline section comes last, open by default")
        onNodeWithTag(RaftComputersRackTags.OfflineTitle).assertIsDisplayed().performClick()
        waitForIdle()
        assertTrue(!shown("zeta"), "tapping the Offline title folds it")
        runOnIdle { store.dispatch(RaftComputersRackToolbarIntent.ChooseSortKey(ComputersRackSortKey.Name, null)) }
        waitForIdle()
        assertTrue(!shown("zeta"), "the folded choice is remembered across sorts")
        onNodeWithTag(RaftComputersRackTags.OfflineTitle).performClick()
        waitForIdle()
        onNodeWithText("zeta").assertIsDisplayed()
    }

    @Test
    fun theSearchNarrowsTheListAndClearAllBringsItBack() = runRobolectricComposeUiTest {
        val store = show()
        assertTrue(onAllNodes(hasTestTagOf(RaftComputersRackTags.Clear)).fetchSemanticsNodes().isEmpty(), "no Clear all at the defaults")
        runOnIdle { store.dispatch(RaftComputersRackToolbarIntent.Search("ALPHA")) }
        waitForIdle()
        assertTrue(shown("alpha-1") && shown("alpha-2") && !shown("beta"), "case-insensitive search over the names")
        onNodeWithTag(RaftComputersRackTags.Clear).performClick()
        waitForIdle()
        assertEquals("", store.state.value.filters.query)
        assertTrue(shown("beta"))
    }

    @Test
    fun statusOfflineListsTheOfflineComputersWithNoSectionAndNoMatchOffersClearAll() = runRobolectricComposeUiTest {
        val store = show()
        onNodeWithTag(RaftComputersRackTags.Status).performClick()
        waitForIdle()
        onNodeWithTag("${RaftComputersRackTags.Status}-offline").performClick()
        waitForIdle()
        assertEquals(RaftComputersRackStatusFilter.Offline, store.state.value.filters.status)
        assertTrue(shown("zeta") && !shown("beta"))
        assertTrue(onAllNodes(hasTestTagOf(RaftComputersRackTags.OfflineTitle)).fetchSemanticsNodes().isEmpty(), "Status: Offline is the list itself")

        runOnIdle { store.dispatch(RaftComputersRackToolbarIntent.Search("nothing like it")) }
        waitForIdle()
        onNodeWithText("No computers match").assertIsDisplayed()
        onNodeWithTag(RaftComputersRackTags.EmptyClear).performClick()
        waitForIdle()
        assertEquals(RaftComputersRackFilters(), store.state.value.filters)
        assertTrue(shown("beta"))
    }

    @Test
    fun groupingByNamePrefixTitlesTheGroupsAndATitleFoldsItsGroup() = runRobolectricComposeUiTest {
        val store = show(ComputersRackView.List)
        runOnIdle { store.dispatch(RaftComputersRackToolbarIntent.SetGroupBy(ComputersRackGroupBy.Prefix)) }
        waitForIdle()
        onNodeWithTag("${RaftComputersRackTags.GroupTitle}-prefix:alpha").assertIsDisplayed()
        // beta has no group of its own: Other, after the named groups.
        assertTrue(top("alpha-2") < top("Other"))
        onNodeWithTag("${RaftComputersRackTags.GroupTitle}-prefix:alpha").performClick()
        waitForIdle()
        assertTrue(!shown("alpha-1") && !shown("alpha-2") && shown("beta"), "the folded group's computers are hidden")
    }

    private fun hasTestTagOf(tag: String) = androidx.compose.ui.test.hasTestTag(tag)
}
