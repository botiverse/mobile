package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.raftFontFamilyForWeight
import build.raft.app.model.TaskStatus
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftMarkdownTaskRefChipTest {

    @Test
    fun paragraphTaskRefsRenderStatusChipsWithPaintedPixels() {
        val body = "Ready: #92 and #209, unknown #7"
        val statuses = mapOf(92 to TaskStatus.InReview, 209 to TaskStatus.Done)
        val annotator = raftMarkdownAnnotator(content = body, isTagValid = knownTaskRefs, taskStatuses = statuses)
        val annotated = paragraph(body, annotator)

        assertEquals(3, annotated.getStringAnnotations(RaftMarkdownTagAnnotationTag, 0, annotated.length).size)
        val boxes = annotated.raftInlineBoxRanges()
        assertEquals(
            listOf(
                raftMarkdownTaskChipStyle(TaskStatus.InReview).box,
                raftMarkdownTaskChipStyle(TaskStatus.Done).box,
                raftMarkdownTaskChipStyle(null).box,
            ),
            boxes.map { it.item }
        )
        // In-box padding is a glued placeholder on Android, a letter-spaced joiner on Skia.
        val placeholderPadding = raftPlatformInlineSpacerIsPlaceholder()
        boxes.zip(listOf("#92", "#209", "#7")).forEach { (box, label) ->
            val expected = if (placeholderPadding) "\uFFFC\u2060$label\u2060\uFFFC" else "\u2060$label\u2060"
            assertEquals(expected, annotated.text.substring(box.start, box.end))
        }
        // Besides the chips' outer margin gaps, only Android's padding spacers are placeholders; the pixels are painted marks.
        val paddingGaps = if (placeholderPadding) listOf(18.5.dp, 4.5.dp, 6.dp) else emptyList()
        assertEquals((listOf(2.dp) + paddingGaps).map(::raftInlineGapInlineId).toSet(), annotated.raftMarkdownInlineContentIds())
        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())
        assertEquals(
            listOf(boxes[0].start, boxes[1].start),
            annotated.raftInlineMarkRanges().map { it.start }
        )
        // The chip text keeps the clickable link and its chip text style.
        val inReviewLink =
            annotated.getLinkAnnotations(0, annotated.length)
                .first { annotated.text.substring(it.start, it.end).contains("#92") }
        // The weight is encoded in the per-weight Raft font family.
        assertEquals(
            raftFontFamilyForWeight(FontWeight.SemiBold),
            (inReviewLink.item as LinkAnnotation.Clickable).styles?.style?.fontFamily
        )
    }

    @Test
    fun qualifiedTaskChipLabelsGlueHyphensAndColonsAndCopyCleanly() {
        val style = raftMarkdownTaskChipStyle(TaskStatus.InProgress)
        val cases =
            listOf(
                "#wg-mobile-sync:#276" to "#wg-\u2060mobile-\u2060sync:\u2060#276",
                "dm:@artin:#170" to "dm:\u2060@artin:\u2060#170",
                "lounge:@agent:#7" to "lounge:\u2060@agent:\u2060#7",
            )
        listOf(false, true).forEach { spacerAsPlaceholder ->
            cases.forEach { (label, gluedLabel) ->
                val annotated =
                    buildAnnotatedString {
                        withRaftInlineChipGaps(style.box) {
                            appendRaftMarkdownTaskChip(style, label, spacerAsPlaceholder = spacerAsPlaceholder)
                        }
                    }
                val box = annotated.raftInlineBoxRanges().single()
                val expectedBoxText =
                    if (spacerAsPlaceholder) "\uFFFC\u2060$gluedLabel\u2060\uFFFC" else "\u2060$gluedLabel\u2060"
                assertEquals(expectedBoxText, annotated.text.substring(box.start, box.end))
                assertEquals(label, annotated.raftTextWithoutInlineGaps())
                assertEquals(label, annotated.text.raftCopyText())
            }
        }
    }

    @Test
    fun revisionChangesWhenAReferencedStatusOrThemeChanges() {
        val actions =
            listOf(
                RaftRichTextAction("a", RaftRichTextActionKind.OpenRaftRef, RaftRichTextRefKind.Task, "#92"),
                RaftRichTextAction("b", RaftRichTextActionKind.OpenRaftRef, RaftRichTextRefKind.Channel, "#general"),
            )
        val inReview = raftMarkdownTaskChipRevision(actions, mapOf(92 to TaskStatus.InReview), dark = false)
        val done = raftMarkdownTaskChipRevision(actions, mapOf(92 to TaskStatus.Done), dark = false)
        val dark = raftMarkdownTaskChipRevision(actions, mapOf(92 to TaskStatus.InReview), dark = true)
        val unrelated = raftMarkdownTaskChipRevision(actions, mapOf(92 to TaskStatus.InReview, 5 to TaskStatus.Todo), dark = false)
        assertNotEquals(inReview, done)
        assertNotEquals(inReview, dark)
        assertEquals(inReview, unrelated)
        assertTrue(raftMarkdownTaskChipRevision(actions.drop(1), mapOf(92 to TaskStatus.Done), dark = true).isEmpty())
    }

    /** The refs here are known tasks of the conversation (no tag context knows none). */
    private val knownTaskRefs: (ThreadMarkdownTag) -> Boolean = { tag -> tag.kind == ThreadMarkdownTagKind.Task }

    private fun paragraph(content: String, annotator: MarkdownAnnotator): AnnotatedString {
        val parserContent = MarkdownParserContent.from(content).parserContent
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val node = tree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        return buildAnnotatedString { buildMarkdownAnnotatedString(parserContent, node, settings(annotator)) }
    }

    private fun settings(annotator: MarkdownAnnotator) =
        DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
            codeSpanStyle = SpanStyle(),
            annotator = annotator
        )
}
