package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.SearchScope
import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SearchSort
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Search tab's filter row (web MessageSearchPage `SearchShellFilters`), at a small (360dp)
 * and a common (412dp) phone width, light and dark. Top: no filter set, every chip labelled
 * (From, Scope, Channel, Any Time, Relevant). Middle: every filter set, each chip naming its
 * value (From: Me, Scope 2, design, Last 30 Days, Recent). Bottom: long values; the sender chip
 * is capped at the row's width and ends in an ellipsis, the rest scroll sideways.
 * Guards the label of a picker trigger in a `LazyRow` (unbounded width), which once measured
 * to nothing and left icon-only chips. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchFilterRowScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test @Config(sdk = [34], qualifiers = "w360dp-h230dp-xxhdpi")
    fun filterRow360Light() = capture("search.filter-row.360", RaftAppAppearance.Light)

    @Test @Config(sdk = [34], qualifiers = "w360dp-h230dp-xxhdpi")
    fun filterRow360Dark() = capture("search.filter-row.360.dark", RaftAppAppearance.Dark)

    @Test @Config(sdk = [34], qualifiers = "w412dp-h230dp-xxhdpi")
    fun filterRow412Light() = capture("search.filter-row.412", RaftAppAppearance.Light)

    @Test @Config(sdk = [34], qualifiers = "w412dp-h230dp-xxhdpi")
    fun filterRow412Dark() = capture("search.filter-row.412.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    SearchFilterRowContent()
                }
            }
        }
    }
}

private val longChannel =
    SearchChannelFilterItem(id = "c-long", label = "proj-mobile-design-system-migration", icon = RaftLucideIconName.Hash)

private val shortChannel = SearchChannelFilterItem(id = "c-design", label = "design", icon = RaftLucideIconName.Hash)

private val filtersSet =
    SharedHomeSearchPanelState(
        serverId = "server-1",
        query = "release",
        sort = SearchSort.Recent,
        timeRange = SearchTimeRange.Last30Days,
        selectedSenderId = "m-self",
        selectedScopes = listOf(SearchScope.Mentioned, SearchScope.Agents),
        senderFilters =
            listOf(
                SearchSenderFilterItem(
                    id = "m-self",
                    label = "Artin",
                    handle = "artin",
                    kind = MemberKind.Human,
                    sourceServerId = ServerId("server-1"),
                    isSelf = true,
                )
            ),
        selectedChannelId = shortChannel.id,
    )

private val longValues =
    SharedHomeSearchPanelState(
        serverId = "server-1",
        query = "release",
        sort = SearchSort.Recent,
        timeRange = SearchTimeRange.Last30Days,
        selectedSenderId = "m-long",
        selectedScopes = listOf(SearchScope.Mentioned, SearchScope.Agents),
        senderFilters =
            listOf(
                SearchSenderFilterItem(
                    id = "m-long",
                    label = "Bartholomew Featherstonehaugh-Wolfeschlegelstein",
                    handle = "bartholomew",
                    kind = MemberKind.Human,
                    sourceServerId = ServerId("server-1"),
                )
            ),
        selectedChannelId = longChannel.id,
    )

@Composable
private fun SearchFilterRowContent() {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        SearchFilterRow(SharedHomeSearchPanelState(serverId = "server-1"))
        SearchFilterRow(filtersSet)
        SearchFilterRow(longValues)
    }
}

@Composable
private fun SearchFilterRow(state: SharedHomeSearchPanelState) {
    SharedHomeSearchFilters(
        state = state,
        channelFilters = listOf(shortChannel, longChannel),
        scopeAnchorChanged = {},
        senderAnchorChanged = {},
        channelAnchorChanged = {},
        timeAnchorChanged = {},
        sortAnchorChanged = {},
        onOpenFilter = { _, _ -> },
        onIntent = {},
    )
}
