package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockRelativeTimeConsumerTest {
    @Test
    fun filesKeepRawCreatedAtForLiveLeafFormatting() {
        val raw = "2026-07-27T12:00:00.123Z"

        assertEquals(raw, threadFileCreatedAtValue(raw))
        assertEquals("", threadFileCreatedAtValue(null))
    }

    @Test
    fun compactAndHeaderTimestampsShareStableReadableWidth() {
        assertEquals(112f, ThreadRelativeTimestampSlotWidthDp)
        assertEquals(112f, threadMessageAvatarSlotWidthDp(showTimestamp = true))
        assertEquals(36f, threadMessageAvatarSlotWidthDp(showTimestamp = false))
    }
}
