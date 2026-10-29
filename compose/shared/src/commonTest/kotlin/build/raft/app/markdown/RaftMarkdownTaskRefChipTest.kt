package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import build.raft.app.RaftColor
import build.raft.app.raftFontFamilyForWeight
import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.designtokens.RaftUiThemeColors
import build.raft.app.model.TaskStatus
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftMarkdownTaskRefChipTest {

    @Test
    fun everyStatusMapsToItsRaftUiSoftFillAndPixelInBothThemes() {
        listOf(RaftUiThemeColors.RaftLight, RaftUiThemeColors.RaftDark).forEach { colors ->
            val expected =
                mapOf(
                    TaskStatus.Todo to (colors.taskStatusTodoSoft to colors.taskStatusTodo),
                    TaskStatus.InProgress to (colors.taskStatusInProgressSoft to RaftUiPalette.taskStatusInProgress),
                    TaskStatus.InReview to (colors.taskStatusInReviewSoft to colors.taskStatusInReview),
                    TaskStatus.Done to (colors.taskStatusDoneSoft to colors.taskStatusDone),
                    TaskStatus.Closed to (colors.taskStatusClosedSoft to colors.taskStatusClosed),
                )
            TaskStatus.entries.forEach { status ->
                val style = raftMarkdownTaskChipStyle(status, colors)
                val (soft, pixel) = expected.getValue(status)
                assertEquals(soft, style.box.backgroundColor, "$status soft fill")
                assertEquals(pixel, style.pixel?.fill, "$status pixel fill")
                assertEquals(RaftUiPalette.stickerInk, style.pixel?.stroke)
                // raft-ui inline TaskChip: no edge, sticker radius, semibold foreground text.
                assertEquals(0.dp, style.box.borderWidth)
                assertEquals(4.dp, style.box.cornerRadius)
                assertEquals(colors.foreground, style.textColor)
                assertEquals(FontWeight.SemiBold, style.fontWeight)
                // inline chip with a leading mark: px-[4.5px] + 10px pixel (raft-design #722) + gap-1
                assertEquals(18.5.dp, style.leadingGap)
                assertEquals(4.5.dp, style.trailingGap)
            }
        }
    }

    @Test
    fun statusFillsAreDistinctPerStatus() {
        val fills = TaskStatus.entries.map { raftMarkdownTaskChipStyle(it, RaftUiThemeColors.RaftLight).box.backgroundColor }
        assertEquals(fills.size, fills.toSet().size)
    }

    @Test
    fun unknownTaskUsesTheNeutralMutedBadge() {
        val style = raftMarkdownTaskChipStyle(null, RaftUiThemeColors.RaftDark)
        assertNull(style.pixel)
        assertEquals(RaftUiPalette.inactive, style.box.backgroundColor)
        assertEquals(RaftUiPalette.stickerInk, style.box.borderColor)
        assertEquals(1.5.dp, style.box.borderWidth)
        assertEquals(RaftUiPalette.inactiveForeground, style.textColor)
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertEquals(6.dp, style.leadingGap)
        assertEquals(6.dp, style.trailingGap)
    }

    @Test
    fun taskChipsKeepAGapFromCjkPunctuationAndEachOther() {
        val body = "（#92）和 #209 #7"
        val statuses = mapOf(92 to TaskStatus.InReview, 209 to TaskStatus.Done)
        val annotated = paragraph(body, raftMarkdownAnnotator(content = body, taskStatuses = statuses))
        val boxes = annotated.raftInlineBoxRanges()
        assertEquals(3, boxes.size)
        val gap = "\uFFFC\u2060"
        // Every chip is flanked by its reserved margin, so "（" / "）" and a neighbouring chip never touch it.
        assertTrue(annotated.text.startsWith("（$gap"), annotated.text)
        boxes.forEach { box ->
            assertEquals(gap, annotated.text.substring(box.start - 2, box.start))
            assertEquals("\u2060\uFFFC", annotated.text.substring(box.end, box.end + 2))
        }
        assertTrue(annotated.raftMarkdownInlineContentIds().contains(raftInlineGapInlineId(2.dp)))
    }

    @Test
    fun taskChipPaddingIsGluedSpacersNotPlaceholders() {
        // artin 10-29 (iOS): a placeholder lead ended a line while the label wrapped, leaving a sliver
        // of the chip box. The lead and trail are word joiners widened by letter spacing instead.
        val text = buildAnnotatedString { appendRaftMarkdownTaskChip(raftMarkdownTaskChipStyle(TaskStatus.Done), "#7") }
        assertEquals("\u2060#7\u2060", text.text)
        assertTrue(text.raftMarkdownInlineContentIds().isEmpty())
        val spacing = text.spanStyles.filter { it.item.letterSpacing.isSpecified }.associate { it.start to it.item.letterSpacing }
        assertEquals(mapOf(0 to 18.5.sp, 3 to 4.5.sp), spacing)
        val unknown = buildAnnotatedString { appendRaftMarkdownTaskChip(raftMarkdownTaskChipStyle(null), "#7") }
        assertEquals(
            mapOf(0 to 6.sp, 3 to 6.sp),
            unknown.spanStyles.filter { it.item.letterSpacing.isSpecified }.associate { it.start to it.item.letterSpacing }
        )
        // The status pixel is the painter's mark on the lead; the unknown badge has none.
        val mark = text.raftInlineMarkRanges().single()
        assertEquals(0 until 1, mark.start until mark.end)
        assertEquals(raftMarkdownTaskChipStyle(TaskStatus.Done).pixel?.fill, mark.item.fill)
        assertEquals(RaftUiPalette.stickerInk, mark.item.stroke)
        assertEquals(4.5.dp, mark.item.inset)
        assertEquals(10.dp, mark.item.size)
        assertTrue(unknown.raftInlineMarkRanges().isEmpty())
    }

    @Test
    fun paragraphTaskRefsRenderStatusChipsWithPaintedPixels() {
        val body = "Ready: #92 and #209, unknown #7"
        val statuses = mapOf(92 to TaskStatus.InReview, 209 to TaskStatus.Done)
        val annotator = raftMarkdownAnnotator(content = body, taskStatuses = statuses)
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
        boxes.zip(listOf("#92", "#209", "#7")).forEach { (box, label) ->
            assertEquals("\u2060$label\u2060", annotated.text.substring(box.start, box.end))
        }
        // Only the chips' outer margin gaps are placeholders; the pixels are painted marks.
        assertEquals(setOf(raftInlineGapInlineId(2.dp)), annotated.raftMarkdownInlineContentIds())
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
    fun taskRefsInListItemsAlsoGetStatusChips() {
        val body = "- #224 iOS thread\n- #145 onboarding"
        val statuses = mapOf(224 to TaskStatus.InReview, 145 to TaskStatus.Done)
        val items = listItemParagraphs(body, raftMarkdownAnnotator(content = body, taskStatuses = statuses))
        assertEquals(2, items.size)
        assertEquals(
            raftMarkdownTaskChipStyle(TaskStatus.InReview).box.backgroundColor,
            items[0].raftInlineBoxRanges().single().item.backgroundColor
        )
        assertEquals(
            raftMarkdownTaskChipStyle(TaskStatus.Done).box.backgroundColor,
            items[1].raftInlineBoxRanges().single().item.backgroundColor
        )
    }

    @Test
    fun surfacesWithoutATaskSourceKeepTheLegacyTaskChip() {
        val body = "See #92"
        val annotator = raftMarkdownAnnotator(content = body)
        val annotated = paragraph(body, annotator)

        assertEquals("See #92", annotated.raftTextWithoutInlineGaps())
        // Only the legacy chip's reserved gaps; no status pixel content.
        assertTrue(annotated.raftMarkdownInlineContentIds().all { raftInlineGapWidth(it) != null })
        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())
        assertEquals(
            RaftColor.InactiveSoft,
            annotated.raftInlineBoxRanges().single().item.backgroundColor
        )
    }

    @Test
    fun nonTaskRefsAreUnaffectedByTheTaskSource() {
        val body = "hi @artin"
        val withSource =
            paragraph(body, raftMarkdownAnnotator(content = body, isTagValid = { true }, taskStatuses = mapOf(1 to TaskStatus.Done)))
        val without = paragraph(body, raftMarkdownAnnotator(content = body, isTagValid = { true }))
        assertEquals(without.text, withSource.text)
        assertTrue(withSource.raftMarkdownInlineContentIds().isEmpty())
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

    private fun paragraph(content: String, annotator: MarkdownAnnotator): AnnotatedString {
        val parserContent = MarkdownParserContent.from(content).parserContent
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val node = tree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        return buildAnnotatedString { buildMarkdownAnnotatedString(parserContent, node, settings(annotator)) }
    }

    private fun listItemParagraphs(content: String, annotator: MarkdownAnnotator): List<AnnotatedString> {
        val parserContent = MarkdownParserContent.from(content).parserContent
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraphs = mutableListOf<ASTNode>()
        fun collect(node: ASTNode) {
            if (node.type == MarkdownElementTypes.PARAGRAPH && node.parent?.type == MarkdownElementTypes.LIST_ITEM) {
                paragraphs += node
            }
            node.children.forEach(::collect)
        }
        collect(tree)
        return paragraphs.map { node ->
            buildAnnotatedString { buildMarkdownAnnotatedString(parserContent, node, settings(annotator)) }
        }
    }

    private fun settings(annotator: MarkdownAnnotator) =
        DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
            codeSpanStyle = SpanStyle(),
            annotator = annotator
        )
}
