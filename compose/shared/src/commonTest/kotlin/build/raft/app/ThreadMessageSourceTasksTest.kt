package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.SharedTaskFactFoldOutcome
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.TaskSnapshot
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task v2: the tasks listed under their source message (slock staging 2618da4f
 * `packages/web/src/components/message/ChatPanel.tsx` `tasksByMessageId` / `taskListsByMessageId`,
 * `ThreadPanel.tsx` `parentSourceTasks`, `components/task/TaskChipList.tsx`), with task DTOs shaped
 * as `packages/server/src/services/taskService.ts` projects them (`sourceMessageId` falls back to
 * the pre-v2 host message server-side) and `task:updated` as `services/taskRealtimeEvents.ts`
 * emits it (`{ channelId, task }`, the full task DTO).
 */
class ThreadMessageSourceTasksTest {
    @Test
    fun tasksHangUnderTheirSourceMessageAsWebDoesWithTheFlagOnAndOff() {
        val tasks =
            listOf(
                // Two v2 tasks from one message, out of number order.
                parsed(taskJson(id = "t-12", number = 12, source = "msg-a")),
                parsed(taskJson(id = "t-11", number = 11, source = "msg-a")),
                // A pre-v2 task: hosted by its message (the server's sourceMessageId is that host).
                parsed(taskJson(id = "t-5", number = 5, source = "msg-b", host = "msg-b", anchored = false, conversation = null)),
                // A v2 task with no source message (made in the channel's task list).
                parsed(taskJson(id = "t-9", number = 9, source = null)),
            )
        // (flag, expected lists)
        listOf(
            true to mapOf("msg-a" to listOf(11, 12), "msg-b" to listOf(5)),
            // Flag off: only a message with a v2 task under it keeps its list; the pre-v2 host keeps its chip.
            false to mapOf("msg-a" to listOf(11, 12)),
        ).forEach { (flag, expected) ->
            val lists = tasks.threadSourceTaskLists(taskV2Enabled = flag)
            assertEquals(expected, lists.mapValues { (_, list) -> list.map { it.taskNumber } }, "taskV2=$flag")
        }

        // The row lookup finds a listed task by id, and a message lists what the map holds.
        val projection = emptyThreadLinkedTasksProjection().copy(sourceTaskLists = tasks.threadSourceTaskLists(true))
        assertEquals("t-5", projection.sourceTaskById("t-5")?.id?.value)
        assertNull(projection.sourceTaskById("t-9"))
        assertEquals(listOf("t-11", "t-12"), projection.sourceTasksFor(message("msg-a")).map { it.taskId })
        assertTrue(projection.sourceTasksFor(message("msg-c")).isEmpty())
    }

    @Test
    fun aRowShowsNumberTitleRepliesAssigneeAndStatus() {
        // (task JSON, viewer, expected row)
        data class Case(val name: String, val json: String, val viewer: String?, val expected: ThreadMessageSourceTaskItem)
        val cases =
            listOf(
                Case(
                    "agent assignee, own conversation with replies",
                    taskJson(id = "t-11", number = 11, source = "msg-a", replies = 8, claimedType = "agent", claimedId = "agent-1", claimedName = "Codex-Android-DevOPS", status = "in_review"),
                    viewer = "user-1",
                    ThreadMessageSourceTaskItem("t-11", 11, "Task 11", TaskStatus.InReview, 8, "agent-1", MemberKind.Agent, "Codex-Android-DevOPS", false),
                ),
                Case(
                    "the reader is the assignee",
                    taskJson(id = "t-12", number = 12, source = "msg-a", claimedType = "user", claimedId = "user-1", claimedName = "artin"),
                    viewer = "user-1",
                    ThreadMessageSourceTaskItem("t-12", 12, "Task 12", TaskStatus.InProgress, 0, "user-1", MemberKind.Human, "artin", true),
                ),
                Case(
                    "unassigned pre-v2 task: no own conversation, so no count",
                    taskJson(id = "t-5", number = 5, source = "msg-b", host = "msg-b", anchored = false, conversation = null, replies = 3, status = "todo"),
                    viewer = "user-1",
                    ThreadMessageSourceTaskItem("t-5", 5, "Task 5", TaskStatus.Todo, 0, null, null, null, false),
                ),
            )
        cases.forEach { case ->
            assertEquals(case.expected, parsed(case.json).toThreadMessageSourceTaskItem(case.viewer), case.name)
        }
    }

