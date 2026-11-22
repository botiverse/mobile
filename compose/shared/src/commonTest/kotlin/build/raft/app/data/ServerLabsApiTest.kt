package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Server Labs wire contract (data/ServerLabsApi.kt). Fixtures are the shapes
 * botiverse/slock staging aa270b415d44 packages/server/src/routes/serverLabs.api.test.ts reads
 * back: a member's first read (`open_lab`, `paused_lab`; draft left out by the server), an admin's
 * enrollment answer (`applied`, `version` 2, the `updatedAt` of the row), and the stale write's
 * 409 `{ error, code, currentVersion }`.
 */
class ServerLabsApiTest {
    private val memberRead =
        """
        {"serverId":"srv-1","accessEnabled":false,"version":0,"canManageAccess":false,"canManageEnrollments":false,
         "labs":[
           {"labKey":"open_lab","name":"Open Lab","description":"Available","state":"open","enrolled":false,"effective":false,"updatedAt":null},
           {"labKey":"paused_lab","name":"Paused Lab","description":"Paused","state":"paused","enrolled":false,"effective":false,"updatedAt":null}
         ]}
        """.trimIndent()

    private val enrollmentAnswer =
        """
        {"applied":true,"auditEventId":"6f1c2f0e-6d3a-4c4e-9a59-1f0a3b2c4d5e","serverId":"srv-1","accessEnabled":true,"version":2,
         "canManageAccess":false,"canManageEnrollments":true,
         "labs":[
           {"labKey":"open_lab","name":"Open Lab","description":"Available","state":"open","enrolled":true,"effective":true,"updatedAt":"2026-11-18T09:30:00.000Z"},
           {"labKey":"paused_lab","name":"Paused Lab","description":"Paused","state":"paused","enrolled":false,"effective":false,"updatedAt":null},
           {"labKey":"retired_lab","name":"Retired Lab","description":"Historical","state":"retired","enrolled":true,"effective":false,"updatedAt":"2026-11-01T00:00:00.000Z"}
         ]}
        """.trimIndent()

    private val staleWrite = """{"error":"Server Labs version changed","code":"server_labs_version_conflict","currentVersion":1}"""

    @Test
    fun readsTheServersReadModel() {
        val readback = parseServerLabsReadback(memberRead)!!
        assertEquals("srv-1", readback.serverId)
        assertEquals(0L, readback.version)
        assertFalse(readback.masterEnabled)
        assertEquals(false, readback.canManageAccess)
        assertEquals(false, readback.canManageEnrollments)
        assertEquals(listOf("open_lab", "paused_lab"), readback.labs.map { it.key })
        assertEquals(listOf(ServerLabState.Open, ServerLabState.Paused), readback.labs.map { it.state })
        assertEquals("Open Lab", readback.labs[0].name)
        assertEquals("Available", readback.labs[0].description)
    }

    @Test
    fun readsAWritesAnswerWithItsReadback() {
        val result = parseServerLabsMutation(enrollmentAnswer)!!
        assertTrue(result.applied)
        assertEquals(2L, result.readback.version)
        assertTrue(result.readback.masterEnabled)
        assertEquals(true, result.readback.canManageEnrollments)
        val open = result.readback.labs.first { it.key == "open_lab" }
        assertTrue(open.enrolled)
        assertTrue(open.effective)
        assertEquals(ServerLabState.Retired, result.readback.labs.first { it.key == "retired_lab" }.state)
    }

    @Test
    fun readsTheVersionConflict() {
        val error = parseServerLabsError(staleWrite)!!
        assertEquals(SERVER_LABS_VERSION_CONFLICT_CODE, error.code)
        assertEquals(1L, error.currentVersion)
        assertEquals("Server Labs version changed", error.message)
    }

    @Test
    fun permissionsTheServerLeavesOutStayUnknown() {
        val readback = parseServerLabsReadback("""{"serverId":"srv-1","accessEnabled":true,"version":4,"labs":[]}""")!!
        assertNull(readback.canManageAccess)
        assertNull(readback.canManageEnrollments)
        assertTrue(readback.labs.isEmpty())
    }

    @Test
    fun theRetiredAttachmentCommentsExperimentIsNeverALab() {
        val readback =
            parseServerLabsReadback(
                """{"serverId":"srv-1","accessEnabled":true,"version":1,"labs":[
                  {"labKey":"attachment_comments_v0","name":"Comments","description":"Old","state":"open","enrolled":true,"effective":true},
                  {"labKey":"open_lab","name":"Open Lab","description":"Available","state":"open","enrolled":false,"effective":false}]}""",
            )!!
        assertEquals(listOf("open_lab"), readback.labs.map { it.key })
    }

