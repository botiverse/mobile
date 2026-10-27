package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task v2 conversation bar (web `TaskConversationBar` / TaskProperties / `useTaskConversationFollow`):
 * status options and edit rights, the page's header state, follow writes and the `task:*` realtime
 * frames it reads. Fixtures follow botiverse/slock staging @ 0000139628e06ca3f10ed2cb6f44b61ba80d70a5:
 * `packages/web/src/components/task/taskStatusUi.ts` (`VALID_TRANSITIONS`, `getTaskStatusOptions`),
 * `components/task/TaskProperties.tsx` (`canEditAssignee`), `routes/channels.ts` (`/threads/follow`
 * with `threadChannelId`), `routes/tasks.ts` (`conversationFollowed`), `services/taskRealtimeEvents.ts`.
 */
class TaskConversationHeaderStoreTest {
    @Test
    fun statusOptionsFollowTheWebTransitionsAndAdminsMayForceAny() {
        // (current status, member options, closed->todo reads "Reopen to Todo")
        val memberCases =
            listOf(
                TaskStatus.Todo to listOf(TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.Closed),
                TaskStatus.InProgress to listOf(TaskStatus.InProgress, TaskStatus.InReview, TaskStatus.Done, TaskStatus.Closed),
                TaskStatus.InReview to listOf(TaskStatus.InReview, TaskStatus.Done, TaskStatus.InProgress, TaskStatus.Closed),
                TaskStatus.Done to listOf(TaskStatus.Done, TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.InReview, TaskStatus.Closed),
                TaskStatus.Closed to listOf(TaskStatus.Closed, TaskStatus.Todo, TaskStatus.InProgress),
            )
        memberCases.forEach { (current, expected) ->
            val options = taskStatusOptions(current, canManageServer = false)
            assertEquals(expected, options.map { it.status }, "member current=$current")
            assertEquals(
                if (current == TaskStatus.Closed) listOf(TaskStatus.Todo) else emptyList(),
                options.filter { it.reopensToTodo }.map { it.status },
                "reopen current=$current",
            )
            assertEquals(TaskStatus.entries.toList(), taskStatusOptions(current, canManageServer = true).map { it.status }, "admin current=$current")
        }
        assertTrue(taskServerRoleCanManage("owner"))
        assertTrue(taskServerRoleCanManage("admin"))
        assertFalse(taskServerRoleCanManage("member"))
        assertFalse(taskServerRoleCanManage(null))
    }

    @Test
    fun editRightsMatchTheWebGatesForRoleReadOnlyAndDone() {
        // (role, status, readOnlyReason, can edit status, can edit assignee)
        data class Case(val role: String?, val status: TaskStatus, val readOnly: String?, val statusEditable: Boolean, val assigneeEditable: Boolean)
        listOf(
            Case("member", TaskStatus.InProgress, null, true, true),
            Case("member", TaskStatus.Done, null, true, false),
            Case("guest", TaskStatus.InProgress, null, false, false),
            Case("owner", TaskStatus.Todo, "historical_joint", false, false),
        ).forEach { case ->
            val task = task(status = case.status).copy(readOnlyReason = case.readOnly)
            assertEquals(case.statusEditable, task.canEditStatus(case.role), "status $case")
            assertEquals(case.assigneeEditable, task.canEditAssignee(case.role), "assignee $case")
        }
    }

