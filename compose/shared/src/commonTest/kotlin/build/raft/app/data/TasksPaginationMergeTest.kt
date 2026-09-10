package build.raft.app.data

import build.raft.app.shouldRequestNextTasksPage
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #345 — pagination merge semantics.
 *
 * These are the load-bearing teeth for the store-side pagination:
 *  - a fresh page APPENDS (never resets the already-loaded rows);
 *  - a row repeated across pages MERGES by id (no duplicate, position kept);
 *  - the cursor/`hasMore` state follows the server exactly (null terminates);
 *  - an invalid-cursor failure drops the cursor (host refreshes the head)
 *    while a transient failure keeps it (fail-open, still walkable).
 */
class TasksPaginationMergeTest {

    private fun task(id: String, status: TaskStatus = TaskStatus.Todo, title: String = "Task $id") =
        TaskSummary(
            id = TaskId(id),
            title = title,
            status = status,
            taskNumber = id.filter { it.isDigit() }.toIntOrNull(),
            messageId = MessageId("message-$id"),
            channelId = ChannelId("channel-1"),
            updatedAtLabel = "now",
        )

    private fun content(vararg tasks: TaskSummary) =
        TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.toList().summaryCounts(), tasks = tasks.toList())))

    @Test
    fun appendingAPageNeverDropsLoadedRows() {
        val first = content(task("task-1"), task("task-2"))
        val merged = first.withAppendedTasks(listOf(task("task-3"), task("task-4")), nextCursor = "c2")

        val tasks = (merged.loadState as ScreenLoadState.Content).data.tasks
        assertEquals(listOf("task-1", "task-2", "task-3", "task-4"), tasks.map { it.id.value })
        assertEquals("c2", merged.nextCursor)
        assertTrue(merged.hasMore)
    }

    @Test
    fun aRowRepeatedAcrossPagesMergesByIdWithoutDuplicating() {
        val first = content(task("task-1", title = "old"), task("task-2"))
        // task-1 arrives again (e.g. overlap) with updated content.
        val merged = first.withAppendedTasks(listOf(task("task-1", title = "new"), task("task-3")), nextCursor = null)

        val tasks = (merged.loadState as ScreenLoadState.Content).data.tasks
        assertEquals(listOf("task-1", "task-2", "task-3"), tasks.map { it.id.value }, "no duplicate, order kept")
        assertEquals("new", tasks.first { it.id.value == "task-1" }.title, "existing row replaced in place")
        assertFalse(merged.hasMore)
        assertNull(merged.nextCursor)
    }

    @Test
    fun aNullNextCursorTerminatesTheWalk() {
        val merged = content(task("task-1")).withAppendedTasks(listOf(task("task-2")), nextCursor = null)
        assertNull(merged.nextCursor)
        assertFalse(merged.hasMore)
    }

    @Test
    fun anEmptyPageStillUpdatesCursorState() {
        val merged = content(task("task-1")).withAppendedTasks(emptyList(), nextCursor = null)
        assertEquals("task-1", (merged.loadState as ScreenLoadState.Content).data.tasks.single().id.value)
        assertNull(merged.nextCursor)
        assertFalse(merged.hasMore)
    }

    @Test
    fun transientLoadMoreFailureKeepsCursorForRetry() {
        val state = content(task("task-1")).copy(nextCursor = "c2", hasMore = true)
        val failed = state.reduce(SharedTasksIntent.LoadMoreFailed(errorCode = "timeout"))
        assertEquals("c2", failed.nextCursor, "transient failure keeps the walkable cursor")
        assertTrue(failed.hasMore)
        assertFalse(failed.loadingMore)
        assertEquals("timeout", failed.loadMoreErrorCode)
    }

    @Test
    fun invalidCursorFailureDropsCursorSoHostRefreshesHead() {
        val state = content(task("task-1")).copy(nextCursor = "stale", hasMore = true)
        val failed = state.reduce(SharedTasksIntent.LoadMoreFailed(errorCode = "Invalid cursor", invalidCursor = true))
        assertNull(failed.nextCursor)
        assertFalse(failed.hasMore)
    }

    @Test
    fun loadMoreIsASingleFlightAndNoOpWithoutMore() {
        // No cursor / !hasMore -> LoadMore is a no-op.
        val idle = content(task("task-1"))
        assertFalse(idle.reduce(SharedTasksIntent.LoadMore).loadingMore)

        // With more available -> starts loading.
        val ready = idle.copy(nextCursor = "c2", hasMore = true)
        assertTrue(ready.reduce(SharedTasksIntent.LoadMore).loadingMore)

        // Already loading -> does not re-enter.
        assertEquals(ready.reduce(SharedTasksIntent.LoadMore).reduce(SharedTasksIntent.LoadMore), ready.reduce(SharedTasksIntent.LoadMore))
    }
}

/**
 * Task #345 — scroll-sentinel criterion (pure). The list must request the next
 * page only when a page exists, nothing is in flight, and the viewport has
 * reached the load-more threshold.
 */
class TasksPaginationSentinelTest {
    @Test
    fun requestsOnlyWhenMoreIsAvailableAndIdle() {
        // No more pages -> never.
        assertFalse(shouldRequestNextTasksPage(totalItems = 10, lastVisibleIndex = 9, hasMore = false, loadingMore = false))
        // Already loading -> no re-entry.
        assertFalse(shouldRequestNextTasksPage(totalItems = 10, lastVisibleIndex = 9, hasMore = true, loadingMore = true))
        // Empty list -> no request.
        assertFalse(shouldRequestNextTasksPage(totalItems = 0, lastVisibleIndex = 0, hasMore = true, loadingMore = false))
    }

    @Test
    fun requestsOnceTheViewportReachesTheThreshold() {
        // 200 items, threshold 5: index 194 leaves 5 after it -> not yet; 195 -> yes.
        assertFalse(shouldRequestNextTasksPage(totalItems = 200, lastVisibleIndex = 194, hasMore = true, loadingMore = false))
        assertTrue(shouldRequestNextTasksPage(totalItems = 200, lastVisibleIndex = 195, hasMore = true, loadingMore = false))
        // At the very end -> yes.
        assertTrue(shouldRequestNextTasksPage(totalItems = 200, lastVisibleIndex = 199, hasMore = true, loadingMore = false))
    }

    @Test
    fun aShortListQualifiesImmediately() {
        // Fewer items than the threshold: the first viewport is already "near the end".
        assertTrue(shouldRequestNextTasksPage(totalItems = 3, lastVisibleIndex = 0, hasMore = true, loadingMore = false))
    }
}
