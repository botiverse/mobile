package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
