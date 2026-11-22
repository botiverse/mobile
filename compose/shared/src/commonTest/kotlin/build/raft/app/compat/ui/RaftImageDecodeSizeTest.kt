package build.raft.app.compat.ui

import coil3.size.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftImageDecodeSizeTest {
    @Test
    fun unknownBoundFallsBackToAPhoneSizedEdgeNeverTheOriginal() {
        assertEquals(
            Size(RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX, RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX),
            raftImageDecodeSize(0),
        )
    }
}
