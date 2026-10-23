package build.raft.app.data

import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AgentTypingPhase
import build.raft.app.model.AgentTypingUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `agent:typing` wire contract, botiverse/slock staging `packages/shared/src/agentTyping.ts`
 * (`AGENT_TYPING_SOCKET_EVENT`, `AgentTypingEvent`). The payloads are the ones
 * `packages/server/src/services/agentTypingService.test.ts` asserts ("start: one emit to the channel
 * room with the contract payload", "a thread target emits to the PARENT channel room with the root
 * message") and the web's `readAgentTypingEvent` rules.
 */
class AgentTypingContractTest {
    private val mapper = RealtimeEventMapper()

    private fun map(payload: String) = mapper.map(RealtimeSocketEvents.AgentTyping, payload)

    @Test fun theSocketSubscribesToTheEvent() {
        assertEquals("agent:typing", RealtimeSocketEvents.AgentTyping)
        assertEquals("agent:typing", GeneratedRealtimeContract.SocketEvent.AgentTyping)
        assertTrue(RealtimeSocketEvents.AgentTyping in RealtimeSocketEvents.ServerWireEvents)
    }

    @Test fun canonicalChannelStart() {
        val event =
            assertIs<AgentTypingUpdated>(
                map(
                    """{"agentId":"agent-1","serverId":"srv-1","channelId":"chan-general","parentMessageId":null,
                        "callId":"c1","state":"start","expiresAt":1767225620000}""",
                ),
            )
        assertEquals("agent-1", event.agentId.value)
        assertEquals("srv-1", event.serverId.value)
        assertEquals("chan-general", event.channelId.value)
        assertNull(event.parentMessageId)
        assertEquals("c1", event.callId)
        assertEquals(AgentTypingPhase.Start, event.phase)
        assertEquals(1_767_225_620_000L, event.expiresAtEpochMillis)
        assertNull(event.taskId)
    }

    @Test fun threadTargetNamesTheParentChannelAndRoot() {
        val event =
            assertIs<AgentTypingUpdated>(
                map(
                    """{"agentId":"agent-1","serverId":"srv-1","channelId":"chan-general","parentMessageId":"msg-root",
                        "callId":"c1","state":"start","expiresAt":1767225620000}""",
                ),
            )
        assertEquals("chan-general", event.channelId.value)
        assertEquals("msg-root", event.parentMessageId?.value)
    }

    @Test fun endAndTaskReplyFrames() {
        val end =
            assertIs<AgentTypingUpdated>(
                map("""{"agentId":"a","serverId":"s","channelId":"c","parentMessageId":null,"callId":"k","state":"end","expiresAt":5}"""),
            )
        assertEquals(AgentTypingPhase.End, end.phase)
        val task =
            assertIs<AgentTypingUpdated>(
                map(
                    """{"agentId":"a","serverId":"s","channelId":"task-conv","parentMessageId":null,"taskId":"task-9",
                        "callId":"k","state":"start","expiresAt":5}""",
                ),
            )
        assertEquals("task-9", task.taskId)
        assertNull(task.parentMessageId)
    }

    @Test fun framesWeCannotPlaceAreDropped() {
        assertNull(map("""{"serverId":"s","channelId":"c","callId":"k","state":"start","expiresAt":1}"""))
        assertNull(map("""{"agentId":"a","channelId":"c","callId":"k","state":"start","expiresAt":1}"""))
        assertNull(map("""{"agentId":"a","serverId":"s","callId":"k","state":"start","expiresAt":1}"""))
        assertNull(map("""{"agentId":"a","serverId":"s","channelId":"c","state":"start","expiresAt":1}"""))
        assertNull(map("""{"agentId":"a","serverId":"s","channelId":"c","callId":"k","state":"typing","expiresAt":1}"""))
    }

    @Test fun aMissingExpiryReadsAsZero() {
        val event = assertIs<AgentTypingUpdated>(map("""{"agentId":"a","serverId":"s","channelId":"c","callId":"k","state":"start"}"""))
        assertEquals(0L, event.expiresAtEpochMillis)
    }
}
