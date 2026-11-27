package build.raft.app

import build.raft.app.thread.ThreadFilesAutoLoadFence
import build.raft.app.thread.threadFilesAutoLoadTransition
import build.raft.app.thread.threadFilesShouldRequestLoadMore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadFilesContentTest {
    @Test
    fun nearBottomRequestsNextCursorPage() {
        assertFalse(
            threadFilesShouldRequestLoadMore(
                itemCount = 50,
                lastVisibleItemIndex = 44,
                hasMore = true,
                loadingMore = false,
                nextCursor = "cursor-2",
                errorCode = null
            )
        )
        assertTrue(
            threadFilesShouldRequestLoadMore(
                itemCount = 50,
                lastVisibleItemIndex = 45,
                hasMore = true,
                loadingMore = false,
                nextCursor = "cursor-2",
                errorCode = null
            )
        )
    }

    @Test
    fun autoAppendStopsDuringRequestAndAfterError() {
        assertFalse(
            threadFilesShouldRequestLoadMore(
                itemCount = 50,
                lastVisibleItemIndex = 49,
                hasMore = true,
                loadingMore = true,
                nextCursor = "cursor-2",
                errorCode = null
            )
        )
        assertFalse(
            threadFilesShouldRequestLoadMore(
                itemCount = 50,
                lastVisibleItemIndex = 49,
                hasMore = true,
                loadingMore = false,
                nextCursor = "cursor-2",
                errorCode = "thread_files_network"
            )
        )
    }

    @Test
    fun autoAppendRequestsEachCursorOnlyOnceUntilCursorAdvances() {
        val first =
            threadFilesAutoLoadTransition(
                fence = ThreadFilesAutoLoadFence(),
                routeListKey = "server-a:channel-a:files",
                itemCount = 50,
                lastVisibleItemIndex = 49,
                hasMore = true,
                loadingMore = false,
                nextCursor = "cursor-2",
                errorCode = null
            )
        assertEquals("cursor-2", first.requestedCursor)
        assertEquals("cursor-2", first.fence.lastAutoRequestedCursor)

        val sameCursorNoProgress =
            threadFilesAutoLoadTransition(
                fence = first.fence,
                routeListKey = "server-a:channel-a:files",
                itemCount = 50,
                lastVisibleItemIndex = 49,
                hasMore = true,
                loadingMore = false,
                nextCursor = "cursor-2",
                errorCode = null
            )
        assertNull(sameCursorNoProgress.requestedCursor)
        assertEquals("cursor-2", sameCursorNoProgress.fence.lastAutoRequestedCursor)

        val cursorAdvanced =
            threadFilesAutoLoadTransition(
                fence = sameCursorNoProgress.fence,
                routeListKey = "server-a:channel-a:files",
                itemCount = 55,
                lastVisibleItemIndex = 54,
                hasMore = true,
                loadingMore = false,
                nextCursor = "cursor-3",
                errorCode = null
            )
        assertEquals("cursor-3", cursorAdvanced.requestedCursor)
        assertEquals("cursor-3", cursorAdvanced.fence.lastAutoRequestedCursor)
    }
}
