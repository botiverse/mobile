package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationDetailsViewModel
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskDetailLoad
import build.raft.app.data.TaskEarlierDiscussion
import build.raft.app.data.TaskHistoryEvent
import build.raft.app.data.TaskHistoryDetail
import build.raft.app.data.TaskSourceMessagePreview
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.data.parseTaskHistory
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Task v2 task head (web `TaskConversationDialog` + `TaskModalHead` + `TaskProperties`,
 * botiverse/slock@3f25e17ed): the task DTO's facts as the head shows them, the head's reads
 * (history per revision, source, earlier discussion), the navigation targets of Source / View in
 * Channel / Earlier discussion, and the task conversation's composer placeholder and search.
 */
class TaskConversationHeadModelTest {
    @Test
    fun headShowsTheTaskFactsAsWebDoes() {
        val task = task().copy(sourceMessageId = MessageId("source-1"))
        val members =
            listOf(
                MemberProfile(id = MemberId("user-1"), displayName = "Artin Display", handle = "artin", kind = MemberKind.Human),
                MemberProfile(id = MemberId("agent-1"), displayName = "Lagoon", handle = "lagoon", kind = MemberKind.Agent),
            )
        val history = parseTaskHistory(
            """{"events":[{"id":"e1","eventType":"assignee_changed","actorType":"user","actorName":"artin",
               "createdAt":"2026-10-27T09:01:00.000Z","payload":{"assigneeType":"agent","assigneeId":"agent-1"}}]}"""
        )!!
        val details =
            TaskConversationDetailsState(
                taskId = task.id,
                history = TaskDetailLoad.Loaded(history),
                sourceMessageId = MessageId("source-1"),
                source = TaskDetailLoad.Loaded(TaskSourceMessagePreview(MessageId("source-1"), members[0], "first line", null)),
            )
        val model =
            task.taskConversationHeadModel(TaskConversationHeaderState(ChannelId("conv-1")), details, "member", false, members, null, serverId = "server-1")
        assertEquals("Ship the bar", model.title)
        // Source and Created by name who they are, so their faces resolve from the server's directory
        // (RaftMemberAvatar) rather than from the sparse task / message payload.
        assertEquals("user-1", model.source?.authorId)
        assertEquals(TaskHeadPerson(MemberKind.Human, "user-1"), model.creator)
        assertEquals("server-1", model.avatarServerId)
        assertEquals("Wire the bar", model.description)
        // Created by: the channel member's display name (web `displayNameFor`), not the DTO handle.
        assertEquals("Artin Display", model.createdBy)
        // Web `formatMediumDateTime24`: the date and the 24-hour time in the reader's zone (UTC here).
        assertEquals(TaskHeadDateTime(2026, 10, 27, 9, 0), model.createdAt)
        assertEquals(TaskHeadDateTime(2026, 10, 27, 18, 30), taskHeadDateTime("2026-10-27T09:00:00.000Z", build.raft.app.model.RaftTimeContext(localOffsetMinutes = 570)))
        assertEquals(TaskHeadDateTime(2024, 2, 29, 23, 59), taskHeadDateTime("2024-03-01T00:59:00Z", build.raft.app.model.RaftTimeContext(localOffsetMinutes = -60)))
        assertNull(taskHeadDateTime("not a time", null))
        assertEquals("Artin Display", model.source?.authorName)
        assertEquals("first line", model.source?.preview)
        // A v2 task has no host message: no Earlier discussion row.
        assertNull(model.earlierDiscussion)
        val rows = assertIs<TaskHeadHistory.Rows>(model.history).rows
        assertEquals("Lagoon", rows.single().assigneeName)
        assertEquals(TaskHistoryDetail.Assigned("agent", "agent-1"), rows.single().detail)
    }

