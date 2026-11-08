package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAgentUpdatedSignalsTest {
    @Test
    fun agentUpdatedNamesTheAgentWhoseProfileReReadsItsDetail() {
        // botiverse/slock agentOrchestrator.notifyModelLimitsChanged (#13492) sends `agent:updated { agentId }`
        // when an agent's modelLimits signal may have changed; profile edits send the same event.
        val mapper = RealtimeEventMapper()
        assertEquals("agent-1", mapper.map(RealtimeSocketEvents.AgentUpdated, """{"agentId":"agent-1"}""")?.updatedAgentIdOrNull())
        assertNull(mapper.map(RealtimeSocketEvents.AgentSession, """{"agentId":"agent-1","sessionId":"s"}""")?.updatedAgentIdOrNull())
    }

    @Test
    fun publishedSignalReachesTheOpenProfileOfThatAgentOnly() = runTest {
        val signals = RaftAgentUpdatedSignals()
        // Virtual time: a signal that never comes fails at once instead of hanging.
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(1_000) { signals.events.first() } }
        signals.publish(serverId = "server-1", agentId = "agent-1")
        val signal = assertNotNull(received.await())

        assertEquals(RaftAgentUpdatedSignal("server-1", "agent-1"), signal)
        assertTrue(signal.isFor(serverId = "server-1", agentId = "agent-1"))
        assertFalse(signal.isFor(serverId = "server-1", agentId = "agent-2"))
        assertFalse(signal.isFor(serverId = "server-2", agentId = "agent-1"))
        assertFalse(signal.isFor(serverId = null, agentId = "agent-1"))
    }
}
