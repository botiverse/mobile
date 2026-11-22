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
        assertEquals(
            Size(RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX, RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX),
            raftImageDecodeSize(-128),
        )
    }

    @Test
    fun originalSentinelMapsToCoilOriginalSize() {
        assertEquals(Size.ORIGINAL, raftImageDecodeSize(RAFT_IMAGE_DECODE_ORIGINAL))
    }

    @Test
    fun displayDecodeEdgeClampsToPlatformCapWhenWindowIsLarger() {
        assertEquals(2048, raftDisplayImageDecodeEdgePx(windowLongEdgePx = 2868, platformMaxEdgePx = 2048))
        assertEquals(1334, raftDisplayImageDecodeEdgePx(windowLongEdgePx = 1334, platformMaxEdgePx = 2048))
        assertEquals(2048, raftDisplayImageDecodeEdgePx(windowLongEdgePx = 0, platformMaxEdgePx = 2048))
        assertEquals(2868, raftDisplayImageDecodeEdgePx(windowLongEdgePx = 2868, platformMaxEdgePx = null))
        assertEquals(
            RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX,
            raftDisplayImageDecodeEdgePx(windowLongEdgePx = 0, platformMaxEdgePx = null),
        )
    }
}
