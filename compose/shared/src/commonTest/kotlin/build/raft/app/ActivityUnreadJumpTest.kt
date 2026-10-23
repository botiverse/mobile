package build.raft.app

import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.SharedActivityState
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin 2026-10-21: Activity head tap / tab re-tap walks the visible list's unread rows (web
 * parity: slock #10720, raft-design #671).
 */
class ActivityUnreadJumpTest {

    /** "u" = unread row, "r" = read row, "s" = section label; keys are "k<index>" unless given. */
    private fun entries(pattern: String, keys: List<String> = pattern.indices.map { "k$it" }): List<ActivityUnreadJumpEntry> =
        pattern.mapIndexed { index, c -> ActivityUnreadJumpEntry(key = keys[index], unread = c == 'u') }

    private fun viewport(firstVisible: Int = 0, offset: Int = 0, visible: IntRange = firstVisible..(firstVisible + 4), size: Int = 100) =
        ActivityUnreadJumpViewport(
            firstVisibleItemIndex = firstVisible,
            firstVisibleItemScrollOffset = offset,
            visibleItemKeys = visible.filter { it < size }.map { "k$it" },
        )

    private fun target(
        pattern: String,
        firstVisible: Int = 0,
        offset: Int = 0,
        visible: IntRange = firstVisible..(firstVisible + 4),
        walk: ActivityUnreadWalk = ActivityUnreadWalk.Idle,
        canLoadMore: Boolean = false,
    ): ActivityUnreadJumpTarget =
        activityNextUnreadJumpTarget(
            entries = entries(pattern),
            viewport = viewport(firstVisible, offset, visible, pattern.length),
            walk = walk,
            canLoadMore = canLoadMore,
        )

    /** The walk as the list leaves it after a finished jump to row [index] with [firstVisible] on top. */
    private fun settledAt(index: Int, firstVisible: Int = index, offset: Int = 0) =
        ActivityUnreadWalk.AtRow(key = "k$index", settled = true, anchorKey = "k$firstVisible", anchorOffset = offset)

    private fun row(index: Int) = ActivityUnreadJumpTarget.Row(index = index, key = "k$index")

    @Test
    fun fromTheTopTheFirstTapGoesToTheFirstUnreadRow() {
        assertEquals(row(2), target("rrurru"))
        // The head row itself counts when it is unread and the list is parked on it.
        assertEquals(row(0), target("urru"))
    }

    @Test
    fun goesToTheNextUnreadBelowTheViewportAndSkipsRowsAbove() {
        // Unread rows 1 and 3 are above the viewport (first visible 5): the walk goes down to 7.
        assertEquals(row(7), target("rururrrur", firstVisible = 5))
        // A first visible row partly scrolled out counts as above.
        assertEquals(row(7), target("rrrrrurur", firstVisible = 5, offset = 40))
        // Flush with the viewport top, it is the next one.
        assertEquals(row(5), target("rrrrrurur", firstVisible = 5))
    }

    @Test
    fun unreadRowsNearTheBottomThatCannotReachTheTopAreVisitedInOrderThenWrapToTop() {
        // Ten rows, five fit the viewport: the list cannot scroll past first visible 5, so the
        // unread rows 6 and 9 never become the first visible row.
        val pattern = "rrurrrurru" // unread: 2, 6, 9
        var firstVisible = 0
        var walk: ActivityUnreadWalk = ActivityUnreadWalk.Idle
        val visited = mutableListOf<String>()
        repeat(5) {
            when (val next = target(pattern, firstVisible = firstVisible, walk = walk)) {
                is ActivityUnreadJumpTarget.Row -> {
                    visited += next.key
                    firstVisible = minOf(next.index, 5)
                    walk = settledAt(next.index, firstVisible)
                }
                else -> {
                    visited += "top"
                    firstVisible = 0
                    walk = ActivityUnreadWalk.Idle
                }
            }
        }
        assertEquals(listOf("k2", "k6", "k9", "top", "k2"), visited)
    }

    @Test
    fun afterTheLastUnreadRowTheNextTapGoesToTheTop() {
        assertEquals(ActivityUnreadJumpTarget.Top, target("ruru", visible = 0..3, walk = settledAt(3, firstVisible = 0)))
        assertEquals(ActivityUnreadJumpTarget.Top, target("urrrrrrr", firstVisible = 3))
    }

