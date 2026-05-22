package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TasksDataSourceTest {
    @Test
    fun typedTasksApiUsesCanonicalTaskRoutesAndBodies() = runBlocking {
        val transport = RecordingTasksApi()
        val client = SlockTasksApiClient(transport)

        val serverTasks = client.serverTasks(ServerId("server-1"), DataRequestPriority.Visible)
        val channelTasks = client.channelTasks(ChannelId("channel-1"), DataRequestPriority.Visible)
        val byNumber = client.taskByNumber(ChannelId("channel-1"), 42, DataRequestPriority.Visible)
        val created = client.createTasks(ChannelId("channel-1"), listOf(TaskCreateDraft("Ship KMP")), DataRequestPriority.Visible)
        val claimed = client.claimTask(TaskId("task-1"))
        val converted = client.convertMessage(MessageId("message-1"))
        val unclaimed = client.unclaimTask(TaskId("task-1"))
        val status = client.updateTaskStatus(TaskId("task-1"), TaskStatus.InReview)
        val deleted = client.deleteTask(TaskId("task-1"))

        assertIs<DataResult.Content<TaskSnapshot>>(serverTasks)
        assertIs<DataResult.Content<TaskSnapshot>>(channelTasks)
        assertIs<DataResult.Content<TaskActionResult>>(byNumber)
        assertIs<DataResult.Content<TaskSnapshot>>(created)
        assertIs<DataResult.Content<TaskActionResult>>(claimed)
        assertIs<DataResult.Content<TaskActionResult>>(converted)
        assertIs<DataResult.Content<TaskActionResult>>(unclaimed)
        assertIs<DataResult.Content<TaskActionResult>>(status)
        assertIs<DataResult.Content<Unit>>(deleted)
        assertEquals(
            listOf(
                "GET tasks/server server=server-1",
                "GET tasks/channel/channel-1",
                "GET tasks/channel/channel-1/number/42",
                """POST tasks/channel/channel-1 body={"tasks":[{"title":"Ship KMP"}]}""",
                "PATCH tasks/task-1/claim",
                """POST tasks/convert-message body={"messageId":"message-1"}""",
                "PATCH tasks/task-1/unclaim",
                """PATCH tasks/task-1/status body={"status":"in_review"}""",
                "DELETE tasks/task-1"
            ),
            transport.calls
        )
    }

    @Test
    fun typedTasksApiRejectsGuessedCreateWrappers() = runBlocking {
        val client = SlockTasksApiClient(
            RecordingTasksApi(createBody = """{"data":[{"id":"task-1","title":"Task #1: guessed"}]}""")
        )

        val result = client.createTasks(ChannelId("channel-1"), listOf(TaskCreateDraft("Ship KMP")), DataRequestPriority.Visible)

        val error = assertIs<DataResult.Error>(result)
        assertEquals("tasks_create_contract_mismatch", error.error.code)
    }

    @Test
    fun typedTasksApiRequiresDeleteOkTrue() = runBlocking {
        val client = SlockTasksApiClient(RecordingTasksApi(deleteBody = """{"success":true}"""))

        val result = client.deleteTask(TaskId("task-1"))

        val error = assertIs<DataResult.Error>(result)
        assertEquals("tasks_delete_contract_mismatch", error.error.code)
    }

    private class RecordingTasksApi(
        private val listBody: String = """{"tasks":[{"id":"task-1","title":"Task #1: canonical"}]}""",
        private val actionBody: String = """{"task":{"id":"task-1","title":"Task #1: canonical"}}""",
        private val createBody: String = """{"tasks":[{"id":"task-2","title":"Task #2: created"}]}""",
        private val deleteBody: String = """{"ok":true}"""
    ) : SlockTasksApi {
        val calls = mutableListOf<String>()

        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            calls += "GET tasks/server server=${serverId.value}"
            return ok(listBody)
        }

        override suspend fun channelTasks(channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse {
            calls += "GET tasks/channel/${channelId.value}"
            return ok(listBody)
        }

        override suspend fun taskByNumber(
            channelId: ChannelId,
            taskNumber: Int,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            calls += "GET tasks/channel/${channelId.value}/number/$taskNumber"
            return ok(actionBody)
        }

        override suspend fun createTasks(
            channelId: ChannelId,
            drafts: List<TaskCreateDraft>,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            calls += "POST tasks/channel/${channelId.value} body=${buildSharedTasksCreateBody(drafts)}"
            return ok(createBody)
        }

        override suspend fun claimTask(taskId: TaskId): HomeNetworkResponse {
            calls += "PATCH tasks/${taskId.value}/claim"
            return ok(actionBody)
        }

        override suspend fun convertMessage(messageId: MessageId): HomeNetworkResponse {
            calls += "POST tasks/convert-message body=${buildSharedTaskConvertBody(messageId)}"
            return ok(actionBody)
        }

        override suspend fun unclaimTask(taskId: TaskId): HomeNetworkResponse {
            calls += "PATCH tasks/${taskId.value}/unclaim"
            return ok(actionBody)
        }

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse {
            calls += "PATCH tasks/${taskId.value}/status body=${buildSharedTaskStatusBody(status)}"
            return ok(actionBody)
        }

        override suspend fun deleteTask(taskId: TaskId): HomeNetworkResponse {
            calls += "DELETE tasks/${taskId.value}"
            return ok(deleteBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }
}
