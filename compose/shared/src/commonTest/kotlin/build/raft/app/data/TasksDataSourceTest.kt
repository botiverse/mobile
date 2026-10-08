package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertIs<DataResult.Content<TaskConvertResult>>(converted)
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
    fun networkTasksApiUsesSlockNetworkClientRoutesAndBodies() = runBlocking {
        val networkClient = RecordingTasksNetworkClient()
        val client = SlockTasksApiClient(NetworkSlockTasksApi(networkClient))

        assertIs<DataResult.Content<TaskSnapshot>>(
            client.serverTasks(ServerId("server-1"), DataRequestPriority.Visible)
        )
        assertIs<DataResult.Content<TaskSnapshot>>(
            client.channelTasks(ChannelId("channel-1"), DataRequestPriority.Visible)
        )
        assertIs<DataResult.Content<TaskActionResult>>(
            client.taskByNumber(ChannelId("channel-1"), 42, DataRequestPriority.Visible)
        )
        assertIs<DataResult.Content<TaskSnapshot>>(
            client.createTasks(ChannelId("channel-1"), listOf(TaskCreateDraft("Ship KMP")), DataRequestPriority.Visible)
        )
        assertIs<DataResult.Content<TaskActionResult>>(client.claimTask(TaskId("task-1")))
        assertIs<DataResult.Content<TaskConvertResult>>(client.convertMessage(MessageId("message-1")))
        assertIs<DataResult.Content<TaskActionResult>>(client.unclaimTask(TaskId("task-1")))
        assertIs<DataResult.Content<TaskActionResult>>(client.updateTaskStatus(TaskId("task-1"), TaskStatus.InReview))
        assertIs<DataResult.Content<Unit>>(client.deleteTask(TaskId("task-1")))

        assertEquals(
            listOf(
                "GET tasks/server priority=Visible server=server-1 body=",
                "GET tasks/channel/channel-1 priority=Visible server= body=",
                "GET tasks/channel/channel-1/number/42 priority=Visible server= body=",
                """POST tasks/channel/channel-1 priority=Visible server= body={"tasks":[{"title":"Ship KMP"}]}""",
                "PATCH tasks/task-1/claim priority=Foreground server= body=",
                """POST tasks/convert-message priority=Foreground server= body={"messageId":"message-1"}""",
                "PATCH tasks/task-1/unclaim priority=Foreground server= body=",
                """PATCH tasks/task-1/status priority=Foreground server= body={"status":"in_review"}""",
                "DELETE tasks/task-1 priority=Foreground server= body="
            ),
            networkClient.calls
        )
    }

    @Test
    fun networkTasksApiBindsProjectedReadsAndWritesToExplicitServer() = runBlocking {
        val networkClient = RecordingTasksNetworkClient()
        val client = SlockTasksApiClient(NetworkSlockTasksApi(networkClient))
        val serverId = ServerId("server-peer")

        assertIs<DataResult.Content<TaskSnapshot>>(
            client.channelTasks(serverId, ChannelId("channel-peer"), DataRequestPriority.Visible)
        )
        assertIs<DataResult.Content<TaskActionResult>>(
            client.taskByNumber(serverId, ChannelId("channel-peer"), 9, DataRequestPriority.Visible)
        )
        assertIs<DataResult.Content<TaskSnapshot>>(
            client.createTasks(
                serverId,
                ChannelId("channel-peer"),
                listOf(TaskCreateDraft("Peer task")),
                DataRequestPriority.Foreground
            )
        )
        assertIs<DataResult.Content<TaskActionResult>>(
            client.updateTaskStatus(serverId, TaskId("task-peer"), TaskStatus.Done)
        )
        assertIs<DataResult.Content<TaskActionResult>>(client.claimTask(serverId, TaskId("task-peer")))
        assertIs<DataResult.Content<TaskConvertResult>>(client.convertMessage(serverId, MessageId("message-peer")))
        assertIs<DataResult.Content<TaskActionResult>>(client.unclaimTask(serverId, TaskId("task-peer")))
        assertIs<DataResult.Content<Unit>>(client.deleteTask(serverId, TaskId("task-peer")))

        assertEquals(
            listOf(
                "GET tasks/channel/channel-peer priority=Visible server=server-peer body=",
                "GET tasks/channel/channel-peer/number/9 priority=Visible server=server-peer body=",
                """POST tasks/channel/channel-peer priority=Foreground server=server-peer body={"tasks":[{"title":"Peer task"}]}""",
                """PATCH tasks/task-peer/status priority=Foreground server=server-peer body={"status":"done"}""",
                "PATCH tasks/task-peer/claim priority=Foreground server=server-peer body=",
                """POST tasks/convert-message priority=Foreground server=server-peer body={"messageId":"message-peer"}""",
                "PATCH tasks/task-peer/unclaim priority=Foreground server=server-peer body=",
                "DELETE tasks/task-peer priority=Foreground server=server-peer body="
            ),
            networkClient.calls
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
    fun convertMessageAcceptsSuccessfulResponseWithoutTaskProjection() = runBlocking {
        val missingBody =
            SlockTasksApiClient(
                RecordingTasksApi(convertResponse = HomeNetworkResponse(code = 204, bodyText = null))
            ).convertMessage(MessageId("message-1"))
        val emptyEnvelope =
            SlockTasksApiClient(
                RecordingTasksApi(convertResponse = HomeNetworkResponse(code = 200, bodyText = "{}"))
            ).convertMessage(MessageId("message-1"))

        assertNull(assertIs<DataResult.Content<TaskConvertResult>>(missingBody).data.task)
        assertNull(assertIs<DataResult.Content<TaskConvertResult>>(emptyEnvelope).data.task)
    }

    @Test
    fun typedTasksApiRequiresDeleteOkTrue() = runBlocking {
        val client = SlockTasksApiClient(RecordingTasksApi(deleteBody = """{"success":true}"""))

        val result = client.deleteTask(TaskId("task-1"))

        val error = assertIs<DataResult.Error>(result)
        assertEquals("tasks_delete_contract_mismatch", error.error.code)
    }

    @Test
    fun taskPermissionMappersRetainExactErrorFieldOn401And403() {
        listOf(401, 403).forEach { status ->
            val exactReason = "  task-$status raw reason  "
            val result =
                HomeNetworkResponse(
                    code = status,
                    bodyText = null,
                    errorBodyText = """{"error":"$exactReason"}"""
                ).toTasksResult()

            val blocker = assertIs<DataResult.NoPermission>(result).blocker
            assertEquals("tasks_$status", blocker.code)
            assertEquals(exactReason, blocker.serverMessage)
        }
    }

    @Test
    fun statusUpdateMapsMatchingAlreadyAppliedConflict() = runBlocking {
        val client =
            SlockTasksApiClient(
                RecordingTasksApi(
                    updateResponse =
                        HomeNetworkResponse(
                            code = 409,
                            bodyText = null,
                            errorBodyText = """{"error":"already_closed"}""",
                        )
                )
            )

        val result = client.updateTaskStatus(TaskId("task-1"), TaskStatus.Closed)

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("tasks_status_already_closed", error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun statusUpdateDoesNotAcceptMismatchedAlreadyAppliedConflict() = runBlocking {
        val client =
            SlockTasksApiClient(
                RecordingTasksApi(
                    updateResponse =
                        HomeNetworkResponse(
                            code = 409,
                            bodyText = null,
                            errorBodyText = """{"error":"already_done"}""",
                        )
                )
            )

        val result = client.updateTaskStatus(TaskId("task-1"), TaskStatus.Closed)

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("tasks_409", error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun statusUpdateAcceptsSuccessfulResponseWithoutTaskProjection() = runBlocking {
        val client =
            SlockTasksApiClient(
                RecordingTasksApi(
                    updateResponse = HomeNetworkResponse(code = 204, bodyText = null)
                )
            )

        val result = client.updateTaskStatus(TaskId("task-1"), TaskStatus.Closed)

        val error = assertIs<DataResult.Error>(result).error
        assertEquals(TASKS_STATUS_APPLIED_WITHOUT_PROJECTION, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun taskWireMappingPreservesTrimmedDescription() = runBlocking {
        val client =
            SlockTasksApiClient(
                RecordingTasksApi(
                    listBody =
                        """
                        {
                          "tasks": [
                            {
                              "id": "task-1",
                              "revision": 41,
                              "channelId": "channel-1",
                              "taskNumber": 1,
                              "title": "Task #1: canonical",
                              "description": "  Align the visual fixture copy.  ",
                              "status": "todo",
                              "createdByType": "user",
                              "createdById": "user-1"
                            },
                            {
                              "id": "task-2",
                              "channelId": "channel-1",
                              "taskNumber": 2,
                              "title": "Task #2: blank description",
                              "description": "   ",
                              "status": "done",
                              "createdByType": "user",
                              "createdById": "user-1"
                            }
                          ]
                        }
                        """.trimIndent()
                )
            )

        val snapshot = assertIs<DataResult.Content<TaskSnapshot>>(
            client.serverTasks(ServerId("server-1"), DataRequestPriority.Visible)
        ).data

        assertEquals("Align the visual fixture copy.", snapshot.tasks[0].description)
        assertEquals(41L, snapshot.tasks[0].revision)
        assertEquals(null, snapshot.tasks[1].description)
    }

    @Test
    fun taskListDropsUnknownStatusAndDoesNotInventUnknownAssigneeKind() = runBlocking {
        val logs = mutableListOf<String>()
        val client =
            SlockTasksApiClient(
                RecordingTasksApi(
                    listBody =
                        """
                        {
                          "tasks": [
                            {
                              "id": "task-invalid-status",
                              "channelId": "channel-1",
                              "title": "Must not become Todo",
                              "status": "future-status",
                              "createdByType": "user",
                              "createdById": "user-1"
                            },
                            {
                              "id": "task-invalid-assignee",
                              "channelId": "channel-1",
                              "title": "Keep task, drop assignee",
                              "status": "in_review",
                              "createdByType": "user",
                              "createdById": "user-1",
                              "claimedByType": "future-member",
                              "claimedById": "secret-assignee-id",
                              "claimedByName": "Secret Assignee"
                            },
                            {
                              "id": "task-valid",
                              "channelId": "channel-1",
                              "title": "Keep valid task",
                              "status": "done",
                              "createdByType": "user",
                              "createdById": "user-1",
                              "claimedByType": "agent",
                              "claimedById": "agent-1",
                              "claimedByName": "Agent One"
                            },
                            {
                              "id": "task-cross-dialect",
                              "channelId": "channel-1",
                              "title": "Keep task without invented members",
                              "status": "todo",
                              "createdByType": "human",
                              "createdById": "user-2",
                              "claimedByType": "system",
                              "claimedById": "system"
                            }
                          ]
                        }
                        """.trimIndent(),
                ),
                diagnosticsLogger = logs::add,
            )

        val snapshot =
            assertIs<DataResult.Content<TaskSnapshot>>(
                client.serverTasks(ServerId("server-1"), DataRequestPriority.Visible),
            ).data

        assertEquals(
            listOf("task-invalid-assignee", "task-valid", "task-cross-dialect"),
            snapshot.tasks.map { it.id.value },
        )
        assertEquals(TaskStatus.InReview, snapshot.tasks.first().status)
        assertNull(snapshot.tasks.first().assignee)
        assertEquals(MemberKind.Agent, snapshot.tasks[1].assignee?.kind)
        assertNull(snapshot.tasks.last().creator)
        assertNull(snapshot.tasks.last().assignee)
        assertTrue(logs.any { it == "projection_value_rejected source=task_wire field=task_status value=future-status" })
        assertTrue(logs.any { it == "projection_value_rejected source=task_assignee field=member_kind value=future-member" })
        assertTrue(logs.any { it == "projection_value_rejected source=task_creator field=member_kind value=human" })
        assertTrue(logs.any { it == "projection_value_rejected source=task_assignee field=member_kind value=system" })
        assertTrue(logs.none { "secret-assignee-id" in it || "Secret Assignee" in it })
    }

    @Test
    fun taskActionWithUnknownStatusReturnsContractMismatch() = runBlocking {
        val logs = mutableListOf<String>()
        val client =
            SlockTasksApiClient(
                RecordingTasksApi(
                    actionBody =
                        """
                        {
                          "task": {
                            "id": "task-1",
                            "channelId": "channel-1",
                            "title": "Must not become Todo",
                            "status": "future-status",
                            "createdByType": "user",
                            "createdById": "user-1"
                          }
                        }
                        """.trimIndent(),
                ),
                diagnosticsLogger = logs::add,
            )

        val error =
            assertIs<DataResult.Error>(
                client.taskByNumber(ChannelId("channel-1"), 1, DataRequestPriority.Visible),
            )

        assertEquals("tasks_by_number_contract_mismatch", error.error.code)
        assertEquals(
            listOf("projection_value_rejected source=task_wire field=task_status value=future-status"),
            logs,
        )
    }

    private class RecordingTasksApi(
        private val listResponse: HomeNetworkResponse? = null,
        private val listBody: String =
            """{"tasks":[{"id":"task-1","channelId":"channel-1","taskNumber":1,"title":"Task #1: canonical","status":"todo","createdByType":"user","createdById":"user-1"}]}""",
        private val actionBody: String =
            """{"task":{"id":"task-1","channelId":"channel-1","taskNumber":1,"title":"Task #1: canonical","status":"todo","createdByType":"user","createdById":"user-1"}}""",
        private val updateResponse: HomeNetworkResponse? = null,
        private val createBody: String =
            """{"tasks":[{"id":"task-2","channelId":"channel-1","taskNumber":2,"title":"Task #2: created","status":"todo","createdByType":"user","createdById":"user-1"}]}""",
        private val deleteBody: String = """{"ok":true}""",
        private val convertResponse: HomeNetworkResponse? = null
    ) : SlockTasksApi {
        val calls = mutableListOf<String>()

        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            calls += "GET tasks/server server=${serverId.value}"
            return listResponse ?: ok(listBody)
        }

        override suspend fun channelTasks(channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse {
            calls += "GET tasks/channel/${channelId.value}"
            return listResponse ?: ok(listBody)
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
            return convertResponse ?: ok(actionBody)
        }

        override suspend fun unclaimTask(taskId: TaskId): HomeNetworkResponse {
            calls += "PATCH tasks/${taskId.value}/unclaim"
            return ok(actionBody)
        }

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse {
            calls += "PATCH tasks/${taskId.value}/status body=${buildSharedTaskStatusBody(status)}"
            return updateResponse ?: ok(actionBody)
        }

        override suspend fun deleteTask(taskId: TaskId): HomeNetworkResponse {
            calls += "DELETE tasks/${taskId.value}"
            return ok(deleteBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private class RecordingTasksNetworkClient : SlockNetworkClient {
        val calls = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            calls +=
                "$method ${path.trim('/')} priority=$priority server=${serverIdOverride?.value.orEmpty()} body=${bodyText.orEmpty()}"
            return when {
                path.trim('/') == "tasks/server" -> ok(listBody)
                path.trim('/').startsWith("tasks/channel/") && path.trim('/').contains("/number/") -> ok(actionBody)
                path.trim('/').startsWith("tasks/channel/") && method == "POST" -> ok(createBody)
                path.trim('/').startsWith("tasks/channel/") -> ok(listBody)
                path.trim('/').startsWith("tasks/") && path.trim('/').endsWith("/claim") -> ok(actionBody)
                path.trim('/') == "tasks/convert-message" -> ok(actionBody)
                path.trim('/').startsWith("tasks/") && path.trim('/').endsWith("/unclaim") -> ok(actionBody)
                path.trim('/').startsWith("tasks/") && path.trim('/').endsWith("/status") -> ok(actionBody)
                path.trim('/').startsWith("tasks/") && method == "DELETE" -> ok("""{"ok":true}""")
                else -> HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = "not found")
            }
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)

        private companion object {
            const val listBody =
                """{"tasks":[{"id":"task-1","channelId":"channel-1","taskNumber":1,"title":"Task #1: canonical","status":"todo","createdByType":"user","createdById":"user-1"}]}"""
            const val createBody =
                """{"tasks":[{"id":"task-2","channelId":"channel-1","taskNumber":2,"title":"Task #2: created","status":"todo","createdByType":"user","createdById":"user-1"}]}"""
            const val actionBody =
                """{"task":{"id":"task-1","channelId":"channel-1","taskNumber":1,"title":"Task #1: canonical","status":"todo","createdByType":"user","createdById":"user-1"}}"""
        }
    }
}
