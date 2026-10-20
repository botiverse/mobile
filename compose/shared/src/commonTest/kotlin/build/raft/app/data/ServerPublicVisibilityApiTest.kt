package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner "Public access" routes, pinned to botiverse/slock@9df0d1b3f
 * packages/server/src/routes/servers.ts:4524-4740 and routes/featureFlags.ts:28-75.
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

    /** Shape of servers.ts:4577-4583 `res.json({...})`, with exposedChannels rows from :4566-4576. */
    private val canonicalReadback =
        """
        {"publiclyVisible":false,"publicGuestJoinEnabled":false,"publicSearchEnabled":true,"slug":"acme",
         "exposedChannels":[
           {"id":"c-1","name":"announcements","description":"Product news"},
           {"id":"c-2","name":"general","description":null}
         ]}
        """.trimIndent()

    private fun guestGate(enabled: Boolean) =
        HomeNetworkResponse(200, """{"evaluations":[{"key":"server_guest_v0","enabled":$enabled,"reason":"rule"}]}""")

    @Test
    fun loadParsesReadbackAndEvaluatesTheGuestGateAsTheServerDoes() = runBlocking {
        val client = RecordingPublicVisibilityNetworkClient(
            mapOf(
                "GET $visibilityPath" to HomeNetworkResponse(200, canonicalReadback),
                "POST feature-flags/evaluate" to guestGate(enabled = true),
            )
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
        assertTrue(loaded.guestJoinApplicable)
        val gate = client.requests.single { it.path == "feature-flags/evaluate" }
        assertEquals(serverId, gate.serverId)
        assertEquals("web", gate.body?.get("platform")?.jsonPrimitive?.content)
        assertEquals(serverId.value, gate.body?.get("serverId")?.jsonPrimitive?.content)
        assertEquals(listOf("server_guest_v0"), gate.body?.get("keys")?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(serverId, client.requests.single { it.path == visibilityPath }.serverId)
    }

    @Test
    fun guestJoinIsNotApplicableWhenTheGateIsOffOrUnreadable() = runBlocking {
        for (gateResponse in listOf(guestGate(enabled = false), HomeNetworkResponse(500, null), HomeNetworkResponse(200, "{}"))) {
            val client = RecordingPublicVisibilityNetworkClient(
                mapOf(
                    "GET $visibilityPath" to HomeNetworkResponse(200, canonicalReadback),
                    "POST feature-flags/evaluate" to gateResponse,
                )
            )
            val loaded = assertIs<ServerPublicVisibilityLoadResult.Loaded>(
                NetworkServerPublicVisibilityRepository(client).load(serverId)
            )
            assertFalse(loaded.guestJoinApplicable, "gate response $gateResponse")
        }
    }

    @Test
    fun gateOffOrNonOwnerHidesTheSectionAndOtherFailuresSurfaceTheServerError() = runBlocking {
        for (code in listOf(404, 403)) {
            val client = RecordingPublicVisibilityNetworkClient(
                mapOf("GET $visibilityPath" to HomeNetworkResponse(code, """{"error":"Not found"}"""))
            )
            assertEquals(ServerPublicVisibilityLoadResult.Unavailable, NetworkServerPublicVisibilityRepository(client).load(serverId))
            assertTrue(client.requests.none { it.path == "feature-flags/evaluate" }, "no gate lookup after $code")
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
                "PATCH $visibilityPath" to
                    HomeNetworkResponse(200, """{"publiclyVisible":false,"publicGuestJoinEnabled":false,"publicSearchEnabled":true}"""),
                "PATCH servers/${serverId.value}/public-search" to HomeNetworkResponse(200, """{"publicSearchEnabled":false}"""),
                "PATCH servers/${serverId.value}/public-guest-join" to HomeNetworkResponse(200, """{"publicGuestJoinEnabled":true}"""),
            )
        )
        val repository = NetworkServerPublicVisibilityRepository(client)

        val visibility = assertIs<ServerPublicMutationResult.Success<ServerPublicVisibilityUpdate>>(
            repository.setPubliclyVisible(serverId, publiclyVisible = false)
        ).value
        assertEquals(ServerPublicVisibilityUpdate(publiclyVisible = false, publicGuestJoinEnabled = false, publicSearchEnabled = true), visibility)
        assertEquals(ServerPublicMutationResult.Success(false), repository.setPublicSearchEnabled(serverId, publicSearchEnabled = false))
        assertEquals(ServerPublicMutationResult.Success(true), repository.setPublicGuestJoinEnabled(serverId, publicGuestJoinEnabled = true))

        val patches = client.requests.filter { it.method == "PATCH" }
        assertEquals(
            listOf(
                visibilityPath to ("publiclyVisible" to false),
                "servers/${serverId.value}/public-search" to ("publicSearchEnabled" to false),
                "servers/${serverId.value}/public-guest-join" to ("publicGuestJoinEnabled" to true),
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
                "PATCH servers/${serverId.value}/public-guest-join" to
                    HomeNetworkResponse(409, """{"error":"Public access must be enabled before visitors can join as Guest"}"""),
            )
        )

        val failed = assertIs<ServerPublicMutationResult.Failed>(
            NetworkServerPublicVisibilityRepository(client).setPublicGuestJoinEnabled(serverId, publicGuestJoinEnabled = true)
        )

        assertEquals(409, failed.code)
        assertEquals("Public access must be enabled before visitors can join as Guest", failed.serverMessage)
    }

    @Test
    fun readbackParserRejectsPayloadsMissingRequiredFields() {
        assertNull(parseSharedServerPublicVisibility("""{"publiclyVisible":true,"slug":"acme","exposedChannels":[]}"""))
        assertNull(parseSharedServerPublicVisibilityUpdate("""{"publiclyVisible":true}"""))
        assertNull(parseSharedServerPublicVisibilityErrorText("""{"error":"   "}"""))
    }
}
