package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedConversationDirectoryStoreTest {
    @Test
    fun homeRowDirectoryProjectionMatchesSummaryBasedProjection() {
        val serverId = ServerId("server-1")
        val rows =
            listOf(
                HomeChannelRow(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    channelType = "private",
                    kind = ConversationKind.Channel,
                    name = "general",
                    subtitle = "General discussion",
                    joined = true,
                    archived = false,
                    permissions = ConversationPermissions(canRead = true)
                ),
                HomeDirectMessageRow(
                    threadId = ThreadId("dm-1"),
                    channelId = ChannelId("dm-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "artin",
                    presenceMemberId = MemberId("member-1")
                ),
                HomeChannelRow(
                    threadId = ThreadId("joint-1"),
                    channelId = ChannelId("joint-1"),
                    channelType = "channel",
                    kind = ConversationKind.Joint,
                    name = "partner",
                    joined = false,
                    permissions = ConversationPermissions(canRead = false)
                ),
                HomeChannelRow(
                    threadId = ThreadId("reply-thread-1"),
                    channelId = ChannelId("reply-stream-1"),
                    kind = ConversationKind.Thread,
                    name = "Parent discussion",
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("parent-msg-1"),
                            streamChannelId = ChannelId("reply-stream-1")
                        )
                )
            )

        rows.forEach { row ->
            assertEquals(
                row.toConversationSummary().toSharedConversationDirectoryProjection(serverId),
                row.toSharedConversationDirectoryProjection(serverId)
            )
        }
    }

    @Test
    fun homeSnapshotResolvesConversationByDirectoryAliases() {
        val snapshot =
            HomeSnapshot(
                server = ServerSummary(ServerId("server-1"), "Slock"),
                currentMember =
                    MemberProfile(
                        id = MemberId("user-1"),
                        displayName = "Artin",
                        handle = "artin",
                        kind = MemberKind.Human
                    ),
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            kind = ConversationKind.Channel,
                            name = "general"
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("reply-thread-1"),
                            channelId = ChannelId("reply-thread-1"),
                            kind = ConversationKind.Thread,
                            name = "Parent discussion",
                            thread =
                                ThreadConversation(
                                    parentChannelId = ChannelId("channel-1"),
                                    parentMessageId = MessageId("parent-msg-1"),
                                    streamChannelId = ChannelId("reply-stream-1")
                                )
                        )
                    ),
                directMessages = emptyList(),
                unreadTotal = 0
            )

        assertEquals(
            "reply-thread-1",
            snapshot.resolveHomeConversationSummary(listOf("parent-msg-1"))?.threadId?.value
        )
        assertEquals(
            "reply-thread-1",
            snapshot.resolveHomeConversationSummary(listOf("reply-stream-1"))?.threadId?.value
        )
        assertEquals(
            "channel-1",
            snapshot.resolveHomeConversationSummary(listOf("channel-1"))?.threadId?.value
        )
    }

    @Test
    fun inboxDirectoryDoesNotSynthesizeAgentSenderAvatarFallback() {
        val projection =
            InboxItem(
                key = "thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-stream-1"),
                channelName = "Thread",
                threadChannelId = ChannelId("thread-stream-1"),
                parentChannelId = ChannelId("channel-1"),
                parentMessageId = MessageId("parent-1"),
                latestActivityMessageId = MessageId("message-1"),
                latestActivitySenderName = "Leila",
                latestActivitySenderId = "agent-1",
                latestActivitySenderKind = MemberKind.Agent,
                latestActivitySenderAvatarWireValue = null
            ).toSharedConversationDirectoryProjection(ServerId("server-1"))

        assertEquals("agent-1", projection?.senderMemberId)
        assertNull(projection?.senderAvatarWireValue)
    }

    @Test
    fun directoryProjectionCarriesCanonicalConversationKind() {
        val direct =
            HomeDirectMessageRow(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                kind = ConversationKind.DirectMessage,
                name = "Artin"
            ).toSharedConversationDirectoryProjection(ServerId("server-1"))
        val thread =
            InboxItem(
                key = "thread:thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-1"),
                channelName = "Thread",
                threadChannelId = ChannelId("thread-1")
            ).toSharedConversationDirectoryProjection(ServerId("server-1"))

        assertEquals(ConversationKind.DirectMessage, direct?.conversationKind)
        assertEquals(ConversationKind.Thread, thread?.conversationKind)
    }
}
