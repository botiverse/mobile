package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockBitmapDecodeSamplingTest {
    @Test
    fun twelveMegapixelPhotoKeepsFullResolutionForLargeRequest() {
        assertEquals(1, slockBitmapDecodeSampleSize(4000, 3000, targetEdgePx = 4096))
    }

    @Test
    fun twentyMegapixelImageIsHalvedEvenWhenRequestAsksForFullSize() {
        // The 59 MB QA PNG: a full ARGB decode needs ~79 MB and OOMed the preview.
        val sampleSize = slockBitmapDecodeSampleSize(5120, 3840, targetEdgePx = 4096)
        assertEquals(2, sampleSize)
        assertWithinCeilings(5120, 3840, sampleSize)
    }

    @Test
    fun longEdgeAboveCeilingIsSampledDownWithinPixelBudget() {
        val sampleSize = slockBitmapDecodeSampleSize(9000, 1000, targetEdgePx = 4096)
        assertEquals(4, sampleSize)
        assertWithinCeilings(9000, 1000, sampleSize)
    }

    @Test
    fun smallRequestStillSamplesTowardRequestedEdge() {
        assertEquals(8, slockBitmapDecodeSampleSize(5120, 3840, targetEdgePx = 512))
    }

    @Test
    fun tinyOrInvalidSourceNeverSamples() {
        assertEquals(1, slockBitmapDecodeSampleSize(64, 64, targetEdgePx = 256))
        assertEquals(1, slockBitmapDecodeSampleSize(0, 3840, targetEdgePx = 256))
    }

    private fun assertWithinCeilings(width: Int, height: Int, sampleSize: Int) {
        val sampledWidth = width / sampleSize
        val sampledHeight = height / sampleSize
        assertTrue(maxOf(sampledWidth, sampledHeight) <= SLOCK_MAX_BITMAP_DECODE_EDGE_PX)
        assertTrue(sampledWidth.toLong() * sampledHeight <= SLOCK_MAX_BITMAP_DECODE_PIXELS)
    }
}