    @Test
    fun creatorOutsideTheChannelReadsAsTheHandleAndNoSourceRowForAHostSource() {
        // A pre-v2 task whose source IS its host: Earlier discussion leads there, so no Source row.
        val task = task().copy(hostMessageId = MessageId("host-1"), sourceMessageId = MessageId("host-1"))
        val unknownCount = task.taskConversationHeadModel(TaskConversationHeaderState(ChannelId("conv-1")), TaskConversationDetailsState(), null, false, emptyList(), null)
        assertEquals("@Artin", unknownCount.createdBy)
        assertNull(unknownCount.source)
        assertEquals(TaskHeadEarlierDiscussion(replyCount = null), unknownCount.earlierDiscussion)

        fun withReplies(count: Int) =
            TaskConversationDetailsState(
                hostMessageId = MessageId("host-1"),
                earlierDiscussion = TaskDetailLoad.Loaded(TaskEarlierDiscussion(MessageId("host-1"), count, ChannelId("th-1"))),
            )
        val counted = task.taskConversationHeadModel(TaskConversationHeaderState(ChannelId("conv-1")), withReplies(3), null, false, emptyList(), null)
        assertEquals(TaskHeadEarlierDiscussion(replyCount = 3), counted.earlierDiscussion)
        // Known to have no replies: hidden (web).
        val empty = task.taskConversationHeadModel(TaskConversationHeaderState(ChannelId("conv-1")), withReplies(0), null, false, emptyList(), null)
        assertNull(empty.earlierDiscussion)
    }

    @Test
    fun historyIsReadOncePerTaskAndAgainForANewerRevisionOnly() = runTest {
        val historyCalls = mutableListOf<TaskId>()
        val sourceCalls = mutableListOf<MessageId>()
        val earlierCalls = mutableListOf<MessageId>()
        val vm = viewModel(this, historyCalls, sourceCalls, earlierCalls)
        val task = task().copy(sourceMessageId = MessageId("source-1"), hostMessageId = MessageId("host-1"))
        vm.onTask(task)
        advanceUntilIdle()
        vm.onTask(task)
        vm.onTask(task.copy(revision = 2))
        advanceUntilIdle()
        vm.onTask(task.copy(revision = 4))
        advanceUntilIdle()
        assertEquals(List(3) { TaskId("task-1") }, historyCalls)
        assertEquals(listOf(MessageId("source-1")), sourceCalls)
        assertEquals(listOf(MessageId("host-1")), earlierCalls)
        val state = vm.state.value
        assertEquals(4L, state.historyRevision)
        assertIs<TaskDetailLoad.Loaded<*>>(state.history)
        assertIs<TaskDetailLoad.Loaded<*>>(state.source)
        assertEquals(2, state.earlierDiscussion.let { (it as TaskDetailLoad.Loaded).value.replyCount })
    }

