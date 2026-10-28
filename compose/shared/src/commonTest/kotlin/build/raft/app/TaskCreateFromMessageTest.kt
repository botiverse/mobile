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
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.TaskSnapshot
import kotlinx.coroutines.async
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
        val created = assertIs<DataResult.Content<TaskSnapshot>>(result).data.tasks.single()
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

    @Test
    fun createStepWritesTheTasksIntoTheStoreOrKeepsTheServersWordsAndCannotBeDismissedMeanwhile() = runTest {
        val source = TaskCreateSourceMessage(ChannelId("channel-1"), MessageId("msg-1"), "artin", "Ship it")
        // (case, answer) -> (closed, error shown, tasks in the store)
        data class Case(val name: String, val answer: HomeNetworkResponse, val closed: Boolean, val error: String?, val stored: Int)
        listOf(
            Case("created", HomeNetworkResponse(200, CreatedBody), closed = true, error = null, stored = 1),
            Case("over 50", HomeNetworkResponse(400, """{"error":"Cannot create more than 50 tasks at once"}"""), false, "Cannot create more than 50 tasks at once", 0),
            Case(
                "task v2 off",
                HomeNetworkResponse(403, """{"error":"Task conversations are not enabled on this server","code":"task_v2_not_enabled"}"""),
                false,
                "Task conversations are not enabled on this server",
                0,
            ),
            Case("no words", HomeNetworkResponse(500, null), false, "Failed to create task", 0),
        ).forEach { case ->
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val api = RecordingTasksApi(answer = case.answer, gate = gate)
            val store = SharedTasksDataStore()
            val scope = SharedTasksScope.Channel(ChannelId("channel-1"))
            store.loadSnapshot("server-1", scope, forceRefresh = true) {
                DataResult.Content(TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap()))
            }
            val flow = TaskCreateFromMessageFlow()
            flow.open(source)
            val running =
                async {
                    flow.submit(listOf("Ship it"), "server-1", SharedTasksDataSource(api, diagnosticsLogger = {}), store, "Failed to create task")
                }
            testScheduler.runCurrent()
            assertTrue(flow.submitting, case.name)
            assertFalse(flow.dismiss(), "nothing closes it while the create runs: ${case.name}")
            assertEquals(source, flow.source, case.name)
            gate.complete(Unit)
            running.await()
            assertFalse(flow.submitting, case.name)
            assertEquals(case.closed, flow.source == null, case.name)
            assertEquals(case.error, flow.errorText, case.name)
            assertEquals(case.stored, store.projection("server-1", scope).tasks.count { it.sourceMessageId == MessageId("msg-1") }, case.name)
            if (!case.closed) assertTrue(flow.dismiss(), "closes once it is done: ${case.name}")
        }
    }

    private class RecordingTasksApi(
        private val answer: HomeNetworkResponse? = null,
        private val gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : RaftTasksApi {
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
            gate?.await()
            answer?.let { return it }
            return HomeNetworkResponse(
                code = 200,
                bodyText = CreatedBody,
            )
        }
    }

    private companion object {
        val CreatedBody =
            """
            {"tasks":[{"id":"task-1","messageId":"task-1","hostMessageId":null,"sourceMessageId":"msg-1",
             "conversationReplyCount":0,"conversationChannelId":null,"conversationAnchored":true,
             "channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":12,
             "title":"First","description":null,"status":"todo","createdByType":"user","createdById":"user-1",
             "createdByName":"artin","claimedByType":null,"claimedById":null,"claimedByName":null,
             "claimedAt":null,"completedAt":null,"createdAt":"2026-10-28T09:00:00.000Z",
             "updatedAt":"2026-10-28T09:00:00.000Z","revision":1,"isLegacy":false}]}
            """.trimIndent()
    }
}
