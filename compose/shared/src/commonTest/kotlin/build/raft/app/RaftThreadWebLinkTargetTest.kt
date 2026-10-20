package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftThreadWebLinkTargetTest {
    @Test
    fun parsesLegacyHostChannelLink() {
        val target = "https://app.slock.ai/channel/channel-123?msg=message-456".toRaftThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("message-456", target?.messageId)
    }

    @Test
    fun parsesRaftChannelLink() {
        val target = "https://app.raft.build/channel/channel-123?msg=message-456".toRaftThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("message-456", target?.messageId)
    }

    @Test
    fun parsesWorkspaceChannelLink() {
        val target =
            "https://app.slock.ai/s/botiverse/channel/channel-123?msg=message-456"
                .toRaftThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("message-456", target?.messageId)
        assertEquals(RaftThreadSurfaceKind.Channel, target?.routeKind)
        assertNull(target?.threadParentMessageId)
    }

    @Test
    fun parsesWorkspaceDmLink() {
        val target =
            "https://app.slock.ai/s/botiverse/dm/dm-123?msg=message-456"
                .toRaftThreadWebLinkTarget()

        assertEquals("dm-123", target?.channelId)
        assertEquals("message-456", target?.messageId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, target?.routeKind)
        assertNull(target?.threadParentMessageId)
    }

    @Test
    fun parsesWorkspaceThreadLink() {
        val target =
            "https://staging.slock.ai/s/botiverse/channel/channel-123?thread=channel-123%3Aparent-789&msg=reply-456"
                .toRaftThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("reply-456", target?.messageId)
        assertEquals(RaftThreadSurfaceKind.Channel, target?.routeKind)
        assertEquals("parent-789", target?.threadParentMessageId)
    }

    @Test
    fun parsesThreadOnlyLinkAsParentMessageTarget() {
        val target =
            "https://app.slock.ai/s/botiverse/channel/channel-123?thread=channel-123%3Aparent-789"
                .toRaftThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("parent-789", target?.messageId)
        assertEquals("parent-789", target?.threadParentMessageId)
    }

    @Test
    fun parsesRaftChannelLinkWithoutMessage() {
        val target = "http://app.slock.ai/channel/channel-123".toRaftThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertNull(target?.messageId)
    }

    @Test
    fun rejectsExternalLinks() {
        assertNull("https://example.com/channel/channel-123?msg=message-456".toRaftThreadWebLinkTarget())
        assertNull("mailto:team@example.com".toRaftThreadWebLinkTarget())
    }

    @Test
    fun webLinkLoadingPayloadUsesChannelLaunchHeader() {
        val target = RaftThreadWebLinkTarget(channelId = "channel-123", messageId = "message-456")
        val request = requireNotNull(target.resolveMarkdownOpenRequest("server-1", channelDirectory()))
        val payload = requireNotNull(buildAppShellThreadPayload(request.conversationIdentity, anchorMessageId = request.anchorMessageId))

        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals(null, payload.subtitle)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("channel-123", payload.sourceThreadId)
        assertEquals("channel-123", payload.streamChannelId)
        assertEquals("message-456", payload.focusedMessageId)
        assertEquals(null, payload.originParentMessageId)
        assertEquals("none", payload.originStatus)
        assertEquals(
            "Message #Channel",
            payload.resolveThreadComposer(displayName = threadHeaderDisplayTitle(payload.title, payload.surfaceKind)).placeholder
        )
    }

    @Test
    fun webLinkLoadingPayloadUsesReplyRouteForThreadPermalink() {
        val target =
            RaftThreadWebLinkTarget(
                channelId = "channel-123",
                messageId = "reply-456",
                routeKind = RaftThreadSurfaceKind.Channel,
                threadParentMessageId = "parent-789"
            )
        val request = requireNotNull(target.resolveMarkdownOpenRequest("server-1", channelDirectory()))
        val payload = requireNotNull(buildAppShellThreadPayload(request.conversationIdentity, anchorMessageId = request.anchorMessageId))

        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(null, payload.sourceThreadId)
        assertEquals(null, payload.streamChannelId)
        assertEquals("channel-123", payload.parentChannelId)
        assertEquals("parent-789", payload.parentMessageId)
        assertEquals("parent-789", payload.originParentMessageId)
        assertEquals("reply-456", payload.focusedMessageId)
        assertEquals("missing", payload.originStatus)
        assertEquals("parent-789", payload.resolveThreadComposer().parentMessageId)
        assertEquals("channel-123", payload.resolveThreadComposer().mentionChannelId)
    }

    @Test
    fun canonicalChannelThreadLinkResolvesWithoutDirectoryProjection() {
        val target =
            requireNotNull(
                (
                    "https://app.raft.build/s/botiverse/channel/channel-123" +
                        "?msg=reply-456&thread=channel-123%3Aparent-789"
                    ).toRaftThreadWebLinkTarget()
            )

        val request =
            requireNotNull(
                target.resolveMarkdownOpenRequest(
                    serverId = "server-1",
                    conversationDirectory = emptyList<SharedConversationDirectoryProjection>()
                        .toSharedConversationDirectoryState()
                )
            )
        val payload =
            requireNotNull(
                buildAppShellThreadPayload(
                    request.conversationIdentity,
                    anchorMessageId = request.anchorMessageId
                )
            )

        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-123", payload.parentChannelId)
        assertEquals("parent-789", payload.parentMessageId)
        assertEquals("reply-456", payload.focusedMessageId)
        assertEquals(ConversationKind.Channel, payload.resolveThreadRouteIdentity().parentConversationKind)
    }

    @Test
    fun canonicalChannelLinkResolvesWithoutDirectoryProjection() {
        val target =
            requireNotNull(
                "https://app.raft.build/s/botiverse/channel/channel-123?msg=message-456"
                    .toRaftThreadWebLinkTarget()
            )

        val request =
            requireNotNull(
                target.resolveMarkdownOpenRequest(
                    serverId = "server-1",
                    conversationDirectory = emptyList<SharedConversationDirectoryProjection>()
                        .toSharedConversationDirectoryState()
                )
            )

        assertEquals(ConversationKind.Channel, request.conversationIdentity.kind)
        assertEquals("channel-123", request.conversationIdentity.channelId?.value)
        assertEquals("message-456", request.anchorMessageId)
    }

    @Test
    fun canonicalDmThreadLinkResolvesWithoutDirectoryProjection() {
        val target =
            requireNotNull(
                (
                    "https://app.raft.build/s/botiverse/dm/dm-123" +
                        "?msg=reply-456&thread=dm-123%3Aparent-789"
                    ).toRaftThreadWebLinkTarget()
            )

        val request =
            requireNotNull(
                target.resolveMarkdownOpenRequest(
                    serverId = "server-1",
                    conversationDirectory = emptyList<SharedConversationDirectoryProjection>()
                        .toSharedConversationDirectoryState()
                )
            )
        val payload =
            requireNotNull(
                buildAppShellThreadPayload(
                    request.conversationIdentity,
                    anchorMessageId = request.anchorMessageId
                )
            )

        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("dm-123", payload.parentChannelId)
        assertEquals("parent-789", payload.parentMessageId)
        assertEquals("reply-456", payload.focusedMessageId)
        assertEquals(ConversationKind.DirectMessage, payload.resolveThreadRouteIdentity().parentConversationKind)
    }

    private fun channelDirectory() =
        listOf(
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-123",
                aliases = setOf("channel-123"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftThreadSurfaceKind.Channel,
                title = "Channel"
            )
        ).toSharedConversationDirectoryState()
}
