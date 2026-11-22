package build.raft.app.shared

import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RaftPickedAttachmentTest {
    @Test
    fun mediaAttachmentPickerAllowsImagesAndVideos() {
        assertSame(
                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                threadMediaAttachmentPickerRequest().mediaType
        )
    }

    @Test
    fun pickedAttachmentCacheNameSanitizesUnsafePathSegments() {
        val name =
                safePickedThreadAttachmentFileName(
                        fileName = "../Camera/DCIM/photo:name?.jpg",
                        mimeType = "image/jpeg",
                        nowMillis = 10L,
                        nanoTime = 20L,
                        extensionForMimeType = { "jpeg" }
                )

        assertEquals("picked-10-20-photo_name_.jpg", name)
    }

    @Test
    fun pickedAttachmentCacheCleanupDeletesStaleAndOverflowButKeepsRecentDrafts() {
        val cacheDir = Files.createTempDirectory("picked-attachment-cleanup").toFile()
        try {
            val now = 10_000L
            val stale = cacheDir.resolve("stale.png").apply { writeText("stale"); setLastModified(now - 1_001L) }
            val recentOldest = cacheDir.resolve("recent-oldest.png").apply { writeText("a"); setLastModified(now - 900L) }
            val recentMiddle = cacheDir.resolve("recent-middle.png").apply { writeText("b"); setLastModified(now - 800L) }
            val recentNewest = cacheDir.resolve("recent-newest.png").apply { writeText("c"); setLastModified(now - 700L) }

            val result =
                    cleanupPickedThreadAttachmentCache(
                            cacheDir = cacheDir,
                            nowMillis = now,
                            maxAgeMillis = 1_000L,
                            maxFiles = 2
                    )

            assertEquals(1, result.deletedStaleCount)
            assertEquals(1, result.deletedOverflowCount)
            assertEquals(2, result.retainedCount)
            assertFalse(stale.exists())
            assertFalse(recentOldest.exists())
            assertTrue(recentMiddle.exists())
            assertTrue(recentNewest.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
