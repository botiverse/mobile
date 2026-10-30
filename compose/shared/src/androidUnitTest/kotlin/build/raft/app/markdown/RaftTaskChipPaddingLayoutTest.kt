package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import build.raft.app.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin 10-30 (Android phone): task chips "#3953" / "#3954" had no side padding and the last digit
 * was clipped. Android 15's Minikin gives a one-character letter-spaced run no advance, so the
 * letter-spaced word joiners #3931 used for the chip padding collapsed to zero; Robolectric on
 * SDK 34 (Android 14 Minikin) still widened them, so the goldens never showed it.
 *
 * These lay the chip out with the real Android text stack on SDK 34 and 35 and check the painted
 * box (the chip's boxed range) is at least the label's advance plus both paddings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RaftTaskChipPaddingLayoutTest {
    @Test @Config(sdk = [34])
    fun chipBoxHoldsLabelPlusPaddingOnAndroid14() = assertChipsPadded()

    @Test @Config(sdk = [35])
    fun chipBoxHoldsLabelPlusPaddingOnAndroid15() = assertChipsPadded()

    @Test @Config(sdk = [35])
    fun letterSpacedJoinerHasNoAdvanceOnAndroid15Minikin() {
        // The root cause, kept as the reason Android takes placeholders: the Skia-form spacer
        // collapses to the bare label on Android 15.
        val style = raftMarkdownTaskChipStyle(null)
        val (box, label) = measure(style, "#3954", spacerAsPlaceholder = false)
        assertEquals(label, box, 0.5f, "letter-spaced joiners now advance on Android 15; box=$box label=$label")
    }

    private fun assertChipsPadded() {
        listOf<TaskStatus?>(null, TaskStatus.Done, TaskStatus.InReview).forEach { status ->
            val style = raftMarkdownTaskChipStyle(status)
            listOf("#7", "#61", "#3954", "#3980").forEach { label ->
                val (box, labelWidth) = measure(style, label, spacerAsPlaceholder = raftPlatformInlineSpacerIsPlaceholder())
                val padding = (style.leadingGap.value + style.trailingGap.value) * DENSITY
                // Placeholder widths round to whole pixels on Android; allow one pixel each side.
                assertTrue(
                    box >= labelWidth + padding - 2f,
                    "status=$status $label: box $box < label $labelWidth + padding $padding",
                )
            }
        }
    }

    /** Box width and bare label advance in px for one chip laid out in a sentence. */
    private fun measure(style: RaftMarkdownTaskChipStyle, label: String, spacerAsPlaceholder: Boolean): Pair<Float, Float> {
        val text =
            buildAnnotatedString {
                append("see ")
                withRaftInlineChipGaps(style.box) {
                    appendRaftMarkdownTaskChip(style, label, spacerAsPlaceholder = spacerAsPlaceholder)
                }
                append(" now")
            }
        val paragraph = layout(text)
        val box = text.raftInlineBoxRanges().single()
        val left = paragraph.getBoundingBox(box.start).left
        val right = paragraph.getBoundingBox(box.end - 1).right
        val bare =
            layout(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM.em)) {
                        withStyle(style.textSpanStyle()) { append(label) }
                    }
                }
            ).getLineWidth(0)
        return (right - left) to bare
    }

    private fun layout(text: AnnotatedString): Paragraph {
        val ids = text.raftMarkdownInlineContentIds()
        val content = raftInlineGapInlineContent(ids)
        val placeholders =
            text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length).mapNotNull { range ->
                content[range.item]?.let { AnnotatedString.Range<Placeholder>(it.placeholder, range.start, range.end) }
            }
        return Paragraph(
            text = text.text,
            style = TextStyle(fontSize = 16.sp),
            width = 5000f,
            density = Density(DENSITY),
            fontFamilyResolver = createFontFamilyResolver(RuntimeEnvironment.getApplication()),
            spanStyles = text.spanStyles,
            placeholders = placeholders,
        )
    }

    private companion object {
        const val DENSITY = 3f
        const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"
    }
}
