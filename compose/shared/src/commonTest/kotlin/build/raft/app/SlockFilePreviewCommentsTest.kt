package build.raft.app

import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.toThreadAttachmentCommentsFailureProjection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun attachmentCommentDraftRetainsExactEditorSnapshotAcrossNonTextAndSameTextEvents() {
        val nativeValue =
            TextFieldValue(
                text = "你好\nreview",
                selection = TextRange(1, 4),
                composition = TextRange(0, 2)
            )
        val editing =
            ThreadAttachmentCommentsDialogState(
                attachment = KuiklyThreadAttachmentItem(id = "attachment-1", name = "runbook.md"),
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
    fun attachmentCommentOwnerKeepsCumulativeMidTextDeleteAndRetypeSnapshotsExact() {
        val nativeValues =
            listOf(
                TextFieldValue("n", selection = TextRange(1), composition = TextRange(0, 1)),
                TextFieldValue("ni", selection = TextRange(2), composition = TextRange(0, 2)),
                TextFieldValue("你好", selection = TextRange(1)),
                TextFieldValue("你X好", selection = TextRange(2)),
                TextFieldValue("你好", selection = TextRange(1)),
                TextFieldValue("你再好", selection = TextRange(2), composition = TextRange(1, 2))
            )
        var state =
            ThreadAttachmentCommentsDialogState(
                attachment = KuiklyThreadAttachmentItem(id = "attachment-1", name = "runbook.md"),
                status = "loaded",
                errorCode = "old_error"
            )
        val retained = mutableListOf<TextFieldValue>()

        nativeValues.forEach { nativeValue ->
            state = state.withAttachmentCommentEditorValue(nativeValue)
            retained += state.draft
            assertSame(nativeValue, state.draft)
        }

        assertEquals(listOf("n", "ni", "你好", "你X好", "你好", "你再好"), retained.map { it.text })
        assertEquals(TextRange(2), retained.last().selection)
        assertEquals(TextRange(1, 2), retained.last().composition)
        assertNull(state.errorCode)
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
                attachment = KuiklyThreadAttachmentItem(id = "attachment-1", name = "runbook.md"),
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
        listOf(
            "demo.mp4" to "video/mp4",
            "DEMO.MP4" to "application/octet-stream"
        ).forEach { (name, mimeType) ->
            val payload =
                KuiklyThreadAttachmentItem(
                    id = "attachment-video",
                    name = name,
                    mimeType = mimeType
                ).toThreadAttachmentCommentsPreviewPayload(commentsEnabled = true)

            assertNull(payload, "inline preview must keep comments outside the file viewer for $name")
        }
    }

    @Test
    fun workspaceAndVideoPreviewsDoNotExposeAttachmentComments() {
        assertFalse(agentWorkspaceLazyPreviewPayload(agentId = "agent-1", path = "notes.md").supportsAttachmentComments())
        listOf(
            "demo.mp4" to "video/mp4",
            "DEMO.MP4" to "application/octet-stream"
        ).forEach { (name, mimeType) ->
            assertFalse(
                KuiklyThreadAttachmentItem(
                    id = "attachment-video",
                    name = name,
                    mimeType = mimeType
                ).toThreadAttachmentLazyPreviewPayload(kind = "video").supportsAttachmentComments()
            )
        }
        assertNull(
            KuiklyThreadAttachmentItem(
                id = "attachment-markdown",
                name = "runbook.md",
                mimeType = "text/markdown"
            ).toThreadAttachmentCommentsPreviewPayload(commentsEnabled = false)
        )
    }
}
