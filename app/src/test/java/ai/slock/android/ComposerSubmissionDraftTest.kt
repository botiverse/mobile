package ai.slock.android

import ai.slock.android.core.data.repository.AttachmentUploadFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSubmissionDraftTest {
    @Test
    fun hasContentUsesTrimmedBody() {
        val draft =
                ComposerSubmissionDraft(
                        value = "  hello world  ",
                        asTask = false,
                        attachments = emptyList(),
                        failedAttachmentIds = emptySet()
                )

        assertEquals("hello world", draft.body)
        assertTrue(draft.hasContent)
    }

    @Test
    fun attachmentsKeepDraftSendableWhenBodyIsBlank() {
        val draft =
                ComposerSubmissionDraft(
                        value = "   ",
                        asTask = false,
                        attachments =
                                listOf(
                                        AttachmentUploadFile(
                                                id = "attachment-1",
                                                fileName = "preview.png",
                                                mimeType = "image/png",
                                                sizeBytes = 12L,
                                                previewUri = "content://preview",
                                                bytes = byteArrayOf(1, 2, 3)
                                        )
                                ),
                        failedAttachmentIds = emptySet()
                )

        assertTrue(draft.hasContent)
    }

    @Test
    fun uploadFailureMarksAllAttachmentsFailed() {
        val draft =
                ComposerSubmissionDraft(
                        value = "hello",
                        asTask = true,
                        attachments =
                                listOf(
                                        attachmentFile("attachment-1"),
                                        attachmentFile("attachment-2")
                                ),
                        failedAttachmentIds = emptySet()
                )

        assertEquals(
                setOf("attachment-1", "attachment-2"),
                draft.withAllAttachmentsFailed().failedAttachmentIds
        )
    }

    @Test
    fun emptyDraftWithoutBodyOrAttachmentsIsNotSendable() {
        val draft =
                ComposerSubmissionDraft(
                        value = "   ",
                        asTask = false,
                        attachments = emptyList(),
                        failedAttachmentIds = emptySet()
                )

        assertFalse(draft.hasContent)
    }

    private fun attachmentFile(id: String) =
            AttachmentUploadFile(
                    id = id,
                    fileName = "$id.png",
                    mimeType = "image/png",
                    sizeBytes = 12L,
                    previewUri = "content://$id",
                    bytes = byteArrayOf(1, 2, 3)
            )
}
