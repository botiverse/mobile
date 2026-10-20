package build.raft.app.data

import build.raft.app.RaftThreadSurfaceKind
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedConversationDirectoryStoreTest {
    @Test
    fun identityConflictsEmitOneBoundedSummaryPerDirectoryBuild() {
        val logs = mutableListOf<String>()

        val directory =
            listOf(
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "canonical-1",
                    aliases = setOf("canonical-1", "shared-alias"),
                    conversationKind = ConversationKind.Channel,
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = "channel",
                    title = "First"
                ),
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "canonical-1",
                    aliases = setOf("canonical-1", "shared-alias"),
                    conversationKind = ConversationKind.Joint,
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = "joint",
                    title = "Replacement"
                ),
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "canonical-2",
                    aliases = setOf("canonical-2", "shared-alias"),
                    conversationKind = ConversationKind.DirectMessage,
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    channelKind = "direct_message",
                    title = "Second"
                )
            ).toSharedConversationDirectoryState(logger = logs::add)

        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("conversation_identity_merge_summary "))
        assertTrue(logs.single().contains("canonicalOverlapCount=1"))
        assertTrue(logs.single().contains("canonicalKindConflictCount=1"))
        assertTrue(logs.single().contains("aliasAmbiguityCount=1"))
        assertTrue(logs.single().contains("canonicalOverlapSample=canonical-1:2"))
        assertTrue(logs.single().contains("canonicalKindConflictSample=canonical-1:Channel,Joint"))
        assertTrue(logs.single().contains("aliasSample=shared-alias:2"))
        assertEquals(setOf("canonical-1", "canonical-2"), directory.duplicateAliases.getValue("shared-alias"))
    }

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
                    directMessagePeerType = "human",
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
    fun homeDmDirectoryProjectionPreservesHumanPeerKind() {
        val projection =
            HomeDirectMessageRow(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                kind = ConversationKind.DirectMessage,
                name = "artin",
                directMessagePeerType = "human",
                presenceMemberId = MemberId("member-1")
            ).toSharedConversationDirectoryProjection(ServerId("server-1"))

        assertEquals(MemberId("member-1"), projection?.peerIdentity?.memberId)
        assertEquals(MemberKind.Human, projection?.peerIdentity?.kind)
        assertEquals(ServerId("server-1"), projection?.peerIdentity?.sourceServerId)
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
    fun mergedDirectoryPrefersReplyThreadCanonicalOverHomeDmAliasInEitherOrder() {
        val homeState =
            HomeSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                currentMember =
                    MemberProfile(
                        id = MemberId("user-1"),
                        displayName = "Artin",
                        handle = "artin",
                        kind = MemberKind.Human
                    ),
                channels = emptyList(),
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("reply-stream"),
                            channelId = ChannelId("dm-parent"),
                            kind = ConversationKind.DirectMessage,
                            name = "Cindy"
                        )
                    ),
                unreadTotal = 0
            ).toSharedConversationDirectoryState()
        val activityState =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "Cindy",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            parentChannelKind = ConversationKind.Channel,
                            parentMessageId = MessageId("parent-message"),
                            parentMessagePreview = "Original thread question",
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()

        listOf(
            listOf(homeState, activityState),
            listOf(activityState, homeState)
        ).forEach { states ->
            val resolved = assertNotNull(states.mergedSharedConversationDirectoryState().resolve("reply-stream"))

            assertEquals("reply-stream", resolved.canonicalChannelId)
            assertEquals(ConversationKind.Thread, resolved.conversationKind)
            assertEquals(RaftThreadSurfaceKind.ReplyThread, resolved.surfaceKind)
            assertEquals("parent-channel", resolved.parentChannelId)
            assertEquals("parent-message", resolved.parentMessageId)
        }
    }

    @Test
    fun mergedDirectoryResolvesReplyThreadCanonicalBeforeDmProjectionAliasInEitherOrder() {
        val dmAliasProjection =
            listOf(
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "dm-parent",
                    aliases = setOf("dm-parent", "reply-stream"),
                    conversationKind = ConversationKind.DirectMessage,
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    channelKind = "dm",
                    title = "Cindy"
                )
            ).toSharedConversationDirectoryState()
        val replyThreadProjection =
            listOf(
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "reply-stream",
                    aliases = setOf("reply-stream", "parent-channel", "parent-message"),
                    conversationKind = ConversationKind.Thread,
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    channelKind = "thread",
                    title = "home-fixes",
                    subtitle = "Original thread question",
                    parentChannelId = "parent-channel",
                    parentMessageId = "parent-message"
                )
            ).toSharedConversationDirectoryState()

        listOf(
            listOf(dmAliasProjection, replyThreadProjection),
            listOf(replyThreadProjection, dmAliasProjection)
        ).forEach { states ->
            val resolved = assertNotNull(states.mergedSharedConversationDirectoryState().resolve("reply-stream"))

            assertEquals("reply-stream", resolved.canonicalChannelId)
            assertEquals(ConversationKind.Thread, resolved.conversationKind)
            assertEquals(RaftThreadSurfaceKind.ReplyThread, resolved.surfaceKind)
            assertEquals("parent-channel", resolved.parentChannelId)
            assertEquals("parent-message", resolved.parentMessageId)
        }
    }

    @Test
    fun directoryResolveUsesExactCanonicalBeforeAlias() {
        val directory =
            listOf(
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "parent-channel",
                    aliases = setOf("parent-channel", "reply-stream"),
                    conversationKind = ConversationKind.Channel,
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = "channel",
                    title = "home-fixes"
                ),
                SharedConversationDirectoryProjection(
                    serverId = "server-1",
                    canonicalChannelId = "reply-stream",
                    aliases = setOf("reply-stream", "parent-channel", "parent-message"),
                    conversationKind = ConversationKind.Thread,
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    channelKind = "thread",
                    title = "home-fixes",
                    subtitle = "Original thread question",
                    parentChannelId = "parent-channel",
                    parentMessageId = "parent-message"
                )
            ).toSharedConversationDirectoryState()

        val parent = assertNotNull(directory.resolve("parent-channel"))
        val thread = assertNotNull(directory.resolve("reply-stream"))

        assertEquals("parent-channel", parent.canonicalChannelId)
        assertEquals(ConversationKind.Channel, parent.conversationKind)
        assertEquals(RaftThreadSurfaceKind.Channel, parent.surfaceKind)
        assertEquals("reply-stream", thread.canonicalChannelId)
        assertEquals(ConversationKind.Thread, thread.conversationKind)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, thread.surfaceKind)
    }

    @Test
    fun parentProjectionDoesNotClaimReplyThreadStreamAlias() {
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "home-fixes",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            parentChannelKind = ConversationKind.Channel,
                            parentMessageId = MessageId("parent-message"),
                            parentMessagePreview = "Original thread question",
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()

        val parent = assertNotNull(directory.resolve("parent-channel"))
        val thread = assertNotNull(directory.resolve("reply-stream"))

        assertEquals(ConversationKind.Channel, parent.conversationKind)
        assertEquals("parent-channel", parent.canonicalChannelId)
        assertEquals(ConversationKind.Thread, thread.conversationKind)
        assertEquals("reply-stream", thread.canonicalChannelId)
        assertNull(directory.duplicateAliases["reply-stream"])
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
