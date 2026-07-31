package build.raft.app

import build.raft.app.data.ThreadApiResult
import build.raft.app.data.toThreadAttachmentCommentsFailureProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockFilePreviewCommentsTest {
    private val errorLabels =
        ThreadUserFacingErrorLabels(
            ThreadUserFacingErrorReason.entries.associateWith { reason -> "localized:${reason.name}" }
        )

    @Test
    fun attachmentCommentFailureShowsKnownLocalizedThenRawThenGeneric() {
        assertEquals(
            "localized:ServerUnavailable",
            attachmentCommentsVisibleErrorMessage(
                errorCode = "http_503",
                serverMessage = "raw server maintenance detail",
                labels = errorLabels,
                genericMessage = "generic"
            )
        )
        assertEquals(
            "Attachment comments are not enabled on this server",
            attachmentCommentsVisibleErrorMessage(
                errorCode = "attachment_comments_disabled",
                serverMessage = "Attachment comments are not enabled on this server",
                labels = errorLabels,
                genericMessage = "generic"
            )
        )
        assertEquals(
            "generic",
            attachmentCommentsVisibleErrorMessage(
                errorCode = "attachment_comments_disabled",
                serverMessage = "  ",
                labels = errorLabels,
                genericMessage = "generic"
            )
        )
    }

    @Test
    fun filePreviewFailureProjectionReachesVisibleCopyAndClearsAsOneState() {
        val failure =
            ThreadApiResult.Failure(
                code = 409,
                errorBodyText =
                    """{"error":"This channel is archived","code":"channel_archived"}"""
            ).toThreadAttachmentCommentsFailureProjection()
        val failedState =
            ThreadAttachmentCommentsDialogState(
                attachment = KuiklyThreadAttachmentItem(id = "attachment-1", name = "runbook.md"),
                status = "error"
            ).withAttachmentCommentsError(failure.errorCode, failure.serverMessage)

        assertEquals("channel_archived", failedState.errorCode)
        assertEquals("This channel is archived", failedState.errorServerMessage)
        assertEquals(
            "This channel is archived",
            attachmentCommentsVisibleErrorMessage(
                errorCode = failedState.errorCode,
                serverMessage = failedState.errorServerMessage,
                labels = errorLabels,
                genericMessage = "generic"
            )
        )

        val retrying = failedState.copy(status = "loading").clearAttachmentCommentsError()
        assertNull(retrying.errorCode)
        assertNull(retrying.errorServerMessage)
    }

    @Test
    fun nonVideoAttachmentCommentsOpenInsideFilePreview() {
        val payload =
            KuiklyThreadAttachmentItem(
                id = "attachment-markdown",
                name = "runbook.md",
                mimeType = "text/markdown"
            ).toThreadAttachmentCommentsPreviewPayload(commentsEnabled = true)

        assertNotNull(payload)
        assertEquals("markdown", payload.requestedKind)
        assertTrue(payload.commentsInitiallyOpen)
        assertTrue(payload.supportsAttachmentComments())
    }

    @Test
    fun inlineVideoKeepsConversationCommentExperience() {
        val payload =
            KuiklyThreadAttachmentItem(
                id = "attachment-video",
                name = "demo.mp4",
                mimeType = "video/mp4"
            ).toThreadAttachmentCommentsPreviewPayload(commentsEnabled = true)

        assertNull(payload)
    }

    @Test
    fun workspaceAndVideoPreviewsDoNotExposeAttachmentComments() {
        assertFalse(agentWorkspaceLazyPreviewPayload(agentId = "agent-1", path = "notes.md").supportsAttachmentComments())
        assertFalse(
            KuiklyThreadAttachmentItem(
                id = "attachment-video",
                name = "demo.mp4",
                mimeType = "video/mp4"
            ).toThreadAttachmentLazyPreviewPayload(kind = "video").supportsAttachmentComments()
        )
        assertNull(
            KuiklyThreadAttachmentItem(
                id = "attachment-markdown",
                name = "runbook.md",
                mimeType = "text/markdown"
            ).toThreadAttachmentCommentsPreviewPayload(commentsEnabled = false)
        )
    }
}
