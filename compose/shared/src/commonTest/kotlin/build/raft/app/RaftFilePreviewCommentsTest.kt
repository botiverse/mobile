package build.raft.app

import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.ThreadAttachmentCommentsDialogState
import build.raft.app.thread.ThreadUserFacingErrorLabels
import build.raft.app.thread.ThreadUserFacingErrorReason
import build.raft.app.thread.attachmentCommentsVisibleErrorMessage
import build.raft.app.thread.supportsThreadAttachmentComments
import build.raft.app.thread.withAttachmentCommentDraftProjection
import build.raft.app.thread.withQuotedAttachmentComment
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftFilePreviewCommentsTest {
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
    fun attachmentCommentDraftRetainsExactEditorSnapshotAcrossNonTextAndSameTextEvents() {
        val nativeValue =
            TextFieldValue(
                text = "你好\nreview",
                selection = TextRange(1, 4),
                composition = TextRange(0, 2)
            )
        val editing =
            ThreadAttachmentCommentsDialogState(
                attachment = RaftThreadAttachmentItem(id = "attachment-1", name = "runbook.md"),
                status = "loaded",
                draft = nativeValue
            )

        val chromeUpdate = editing.copy(filter = "unresolved", sending = true)
        val sameTextProjection = chromeUpdate.withAttachmentCommentDraftProjection(nativeValue.text)

        assertSame(nativeValue, chromeUpdate.draft)
        assertSame(chromeUpdate, sameTextProjection)
        assertSame(nativeValue, sameTextProjection.draft)
        assertEquals(TextRange(1, 4), sameTextProjection.draft.selection)
        assertEquals(TextRange(0, 2), sameTextProjection.draft.composition)
    }

    @Test
    fun attachmentCommentQuoteIsAnIntentionalReplaceWithCursorAtNewTextEnd() {
        val composingValue =
            TextFieldValue(
                text = "Draft",
                selection = TextRange(2),
                composition = TextRange(0, 5)
            )
        val state =
            ThreadAttachmentCommentsDialogState(
                attachment = RaftThreadAttachmentItem(id = "attachment-1", name = "runbook.md"),
                status = "loaded",
                draft = composingValue,
                errorCode = "old_error"
            )
        val comment =
            ThreadAttachmentCommentItem(
                id = "comment-1",
                channelId = "channel-1",
                senderType = "user",
                senderId = "user-1",
                senderName = "Artea",
                content = "Please keep this line.",
                createdAt = "2026-08-03T03:00:00Z",
                reactions = emptyList(),
                anchorLabel = "line 4"
            )

        val quoted = state.withQuotedAttachmentComment(comment)

        assertEquals("Draft\n\n> Please keep this line.\n\n", quoted.draft.text)
        assertEquals(TextRange(quoted.draft.text.length), quoted.draft.selection)
        assertNull(quoted.draft.composition)
        assertEquals("Artea", quoted.pendingQuoteLabel)
        assertEquals("line 4", quoted.pendingAnchorLabel)
        assertNull(quoted.errorCode)
    }

    @Test
    fun commentableAttachmentCommentsOpenInsideFilePreview() {
        val payload =
            RaftThreadAttachmentItem(
                id = "attachment-markdown",
                name = "runbook.md",
                mimeType = "text/markdown"
            ).toThreadAttachmentCommentsPreviewPayload(commentsEnabled = true)

        assertNotNull(payload)
        assertEquals("markdown", payload.requestedKind)
        assertTrue(payload.commentsInitiallyOpen)
        assertTrue(payload.supportsAttachmentComments())
    }

    // Per-type entry points, matching web (MessageItem.handleOpenAttachmentCard + attachmentPreviewSurfaces).
    private fun item(name: String, mime: String) =
        RaftThreadAttachmentItem(id = "attachment-1", name = name, mimeType = mime)

    @Test
    fun viewerCommentsFollowWebPerAttachmentType() {
        val commentable =
            listOf(
                "runbook.md" to "text/markdown",
                "main.kt" to "text/plain",
                "data.csv" to "text/csv",
                "sheet.xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "page.html" to "text/html"
            )
        commentable.forEach { (name, mime) ->
            val payload = item(name, mime).toThreadAttachmentCommentsPreviewPayload(commentsEnabled = true)
            assertNotNull(payload, "web offers comments on $name")
            assertTrue(payload.supportsAttachmentComments(), name)
            assertTrue(item(name, mime).supportsThreadAttachmentComments(), name)
        }
        val notCommentable =
            listOf(
                "photo.png" to "image/png",
                "paper.pdf" to "application/pdf",
                "song.mp3" to "audio/mpeg",
                "archive.zip" to "application/zip",
                "blob.bin" to "application/octet-stream"
            )
        notCommentable.forEach { (name, mime) ->
            assertNull(item(name, mime).toThreadAttachmentCommentsPreviewPayload(commentsEnabled = true), name)
            assertFalse(item(name, mime).supportsThreadAttachmentComments(), name)
            assertFalse(
                item(name, mime).toThreadAttachmentLazyPreviewPayload(kind = "file", attachmentCommentsEnabled = true)
                    .supportsAttachmentComments(),
                name
            )
        }
        // Video keeps its comments dialog (web has timestamp comments, a follow-up here), never the viewer.
        assertTrue(item("demo.mp4", "video/mp4").supportsThreadAttachmentComments())
    }
}