    @Test
    fun headerStateResolvesTheTaskAndClosesOnlyForItsOwnDeletion() {
        val start = TaskConversationHeaderState(ChannelId("conv-1"))
        val ref = TaskConversationChannelRef(ChannelId("conv-1"), ChannelId("channel-1"), TaskId("task-1"))
        val resolved = start.reduce(TaskConversationHeaderIntent.RefLoaded(DataResult.Content(ref)))
        assertEquals(TaskConversationRefStatus.Resolved, resolved.refStatus)
        assertEquals(ref, resolved.ref)

        // (result, status): a channel that is not a task conversation shows no bar; a failure may retry.
        assertEquals(
            TaskConversationRefStatus.NotTask,
            start.reduce(TaskConversationHeaderIntent.RefLoaded(DataResult.Empty(EmptyReason.NoMessages))).refStatus,
        )
        assertEquals(
            TaskConversationRefStatus.Failed,
            start.reduce(TaskConversationHeaderIntent.RefLoaded(DataResult.Error(ScreenError("x")))).refStatus,
        )
        // Another conversation's answer is not this page's task.
        assertEquals(
            TaskConversationRefStatus.NotTask,
            start.reduce(TaskConversationHeaderIntent.RefLoaded(DataResult.Content(ref.copy(conversationChannelId = ChannelId("conv-2"))))).refStatus,
        )

        assertFalse(resolved.reduce(TaskConversationHeaderIntent.TaskDeleted(TaskId("task-2"), ChannelId("conv-2"))).removed)
        assertTrue(resolved.reduce(TaskConversationHeaderIntent.TaskDeleted(TaskId("task-1"), null)).removed)
        // Before the task is known, the deleted conversation id still names this page.
        assertTrue(start.reduce(TaskConversationHeaderIntent.TaskDeleted(TaskId("task-9"), ChannelId("conv-1"))).removed)
    }

    @Test
    fun aTaskThatLeavesAStatusFilteredListIsNotDeleted() {
        // Tenny #3786: the channel list may show open tasks only; moving the task to Done or Closed
        // drops it from that list. The page keeps the last copy and is not "deleted".
        val ref = TaskConversationChannelRef(ChannelId("conv-1"), ChannelId("channel-1"), TaskId("task-1"))
        val seen =
            TaskConversationHeaderState(ChannelId("conv-1"))
                .reduce(TaskConversationHeaderIntent.RefLoaded(DataResult.Content(ref)))
                .reduce(TaskConversationHeaderIntent.TaskSeen(task(TaskStatus.InProgress)))
        assertEquals(TaskStatus.InProgress, seen.barTask(listed = task(TaskStatus.InProgress))?.status)
        listOf(TaskStatus.Done, TaskStatus.Closed).forEach { status ->
            // `task:updated` (or the write's answer) carries the new status; the filtered list no longer has it.
            val moved = seen.reduce(TaskConversationHeaderIntent.TaskSeen(task(status)))
            assertFalse(moved.removed, "status=$status")
            assertEquals(status, moved.barTask(listed = null)?.status, "status=$status")
            // Even with no update seen, a list without the task is not a deletion.
            assertFalse(seen.removed)
            assertEquals(TaskStatus.InProgress, seen.barTask(listed = null)?.status)
        }
        // Another task's copy is not this page's; only `task:deleted` removes the bar.
        assertNull(
            TaskConversationHeaderState(ChannelId("conv-1"))
                .reduce(TaskConversationHeaderIntent.RefLoaded(DataResult.Content(ref.copy(taskId = TaskId("task-2")))))
                .reduce(TaskConversationHeaderIntent.TaskSeen(task(TaskStatus.Done)))
                .lastSeenTask,
        )
        val deleted = seen.reduce(TaskConversationHeaderIntent.TaskDeleted(TaskId("task-1"), null))
        assertTrue(deleted.removed)
        assertNull(deleted.barTask(listed = task(TaskStatus.InProgress)))
    }

    @Test
    fun followOverrideWinsOverTheTaskDtoOnlyAfterTheServerTookIt() {
        val state = TaskConversationHeaderState(ChannelId("conv-1"))
        assertEquals(true, state.followed(taskFollowed = true))
        assertNull(state.followed(taskFollowed = null))
        val pending = state.reduce(TaskConversationHeaderIntent.FollowStarted(follow = false))
        assertTrue(pending.followPending)
        val rejected = pending.reduce(TaskConversationHeaderIntent.FollowSettled(follow = false, accepted = false))
        assertFalse(rejected.followPending)
        assertEquals(true, rejected.followed(taskFollowed = true))
        val accepted = pending.reduce(TaskConversationHeaderIntent.FollowSettled(follow = false, accepted = true))
        assertEquals(false, accepted.followed(taskFollowed = true))
    }

