package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockThreadWebLinkTargetTest {
    @Test
    fun parsesSlockChannelLink() {
        val target = "https://app.slock.ai/channel/channel-123?msg=message-456".toSlockThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("message-456", target?.messageId)
    }

    @Test
    fun parsesRaftChannelLink() {
        val target = "https://app.raft.build/channel/channel-123?msg=message-456".toSlockThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("message-456", target?.messageId)
    }

    @Test
    fun parsesWorkspaceChannelLink() {
        val target =
            "https://app.slock.ai/s/botiverse/channel/channel-123?msg=message-456"
                .toSlockThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("message-456", target?.messageId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, target?.routeKind)
        assertNull(target?.threadParentMessageId)
    }

    @Test
    fun parsesWorkspaceDmLink() {
        val target =
            "https://app.slock.ai/s/botiverse/dm/dm-123?msg=message-456"
                .toSlockThreadWebLinkTarget()

        assertEquals("dm-123", target?.channelId)
        assertEquals("message-456", target?.messageId)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, target?.routeKind)
        assertNull(target?.threadParentMessageId)
    }

    @Test
    fun parsesWorkspaceThreadLink() {
        val target =
            "https://staging.slock.ai/s/botiverse/channel/channel-123?thread=channel-123%3Aparent-789&msg=reply-456"
                .toSlockThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("reply-456", target?.messageId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, target?.routeKind)
        assertEquals("parent-789", target?.threadParentMessageId)
    }

    @Test
    fun parsesThreadOnlyLinkAsParentMessageTarget() {
        val target =
            "https://app.slock.ai/s/botiverse/channel/channel-123?thread=channel-123%3Aparent-789"
                .toSlockThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertEquals("parent-789", target?.messageId)
        assertEquals("parent-789", target?.threadParentMessageId)
    }

    @Test
    fun parsesSlockChannelLinkWithoutMessage() {
        val target = "http://app.slock.ai/channel/channel-123".toSlockThreadWebLinkTarget()

        assertEquals("channel-123", target?.channelId)
        assertNull(target?.messageId)
    }

    @Test
    fun rejectsExternalLinks() {
        assertNull("https://example.com/channel/channel-123?msg=message-456".toSlockThreadWebLinkTarget())
        assertNull("mailto:team@example.com".toSlockThreadWebLinkTarget())
    }

    @Test
    fun webLinkLoadingPayloadUsesChannelLaunchHeader() {
        val payload =
            KuiklyThreadScreenPayload(
                title = "Thread",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                canSend = false
            ).webLinkLoadingPayload(SlockThreadWebLinkTarget(channelId = "channel-123", messageId = "message-456"))

        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals(null, payload.subtitle)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals("channel-123", payload.sourceThreadId)
        assertEquals("channel-123", payload.streamChannelId)
        assertEquals("message-456", payload.focusedMessageId)
        assertEquals(null, payload.originParentMessageId)
        assertEquals("none", payload.originStatus)
        assertEquals(
            "Message #Channel",
            payload.resolveThreadComposer(displayName = threadHeaderDisplayTitle(payload.title, payload.surfaceKind)).placeholder
        )
        assertEquals(false, payload.resolveThreadComposer().enabled)
    }

    @Test
    fun webLinkLoadingPayloadUsesReplyRouteForThreadPermalink() {
        val payload =
            KuiklyThreadScreenPayload(
                title = "Thread",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                canSend = true
            ).webLinkLoadingPayload(
                SlockThreadWebLinkTarget(
                    channelId = "channel-123",
                    messageId = "reply-456",
                    routeKind = KuiklyThreadSurfaceKind.Channel,
                    threadParentMessageId = "parent-789"
                )
            )

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(null, payload.sourceThreadId)
        assertEquals(null, payload.streamChannelId)
        assertEquals("channel-123", payload.parentChannelId)
        assertEquals("parent-789", payload.parentMessageId)
        assertEquals("parent-789", payload.originParentMessageId)
        assertEquals("reply-456", payload.focusedMessageId)
        assertEquals("loading", payload.originStatus)
        assertEquals("parent-789", payload.resolveThreadComposer().parentMessageId)
        assertEquals("channel-123", payload.resolveThreadComposer().mentionChannelId)
    }
}
