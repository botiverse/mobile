package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentSavePreparationFailurePayloadTest {
    @Test
    fun downloadFailureKeepsRightCauseMapping() {
        val payload =
            attachmentSavePreparationFailurePayload(
                AttachmentDownloadFailedException("network_error", "Attachment download timed out")
            )

        assertEquals("download_failed", payload["status"])
        assertEquals(false, payload["cached"])
    }

    @Test
    fun storageFailureKindMapsToStorageFailed() {
        val payload =
            attachmentSavePreparationFailurePayload(
                AttachmentDownloadFailedException("storage_write", "Unable to write download staging file")
            )

        assertEquals("storage_failed", payload["status"])
        assertEquals(false, payload["cached"])
    }

    @Test
    fun stoppedPreparationMapsToCancelledInsteadOfDownloadFailed() {
        val payload = attachmentSavePreparationFailurePayload(AttachmentDownloadStoppedException())

        assertEquals("cancelled", payload["status"])
        assertEquals(false, payload["cached"])
    }

    @Test
    fun unpreparedCacheOrFileExceptionMapsToStorageFailed() {
        val payload = attachmentSavePreparationFailurePayload(IllegalStateException("cache directory vanished"))

        assertEquals("storage_failed", payload["status"])
        assertEquals(false, payload["cached"])
    }
}
