package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where tapping a listed task goes, and the first send of a task without a conversation (slock
 * staging 3f25e17e web `useOpenTask.ts`, `TaskConversationDialog.tsx` "opening does not create",
 * `threadStore.ensureTaskModalThreadChannel`).
 */
class TaskOpenSurfacesTest {
    @Test
    fun eachKindOfListedTaskOpensWhereWebOpensIt() {
        // Task DTOs as the server projects them (taskService.ts: isLegacy, hostMessageId, conversation).
        val legacy = task("""{"messageId":"t-1","hostMessageId":null,"conversationChannelId":null,"conversationAnchored":false,"isLegacy":true}""")
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val v2 = task("""{"messageId":"t-1","hostMessageId":null,"conversationChannelId":"conv-1","conversationAnchored":true,"isLegacy":false}""")
        val v2Lazy = task("""{"messageId":"t-1","hostMessageId":null,"conversationChannelId":null,"conversationAnchored":true,"isLegacy":false}""")
        // (case, task, flag) -> (surface kind or null, page target)
        data class Case(val name: String, val task: TaskSummary, val taskV2: Boolean, val surface: String?, val target: TaskOpenTarget)
        listOf(
            Case("legacy", legacy, true, "Legacy", TaskOpenTarget.LegacyPanel),
            Case("legacy, flag off", legacy, false, "Legacy", TaskOpenTarget.LegacyPanel),
            Case("pre-v2, flag off", preV2, false, null, TaskOpenTarget.HostThread),
            Case("pre-v2, flag on", preV2, true, "Pending", TaskOpenTarget.PendingConversation),
            Case("v2", v2, true, null, TaskOpenTarget.Conversation(ChannelId("conv-1"))),
            Case("v2 without a conversation yet", v2Lazy, false, "Pending", TaskOpenTarget.PendingConversation),
        ).forEach { case ->
            assertEquals(case.target, case.task.taskOpenTarget(case.taskV2), case.name)
            assertEquals(case.surface, case.task.taskOpenSurface(case.taskV2)?.let { it::class.simpleName }, case.name)
        }
    }

    @Test
    fun firstSendCreatesTheConversationThenSendsIntoItAndFailuresKeepTheServersWords() = runTest {
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        // (case, POST answer, send answer) -> (result, calls)
        data class Case(val name: String, val post: HomeNetworkResponse, val send: ThreadApiResult<Unit>, val expected: TaskConversationStart, val calls: List<String>)
        val created = HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}""")
        listOf(
            Case("ok", created, ThreadApiResult.Success(Unit), TaskConversationStart.Started(ChannelId("conv-local")), listOf("post:t-1", "send:conv-local:hello")),
            Case(
                "archived channel",
                HomeNetworkResponse(409, """{"error":"Channel is archived"}"""),
                ThreadApiResult.Success(Unit),
                TaskConversationStart.Failed("Channel is archived"),
                listOf("post:t-1"),
            ),
            Case(
                "not a member",
                HomeNetworkResponse(403, """{"error":"You must join this channel to reply in this task"}"""),
                ThreadApiResult.Success(Unit),
                TaskConversationStart.Failed("You must join this channel to reply in this task"),
                listOf("post:t-1"),
            ),
            Case(
                "send refused",
                created,
                ThreadApiResult.Failure(code = 400, errorBodyText = """{"error":"Message too long"}"""),
                TaskConversationStart.Failed("Message too long"),
                listOf("post:t-1", "send:conv-local:hello"),
            ),
        ).forEach { case ->
            val calls = mutableListOf<String>()
            val api = ConversationApi(case.post, calls)
            val result =
                startTaskConversation(
                    task = preV2,
                    serverId = ServerId("server-1"),
                    body = "  hello ",
                    tasksDataSource = SharedTasksDataSource(api, diagnosticsLogger = {}),
                    send = { channelId, body ->
                        calls += "send:$channelId:$body"
                        case.send
                    },
                )
            assertEquals(case.expected, result, case.name)
            assertEquals(case.calls, calls, case.name)
        }
    }

    @Test
    fun aDoubleTapOpensOnce() {
        var now = 0L
        val gate = TaskOpenGate(repeatWindowMs = 700L, nowMs = { now })
        assertTrue(gate.tryBegin("t-1"))
        assertFalse(gate.tryBegin("t-1"), "second tap while the first resolves")
        assertFalse(gate.tryBegin("t-2"), "another task while one opens")
        gate.finish("t-1")
        now = 200L
        assertFalse(gate.tryBegin("t-1"), "repeat tap right after the page opened")
        assertTrue(gate.tryBegin("t-2"))
        gate.finish("t-2")
        now = 2_000L
        assertTrue(gate.tryBegin("t-1"), "a later open goes through")
    }

    private fun task(fields: String): TaskSummary {
        val base =
            """{"id":"t-1","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,
               "title":"Ship it","status":"todo","sourceMessageId":"msg-host","conversationReplyCount":0,
               "createdByType":"user","createdById":"user-1","createdByName":"artin","revision":1,"""
        val json = base + fields.trim().removePrefix("{")
        return parseSharedServerTasksPayload("""{"tasks":[$json]}""")?.tasks?.single() ?: error("task dropped: $json")
    }

    private class ConversationApi(private val post: HomeNetworkResponse, private val calls: MutableList<String>) : RaftTasksApi {
        override suspend fun serverTasks(serverId: ServerId, priority: build.raft.app.data.DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun openTaskConversation(serverId: ServerId, taskId: TaskId): HomeNetworkResponse {
            calls += "post:${taskId.value}"
            return post
        }
    }
}
