package build.raft.app.shared

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentDirectDownloadCacheTest {
    @Test
    fun deleteRemovesPublishedTargetAndDirectDownloadPartFiles() {
        val directory = Files.createTempDirectory("direct-attachment-cache").toFile()
        try {
            val target = directory.resolve("attachment.apk").apply { writeText("published") }
            val legacyPart = directory.resolve("attachment.apk.part").apply { writeText("legacy") }
            val attemptPart = directory.resolve("attachment.apk.part-attempt-1").apply { writeText("attempt") }
            val unrelated = directory.resolve("attachment.apk.partial").apply { writeText("keep") }

            assertEquals("deleted", deleteDirectAttachmentCache(target, expectedAttemptToken = null))

            assertFalse(target.exists())
            assertFalse(legacyPart.exists())
            assertFalse(attemptPart.exists())
            assertTrue(unrelated.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun deleteIsIdempotentWhenDirectDownloadCacheIsMissing() {
        val directory = Files.createTempDirectory("missing-direct-attachment-cache").toFile()
        try {
            assertEquals(
                "deleted",
                deleteDirectAttachmentCache(directory.resolve("missing.apk"), expectedAttemptToken = null)
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
