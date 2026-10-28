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
                    randomId = "rid-1",
                    send = { channelId, body, _ ->
                        calls += "send:$channelId:$body"
                        case.send
                    },
                )
            assertEquals(case.expected, result, case.name)
            assertEquals(case.calls, calls, case.name)
        }
    }

    @Test
    fun aRetryAfterALostAnswerReusesTheConversationAndTheRandomIdSoTheServerDedupes() = runTest {
        // Tenny: POST /conversation succeeded, then the send failed — or timed out although the
        // server took it. The retry POSTs again (the server answers the same conversation) and
        // sends with the SAME randomId, so the server dedupes it: one message, not two.
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val calls = mutableListOf<String>()
        val api = ConversationApi(HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}"""), calls)
        val opened = mutableListOf<ChannelId>()
        val sends = mutableListOf<Pair<String, String>>()
        var attempt = 0
        var ids = 0
        val flow = TaskOpenSurfaceFlow(newRandomId = { "rid-${++ids}" })
        flow.open(TaskOpenSurface.Pending(preV2))
        suspend fun send() =
            flow.send(
                text = "On it",
                serverId = "server-1",
                tasksDataSource = SharedTasksDataSource(api, diagnosticsLogger = {}),
                sendMessage = { channelId, _, randomId ->
                    sends += channelId to randomId
                    attempt += 1
                    // First attempt: the server took it, but the answer was lost (a timeout).
                    if (attempt == 1) ThreadApiResult.Failure(code = 504) else ThreadApiResult.Success(Unit)
                },
                currentServerId = { "server-1" },
                fallbackError = "Couldn't send the message",
            )
        assertEquals(null, send())
        assertEquals("Couldn't send the message", flow.errorText)
        assertEquals("On it", flow.draft("t-1"), "the typed text stays after a failure")
        send()?.let { (_, conversation) -> opened += conversation }
        assertEquals(listOf("post:t-1", "post:t-1"), calls.filter { it.startsWith("post:") })
        assertEquals(listOf("conv-local" to "rid-1", "conv-local" to "rid-1"), sends, "same conversation, same randomId")
        assertEquals(listOf(ChannelId("conv-local")), opened)
        // The pending page stays (composer off) until the conversation page covers it.
        assertTrue(flow.handingOff)
        assertEquals(preV2, flow.surface?.task)
        // The conversation's own `task:updated` does not open it a second time.
        val anchored = preV2.copy(conversationChannelId = ChannelId("conv-local"), conversationAnchored = true)
        assertEquals(null, flow.onTaskChanged(anchored, taskV2Enabled = true), "the page it opens is already on its way")
        flow.finishHandOff()
        assertEquals(null, flow.surface, "it gives way to the conversation page")
        assertFalse(flow.handingOff)
        assertEquals("", flow.draft("t-1"))

        // The next draft gets a new id.
        flow.open(TaskOpenSurface.Pending(preV2))
        send()
        assertEquals("rid-2", sends.last().second)
    }

    @Test
    fun theOpenPageFollowsItsTaskAndGivesWayWhenItsConversationAppears() {
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val flow = TaskOpenSurfaceFlow(newRandomId = { "rid" })
        flow.open(TaskOpenSurface.Pending(preV2))
        flow.onDraftChange("t-1", "half a thought")
        // Another task's update, and the same copy: nothing changes.
        assertEquals(null, flow.onTaskChanged(preV2.copy(id = TaskId("t-2")), taskV2Enabled = true))
        assertEquals(null, flow.onTaskChanged(preV2, taskV2Enabled = true))
        // `task:updated` with a new status, still no conversation: the page shows the new copy.
        val reviewed = preV2.copy(status = TaskStatus.InReview, revision = 2L)
        assertEquals(null, flow.onTaskChanged(reviewed, taskV2Enabled = true))
        assertEquals(reviewed, flow.surface?.task)
        // A store still holding the older revision does not put the old status back.
        assertEquals(null, flow.onTaskChanged(preV2, taskV2Enabled = true))
        assertEquals(reviewed, flow.surface?.task)
        // Someone else wrote first: the conversation exists; the caller opens it.
        val anchored = reviewed.copy(conversationChannelId = ChannelId("conv-1"), conversationAnchored = true)
        assertEquals(anchored, flow.onTaskChanged(anchored, taskV2Enabled = true))
        assertEquals(null, flow.surface)
        assertEquals("half a thought", flow.draft("t-1"), "the draft is kept while the page lives")
    }

    @Test
    fun aSendThatFinishesOnAnotherServerOpensNothingAndClosingKeepsTheDraft() = runTest {
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val api = ConversationApi(HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}"""), mutableListOf())
        val flow = TaskOpenSurfaceFlow(newRandomId = { "rid" })
        flow.open(TaskOpenSurface.Pending(preV2))
        flow.onDraftChange("t-1", "draft")
        assertTrue(flow.dismiss())
        assertEquals("draft", flow.draft("t-1"))
        flow.open(TaskOpenSurface.Pending(preV2))
        var server = "server-1"
        val opened =
            flow.send(
                text = "On it",
                serverId = "server-1",
                tasksDataSource = SharedTasksDataSource(api, diagnosticsLogger = {}),
                sendMessage = { _, _, _ ->
                    server = "server-2"
                    ThreadApiResult.Success(Unit)
                },
                currentServerId = { server },
                fallbackError = "x",
            )
        assertEquals(null, opened, "web stops when the context changed meanwhile")
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
