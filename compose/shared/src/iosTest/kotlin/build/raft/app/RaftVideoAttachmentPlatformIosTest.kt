package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftVideoAttachmentPlatformIosTest {
    @Test
    fun avPlayerFileVideoFamiliesArePreviewable() {
        listOf(
            "clip.mp4" to "video/mp4",
            "camera.mov" to "video/quicktime",
            "clip.m4v" to "video/x-m4v",
            "clip.3gp" to "video/3gpp",
            "clip.3g2" to "video/3gpp2"
        ).forEach { (fileName, mimeType) ->
            assertTrue(raftFilePreviewIsPreviewableVideoAttachment(fileName, mimeType), fileName)
        }
        assertFalse(raftFilePreviewIsPreviewableVideoAttachment("clip.webm", "video/webm"))
        assertFalse(raftFilePreviewIsPreviewableVideoAttachment("clip.mkv", "video/x-matroska"))
        assertFalse(raftFilePreviewIsPreviewableVideoAttachment("clip.ts", "video/mp2t"))
    }
}
