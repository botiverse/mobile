package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidImageGalleryExportTest {
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

}