    @Test
    fun aManualScrollRecomputesFromTheCurrentPosition() {
        // The walk left row 2 on top; the reader scrolled down to first visible 7 meanwhile:
        // row 4 (the walk's next) is skipped, the next unread from the viewport is 8.
        assertEquals(row(8), target("rrurururu", firstVisible = 7, walk = settledAt(2)))
        // ...or back up: rows above the old place are visited again.
        assertEquals(row(2), target("rrururrru", firstVisible = 1, walk = settledAt(4)))
        // A drag of less than a row also counts: row 2 partly shown again, so it is next.
        assertEquals(row(2), target("rrururrru", firstVisible = 1, offset = 30, walk = settledAt(2)))
    }

    @Test
    fun aRealtimeReorderContinuesFromTheJumpedRowsCurrentPosition() {
        // The walk sat on "b" (index 1, first visible); a new row landed above it, so "b" is now
        // index 2 and still the first visible item (LazyColumn keeps it anchored by key).
        val keys = listOf("new", "a", "b", "c", "d")
        val next =
            activityNextUnreadJumpTarget(
                entries = entries("uruuu", keys),
                viewport = ActivityUnreadJumpViewport(2, 0, listOf("b", "c", "d")),
                walk = ActivityUnreadWalk.AtRow(key = "b", settled = true, anchorKey = "b", anchorOffset = 0),
            )
        assertEquals(ActivityUnreadJumpTarget.Row(index = 3, key = "c"), next)
    }

    @Test
    fun aTapDuringTheScrollToTopRestartsAtTheFirstUnreadRow() {
        // Mid-animation the viewport is still far down; the walk restarts from the head anyway.
        assertEquals(row(1), target("rurrrrrruu", firstVisible = 7, walk = ActivityUnreadWalk.ReturningToTop))
    }

    @Test
    fun anInterruptedJumpContinuesAfterItsRowOnlyWhileTheRowIsOnScreen() {
        val interrupted = ActivityUnreadWalk.AtRow(key = "k4", settled = false)
        assertEquals(row(6), target("ruruurur", firstVisible = 2, visible = 2..6, walk = interrupted))
        // The row is off screen (a drag took the list elsewhere): from the viewport.
        assertEquals(row(1), target("ruruurur", firstVisible = 0, visible = 0..3, walk = interrupted))
    }

    @Test
    fun noUnreadRowsScrollsToTop() {
        assertEquals(ActivityUnreadJumpTarget.Top, target("rrrr"))
        assertEquals(ActivityUnreadJumpTarget.Top, target("rrrr", firstVisible = 2))
        assertEquals(ActivityUnreadJumpTarget.Top, target(""))
    }

    @Test
    fun aPagedListLoadsTheNextPageBeforeWrappingToTop() {
        assertEquals(ActivityUnreadJumpTarget.LoadMore, target("rurr", visible = 0..3, walk = settledAt(1, firstVisible = 0), canLoadMore = true))
        assertEquals(ActivityUnreadJumpTarget.LoadMore, target("rrrr", canLoadMore = true))
        // After the page landed, the same walk finds the unread row in it.
        assertEquals(row(5), target("rurrru", visible = 0..4, walk = settledAt(1, firstVisible = 0), canLoadMore = true))
        // An unread row already loaded wins over loading.
        assertEquals(row(1), target("ru", canLoadMore = true))
    }

    @Test
    fun pagingStateDecidesWhenAPageHasLanded() {
        val before = ActivityUnreadJumpPaging(rowCount = 20, hasMore = true, loadingMore = false, loadMoreErrorCode = null)
        assertTrue(before.canLoadMore)
        assertFalse(before.copy(loadMoreErrorCode = "network").canLoadMore)
        assertFalse(before.copy(hasMore = false).canLoadMore)
        assertFalse(before.copy(loadingMore = true).settledAfter(before))
        assertFalse(before.settledAfter(before), "nothing happened yet")
        assertTrue(before.copy(rowCount = 40).settledAfter(before))
        assertTrue(before.copy(hasMore = false).settledAfter(before))
        assertTrue(before.copy(loadMoreErrorCode = "network").settledAfter(before))
    }

    @Test
    fun sectionLabelsAreNeverTargets() {
        // Conversation list: "Pinned" label, a pinned read row, the "Recent" label, then rows.
        assertEquals(row(4), target("srsru"))
    }

    @Test
    fun walksOnlyTheRowsOfTheFilteredListItIsGiven() {
        // Unread filter: every row is unread, so each tap advances one row.
        val unreadOnly = "uuu"
        assertEquals(row(0), target(unreadOnly))
        assertEquals(row(1), target(unreadOnly, visible = 0..2, walk = settledAt(0)))
        assertEquals(row(2), target(unreadOnly, visible = 0..2, walk = settledAt(1, firstVisible = 0)))
        assertEquals(ActivityUnreadJumpTarget.Top, target(unreadOnly, visible = 0..2, walk = settledAt(2, firstVisible = 0)))
        // Mentions filter, all read: a tap only scrolls to top.
        assertEquals(ActivityUnreadJumpTarget.Top, target("rr"))
    }

