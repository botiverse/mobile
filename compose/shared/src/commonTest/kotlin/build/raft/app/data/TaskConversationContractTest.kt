package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task v2 conversations: the wire contracts mobile reads, with fixtures shaped exactly as
 * botiverse/slock emits them @ 95703ec22e319f526dedf6c899751929b71062d7:
 * - task DTO: `packages/server/src/services/taskService.ts` task projection (`hostMessageId`,
 *   `conversationChannelId`, `conversationAnchored`, `conversationReplyCount`, `isLegacy`);
 * - `POST /api/tasks/:taskId/conversation`: `packages/server/src/routes/tasks.ts`;
 * - `GET /api/channels/:id` of a `type: "task"` channel: `routes/channels.ts` + `db/schema.ts` `channels`;
 * - Activity row `kind: "task"`: `services/channelService.ts` `TaskConversationInboxItem`.
 */
class TaskConversationContractTest {
    @Test
    fun taskDtoModesMapConversationFieldsAndNeverUseTheTaskIdAsAMessage() {
        // (case, task JSON, expected messageId, conversationChannelId, anchored, legacy)
        data class Case(
            val name: String,
            val json: String,
            val messageId: String?,
            val conversationChannelId: String?,
            val anchored: Boolean,
            val legacy: Boolean,
        )
        val cases =
            listOf(
                // A v2 task: `messageId` is the task id fallback, `hostMessageId` null.
                Case("v2 with conversation", taskJson(messageId = "task-1", hostMessageId = null, conversationChannelId = "conv-1", anchored = true), null, "conv-1", true, false),
                // A v2 task whose conversation is created on first use.
                Case("v2 lazy", taskJson(messageId = "task-1", hostMessageId = null, conversationChannelId = null, anchored = true), null, null, true, false),
                // A pre-v2 task: its discussion is the host message's thread.
                Case("pre-v2 host", taskJson(messageId = "msg-host", hostMessageId = "msg-host", conversationChannelId = null, anchored = false), "msg-host", null, false, false),
                // A pre-v2 task written to since v2 has both.
                Case("pre-v2 with conversation", taskJson(messageId = "msg-host", hostMessageId = "msg-host", conversationChannelId = "conv-2", anchored = true), "msg-host", "conv-2", true, false),
                // A pre-message orphan.
                Case("legacy", taskJson(messageId = "task-1", hostMessageId = null, conversationChannelId = null, anchored = false, isLegacy = true), "task-1", null, false, true),
            )
        cases.forEach { case ->
            val task = parseSharedServerTasksPayload("""{"tasks":[${case.json}]}""")?.tasks?.singleOrNull()
                ?: error("task dropped case=${case.name}")
            assertEquals(case.messageId, task.messageId?.value, case.name)
            assertEquals(case.messageId, task.parentMessageId?.value, case.name)
            assertEquals(case.conversationChannelId, task.conversationChannelId?.value, case.name)
            assertEquals(case.anchored, task.conversationAnchored, case.name)
            assertEquals(case.legacy, task.isLegacy, case.name)
            assertEquals("channel", task.channelType, case.name)
            assertEquals(3, task.conversationReplyCount, case.name)
        }
    }

    @Test
    fun openConversationResponseYieldsTheLocalConversationAndRejectsDriftedShapes() {
        val opened =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-canonical","created":true}""",
            ).toTaskConversationOpenResult()
        val result = assertIs<DataResult.Content<TaskConversationOpenResult>>(opened).data
        assertEquals(ChannelId("conv-local"), result.conversationChannelId)
        assertEquals(ChannelId("conv-canonical"), result.canonicalConversationChannelId)
        assertTrue(result.created)

        val notEnabled =
            HomeNetworkResponse(
                code = 409,
                bodyText = null,
                errorBodyText = """{"error":"Task conversations are not enabled on this server","code":"task_v2_not_enabled"}""",
            ).toTaskConversationOpenResult()
        assertEquals("tasks_conversation_409", assertIs<DataResult.Error>(notEnabled).error.code)

