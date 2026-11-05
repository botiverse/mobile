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
        val target = "https://app.slock.ai/channel/bd6e1218-1e13-4e40-838a-8c168fad487a?msg=1c1c2197-490a-435b-8e07-e6c79c871ed1".toRaftThreadWebLinkTarget()

        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", target?.channelId)
        assertEquals("1c1c2197-490a-435b-8e07-e6c79c871ed1", target?.messageId)
    }

    @Test
    fun parsesRaftChannelLink() {
        val target = "https://app.raft.build/channel/bd6e1218-1e13-4e40-838a-8c168fad487a?msg=1c1c2197-490a-435b-8e07-e6c79c871ed1".toRaftThreadWebLinkTarget()

        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", target?.channelId)
        assertEquals("1c1c2197-490a-435b-8e07-e6c79c871ed1", target?.messageId)
    }

    @Test
    fun parsesWorkspaceChannelLink() {
        val target =
            "https://app.slock.ai/s/botiverse/channel/bd6e1218-1e13-4e40-838a-8c168fad487a?msg=1c1c2197-490a-435b-8e07-e6c79c871ed1"
                .toRaftThreadWebLinkTarget()

        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", target?.channelId)
        assertEquals("1c1c2197-490a-435b-8e07-e6c79c871ed1", target?.messageId)
        assertEquals(RaftThreadSurfaceKind.Channel, target?.routeKind)
        assertNull(target?.threadParentMessageId)
    }

    @Test
    fun parsesWorkspaceDmLink() {
        val target =
            "https://app.slock.ai/s/botiverse/dm/4d4c5d12-2b19-4965-8149-633029a46e71?msg=1c1c2197-490a-435b-8e07-e6c79c871ed1"
                .toRaftThreadWebLinkTarget()

        assertEquals("4d4c5d12-2b19-4965-8149-633029a46e71", target?.channelId)
        assertEquals("1c1c2197-490a-435b-8e07-e6c79c871ed1", target?.messageId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, target?.routeKind)
        assertNull(target?.threadParentMessageId)
    }

    @Test
    fun parsesWorkspaceThreadLink() {
        val target =
            "https://staging.slock.ai/s/botiverse/channel/bd6e1218-1e13-4e40-838a-8c168fad487a?thread=bd6e1218-1e13-4e40-838a-8c168fad487a%3A9d6d1a00-86de-4c81-81ef-1f727384ecbb&msg=a10aa1e2-88f9-4633-804e-20497eebbdf4"
                .toRaftThreadWebLinkTarget()

        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", target?.channelId)
        assertEquals("a10aa1e2-88f9-4633-804e-20497eebbdf4", target?.messageId)
        assertEquals(RaftThreadSurfaceKind.Channel, target?.routeKind)
        assertEquals("9d6d1a00-86de-4c81-81ef-1f727384ecbb", target?.threadParentMessageId)
    }

    @Test
    fun parsesThreadOnlyLinkAsParentMessageTarget() {
        val target =
            "https://app.slock.ai/s/botiverse/channel/bd6e1218-1e13-4e40-838a-8c168fad487a?thread=bd6e1218-1e13-4e40-838a-8c168fad487a%3A9d6d1a00-86de-4c81-81ef-1f727384ecbb"
                .toRaftThreadWebLinkTarget()

        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", target?.channelId)
        assertEquals("9d6d1a00-86de-4c81-81ef-1f727384ecbb", target?.messageId)
        assertEquals("9d6d1a00-86de-4c81-81ef-1f727384ecbb", target?.threadParentMessageId)
    }

    @Test
    fun parsesRaftChannelLinkWithoutMessage() {
        val target = "http://app.slock.ai/channel/bd6e1218-1e13-4e40-838a-8c168fad487a".toRaftThreadWebLinkTarget()

        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", target?.channelId)
        assertNull(target?.messageId)
    }

    @Test
    fun rejectsExternalLinks() {
        assertNull("https://example.com/channel/bd6e1218-1e13-4e40-838a-8c168fad487a?msg=1c1c2197-490a-435b-8e07-e6c79c871ed1".toRaftThreadWebLinkTarget())
        assertNull("mailto:team@example.com".toRaftThreadWebLinkTarget())
    }

    @Test
    fun webLinkLoadingPayloadUsesChannelLaunchHeader() {
        val target = RaftThreadWebLinkTarget(channelId = "bd6e1218-1e13-4e40-838a-8c168fad487a", messageId = "1c1c2197-490a-435b-8e07-e6c79c871ed1")
        val request = requireNotNull(target.resolveMarkdownOpenRequest("server-1", channelDirectory()))
        val payload = requireNotNull(buildAppShellThreadPayload(request.conversationIdentity, anchorMessageId = request.anchorMessageId))

        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals(null, payload.subtitle)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", payload.sourceThreadId)
        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", payload.streamChannelId)
        assertEquals("1c1c2197-490a-435b-8e07-e6c79c871ed1", payload.focusedMessageId)
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
                channelId = "bd6e1218-1e13-4e40-838a-8c168fad487a",
                messageId = "a10aa1e2-88f9-4633-804e-20497eebbdf4",
                routeKind = RaftThreadSurfaceKind.Channel,
                threadParentMessageId = "9d6d1a00-86de-4c81-81ef-1f727384ecbb"
            )
        val request = requireNotNull(target.resolveMarkdownOpenRequest("server-1", channelDirectory()))
        val payload = requireNotNull(buildAppShellThreadPayload(request.conversationIdentity, anchorMessageId = request.anchorMessageId))

        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(null, payload.sourceThreadId)
        assertEquals(null, payload.streamChannelId)
        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", payload.parentChannelId)
        assertEquals("9d6d1a00-86de-4c81-81ef-1f727384ecbb", payload.parentMessageId)
        assertEquals("9d6d1a00-86de-4c81-81ef-1f727384ecbb", payload.originParentMessageId)
        assertEquals("a10aa1e2-88f9-4633-804e-20497eebbdf4", payload.focusedMessageId)
        assertEquals("missing", payload.originStatus)
        assertEquals("9d6d1a00-86de-4c81-81ef-1f727384ecbb", payload.resolveThreadComposer().parentMessageId)
        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", payload.resolveThreadComposer().mentionChannelId)
    }

    @Test
    fun canonicalChannelThreadLinkResolvesWithoutDirectoryProjection() {
        val target =
            requireNotNull(
                (
                    "https://app.raft.build/s/botiverse/channel/bd6e1218-1e13-4e40-838a-8c168fad487a" +
                        "?msg=a10aa1e2-88f9-4633-804e-20497eebbdf4&thread=bd6e1218-1e13-4e40-838a-8c168fad487a%3A9d6d1a00-86de-4c81-81ef-1f727384ecbb"
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
        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", payload.parentChannelId)
        assertEquals("9d6d1a00-86de-4c81-81ef-1f727384ecbb", payload.parentMessageId)
        assertEquals("a10aa1e2-88f9-4633-804e-20497eebbdf4", payload.focusedMessageId)
        assertEquals(ConversationKind.Channel, payload.resolveThreadRouteIdentity().parentConversationKind)
    }

    @Test
    fun canonicalChannelLinkResolvesWithoutDirectoryProjection() {
        val target =
            requireNotNull(
                "https://app.raft.build/s/botiverse/channel/bd6e1218-1e13-4e40-838a-8c168fad487a?msg=1c1c2197-490a-435b-8e07-e6c79c871ed1"
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
        assertEquals("bd6e1218-1e13-4e40-838a-8c168fad487a", request.conversationIdentity.channelId?.value)
        assertEquals("1c1c2197-490a-435b-8e07-e6c79c871ed1", request.anchorMessageId)
    }

    @Test
    fun canonicalDmThreadLinkResolvesWithoutDirectoryProjection() {
        val target =
            requireNotNull(
                (
                    "https://app.raft.build/s/botiverse/dm/4d4c5d12-2b19-4965-8149-633029a46e71" +
                        "?msg=a10aa1e2-88f9-4633-804e-20497eebbdf4&thread=4d4c5d12-2b19-4965-8149-633029a46e71%3A9d6d1a00-86de-4c81-81ef-1f727384ecbb"
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
        assertEquals("4d4c5d12-2b19-4965-8149-633029a46e71", payload.parentChannelId)
        assertEquals("9d6d1a00-86de-4c81-81ef-1f727384ecbb", payload.parentMessageId)
        assertEquals("a10aa1e2-88f9-4633-804e-20497eebbdf4", payload.focusedMessageId)
        assertEquals(ConversationKind.DirectMessage, payload.resolveThreadRouteIdentity().parentConversationKind)
    }

    private fun channelDirectory() =
        listOf(
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "bd6e1218-1e13-4e40-838a-8c168fad487a",
                aliases = setOf("bd6e1218-1e13-4e40-838a-8c168fad487a"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftThreadSurfaceKind.Channel,
                title = "Channel"
            )
        ).toSharedConversationDirectoryState()
}
