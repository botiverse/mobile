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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** artin 2026-10-21: Activity head tap / tab re-tap walks the visible list's unread rows. */
class ActivityUnreadJumpTest {

    /** "u" = unread row, "r" = read row, "s" = section label; keys are "k<index>". */
    private fun entries(pattern: String): List<ActivityUnreadJumpEntry> =
        pattern.mapIndexed { index, c -> ActivityUnreadJumpEntry(key = "k$index", unread = c == 'u') }

    private fun target(
        pattern: String,
        firstVisible: Int = 0,
        offset: Int = 0,
        visible: IntRange = firstVisible..(firstVisible + 4),
        cursor: Int? = null,
    ): ActivityUnreadJumpTarget =
        activityNextUnreadJumpTarget(
            entries = entries(pattern),
            firstVisibleItemIndex = firstVisible,
            firstVisibleItemScrollOffset = offset,
            visibleItemKeys = visible.filter { it < pattern.length }.map { "k$it" }.toSet(),
            cursorKey = cursor?.let { "k$it" },
        )

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
        assertEquals(row(7), target("rururrrur", firstVisible = 5, offset = 0))
        // A first visible row partly scrolled out counts as above.
        assertEquals(row(7), target("rrrrrurur", firstVisible = 5, offset = 40))
        // Flush with the viewport top, it is the next one.
        assertEquals(row(5), target("rrrrrurur", firstVisible = 5, offset = 0))
    }

    @Test
    fun repeatedTapsWalkDownFromTheRowJumpedToEvenWhenTheListCouldNotBringItToTheTop() {
        // Rows 8 and 9 are near the end: the list clamps at first visible 6, so the jumped-to
        // row 8 is still laid out but not first; the walk continues after it, not back to it.
        assertEquals(row(9), target("rrrrrrrruur", firstVisible = 6, visible = 6..10, cursor = 8))
        // Cursor row scrolled out of view (the reader moved): restart from the viewport.
        assertEquals(row(2), target("rrurrrrruu", firstVisible = 0, visible = 0..4, cursor = 8))
    }

    @Test
    fun unreadRowsNearTheBottomThatCannotReachTheTopAreVisitedInOrderThenWrapToTop() {
        // Ten rows, five fit the viewport: the list cannot scroll past first visible 5, so the
        // unread rows 6, 8 and 9 never become the first visible row (web parity, raft-design #671).
        val pattern = "rrurrrurru" // unread: 2, 6, 9
        val lastFirstVisible = 5
        fun clampedFirstVisible(target: Int) = minOf(target, lastFirstVisible)
        var firstVisible = 0
        var cursor: Int? = null
        val visited = mutableListOf<String>()
        repeat(5) {
            when (val next = target(pattern, firstVisible = firstVisible, visible = firstVisible..(firstVisible + 4), cursor = cursor)) {
                ActivityUnreadJumpTarget.Top -> {
                    visited += "top"
                    firstVisible = 0
                    cursor = null
                }
                is ActivityUnreadJumpTarget.Row -> {
                    visited += next.key
                    firstVisible = clampedFirstVisible(next.index)
                    cursor = next.index
                }
            }
        }
        assertEquals(listOf("k2", "k6", "k9", "top", "k2"), visited)
    }

    @Test
    fun afterTheLastUnreadRowTheNextTapGoesToTheTopAndThenRestarts() {
        assertEquals(ActivityUnreadJumpTarget.Top, target("ruru", firstVisible = 0, visible = 0..3, cursor = 3))
        assertEquals(ActivityUnreadJumpTarget.Top, target("urrrrrrr", firstVisible = 3))
        // Back at the top (cursor cleared): first unread again.
        assertEquals(row(1), target("ruru", firstVisible = 0, visible = 0..3, cursor = null))
    }

    @Test
    fun noUnreadRowsScrollsToTop() {
        assertEquals(ActivityUnreadJumpTarget.Top, target("rrrr"))
        assertEquals(ActivityUnreadJumpTarget.Top, target("rrrr", firstVisible = 2))
        assertEquals(ActivityUnreadJumpTarget.Top, target(""))
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
        assertEquals(row(1), target(unreadOnly, visible = 0..2, cursor = 0))
        assertEquals(row(2), target(unreadOnly, firstVisible = 0, visible = 0..2, cursor = 1))
        assertEquals(ActivityUnreadJumpTarget.Top, target(unreadOnly, visible = 0..2, cursor = 2))
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
        // Card rows: any unread count.
        assertTrue(item("a", unread = 2).activityRowDisplaysUnread(conversationListContext = null))
        assertFalse(item("a", unread = 0).activityRowDisplaysUnread(conversationListContext = null))
        // Conversation-list rows: a muted conversation shows no count, so it is not a target.
        val muted = ActivityConversationListContext(mutedChannelIds = setOf("quiet"))
        assertFalse(item("q", unread = 3, channel = "quiet").activityRowDisplaysUnread(muted))
        assertTrue(item("loud", unread = 3).activityRowDisplaysUnread(muted))
        assertTrue(item("muted-card", unread = 3, channel = "quiet").activityRowDisplaysUnread(null))
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
}
