package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A failed attachment save (thread, file page, download centre) says catalog copy chosen by the
 * host's status word; the host's own message ("Unable to create Downloads file", "Another file
 * save is already active", "Save timed out") only reaches the log.
 */
class AttachmentSaveFailureCopyTest {
    private val downloads = RaftAttachmentSaveDestination.Downloads
    private val photos = RaftAttachmentSaveDestination.Photos
    private val files = RaftAttachmentSaveDestination.FilesExport

    @Test
    fun successAndCancelAreNotFailures() {
        listOf("saved", "exported", "cancelled").forEach { status ->
            RaftAttachmentSaveDestination.entries.forEach { destination ->
                assertNull(raftAttachmentSaveFailure(status, destination), "$status / $destination")
            }
        }
    }

    @Test
    fun statusWordsMapToCatalogCopyPerDestination() {
        assertEquals(RaftAttachmentSaveFailure.DownloadFailed, raftAttachmentSaveFailure("download_failed", downloads))
        assertEquals(RaftAttachmentSaveFailure.DownloadFailed, raftAttachmentSaveFailure("download_failed", photos))

        assertEquals(RaftAttachmentSaveFailure.StorageUnavailable, raftAttachmentSaveFailure("storage_failed", downloads))
        assertEquals(RaftAttachmentSaveFailure.PhotosSaveFailed, raftAttachmentSaveFailure("storage_failed", photos))
        assertEquals(RaftAttachmentSaveFailure.FilesExportFailed, raftAttachmentSaveFailure("storage_failed", files))

        assertEquals(RaftAttachmentSaveFailure.PhotosPermissionDenied, raftAttachmentSaveFailure("permission_denied", photos))
        assertEquals(RaftAttachmentSaveFailure.PhotosPermissionRestricted, raftAttachmentSaveFailure("permission_restricted", photos))
        assertEquals(RaftAttachmentSaveFailure.PhotosUnsupportedImage, raftAttachmentSaveFailure("unsupported_image", photos))
        assertEquals(RaftAttachmentSaveFailure.PhotosSaveFailed, raftAttachmentSaveFailure("photos_failed", photos))

        // iOS "failed" (a second save while one runs, no URL, no export authority), an empty
        // result and any future word read as the destination's generic failure.
        listOf("failed", "", "url_unavailable", "something_new").forEach { status ->
            assertEquals(RaftAttachmentSaveFailure.UnableToDownload, raftAttachmentSaveFailure(status, downloads), status)
            assertEquals(RaftAttachmentSaveFailure.PhotosSaveFailed, raftAttachmentSaveFailure(status, photos), status)
            assertEquals(RaftAttachmentSaveFailure.FilesExportFailed, raftAttachmentSaveFailure(status, files), status)
        }
    }

    @Test
    fun everyFailureHasItsOwnCatalogString() {
        assertEquals(Res.string.file_preview_download_failed, RaftAttachmentSaveFailure.DownloadFailed.messageRes)
        assertEquals(Res.string.file_preview_error_storage_unavailable, RaftAttachmentSaveFailure.StorageUnavailable.messageRes)
        assertEquals(Res.string.file_preview_error_download_attachment, RaftAttachmentSaveFailure.UnableToDownload.messageRes)
        assertEquals(Res.string.file_preview_export_failed, RaftAttachmentSaveFailure.FilesExportFailed.messageRes)
        assertEquals(Res.string.thread_image_preview_photos_save_failed, RaftAttachmentSaveFailure.PhotosSaveFailed.messageRes)
        assertEquals(
            RaftAttachmentSaveFailure.entries.size,
            RaftAttachmentSaveFailure.entries.map { it.messageRes }.toSet().size,
            "each failure says something different"
        )
    }

    @Test
    fun hostMessagesAreLoggedOnOneBoundedLineWithoutUrls() {
        assertEquals("none", raftAttachmentHostMessageForLog(null))
        assertEquals("none", raftAttachmentHostMessageForLog("  "))
        assertEquals("Unable to create Downloads file", raftAttachmentHostMessageForLog("Unable to create Downloads file"))
        val logged = raftAttachmentHostMessageForLog("Download failed:\nHTTP 403 https://cdn.example.test/a?sig=secret")
        assertEquals("Download failed: HTTP 403 <url>", logged)
        assertFalse(logged.contains("secret"))
        assertTrue(raftAttachmentHostMessageForLog("x".repeat(500)).length <= 160)
    }
}
