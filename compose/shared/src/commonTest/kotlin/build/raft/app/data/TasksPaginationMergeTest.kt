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

/**
 * Task #345 — the parser must accept the REAL production `detail=summary` item
 * shape, which carries extra task fields beyond the minimal summary projection
 * (`channelType`, `claimedAt/ById/ByType`, `completedAt`, `createdById/ByType`,
 * `revision`, `isLegacy`) and, crucially, NO `description` body.
 *
 * The captured shape is from a real production response
 * (`GET https://api.raft.build/api/tasks/server?detail=summary&limit=200`).
 */
class TasksSummaryProductionShapeTest {
    private val productionPage =
        """
        {
          "tasks": [
            {
              "id": "e3c07075-d37b-4b9e-8a3b-c6b6579493ac",
              "messageId": "d5ffa252-72e3-4cf6-89df-83984179906e",
              "channelId": "522bfaf6-f795-4e0e-8bc4-c78e80bc8220",
              "channelName": "McDonalds_Club",
              "channelType": "channel",
              "taskNumber": 1,
              "title": "Fixture task 1",
              "revision": 1,
              "status": "done",
              "createdByType": "user",
              "createdById": "6807454d-d743-4a5c-8eb2-b50ed75ac3a4",
              "createdByName": "Amida",
              "claimedByType": "agent",
              "claimedById": "a4232306-5653-41e6-bd8c-d6e964ac90e4",
              "claimedByName": "McNuggets",
              "claimedAt": "2026-05-11T08:14:56.745Z",
              "completedAt": "2026-08-10T05:27:24.701Z",
              "createdAt": "2026-05-11T08:14:32.438Z",
              "updatedAt": "2026-08-10T05:27:24.701Z",
              "source": "tasks",
              "hasDescription": false,
              "descriptionBytes": 0,
              "isLegacy": false
            }
          ],
          "next_cursor": "eyJjIjoiZDE4ZDlkYWQtOGE1Yy00NGVmLThkODUtMmMwNTcwYmFmOTM5IiwibiI6MjN9"
        }
        """.trimIndent()

    @Test
    fun parsesTheProductionSummaryShapeAndKeepsTheCursor() {
        val page = parseSharedServerTasksPagePayload(productionPage, diagnostics = {})
            ?: error("production summary shape must parse")
        assertEquals(1, page.tasks.size)
        val task = page.tasks.single()
        assertEquals("e3c07075-d37b-4b9e-8a3b-c6b6579493ac", task.id.value)
        assertEquals("McDonalds_Club", task.channelName)
        assertEquals("Fixture task 1", task.title)
        // No description body on the summary shape.
        assertNull(task.description)
        // The opaque cursor is carried through verbatim.
        assertEquals("eyJjIjoiZDE4ZDlkYWQtOGE1Yy00NGVmLThkODUtMmMwNTcwYmFmOTM5IiwibiI6MjN9", page.nextCursor)
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun decodesTheProductionCursorAsChannelAndTaskNumber() {
        val raw = "eyJjIjoiZDE4ZDlkYWQtOGE1Yy00NGVmLThkODUtMmMwNTcwYmFmOTM5IiwibiI6MjN9"
        val decoded = kotlin.io.encoding.Base64.UrlSafe.decode(raw)
            .decodeToString()
        assertTrue(decoded.contains("\"c\":\"d18d9dad-8a5c-44ef-8d85-2c0570baf939\""), "cursor carries channelId")
        assertTrue(decoded.contains("\"n\":23"), "cursor carries taskNumber")
    }
}

/**
 * Task #345 — the server-switch fence (pure). A page must only be applied when
 * the server it was fetched for is still current; otherwise the page is stale
 * and must be discarded (not folded into the new server's projection).
 */
class TasksPaginationServerFenceTest {
    @Test
    fun aPageIsAppliedOnlyWhenItsServerIsStillCurrent() {
        // Same server -> apply.
        assertTrue(tasksPageServerStillCurrent("server-A", "server-A"))
        // Switched A -> B while in flight -> discard.
        assertFalse(tasksPageServerStillCurrent("server-A", "server-B"))
        // Unknown request server -> discard (fail closed).
        assertFalse(tasksPageServerStillCurrent(null, "server-A"))
        // No current server -> discard.
        assertFalse(tasksPageServerStillCurrent("server-A", null))
        assertFalse(tasksPageServerStillCurrent(null, null))
    }

    @Test
    fun aPageIsDiscardedOnASameServerOwnerSwitch() {
        // Same server, same owner -> apply.
        assertTrue(tasksPageOwnerStillCurrent("base|user-A", "base|user-A"))
        // Same server, user A logged out and user B logged in -> discard
        // (a server-ID-only fence would WRONGLY apply this page).
        assertFalse(tasksPageOwnerStillCurrent("base|user-A", "base|user-B"))
        // Owner cleared (logout) -> discard.
        assertFalse(tasksPageOwnerStillCurrent("base|user-A", null))
        assertFalse(tasksPageOwnerStillCurrent(null, "base|user-B"))
        // No owner provider at all (both null) -> unchanged, apply.
        assertTrue(tasksPageOwnerStillCurrent(null, null))
    }
}
