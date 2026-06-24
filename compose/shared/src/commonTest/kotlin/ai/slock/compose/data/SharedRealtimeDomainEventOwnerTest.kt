package ai.slock.compose.data

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SharedRealtimeDomainEventOwnerTest {
    @Test
    fun ignoresBlankServerIdsAndNonThreadProjectionEvents() {
        val owner = SharedRealtimeDomainEventOwner()

        owner.accept("", DirectMessageCreated(ChannelId("dm-1")))
        assertNull(owner.threadProjections.value)

        owner.accept("server-1", Heartbeat(seq = 10L))
        assertNull(owner.threadProjections.value)
    }

    @Test
    fun projectsThreadRelevantAndPresenceEventsWithSequence() {
        val owner = SharedRealtimeDomainEventOwner()
        val messageRouteEvent = DirectMessageCreated(ChannelId("dm-1"))
        val presenceEvent =
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working,
                statusLabel = "Running tests",
                serverSeq = 7L
            )

        owner.accept("server-1", messageRouteEvent)

        val first = owner.threadProjections.value
        assertEquals(1L, first?.sequence)
        assertEquals("server-1", first?.serverId)
        assertSame(messageRouteEvent, first?.event)

        owner.accept("server-1", presenceEvent)

        val second = owner.threadProjections.value
        assertEquals(2L, second?.sequence)
        assertEquals("server-1", second?.serverId)
        assertSame(presenceEvent, second?.event)
    }
}
