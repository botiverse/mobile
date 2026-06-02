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
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.threadIdentity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ComposeLocalWriteThroughTest {
    @Test
    fun writeHomeResultPreservesStoredDirectMessagePresence() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val dm = ConversationSummary(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            kind = ConversationKind.DirectMessage,
            name = "Agent",
            presence = Presence.Active,
            presenceMemberId = MemberId("agent-1")
        )
        store.writeHome(home(directMessages = listOf(dm)))

        writer.writeHomeResult(
            DataResult.Content(
                home(
                    directMessages =
                        listOf(
                            dm.copy(
                                presence = null,
                                presenceMemberId = null,
                                lastMessagePreview = "new"
                            )
                        )
                )
            ),
            source = "test"
        )

        val cached = store.readHome(serverId)?.directMessages?.single()
        assertEquals(Presence.Active, cached?.presence)
        assertEquals(MemberId("agent-1"), cached?.presenceMemberId)
        assertEquals("new", cached?.lastMessagePreview)
    }

    @Test
    fun writeReplyThreadResultRequiresAndStoresParentContext() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val parent = message(
            id = "parent-1",
            threadId = "channel-1",
            channelId = "channel-1",
            threadChannelId = "thread-channel-1",
            replyCount = 4
        )
        val rawThread =
            ConversationSummary(
                threadId = ThreadId("thread-channel-1"),
                channelId = ChannelId("thread-channel-1"),
                kind = ConversationKind.Channel,
                name = "thread-channel-1"
            )
        val fallbackHeader =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "general"
            )

        writer.writeReplyThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = rawThread,
                    messages = listOf(message("reply-1", "thread-channel-1", "thread-channel-1", seq = 2)),
                    parentMessage = null
                )
            ),
            source = "created_reply_thread",
            fallbackThreadId = ThreadId("thread-channel-1"),
            fallbackHeader = fallbackHeader,
            fallbackParentMessage = parent
        )

        val cached = store.readThread(threadIdentity(ChannelId("channel-1"), MessageId("parent-1")))
        assertNotNull(cached)
        assertEquals(ConversationKind.Thread, cached.conversation.kind)
        assertEquals(ChannelId("channel-1"), cached.conversation.channelId)
        assertEquals(ChannelId("thread-channel-1"), cached.conversation.thread?.streamChannelId)
        assertEquals(parent.id, cached.parentMessage?.id)
    }

    @Test
    fun duplicateThreadSnapshotWriteIsSkipped() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val snapshot =
            ThreadSnapshot(
                conversation = channel(),
                messages = listOf(message("m1", "channel-1", "channel-1", seq = 1)),
                lastSeq = 1
            )

        writer.writeThreadResult(DataResult.Content(snapshot), source = "test_thread")
        writer.writeThreadResult(DataResult.Content(snapshot), source = "test_thread")

        assertEquals(snapshot.messages.single().id, store.readThread(ThreadId("channel-1"))?.messages?.single()?.id)
        assertEquals(1, logs.count { it.startsWith("write_thread source=test_thread") })
        assertEquals(1, logs.count { it.startsWith("write_thread_skip source=test_thread reason=unchanged") })
    }

    @Test
    fun realtimeMessageUpdatesCachedThreadAndHomePreview() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val channel = channel()
        store.writeHome(home(channels = listOf(channel)))
        store.writeThread(
            ThreadSnapshot(
                conversation = channel,
                messages = listOf(message("old", "channel-1", "channel-1", seq = 1))
            )
        )

        writer.writeRealtimeEvent(
            serverId,
            MessageNew(message("new", "channel-1", "channel-1", seq = 2, body = "new body"))
        )

        val thread = store.readThread(ThreadId("channel-1"))
        assertEquals(listOf("old", "new"), thread?.messages?.map { it.id.value })
        val homeChannel = store.readHome(serverId)?.channels?.single()
        assertEquals("new body", homeChannel?.lastMessagePreview)
        assertEquals(1, homeChannel?.unreadCount)
    }

    @Test
    fun realtimePresenceUpdatesCachedDirectMessage() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val dm =
            ConversationSummary(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                kind = ConversationKind.DirectMessage,
                name = "Agent",
                presence = Presence.Offline,
                presenceMemberId = MemberId("agent-1")
            )
        store.writeHome(home(directMessages = listOf(dm)))

        writer.writeRealtimeEvent(
            serverId,
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent
            )
        )

        val cached = store.readHome(serverId)?.directMessages?.single()
        assertEquals(Presence.Active, cached?.presence)
    }

    @Test
    fun clearConversationUnreadUpdatesCachedHomeRow() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        store.writeHome(
            home(
                channels =
                    listOf(
                        channel().copy(
                            unreadCount = 3,
                            unreadCountKnown = true,
                            mentionCount = 1,
                            mentionCountKnown = true,
                            firstUnreadMessageId = MessageId("message-1")
                        )
                    )
            ).copy(unreadTotal = 3)
        )

        writer.clearConversationUnread(ChannelId("channel-1"), "test", serverId)

        val cached = store.readHome(serverId)
        val channel = cached?.channels?.single()
        assertEquals(0, channel?.unreadCount)
        assertEquals(true, channel?.unreadCountKnown)
        assertEquals(0, channel?.mentionCount)
        assertEquals(null, channel?.firstUnreadMessageId)
        assertEquals(0, cached?.unreadTotal)
    }

    @Test
    fun heartbeatDoesNotRewriteSnapshots() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)

        writer.writeRealtimeEvent(serverId, ai.slock.compose.model.Heartbeat(seq = 42))

        assertNull(store.readHome(serverId))
        assertEquals(listOf("write_realtime_skip type=heartbeat seq=42"), logs)
    }

    private fun home(
        channels: List<ConversationSummary> = listOf(channel()),
        directMessages: List<ConversationSummary> = emptyList()
    ): HomeSnapshot =
        HomeSnapshot(
            server = server,
            currentMember = member,
            channels = channels,
            directMessages = directMessages,
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
        threadId: String,
        channelId: String,
        threadChannelId: String? = null,
        seq: Long? = null,
        body: String = id,
        replyCount: Int = 0
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId(threadId),
            channelId = ChannelId(channelId),
            threadChannelId = threadChannelId?.let(::ChannelId),
            seq = seq,
            author = member,
            timestampLabel = "12:00",
            body = body,
            replyCount = replyCount
        )

    private companion object {
        val serverId = ServerId("server-1")
        val server = ServerSummary(id = serverId, name = "Slock")
        val member =
            MemberProfile(
                id = MemberId("member-1"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
                presence = Presence.Active
            )
    }
}
