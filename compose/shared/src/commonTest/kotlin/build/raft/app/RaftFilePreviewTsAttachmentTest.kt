package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * task #511 — ".ts" is ambiguous: OS MIME tables (Android MimeTypeMap) auto-tag
 * it video/mp2t, and RaftFilePreview's video-container extension map had "ts"
 * alongside m2ts/mts. A TypeScript attachment therefore rendered as a video
 * player with "视频播放失败". Fix: ".ts" resolves to text/code, not MpegTs.
 */
class RaftFilePreviewTsAttachmentTest {

    @Test
    fun tsExtensionWithMp2tMimeIsNotVideo() {
        // Android MimeTypeMap guesses .ts -> video/mp2t on upload; must not win.
        assertNull(raftFilePreviewVideoContainer(fileName = "app.ts", mimeType = "video/mp2t"))
        assertNull(raftFilePreviewVideoContainer(fileName = "cross.storage-r6-review.test.ts", mimeType = "video/mp2t"))
    }

    @Test
    fun realMpegTsExtensionsStillResolve() {
        // .m2ts / .mts are unambiguous video containers — keep them.
        assertEquals(
            RaftPreviewableVideoContainer.MpegTs,
            raftFilePreviewVideoContainer(fileName = "clip.m2ts", mimeType = "")
        )
        assertEquals(
            RaftPreviewableVideoContainer.MpegTs,
            raftFilePreviewVideoContainer(fileName = "movie.mts", mimeType = "video/mp2t")
        )
    }
}
