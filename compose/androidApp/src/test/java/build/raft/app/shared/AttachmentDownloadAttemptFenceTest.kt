package build.raft.app.shared

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDownloadAttemptFenceTest {
    @Test
    fun stoppedAttemptCleanupCannotDeleteOrPublishImmediateRetry() {
        val directory = Files.createTempDirectory("attachment-attempt-fence").toFile()
        try {
            val target = File(directory, "attachment.pdf")
            val oldDownloadId = "attempt-old:attachment"
            val newDownloadId = "attempt-new:attachment"
            val oldTemp = attachmentDownloadTempFile(target, oldDownloadId)
            val newTemp = attachmentDownloadTempFile(target, newDownloadId)
            val fence = AttachmentDownloadAttemptFence()

            assertNotEquals(oldTemp, newTemp)
            oldTemp.writeText("old")
            fence.begin(target, oldDownloadId)

            newTemp.writeText("new")
            fence.begin(target, newDownloadId)

            assertFalse(fence.isCurrent(target, oldDownloadId))
            assertTrue(fence.isCurrent(target, newDownloadId))

            oldTemp.delete()
            fence.end(target, oldDownloadId)

            assertTrue("old finally must not remove the retry staging file", newTemp.isFile)
            assertTrue("old finally must not clear the retry publication owner", fence.isCurrent(target, newDownloadId))
            assertTrue(attachmentDownloadPartFiles(target).contains(newTemp))
        } finally {
            directory.deleteRecursively()
        }
    }
}
