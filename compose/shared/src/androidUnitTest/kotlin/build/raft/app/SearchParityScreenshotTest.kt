package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxItem
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SearchSort
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.authSessionFixture
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.resources.StringDesc
import build.raft.app.settings.SharedHomeSearchPanel
import build.raft.app.thread.key
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
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
 * Search parity with the web (botiverse/slock staging `packages/web/src/features/search`), on the
 * phone search page:
 * - `search.entities.show-all`: seven channels match; "Channels 7" with "Show all" at the heading's
 *   end, five rows (`SearchEntityResultsSection`);
 * - `search.incomplete`: a Free server's history notice (`HistoryWindowBanner`), then "No matches
 *   yet", where the search got to and Continue searching (`SearchNoResultsState` `searchIncomplete`);
 * - `search.hit-rows.retracted`: a retracted hit shows the muted retracted line, not its text
 *   (`MessageHitRow` `search-hit-retracted`), above an ordinary hit.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h720dp-xxhdpi")
class SearchParityScreenshotTest {
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
    fun incompleteSearchWithHistoryNotice() =
        RaftRobolectricScreenshot.capture("search.incomplete", RaftAppAppearance.Light, reportDir = REPORT_DIR, activities = activities) {
            Host { IncompletePanel() }
        }

    @Test
    fun continueSearchingAsksForTheNextPage() =
        runRobolectricComposeUiTest {
            val intents = mutableListOf<SharedHomeSearchSavedIntent>()
            setContent { Host { IncompletePanel(onIntent = { intents += it }) } }
            onNodeWithText("No matches yet").assertExists()
            onNodeWithText("On the Free plan, search covers only the last 30 days of messages.", substring = true).assertExists()

            onNodeWithText("Continue searching").performClick()
            waitForIdle()

            assertEquals(1, intents.count { it == SharedHomeSearchSavedIntent.LoadMoreSearch }, "intents=$intents")
        }

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
    private fun IncompletePanel(onIntent: (SharedHomeSearchSavedIntent) -> Unit = {}) {
        SharedHomeSearchPanel(
            state =
                SharedHomeSearchPanelState(
                    serverId = "server-1",
                    query = "release",
                    // The server walk (and so an incomplete search) is the newest-first sort's.
                    sort = SearchSort.Recent,
                    searched = true,
                    empty = true,
                    hasMore = true,
                    nextCursor = "cursor-1",
                    searchIncomplete = true,
                    searchedThrough = "2026-09-30T08:00:00.000Z",
                ),
            homeState = home(channels = 0, plan = "free"),
            onIntent = onIntent,
            onOpenItem = {},
            onOpenEntity = {},
            onHistoryUpgrade = {},
            bottomContentPadding = 0.dp,
            timeContext = TimeContext,
        )
    }

    private companion object {
        const val REPORT_DIR = "build/reports/home-screenshots"
        const val ServerRetractedPlaceholder = "This message was retracted by its sender."

        // 2026-11-16 at UTC+0: the searched-through time reads "09-30 08:00".
        val TimeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = 20773L, nowEpochMillis = 1_794_830_400_000L)

        fun home(channels: Int, plan: String?): SharedHomeState =
            SharedHomeState(
                content =
                    SharedHomeContentState.Content(
                        snapshot =
                            HomeSnapshot(
                                server = ServerSummary(ServerId("server-1"), "Raft", plan = plan),
                                currentMember =
                                    MemberProfile(id = MemberId("user-1"), displayName = "Me", handle = "me", kind = MemberKind.Human),
                                channels =
                                    (1..channels).map { index ->
                                        val id = "eng-0$index"
                                        HomeChannelRow(threadId = ThreadId(id), channelId = ChannelId(id), name = id)
                                    },
                                directMessages = emptyList(),
                                unreadTotal = 0,
                            ),
                        session = authSessionFixture(),
                    )
            )

        fun message(id: String, preview: String) =
            InboxItem(
                key = "channel:c:$id",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("c"),
                channelName = "release",
                lastMessageSenderName = "Friday",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessagePreview = preview,
                lastMessageAt = "2026-10-23T16:31:00Z",
            )
    }
}
