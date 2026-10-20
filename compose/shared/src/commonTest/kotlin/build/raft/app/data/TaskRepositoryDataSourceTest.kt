package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskRepositoryDataSourceTest {
    @Test
    fun taskRepositoryMapsServerTasksAndClaimAction() = runBlocking {
        val network =
            FakeTaskNetworkClient(
                serverTasksJson = """
                    {
                      "tasks": [
                        {
                          "id": "task-1",
                          "title": "Wire tasks",
                          "status": "in_progress",
                          "taskNumber": 181,
                          "messageId": "parent-message-1",
                          "threadId": "thread-message-1",
                          "claimedByType": "user",
                          "claimedById": "user-1",
                          "claimedByName": "Artin",
                          "channelName": "android-dev",
                          "updatedAt": "Today"
                        },
                        {
                          "id": "task-2",
                          "title": "Done task",
                          "status": "done",
                          "claimedByType": "user",
                          "claimedById": "other-user"
                        }
                      ]
                    }
                """.trimIndent(),
                claimTaskJson = """
                    {"task":{"id":"task-1","title":"Wire tasks","status":"in_review","claimedByType":"user","claimedById":"user-1"}}
                """.trimIndent(),
                updateTaskStatusJson = """
                    {"task":{"id":"task-1","title":"Wire tasks","status":"closed","claimedByType":"user","claimedById":"user-1"}}
                """.trimIndent()
            )
        val repository = RealTaskRepository(sampleStore(), network)

        val tasks =
            repository.getTasks(
                ServerId("server-1"),
                TaskFilter(statuses = setOf(TaskStatus.InProgress), assignedToMeOnly = true)
            )
        val claim = repository.claimTask(TaskId("task-1"))
        val statusUpdate = repository.updateTaskStatus(TaskId("task-1"), TaskStatus.Closed)

        val snapshot = assertIs<DataResult.Content<TaskSnapshot>>(tasks).data
        assertEquals(1, snapshot.tasks.size)
        assertEquals("Wire tasks", snapshot.tasks.single().title)
        assertEquals("android-dev", snapshot.tasks.single().channelName)
        assertEquals("parent-message-1", snapshot.tasks.single().parentMessageId?.value)
        assertEquals("thread-message-1", snapshot.tasks.single().threadId?.value)
        assertEquals(false, snapshot.tasks.single().isThreadTask)
        assertEquals("Artin", snapshot.tasks.single().assignee?.displayName)
        assertEquals(1, snapshot.summaryCounts[TaskStatus.InProgress])
        assertEquals(TaskStatus.InReview, assertIs<DataResult.Content<TaskActionResult>>(claim).data.task.status)
        assertEquals(TaskStatus.Closed, assertIs<DataResult.Content<TaskActionResult>>(statusUpdate).data.task.status)
        assertEquals(listOf("task-1"), network.claimedTaskIds)
        assertEquals(listOf("""task-1:{"status":"closed"}"""), network.updatedTaskStatusBodies)
        assertEquals(
            listOf(DataRequestPriority.Visible, DataRequestPriority.Foreground, DataRequestPriority.Foreground),
            network.priorities
        )
    }

    @Test
    fun taskRepositoryMapsChannelScopedTasks() = runBlocking {
        val network =
            FakeTaskNetworkClient(
                channelTasksJson = """
                    {
                      "tasks": [
                        {
                          "id": "task-channel-1",
                          "title": "Channel task",
                          "status": "todo",
                          "taskNumber": 24,
                          "channelId": "channel-1",
                          "channelName": "android-dev",
                          "updatedAt": "2026-05-09T20:55:00+08:00"
                        }
                      ]
                    }
                """.trimIndent()
            )
        val repository = RealTaskRepository(sampleStore(), network)

        val tasks =
            repository.getChannelTasks(
                serverId = ServerId("server-1"),
                channelId = ChannelId("channel-1"),
                filter = TaskFilter(statuses = setOf(TaskStatus.Todo))
            )

        val snapshot = assertIs<DataResult.Content<TaskSnapshot>>(tasks).data
        assertEquals(1, snapshot.tasks.size)
        assertEquals("Channel task", snapshot.tasks.single().title)
        assertEquals("android-dev", snapshot.tasks.single().channelName)
        assertEquals(TaskStatus.Todo, snapshot.tasks.single().status)
        assertEquals(1, snapshot.summaryCounts[TaskStatus.Todo])
        assertEquals(listOf("channel-1"), network.channelTaskCalls)
        assertEquals(listOf("server-1"), network.channelTaskServerIds)
    }

    @Test
    fun explicitChannelTasksRouteToRequestedAccessibleServer() = runBlocking {
        val network = FakeTaskNetworkClient()
        val repository = RealTaskRepository(sampleStore(), network)

        val result =
            repository.getChannelTasks(
                serverId = ServerId("server-2"),
                channelId = ChannelId("channel-2"),
                filter = TaskFilter()
            )

        assertIs<DataResult.Empty>(result)
        assertEquals(listOf("channel-2"), network.channelTaskCalls)
        assertEquals(listOf("server-2"), network.channelTaskServerIds)
    }

    @Test
    fun taskRepositoryMapsRealServerTaskShapeForThreadNavigation() = runBlocking {
        val network =
            FakeTaskNetworkClient(
                serverTasksJson = """
                    {
                      "tasks": [
                        {
                          "id": "message-task-1",
                          "taskNumber": 9,
                          "title": "Open replies from Tasks",
                          "content": "Open replies from Tasks",
                          "status": "in_review",
                          "taskStatus": "in_review",
                          "channelType": "channel",
                          "channelId": "parent-channel-1",
                          "channelName": "home-fixes",
                          "messageId": "parent-message-1",
                          "threadId": "reply-thread-1",
                          "claimedById": "agent-1",
                          "claimedByName": "Android-App-Integrator",
                          "claimedByType": "agent",
                          "createdAt": "2026-05-09T03:00:00.000Z",
                          "updatedAt": "2026-05-09T03:05:00.000Z"
                        },
                        {
                          "id": "closed-task-1",
                          "taskNumber": 10,
                          "title": "Closed task",
                          "status": "closed",
                          "channelType": "channel",
                          "channelId": "parent-channel-2",
                          "messageId": "parent-message-2",
                          "threadId": "reply-thread-2"
                        }
                      ]
                    }
                """.trimIndent()
            )
        val repository = RealTaskRepository(sampleStore(), network)

        val result = repository.getTasks(ServerId("server-1"), TaskFilter())

        val snapshot = assertIs<DataResult.Content<TaskSnapshot>>(result).data
        val threadTask = snapshot.tasks.first { it.id.value == "message-task-1" }
        assertEquals(TaskStatus.InReview, threadTask.status)
        assertEquals(9, threadTask.taskNumber)
        assertEquals("parent-channel-1", threadTask.channelId?.value)
        assertEquals("parent-message-1", threadTask.messageId?.value)
        assertEquals("parent-message-1", threadTask.parentMessageId?.value)
        assertEquals("reply-thread-1", threadTask.threadId?.value)
        assertEquals(false, threadTask.isThreadTask)
        assertEquals("Android-App-Integrator", threadTask.assignee?.displayName)
        assertEquals(MemberKind.Agent, threadTask.assignee?.kind)
        assertEquals("home-fixes", threadTask.channelName)
        assertEquals(TaskStatus.Closed, snapshot.tasks.first { it.id.value == "closed-task-1" }.status)
        assertEquals(1, snapshot.summaryCounts[TaskStatus.InReview])
        assertEquals(1, snapshot.summaryCounts[TaskStatus.Closed])
    }

    @Test
    fun taskRepositoryRequiresSelectedSessionServer() = runBlocking {
        val repository = RealTaskRepository(sampleStore(), FakeTaskNetworkClient())

        val result = repository.getTasks(ServerId("other-server"), TaskFilter())

        assertIs<DataResult.NoPermission>(result)
        Unit
    }

    private fun sampleStore(): RaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-1",
                        email = "artin@slock.ai",
                        displayName = "Artin"
                    ),
                accessToken = "token-a",
                refreshToken = "token-r",
                selectedServerId = "server-1",
                servers =
                    listOf(
                        StoredAuthServer(
                            id = "server-1",
                            name = "Slock Android",
                            slug = "android"
                        ),
                        StoredAuthServer(
                            id = "server-2",
                            name = "Slock iOS",
                            slug = "ios"
                        )
                    )
            )
        )

    private class FakeTaskNetworkClient(
        private val serverTasksJson: String = """{"tasks":[]}""",
        private val channelTasksJson: String = """{"tasks":[]}""",
        private val claimTaskJson: String = """{"task":{}}""",
        private val updateTaskStatusJson: String = """{"task":{}}"""
    ) : RaftNetworkClient {
        val priorities = mutableListOf<DataRequestPriority>()
        val claimedTaskIds = mutableListOf<String>()
        val channelTaskCalls = mutableListOf<String>()
        val channelTaskServerIds = mutableListOf<String>()
        val updatedTaskStatusBodies = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            priorities += priority
            val normalized = path.trim('/')
            return when {
                normalized == "tasks/server" && method == "GET" ->
                    ok(serverTasksJson)
                normalized.startsWith("tasks/channel/") && method == "GET" -> {
                    channelTaskCalls += normalized.removePrefix("tasks/channel/")
                    channelTaskServerIds += serverIdOverride?.value.orEmpty()
                    ok(channelTasksJson)
                }
                normalized.startsWith("tasks/") && normalized.endsWith("/claim") && method == "PATCH" -> {
                    val taskId = normalized.removePrefix("tasks/").substringBefore("/")
                    claimedTaskIds += taskId
                    ok(claimTaskJson)
                }
                normalized.startsWith("tasks/") && normalized.endsWith("/status") && method == "PATCH" -> {
                    val taskId = normalized.removePrefix("tasks/").substringBefore("/")
                    updatedTaskStatusBodies += "$taskId:${bodyText.orEmpty()}"
                    ok(updateTaskStatusJson)
                }
                else -> error("Unhandled request $method $normalized")
            }
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }
}
