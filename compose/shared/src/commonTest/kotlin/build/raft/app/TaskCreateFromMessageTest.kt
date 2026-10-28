package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.TaskCreateDraft
import build.raft.app.data.buildSharedTasksCreateBody
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task v2 "Create Task from Message" (slock staging 3e8ad99e web `CreateTaskDialog` + taskStore
 * `createTasks`; server `routes/tasks.ts` POST /tasks/channel/:channelId): one item per non-blank
 * title, each `{ title, sourceMessageId }`; without a source the item is `{ title }`.
 */
class TaskCreateFromMessageTest {
    @Test
    fun createBodyNamesTheSourceMessageOnlyWhenThereIsOne() {
        // (draft) -> (title, sourceMessageId or absent)
        listOf(
            TaskCreateDraft(title = " Ship it ", sourceMessageId = MessageId("msg-1")) to ("Ship it" to "msg-1"),
            TaskCreateDraft(title = "Plain") to ("Plain" to null),
        ).forEach { (draft, expected) ->
            val item = Json.parseToJsonElement(buildSharedTasksCreateBody(listOf(draft))).jsonObject["tasks"]!!.jsonArray.single().jsonObject
            assertEquals(expected.first, item["title"]?.jsonPrimitive?.content)
            assertEquals(expected.second, item["sourceMessageId"]?.jsonPrimitive?.content)
            assertEquals(expected.second != null, "sourceMessageId" in item, "an absent source is not sent as null")
        }
    }

    @Test
    fun createFromMessagePostsEveryNonBlankTitleUnderTheMessageAndReadsTheCreatedTasks() = runTest {
        val api = RecordingTasksApi()
        val result =
            SharedTasksDataSource(api, diagnosticsLogger = {}).createTasksFromMessage(
                serverId = ServerId("server-1"),
                channelId = ChannelId("channel-1"),
                titles = listOf("First", "  ", "Second "),
                sourceMessageId = MessageId("msg-1"),
            )
        val (path, body) = api.posts.single()
        assertEquals("server-1:channel-1", path)
        val items = Json.parseToJsonElement(body).jsonObject["tasks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("First", "Second"), items.map { it["title"]?.jsonPrimitive?.content })
        assertTrue(items.all { it["sourceMessageId"]?.jsonPrimitive?.content == "msg-1" })
        val created = assertIs<DataResult.Content<build.raft.app.data.TaskSnapshot>>(result).data.tasks.single()
        assertEquals(MessageId("msg-1"), created.sourceMessageId)

        // Only blank titles: nothing is sent.
        val blank = RecordingTasksApi()
        val none = SharedTasksDataSource(blank, diagnosticsLogger = {}).createTasksFromMessage(ServerId("server-1"), ChannelId("channel-1"), listOf(" "), MessageId("msg-1"))
        assertIs<DataResult.Empty>(none)
        assertTrue(blank.posts.isEmpty())
    }

    @Test
    fun firstTitleIsTheMessageOnOneLineCutAt200() {
        assertEquals("Ship the preview build today", taskTitleFromMessage("  Ship the preview\n\nbuild   today "))
        val long = taskTitleFromMessage("x".repeat(250))
        assertEquals(200, long.length)
        assertTrue(long.endsWith("…"))
        assertFalse(taskTitleFromMessage("y".repeat(200)).endsWith("…"))
    }

    private class RecordingTasksApi : RaftTasksApi {
        val posts = mutableListOf<Pair<String, String>>()

        override suspend fun serverTasks(serverId: ServerId, priority: build.raft.app.data.DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun createTasks(
            serverId: ServerId,
            channelId: ChannelId,
            drafts: List<TaskCreateDraft>,
            priority: build.raft.app.data.DataRequestPriority,
        ): HomeNetworkResponse {
            posts += "${serverId.value}:${channelId.value}" to buildSharedTasksCreateBody(drafts)
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {"tasks":[{"id":"task-1","messageId":"task-1","hostMessageId":null,"sourceMessageId":"msg-1",
                     "conversationReplyCount":0,"conversationChannelId":null,"conversationAnchored":true,
                     "channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":12,
                     "title":"First","description":null,"status":"todo","createdByType":"user","createdById":"user-1",
                     "createdByName":"artin","claimedByType":null,"claimedById":null,"claimedByName":null,
                     "claimedAt":null,"completedAt":null,"createdAt":"2026-10-28T09:00:00.000Z",
                     "updatedAt":"2026-10-28T09:00:00.000Z","revision":1,"isLegacy":false}]}
                    """.trimIndent(),
            )
        }
    }
}
