package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Slack Bridge channel pairing against the shapes of botiverse/slock
 * `packages/shared/src/slackBridgeProvisioning.ts` (`slackBridgeProvisioningResponseSchema`,
 * strict objects) and `packages/server/src/routes/slackBridge.ts`.
 */
class SlackBridgeChannelPairingApiTest {
    private fun provisioning(
        stage: String = "health",
        pairs: String = "",
        preflight: String = "null",
    ): String =
        """{"protocolVersion":1,"snapshot":{"stage":"$stage","workspaceName":"Botiverse",""" +
            """"raftChannels":[{"id":"raft-1","name":"triage"},{"id":"raft-2","name":"general"}],""" +
            """"slackChannels":[{"id":"C1","name":"triage","privacyClass":"public","isMember":true},""" +
            """{"id":"C2","name":"general","privacyClass":"public","isMember":true},""" +
            """{"id":"C3","name":"secret","privacyClass":"private","isMember":true},""" +
            """{"id":"C4","name":"random","privacyClass":"public","isMember":false}],""" +
            """"channelPairs":[$pairs],"preflight":$preflight,""" +
            """"rawHealth":{"install":null,"credential":null,"bindings":[],"audiences":[],"lastVerifiedAt":null,""" +
            """"agentMentionSync":null,"failingSurface":null}},"oauthAuthority":null}"""

    private val passedPreflight =
        """{"state":"passed","checks":[{"id":"oauth","state":"passed"},{"id":"endpoint","state":"passed"},""" +
            """{"id":"scope","state":"passed"},{"id":"audience","state":"passed"}]}"""

    private val flagOn = """{"evaluations":[{"key":"slack_bridge_v0","enabled":true}]}"""

    @Test
    fun healthSnapshotOffersSameVisibilityChannelsWithTheirMarks() {
        val snapshot = parseSlackBridgeProvisioning(provisioning(pairs = """{"raftChannelId":"raft-2","slackChannelId":"C2","bindingEpoch":4}"""))!!
        val state = snapshot.toChannelSlackBridgeState("raft-1", visibility = "public")!!

        assertEquals(listOf("C1", "C2", "C4"), state.options.map { it.slackChannelId })
        assertEquals(true, state.options.single { it.slackChannelId == "C2" }.pairedElsewhere)
        assertEquals(true, state.options.single { it.slackChannelId == "C4" }.membershipRequired)
        assertNull(state.pairedSlackChannelId)
        assertNull(parseSlackBridgeProvisioning(provisioning(stage = "channels"))!!.toChannelSlackBridgeState("raft-1", "public"))
    }

