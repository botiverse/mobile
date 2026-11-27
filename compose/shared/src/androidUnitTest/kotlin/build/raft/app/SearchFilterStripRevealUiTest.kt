package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.SearchSort
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.settings.SharedHomeSearchFilters
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The search filter strip keeps an applied filter in view as the web's does (botiverse/slock
 * staging `SearchFilterBar`, raft-ui FitToolbar `useScrollGroup`): Search opened with "Last 7 days"
 * shows that filter, scrolled past From, Scope and Channel, and a filter applied while Search is
 * open comes into view. On this 412dp phone the strip is narrower than its four filters.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h240dp-xxhdpi")
class SearchFilterStripRevealUiTest {
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

    @Test
    fun aFilterAppliedWhenSearchOpensIsInView() =
        runRobolectricComposeUiTest {
            setContent { Host { Filters(search(SearchTimeRange.Last7Days)) } }
            waitForIdle()

            assertInStrip("Last 7 days")
            assertTrue(bounds("From").left < strip().left, "the strip scrolled past From to show the filter")
        }

    @Test
    fun aFilterAppliedWhileSearchIsOpenComesIntoView() =
        runRobolectricComposeUiTest {
            val state = mutableStateOf(search(SearchTimeRange.Any))
            setContent { Host { Filters(state.value) } }
            waitForIdle()
            assertTrue(bounds("Any time").left >= strip().right, "with nothing applied the strip stays at its start")

            state.value = search(SearchTimeRange.Last7Days)
            waitForIdle()

            assertInStrip("Last 7 days")
        }

    private fun ComposeUiTest.strip(): DpRect = onNodeWithTag("search-filter-strip").getUnclippedBoundsInRoot()

    private fun ComposeUiTest.bounds(label: String): DpRect = onNodeWithText(label).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.assertInStrip(label: String) {
        val strip = strip()
        val chip = bounds(label)
        assertTrue(
            chip.left >= strip.left - 0.5.dp && chip.right <= strip.right + 0.5.dp,
            "$label is in view: the chip at ${chip.left}..${chip.right}, the strip at ${strip.left}..${strip.right}",
        )
    }

    private fun search(time: SearchTimeRange) =
        SharedHomeSearchPanelState(serverId = "server-1", query = "release", sort = SearchSort.Recent, timeRange = time)

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light) { content() }
        }
    }

    @Composable
    private fun Filters(state: SharedHomeSearchPanelState) {
        SharedHomeSearchFilters(
            state = state,
            channelFilters = emptyList(),
            scopeAnchorChanged = {},
            senderAnchorChanged = {},
            channelAnchorChanged = {},
            timeAnchorChanged = {},
            sortAnchorChanged = {},
            onOpenFilter = { _, _ -> },
            onIntent = {},
        )
    }
}
