package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rendered inline code carries layout-only characters; every copy of it gets the source back. */
class RaftCopyTextTest {
    private val code =
        "reply/channel-markdown/msg-inline ./gradlew :shared:test " +
            "averyveryverylongtokenwithoutanybreakopportunityatall0123456789abcdefghijklmnop"
    private val body = "Run `$code` now."
    private val expected = "Run $code now."

    @Test
    fun copyingTheRenderedParagraphGivesTheSourceTextBack() {
        assertEquals(expected, render(body).text.raftCopyText())
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
