package build.raft.app

import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockMessagePermalinkTest {
    @Test
    fun parsesCanonicalChannelAndDmTargets() {
        val channel =
            "https://app.raft.build/s/mobile/channel/channel-123?msg=message-456"
                .toSlockMessagePermalinkTarget()
        val dm =
            "https://app.raft.build/s/mobile/dm/dm-123?msg=message-789"
                .toSlockMessagePermalinkTarget()

        assertEquals("mobile", channel?.serverSlug)
        assertEquals(ConversationKind.Channel, channel?.routeKind)
        assertEquals("channel-123", channel?.channelId?.value)
        assertEquals("message-456", channel?.messageId?.value)
        assertEquals(ConversationKind.DirectMessage, dm?.routeKind)
        assertEquals("dm-123", dm?.channelId?.value)
    }

    @Test
    fun parsesThreadParentAndUtf8MessageId() {
        val target =
            "https://staging.slock.ai/s/mobile/channel/channel-123" +
                "?thread=channel-123%3Aparent-%E4%B8%80&msg=reply%2B456"

        val parsed = target.toSlockMessagePermalinkTarget()

        assertEquals("parent-一", parsed?.threadParentMessageId?.value)
        assertEquals("reply+456", parsed?.messageId?.value)
    }

    @Test
    fun threadOnlyTargetUsesParentAsMessage() {
        val target =
            "https://app.slock.ai/s/mobile/channel/channel-123?thread=channel-123%3Aparent-789"
                .toSlockMessagePermalinkTarget()

        assertEquals("parent-789", target?.messageId?.value)
        assertEquals("parent-789", target?.threadParentMessageId?.value)
    }

    @Test
    fun acceptsOnlyExactConfiguredHost() {
        val custom = "https://preview.example.test/s/mobile/channel/c?msg=m"
        assertNull(custom.toSlockMessagePermalinkTarget())
        assertEquals(
            "m",
            custom.toSlockMessagePermalinkTarget("https://preview.example.test/root")?.messageId?.value
        )
        assertNull(
            "https://evil.preview.example.test/s/mobile/channel/c?msg=m"
                .toSlockMessagePermalinkTarget("https://preview.example.test/root")
        )
    }

    @Test
    fun rejectsMalformedOrContentlessTargets() {
        assertNull("https://app.raft.build/s/mobile/channel/c".toSlockMessagePermalinkTarget())
        assertNull("https://app.raft.build/s/mobile/joint/c?msg=m".toSlockMessagePermalinkTarget())
        assertNull("https://app.raft.build/s/mobile/channel/c/extra?msg=m".toSlockMessagePermalinkTarget())
        assertNull(
            "https://app.raft.build/s/mobile/channel/c?thread=other%3Aparent&msg=m"
                .toSlockMessagePermalinkTarget()
        )
        assertNull("https://example.com/s/mobile/channel/c?msg=m".toSlockMessagePermalinkTarget())
        assertNull("mailto:team@example.com".toSlockMessagePermalinkTarget())
    }
}
