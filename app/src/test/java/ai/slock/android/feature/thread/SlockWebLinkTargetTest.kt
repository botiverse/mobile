package ai.slock.android.feature.thread

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlockWebLinkTargetTest {
    @Test
    fun parsesSlockChannelMessageWebUrl() {
        val target =
                "https://app.slock.ai/s/community/channel/1cfb70da-8dca-4bb7-a4b5-a894de1ca65c?msg=04fcdf5e-d868-440a-aaf2-2944b5c0e539"
                        .toSlockWebLinkTarget()

        assertEquals(ChannelId("1cfb70da-8dca-4bb7-a4b5-a894de1ca65c"), target?.channelId)
        assertEquals(MessageId("04fcdf5e-d868-440a-aaf2-2944b5c0e539"), target?.messageId)
    }

    @Test
    fun parsesSlockChannelWebUrlWithoutMessage() {
        val target =
                "https://app.slock.ai/s/community/channel/channel-1".toSlockWebLinkTarget()

        assertEquals(ChannelId("channel-1"), target?.channelId)
        assertNull(target?.messageId)
    }

    @Test
    fun ignoresExternalUrls() {
        assertNull("https://example.com/s/community/channel/channel-1?msg=message-1".toSlockWebLinkTarget())
    }
}
