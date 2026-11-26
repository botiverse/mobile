package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AgentVoiceApiTest {
    @AfterTest
    fun tearDownRegistry() {
        RaftAgentVoiceCallEngineRegistry.unregister()
    }

    @Test
    fun featureFlagIsRegisteredAndDefaultOff() {
        assertEquals("agent_voice_v1", AGENT_VOICE_FEATURE_FLAG_KEY)
        assertTrue(AGENT_VOICE_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(AGENT_VOICE_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
    }

    @Test
    fun parsesVoiceSettingsAndHandlesNotFoundAsNull() = runTest {
        val client = FakeVoiceNetworkClient()
        client.nextResponse = HomeNetworkResponse(code = 200, bodyText = """{"askable":true,"callable":false}""")
        val api = RaftAgentVoiceSettingsApi(client)

        val loaded = api.readSettings(agentId = "agent-1", serverId = "srv-1")
        assertIs<DataResult.Content<AgentVoiceSettings?>>(loaded)
        assertEquals(AgentVoiceSettings(askable = true, callable = false), loaded.data)
        assertEquals("agents/agent-1/voice-settings", client.lastPath)
        assertEquals("GET", client.lastMethod)
        assertEquals(ServerId("srv-1"), client.lastServerIdOverride)

        client.nextResponse = HomeNetworkResponse(code = 404, bodyText = """{"code":"agent_not_found"}""")
        val hidden = api.readSettings(agentId = "agent-1", serverId = "srv-1")
        assertIs<DataResult.Content<AgentVoiceSettings?>>(hidden)
        assertNull(hidden.data)
    }

    @Test
    fun updatesSingleVoiceSettingFieldAndPreservesTraceIdOnError() = runTest {
        val client = FakeVoiceNetworkClient()
        client.nextResponse = HomeNetworkResponse(code = 200, bodyText = """{"askable":false,"callable":true}""")
        val api = RaftAgentVoiceSettingsApi(client)

        val updated = api.updateSettings(agentId = "agent-1", callable = true, serverId = "srv-1")
        assertIs<DataResult.Content<AgentVoiceSettings>>(updated)
        assertEquals(AgentVoiceSettings(askable = false, callable = true), updated.data)
        assertEquals("PUT", client.lastMethod)
        assertEquals("""{"callable":true}""", client.lastBodyText)

        client.nextResponse =
            HomeNetworkResponse(
                code = 500,
                bodyText = null,
                errorBodyText = """{"error":"Server busy","code":"internal_error"}""",
                responseHeaders = mapOf("X-Trace-Id" to listOf("4bf92f3577b34da6a3ce929d0e0e4736")),
            )
        val failed = api.updateSettings(agentId = "agent-1", askable = true, serverId = "srv-1")
        assertIs<DataResult.Error>(failed)
        assertEquals("Server busy", failed.error.serverMessage)
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", failed.error.traceId)
    }

    @Test
    fun parsesVoiceQuestionsAndHandlesNotFoundAsNull() = runTest {
        val client = FakeVoiceNetworkClient()
        client.nextResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {
                      "questions": [
                        {
                          "id": "q-1",
                          "at": "2026-11-26T10:15:00.000Z",
                          "asker": {"id": "u-1", "name": "Alice"},
                          "app": {"id": "app-1", "name": "Voice Companion"}
                        },
                        {
                          "at": "2026-11-25T08:00:00.000Z",
                          "asker": null,
                          "app": null
                        }
                      ]
                    }
                    """.trimIndent(),
            )
        val api = RaftAgentVoiceSettingsApi(client)
        val result = api.readQuestions(agentId = "agent-1", serverId = "srv-1")
        assertIs<DataResult.Content<List<AgentVoiceQuestion>?>>(result)
        val list = assertNotNull(result.data)
        assertEquals(2, list.size)
        assertEquals("q-1", list[0].id)
        assertEquals("Alice", list[0].asker?.name)
        assertEquals("Voice Companion", list[0].app?.name)
        assertEquals("q-1", list[0].stableKey)
        assertNull(list[1].asker)
        assertNull(list[1].app)
        assertEquals("2026-11-25T08:00:00.000Z-former", list[1].stableKey)

        client.nextResponse = HomeNetworkResponse(code = 404, bodyText = """{"code":"agent_not_found"}""")
        val hidden = api.readQuestions(agentId = "agent-1", serverId = "srv-1")
        assertIs<DataResult.Content<List<AgentVoiceQuestion>?>>(hidden)
        assertNull(hidden.data)
    }

    @Test
    fun mapsVoiceCallStartProblemsMatchingWebContract() {
        assertEquals(AgentVoiceCallProblem.NotAllowed, startProblemOf(404, """{"code":"agent_not_found"}"""))
        assertEquals(AgentVoiceCallProblem.RateLimited, startProblemOf(429, """{"code":"voice_rate_limited"}"""))
        assertEquals(AgentVoiceCallProblem.Unavailable, startProblemOf(409, """{"code":"voice_call_unavailable"}"""))
        assertEquals(AgentVoiceCallProblem.AgentOffline, startProblemOf(409, """{"code":"agent_offline"}"""))
        assertEquals(AgentVoiceCallProblem.UnsupportedRuntime, startProblemOf(409, """{"code":"unsupported_runtime"}"""))
        assertEquals(AgentVoiceCallProblem.Failed, startProblemOf(409, """{"code":"something_else"}"""))
        assertEquals(AgentVoiceCallProblem.Failed, startProblemOf(500, """{"error":"boom"}"""))
    }

    @Test
    fun parsesValidCallStartAndRejectsMalformedGrants() {
        val validCallId = "123e4567-e89b-12d3-a456-426614174000"
        val parsed =
            parseAgentVoiceCallStartResult(
                """{"callId":"$validCallId","room":{"url":"wss://live.example.test","token":"tok-1","expiresAt":"2026-11-26T13:15:00.000Z"}}""",
            )
        assertNotNull(parsed)
        assertEquals(validCallId, parsed.callId)
        assertEquals("wss://live.example.test", parsed.room.url)
        assertEquals("tok-1", parsed.room.token)
        assertTrue(parsed.room.expiresAtEpochMs > 0L)

        // Non-UUID callId rejected
        assertNull(
            parseAgentVoiceCallStartResult(
                """{"callId":"not-a-uuid","room":{"url":"wss://live.example.test","token":"tok-1","expiresAt":"2026-11-26T13:15:00.000Z"}}""",
            ),
        )
        // Missing token or invalid expiresAt rejected
        assertNull(
            parseAgentVoiceCallStartResult(
                """{"callId":"$validCallId","room":{"url":"wss://live.example.test","token":"","expiresAt":"2026-11-26T13:15:00.000Z"}}""",
            ),
        )
        assertNull(
            parseAgentVoiceCallStartResult(
                """{"callId":"$validCallId","room":{"url":"wss://live.example.test","token":"tok-1","expiresAt":"not-a-date"}}""",
            ),
        )
    }

    @Test
    fun callApiSendsServerIdOverrideForStartRenewAndHangUp() = runTest {
        val callId = "123e4567-e89b-12d3-a456-426614174000"
        val client = FakeVoiceNetworkClient()
        val api = RaftAgentVoiceCallNetworkApi(client)

        client.nextResponse =
            HomeNetworkResponse(
                code = 201,
                bodyText = """{"callId":"$callId","room":{"url":"wss://live.example.test","token":"tok-1","expiresAt":"2026-11-26T13:15:00.000Z"}}""",
            )
        val started = api.startCall(agentId = "agent-1", serverId = "srv-origin")
        assertIs<AgentVoiceCallStartOutcome.Started>(started)
        assertEquals("agents/agent-1/voice/calls", client.lastPath)
        assertEquals("POST", client.lastMethod)
        assertEquals("{}", client.lastBodyText)
        assertEquals(ServerId("srv-origin"), client.lastServerIdOverride)

        client.nextResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"callId":"$callId","room":{"url":"wss://live.example.test","token":"tok-2","expiresAt":"2026-11-26T13:20:00.000Z"}}""",
            )
        val renewed = api.renewToken(agentId = "agent-1", callId = callId, serverId = "srv-origin")
        assertNotNull(renewed)
        assertEquals("tok-2", renewed.room.token)
        assertEquals("agents/agent-1/voice/calls/$callId/token", client.lastPath)
        assertEquals(ServerId("srv-origin"), client.lastServerIdOverride)

        client.nextResponse = HomeNetworkResponse(code = 204, bodyText = null)
        assertTrue(api.hangUp(agentId = "agent-1", callId = callId, serverId = "srv-origin"))
        assertEquals("agents/agent-1/voice/calls/$callId/hang-up", client.lastPath)
        assertEquals(ServerId("srv-origin"), client.lastServerIdOverride)
    }

    @Test
    fun eligibilityPredicateMirrorsWebRulesAndEngineSupport() {
        fun check(
            featureEnabled: Boolean = true,
            serverId: String? = "srv-1",
            serverRole: String? = "member",
            agentId: String? = "agent-1",
            deletedAt: String? = null,
            managedByAppInstallId: String? = null,
            agentStatus: String? = "active",
            machineId: String? = "machine-1",
            computerOffline: Boolean = false,
            engineSupported: Boolean = true,
        ): Boolean =
            agentVoiceCallEntryEligibility(
                featureEnabled = featureEnabled,
                serverId = serverId,
                serverRole = serverRole,
                agentId = agentId,
                deletedAt = deletedAt,
                managedByAppInstallId = managedByAppInstallId,
                agentStatus = agentStatus,
                machineId = machineId,
                computerOffline = computerOffline,
                engineSupported = engineSupported,
            )

        assertTrue(check())
        assertTrue(check(serverRole = "owner"))
        assertTrue(check(serverRole = "admin"))
        assertTrue(check(agentStatus = "idle"))
        assertTrue(check(agentStatus = null))

        assertFalse(check(featureEnabled = false), "flag off")
        assertFalse(check(engineSupported = false), "native engine unsupported")
        assertFalse(check(serverId = null), "missing server")
        assertFalse(check(serverRole = null), "unknown role")
        assertFalse(check(serverRole = "guest"), "guest blocked")
        assertFalse(check(serverRole = "Guest"), "guest case-insensitive")
        assertFalse(check(agentId = ""), "missing agent")
        assertFalse(check(deletedAt = "2026-11-26T00:00:00Z"), "deleted agent")
        assertFalse(check(managedByAppInstallId = "install-1"), "app bot blocked")
        assertFalse(check(agentStatus = "stopped"), "stopped agent")
        assertFalse(check(agentStatus = "Stopped"), "stopped case-insensitive")
        assertFalse(check(machineId = null), "no computer")
        assertFalse(check(machineId = "  "), "blank computer id")
        assertFalse(check(computerOffline = true), "computer offline")
    }

    @Test
    fun engineRegistryTracksSupportAndUnregistersCleanly() {
        assertFalse(RaftAgentVoiceCallEngineRegistry.isSupported())
        val supported =
            object : RaftAgentVoiceCallEngine {
                override val isSupported: Boolean = true
                override fun connect(
                    callId: String,
                    url: String,
                    token: String,
                    muted: Boolean,
                    speakerOn: Boolean,
                    listener: RaftAgentVoiceEngineListener,
                ) = Unit
                override fun setMuted(muted: Boolean) = Unit
                override fun setSpeakerOn(speakerOn: Boolean) = Unit
                override fun updateToken(token: String) = Unit
                override fun disconnect() = Unit
            }
        RaftAgentVoiceCallEngineRegistry.register(supported)
        assertTrue(RaftAgentVoiceCallEngineRegistry.isSupported())
        assertEquals(supported, RaftAgentVoiceCallEngineRegistry.currentOrNull())

        RaftAgentVoiceCallEngineRegistry.unregister()
        assertFalse(RaftAgentVoiceCallEngineRegistry.isSupported())
        assertNull(RaftAgentVoiceCallEngineRegistry.currentOrNull())
    }

    private class FakeVoiceNetworkClient : RaftNetworkClient {
        var lastPath: String? = null
        var lastMethod: String? = null
        var lastBodyText: String? = null
        var lastServerIdOverride: ServerId? = null
        var nextResponse: HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = "{}")

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            lastPath = path
            lastMethod = method
            lastBodyText = bodyText
            lastServerIdOverride = serverIdOverride
            return nextResponse
        }
    }
}
