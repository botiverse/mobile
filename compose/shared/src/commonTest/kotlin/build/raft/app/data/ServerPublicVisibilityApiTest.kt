package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner "Public access" routes (botiverse/slock packages/server/src/routes/servers.ts
 * `/:id/public-visibility` and `/:id/public-search`).
 */
class ServerPublicVisibilityApiTest {
    private data class RecordedRequest(
        val method: String,
        val path: String,
        val priority: DataRequestPriority,
        val serverId: ServerId?,
        val body: JsonObject?,
    )

    private class RecordingPublicVisibilityNetworkClient(
        private val responses: Map<String, HomeNetworkResponse>,
    ) : RaftNetworkClient {
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            requests += RecordedRequest(
                method = method,
                path = path,
                priority = priority,
                serverId = serverIdOverride,
                body = bodyText?.let { Json.parseToJsonElement(it).jsonObject },
            )
            return responses["$method $path"] ?: HomeNetworkResponse(404, """{"error":"Not found"}""")
        }
    }

    private val serverId = ServerId("11111111-1111-4111-8111-111111111111")
    private val visibilityPath = "servers/${serverId.value}/public-visibility"

    /**
     * Shape of the GET's `res.json({ publiclyVisible, publicSearchEnabled, slug, exposedChannels })`
     * (botiverse/slock release/v1.28.0 servers.ts; `publicGuestJoinEnabled` retired in #13829).
     */
    private val canonicalReadback =
        """
        {"publiclyVisible":false,"publicSearchEnabled":true,"slug":"acme",
         "exposedChannels":[
           {"id":"c-1","name":"announcements","description":"Product news"},
           {"id":"c-2","name":"general","description":null}
         ]}
        """.trimIndent()

    @Test
    fun loadParsesTheReadbackWithoutAskingForFeatureFlags() = runBlocking {
        val client = RecordingPublicVisibilityNetworkClient(
            mapOf("GET $visibilityPath" to HomeNetworkResponse(200, canonicalReadback))
        )

        val loaded = assertIs<ServerPublicVisibilityLoadResult.Loaded>(
            NetworkServerPublicVisibilityRepository(client).load(serverId)
        )

        assertFalse(loaded.readback.publiclyVisible)
        assertTrue(loaded.readback.publicSearchEnabled)
        assertEquals("acme", loaded.readback.slug)
        assertEquals(
            listOf(ServerPublicExposedChannel("c-1", "announcements", "Product news"), ServerPublicExposedChannel("c-2", "general", null)),
            loaded.readback.exposedChannels
        )
        assertEquals(listOf(visibilityPath), client.requests.map { it.path })
        assertEquals(serverId, client.requests.single().serverId)
    }

    @Test
    fun gateOffOrNonOwnerHidesTheSectionAndOtherFailuresSurfaceTheServerError() = runBlocking {
        for (code in listOf(404, 403)) {
            val client = RecordingPublicVisibilityNetworkClient(
                mapOf("GET $visibilityPath" to HomeNetworkResponse(code, """{"error":"Not found"}"""))
            )
            assertEquals(ServerPublicVisibilityLoadResult.Unavailable, NetworkServerPublicVisibilityRepository(client).load(serverId))
        }
        val failing = RecordingPublicVisibilityNetworkClient(
            mapOf("GET $visibilityPath" to HomeNetworkResponse(500, null, errorBodyText = """{"error":"Failed to read public visibility"}"""))
        )
        assertEquals(
            ServerPublicVisibilityLoadResult.Failed("Failed to read public visibility"),
            NetworkServerPublicVisibilityRepository(failing).load(serverId)
        )
    }

    @Test
    fun patchRoutesSendNamedBooleanBodiesAndParseTheirResponses() = runBlocking {
        val client = RecordingPublicVisibilityNetworkClient(
            mapOf(
                "PATCH $visibilityPath" to HomeNetworkResponse(200, """{"publiclyVisible":false,"publicSearchEnabled":true}"""),
                "PATCH servers/${serverId.value}/public-search" to HomeNetworkResponse(200, """{"publicSearchEnabled":false}"""),
            )
        )
        val repository = NetworkServerPublicVisibilityRepository(client)

        val visibility = assertIs<ServerPublicMutationResult.Success<ServerPublicVisibilityUpdate>>(
            repository.setPubliclyVisible(serverId, publiclyVisible = false)
        ).value
        assertEquals(ServerPublicVisibilityUpdate(publiclyVisible = false, publicSearchEnabled = true), visibility)
        assertEquals(ServerPublicMutationResult.Success(false), repository.setPublicSearchEnabled(serverId, publicSearchEnabled = false))

        val patches = client.requests.filter { it.method == "PATCH" }
        assertEquals(
            listOf(
                visibilityPath to ("publiclyVisible" to false),
                "servers/${serverId.value}/public-search" to ("publicSearchEnabled" to false),
            ),
            patches.map { request ->
                val field = request.body!!.entries.single()
                request.path to (field.key to field.value.jsonPrimitive.boolean)
            }
        )
        assertTrue(patches.all { it.priority == DataRequestPriority.Foreground && it.serverId == serverId })
    }

    @Test
    fun patchFailureKeepsTheStatusAndServerMessage() = runBlocking {
        val client = RecordingPublicVisibilityNetworkClient(
            mapOf(
                "PATCH servers/${serverId.value}/public-search" to
                    HomeNetworkResponse(409, """{"error":"Public access must be enabled first"}"""),
            )
        )

        val failed = assertIs<ServerPublicMutationResult.Failed>(
            NetworkServerPublicVisibilityRepository(client).setPublicSearchEnabled(serverId, publicSearchEnabled = true)
        )

        assertEquals(409, failed.code)
        assertEquals("Public access must be enabled first", failed.serverMessage)
    }

    @Test
    fun readbackParserRejectsPayloadsMissingRequiredFields() {
        assertNull(parseSharedServerPublicVisibility("""{"publiclyVisible":true,"slug":"acme","exposedChannels":[]}"""))
        assertNull(parseSharedServerPublicVisibilityUpdate("""{"publicSearchEnabled":true}"""))
        assertNull(parseSharedServerPublicVisibilityErrorText("""{"error":"   "}"""))
    }
}