    @Test
    fun aFailedRefreshKeepsTheRowsAndALateAnswerForAnotherTaskIsDropped() = runTest {
        var fail = false
        val gate = CompletableDeferred<Unit>()
        val vm =
            TaskConversationDetailsViewModel(
                loadHistory = { taskId ->
                    if (taskId == TaskId("task-old")) gate.await()
                    if (fail) DataResult.Error(ScreenError("x", true)) else DataResult.Content(listOf(event(taskId.value)))
                },
                loadSource = { _, id -> DataResult.Content(TaskSourceMessagePreview(id, null, "", null)) },
                loadEarlierDiscussion = { _, id -> DataResult.Content(TaskEarlierDiscussion(id, 0, null)) },
                scopeOverride = this,
            )
        vm.onTask(task().copy(id = TaskId("task-1")))
        advanceUntilIdle()
        fail = true
        vm.onTask(task().copy(id = TaskId("task-1"), revision = 9))
        advanceUntilIdle()
        assertEquals("task-1", (vm.state.value.history as TaskDetailLoad.Loaded).value.single().id)

        // The page moves to another task while the old task's read is still out: its answer is dropped.
        fail = false
        vm.onTask(task().copy(id = TaskId("task-old"), revision = 1))
        vm.onTask(task().copy(id = TaskId("task-2"), revision = 1))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TaskId("task-2"), vm.state.value.taskId)
        assertEquals("task-2", (vm.state.value.history as TaskDetailLoad.Loaded).value.single().id)
    }

    @Test
    fun aTaskWithoutRevisionRereadsItsHistoryWhenItsFactsChangeAndRetryRereadsAFailure() = runTest {
        val calls = mutableListOf<TaskId>()
        var fail = true
        val vm =
            TaskConversationDetailsViewModel(
                loadHistory = { taskId ->
                    calls += taskId
                    if (fail) DataResult.Error(ScreenError("x", true)) else DataResult.Content(listOf(event("e")))
                },
                loadSource = { _, id -> DataResult.Content(TaskSourceMessagePreview(id, null, "", null)) },
                loadEarlierDiscussion = { _, id -> DataResult.Content(TaskEarlierDiscussion(id, 0, null)) },
                scopeOverride = this,
            )
        val unrevised = task().copy(revision = null)
        vm.onTask(unrevised)
        advanceUntilIdle()
        assertIs<TaskDetailLoad.Failed>(vm.state.value.history)
        // Retry reads it again.
        fail = false
        vm.retryHistory()
        advanceUntilIdle()
        assertIs<TaskDetailLoad.Loaded<*>>(vm.state.value.history)
        // The same facts: nothing to read; a status change: read again.
        vm.onTask(unrevised.copy())
        vm.onTask(unrevised.copy(status = build.raft.app.model.TaskStatus.Done))
        advanceUntilIdle()
        assertEquals(3, calls.size)
    }

    @Test
    fun onAPhoneTheHeadMovesWithTheConversationScrollAndOffAPhoneItStays() {
        val state = TaskHeadScrollState()
        state.updateHeight(300f)
        // Pinned (tablet): nothing moves.
        assertEquals(0f, state.consume(-50f))
        state.scrollsWithConversation = true
        // A drag towards newer messages slides it away first, up to its height.
        assertEquals(-120f, state.connection.onPreScroll(androidx.compose.ui.geometry.Offset(0f, -120f), androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput).y)
        assertEquals(-180f, state.connection.onPreScroll(androidx.compose.ui.geometry.Offset(0f, -500f), androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput).y)
        assertEquals(true, state.collapsed)
        // A drag back down: the list scrolls first; only what it leaves (at its oldest) brings the head back.
        assertEquals(0f, state.connection.onPreScroll(androidx.compose.ui.geometry.Offset(0f, 80f), androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput).y)
        assertEquals(
            80f,
            state.connection.onPostScroll(
                androidx.compose.ui.geometry.Offset.Zero,
                androidx.compose.ui.geometry.Offset(0f, 80f),
                androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput,
            ).y,
        )
        assertEquals(false, state.collapsed)
        // The keyboard folds it away (even before it is measured); the top bar's summary brings it back.
        val fresh = TaskHeadScrollState().apply { scrollsWithConversation = true }
        fresh.collapse()
        fresh.updateHeight(200f)
        assertEquals(true, fresh.collapsed)
        fresh.expand()
        assertEquals(false, fresh.collapsed)
    }

    @Test
    fun sourceAndViewInChannelOpenTheTaskChannelAtItsMessage() {
        val dmTask = task().copy(channelId = ChannelId("dm-1"), channelType = "dm", sourceMessageId = MessageId("source-1"))
        val dm = dmTask.taskChannelOpenRequest("server-1", dmTask.taskViewInChannelAnchor())!!
        assertEquals(ConversationKind.DirectMessage, dm.conversationIdentity.kind)
        assertEquals(ChannelId("dm-1"), dm.conversationIdentity.channelId)
        assertEquals("source-1", dm.anchorMessageId)

        // A pre-v2 task without a source hangs under its host; a task with neither opens the channel.
        val hosted = task().copy(hostMessageId = MessageId("host-1"))
        assertEquals(MessageId("host-1"), hosted.taskViewInChannelAnchor())
        val bare = task()
        val channel = bare.taskChannelOpenRequest("server-1", bare.taskViewInChannelAnchor())!!
        assertEquals(ConversationKind.Channel, channel.conversationIdentity.kind)
        assertNull(channel.anchorMessageId)
        assertNull(bare.copy(channelId = null).taskChannelOpenRequest("server-1", null))
    }

    @Test
    fun earlierDiscussionOpensTheHostMessageThread() {
        val hosted = task().copy(hostMessageId = MessageId("host-1"))
        val request = hosted.taskEarlierDiscussionOpenRequest("server-1", ChannelId("th-1"))!!
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals("host-1", request.conversationIdentity.threadParentMessageId())
        assertNull(task().taskEarlierDiscussionOpenRequest("server-1", null))
    }

    @Test
    fun aTaskConversationComposerSaysMessageThisTaskAndSearchesInPlace() {
        assertEquals(
            ThreadTaskComposerPlaceholder,
            threadSurfaceComposerPlaceholder(RaftThreadSurfaceKind.Channel, "#ch:#7 Ship", "conv-1", channelKind = RaftTaskConversationChannelKind),
        )
        assertEquals("Message #general", threadSurfaceComposerPlaceholder(RaftThreadSurfaceKind.Channel, "general", "ch-1", channelKind = "channel"))
        val payload =
            RaftThreadScreenPayload(
                instanceId = "conv-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftTaskConversationChannelKind,
                sourceThreadId = "conv-1",
                status = "content",
            )
        assertEquals(ThreadHeaderSearchAction.InlineThreadSearch, payload.threadHeaderSearchAction())
        assertNull(payload.threadChannelSearchChannelId())
        assertEquals(ThreadTaskComposerPlaceholder, payload.resolveThreadComposer("#ch:#7 Ship").placeholder)
        assertEquals(
            ThreadHeaderSearchAction.ChannelSearch,
            payload.copy(channelKind = "channel").threadHeaderSearchAction(),
        )
    }

    private fun viewModel(
        scope: TestScope,
        historyCalls: MutableList<TaskId>,
        sourceCalls: MutableList<MessageId>,
        earlierCalls: MutableList<MessageId>,
    ) = TaskConversationDetailsViewModel(
        loadHistory = { taskId ->
            historyCalls += taskId
            DataResult.Content(listOf(event("e-${historyCalls.size}")))
        },
        loadSource = { _, id ->
            sourceCalls += id
            DataResult.Content(TaskSourceMessagePreview(id, null, "line", null))
        },
        loadEarlierDiscussion = { _, id ->
            earlierCalls += id
            DataResult.Content(TaskEarlierDiscussion(id, 2, null))
        },
        scopeOverride = scope,
    )

    private fun event(id: String) =
        TaskHistoryEvent(id, "created", "user", "artin", "2026-10-27T09:00:00.000Z", kotlinx.serialization.json.JsonObject(emptyMap()))

    private fun task(): TaskSummary =
        parseSharedServerTasksPayload(
            """
            {"tasks":[{"id":"task-1","messageId":"task-1","hostMessageId":null,"sourceMessageId":null,"conversationReplyCount":0,
             "conversationChannelId":"conv-1","conversationAnchored":true,"channelId":"channel-1","channelName":"general",
             "channelType":"channel","taskNumber":7,"title":"Ship the bar","description":"Wire the bar","status":"in_progress",
             "createdByType":"user","createdById":"user-1","createdByName":"Artin","claimedByType":null,"claimedById":null,
             "claimedByName":null,"createdAt":"2026-10-27T09:00:00.000Z","updatedAt":"2026-10-27T09:30:00.000Z","revision":1,
             "isLegacy":false,"readOnlyReason":null}]}
            """.trimIndent()
        )?.tasks?.single() ?: error("task dropped")
}
