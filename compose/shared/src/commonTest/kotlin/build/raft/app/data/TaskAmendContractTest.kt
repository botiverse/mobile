package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PATCH /api/tasks/:taskId` (botiverse/slock v1.21.64 4a60ff938b6b9c44c65a48aa7df8ea40663f61fe,
 * `packages/server/src/routes/tasks.ts` `taskRouter.patch("/:taskId")`; web editor #14114).
 */
class TaskAmendContractTest {
    private fun body(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun bodyCarriesOneFieldTrimmedAndTheRevision() {
        val title = body(buildSharedTaskAmendBody(TaskAmendPatch.Title("  New title "), expectedRevision = 7L))
        assertEquals(setOf("title", "expectedRevision"), title.keys)
        assertEquals("New title", title["title"]!!.jsonPrimitive.content)
        assertEquals(7L, title["expectedRevision"]!!.jsonPrimitive.long)

        val description = body(buildSharedTaskAmendBody(TaskAmendPatch.Description("Line one\nline two "), expectedRevision = null))
        assertEquals(setOf("description"), description.keys)
        assertEquals("Line one\nline two", description["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun aBlankDescriptionClearsIt() {
        val cleared = body(buildSharedTaskAmendBody(TaskAmendPatch.Description("   "), expectedRevision = 3L))
        // Never `description` and `clearDescription` together: the server refuses that with a 400.
        assertEquals(setOf("clearDescription", "expectedRevision"), cleared.keys)
        assertTrue(cleared["clearDescription"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun savedAnswerIsTheProjectedTask() {
        val result = HomeNetworkResponse(code = 200, bodyText = TaskAmendFixtures.taskBody(title = "Renamed", revision = 8L)).toTaskAmendResult()
        val saved = assertIs<TaskAmendResult.Saved>(result)
        assertEquals("Renamed", saved.task.title)
        assertEquals(8L, saved.task.revision)
        assertEquals("Why it matters", saved.task.description)
    }

    @Test
    fun aLostRevisionRaceIsStaleWithTheCurrentRevision() {
        val stale =
            HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = """{"error":"task state changed concurrently","currentRevision":12}""")
                .toTaskAmendResult()
        assertEquals(TaskAmendResult.Stale(12L), stale)
        val unreadable =
            HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = """{"error":"task state changed concurrently","currentRevision":null}""")
                .toTaskAmendResult()
        assertEquals(TaskAmendResult.Stale(null), unreadable)
    }

    @Test
    fun refusalsKeepTheServerSentenceAndTraceId() {
        val traceId = "0123456789abcdef0123456789abcdef"
        listOf(
            403 to "You must join this channel to modify tasks",
            // A 409 without `currentRevision` (a legacy card) is a refusal, not a race.
            409 to "Legacy tasks cannot be amended",
            400 to "title must be a non-empty string",
        ).forEach { (code, sentence) ->
            val result =
                HomeNetworkResponse(
                    code = code,
                    bodyText = null,
                    errorBodyText = """{"error":"$sentence"}""",
                    responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                ).toTaskAmendResult()
            val failed = assertIs<TaskAmendResult.Failed>(result, "HTTP $code")
            assertEquals("tasks_amend_$code", failed.error.code)
            assertEquals(sentence, failed.error.serverMessage)
            assertEquals(traceId, failed.error.traceId)
            assertFalse(failed.error.retryable)
        }
    }

    @Test
    fun aSuccessWithoutATaskIsAContractMismatch() {
        val failed = assertIs<TaskAmendResult.Failed>(HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""").toTaskAmendResult())
        assertEquals("tasks_amend_contract_mismatch", failed.error.code)
        assertNull(failed.error.serverMessage)
    }

    @Test
    fun clientPatchesTheTaskRouteOnItsServer() = runTest {
        val api = TaskAmendFixtures.RecordingAmendApi { HomeNetworkResponse(code = 200, bodyText = TaskAmendFixtures.taskBody(title = "Renamed", revision = 8L)) }
        val result = RaftTasksApiClient(api).amendTask(ServerId("server-1"), TaskId("task-9"), TaskAmendPatch.Title("Renamed"), expectedRevision = 7L)
        assertIs<TaskAmendResult.Saved>(result)
        val call = api.calls.single()
        assertEquals(ServerId("server-1"), call.serverId)
        assertEquals(TaskId("task-9"), call.taskId)
        assertEquals("Renamed", body(call.body)["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSummaryRowWithAnUnsentDescriptionIsNotLoaded() {
        val summary =
            """{"task":{"id":"task-9","channelId":"channel-1","taskNumber":9,"title":"Summary","status":"todo","hasDescription":true,"revision":4}}"""
        val task = assertIs<TaskAmendResult.Saved>(HomeNetworkResponse(code = 200, bodyText = summary).toTaskAmendResult()).task
        assertNull(task.description)
        assertFalse(task.descriptionLoaded)
        val none =
            """{"task":{"id":"task-9","channelId":"channel-1","taskNumber":9,"title":"Full","status":"todo","description":null,"revision":4}}"""
        assertTrue(assertIs<TaskAmendResult.Saved>(HomeNetworkResponse(code = 200, bodyText = none).toTaskAmendResult()).task.descriptionLoaded)
        assertEquals(TaskStatus.Todo, task.status)
    }
}

internal object TaskAmendFixtures {
    fun taskBody(title: String, revision: Long, description: String = "Why it matters"): String =
        """{"task":{"id":"task-9","channelId":"channel-1","taskNumber":9,"title":"$title","description":"$description","status":"todo","createdByType":"user","createdById":"user-1","revision":$revision}}"""

    data class AmendCall(val serverId: ServerId, val taskId: TaskId, val body: String)

    class RecordingAmendApi(
        private val deleteAnswer: () -> HomeNetworkResponse = { HomeNetworkResponse(code = 501, bodyText = null) },
        private val answer: () -> HomeNetworkResponse,
    ) : RaftTasksApi {
        val calls = mutableListOf<AmendCall>()
        val deleted = mutableListOf<TaskId>()

        override suspend fun deleteTask(serverId: ServerId, taskId: TaskId): HomeNetworkResponse {
            deleted += taskId
            return deleteAnswer()
        }

        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun amendTask(serverId: ServerId, taskId: TaskId, bodyText: String): HomeNetworkResponse {
            calls += AmendCall(serverId, taskId, bodyText)
            return answer()
        }
    }
}
