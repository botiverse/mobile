package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import build.raft.app.threadContextMenuSelectionCopyText
import build.raft.app.threadInPlaceSelectionCopyText
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Rendered inline code carries layout-only characters; every copy of it gets the source back. */
class RaftCopyTextTest {
    private val code =
        "reply/channel-markdown/msg-inline ./gradlew :shared:test " +
            "averyveryverylongtokenwithoutanybreakopportunityatall0123456789abcdefghijklmnop"
    private val body = "Run `$code` now."
    private val expected = "Run $code now."

    @Test
    fun renderedInlineCodeHoldsLayoutOnlyCharacters() {
        val rendered = render(body).text
        // The test is only meaningful while the render really holds them.
        assertNotEquals(expected, rendered)
        assertTrue(rendered.contains('\u200B'), "a hyphen break mark")
        assertTrue(rendered.contains('\u2060'), "a slash glue / gap joiner")
        assertTrue(rendered.contains('\uFFFC'), "a reserved padding gap")
    }

    @Test
    fun copyingTheRenderedParagraphGivesTheSourceTextBack() {
        assertEquals(expected, render(body).text.raftCopyText())
    }

    @Test
    fun copyingOnlyTheCodeSpanGivesTheCodeBack() {
        val rendered = render(body)
        val range =
            rendered.getStringAnnotations(RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG, 0, rendered.length).single()
        assertEquals(code, rendered.text.substring(range.start, range.end).raftCopyText())
    }

    @Test
    fun inThreadSelectionCopiesAreClean() {
        val rendered = render(body).text
        // A partial selection (one of two paragraphs) copies the rendered text, cleaned.
        assertEquals(
            expected,
            threadInPlaceSelectionCopyText(
                selectedTexts = listOf(rendered, ""),
                selectableTexts = listOf(rendered, "Second paragraph."),
                fullMessageText = "source",
            ),
        )
        assertEquals(
            expected,
            threadContextMenuSelectionCopyText(
                selectedTexts = listOf(rendered, ""),
                selectableTexts = listOf(rendered, "Second paragraph."),
                fullMessageText = "source",
            ),
        )
    }

    @Test
    fun plainTextIsReturnedAsIs() {
        val plain = "nothing/to-clean here"
        assertTrue(plain.raftCopyText() === plain)
    }

    private fun render(content: String): AnnotatedString {
        val parserContent = MarkdownParserContent.from(content).parserContent
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraph = tree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = raftMarkdownAnnotator(parserContent),
            )
        return buildAnnotatedString { buildMarkdownAnnotatedString(parserContent, paragraph, settings) }
    }
}
