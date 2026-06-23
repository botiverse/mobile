package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedRealtimeStoreTest {
    @Test
    fun duplicateMessageEventWritesLocalStoreOnce() = runBlocking {
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val realtimeStore =
            SharedRealtimeStore(
                localWriteThrough = ComposeLocalWriteThrough(localStore, logger = logs::add),
                logger = logs::add
            )
        localStore.writeHome(home())
        val event =
            MessageNew(
                message =
                    message(
                        id = "message-1",
                        body = "hello",
                        seq = 10,
                        author = agent
                    )
            )

        val first = realtimeStore.acceptRealtimeEvent(serverId, event)
        val second = realtimeStore.acceptRealtimeEvent(serverId, event)

        val cached = localStore.readHome(serverId)?.channels?.single()
        assertTrue(first.wrote)
        assertEquals(1, first.accepted)
        assertEquals(0, first.duplicates)
        assertFalse(second.wrote)
        assertEquals(0, second.accepted)
        assertEquals(1, second.duplicates)
        assertEquals("hello", cached?.lastMessagePreview)
        assertEquals(1, cached?.unreadCount)
        assertEquals(
            1,
            logs.count { it.startsWith("shared_realtime_accept") && it.contains("accepted=1") }
        )
        assertEquals(
            1,
            logs.count { it.startsWith("shared_realtime_accept") && it.contains("duplicates=1") }
        )
    }

    private fun home(): HomeSnapshot =
        HomeSnapshot(
            server = server,
            currentMember = currentMember,
            channels = listOf(channel()),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun channel(): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId("channel-1"),
            channelId = ChannelId("channel-1"),
            kind = ConversationKind.Channel,
            name = "general"
        )

    private fun message(
        id: String,
        body: String,
        seq: Long,
        author: MemberProfile
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId("channel-1"),
            channelId = ChannelId("channel-1"),
            seq = seq,
            author = author,
            timestampLabel = "12:00",
            body = body
        )

    private companion object {
        val serverId = ServerId("server-1")
        val server = ServerSummary(id = serverId, name = "Slock")
        val currentMember =
            MemberProfile(
                id = MemberId("member-1"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
                presence = Presence.Active
            )
        val agent =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Agent",
                handle = "agent",
                kind = MemberKind.Agent,
                presence = Presence.Active
            )
    }
}
