package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataResultPresentationTest {
    @Test
    fun attachmentTooLargeRequiresAnExactStructuredCode() {
        assertEquals(
            ErrorPresentationKey.AttachmentTooLarge,
            "attachment_too_large".errorPresentationKeyOrNull()
        )
        assertEquals(
            ErrorPresentationKey.AttachmentTooLarge,
            "UPLOAD_TOO_LARGE".errorPresentationKeyOrNull()
        )

        assertNull("body_too_large".errorPresentationKeyOrNull())
        assertNull("attachment_size_unavailable".errorPresentationKeyOrNull())
    }
}