    @Test
    fun viewModelFollowsAndUnfollowsByTheConversationIdAndReportsAFailure() = runTest {
        val calls = mutableListOf<String>()
        var unfollowResult: DataResult<ThreadActionResult> = DataResult.Error(ScreenError("thread_unfollow_500"))
        val viewModel =
            headerViewModel(
                follow = { id ->
                    calls += "follow:${id.value}"
                    DataResult.Content(ThreadActionResult(id, changed = true))
                },
                unfollow = { id ->
                    calls += "unfollow:${id.value}"
                    unfollowResult
                },
            )
        viewModel.start()
        advanceUntilIdle()
        assertEquals(TaskConversationRefStatus.Resolved, viewModel.state.value.refStatus)

        viewModel.toggleFollow(currentlyFollowed = false)
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.followed(taskFollowed = false))

        val failure = CompletableDeferred<TaskConversationHeaderEvent>()
        val collector = launch { failure.complete(viewModel.events.first()) }
        viewModel.toggleFollow(currentlyFollowed = true)
        advanceUntilIdle()
        assertEquals(listOf("follow:conv-1", "unfollow:conv-1"), calls)
        assertEquals(true, viewModel.state.value.followed(taskFollowed = false), "a failed unfollow keeps the follow")
        val event = assertIs<TaskConversationHeaderEvent.FollowFailed>(failure.await())
        assertEquals("thread_unfollow_500", event.code)
        collector.cancel()

