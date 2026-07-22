package build.raft.app

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
    fun autoAppendRequiresMoreRowsAndAUsableCursor() {
        assertFalse(
            threadFilesShouldRequestLoadMore(
                itemCount = 3,
                lastVisibleItemIndex = 2,
                hasMore = false,
                loadingMore = false,
                nextCursor = null,
                errorCode = null
            )
        )
        assertFalse(
            threadFilesShouldRequestLoadMore(
                itemCount = 3,
                lastVisibleItemIndex = 2,
                hasMore = true,
                loadingMore = false,
                nextCursor = " ",
                errorCode = null
            )
        )
    }

    @Test
    fun shortScrollablePageCanAutoFillViewport() {
        assertTrue(
            threadFilesShouldRequestLoadMore(
                itemCount = 3,
                lastVisibleItemIndex = 2,
                hasMore = true,
                loadingMore = false,
                nextCursor = "cursor-2",
                errorCode = null
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

    @Test
    fun autoAppendFenceIsPerRouteListAndAppendErrorStaysManual() {
        val existingFence =
            ThreadFilesAutoLoadFence(
                routeListKey = "server-a:channel-a:files",
                lastAutoRequestedCursor = "cursor-2"
            )
        val appendError =
            threadFilesAutoLoadTransition(
                fence = existingFence,
                routeListKey = "server-a:channel-a:files",
                itemCount = 50,
                lastVisibleItemIndex = 49,
                hasMore = true,
                loadingMore = false,
                nextCursor = "cursor-2",
                errorCode = "thread_files_network"
            )
        assertNull(appendError.requestedCursor)
        assertEquals(existingFence, appendError.fence)

        val differentRouteSameCursor =
            threadFilesAutoLoadTransition(
                fence = appendError.fence,
                routeListKey = "server-a:channel-b:files",
                itemCount = 50,
                lastVisibleItemIndex = 49,
                hasMore = true,
                loadingMore = false,
                nextCursor = "cursor-2",
                errorCode = null
            )
        assertEquals("cursor-2", differentRouteSameCursor.requestedCursor)
        assertEquals("server-a:channel-b:files", differentRouteSameCursor.fence.routeListKey)
    }
}