    @Test
    fun flagOffHidesThePickWithoutLoadingTheSnapshot() = runBlocking {
        val network = FakeNetwork(mapOf("POST feature-flags/evaluate" to """{"evaluations":[{"key":"slack_bridge_v0","enabled":false}]}"""))

        assertNull(SlackBridgeChannelPairingRepository(network, ServerId("server-1")).loadState(ChannelId("raft-1"), "public"))
        assertEquals(listOf("POST feature-flags/evaluate"), network.calls)
        val body = Json.parseToJsonElement(network.bodies.single()!!).jsonObject
        assertEquals("server-1", body["serverId"]?.jsonPrimitive?.content)
        assertEquals("slack_bridge_v0", body["keys"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("web", body["platform"]?.jsonPrimitive?.content)
    }

    @Test
    fun pickingANewChannelSavesTheWholeSetThenPreflightsAndEnables() = runBlocking {
        val existing = """{"raftChannelId":"raft-2","slackChannelId":"C2","bindingEpoch":4}"""
        val paired = """$existing,{"raftChannelId":"raft-1","slackChannelId":"C1","bindingEpoch":1}"""
        val network =
            FakeNetwork(
                mapOf(
                    "GET slack-bridge/provisioning" to provisioning(pairs = existing),
                    "PUT slack-bridge/provisioning/channel-pairs" to provisioning(stage = "preflight", pairs = paired),
                    "POST slack-bridge/provisioning/preflight" to provisioning(stage = "enable", pairs = paired, preflight = passedPreflight),
                    "POST slack-bridge/provisioning/enable" to provisioning(pairs = paired, preflight = passedPreflight),
                )
            )

        val result = SlackBridgeChannelPairingRepository(network, ServerId("server-1")).apply(ChannelId("raft-1"), "public", "C1")

        assertEquals("C1", assertIs<DataResult.Content<ChannelSlackBridgeState?>>(result).data?.pairedSlackChannelId)
        assertEquals(
            listOf(
                "GET slack-bridge/provisioning",
                "PUT slack-bridge/provisioning/channel-pairs",
                "POST slack-bridge/provisioning/preflight",
                "POST slack-bridge/provisioning/enable",
            ),
            network.calls,
        )
        val pairs = Json.parseToJsonElement(network.bodies[1]!!).jsonObject["pairs"]!!.jsonArray
        assertEquals(
            listOf("raft-2" to "C2", "raft-1" to "C1"),
            pairs.map { it.jsonObject["raftChannelId"]!!.jsonPrimitive.content to it.jsonObject["slackChannelId"]!!.jsonPrimitive.content },
        )
        assertEquals(setOf("raftChannelId", "slackChannelId"), pairs.first().jsonObject.keys)
    }

    @Test
    fun disconnectingRemovesTheCurrentPairByItsBindingEpoch() = runBlocking {
        val mine = """{"raftChannelId":"raft-1","slackChannelId":"C1","bindingEpoch":7}"""
        val network =
            FakeNetwork(
                mapOf(
                    "GET slack-bridge/provisioning" to provisioning(pairs = mine),
                    "DELETE slack-bridge/provisioning/channel-pairs" to provisioning(),
                )
            )

        val result = SlackBridgeChannelPairingRepository(network, ServerId("server-1")).apply(ChannelId("raft-1"), "public", null)

        assertNull(assertIs<DataResult.Content<ChannelSlackBridgeState?>>(result).data?.pairedSlackChannelId)
        assertEquals(listOf("GET slack-bridge/provisioning", "DELETE slack-bridge/provisioning/channel-pairs"), network.calls)
        val removal = Json.parseToJsonElement(network.bodies[1]!!).jsonObject["pairs"]!!.jsonArray.single().jsonObject
        assertEquals(7L, removal["expectedBindingEpoch"]!!.jsonPrimitive.long)
    }

    @Test
    fun aPreflightThatDoesNotPassStopsBeforeEnable() = runBlocking {
        val failed = """{"state":"failed","checks":[{"id":"scope","state":"failed"}]}"""
        val network =
            FakeNetwork(
                mapOf(
                    "GET slack-bridge/provisioning" to provisioning(),
                    "PUT slack-bridge/provisioning/channel-pairs" to provisioning(stage = "preflight"),
                    "POST slack-bridge/provisioning/preflight" to provisioning(stage = "preflight", preflight = failed),
                )
            )

        val result = SlackBridgeChannelPairingRepository(network, ServerId("server-1")).apply(ChannelId("raft-1"), "public", "C1")

        assertEquals(SLACK_BRIDGE_PREFLIGHT_FAILED, assertIs<DataResult.Error>(result).error.code)
        assertEquals(false, network.calls.contains("POST slack-bridge/provisioning/enable"))
    }

    @Test
    fun controlPlaneErrorsKeepTheirCode() = runBlocking {
        val network =
            FakeNetwork(
                mapOf("GET slack-bridge/provisioning" to provisioning()),
                failures = mapOf("PUT slack-bridge/provisioning/channel-pairs" to (403 to """{"ok":false,"code":"slack_bridge_disabled"}""")),
            )

        val result = SlackBridgeChannelPairingRepository(network, ServerId("server-1")).apply(ChannelId("raft-1"), "public", "C1")

        assertEquals("slack_bridge_disabled", assertIs<DataResult.Error>(result).error.code)
        assertEquals(true, parseSlackBridgeFlagEnabled(flagOn))
    }

    @Test
    fun controlPlane4xxRefusalsPreserveServerMessageCodeAndTraceId() = runBlocking {
        val traceId = "62ac7fa79a3785a6bee414a074f82634"
        val network =
            FakeNetwork(
                mapOf("GET slack-bridge/provisioning" to provisioning()),
                failures =
                    mapOf(
                        "PUT slack-bridge/provisioning/channel-pairs" to
                            (409 to """{"ok":false,"code":"slack_channel_already_paired","error":"That Slack channel is already paired with #general."}"""),
                    ),
                traceId = traceId,
            )

        val result = SlackBridgeChannelPairingRepository(network, ServerId("server-1")).apply(ChannelId("raft-1"), "public", "C1")

        val err = assertIs<DataResult.Error>(result).error
        assertEquals("slack_channel_already_paired", err.code)
        assertEquals("slack_channel_already_paired", err.serverCode)
        assertEquals("That Slack channel is already paired with #general.", err.serverMessage)
        assertEquals(traceId, err.traceId)
        assertEquals(false, err.retryable)
    }

    @Test
    fun initialLoadRefusalInsideApplyPreservesServerMessageAndTraceId() = runBlocking {
        val traceId = "49a2bff61a2b3c4d5e6f708192a3b4c5"
        val network =
            FakeNetwork(
                emptyMap(),
                failures =
                    mapOf(
                        "GET slack-bridge/provisioning" to
                            (403 to """{"ok":false,"code":"slack_bridge_admin_required","error":"Only server admins can manage Slack Bridge."}"""),
                    ),
                traceId = traceId,
            )

        val result = SlackBridgeChannelPairingRepository(network, ServerId("server-1")).apply(ChannelId("raft-1"), "public", "C1")

        val err = assertIs<DataResult.Error>(result).error
        assertEquals("slack_bridge_admin_required", err.code)
        assertEquals("slack_bridge_admin_required", err.serverCode)
        assertEquals("Only server admins can manage Slack Bridge.", err.serverMessage)
        assertEquals(traceId, err.traceId)
    }

    @Test
    fun controlPlaneScrubs401And5xxAndTransportErrorBodiesAndKeepsTier1Classification() = runBlocking {
        val trace401 = "11111111111111111111111111111111"
        val trace403Unauthorized = "22222222222222222222222222222222"
        val trace500 = "33333333333333333333333333333333"
        val traceZero = "44444444444444444444444444444444"
        data class Case(
            val status: Int,
            val body: String,
            val headerTraceId: String,
            val expectedKey: ErrorPresentationKey,
            val expectedRetryable: Boolean,
            val expectedTraceId: String?,
        )
        val cases =
            listOf(
                Case(401, """{"ok":false,"code":"unauthorized","error":"jwt expired"}""", trace401, ErrorPresentationKey.SessionExpired, false, trace401),
                Case(403, """{"ok":false,"code":"unauthorized","error":"jwt expired"}""", trace403Unauthorized, ErrorPresentationKey.SessionExpired, false, trace403Unauthorized),
                Case(500, """{"ok":false,"code":"internal_error","error":"SQL panic"}""", trace500, ErrorPresentationKey.ServerUnavailable, true, trace500),
                Case(0, """{"error":"synthetic transport"}""", traceZero, ErrorPresentationKey.NetworkConnectionFailed, true, null),
            )
        for (case in cases) {
            val network =
                FakeNetwork(
                    mapOf("GET slack-bridge/provisioning" to provisioning()),
                    failures = mapOf("PUT slack-bridge/provisioning/channel-pairs" to (case.status to case.body)),
                    traceId = case.headerTraceId,
                )
            val result = SlackBridgeChannelPairingRepository(network, ServerId("server-1")).apply(ChannelId("raft-1"), "public", "C1")
            val err = assertIs<DataResult.Error>(result).error
            assertNull(err.serverMessage, "status=${case.status} must scrub serverMessage")
            assertEquals(case.expectedRetryable, err.retryable, "status=${case.status} retryable")
            assertEquals(case.expectedTraceId, err.traceId, "status=${case.status} traceId")
            assertEquals(case.expectedKey, err.errorPresentation().key, "status=${case.status} presentation key")
            assertNull(err.errorPresentation().rawServerMessage, "status=${case.status} rawServerMessage")
        }
    }

    private class FakeNetwork(
        private val responses: Map<String, String>,
        private val failures: Map<String, Pair<Int, String>> = emptyMap(),
        private val traceId: String? = null,
    ) : RaftNetworkClient {
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<String?>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            val key = "$method ${path.trim('/')}"
            calls += key
            bodies += bodyText
            val headers = traceId?.let { mapOf("X-Trace-Id" to listOf(it)) }.orEmpty()
            failures[key]?.let { (code, body) ->
                return HomeNetworkResponse(code = code, bodyText = null, errorBodyText = body, responseHeaders = headers)
            }
            return HomeNetworkResponse(code = 200, bodyText = responses[key] ?: error("unexpected $key"), responseHeaders = headers)
        }
    }
}
