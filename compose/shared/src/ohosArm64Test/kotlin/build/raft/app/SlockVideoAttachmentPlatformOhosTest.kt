package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockVideoAttachmentPlatformOhosTest {
    @Test
    fun arkUiVideoDocumentedContainersArePreviewable() {
        listOf(
            "clip.mp4" to "video/mp4",
            "clip.mkv" to "video/x-matroska",
            "clip.ts" to "video/mp2t"
        ).forEach { (fileName, mimeType) ->
            assertTrue(slockFilePreviewIsPreviewableVideoAttachment(fileName, mimeType), fileName)
        }
        assertFalse(slockFilePreviewIsPreviewableVideoAttachment("camera.mov", "video/quicktime"))
        assertFalse(slockFilePreviewIsPreviewableVideoAttachment("clip.webm", "video/webm"))
        assertFalse(slockFilePreviewIsPreviewableVideoAttachment("clip.flv", "video/x-flv"))
    }
}
