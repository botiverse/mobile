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
    private fun operation(attempt: Long, channel: ChannelId = channelId) =
        TasksCreateOperationId(attempt = attempt, channelId = channel)

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

        val started = state.reduce(SharedTasksIntent.CreateTasksStarted(operation(5L)))

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

        val creating = idle.reduce(SharedTasksIntent.CreateTasksStarted(operation(1L)))
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
                .reduce(SharedTasksIntent.CreateTasksStarted(operation(1L)))

        val succeeded =
            started.reduce(SharedTasksIntent.CreateTasksSucceeded(operation(1L), emptySnapshot))

        assertFalse(succeeded.isCreatingTasksFor(channelId))
        assertEquals(started.createAttempt, succeeded.createTerminalAttempt)
        assertNull(succeeded.createErrorCode)
    }

    @Test
    fun createFailureClosesPendingAndKeepsRetryableErrorForExactAttempt() {
        val started =
            TasksUiState(loadState = ScreenLoadState.Content(emptySnapshot.toTasksContent()))
                .reduce(SharedTasksIntent.CreateTasksStarted(operation(1L)))

        val failed =
            started.reduce(
                SharedTasksIntent.CreateTasksFailed(operation(1L), "tasks_create_network")
            )

        assertFalse(failed.isCreatingTasksFor(channelId))
        assertEquals(started.createAttempt, failed.createTerminalAttempt)
        assertEquals("tasks_create_network", failed.createErrorCode)

        val retry = failed.reduce(SharedTasksIntent.CreateTasksStarted(operation(2L)))
        assertTrue(retry.isCreatingTasksFor(channelId))
        assertEquals(2L, retry.createAttempt)
        assertNull(retry.createTerminalAttempt)
        assertNull(retry.createErrorCode)
    }

    @Test
    fun staleSuccessAndFailureCannotTerminateNewerAttempt() {
        val attemptA = operation(1L)
        val attemptB = operation(2L)
        val pendingB =
            TasksUiState(loadState = ScreenLoadState.Content(emptySnapshot.toTasksContent()))
                .reduce(SharedTasksIntent.CreateTasksStarted(attemptA))
                .reduce(SharedTasksIntent.CreateTasksStarted(attemptB))

        val afterStaleSuccess =
            pendingB.reduce(SharedTasksIntent.CreateTasksSucceeded(attemptA, emptySnapshot))
        val afterStaleFailure =
            pendingB.reduce(SharedTasksIntent.CreateTasksFailed(attemptA, "stale_failure"))

        assertEquals(pendingB, afterStaleSuccess)
        assertEquals(pendingB, afterStaleFailure)
        assertTrue(afterStaleSuccess.ownsCreateOperation(attemptB))
        assertTrue(afterStaleFailure.ownsCreateOperation(attemptB))

        val completedB =
            pendingB.reduce(SharedTasksIntent.CreateTasksSucceeded(attemptB, emptySnapshot))
        assertFalse(completedB.isCreatingTasksFor(channelId))
        assertEquals(attemptB.attempt, completedB.createTerminalAttempt)
    }

    @Test
    fun staleTerminalCannotCrossChannelBoundary() {
        val channelA = ChannelId("channel-a")
        val channelB = ChannelId("channel-b")
        val attemptA = operation(1L, channelA)
        val attemptB = operation(2L, channelB)
        val pendingB =
            TasksUiState(loadState = ScreenLoadState.Content(emptySnapshot.toTasksContent()))
                .reduce(SharedTasksIntent.CreateTasksStarted(attemptA))
                .reduce(SharedTasksIntent.CreateTasksStarted(attemptB))

        assertEquals(
            pendingB,
            pendingB.reduce(SharedTasksIntent.CreateTasksSucceeded(attemptA, emptySnapshot))
        )
        assertEquals(
            pendingB,
            pendingB.reduce(SharedTasksIntent.CreateTasksFailed(attemptA, "stale_failure"))
        )
        assertTrue(pendingB.isCreatingTasksFor(channelB))
        assertFalse(pendingB.isCreatingTasksFor(channelA))
    }
}