    private fun item(key: String, unread: Int, channel: String = key) =
        InboxItem(
            key = key,
            kind = InboxItemKind.Channel,
            channelId = ChannelId(channel),
            channelName = "#$channel",
            unreadCount = unread,
        )

    @Test
    fun unreadIsTheStateTheRowDisplays() {
        assertTrue(item("a", unread = 2).activityRowDisplaysUnread(ActivityConversationListContext.Empty))
        assertFalse(item("a", unread = 0).activityRowDisplaysUnread(ActivityConversationListContext.Empty))
        // A muted conversation shows no count, so it is not a target.
        val muted = ActivityConversationListContext(mutedChannelIds = setOf("quiet"))
        assertFalse(item("q", unread = 3, channel = "quiet").activityRowDisplaysUnread(muted))
        assertTrue(item("loud", unread = 3).activityRowDisplaysUnread(muted))
    }

    @Test
    fun onlyAPageWithAListWalksUnreadRowsOtherwiseTheTapScrollsToTop() {
        val server = ServerSummary(ServerId("server-1"), "Raft")
        val withRows =
            SharedActivityState(
                selectedFilter = InboxFilter.All,
                snapshot = InboxSnapshot(server = server, items = listOf(item("a", unread = 1))),
            )
        assertTrue(activitySelectedPageHasList(withRows, conversationListPending = false))
        assertFalse(activitySelectedPageHasList(withRows, conversationListPending = true))
        assertFalse(
            activitySelectedPageHasList(
                withRows.copy(snapshot = InboxSnapshot(server = server, items = emptyList())),
                conversationListPending = false,
            )
        )
        assertFalse(activitySelectedPageHasList(SharedActivityState(loading = true), conversationListPending = false))
    }

    // artin 2026-10-22: single re-tap walks, double re-tap goes back to the top (virtual time).

    private val doubleTapTimeout = 300.milliseconds

    @Test
    fun singleRetapWalksAtOnce() = runTest {
        val gesture = ActivityRetapGesture(testScheduler.timeSource)
        assertEquals(ActivityRetapAction.WalkNextUnread, gesture.onTap(doubleTapTimeout))
        assertEquals(ActivityUnreadJumpRequest(generation = 1, toTop = false), ActivityUnreadJumpRequest().next(ActivityRetapAction.WalkNextUnread))
    }

    @Test
    fun doubleRetapWithinTimeoutScrollsToTop() = runTest {
        val gesture = ActivityRetapGesture(testScheduler.timeSource)
        var request = ActivityUnreadJumpRequest()
        request = request.next(gesture.onTap(doubleTapTimeout))
        assertFalse(request.toTop)
        delay(200)
        request = request.next(gesture.onTap(doubleTapTimeout))
        // A newer generation (restarts the list effect, cancelling the walk) asking for the top.
        assertEquals(ActivityUnreadJumpRequest(generation = 2, toTop = true), request)
        // Exactly at the timeout still counts as the second tap.
        delay(1_000)
        gesture.onTap(doubleTapTimeout)
        delay(300)
        assertEquals(ActivityRetapAction.ScrollToTop, gesture.onTap(doubleTapTimeout))
    }

    @Test
    fun twoRetapsBeyondTimeoutWalkTwice() = runTest {
        val gesture = ActivityRetapGesture(testScheduler.timeSource)
        assertEquals(ActivityRetapAction.WalkNextUnread, gesture.onTap(doubleTapTimeout))
        delay(301)
        assertEquals(ActivityRetapAction.WalkNextUnread, gesture.onTap(doubleTapTimeout))
    }

    @Test
    fun thirdQuickRetapAfterDoubleTapWalksAgain() = runTest {
        val gesture = ActivityRetapGesture(testScheduler.timeSource)
        gesture.onTap(doubleTapTimeout)
        delay(100)
        assertEquals(ActivityRetapAction.ScrollToTop, gesture.onTap(doubleTapTimeout))
        delay(100)
        assertEquals(ActivityRetapAction.WalkNextUnread, gesture.onTap(doubleTapTimeout))
    }

    @Test
    fun retapRequestGenerationIsFencedAgainstRemountReplay() {
        val request = ActivityUnreadJumpRequest(generation = 3, toTop = true)
        val fence = AppShellScrollToTopRequestFence(request.generation)
        assertFalse(fence.consume(request.generation))
        assertTrue(fence.consume(request.next(ActivityRetapAction.WalkNextUnread).generation))
    }
}
