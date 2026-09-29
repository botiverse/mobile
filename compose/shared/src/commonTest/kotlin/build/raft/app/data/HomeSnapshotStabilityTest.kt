package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class HomeSnapshotStabilityTest {

    @Test
    fun preserveStableRowsRetainsRealtimePreviewWhenIncomingNetworkPayloadHasNullPreview() {
        val priorDm = HomeDirectMessageRow(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            name = "Alice",
            lastMessagePreview = "Hello from realtime!",
            lastMessageSenderName = "Alice",
            lastMessageId = MessageId("msg-100"),
            lastMessageAt = "12:00"
        )
        val priorSnapshot = sampleHome().copy(directMessages = listOf(priorDm))

        val incomingDm = HomeDirectMessageRow(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            name = "Alice",
            lastMessagePreview = null,
            lastMessageSenderName = null,
            lastMessageId = null,
            lastMessageAt = null
        )
        val incomingSnapshot = sampleHome().copy(directMessages = listOf(incomingDm))

        val result = incomingSnapshot.preserveStableRowsFrom(priorSnapshot)
        val resultDm = result.directMessages.single()

        assertEquals("Hello from realtime!", resultDm.lastMessagePreview)
        assertEquals("Alice", resultDm.lastMessageSenderName)
        assertEquals(MessageId("msg-100"), resultDm.lastMessageId)
        assertEquals("12:00", resultDm.lastMessageAt)
        assertSame(priorDm, resultDm)
    }

    @Test
    fun preserveStableRowsPrefersIncomingPreviewWhenNetworkProvidesIt() {
        val priorDm = HomeDirectMessageRow(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            name = "Alice",
            lastMessagePreview = "Old preview"
        )
        val priorSnapshot = sampleHome().copy(directMessages = listOf(priorDm))

        val incomingDm = HomeDirectMessageRow(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            name = "Alice",
            lastMessagePreview = "New wire preview"
        )
        val incomingSnapshot = sampleHome().copy(directMessages = listOf(incomingDm))

        val result = incomingSnapshot.preserveStableRowsFrom(priorSnapshot)
        assertEquals("New wire preview", result.directMessages.single().lastMessagePreview)
    }

    @Test
    fun preserveStableRowsHandlesEmptyOrNullPreviewsGracefully() {
        val priorDm = HomeDirectMessageRow(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            name = "Alice",
            lastMessagePreview = null
        )
        val priorSnapshot = sampleHome().copy(directMessages = listOf(priorDm))

        val incomingDm = HomeDirectMessageRow(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            name = "Alice",
            lastMessagePreview = null
        )
        val incomingSnapshot = sampleHome().copy(directMessages = listOf(incomingDm))

        val result = incomingSnapshot.preserveStableRowsFrom(priorSnapshot)
        assertNull(result.directMessages.single().lastMessagePreview)
        assertSame(priorDm, result.directMessages.single())
    }

    @Test
    fun preserveStableRowsRejectsPreviewInheritanceAcrossDifferentServers() {
        val priorDm = HomeDirectMessageRow(
            threadId = ThreadId("shared-key"),
            channelId = ChannelId("shared-key"),
            name = "Alice",
            lastMessagePreview = "Secret server 1 preview",
            lastMessageSenderName = "Alice",
            lastMessageId = MessageId("msg-1"),
            lastMessageAt = "12:00"
        )
        val priorChannel = HomeChannelRow(
            threadId = ThreadId("general"),
            channelId = ChannelId("general"),
            name = "general",
            lastMessagePreview = "Secret channel preview"
        )
        val priorSnapshot = sampleHome().copy(
            server = ServerSummary(ServerId("server-1"), "Server 1"),
            channels = listOf(priorChannel),
            directMessages = listOf(priorDm)
        )

        val incomingDm = HomeDirectMessageRow(
            threadId = ThreadId("shared-key"),
            channelId = ChannelId("shared-key"),
            name = "Alice",
            lastMessagePreview = null
        )
        val incomingChannel = HomeChannelRow(
            threadId = ThreadId("general"),
            channelId = ChannelId("general"),
            name = "general",
            lastMessagePreview = null
        )
        val incomingSnapshot = sampleHome().copy(
            server = ServerSummary(ServerId("server-2"), "Server 2"),
            channels = listOf(incomingChannel),
            directMessages = listOf(incomingDm)
        )

        val result = incomingSnapshot.preserveStableRowsFrom(priorSnapshot)
        assertNull(result.directMessages.single().lastMessagePreview)
        assertNull(result.channels.single().lastMessagePreview)
        assertSame(incomingSnapshot, result)
    }

    @Test
    fun preserveStableRowsRejectsPreviewInheritanceAcrossDifferentPrincipals() {
        val priorDm = HomeDirectMessageRow(
            threadId = ThreadId("shared-key"),
            channelId = ChannelId("shared-key"),
            name = "Alice",
            lastMessagePreview = "Confidential user 1 preview",
            lastMessageSenderName = "Alice",
            lastMessageId = MessageId("msg-1"),
            lastMessageAt = "12:00"
        )
        val priorChannel = HomeChannelRow(
            threadId = ThreadId("general"),
            channelId = ChannelId("general"),
            name = "general",
            lastMessagePreview = "User 1 channel preview"
        )
        val priorSnapshot = sampleHome().copy(
            currentMember = MemberProfile(
                id = MemberId("user-1"),
                displayName = "User 1",
                handle = "u1",
                kind = MemberKind.Human,
                presence = Presence.Active
            ),
            channels = listOf(priorChannel),
            directMessages = listOf(priorDm)
        )

        val incomingDm = HomeDirectMessageRow(
            threadId = ThreadId("shared-key"),
            channelId = ChannelId("shared-key"),
            name = "Alice",
            lastMessagePreview = null
        )
        val incomingChannel = HomeChannelRow(
            threadId = ThreadId("general"),
            channelId = ChannelId("general"),
            name = "general",
            lastMessagePreview = null
        )
        val incomingSnapshot = sampleHome().copy(
            currentMember = MemberProfile(
                id = MemberId("user-2"),
                displayName = "User 2",
                handle = "u2",
                kind = MemberKind.Human,
                presence = Presence.Active
            ),
            channels = listOf(incomingChannel),
            directMessages = listOf(incomingDm)
        )

        val result = incomingSnapshot.preserveStableRowsFrom(priorSnapshot)
        assertNull(result.directMessages.single().lastMessagePreview)
        assertNull(result.channels.single().lastMessagePreview)
        assertSame(incomingSnapshot, result)
    }

    private fun sampleHome(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "Slock"),
            currentMember = MemberProfile(
                id = MemberId("user-1"),
                displayName = "Artin",
                handle = "artin@slock.ai",
                kind = MemberKind.Human,
                presence = Presence.Active
            ),
            channels = emptyList(),
            directMessages = emptyList(),
            unreadTotal = 0
        )
}
