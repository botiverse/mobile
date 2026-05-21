package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SharedTasksStoreTest {
    @Test
    fun loadingKeepsVisibleContentAndMarksRefresh() {
        val state =
            TasksUiState(
                loadState =
                    ScreenLoadState.Content(
                        TasksContent(
                            summaryCounts = mapOf(TaskStatus.Todo to 1),
                            tasks = listOf(task("task-1", TaskStatus.Todo))
                        )
                    ),
                navigationErrorCode = "missing_task_reference"
            )

        val next = state.reduce(SharedTasksIntent.StartLoading(filter = TaskStatus.InReview))

        assertEquals(TaskStatus.InReview, next.selectedFilter)
        assertEquals(true, next.refreshing)
        assertEquals(null, next.navigationErrorCode)
        assertIs<ScreenLoadState.Content<TasksContent>>(next.loadState)
    }

    @Test
    fun optimisticStatusUpdateAndFailureRestorePreviousTask() {
        val previousTask = task("task-1", TaskStatus.Todo)
        val state =
            TasksUiState(
                loadState =
                    ScreenLoadState.Content(
                        TasksContent(
                            summaryCounts = mapOf(TaskStatus.Todo to 1),
                            tasks = listOf(previousTask)
                        )
                    )
            )

        val optimistic = state.reduce(SharedTasksIntent.UpdateTaskStatus("task-1", TaskStatus.Done))
        val optimisticContent = assertIs<ScreenLoadState.Content<TasksContent>>(optimistic.loadState).data
        assertEquals(TaskStatus.Done, optimisticContent.tasks.single().status)
        assertEquals(1, optimisticContent.summaryCounts[TaskStatus.Done])
        assertEquals("task-1", optimistic.statusUpdatingTaskId)

        val restored =
            optimistic.reduce(
                SharedTasksIntent.TaskStatusUpdateFailed(
                    previousTask = previousTask,
                    errorCode = "task_status_failed"
                )
            )
        val restoredContent = assertIs<ScreenLoadState.Content<TasksContent>>(restored.loadState).data
        assertEquals(TaskStatus.Todo, restoredContent.tasks.single().status)
        assertEquals(1, restoredContent.summaryCounts[TaskStatus.Todo])
        assertEquals(null, restored.statusUpdatingTaskId)
        assertEquals("task_status_failed", restored.statusUpdateErrorCode)
    }

    @Test
    fun setContentDerivesSummaryCountsWhenSnapshotCountsAreMissing() {
        val state = TasksUiState(ScreenLoadState.Loading)

        val next =
            state.reduce(
                SharedTasksIntent.SetContent(
                    snapshot =
                        TaskSnapshot(
                            tasks =
                                listOf(
                                    task("task-1", TaskStatus.Todo),
                                    task("task-2", TaskStatus.InProgress)
                                ),
                            summaryCounts = emptyMap()
                        ),
                    selectedFilter = TaskStatus.Todo
                )
            )

        val content = assertIs<ScreenLoadState.Content<TasksContent>>(next.loadState).data
        assertEquals(TaskStatus.Todo, next.selectedFilter)
        assertEquals(false, next.refreshing)
        assertEquals(1, content.summaryCounts[TaskStatus.Todo])
        assertEquals(1, content.summaryCounts[TaskStatus.InProgress])
    }

    @Test
    fun presentationReducerPrunesChannelsAndFiltersVisibleTasks() {
        val content =
            TasksContent(
                summaryCounts = emptyMap(),
                tasks =
                    listOf(
                        task("task-1", TaskStatus.Todo, channelName = "android"),
                        task("task-2", TaskStatus.InReview, channelName = "kmp"),
                        task("task-3", TaskStatus.Todo, channelName = "kmp")
                    )
            )

        val presentation =
            TasksPresentationState()
                .reduce(TasksPresentationIntent.ToggleChannel("kmp"))
                .reduce(TasksPresentationIntent.ToggleChannel("missing"))
                .reduce(TasksPresentationIntent.PruneUnavailableChannels(setOf("android", "kmp")))
                .reduce(TasksPresentationIntent.SelectMode(TasksViewMode.List))

        val visible =
            content.visibleContent(
                selectedFilter = TaskStatus.Todo,
                presentationState = presentation,
                showChannelFilter = true
            )

        assertEquals(TasksViewMode.List, presentation.selectedMode)
        assertEquals(setOf("kmp"), visible.selectedChannelNames)
        assertEquals(listOf("android", "kmp"), visible.channelOptions)
        assertEquals(listOf("task-3"), visible.filteredTasks.map { it.id.value })
        assertEquals(2, visible.channelScopedContent.tasks.size)
        assertEquals(1, visible.channelScopedContent.summaryCounts[TaskStatus.Todo])
        assertEquals(1, visible.channelScopedContent.summaryCounts[TaskStatus.InReview])
    }

    private fun task(
        id: String,
        status: TaskStatus,
        channelName: String? = null
    ): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = "Task $id",
            status = status,
            taskNumber = id.substringAfter("task-").toIntOrNull(),
            messageId = MessageId("message-$id"),
            channelId = ChannelId("channel-1"),
            channelName = channelName,
            updatedAtLabel = "now"
        )
}
