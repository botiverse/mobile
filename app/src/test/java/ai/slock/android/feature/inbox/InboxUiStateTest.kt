package ai.slock.android.feature.inbox

import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.state.ScreenLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class InboxUiStateTest {
    @Test
    fun withFilterLoadingOptimisticallySwitchesFilterAndShowsLoading() {
        val state = InboxUiState(
            loadState = ScreenLoadState.Content(
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
            ),
            selectedFilter = InboxFilter.All,
            focusedItemKey = "inbox-item",
            loadingMore = true,
            nextOffset = 20
        )

        val loading = state.withFilterLoading(InboxFilter.Unread)

        assertSame(ScreenLoadState.Loading, loading.loadState)
        assertEquals(InboxFilter.Unread, loading.selectedFilter)
        assertNull(loading.focusedItemKey)
        assertFalse(loading.loadingMore)
        assertNull(loading.nextOffset)
    }
}
