package build.raft.app.markdown

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip content-integrity net for the markdown annotator (task #495).
 *
 * The task #493 bug class: a lexer token type with no dispatch branch is
 * silently DROPPED — `artin <artin@example.test>` rendered as "artin <>" because
 * EMAIL_AUTOLINK had no branch (fixed in mobile#995). These tests parse real
 * message shapes and assert the human-visible content survives into the
 * rendered text. The static sibling is scripts/patrol/renderer_coverage.mjs,
 * which diffs the library's type universe against the dispatch nightly.
 */
class MarkdownContentIntegrityTest {
    @Test
    fun bareEmailAutolinkKeepsTheAddressText() {
        val rendered = renderTopParagraphs("Bare: <artin@example.test> end.")
        assertTrue(
            rendered.any { it.contains("artin@example.test") },
            "email autolink text must survive rendering; got: $rendered"
        )
    }

    @Test
    fun generatedLiteralBoundariesConserveEveryVisibleCharacter() {
        literalBoundarySources().forEach { source ->
            val rendered = renderTopParagraphsWithLinks(source).single()
            assertEquals(
                source,
                rendered.text,
                "literal boundary lost or rewrote source characters; source=$source",
            )
            assertEquals(
                emptyList(),
                rendered.urls,
                "literal boundary unexpectedly became a link; source=$source; rendered=${rendered.text}",
            )
        }
    }

    @Test
    fun deeplyNestedBracketsDoNotThrowStackOverflow() {
        val depth = 200
        val source = "[".repeat(depth) + "https://xxxx.com/path" + "]".repeat(depth)
        val rendered = renderTopParagraphsWithLinks(source).single()
        assertTrue(
            rendered.text.contains("https://xxxx.com/path"),
            "URL text must survive deeply nested brackets without StackOverflowError",
        )
    }

    @Test
    fun generatedUrlBoundariesConserveTextAndExactLinkTarget() {
        generatedUrlBoundaryCases().forEach { case ->
            val rendered = renderTopParagraphsWithLinks(case.source).single()
            assertEquals(
                case.source,
                rendered.text,
                "URL boundary lost or rewrote visible characters; source=${case.source}",
            )
            if (case.linkMayBeSuppressedByMarkdownContainer) {
                assertTrue(
                    rendered.urls.isEmpty() || rendered.urls == listOf(case.url),
                    "optional URL annotation escaped its generated source slice; source=${case.source}; urls=${rendered.urls}",
                )
            } else {
                assertEquals(
                    listOf(case.url),
                    rendered.urls,
                    "URL annotation did not preserve the generated balanced target; source=${case.source}",
                )
            }
        }
    }

    @Test
    fun cjkAdjacentBareUrlIsClickableWithoutChangingVisibleText() {
        val url = "https://www.example.com/design/AbCdEf0123456789/Raft-brand---Exploration?node-id=372-1396&t=AbCdEfGh01234567-1"
        val source = "@alice 情绪板已经准备完成，有空可以看下$url"
        val rendered = renderTopParagraphsWithLinks(source).single()
        assertEquals(source, rendered.text)
        assertEquals(listOf(url), rendered.urls)
    }


    @Test
    fun bareUrlEndingInUnderscoreKeepsItInTheLinkAndNeverShowsAsterisk() {
        // Reported Android 1.23 message: the link dropped the trailing `_` and the
        // leftover `_` rendered as `*`.
        val first = "https://hands.build/share/GJzCI5Wry2E3i2SAYTXqCrqqbLcVTh5_"
        val second = "https://hands.build/share/tRW8sPYDJG_a8LbyB9KMKzm39XlxJZlY"
        val firstItem = "main 渠道 构建 2（12300002，正式用户会收到的那个包）：$first"
        val secondItem = "main 渠道 构建 3：$second"
        val rendered = renderTopParagraphsWithLinks("- $firstItem\n- $secondItem")
        assertEquals(listOf(firstItem, secondItem), rendered.map { it.text })
        assertEquals(listOf(listOf(first), listOf(second)), rendered.map { it.urls })
    }

    private fun renderTopParagraphs(content: String): List<String> {
        // Uses the PRODUCTION annotator (raftMarkdownAnnotator) so app-side
        // annotate hooks are part of the integrity contract too.
        val parserContent = MarkdownParserContent.from(content).parserContent
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraphs = mutableListOf<ASTNode>()
        fun collect(node: ASTNode) {
            if (node.type == MarkdownElementTypes.PARAGRAPH) paragraphs += node
            node.children.forEach(::collect)
        }
        collect(tree)
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = raftMarkdownAnnotator(parserContent)
            )
        return paragraphs.map { paragraph ->
            buildAnnotatedString {
                buildMarkdownAnnotatedString(parserContent, paragraph, settings)
            }.raftTextWithoutInlineGaps()
        }
    }

    private fun renderTopParagraphsWithLinks(content: String): List<RenderedParagraph> {
        val parserContent = MarkdownParserContent.from(content).parserContent
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraphs = mutableListOf<ASTNode>()
        fun collect(node: ASTNode) {
            if (node.type == MarkdownElementTypes.PARAGRAPH) paragraphs += node
            node.children.forEach(::collect)
        }
        collect(tree)
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = raftMarkdownAnnotator(parserContent),
            )
        return paragraphs.map { paragraph ->
            val annotated =
                buildAnnotatedString {
                    buildMarkdownAnnotatedString(parserContent, paragraph, settings)
                }
            RenderedParagraph(
                // Reserved chip gaps are layout-only placeholders, not source characters.
                text = annotated.raftTextWithoutInlineGaps(),
                urls =
                    annotated
                        .getLinkAnnotations(0, annotated.length)
                        .mapNotNull { range -> (range.item as? LinkAnnotation.Url)?.url },
            )
        }
    }

    /**
     * Grammar-generated messages with no valid Markdown construct. Their
     * conservation law is stronger than a hand-written case list: every source
     * character must remain visible and none may acquire link semantics.
     */
    private fun literalBoundarySources(): List<String> {
        val leftContexts = listOf("a", "aas", "before ")
        val boundaryAtoms =
            buildList {
                val pairs = listOf('<' to '>', '(' to ')', '[' to ']', '{' to '}')
                pairs.forEach { (open, close) ->
                    add("$open$close")
                    add("${open}x$close")
                    add("$open")
                    add("$close")
                }
                add("<>")
                add("<<>>")
            }
        val rightContexts = listOf("", "z", " after")
        return leftContexts.flatMap { left ->
            boundaryAtoms.flatMap { atom ->
                rightContexts.map { right -> "$left$atom$right" }
            }
        }.distinct()
    }

    /**
     * Generates balanced URL paths and surrounds them with prose punctuation.
     * The generator itself owns the exact URL slice, so the oracle can assert
     * both visible-text conservation and link-target boundaries without
     * encoding individual examples such as `asd(xxx)`.
     */
    private fun generatedUrlBoundaryCases(): List<GeneratedUrlBoundaryCase> {
        val base = "https://xxx.com/xx/asd"
        val contexts =
            listOf<Pair<(String) -> String, Boolean>>(
                ({ url: String -> url }) to false,
                ({ url: String -> "$url." }) to false,
                ({ url: String -> "$url, next" }) to false,
                ({ url: String -> "See $url now" }) to false,
                ({ url: String -> "See ($url) now" }) to false,
                // `[url]` is also valid unresolved-reference syntax. GFM may
                // suppress the nested autolink, but it must never create a
                // target outside the generated URL slice.
                ({ url: String -> "See [$url] now" }) to true,
            )
        return generatedBalancedUrlTargets(base).flatMap { url ->
            contexts.map { (context, linkMayBeSuppressed) ->
                GeneratedUrlBoundaryCase(
                    source = context(url),
                    url = url,
                    linkMayBeSuppressedByMarkdownContainer = linkMayBeSuppressed,
                )
            }
        }
    }

    private fun generatedBalancedUrlTargets(base: String): List<String> =
        // GFM's extended-autolink grammar guarantees one nested balanced pair;
        // exercise that whole contract instead of asserting recursive URL
        // semantics beyond what web/commonmark promises.
        listOf(base) + generatedBalancedParenthesisPayloads(maxDepth = 1).map { payload -> "$base($payload)" }

    private fun generatedBalancedParenthesisPayloads(maxDepth: Int): List<String> {
        fun atDepth(depth: Int): List<String> {
            val literals = listOf("x", "xxx", "a-b", "a_b")
            if (depth == 0) return literals
            val nested = atDepth(depth - 1).map { inner -> "a($inner)c" }
            return literals + nested
        }
        return atDepth(maxDepth).distinct()
    }

    private data class GeneratedUrlBoundaryCase(
        val source: String,
        val url: String,
        val linkMayBeSuppressedByMarkdownContainer: Boolean,
    )

    private data class RenderedParagraph(
        val text: String,
        val urls: List<String>,
    )
}
