package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftVideoAttachmentPlatformAndroidTest {
    @Test
    fun media3ProgressiveVideoContainersArePreviewable() {
        listOf(
            "clip.mp4" to "video/mp4",
            "camera.mov" to "video/quicktime",
            "clip.m4v" to "video/x-m4v",
            "clip.3gp" to "video/3gpp",
            "clip.3g2" to "video/3gpp2",
            "clip.webm" to "video/webm",
            "clip.mkv" to "video/x-matroska",
            "clip.m2ts" to "video/mp2t",
            "clip.mpg" to "video/mpeg",
            "clip.flv" to "video/x-flv"
        ).forEach { (fileName, mimeType) ->
            assertTrue(raftFilePreviewIsPreviewableVideoAttachment(fileName, mimeType), fileName)
        }
        assertFalse(raftFilePreviewIsPreviewableVideoAttachment("clip.ts", "video/mp2t"))
        assertFalse(raftFilePreviewIsPreviewableVideoAttachment("clip.avi", "video/x-msvideo"))
    }
}
