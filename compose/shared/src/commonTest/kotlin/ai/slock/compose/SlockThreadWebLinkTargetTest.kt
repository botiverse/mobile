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
}
