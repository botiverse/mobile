package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AgentTypingPhase
import build.raft.app.model.AgentTypingUpdated
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Port of the web's `packages/web/tests/agentTyping.store.test.ts` rules, on virtual time. */
class SharedAgentTypingStoreTest {
    private val server = "srv-1"

    private fun TestScope.store() = SharedAgentTypingStore(backgroundScope, now = { testScheduler.currentTime })

    private fun TestScope.typing(
        agent: String,
        call: String = "call-$agent",
        channel: String = "ch-1",
        parent: String? = null,
        phase: AgentTypingPhase = AgentTypingPhase.Start,
        expiresIn: Long = AGENT_TYPING_TTL_MS,
        serverId: String = server,
    ) = AgentTypingUpdated(
        agentId = MemberId(agent),
        serverId = ServerId(serverId),
        channelId = ChannelId(channel),
        parentMessageId = parent?.let(::MessageId),
        callId = call,
        phase = phase,
        expiresAtEpochMillis = testScheduler.currentTime + expiresIn,
    )

    private fun SharedAgentTypingStore.ids(channel: String = "ch-1", parent: String? = null, serverId: String = server) =
        state.value.typingAgentIds(serverId, channel, parent)

    private fun mapped(eventName: String, payload: String): RealtimeEvent =
        assertNotNull(RealtimeEventMapper().map(eventName, payload))

    private fun agentMessage(agent: String, channel: String, threadRoot: String? = null): RealtimeEvent {
        val context =
            threadRoot?.let {
                ""","conversationContext":{"channelType":"thread","parentMessageId":"$it","parentChannelId":"ch-1","parentChannelType":"channel"}"""
            }.orEmpty()
        return mapped(
            RealtimeSocketEvents.MessageNew,
            """{"id":"m-$agent","channelId":"$channel","senderType":"agent","senderId":"$agent","senderName":"$agent",
                "messageType":"chat","content":"hi"$context}""",
        )
    }

    @Test fun startsShowInTheOrderTheyStartedAndRefreshesKeepTheirPlace() = runTest {
        val store = store()
        store.consume(server, typing("niko"))
        advanceTimeBy(1_000)
        store.consume(server, typing("tenny"))
        advanceTimeBy(1_000)
        store.consume(server, typing("niko")) // refresh
        assertEquals(listOf("niko", "tenny"), store.ids())
        // Two calls of one agent name it once.
        store.consume(server, typing("niko", call = "second"))
        assertEquals(listOf("niko", "tenny"), store.ids())
    }

    @Test fun aLineExpiresAfterItsTtlWithoutARefreshAndARefreshExtendsIt() = runTest {
        val store = store()
        store.consume(server, typing("niko"))
        advanceTimeBy(AGENT_TYPING_SEND_INTENT_REFRESH_MS)
        store.consume(server, typing("niko"))
        advanceTimeBy(AGENT_TYPING_TTL_MS - 1)
        runCurrent()
        assertEquals(listOf("niko"), store.ids(), "the refresh moved the deadline")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(emptyList(), store.ids())
        assertTrue(store.state.value.byChannel.isEmpty())
    }

    @Test fun aSkewedServerClockFallsBackToTheTtl() = runTest {
        val store = store()
        store.consume(server, typing("past", expiresIn = -5_000))
        store.consume(server, typing("far", expiresIn = 10 * AGENT_TYPING_TTL_MS))
        store.consume(server, typing("near", expiresIn = 3_000))
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(listOf("past", "far"), store.ids())
        advanceTimeBy(AGENT_TYPING_TTL_MS)
        runCurrent()
        assertEquals(emptyList(), store.ids())
        assertEquals(AGENT_TYPING_TTL_MS + 100, agentTypingLocalDeadline(expiresAt = 0, now = 100))
    }

    @Test fun endRemovesTheCall() = runTest {
        val store = store()
        store.consume(server, typing("niko"))
        store.consume(server, typing("niko", phase = AgentTypingPhase.End))
        assertEquals(emptyList(), store.ids())
    }

