package build.raft.app

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftShellOverlayContentRegistryTest {
    @Test
    fun contentIsConsumedOnceByTheWindowHost() {
        val entry =
            RaftShellOverlayContentEntry(
                requestId = "thread-message-1",
                contentSize = Size(240f, 480f),
                viewportGap = 8f,
                horizontalOffset = -16f,
                verticalOffset = 8f,
                content = { _ -> },
                onSelected = {},
            )

        RaftShellOverlayContentRegistry.register(entry)

        assertEquals(entry, RaftShellOverlayContentRegistry.take(entry.requestId))
        assertNull(RaftShellOverlayContentRegistry.take(entry.requestId))
    }

    @Test
    fun removingAnUnconsumedEntryMakesAStalePageRequestInert() {
        val entry =
            RaftShellOverlayContentEntry(
                requestId = "thread-message-stale",
                contentSize = Size(240f, 480f),
                viewportGap = 8f,
                horizontalOffset = -16f,
                verticalOffset = 8f,
                content = { _ -> },
                onSelected = {},
            )

        RaftShellOverlayContentRegistry.register(entry)
        RaftShellOverlayContentRegistry.remove(entry.requestId)

        assertNull(RaftShellOverlayContentRegistry.take(entry.requestId))
    }
}
