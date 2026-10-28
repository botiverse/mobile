package build.raft.app

import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskStatusOption
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One task page (artin, 1.17.0 b3: "status can't be changed" on a task opened from its chip): a
 * task without a conversation yet opens as the conversation page it becomes, with the same head,
 * the same Status and Assignee editors under the same gates, writing through the same intents.
 */
class TaskPendingPageTest {
    private val members =
        listOf(
            MemberProfile(id = MemberId("user-1"), displayName = "Artin", handle = "artin", kind = MemberKind.Human),
            MemberProfile(id = MemberId("agent-1"), displayName = "Lagoon", handle = "lagoon", kind = MemberKind.Agent),
        )

    @Test
    fun `a member gets the conversation head editors on a task without a conversation`() {
        val pending = pendingTask()
        val model = pending.taskConversationHeadModel(pending.taskPendingHeaderState(), TaskConversationDetailsState(), "member", false, members, null)
        assertTrue(model.bar.statusEditable)
        assertTrue(model.bar.assigneeEditable)
        assertEquals(
            listOf(TaskStatus.InProgress, TaskStatus.InReview, TaskStatus.Done, TaskStatus.Closed),
            model.bar.statusOptions.map(TaskStatusOption::status),
        )
        // The same bar the conversation page shows for this task once its conversation exists.
        val conversation = pending.copy(conversationChannelId = ChannelId("conv-1"), conversationAnchored = true)
        val conversationBar =
            conversation.taskConversationHeadModel(TaskConversationHeaderState(ChannelId("conv-1")), TaskConversationDetailsState(), "member", false, members, null).bar
        assertEquals(conversationBar, model.bar)
        // An owner may force any status, as there.
        val owner = pending.taskConversationHeadModel(pending.taskPendingHeaderState(), TaskConversationDetailsState(), "owner", false, members, null)
        assertEquals(TaskStatus.entries.toList(), owner.bar.statusOptions.map(TaskStatusOption::status))
    }

    @Test
    fun `a guest a read-only task and the offline stand-in read as plain facts`() {
        val pending = pendingTask()
        fun bar(task: TaskSummary, role: String?, editable: Boolean = true) =
            task.taskConversationHeadModel(task.taskPendingHeaderState(), TaskConversationDetailsState(), role, false, members, null, editable).bar
        listOf(
            "guest" to bar(pending, "guest"),
            "read-only task" to bar(pending.copy(readOnlyReason = "historical_joint"), "member"),
            "offline stand-in" to bar(pending, "member", editable = false),
        ).forEach { (case, model) ->
            assertFalse(model.statusEditable, case)
            assertEquals(emptyList(), model.statusOptions, case)
            assertFalse(model.assigneeEditable, case)
            assertEquals(TaskStatus.InProgress, model.status, case)
            assertEquals("Lagoon", model.assigneeName, case)
        }
        // Done freezes the assignee (the server's rule) but its status can still move.
        val done = bar(pending.copy(status = TaskStatus.Done), "member")
        assertTrue(done.statusEditable)
        assertFalse(done.assigneeEditable)
    }

    @Test
    fun `status and assignee changes take the conversation head intents`() {
        val pending = pendingTask()
        val sent = mutableListOf<SharedTasksRouteIntent>()
        val edits = TaskHeadEdits { sent += it }
        edits.updateStatus(pending, TaskStatus.InReview)
        edits.assign(pending, members[0])
        edits.assign(pending, null)
        assertEquals(
            listOf(
                SharedTasksRouteIntent.UpdateStatus(pending, TaskStatus.InReview),
                SharedTasksRouteIntent.AssignTask(pending, members[0]),
                SharedTasksRouteIntent.AssignTask(pending, null),
            ),
            sent,
        )
    }

    @Test
    fun `the pending page shows the edited task and keeps it through an older copy`() {
        val pending = pendingTask()
        val flow = TaskOpenSurfaceFlow(newRandomId = { "rid" })
        flow.open(TaskOpenSurface.Pending(pending))
        // The channel store's optimistic copy: the page shows it at once.
        val optimistic = pending.copy(status = TaskStatus.InReview)
        assertEquals(null, flow.onTaskChanged(optimistic, taskV2Enabled = true))
        assertEquals(TaskStatus.InReview, flow.surface?.task?.status)
        // A refused write rolls the store back to the copy before it.
        assertEquals(null, flow.onTaskChanged(pending, taskV2Enabled = true))
        assertEquals(pending, flow.surface?.task)
        // Accepted this time: the server's copy (a newer revision) replaces the optimistic one ...
        flow.onTaskChanged(optimistic, taskV2Enabled = true)
        val confirmed = optimistic.copy(revision = 2L)
        flow.onTaskChanged(confirmed, taskV2Enabled = true)
        assertEquals(confirmed, flow.surface?.task)
        // ... and another store's older copy does not undo it.
        flow.onTaskChanged(pending, taskV2Enabled = true)
        assertEquals(confirmed, flow.surface?.task)
    }

    private fun pendingTask(): TaskSummary =
        parseSharedServerTasksPayload(
            """
            {"tasks":[{"id":"task-1","messageId":"msg-host","hostMessageId":"msg-host","sourceMessageId":"msg-host","conversationReplyCount":0,
             "conversationChannelId":null,"conversationAnchored":false,"channelId":"channel-1","channelName":"general",
             "channelType":"channel","taskNumber":7,"title":"Ship the bar","description":"Wire the bar","status":"in_progress",
             "createdByType":"user","createdById":"user-1","createdByName":"Artin","claimedByType":"agent","claimedById":"agent-1",
             "claimedByName":"Lagoon","createdAt":"2026-10-27T09:00:00.000Z","updatedAt":"2026-10-27T09:30:00.000Z","revision":1,
             "isLegacy":false,"readOnlyReason":null}]}
            """.trimIndent()
        )?.tasks?.single() ?: error("task dropped")
}