    @Test fun conversationsAreKeyedByChannelAndThread() = runTest {
        val store = store()
        store.consume(server, typing("niko"))
        store.consume(server, typing("kirby", parent = "root-1"))
        store.consume(server, typing("tenny", channel = "dm-1"))
        assertEquals(listOf("niko"), store.ids())
        assertEquals(listOf("kirby"), store.ids(parent = "root-1"))
        assertEquals(emptyList(), store.ids(parent = "root-2"))
        assertEquals(listOf("tenny"), store.ids(channel = "dm-1"))
        assertEquals(emptyList(), store.ids(channel = "ch-2"), "another channel shows nobody")
    }

    @Test fun theAgentsMessageClearsItsLineAndLateStartsForThatCallAreIgnored() = runTest {
        val store = store()
        store.consume(server, typing("niko"))
        store.consume(server, typing("kirby"))
        store.consume(server, agentMessage("niko", "ch-1"))
        assertEquals(listOf("kirby"), store.ids())
        // A refresh already in flight for the posted call does not bring it back.
        store.consume(server, typing("niko"))
        assertEquals(listOf("kirby"), store.ids())
        // A new call does.
        store.consume(server, typing("niko", call = "next"))
        assertEquals(listOf("kirby", "niko"), store.ids())
    }

    @Test fun aPersonsMessageClearsNothing() = runTest {
        val store = store()
        store.consume(server, typing("niko"))
        store.consume(
            server,
            mapped(
                RealtimeSocketEvents.MessageNew,
                """{"id":"m1","channelId":"ch-1","senderType":"user","senderId":"niko","messageType":"chat","content":"hi"}""",
            ),
        )
        assertEquals(listOf("niko"), store.ids())
    }

    @Test fun threadRepliesClearByRootFromTheMessageOrTheThreadUpdate() = runTest {
        val store = store()
        store.consume(server, typing("niko", parent = "root-1"))
        store.consume(server, typing("kirby", parent = "root-1"))
        store.consume(server, typing("niko"))
        store.consume(server, agentMessage("niko", "thread-ch-1", threadRoot = "root-1"))
        assertEquals(listOf("kirby"), store.ids(parent = "root-1"))
        assertEquals(listOf("niko"), store.ids(), "the channel-level call stays")
        store.consume(
            server,
            mapped(
                RealtimeSocketEvents.ThreadUpdated,
                """{"parentMessageId":"root-1","threadChannelId":"thread-ch-1","replyCount":2,"participantIds":["kirby"],
                    "latestReply":{"id":"r2","seq":3,"content":"done","senderId":"kirby","senderType":"agent",
                    "senderName":"kirby","createdAt":"2026-10-23T00:00:00Z"}}""",
            ),
        )
        assertEquals(emptyList(), store.ids(parent = "root-1"))
    }

    @Test fun aServerSwitchStartsOverAndAnotherServersFramesAreNotShown() = runTest {
        val store = store()
        store.bindServer(server)
        store.consume(server, typing("niko"))
        // A frame whose payload names another server than the one it arrived under is ignored.
        store.consume(server, typing("ghost", serverId = "srv-2"))
        assertEquals(listOf("niko"), store.ids())
        store.bindServer("srv-2")
        assertEquals(emptyList(), store.ids())
        assertEquals(emptyList(), store.ids(serverId = "srv-2"))
        store.consume("srv-2", typing("tenny", serverId = "srv-2"))
        assertEquals(listOf("tenny"), store.ids(serverId = "srv-2"))
        assertEquals(emptyList(), store.ids(), "srv-1 lines never show under srv-2")
    }

    @Test fun repeatedDeliveryIsIdempotent() = runTest {
        val store = store()
        val start = typing("niko")
        repeat(3) { store.consume(server, start) }
        assertEquals(1, store.state.value.byChannel["ch-1"]?.size)
        val message = agentMessage("niko", "ch-1")
        repeat(2) { store.consume(server, message) }
        assertEquals(emptyList(), store.ids())
    }
}
