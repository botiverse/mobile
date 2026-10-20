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
    private val wide = RaftMessageImageDimensions(1200, 400)
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
    fun rowsFillByThreeAndFourSplitsTwoAndTwo() {
        assertEquals(listOf(2, 2), buildRaftMessageImageGalleryRows(List(4) { normal }).map { it.items.size })
        assertEquals(listOf(3, 2), buildRaftMessageImageGalleryRows(List(5) { normal }).map { it.items.size })
        assertEquals(listOf(2), buildRaftMessageImageGalleryRows(List(2) { normal }).map { it.items.size })
    }

    @Test
    fun wideImageTakesItsOwnRowAndStaysWhole() {
        val rows = buildRaftMessageImageGalleryRows(listOf(normal, wide, normal))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), rows.map { row -> row.items.map { it.index } })
        assertEquals(RaftMessageImageFit.Contain, rows[1].items.single().fit)
        assertEquals(RaftMessageImageFit.Cover, rows[0].items.single().fit)
    }

    @Test
    fun tallImageInARowStaysWhole() {
        val rows = buildRaftMessageImageGalleryRows(listOf(normal, tall))
        assertEquals(listOf(RaftMessageImageFit.Cover, RaftMessageImageFit.Contain), rows.single().items.map { it.fit })
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

    @Test
    fun singleSizeWithoutDimensionsReserves176At4By3() {
        assertEquals(DpSize(176.dp, 132.dp), raftMessageImageSingleSize(RaftMessageImageDimensions(), 360.dp))
        assertEquals(DpSize(120.dp, 90.dp), raftMessageImageSingleSize(RaftMessageImageDimensions(0, 10), 120.dp))
    }
}
