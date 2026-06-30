package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SessionSnapshot
import build.raft.app.model.ThreadId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryComposeLocalDataStoreTest {
    @Test
    fun storesSessionAndHomeSnapshots() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val session = SessionSnapshot(
            currentServerId = serverId,
            currentMember = member,
            availableServers = listOf(server)
        )
        val home = HomeSnapshot(
            server = server,
            currentMember = member,
            channels = listOf(channel),
            directMessages = emptyList(),
            unreadTotal = 4
        )

        store.writeSession(session)
        store.writeHome(home)

        assertEquals(session, store.readSession())
        assertEquals(home, store.readHome(serverId))
        assertEquals(home, store.observeHome(serverId).first())
    }

    @Test
    fun clearsUnreadForOneChannelAcrossServerSnapshots() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val otherServerId = ServerId("server-other")
        store.writeUnread(
            ChannelUnreadSnapshot(
                server = server,
                unreadCounts = mapOf(channelId to 3, ChannelId("random") to 2)
            )
        )
        store.writeUnread(
            ChannelUnreadSnapshot(
                server = server.copy(id = otherServerId),
                unreadCounts = mapOf(channelId to 7)
            )
        )

        store.clearChannelUnread(channelId)

        assertEquals(mapOf(ChannelId("random") to 2), store.readUnread(serverId)?.unreadCounts)
        assertEquals(emptyMap(), store.readUnread(otherServerId)?.unreadCounts)
    }

    @Test
    fun missingSnapshotReturnsNull() = runBlocking {
        val store = InMemoryComposeLocalDataStore()

        assertNull(store.readThread(ThreadId("missing")))
    }

    private companion object {
        val serverId = ServerId("server-1")
        val channelId = ChannelId("channel-1")
        val server = ServerSummary(id = serverId, name = "Slock")
        val member = MemberProfile(
            id = MemberId("member-1"),
            displayName = "Artin",
            handle = "artin",
            kind = MemberKind.Human,
            presence = Presence.Active
        )
        val channel = ConversationSummary(
            threadId = ThreadId(channelId.value),
            channelId = channelId,
            kind = ConversationKind.Channel,
            name = "general"
        ).toHomeChannelRow()
    }
}
