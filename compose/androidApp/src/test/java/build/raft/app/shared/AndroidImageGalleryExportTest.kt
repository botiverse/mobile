package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidImageGalleryExportTest {
    @Test
    fun imageMimeAliasesNormalizeToStableTypes() {
        assertEquals("image/jpeg", normalizeAndroidImageMimeType("image/jpg"))
        assertEquals("image/jpeg", normalizeAndroidImageMimeType("image/pjpeg"))
        assertEquals("image/png", normalizeAndroidImageMimeType("image/x-png"))
        assertEquals("image/heif", normalizeAndroidImageMimeType("image/heic"))
    }

    @Test
    fun nonImageMimeDoesNotNormalize() {
        assertNull(normalizeAndroidImageMimeType("application/pdf"))
        assertNull(normalizeAndroidImageMimeType("image/svg+xml"))
    }

    @Test
    fun claimedMimeMustMatchDecodedMime() {
        assertEquals(
            true,
            androidGalleryImageMetadataMatches("photo.jpg", "image/jpeg; charset=binary", "image/jpeg")
        )
        assertEquals(
            false,
            androidGalleryImageMetadataMatches("photo.jpg", "image/png", "image/jpeg")
        )
    }

    @Test
    fun knownFileExtensionMustMatchDecodedMime() {
        assertEquals(true, androidGalleryImageMetadataMatches("photo.jpeg", null, "image/jpeg"))
        assertEquals(false, androidGalleryImageMetadataMatches("photo.png", null, "image/jpeg"))
        assertEquals(true, androidGalleryImageMetadataMatches("attachment", null, "image/png"))
    }

    @Test
    fun existingUnknownOrNonImageMimeClaimFailsClosed() {
        assertEquals(
            false,
            androidGalleryImageMetadataMatches("photo.heic", "application/octet-stream", "image/heif")
        )
        assertEquals(
            false,
            androidGalleryImageMetadataMatches("photo.heic", "application/pdf", "image/heif")
        )
    }

    @Test
    fun existingUnknownExtensionFailsClosedWhileMissingExtensionUsesDetectedType() {
        assertEquals(false, androidGalleryImageMetadataMatches("photo.unknown", null, "image/png"))
        assertEquals(true, androidGalleryImageMetadataMatches("photo", null, "image/png"))
    }

}