        val guessedAlias =
            HomeNetworkResponse(code = 200, bodyText = """{"channelId":"conv-local"}""").toTaskConversationOpenResult(diagnostics = {})
        assertEquals("tasks_conversation_contract_mismatch", assertIs<DataResult.Error>(guessedAlias).error.code)
    }

    @Test
    fun taskChannelDetailNamesItsParentAndOtherChannelsDoNot() {
        val ref = parseTaskConversationChannelDetail(taskChannelDetailJson())
        assertEquals(TaskConversationChannelRef(ChannelId("conv-1"), ChannelId("channel-1"), TaskId("task-1")), ref)
        assertNull(parseTaskConversationChannelDetail("""{"id":"channel-1","name":"general","type":"channel"}"""))
        assertNull(
            parseTaskConversationChannelDetail("""{"id":"conv-1","name":"task-x","type":"task","parentChannelId":null,"taskId":"task-1"}""")
        )
    }

    @Test
    fun taskConversationThreadLoadTakesTitleAndWriteAuthorityFromTheTaskAndItsChannel() = runTest {
        // (case, parent channel `joined`, expected joined / canSend)
        listOf(true to true, false to false).forEach { (parentJoined, expectedWrite) ->
            val network =
                FakeConversationNetworkClient(
                    responses =
                        mapOf(
                            "GET messages/channel/conv-1" to
                                """{"historyLimited":false,"messages":[{"id":"reply-1","seq":4,"channelId":"conv-1","senderType":"user","senderId":"user-1","senderName":"Artin","messageType":"chat","content":"on it","createdAt":"2026-10-27T10:00:00.000Z"}]}""",
                            "GET channels/conv-1" to taskChannelDetailJson(),
                            "GET channels/channel-1" to
                                """{"id":"channel-1","name":"general","type":"channel","joined":$parentJoined}""",
                            "GET tasks/channel/channel-1" to
                                """{"tasks":[${taskJson(messageId = "task-1", hostMessageId = null, conversationChannelId = "conv-1", anchored = true)}]}""",
                        ),
                )
            val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)

            val snapshot =
                assertIs<DataResult.Content<ThreadSnapshot>>(repository.getThread(ThreadId("conv-1"))).data
            val conversation = snapshot.conversation

            assertEquals(listOf("reply-1"), snapshot.messages.map { it.id.value }, "joined=$parentJoined")
            assertEquals(ConversationKind.Task, conversation.kind, "joined=$parentJoined")
            assertEquals("task", conversation.channelType, "joined=$parentJoined")
            assertEquals("#general:#7 Ship the task conversation", conversation.name, "joined=$parentJoined")
            assertEquals(expectedWrite, conversation.joined, "joined=$parentJoined")
            assertEquals(expectedWrite, conversation.permissions.canSend, "joined=$parentJoined")
            assertFalse(conversation.permissions.canCreateTask, "joined=$parentJoined")
        }
    }

    @Test
    fun overlappingLoadsOfOneTaskConversationShareItsParentAndTaskFetch() = runTest {
        // Hands 4cf4c781: two loads of the same conversation in flight at once (prewarm + open)
        // fetched the parent channel and the whole task list twice on a slow link.
        val taskListStarted = CompletableDeferred<Unit>()
        val releaseTaskList = CompletableDeferred<Unit>()
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/conv-1" to """{"historyLimited":false,"messages":[]}""",
                        "GET channels/conv-1" to taskChannelDetailJson(),
                        "GET channels/channel-1" to """{"id":"channel-1","name":"general","type":"channel","joined":true}""",
                        "GET tasks/channel/channel-1" to
                            """{"tasks":[${taskJson(messageId = "task-1", hostMessageId = null, conversationChannelId = "conv-1", anchored = true)}]}""",
                    ),
                requestStarted = mapOf("GET tasks/channel/channel-1" to taskListStarted),
                requestRelease = mapOf("GET tasks/channel/channel-1" to releaseTaskList),
            )
        val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)

        val loads = List(2) { async { repository.getThread(ThreadId("conv-1")) } }
        taskListStarted.await()
        runCurrent()
        releaseTaskList.complete(Unit)
        val conversations = loads.awaitAll().map { assertIs<DataResult.Content<ThreadSnapshot>>(it).data.conversation }

        conversations.forEach { conversation ->
            assertEquals("#general:#7 Ship the task conversation", conversation.name)
            assertTrue(conversation.permissions.canSend)
        }
        assertEquals(2, network.requests.count { it.path == "channels/conv-1" }, "each load reads its own detail")
        assertEquals(1, network.requests.count { it.path == "channels/channel-1" }, "parent detail shared")
        assertEquals(1, network.requests.count { it.path == "tasks/channel/channel-1" }, "task list shared")

        // Once the flight lands nothing is cached: a later load fetches again.
        repository.getThread(ThreadId("conv-1"))
        assertEquals(2, network.requests.count { it.path == "tasks/channel/channel-1" })
    }

    @Test
    fun taskConversationWithoutItsParentChannelFailsClosed() = runTest {
        val network =
            FakeConversationNetworkClient(
                responses =
                    mapOf(
                        "GET messages/channel/conv-1" to """{"historyLimited":false,"messages":[]}""",
                        "GET channels/conv-1" to taskChannelDetailJson(),
                    ),
                failures = mapOf("GET channels/channel-1" to "offline", "GET tasks/channel/channel-1" to "offline"),
            )
        val repository = RealConversationRepository(conversationRepositoryTestSessionStore(), network)

        val conversation =
            assertIs<DataResult.Content<ThreadSnapshot>>(repository.getThread(ThreadId("conv-1"))).data.conversation

        assertEquals(ConversationKind.Task, conversation.kind)
        assertFalse(conversation.joined)
        assertFalse(conversation.permissions.canSend)
    }

    @Test
    fun activityTaskRowFoldsIntoAThreadShapedRowTitledByTheTaskAddress() {
        val snapshot = parseSharedInboxSnapshot(inboxBody(taskInboxRowJson())) ?: error("inbox response dropped")
        val item = snapshot.items.singleOrNull() ?: error("task row dropped items=${snapshot.items}")

        assertEquals(InboxItemKind.Thread, item.kind)
        assertTrue(item.isTaskConversation)
        assertEquals("thread:conv-1", item.key)
        assertEquals(ChannelId("conv-1"), item.threadChannelId)
        assertEquals(ChannelId("channel-1"), item.parentChannelId)
        assertNull(item.parentMessageId)
        assertEquals("#general:#7 Ship the task conversation", item.parentMessagePreview)
        assertEquals(TaskId("task-1"), item.taskConversation?.taskId)
        assertEquals(MessageId("reply-2"), item.firstUnreadMessageId)
        assertEquals(2, item.unreadCount)
    }

    @Test
    fun activityRowKindsOtherThanTheContractStayDropped() {
        val snapshot = parseSharedInboxSnapshot(inboxBody(taskInboxRowJson().replace("\"kind\":\"task\"", "\"kind\":\"task_v3\"")))
        assertNotNull(snapshot)
        assertTrue(snapshot.items.isEmpty())
    }

    private fun taskJson(
        messageId: String,
        hostMessageId: String?,
        conversationChannelId: String?,
        anchored: Boolean,
        isLegacy: Boolean = false,
    ): String =
        """
        {"id":"task-1","messageId":"$messageId","hostMessageId":${hostMessageId.jsonString()},
         "sourceMessageId":${hostMessageId.jsonString()},"conversationReplyCount":3,
         "conversationChannelId":${conversationChannelId.jsonString()},"conversationAnchored":$anchored,
         "channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,
         "title":"Ship the task conversation","description":null,"status":"in_progress",
         "createdByType":"user","createdById":"user-1","createdByName":"Artin","createdByHandle":"artin",
         "createdByMembershipStatus":"active","claimedByType":null,"claimedById":null,"claimedByName":null,
         "claimedAt":null,"completedAt":null,"requiresResourceReceipt":false,"resourceReceipt":null,
         "resourceReceiptRecordedAt":null,"resourceTeardownOwnerAgentId":null,"resourceExpiryFollowupId":null,
         "createdAt":"2026-10-27T09:00:00.000Z","updatedAt":"2026-10-27T09:30:00.000Z","revision":2,
         "isLegacy":$isLegacy}
        """.trimIndent()

    private fun taskChannelDetailJson(): String =
        """{"id":"conv-1","serverId":"server-1","name":"task-task-1","description":null,"type":"task","guestVisible":false,"guestJoinable":false,"parentMessageId":null,"parentChannelId":"channel-1","taskId":"task-1","createdAt":"2026-10-27T09:00:00.000Z","archivedAt":null,"joined":false}"""

    private fun taskInboxRowJson(): String =
        """
        {"kind":"task","threadChannelId":"conv-1","parentMessageId":null,"parentChannelId":"channel-1",
         "parentChannelName":"general","parentChannelType":"channel","parentMessagePreview":"Ship the task conversation",
         "parentMessageSenderType":"","parentMessageSenderId":"","latestActivityPreview":"on it",
         "latestActivitySenderType":"user","latestActivitySenderId":"user-2","latestActivitySenderName":"Mira",
         "latestActivityMessageId":"reply-3","latestActivitySeq":"12","doneFrontierSeq":"12",
         "firstUnreadMessageId":"reply-2","firstMentionMessageId":null,"lastActivityAt":"2026-10-27T10:00:00.000Z",
         "lastReplyAt":"2026-10-27T10:00:00.000Z","replyCount":3,"unreadCount":2,"hasMention":false,
         "taskId":"task-1","taskNumber":7,"taskTitle":"Ship the task conversation","taskStatus":"in_progress",
         "taskClaimedByName":null,"isFollowing":true}
        """.trimIndent()

    private fun inboxBody(row: String): String = """{"items":[$row],"hasMore":false,"totalCount":1,"totalUnreadCount":2}"""

    private fun String?.jsonString(): String = this?.let { "\"$it\"" } ?: "null"
}
