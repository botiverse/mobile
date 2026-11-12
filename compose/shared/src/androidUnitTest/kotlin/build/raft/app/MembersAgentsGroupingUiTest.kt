package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.MembersAgentsGrouping
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Members page's Agents list is one list by default and By computer once the reader picks it,
 * the choice kept (web Members `GroupSelect`, slock #14068 `DEFAULT_AGENTS_GROUPING = "none"`).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MembersAgentsGroupingUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        store: MembersGroupingTestStore = MembersGroupingTestStore(grouping = null),
        showGuests: Boolean = true,
    ) {
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                MembersListVisualContent(store = store, showGuests = showGuests)
            }
        }
    }

    /**
     * The tabs, as the toolbar shows them at this width: the segmented control, or the Select it
     * gives way to on a narrow phone (RaftFitToolbar step 6).
     */
    private fun androidx.compose.ui.test.ComposeUiTest.showTab(tab: MembersTab) {
        val select = "$MembersScopeTestTag-select"
        if (onAllNodesWithTag(select).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithTag(select).performClick()
            onNodeWithTag("$select-${tab.name}").performClick()
        } else {
            onNodeWithTag("$MembersScopeTestTag-${tab.name}").performClick()
        }
    }

    /** The Group menu on the toolbar, or in ⋯ More once it has folded there (RaftFitToolbar step 7). */
    private fun androidx.compose.ui.test.ComposeUiTest.pickGrouping(grouping: MembersAgentsGrouping) {
        if (onAllNodesWithTag(MembersAgentsGroupActionTestTag).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithTag(MembersAgentsGroupActionTestTag).performClick()
        } else {
            onNodeWithContentDescription("More").performClick()
        }
        onNodeWithTag("$MembersAgentsGroupActionTestTag-${grouping.name}").performClick()
    }

    @Test
    fun agentsAreOneListUntilTheReaderGroupsThemByComputer() =
        runRobolectricComposeUiTest {
            val store = MembersGroupingTestStore(grouping = null)
            show(store)
            // The All tab: people first, then agents, by name; no Group menu here.
            onNodeWithText("Tenny").assertExists()
            onNodeWithText("artin").assertExists()
            onAllNodesWithTag(MembersAgentsGroupActionTestTag).assertCountEquals(0)

            // The Agents tab: No grouping until the reader picks one.
            showTab(MembersTab.Agents)
            onAllNodesWithText("artin").assertCountEquals(0)
            onNodeWithText("Volta").assertExists()
            onAllNodesWithText("MacBook Pro", substring = true).assertCountEquals(0)

            pickGrouping(MembersAgentsGrouping.Computer)

            onAllNodesWithText("MacBook Pro", substring = true).assertCountEquals(1)
            onAllNodesWithText("daybed-earth", substring = true).assertCountEquals(1)
            assertEquals(MembersAgentsGrouping.Computer, store.grouping)
        }

    @Test
    fun theSearchMatchesNameOrHandleAcrossTabs() =
        runRobolectricComposeUiTest {
            show()
            onNodeWithTag(MembersSearchTestTag).performTextInput("vol")
            onNodeWithText("Volta").assertExists()
            onAllNodesWithText("Tenny").assertCountEquals(0)
            onAllNodesWithText("artin").assertCountEquals(0)

            onNodeWithTag(MembersSearchTestTag).performTextReplacement("nobody")
            onNodeWithText("No members match “nobody”.").assertExists()
        }

    @Test
    fun guestsAreListedForOwnersAndAdminsOnly() =
        runRobolectricComposeUiTest {
            show(showGuests = false)
            onAllNodesWithText("Quill").assertCountEquals(0)
            onNodeWithText("Artea").assertExists()
        }
}
