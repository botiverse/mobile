package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.TaskId
import build.raft.app.taskCardReplyCount
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task v2 reply counts on task cards (web TaskChipList `ReplyCount`, taskStore
 * `bumpTaskConversationReplyCount`, socketBridge `applyTaskConversationReply`): the task DTO's
 * `conversationReplyCount`, moved by `message:new` frames of the task's conversation (their
 * `conversationContext` names the task; slock staging 0000139628e0 `services/messageService.ts`
 * `buildFrontendConversationContext`).
 */
class TaskConversationReplyCountTest {
    @Test
    fun cardShowsTheCountOnlyForATaskWithItsOwnConversation() {
        val task = parsedTask()
        // (conversation, count, shown)
        listOf(
            Triple("conv-1", 4, 4),
            Triple("conv-1", 0, null),
            Triple(null, 4, null),
        ).forEach { (conversation, count, shown) ->
            val variant = task.copy(conversationChannelId = conversation?.let(::ChannelId), conversationReplyCount = count)
            assertEquals(shown, variant.taskCardReplyCount(), "conversation=$conversation count=$count")
        }
    }

    @Test
    fun taskConversationMessageFrameNamesItsTask() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.MessageNew,
                """
                {"id":"reply-5","seq":9,"channelId":"conv-1","senderType":"agent","senderId":"agent-1","senderName":"lagoon",
                 "messageType":"chat","content":"done","createdAt":"2026-10-27T10:00:00.000Z",
                 "conversationContext":{"channelType":"task","taskId":"task-1","parentChannelId":"channel-1","parentChannelType":"channel"}}
                """.trimIndent(),
            )
        val context = assertIs<MessageNew>(event).message.conversationContext
        assertEquals(ConversationKind.Task, context?.channelKind)
        assertEquals(TaskId("task-1"), context?.taskId)
        assertEquals(ChannelId("channel-1"), context?.parentChannelId)
    }

    @Test
    fun aReplyBumpsTheLoadedTaskOnceAcrossItsChannelProjection() = runTest {
        val store = SharedTasksDataStore()
        val scope = SharedTasksScope.Channel(ChannelId("channel-1"))
        store.loadSnapshot("server-1", scope, forceRefresh = true) {
            DataResult.Content(TaskSnapshot(tasks = listOf(parsedTask()), summaryCounts = emptyMap()))
        }
        fun count() = store.projection("server-1", scope).tasks.single().conversationReplyCount

        assertEquals(4, count())
        assertTrue(store.bumpTaskConversationReplyCount("server-1", TaskId("task-1"), MessageId("reply-5")))
        assertEquals(5, count())
        // The same frame from the reader's own room counts once.
        assertFalse(store.bumpTaskConversationReplyCount("server-1", TaskId("task-1"), MessageId("reply-5")))
        assertTrue(store.bumpTaskConversationReplyCount("server-1", TaskId("task-1"), MessageId("reply-6")))
        assertEquals(6, count())
        // A task not loaded here is not invented.
        assertFalse(store.bumpTaskConversationReplyCount("server-1", TaskId("task-9"), MessageId("reply-7")))
        assertNull(store.projection("server-1", scope).tasks.firstOrNull { it.id == TaskId("task-9") })
    }

    private fun parsedTask() =
        parseSharedServerTasksPayload(
            """
            {"tasks":[{"id":"task-1","messageId":"task-1","hostMessageId":null,"sourceMessageId":null,"conversationReplyCount":4,
             "conversationChannelId":"conv-1","conversationAnchored":true,"channelId":"channel-1","channelName":"general",
             "channelType":"channel","taskNumber":7,"title":"Ship the bar","description":null,"status":"in_progress",
             "createdByType":"user","createdById":"user-1","createdByName":"Artin","createdByHandle":"artin",
             "createdByMembershipStatus":"active","claimedByType":null,"claimedById":null,"claimedByName":null,
             "claimedAt":null,"completedAt":null,"requiresResourceReceipt":false,"resourceReceipt":null,
             "resourceReceiptRecordedAt":null,"resourceTeardownOwnerAgentId":null,"resourceExpiryFollowupId":null,
             "createdAt":"2026-10-27T09:00:00.000Z","updatedAt":"2026-10-27T09:30:00.000Z","revision":3,"isLegacy":false}]}
            """.trimIndent()
        )?.tasks?.single() ?: error("task dropped")
}
