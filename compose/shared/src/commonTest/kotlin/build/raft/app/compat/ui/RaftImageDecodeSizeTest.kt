package build.raft.app.compat.ui

import coil3.size.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftImageDecodeSizeTest {
    @Test
    fun decodeIsBoundedByTheRequestedLongEdge() {
        assertEquals(Size(1080, 1080), raftImageDecodeSize(1080))
        assertEquals(Size(64, 64), raftImageDecodeSize(64))
    }

    @Test
    fun unknownBoundFallsBackToAPhoneSizedEdgeNeverTheOriginal() {
        assertEquals(
            Size(RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX, RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX),
            raftImageDecodeSize(0),
        )
    }

    @Test
    fun originalSizeIsOnlyAnExplicitOptIn() {
        assertEquals(Size.ORIGINAL, raftImageDecodeSize(RAFT_IMAGE_DECODE_ORIGINAL))
    }
}
