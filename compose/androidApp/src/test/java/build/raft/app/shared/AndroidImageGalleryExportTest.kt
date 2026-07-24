package build.raft.app.shared

import android.database.SQLException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun legacyCandidatePreservesReadableBasenameAndRealExtension() {
        assertEquals("photo.jpg", legacyAndroidGalleryCandidateName("photo.jpg", 0))
        assertEquals("photo (1).jpg", legacyAndroidGalleryCandidateName("photo.jpg", 1))
        assertEquals("family.photo (2).jpeg", legacyAndroidGalleryCandidateName("family.photo.jpeg", 2))
        assertEquals("attachment (3)", legacyAndroidGalleryCandidateName("attachment", 3))
    }

    @Test
    fun twoSameNameLegacySavesCreateDifferentRowsAndPaths() {
        val directory = File("/Pictures/Raft")
        val occupiedPaths = linkedSetOf<String>()
        var nextRow = 1
        val insert: (String, String) -> String? = { _, path ->
            if (!occupiedPaths.add(path)) null else "content://images/${nextRow++}"
        }

        val first = insertLegacyAndroidGalleryImage(directory, "photo.jpg", { it.absolutePath in occupiedPaths }, insert)
        val second = insertLegacyAndroidGalleryImage(directory, "photo.jpg", { it.absolutePath in occupiedPaths }, insert)

        assertEquals("content://images/1", first?.uri)
        assertEquals("photo.jpg", first?.displayName)
        assertEquals("content://images/2", second?.uri)
        assertEquals("photo (1).jpg", second?.displayName)
        assertTrue(first?.absolutePath != second?.absolutePath)
    }

    @Test
    fun preExistingLegacyFileIsUntouchedAndSkipped() {
        val directory = File("/Pictures/Raft")
        val preExisting = File(directory, "photo.jpg").absolutePath
        val inserted = mutableListOf<String>()

        val result = insertLegacyAndroidGalleryImage(
            directory = directory,
            requestedDisplayName = "photo.jpg",
            pathExists = { it.absolutePath == preExisting },
            insertCandidate = { _, path ->
                inserted += path
                "content://images/new"
            }
        )

        assertEquals("photo (1).jpg", result?.displayName)
        assertEquals(listOf(File(directory, "photo (1).jpg").absolutePath), inserted)
        assertTrue(preExisting !in inserted)
    }

    @Test
    fun legacyInsertRaceRetriesFreshCandidate() {
        val directory = File("/Pictures/Raft")
        val attemptedNames = mutableListOf<String>()

        val result = insertLegacyAndroidGalleryImage(
            directory = directory,
            requestedDisplayName = "photo.png",
            pathExists = { false },
            insertCandidate = { name, _ ->
                attemptedNames += name
                if (attemptedNames.size == 1) null else "content://images/2"
            }
        )

        assertEquals(listOf("photo.png", "photo (1).png"), attemptedNames)
        assertEquals("photo (1).png", result?.displayName)
        assertEquals("content://images/2", result?.uri)
    }

    @Test
    fun legacyConstraintExceptionRetriesFreshCandidate() {
        val attemptedNames = mutableListOf<String>()

        val result = insertLegacyAndroidGalleryImage(
            directory = File("/Pictures/Raft"),
            requestedDisplayName = "photo.webp",
            pathExists = { false },
            insertCandidate = { name, _ ->
                attemptedNames += name
                if (attemptedNames.size == 1) throw SQLException("unique _data")
                "content://images/2"
            }
        )

        assertEquals(listOf("photo.webp", "photo (1).webp"), attemptedNames)
        assertEquals("photo (1).webp", result?.displayName)
    }

    @Test
    fun persistentLegacyInsertFailureIsBounded() {
        var attempts = 0

        val result = insertLegacyAndroidGalleryImage(
            directory = File("/Pictures/Raft"),
            requestedDisplayName = "photo.jpg",
            pathExists = { false },
            insertCandidate = { _, _ ->
                attempts += 1
                null
            }
        )

        assertNull(result)
        assertEquals(LEGACY_ANDROID_GALLERY_MAX_INSERT_ATTEMPTS, attempts)
    }
}
