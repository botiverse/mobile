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
}
