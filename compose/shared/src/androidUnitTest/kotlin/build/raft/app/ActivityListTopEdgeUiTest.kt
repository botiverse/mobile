package build.raft.app

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.SharedActivityPresentationStore
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * artin (1.22): at the top of Activity, new activity landed above the viewport with no hint and he
 * found himself "on the second screen". Drives the real [SharedActivityList] (day headings on, as
 * #4410 ships them) and checks the web rule (slock ThreadsInbox `isNearTopRef = scrollTop < 50`):
 * a list within 50dp of its top shows the new top; a reader further down keeps their row and gets
 * the "↑ N new updates" pill, whose tap goes back to the top.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ActivityListTopEdgeUiTest {
    @get:Rule val activities = RobolectricComposeActivityRule()

    /** 2026-11-05T12:00:00Z. */
    private val timeContext =
        RaftTimeContext(localOffsetMinutes = 0, timeFormat = "24h")
            .refreshedAt(parseRaftTimestampEpochMillisOrNull("2026-11-05T12:00:00Z")!!)

    private fun row(key: String, at: String, message: String = "$key-m1") =
        InboxItem(
            key = key,
            kind = InboxItemKind.Channel,
            channelId = ChannelId(key),
            channelName = key,
            lastMessageId = MessageId(message),
            lastMessagePreview = "Preview of $key",
            lastMessageAt = at,
            latestActivityAt = at,
        )

    /** Forty rows from yesterday, newest first: one "Yesterday" heading, then the rows. */
    private val rows = (0 until 40).map { index -> row("row$index", "2026-11-04T%02d:00:00Z".format(22 - index / 2)) }

    private fun snapshot(items: List<InboxItem>) = InboxSnapshot(server = ServerSummary(ServerId("server-1"), "Raft"), items = items)

    private class Harness(val items: MutableState<List<InboxItem>>, val listState: LazyListState) {
        lateinit var scope: CoroutineScope
    }

    private fun ComposeUiTest.mount(): Harness {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val harness = Harness(mutableStateOf(rows), LazyListState())
        val store = SharedActivityPresentationStore()
        setContent {
            harness.scope = rememberCoroutineScope()
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light) {
                SharedActivityList(
                    active = true,
                    snapshot = snapshot(harness.items.value),
                    selectedFilter = InboxFilter.All,
                    senderProfiles = emptyMap(),
                    refreshing = false,
                    pullRefreshing = false,
                    loadingMore = false,
                    loadMoreErrorCode = null,
                    onOpenItem = {},
                    onIntent = {},
                    bottomContentPadding = 0.dp,
                    timeContext = timeContext,
                    listState = harness.listState,
                    activityTaskStatusBadgesEnabled = false,
                    presentationAccountId = "account-1",
                    stableOrderEnabled = false,
                    stableOrderDecisionRevision = 0L,
                    presentationStore = store,
                    foregroundEpoch = 0L,
                )
            }
            }
        }
        waitForIdle()
        return harness
    }

    /** A finger drag of [dp] down the list (a user scroll, which also retires the entry session). */
    private fun ComposeUiTest.dragBy(harness: Harness, dp: Float) {
        val px = dp * density.density
        runOnIdle { harness.scope.launch { harness.listState.scroll(MutatePriority.UserInput) { scrollBy(px) } } }
        waitForIdle()
    }

    private fun ComposeUiTest.change(harness: Harness, next: List<InboxItem>) {
        runOnIdle { harness.items.value = next }
        waitForIdle()
    }

    private fun Harness.firstVisibleRowKey(): String? =
        listState.layoutInfo.visibleItemsInfo
            .mapNotNull { it.key as? String }
            .firstOrNull { !it.startsWith(ACTIVITY_DAY_GROUP_KEY_PREFIX) }

    private fun Harness.assertAtTop() {
        assertEquals(0, listState.firstVisibleItemIndex, "the list is at its top")
        assertEquals(0, listState.firstVisibleItemScrollOffset, "the list is at its top")
    }

    @Test
    fun atTheTopANewRowAtIndexZeroIsTheFirstVisibleRow() =
        runRobolectricComposeUiTest {
            val harness = mount()
            dragBy(harness, 1f)
            dragBy(harness, -1f)
            harness.assertAtTop()
            change(harness, listOf(row("new", "2026-11-05T11:59:00Z")) + rows)
            harness.assertAtTop()
            assertEquals("new", harness.firstVisibleRowKey())
            onNodeWithTag(ActivityNewUpdatesPillTag).assertDoesNotExist()
        }

    @Test
    fun withinTheWebThresholdOfTheTopANewRowStillShowsAtTheTop() =
        runRobolectricComposeUiTest {
            val harness = mount()
            // 20dp down: the web's `scrollTop < 50` still counts this as at the top.
            dragBy(harness, 20f)
            assertTrue(harness.listState.firstVisibleItemScrollOffset > 0 || harness.listState.firstVisibleItemIndex > 0)
            change(harness, listOf(row("new", "2026-11-05T11:59:00Z")) + rows)
            harness.assertAtTop()
            assertEquals("new", harness.firstVisibleRowKey())
            onNodeWithTag(ActivityNewUpdatesPillTag).assertDoesNotExist()
        }

    @Test
    fun atTheTopTheHeadRowMovingUnderANewTodayHeadingStaysInView() =
        runRobolectricComposeUiTest {
            val harness = mount()
            harness.assertAtTop()
            // The head row gets new activity today: same head row, but a "Today" heading now leads the
            // list, and LazyList anchors on the "Yesterday" heading that was first.
            val bumped = row("row0", "2026-11-05T11:59:00Z", message = "row0-m2")
            change(harness, listOf(bumped) + rows.drop(1))
            harness.assertAtTop()
            assertEquals("row0", harness.firstVisibleRowKey())
        }

    @Test
    fun scrolledDownTheReaderKeepsTheirRowSeesThePillAndItsTapGoesToTheTop() =
        runRobolectricComposeUiTest {
            val harness = mount()
            dragBy(harness, 600f)
            val readerKey = harness.firstVisibleRowKey()
            val readerIndex = harness.listState.firstVisibleItemIndex
            assertTrue(readerIndex > 3, "scrolled several rows down")
            onNodeWithTag(ActivityNewUpdatesPillTag).assertDoesNotExist()

            change(harness, listOf(row("new", "2026-11-05T11:59:00Z")) + rows)
            assertEquals(readerKey, harness.firstVisibleRowKey(), "the reader keeps their row")
            onNodeWithTag(ActivityNewUpdatesPillTag).assertExists()

            onNodeWithTag(ActivityNewUpdatesPillTag).performClick()
            waitForIdle()
            harness.assertAtTop()
            assertEquals("new", harness.firstVisibleRowKey())
            onNodeWithTag(ActivityNewUpdatesPillTag).assertDoesNotExist()
        }
}