        unfollowResult = DataResult.Content(ThreadActionResult(ChannelId("conv-1"), changed = true))
        viewModel.onTaskDeleted(TaskId("task-1"), null)
        viewModel.toggleFollow(currentlyFollowed = true)
        advanceUntilIdle()
        assertEquals(2, calls.size, "no write once the task is gone")
    }

    @Test
    fun repositoryFollowsATaskConversationByItsChannelAndResolvesItsTask() = runTest {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        // routes/channels.ts `/threads/follow`, task arm: `{ ok: true, threadChannelId }`.
                        "POST channels/threads/follow" to """{"ok":true,"threadChannelId":"conv-1"}""",
                        "GET channels/conv-1" to
                            """{"id":"conv-1","serverId":"server-1","name":"task-task-1","description":null,"type":"task","guestVisible":false,"guestJoinable":false,"parentMessageId":null,"parentChannelId":"channel-1","taskId":"task-1","createdAt":"2026-10-27T09:00:00.000Z","archivedAt":null,"joined":false}""",
                        "GET channels/channel-1" to """{"id":"channel-1","name":"general","type":"channel","joined":true}""",
                    ),
            )
        val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)

        val followed = assertIs<DataResult.Content<ThreadActionResult>>(repository.followTaskConversation(ChannelId("conv-1"))).data
        assertEquals(ChannelId("conv-1"), followed.threadChannelId)
        val body = Json.parseToJsonElement(network.bodies.single()).jsonObject
        assertEquals("conv-1", body["threadChannelId"]?.jsonPrimitive?.content)
        assertNull(body["parentMessageId"])

        assertEquals(
            TaskConversationChannelRef(ChannelId("conv-1"), ChannelId("channel-1"), TaskId("task-1")),
            assertIs<DataResult.Content<TaskConversationChannelRef>>(repository.loadTaskConversationRef(ChannelId("conv-1"))).data,
        )
        assertIs<DataResult.Empty>(repository.loadTaskConversationRef(ChannelId("channel-1")))
    }

    @Test
    fun taskListCarriesTheReadersConversationFollowState() {
        // routes/tasks.ts `withConversationFollowed`: only v2 tasks carry it.
        val tasks =
            parseSharedServerTasksPayload(
                """{"tasks":[${taskDtoJson(conversationFollowed = "true")},${taskDtoJson(id = "task-2", conversationFollowed = null)}]}"""
            )?.tasks ?: error("tasks dropped")
        assertEquals(listOf(true, null), tasks.map { it.conversationFollowed })
    }

    @Test
    fun realtimeTaskFramesKeepTheV2ConversationFields() {
        val mapper = RealtimeEventMapper()
        // emitTaskUpdated: `{ channelId, task: projectTaskToChannel(enriched) }` (the full task DTO).
        val updated =
            assertIs<TaskUpdated>(
                mapper.map(RealtimeSocketEvents.TaskUpdated, """{"channelId":"channel-1","task":${taskDtoJson(conversationFollowed = null)}}""")
            ).task
        assertNull(updated.messageId, "a v2 task's messageId is its id, never a message")
        assertEquals(ChannelId("conv-1"), updated.conversationChannelId)
        assertTrue(updated.conversationAnchored)
        assertEquals(4, updated.conversationReplyCount)
        assertEquals("Wire the bar", updated.description)

        // emitTaskDeleted: `{ channelId, taskId, conversationChannelId? }`.
        val deleted =
            assertIs<TaskDeleted>(
                mapper.map(RealtimeSocketEvents.TaskDeleted, """{"channelId":"channel-1","taskId":"task-1","conversationChannelId":"conv-1"}""")
            )
        assertEquals(TaskId("task-1"), deleted.taskId)
        assertEquals(ChannelId("conv-1"), deleted.conversationChannelId)
        val olderServer = assertIs<TaskDeleted>(mapper.map(RealtimeSocketEvents.TaskDeleted, """{"channelId":"channel-1","taskId":"task-1"}"""))
        assertNull(olderServer.conversationChannelId)
    }

    private fun TestScope.headerViewModel(
        follow: suspend (ChannelId) -> DataResult<ThreadActionResult>,
        unfollow: suspend (ChannelId) -> DataResult<ThreadActionResult>,
    ): TaskConversationHeaderViewModel =
        TaskConversationHeaderViewModel(
            conversationChannelId = ChannelId("conv-1"),
            loadRef = { DataResult.Content(TaskConversationChannelRef(it, ChannelId("channel-1"), TaskId("task-1"))) },
            follow = follow,
            unfollow = unfollow,
            scopeOverride = this,
        )

    private fun task(status: TaskStatus): TaskSummary =
        parseSharedServerTasksPayload("""{"tasks":[${taskDtoJson(conversationFollowed = null)}]}""")
            ?.tasks
            ?.single()
            ?.copy(status = status)
            ?: error("task dropped")

    private fun taskDtoJson(id: String = "task-1", conversationFollowed: String?): String =
        """
        {"id":"$id","messageId":"$id","hostMessageId":null,"sourceMessageId":null,"conversationReplyCount":4,
         "conversationChannelId":"conv-1","conversationAnchored":true,"channelId":"channel-1","channelName":"general",
         "channelType":"channel","taskNumber":7,"title":"Ship the bar","description":"Wire the bar","status":"in_progress",
         "createdByType":"user","createdById":"user-1","createdByName":"Artin","createdByHandle":"artin",
         "createdByMembershipStatus":"active","claimedByType":"agent","claimedById":"agent-1","claimedByName":"lagoon",
         "claimedAt":"2026-10-27T09:10:00.000Z","completedAt":null,"requiresResourceReceipt":false,"resourceReceipt":null,
         "resourceReceiptRecordedAt":null,"resourceTeardownOwnerAgentId":null,"resourceExpiryFollowupId":null,
         "createdAt":"2026-10-27T09:00:00.000Z","updatedAt":"2026-10-27T09:30:00.000Z","revision":3,"isLegacy":false,
         "readOnlyReason":null${conversationFollowed?.let { ""","conversationFollowed":$it""" } ?: ""}}
        """.trimIndent()
}
