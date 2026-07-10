package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockFilePreviewCommentsTest {
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
