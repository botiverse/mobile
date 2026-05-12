package ai.slock.android.feature.inbox

import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.state.ScreenLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class InboxUiStateTest {
    @Test
    fun withFilterLoadingOptimisticallySwitchesFilterAndShowsLoading() {
        val content =
            InboxContent(
                server = ServerSummary(
                    id = ServerId("server-1"),
                    name = "Workspace"
                ),
                items = emptyList(),
                totalCount = 12,
                totalUnreadCount = 4,
                hasMore = true
            )
        val state = InboxUiState(
            loadState = ScreenLoadState.Content(content),
            selectedFilter = InboxFilter.All,
            focusedItemKey = "inbox-item",
            loadingMore = true,
            nextOffset = 20
        )

        val loading = state.withFilterLoading(InboxFilter.Unread, renderCache = content)

        assertSame(ScreenLoadState.Loading, loading.loadState)
        assertEquals(InboxFilter.Unread, loading.selectedFilter)
        assertNull(loading.focusedItemKey)
        assertFalse(loading.loadingMore)
        assertNull(loading.nextOffset)
        assertSame(content, loading.previousContent)
    }

    @Test
    fun clearAllUnreadKeepsAllItemsAndClearsCountsInAllFilter() {
        val state =
            InboxUiState(
                loadState =
                    ScreenLoadState.Content(
                        InboxContent(
                            server = server,
                            items = listOf(inboxItem("channel-1", unreadCount = 3)),
                            totalCount = 8,
                            totalUnreadCount = 3
                        )
                    ),
                selectedFilter = InboxFilter.All
            )

        val cleared = state.clearAllUnread()
        val content = (cleared.loadState as ScreenLoadState.Content).data

        assertEquals(1, content.items.size)
        assertEquals(0, content.items.single().unreadCount)
        assertNull(content.items.single().firstUnreadMessageId)
        assertEquals(8, content.totalCount)
        assertEquals(0, content.totalUnreadCount)
    }

    @Test
    fun clearAllUnreadRemovesRowsInUnreadFilter() {
        val state =
            InboxUiState(
                loadState =
                    ScreenLoadState.Content(
                        InboxContent(
                            server = server,
                            items = listOf(inboxItem("channel-1", unreadCount = 3)),
                            totalCount = 1,
                            totalUnreadCount = 3
                        )
                    ),
                selectedFilter = InboxFilter.Unread
            )

        val cleared = state.clearAllUnread()
        val content = (cleared.loadState as ScreenLoadState.Content).data

        assertEquals(emptyList<InboxItem>(), content.items)
        assertEquals(0, content.totalCount)
        assertEquals(0, content.totalUnreadCount)
    }

    private val server =
        ServerSummary(
            id = ServerId("server-1"),
            name = "Workspace"
        )

    private fun inboxItem(channelId: String, unreadCount: Int): InboxItem =
        InboxItem(
            key = channelId,
            kind = InboxItemKind.Channel,
            channelId = ChannelId(channelId),
            channelName = channelId,
            unreadCount = unreadCount,
            firstUnreadMessageId = ai.slock.android.core.model.MessageId("message-$channelId")
        )
}
