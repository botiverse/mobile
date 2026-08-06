package build.raft.app

import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Task #130 Phase D: the image text-annotation field routes every editor update
 * through [slockFilePreviewAnnotationDraft], i.e. the composition-safe
 * [coerceTextLength] rather than a bare `take(80)`.
 *
 * Contract: while an IME composition is active the value passes through
 * untouched (applying the limit mid-composition would restart the input
 * connection and drop IME state); once composition has ended the text is
 * truncated, the selection is coerced into the new bounds and the finished
 * composition is cleared.
 */
class SlockFilePreviewAnnotationDraftTest {

    @Test
    fun shortTextPassesThroughUnchanged() {
        val value = TextFieldValue("hello", selection = TextRange(5))
        assertSame(value, slockFilePreviewAnnotationDraft(value))
    }

    @Test
    fun overLongTextIsTruncatedToTheLimit() {
        val long = "x".repeat(ANNOTATION_TEXT_MAX_LENGTH + 25)
        val result = slockFilePreviewAnnotationDraft(TextFieldValue(long, selection = TextRange(long.length)))
        assertEquals(ANNOTATION_TEXT_MAX_LENGTH, result.text.length)
    }

    @Test
    fun truncationCoercesSelectionIntoBounds() {
        val long = "y".repeat(ANNOTATION_TEXT_MAX_LENGTH + 10)
        val result = slockFilePreviewAnnotationDraft(TextFieldValue(long, selection = TextRange(long.length)))
        assertEquals(ANNOTATION_TEXT_MAX_LENGTH, result.selection.start)
        assertEquals(ANNOTATION_TEXT_MAX_LENGTH, result.selection.end)
    }

    @Test
    fun activeCompositionIsNeverTruncated() {
        // Truncating mid-composition would restart the input connection; the whole
        // value must be forwarded verbatim until the IME finishes.
        val long = "z".repeat(ANNOTATION_TEXT_MAX_LENGTH + 5)
        val composing =
            TextFieldValue(long, selection = TextRange(long.length), composition = TextRange(0, long.length))
        val result = slockFilePreviewAnnotationDraft(composing)
        assertSame(composing, result)
        assertEquals(long.length, result.text.length)
    }

    @Test
    fun finishedCompositionIsClearedAfterTruncation() {
        val long = "w".repeat(ANNOTATION_TEXT_MAX_LENGTH + 3)
        val result = slockFilePreviewAnnotationDraft(TextFieldValue(long, selection = TextRange(long.length)))
        assertNull(result.composition)
    }

    @Test
    fun cjkTextIsTruncatedByCharacterCount() {
        val cjk = "标".repeat(ANNOTATION_TEXT_MAX_LENGTH + 4)
        val result = slockFilePreviewAnnotationDraft(TextFieldValue(cjk, selection = TextRange(cjk.length)))
        assertEquals(ANNOTATION_TEXT_MAX_LENGTH, result.text.length)
    }
}
