package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentDownloadFailurePayloadTest {
    @Test
    fun storageFailureKindsMapToStorageFailed() {
        for (errorKind in listOf("storage_failed", "storage_write", "storage_prepare", "storage_publish")) {
            val payload =
                attachmentDownloadFailurePayload(
                    AttachmentDownloadFailedException(errorKind, "Unable to write download staging file")
                )

            assertEquals("storage_failed", payload["status"])
            assertEquals(false, payload["cached"])
        }
    }

    @Test
    fun networkAndUnknownKindsMapToDownloadFailed() {
        for (errorKind in listOf(null, "network_error", "timeout", "invalid_request", "storage_read")) {
            val payload =
                attachmentDownloadFailurePayload(
                    AttachmentDownloadFailedException(errorKind, "Attachment download failed")
                )

            assertEquals("download_failed", payload["status"])
            assertEquals(false, payload["cached"])
        }
    }

    @Test
    fun messagePassesThroughFromTheException() {
        val payload =
            attachmentDownloadFailurePayload(
                AttachmentDownloadFailedException("storage_write", "Unable to write download staging file")
            )
        assertEquals("Unable to write download staging file", payload["message"])
    }
}