    @Test
    fun anUnknownStateIsKeptReadOnlyAndAnUnreadableRowIsSkipped() {
        val readback =
            parseServerLabsReadback(
                """{"serverId":"srv-1","accessEnabled":true,"version":1,"labs":[
                  {"labKey":"future_lab","name":"Future","description":"New state","state":"canary","enrolled":false,"effective":false},
                  {"labKey":"broken_lab","name":"Broken"}]}""",
            )!!
        assertEquals(listOf("future_lab"), readback.labs.map { it.key })
        assertEquals(ServerLabState.Unknown, readback.labs.single().state)
        // Rows came and none decoded: unreadable, not "no Labs".
        assertNull(parseServerLabsReadback("""{"serverId":"srv-1","accessEnabled":true,"version":1,"labs":[{"labKey":"x"}]}"""))
    }

    @Test
    fun writesSendEnabledAndTheExpectedVersion() {
        val body = Json.parseToJsonElement(encodeServerLabsMutationRequest(ServerLabsMutationRequest(enabled = true, expectedVersion = 3))).jsonObject
        assertEquals(setOf("enabled", "expectedVersion"), body.keys)
        assertTrue(body.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(3L, body.getValue("expectedVersion").jsonPrimitive.long)
    }

    @Test
    fun repositoryCallsTheServerScopedRoutes() = runTest {
        val client =
            RecordingLabsClient { request ->
                when (request.method) {
                    "GET" -> HomeNetworkResponse(code = 200, bodyText = memberRead)
                    else -> HomeNetworkResponse(code = 200, bodyText = enrollmentAnswer)
                }
            }
        val repository = NetworkServerLabsRepository(client)
        assertIs<ServerLabsLoadResult.Loaded>(repository.load("srv-1"))
        assertIs<ServerLabsMutationOutcome.Done>(repository.setAccess("srv-1", ServerLabsMutationRequest(true, 0)))
        assertIs<ServerLabsMutationOutcome.Done>(repository.setEnrollment("srv-1", "open_lab", ServerLabsMutationRequest(true, 1)))
        assertEquals(
            listOf("GET servers/srv-1/labs", "PATCH servers/srv-1/labs/access", "PUT servers/srv-1/labs/open_lab"),
            client.requests.map { "${it.method} ${it.path}" },
        )
        // X-Server-Id must equal the path's server.
        assertEquals(listOf("srv-1", "srv-1", "srv-1"), client.requests.map { it.serverId })
        assertTrue(client.requests.all { it.priority == DataRequestPriority.Foreground })
        assertEquals(1L, Json.parseToJsonElement(client.requests.last().body!!).jsonObject.getValue("expectedVersion").jsonPrimitive.long)
        // A key outside the server's LAB_KEY_RE is never sent.
        assertIs<ServerLabsMutationOutcome.Failed>(repository.setEnrollment("srv-1", "../access", ServerLabsMutationRequest(true, 1)))
        assertEquals(3, client.requests.size)
    }

    @Test
    fun repositoryMaps404ToUnavailableAndKeepsTheConflictsCodeAndTraceId() = runTest {
        val unavailable = NetworkServerLabsRepository(RecordingLabsClient { HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = """{"error":"Not found"}""") })
        assertEquals(ServerLabsLoadResult.Unavailable, unavailable.load("srv-1"))

        val conflict =
            NetworkServerLabsRepository(
                RecordingLabsClient {
                    HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = staleWrite, responseHeaders = mapOf("X-Trace-Id" to listOf(TRACE_ID)))
                },
            )
        val failed = assertIs<ServerLabsMutationOutcome.Failed>(conflict.setAccess("srv-1", ServerLabsMutationRequest(false, 0)))
        assertTrue(failed.failure.versionConflict)
        assertEquals("http_409", failed.failure.error.code)
        assertEquals(TRACE_ID, failed.failure.error.traceId)

        val serverError =
            NetworkServerLabsRepository(
                RecordingLabsClient {
                    HomeNetworkResponse(code = 500, bodyText = null, errorBodyText = """{"error":"Server Labs request failed","code":"server_labs_failed"}""", responseHeaders = mapOf("x-trace-id" to listOf(TRACE_ID)))
                },
            )
        val loadFailed = assertIs<ServerLabsLoadResult.Failed>(serverError.load("srv-1"))
        assertEquals("server_labs_failed", loadFailed.failure.serverCode)
        assertEquals(TRACE_ID, loadFailed.failure.error.traceId)
    }

    private data class LabsRequest(
        val priority: DataRequestPriority,
        val method: String,
        val path: String,
        val serverId: String?,
        val body: String?,
    )

    private class RecordingLabsClient(private val answer: (LabsRequest) -> HomeNetworkResponse) : RaftNetworkClient {
        val requests = mutableListOf<LabsRequest>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            val request = LabsRequest(priority, method, path.trim('/'), serverIdOverride?.value, bodyText)
            requests += request
            return answer(request)
        }
    }

    private companion object {
        const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    }
}
