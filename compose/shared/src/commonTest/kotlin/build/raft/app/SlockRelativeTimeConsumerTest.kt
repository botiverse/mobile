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
    fun compactMetadataSlotKeepsGeometryStableWhenTimestampAppears() {
        val beforePress =
            threadMessageMetadataSlotLayout(
                showAvatar = false,
                compactTimestampGutter = true,
                showTimestamp = false
            )
        val duringPress =
            threadMessageMetadataSlotLayout(
                showAvatar = false,
                compactTimestampGutter = true,
                showTimestamp = true
            )

        assertEquals(112f, ThreadRelativeTimestampSlotWidthDp)
        assertEquals(36f, beforePress.widthDp)
        assertEquals(18f, beforePress.heightDp)
        assertEquals(false, beforePress.timestampVisible)
        assertEquals(beforePress.widthDp, duringPress.widthDp)
        assertEquals(beforePress.heightDp, duringPress.heightDp)
        assertEquals(true, duringPress.timestampVisible)

        val compactAuthorSlot =
            threadMessageMetadataSlotLayout(
                showAvatar = true,
                compactTimestampGutter = true,
                showTimestamp = false
            )
        assertEquals(beforePress.widthDp, compactAuthorSlot.widthDp)
        assertEquals(40f, compactAuthorSlot.heightDp)

        val normalAuthorSlot =
            threadMessageMetadataSlotLayout(
                showAvatar = true,
                compactTimestampGutter = false,
                showTimestamp = false
            )
        assertEquals(36f, normalAuthorSlot.widthDp)
        assertEquals(40f, normalAuthorSlot.heightDp)
    }
}
