package build.raft.designsystem

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftMessageImageDimensions
import build.raft.designsystem.components.RaftMessageImageFit
import build.raft.designsystem.components.buildRaftMessageImageGalleryRows
import build.raft.designsystem.components.raftMessageImageSingleSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors raft-ui `message-image-gallery-rows.test.ts`. */
class RaftMessageImageGalleryTest {
    private val normal = RaftMessageImageDimensions(800, 600)
    private val tall = RaftMessageImageDimensions(400, 1000)

    @Test
    fun loneImageKeepsItsProportionsAndNeverCrops() {
        val rows = buildRaftMessageImageGalleryRows(listOf(normal))
        assertEquals(1, rows.size)
        val item = rows.single().items.single()
        assertTrue(item.single)
        assertEquals(RaftMessageImageFit.Contain, item.fit)
    }

    @Test
    fun singleSizeCapsTo416By288AndToTheAvailableWidth() {
        // 800x600 scales by min(416/800, 288/600) = 0.48 -> 384 wide, then the 360 cap.
        assertEquals(DpSize(360.dp, 270.dp), raftMessageImageSingleSize(normal, 360.dp))
        // 400x1000 scales by 288/1000 -> 115 wide.
        assertEquals(115.dp, raftMessageImageSingleSize(tall, 360.dp).width)
        // Small images never scale up.
        assertEquals(DpSize(120.dp, 90.dp), raftMessageImageSingleSize(RaftMessageImageDimensions(120, 90), 360.dp))
    }
}
