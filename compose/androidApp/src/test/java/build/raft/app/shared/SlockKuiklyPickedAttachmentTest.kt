package build.raft.app.shared

import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockKuiklyPickedAttachmentTest {
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
}