    @Test
    fun taskUpdatedFramesMoveTheRowsStatusAndAssigneeLive() = runTest {
        val store = SharedTasksDataStore()
        val scope = SharedTasksScope.Channel(ChannelId("channel-1"))
        store.loadSnapshot("server-1", scope, forceRefresh = true) {
            DataResult.Content(TaskSnapshot(tasks = listOf(parsed(taskJson(id = "t-11", number = 11, source = "msg-a"))), summaryCounts = emptyMap()))
        }
        fun row() =
            store.projection("server-1", scope).tasks.threadSourceTaskLists(taskV2Enabled = true)["msg-a"]
                ?.single()?.toThreadMessageSourceTaskItem(viewerUserId = "user-1")
        assertEquals(TaskStatus.InProgress to null, row()?.let { it.status to it.assigneeName })

        val mapper = RealtimeEventMapper()
        fun frame(json: String) = assertIs<TaskUpdated>(mapper.map(RealtimeSocketEvents.TaskUpdated, """{"channelId":"channel-1","task":$json}""")).task
        // Claimed and sent to review (the server's full DTO, which names the source message).
        val claimed = frame(taskJson(id = "t-11", number = 11, source = "msg-a", status = "in_review", claimedType = "agent", claimedId = "agent-1", claimedName = "Codex-Android-DevOPS", revision = 4))
        assertEquals(MessageId("msg-a"), claimed.sourceMessageId)
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTaskForCurrentServer("server-1", claimed))
        assertEquals(TaskStatus.InReview to "Codex-Android-DevOPS", row()?.let { it.status to it.assigneeName })

        // A copy without the field (an older frame) keeps the task under its message.
        val done = frame(taskJson(id = "t-11", number = 11, source = null, status = "done", revision = 5))
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTaskForCurrentServer("server-1", done))
        assertEquals(TaskStatus.Done to null, row()?.let { it.status to it.assigneeName })
    }

    @Test
    fun theTaskListSitsAboveTheFooterChipsAndTheReplies() {
        assertEquals(
            listOf(ThreadMessageTrailingSection.SourceTasks, ThreadMessageTrailingSection.MetaChips, ThreadMessageTrailingSection.InlineReplies),
            threadMessageTrailingSectionOrder(hasMetaChips = true, hasInlineReplies = true, hasSourceTasks = true),
        )
        assertEquals(
            listOf(ThreadMessageTrailingSection.MetaChips),
            threadMessageTrailingSectionOrder(hasMetaChips = true, hasInlineReplies = false),
        )
    }

    private fun parsed(json: String): TaskSummary =
        parseSharedServerTasksPayload("""{"tasks":[$json]}""")?.tasks?.single() ?: error("task dropped: $json")

    private fun taskJson(
        id: String,
        number: Int,
        source: String?,
        host: String? = null,
        anchored: Boolean = true,
        conversation: String? = "conv-$id",
        replies: Int = 0,
        status: String = "in_progress",
        claimedType: String? = null,
        claimedId: String? = null,
        claimedName: String? = null,
        revision: Long = 3,
    ): String =
        """
        {"id":"$id","messageId":"${host ?: id}","hostMessageId":${host.json()},"sourceMessageId":${source.json()},
         "conversationReplyCount":$replies,"conversationChannelId":${conversation.json()},"conversationAnchored":$anchored,
         "channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":$number,
         "title":"Task $number","description":null,"status":"$status",
         "createdByType":"user","createdById":"user-1","createdByName":"artin","createdByHandle":"artin",
         "createdByMembershipStatus":"active","claimedByType":${claimedType.json()},"claimedById":${claimedId.json()},
         "claimedByName":${claimedName.json()},"claimedAt":null,"completedAt":null,
         "createdAt":"2026-10-28T09:00:00.000Z","updatedAt":"2026-10-28T09:30:00.000Z","revision":$revision,"isLegacy":false}
        """.trimIndent()

    private fun message(id: String) = RaftThreadMessageItem(id = id, authorId = "user-1", authorName = "artin")

    private fun String?.json(): String = this?.let { "\"$it\"" } ?: "null"
}
