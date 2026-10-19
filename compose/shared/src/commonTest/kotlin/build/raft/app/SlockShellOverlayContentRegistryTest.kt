package build.raft.app

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockShellOverlayContentRegistryTest {
    @Test
    fun contentIsConsumedOnceByTheWindowHost() {
        val entry =
            SlockShellOverlayContentEntry(
                requestId = "thread-message-1",
                contentSize = Size(240f, 480f),
                viewportGap = 8f,
                horizontalOffset = -16f,
                verticalOffset = 8f,
                content = { _ -> },
                onSelected = {},
            )

        SlockShellOverlayContentRegistry.register(entry)

        assertEquals(entry, SlockShellOverlayContentRegistry.take(entry.requestId))
        assertNull(SlockShellOverlayContentRegistry.take(entry.requestId))
    }

    @Test
    fun removingAnUnconsumedEntryMakesAStalePageRequestInert() {
        val entry =
            SlockShellOverlayContentEntry(
                requestId = "thread-message-stale",
                contentSize = Size(240f, 480f),
                viewportGap = 8f,
                horizontalOffset = -16f,
                verticalOffset = 8f,
                content = { _ -> },
                onSelected = {},
            )

        SlockShellOverlayContentRegistry.register(entry)
        SlockShellOverlayContentRegistry.remove(entry.requestId)

        assertNull(SlockShellOverlayContentRegistry.take(entry.requestId))
    }
}
