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
    fun pickedAttachmentCacheNameInfersExtensionWhenMissing() {
        val name =
                safePickedThreadAttachmentFileName(
                        fileName = "clipboard image",
                        mimeType = "image/png",
                        nowMillis = 30L,
                        nanoTime = 40L,
                        extensionForMimeType = { "png" }
                )

        assertEquals("picked-30-40-clipboard image.png", name)
    }

    @Test
    fun pickedAttachmentCacheNameCapsLongFileNames() {
        val name =
                safePickedThreadAttachmentFileName(
                        fileName = "a".repeat(200),
                        mimeType = "image/webp",
                        nowMillis = 50L,
                        nanoTime = 60L,
                        extensionForMimeType = { "webp" }
                )

        assertTrue(name.startsWith("picked-50-60-"))
        assertTrue(name.endsWith(".webp"))
        assertTrue(name.length <= "picked-50-60-".length + 120 + ".webp".length)
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

    @Test
    fun staleDeleteFailureDoesNotConsumeRecentFileRetentionBudget() {
        val cacheDir = Files.createTempDirectory("picked-attachment-delete-failure").toFile()
        try {
            val now = 10_000L
            val stale = cacheDir.resolve("stale.png").apply { writeText("stale"); setLastModified(now - 1_001L) }
            val recentOld = cacheDir.resolve("recent-old.png").apply { writeText("old"); setLastModified(now - 900L) }
            val recentNew = cacheDir.resolve("recent-new.png").apply { writeText("new"); setLastModified(now - 800L) }

            val result =
                    cleanupPickedThreadAttachmentCache(
                            cacheDir = cacheDir,
                            nowMillis = now,
                            maxAgeMillis = 1_000L,
                            maxFiles = 2,
                            deleteFile = { file -> if (file == stale) false else file.delete() }
                    )

            assertEquals(0, result.deletedStaleCount)
            assertEquals(0, result.deletedOverflowCount)
            assertEquals(3, result.retainedCount)
            assertTrue(stale.exists())
            assertTrue(recentOld.exists())
            assertTrue(recentNew.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
