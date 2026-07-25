package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedTasksCreateOperationTest {
    private val channelId = ChannelId("channel-1")
    private val emptySnapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())

    @Test
    fun createStartOwnsDedicatedPendingIdentityAndClearsOldFailure() {
        val state =
            TasksUiState(
                loadState = ScreenLoadState.Content(emptySnapshot.toTasksContent()),
                refreshing = true,
                createErrorCode = "old_failure",
                createAttempt = 4L,
                createTerminalAttempt = 4L
            )

        val started = state.reduce(SharedTasksIntent.CreateTasksStarted(channelId))

        assertTrue(started.isCreatingTasksFor(channelId))
        assertEquals(5L, started.createAttempt)
        assertNull(started.createTerminalAttempt)
        assertNull(started.createErrorCode)
    }

    @Test
    fun ordinaryRefreshNeverCreatesOrChangesCreatePendingIdentity() {
        val idle = TasksUiState(loadState = ScreenLoadState.Content(emptySnapshot.toTasksContent()))
        val refreshing = idle.reduce(SharedTasksIntent.StartLoading(filter = null))
        assertFalse(refreshing.isCreatingTasksFor(channelId))
        assertEquals(0L, refreshing.createAttempt)

        val creating = idle.reduce(SharedTasksIntent.CreateTasksStarted(channelId))
        val refreshed =
            creating.reduce(
                SharedTasksIntent.SetResult(
                    result = DataResult.Content(emptySnapshot),
                    selectedFilter = null
                )
            )
        assertTrue(refreshed.isCreatingTasksFor(channelId))
        assertEquals(creating.createAttempt, refreshed.createAttempt)
        assertNull(refreshed.createTerminalAttempt)
    }

    @Test
    fun createSuccessClosesExactAttemptWithoutFailure() {
        val started =
            TasksUiState(loadState = ScreenLoadState.Content(emptySnapshot.toTasksContent()))
                .reduce(SharedTasksIntent.CreateTasksStarted(channelId))

        val succeeded = started.reduce(SharedTasksIntent.CreateTasksSucceeded(emptySnapshot))

        assertFalse(succeeded.isCreatingTasksFor(channelId))
        assertEquals(started.createAttempt, succeeded.createTerminalAttempt)
        assertNull(succeeded.createErrorCode)
    }

    @Test
    fun createFailureClosesPendingAndKeepsRetryableErrorForExactAttempt() {
        val started =
            TasksUiState(loadState = ScreenLoadState.Content(emptySnapshot.toTasksContent()))
                .reduce(SharedTasksIntent.CreateTasksStarted(channelId))

        val failed = started.reduce(SharedTasksIntent.CreateTasksFailed("tasks_create_network"))

        assertFalse(failed.isCreatingTasksFor(channelId))
        assertEquals(started.createAttempt, failed.createTerminalAttempt)
        assertEquals("tasks_create_network", failed.createErrorCode)

        val retry = failed.reduce(SharedTasksIntent.CreateTasksStarted(channelId))
        assertTrue(retry.isCreatingTasksFor(channelId))
        assertEquals(2L, retry.createAttempt)
        assertNull(retry.createTerminalAttempt)
        assertNull(retry.createErrorCode)
    }
}
