package build.raft.app.data

import build.raft.app.channelSettingsCanStopAllAgents
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Payloads copied from botiverse/slock AGENT_CONTROLS_SLOCK_SHA:
// packages/server/src/routes/channels.ts `POST /:id/stop-all-agents` and
// `POST /:id/resume-all-agents`; web packages/web/src/components/message/SOSDialog.tsx.
class ChannelSosStoreTest {
    private val channelId = ChannelId("channel-1")
    private val stopPath = "channels/channel-1/stop-all-agents"
    private val resumePath = "channels/channel-1/resume-all-agents"

    @Test
    fun parsesStopAndResumeResponses() {
        assertEquals(
            ChannelStopAllAgentsResult(stopped = 1, total = 2),
            parseSharedChannelStopAllAgents(
                """{"ok":true,"stopped":1,"total":2,"results":[{"agentId":"a1","ok":true},{"agentId":"a2","ok":false,"error":"Agent not found"}]}"""
            )
        )
        val resume =
            parseSharedChannelResumeAllAgents(
                """{"ok":false,"started":1,"total":3,"results":[{"agentId":"a1","ok":true},{"agentId":"a2","ok":false,"reason":"start_in_flight"},{"agentId":"a3","ok":false,"error":"boom"}]}"""
            )
        assertEquals(1, resume?.started)
        assertEquals(listOf(ChannelResumeMiss("a2", "start_in_flight"), ChannelResumeMiss("a3", null)), resume?.missed)
        assertNull(parseSharedChannelStopAllAgents("""{"ok":true}"""))
    }

    @Test
    fun resumeBodyOmitsAgentIdsUnlessRetrying() {
        val first = Json.parseToJsonElement(channelResumeAllAgentsRequestBody("p", null)).jsonObject
        assertEquals(setOf("prompt"), first.keys)
        val retry = Json.parseToJsonElement(channelResumeAllAgentsRequestBody("p", listOf("a2"))).jsonObject
        assertEquals("a2", retry.getValue("agentIds").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun stopThenResumeClosesWhenEveryAgentGotTheGuidance() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("POST", stopPath, 200, """{"ok":true,"stopped":2,"total":2,"results":[]}""")
        network.respond("POST", resumePath, 200, """{"ok":true,"started":2,"total":2,"results":[{"agentId":"a1","ok":true},{"agentId":"a2","ok":true}]}""")
        val store = ChannelSosStore(channelId, "design", RaftAgentControlsApi(network), this)

        store.dispatch(ChannelSosIntent.Stop)
        runCurrent()
        assertEquals(ChannelSosPhase.Stopped, store.state.value.phase)
        assertFalse(store.state.value.canResume)

        store.dispatch(ChannelSosIntent.UpdateGuidance("Only touch the frontend"))
        store.dispatch(ChannelSosIntent.Resume)
        runCurrent()

        assertTrue(store.state.value.closed)
        val resumeRequest = network.requests.last()
        assertEquals(DataRequestPriority.Foreground, resumeRequest.priority)
        val prompt = Json.parseToJsonElement(resumeRequest.bodyText!!).jsonObject.getValue("prompt").jsonPrimitive.content
        assertTrue(prompt.startsWith("[SOS] The user has emergency-stopped all agents in #design"))
        assertTrue("Only touch the frontend" in prompt)
    }

    @Test
    fun resumeShortfallKeepsTheDialogAndRetriesOnlyTheMissedAgents() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("POST", stopPath, 200, """{"ok":true,"stopped":3,"total":3,"results":[]}""")
        network.respond(
            "POST",
            resumePath,
            200,
            """{"ok":false,"started":1,"total":3,"results":[{"agentId":"a1","ok":true},{"agentId":"a2","ok":false,"reason":"start_in_flight"},{"agentId":"a3","ok":false,"error":"boom"}]}"""
        )
        network.respond("POST", resumePath, 200, """{"ok":true,"started":2,"total":2,"results":[]}""")
        val store = ChannelSosStore(channelId, "design", RaftAgentControlsApi(network), this)
        store.dispatch(ChannelSosIntent.Stop)
        runCurrent()
        store.dispatch(ChannelSosIntent.UpdateGuidance("fix it"))
        store.dispatch(ChannelSosIntent.Resume)
        runCurrent()

        assertEquals(ChannelSosResumeShortfall(starting = 1, failed = 1, agentIds = listOf("a2", "a3")), store.state.value.shortfall)
        assertFalse(store.state.value.closed)

        store.dispatch(ChannelSosIntent.Resume)
        runCurrent()
        val retryIds = Json.parseToJsonElement(network.requests.last().bodyText!!).jsonObject.getValue("agentIds").jsonArray
        assertEquals(listOf("a2", "a3"), retryIds.map { it.jsonPrimitive.content })
        assertTrue(store.state.value.closed)
    }

    @Test
    fun failedStopStaysOnConfirmWithAnError() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond(
            "POST",
            stopPath,
            403,
            """{"error":"The `controlAgentRuntime` capability is required to stop all agents in a channel"}"""
        )
        val store = ChannelSosStore(channelId, "design", RaftAgentControlsApi(network), this)

        store.dispatch(ChannelSosIntent.Stop)
        runCurrent()

        assertEquals(ChannelSosPhase.Confirm, store.state.value.phase)
        assertFalse(store.state.value.stopping)
        assertEquals("http_403", store.state.value.errorCode)
    }

    @Test
    fun failedResumeKeepsTheGuidance() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("POST", stopPath, 200, """{"ok":true,"stopped":1,"total":1,"results":[]}""")
        network.respond("POST", resumePath, 500, """{"error":"Failed to resume agents"}""")
        val store = ChannelSosStore(channelId, "design", RaftAgentControlsApi(network), this)
        store.dispatch(ChannelSosIntent.Stop)
        runCurrent()
        store.dispatch(ChannelSosIntent.UpdateGuidance("keep me"))
        store.dispatch(ChannelSosIntent.Resume)
        runCurrent()

        assertEquals("keep me", store.state.value.guidance)
        assertEquals("http_500", store.state.value.errorCode)
        assertFalse(store.state.value.closed)
    }

    @Test
    fun entryFollowsJoinAndControlAgentRuntime() {
        val channel = HomeChannelRow(threadId = ThreadId("t1"), channelId = channelId, name = "design")
        assertTrue(channelSettingsCanStopAllAgents(channel, "member"))
        assertFalse(channelSettingsCanStopAllAgents(channel, "guest"))
        assertFalse(channelSettingsCanStopAllAgents(channel.copy(joined = false), "owner"))
        assertFalse(channelSettingsCanStopAllAgents(channel.copy(kind = ConversationKind.DirectMessage), "owner"))
    }
}
