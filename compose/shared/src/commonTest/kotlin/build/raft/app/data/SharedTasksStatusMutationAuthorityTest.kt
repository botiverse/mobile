package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SharedTasksStatusMutationAuthorityTest {
    @Test
    fun ownerBoundStoreRejectsSynchronousUnadmittedUpsert() {
        val authority = authority()

        assertEquals(
            SharedTaskFactFoldOutcome.Conflict,
            authority.store.upsertTask(ServerA, task(status = TaskStatus.Todo, revision = 1L))
        )
        assertEquals(emptyList(), authority.store.projection(ServerA, SharedTasksScope.Server).tasks)
    }

    @Test
    fun beginStatusMutationPublishesOptimisticProjection() = runBlocking {
        val authority = authority()
        val previousTask = task(status = TaskStatus.InProgress, revision = 7L)
        assertEquals(
            SharedTaskFactFoldOutcome.Accepted,
            authority.store.upsertTaskForCurrentOwner(ServerA, previousTask)
        )

        val permit =
            assertNotNull(
                authority.store.beginStatusMutation(
                    serverId = ServerA,
                    scope = SharedTasksScope.Channel(ChannelA),
                    taskId = previousTask.id,
                    expectedRevision = 7L,
                    status = TaskStatus.Done
                )
            )

        assertEquals(TaskStatus.InProgress, permit.previousTask.status)
        assertEquals(TaskStatus.Done, permit.optimisticTask.status)
        assertEquals(TaskStatus.Done, authority.projectedTask().status)
        assertEquals(
            TaskStatus.Done,
            authority.store.projection(ServerA, SharedTasksScope.Server).tasks.single().status
        )
    }

    @Test
    fun ownerEnvironmentSwitchRejectsLateSuccess() = runBlocking {
        val authority = authority()
        val previousTask = task(status = TaskStatus.Todo, revision = 3L)
        authority.store.upsertTaskForCurrentOwner(ServerA, previousTask)
        val permit = assertNotNull(authority.begin(previousTask, TaskStatus.Done))

        authority.ownerIdentity = "https://api-b.example.test|user-a"

        assertNull(
            authority.store.completeStatusMutation(
                permit,
                previousTask.copy(status = TaskStatus.Done, revision = 4L)
            )
        )
        assertEquals(emptyList(), authority.store.projection(ServerA, SharedTasksScope.Server).tasks)
    }

    @Test
    fun selectedServerSwitchPermanentlyRetiresLateSuccess() = runBlocking {
        val authority = authority()
        val previousTask = task(status = TaskStatus.InReview, revision = 11L)
        authority.store.upsertTaskForCurrentOwner(ServerA, previousTask)
        val permit = assertNotNull(authority.begin(previousTask, TaskStatus.Closed))
        val lateResponse = previousTask.copy(status = TaskStatus.Closed, revision = 12L)

        authority.selectedServerId = "server-b"

        assertNull(authority.store.completeStatusMutation(permit, lateResponse))
        assertEquals(TaskStatus.InReview, authority.projectedTask().status)

        authority.selectedServerId = ServerA

        assertNull(authority.store.completeStatusMutation(permit, lateResponse))
        assertEquals(TaskStatus.InReview, authority.projectedTask().status)
    }

    @Test
    fun newerRealtimeFactSupersedesPermitAndLateFailureCannotRollback() = runBlocking {
        val authority = authority()
        val previousTask = task(status = TaskStatus.Todo, revision = 20L)
        authority.store.upsertTaskForCurrentOwner(ServerA, previousTask)
        val permit = assertNotNull(authority.begin(previousTask, TaskStatus.Done))
        val realtimeTask = previousTask.copy(status = TaskStatus.InReview, revision = 21L)

        assertEquals(
            SharedTaskFactFoldOutcome.Accepted,
            authority.store.upsertTaskForCurrentOwner(ServerA, realtimeTask)
        )
        assertEquals(TaskStatus.InReview, authority.projectedTask().status)

        assertNull(authority.store.rollbackStatusMutation(permit))
        assertEquals(TaskStatus.InReview, authority.projectedTask().status)
    }

    @Test
    fun staleRealtimeFactDoesNotEraseOptimisticProjection() = runBlocking {
        val authority = authority()
        val previousTask = task(status = TaskStatus.InProgress, revision = 30L)
        authority.store.upsertTaskForCurrentOwner(ServerA, previousTask)
        val permit = assertNotNull(authority.begin(previousTask, TaskStatus.Done))

        assertEquals(
            SharedTaskFactFoldOutcome.StaleIgnored,
            authority.store.upsertTaskForCurrentOwner(
                ServerA,
                previousTask.copy(status = TaskStatus.Todo, revision = 29L)
            )
        )
        assertEquals(TaskStatus.Done, authority.projectedTask().status)

        assertEquals(
            TaskStatus.Done,
            authority.store.completeStatusMutationWithoutProjection(permit)?.status
        )
        assertEquals(TaskStatus.Done, authority.projectedTask().status)
    }

    @Test
    fun successfulResponseWithoutRevisionCommitsAgainstPermitRevision() = runBlocking {
        val authority = authority()
        val previousTask = task(status = TaskStatus.Todo, revision = 35L)
        authority.store.upsertTaskForCurrentOwner(ServerA, previousTask)
        val permit = assertNotNull(authority.begin(previousTask, TaskStatus.Closed))

        val accepted =
            assertNotNull(
                authority.store.completeStatusMutation(
                    permit,
                    previousTask.copy(status = TaskStatus.Closed, revision = null)
                )
            )

        assertEquals(TaskStatus.Closed, accepted.status)
        assertEquals(35L, accepted.revision)
        assertEquals(TaskStatus.Closed, authority.projectedTask().status)
    }

    @Test
    fun authClearGenerationInvalidatesPermit() = runBlocking {
        val authority = authority()
        val previousTask = task(status = TaskStatus.Todo, revision = 40L)
        authority.store.upsertTaskForCurrentOwner(ServerA, previousTask)
        val permit = assertNotNull(authority.begin(previousTask, TaskStatus.Done))

        authority.ownerIdentity = null
        authority.store.bindCurrentOwnerIdentity()
        authority.ownerIdentity = OwnerA
        authority.store.bindCurrentOwnerIdentity()

        assertNull(
            authority.store.completeStatusMutation(
                permit,
                previousTask.copy(status = TaskStatus.Done, revision = 41L)
            )
        )
        assertEquals(emptyList(), authority.store.projection(ServerA, SharedTasksScope.Server).tasks)
    }

    @Test
    fun beginAssignMutationPublishesOptimisticAssigneeAndSharesOneMutationSlot() = runBlocking {
        val authority = authority()
        val assignee = MemberProfile(MemberId("user-2"), "Nathan Peer", "nathan", MemberKind.Human)
        val previousTask = task(status = TaskStatus.Todo, revision = 50L).copy(assignee = null)
        assertEquals(
            SharedTaskFactFoldOutcome.Accepted,
            authority.store.upsertTaskForCurrentOwner(ServerA, previousTask)
        )

        val permit =
            assertNotNull(
                authority.store.beginAssignMutation(
                    serverId = ServerA,
                    scope = SharedTasksScope.Channel(ChannelA),
                    taskId = previousTask.id,
                    expectedRevision = 50L,
                    assignee = assignee
                )
            )

        assertEquals(null, permit.previousTask.assignee)
        assertEquals(MemberId("user-2"), permit.optimisticTask.assignee?.id)
        assertEquals(MemberId("user-2"), authority.projectedTask().assignee?.id)

        // Chained-begin semantics (same as status): a second begin while one write
        // is pending bases its previousTask on the pending optimistic projection.
        val chained =
            assertNotNull(
                authority.store.beginStatusMutation(
                    serverId = ServerA,
                    scope = SharedTasksScope.Channel(ChannelA),
                    taskId = previousTask.id,
                    expectedRevision = 50L,
                    status = TaskStatus.Done
                )
            )
        assertEquals(MemberId("user-2"), chained.previousTask.assignee?.id)
        assertEquals(TaskStatus.Done, chained.optimisticTask.status)

        // Rollback path is shared with status mutations: the assign write failing
        // restores its own previousTask snapshot.
        val restored = authority.store.rollbackStatusMutation(permit)
        assertEquals(null, restored?.assignee)
    }

    private class MutationAuthority(
        var ownerIdentity: String? = OwnerA,
        var selectedServerId: String? = ServerA
    ) {
        val store =
            SharedTasksDataStore(
                ownerIdentityProvider = { ownerIdentity },
                currentServerIdProvider = { selectedServerId }
            )

        suspend fun begin(
            task: TaskSummary,
            status: TaskStatus
        ): SharedTasksStatusMutationPermit? =
            store.beginStatusMutation(
                serverId = ServerA,
                scope = SharedTasksScope.Channel(ChannelA),
                taskId = task.id,
                expectedRevision = task.revision,
                status = status
            )

        fun projectedTask(): TaskSummary =
            store
                .projection(ServerA, SharedTasksScope.Channel(ChannelA))
                .tasks
                .single()
    }

    private fun authority(): MutationAuthority = MutationAuthority()

    private fun task(
        status: TaskStatus,
        revision: Long
    ): TaskSummary =
        TaskSummary(
            id = TaskId("task-17"),
            title = "Unify task status authority",
            status = status,
            taskNumber = 17,
            messageId = MessageId("message-task-17"),
            channelId = ChannelA,
            channelName = "proj-uiux",
            revision = revision,
            updatedAtLabel = "now"
        )

    private companion object {
        const val OwnerA = "https://api-a.example.test|user-a"
        const val ServerA = "server-a"
        val ChannelA = ChannelId("channel-a")
    }
}
