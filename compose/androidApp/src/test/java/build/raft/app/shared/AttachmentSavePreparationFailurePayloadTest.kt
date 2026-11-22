package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentSavePreparationFailurePayloadTest {
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
}
