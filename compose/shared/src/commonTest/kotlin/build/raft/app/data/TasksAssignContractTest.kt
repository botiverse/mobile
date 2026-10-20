package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the task v2 assignee-write port (task #244).
 *
 * Server contract (botiverse/slock origin/staging tasks.ts, `PATCH
 * /api/tasks/:taskId/assignee`): body `{ assignee: {type:'user'|'agent', id} | null,
 * expectedRevision? }`; explicit `assignee: null` unassigns; 409 +
 * `task state changed concurrently` + `currentRevision` is the lost-OCC signal;
 * 403 without the `assignTasks` capability; 200 returns `{ task: <projected> }`.
 */
class TasksAssignContractTest {
    private val human =
        MemberProfile(MemberId("user-1"), "Artin Host", "artin", MemberKind.Human)
    private val agent =
        MemberProfile(MemberId("agent-1"), "Sacha Agent", "sacha", MemberKind.Agent)

    @Test
    fun assignBodySerializesUserAssignee() {
        assertEquals(
            """{"assignee":{"type":"user","id":"user-1"}}""",
            buildSharedTaskAssignBody(human, expectedRevision = null),
        )
    }

    @Test
    fun assignBodySerializesAgentAssignee() {
        assertEquals(
            """{"assignee":{"type":"agent","id":"agent-1"}}""",
            buildSharedTaskAssignBody(agent, expectedRevision = null),
        )
    }

    @Test
    fun unassignBodyKeepsExplicitNullAssigneeKey() {
        // `explicitNulls = false` in tasksJson would drop the key entirely; the
        // server 400s on a missing key, so the explicit null must survive.
        assertEquals("""{"assignee":null}""", buildSharedTaskAssignBody(null, expectedRevision = null))
    }

    @Test
    fun assignBodyIncludesExpectedRevisionWhenPresent() {
        assertEquals(
            """{"assignee":{"type":"user","id":"user-1"},"expectedRevision":42}""",
            buildSharedTaskAssignBody(human, expectedRevision = 42L),
        )
        assertEquals(
            """{"assignee":null,"expectedRevision":7}""",
            buildSharedTaskAssignBody(null, expectedRevision = 7L),
        )
    }

    @Test
    fun systemMemberIsNotAssignable() {
        val system = MemberProfile(MemberId("sys-1"), "System", "system", MemberKind.System)
        assertFailsWith<IllegalArgumentException> {
            buildSharedTaskAssignBody(system, expectedRevision = null)
        }
    }

    @Test
    fun assignSuccessMapsProjectedTask() = runBlocking {
        val response = HomeNetworkResponse(code = 200, bodyText = taskBody(assigneeType = "user", assigneeId = "user-1"))
        val result = response.toTaskAssignActionResult()
        val content = assertIs<DataResult.Content<TaskActionResult>>(result)
        assertEquals(MemberId("user-1"), content.data.task.assignee?.id)
        assertEquals(42L, content.data.task.revision)
    }
    @Test
    fun assignLostOccRaceMapsToConflictCode() {
        val payload = """{"error":"task state changed concurrently","currentRevision":9}"""
        val response =
            HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = payload)
        val result = response.toTaskAssignActionResult()
        val error = assertIs<DataResult.Error>(result)
        assertEquals(TASKS_ASSIGN_CONFLICT, error.error.code)
        assertTrue(error.error.retryable)
    }

    @Test
    fun assignConflictDetectionRequiresExactServerError() {
        assertTrue(taskAssignConflictErrorCode("""{"error":"task state changed concurrently"}"""))
        assertTrue(taskAssignConflictErrorCode("""{"error":"Task State Changed Concurrently"}"""))
        assertTrue(
            !taskAssignConflictErrorCode(null),
            "missing body is not a conflict",
        )
        assertTrue(
            !taskAssignConflictErrorCode("""{"error":"some other failure"}"""),
            "other 409 domain errors stay generic",
        )
    }

    @Test
    fun assignOtherConflictStaysGenericRetryable() {
        val response =
            HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = """{"error":"some other failure"}""")
        val result = response.toTaskAssignActionResult()
        val error = assertIs<DataResult.Error>(result)
        assertEquals("tasks_409", error.error.code)
    }

    @Test
    fun assignWithoutCapabilityMapsToNoPermission() {
        val response =
            HomeNetworkResponse(code = 403, bodyText = null, errorBodyText = """{"error":"You do not have permission to assign tasks"}""")
        val result = response.toTaskAssignActionResult()
        val blocked = assertIs<DataResult.NoPermission>(result)
        assertEquals("tasks_403", blocked.blocker.code)
    }

    @Test
    fun assignValidationErrorMapsToRetryable400() {
        val response =
            HomeNetworkResponse(code = 400, bodyText = null, errorBodyText = """{"error":"assignee must be { type: 'user'|'agent', id } or null"}""")
        val result = response.toTaskAssignActionResult()
        val error = assertIs<DataResult.Error>(result)
        assertEquals("tasks_400", error.error.code)
    }

    @Test
    fun assignSuccessWithoutTaskProjectionIsContractMismatch() {
        val response = HomeNetworkResponse(code = 200, bodyText = """{"task":null}""")
        val result = response.toTaskAssignActionResult()
        val error = assertIs<DataResult.Error>(result)
        assertEquals("tasks_assign_contract_mismatch", error.error.code)
    }

    @Test
    fun typedClientSendsAssignPatchToCanonicalRoute() = runBlocking {
        val transport = RecordingAssignApi()
        val client = RaftTasksApiClient(transport)

        client.assignTask(
            ServerId("server-1"),
            TaskId("task-9"),
            assignee = human,
            expectedRevision = 42L,
        )

        assertEquals(1, transport.calls.size)
        val (taskId, body) = transport.calls.single()
        assertEquals(TaskId("task-9"), taskId)
        assertEquals(
            """{"assignee":{"type":"user","id":"user-1"},"expectedRevision":42}""",
            body,
        )
    }

    @Test
    fun typedClientUnassignSendsExplicitNullBody() = runBlocking {
        val transport = RecordingAssignApi()
        val client = RaftTasksApiClient(transport)

        client.assignTask(ServerId("server-1"), TaskId("task-9"), assignee = null, expectedRevision = 3L)

        val (_, body) = transport.calls.single()
        assertEquals("""{"assignee":null,"expectedRevision":3}""", body)
    }

    private class RecordingAssignApi : RaftTasksApi {
        val calls = mutableListOf<Pair<TaskId, String>>()

        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun assignTask(serverId: ServerId, taskId: TaskId, bodyText: String): HomeNetworkResponse {
            calls += taskId to bodyText
            return HomeNetworkResponse(code = 200, bodyText = taskBody(assigneeType = "user", assigneeId = "user-1"))
        }
    }

    private companion object {
        fun taskBody(assigneeType: String?, assigneeId: String?): String {
            val claimed =
                if (assigneeType == null || assigneeId == null) {
                    ""
                } else {
                    ""","claimedByType":"$assigneeType","claimedById":"$assigneeId","claimedByName":"Artin Host""""
                }
            return """{"task":{"id":"task-9","channelId":"channel-1","taskNumber":9,"title":"Task #9: assignee","status":"todo","createdByType":"user","createdById":"user-1"$claimed,"revision":42}}"""
        }
    }
}
