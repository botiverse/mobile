package ai.slock.compose

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadComposerLayoutTest {
    @Test
    fun threadComposerBottomPaddingIncludesClosedKeyboardSafeArea() {
        assertEquals(38.dp, threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingDoesNotDoubleCountSafeAreaWhenKeyboardOpen() {
        assertEquals(134.dp, threadComposerBottomPadding(keyboardInsetDp = 120f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingKeepsSmallLiftWithoutSafeArea() {
        assertEquals(14.dp, threadComposerBottomPadding(keyboardInsetDp = -1f, bottomSafeArea = 0.dp))
    }

    @Test
    fun mentionSuggestionInsertMovesCursorAfterInsertedMention() {
        val edit = applyKuiklyComposerSuggestionEdit(
            text = "ping @ar",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5,
            replacement = "@artin "
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "ping @artin ", cursor = 12), edit)
    }

    @Test
    fun mentionSuggestionInsertPreservesSuffixWithoutDoubleSpace() {
        val edit = applyKuiklyComposerSuggestionEdit(
            text = "ping @ar world",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5,
            replacement = "@artin "
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "ping @artin world", cursor = 12), edit)
    }

    @Test
    fun composerAttachmentRowsUseReactImageAndFileWidths() {
        val rows =
            composerAttachmentRows(
                listOf(
                    KuiklyPendingAttachmentItem(
                        id = "image",
                        fileName = "composer-preview.png",
                        mimeType = "image/png",
                        previewUri = "data:image/png;base64,"
                    ),
                    KuiklyPendingAttachmentItem(
                        id = "report",
                        fileName = "release-notes.pdf",
                        mimeType = "application/pdf"
                    ),
                    KuiklyPendingAttachmentItem(
                        id = "logs",
                        fileName = "android-smoke-log.zip",
                        mimeType = "application/zip"
                    ),
                    KuiklyPendingAttachmentItem(
                        id = "trace",
                        fileName = "oversized-trace.jsonl",
                        mimeType = "application/json"
                    )
                )
            )

        assertEquals(listOf(listOf("image", "report"), listOf("logs", "trace")), rows.map { row -> row.map { it.id } })
        assertEquals(64, rows.first().first().composerAttachmentPreviewWidthDp())
        assertEquals(128, rows.first()[1].composerAttachmentPreviewWidthDp())
    }
}
