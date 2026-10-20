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
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
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
    fun emailInsideCodeSpanStaysVerbatim() {
        val rendered = renderTopParagraphs("Inline: `artin <artin@example.test>` end.")
        assertTrue(
            rendered.any { it.contains("artin <artin@example.test>") },
            "code span must keep the angle-bracket email literal; got: $rendered"
        )
    }

    @Test
    fun genericAngleBracketPlaceholdersInsideCodeSpanStayVerbatim() {
        val rendered = renderTopParagraphs("Routes: `/apps/<app>/latest` and `/share/<token>`.")
        assertTrue(
            rendered.any { it.contains("/apps/<app>/latest") && it.contains("/share/<token>") },
            "code spans must keep generic angle-bracket placeholders literal; got: $rendered"
        )
    }

    @Test
    fun bareEmailAutolinkKeepsTheAddressText() {
        val rendered = renderTopParagraphs("Bare: <artin@example.test> end.")
        assertTrue(
            rendered.any { it.contains("artin@example.test") },
            "email autolink text must survive rendering; got: $rendered"
        )
    }

    @Test
    fun boldAndStrikethroughContentSurvives() {
        val rendered = renderTopParagraphs("Keep **bold words** and ~~struck words~~ intact.")
        assertTrue(
            rendered.any { it.contains("bold words") && it.contains("struck words") },
            "emphasis/strikethrough content must survive rendering; got: $rendered"
        )
    }

    @Test
    fun escapedBacktickSurvivesAsLiteralBacktick() {
        val rendered = renderTopParagraphs("Type \\` to start a code span.")
        assertTrue(
            rendered.any { it.contains("Type ` to start") },
            "escaped backtick must render as a literal backtick; got: $rendered"
        )
    }

    @Test
    fun unpairedTildeSurvivesInPlainText() {
        val rendered = renderTopParagraphs("Takes ~5 minutes to finish.")
        assertTrue(
            rendered.any { it.contains("~5 minutes") },
            "unpaired tilde must survive rendering; got: $rendered"
        )
    }

    @Test
    fun strikethroughDelimitersDoNotLeakTildes() {
        val rendered = renderTopParagraphs("Keep ~~struck words~~ clean.")
        assertTrue(
            rendered.any { it.contains("struck words") && !it.contains("~") },
            "strikethrough delimiter tildes must not leak into text; got: $rendered"
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
    fun boldAndEmphasisBoundariesTakePrecedenceOverAutolink() {
        data class BoldAutolinkCase(
            val source: String,
            val expectedText: String,
            val expectedUrl: String,
            val urlStart: Int,
            val urlEnd: Int,
            val assertBold: Boolean = false,
            val assertItalic: Boolean = false,
            val assertStrikethrough: Boolean = false,
        )

        val testCases =
            listOf(
                BoldAutolinkCase(
                    source = "**https://xxxx.com**(x)",
                    expectedText = "https://xxxx.com(x)",
                    expectedUrl = "https://xxxx.com",
                    urlStart = 0,
                    urlEnd = 16,
                    assertBold = true,
                ),
                BoldAutolinkCase(
                    source = "**https://xxxx.com/**(x)",
                    expectedText = "https://xxxx.com/(x)",
                    expectedUrl = "https://xxxx.com/",
                    urlStart = 0,
                    urlEnd = 17,
                    assertBold = true,
                ),
                BoldAutolinkCase(
                    source = "**https://xxxx.com/path**(x)",
                    expectedText = "https://xxxx.com/path(x)",
                    expectedUrl = "https://xxxx.com/path",
                    urlStart = 0,
                    urlEnd = 21,
                    assertBold = true,
                ),
                BoldAutolinkCase(
                    source = "**https://xxxx.com/path?a=1&b=2**(x)",
                    expectedText = "https://xxxx.com/path?a=1&b=2(x)",
                    expectedUrl = "https://xxxx.com/path?a=1&b=2",
                    urlStart = 0,
                    urlEnd = 29,
                    assertBold = true,
                ),
                BoldAutolinkCase(
                    source = "**https://xxxx.com:8080/foo**(x)",
                    expectedText = "https://xxxx.com:8080/foo(x)",
                    expectedUrl = "https://xxxx.com:8080/foo",
                    urlStart = 0,
                    urlEnd = 25,
                    assertBold = true,
                ),
                BoldAutolinkCase(
                    source = "**https://xxxx.com/path#hash**(x)",
                    expectedText = "https://xxxx.com/path#hash(x)",
                    expectedUrl = "https://xxxx.com/path#hash",
                    urlStart = 0,
                    urlEnd = 26,
                    assertBold = true,
                ),
                BoldAutolinkCase(
                    source = "前缀**https://xxxx.com/path**(x)后缀",
                    expectedText = "前缀https://xxxx.com/path(x)后缀",
                    expectedUrl = "https://xxxx.com/path",
                    urlStart = 2,
                    urlEnd = 23,
                    assertBold = true,
                ),
                BoldAutolinkCase(
                    source = "*https://xxxx.com/path*(x)",
                    expectedText = "https://xxxx.com/path(x)",
                    expectedUrl = "https://xxxx.com/path",
                    urlStart = 0,
                    urlEnd = 21,
                    assertItalic = true,
                ),
                BoldAutolinkCase(
                    source = "~~https://xxxx.com/path~~(x)",
                    expectedText = "https://xxxx.com/path(x)",
                    expectedUrl = "https://xxxx.com/path",
                    urlStart = 0,
                    urlEnd = 21,
                    assertStrikethrough = true,
                ),
                BoldAutolinkCase(
                    source = "**https://en.wikipedia.org/wiki/Foo_(bar)**(x)",
                    expectedText = "https://en.wikipedia.org/wiki/Foo_(bar)(x)",
                    expectedUrl = "https://en.wikipedia.org/wiki/Foo_(bar)",
                    urlStart = 0,
                    urlEnd = 39,
                    assertBold = true,
                ),
            )

        testCases.forEach { case ->
            val parserContent = MarkdownParserContent.from(case.source).parserContent
            val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
            val paragraphs = mutableListOf<org.intellij.markdown.ast.ASTNode>()
            fun collect(node: org.intellij.markdown.ast.ASTNode) {
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
            val annotated =
                buildAnnotatedString {
                    paragraphs.forEach { p ->
                        buildMarkdownAnnotatedString(parserContent, p, settings)
                    }
                }

            // 1. Visible text conservation
            assertEquals(
                case.expectedText,
                annotated.text,
                "Rendered text mismatch for ${case.source}",
            )

            // 2. Link annotation target and bounds
            val links = annotated.getLinkAnnotations(0, annotated.length)
            assertEquals(
                1,
                links.size,
                "Expected exactly 1 link for ${case.source}; got: $links",
            )
            val link = links.single()
            assertEquals(
                case.expectedUrl,
                (link.item as LinkAnnotation.Url).url,
                "Link destination mismatch for ${case.source}",
            )
            assertEquals(case.urlStart, link.start, "Link start mismatch for ${case.source}")
            assertEquals(case.urlEnd, link.end, "Link end mismatch for ${case.source}")

            // 3. SpanStyle checks
            if (case.assertBold) {
                assertTrue(
                    annotated.spanStyles.any {
                        it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold &&
                            it.start == case.urlStart && it.end == case.urlEnd
                    },
                    "Bold SpanStyle missing or bounded incorrectly for ${case.source}; spans=${annotated.spanStyles}",
                )
            }
            if (case.assertItalic) {
                assertTrue(
                    annotated.spanStyles.any {
                        it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic &&
                            it.start == case.urlStart && it.end == case.urlEnd
                    },
                    "Italic SpanStyle missing for ${case.source}; spans=${annotated.spanStyles}",
                )
            }
            if (case.assertStrikethrough) {
                assertTrue(
                    annotated.spanStyles.any {
                        it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough &&
                            it.start == case.urlStart && it.end == case.urlEnd
                    },
                    "Strikethrough SpanStyle missing for ${case.source}; spans=${annotated.spanStyles}",
                )
            }

            // 4. AST node structural check: Container element must be present in AST
            val allNodes = buildList {
                fun collectAll(node: org.intellij.markdown.ast.ASTNode) {
                    add(node)
                    node.children.forEach(::collectAll)
                }
                collectAll(tree)
            }
            if (case.assertBold) {
                assertTrue(
                    allNodes.any { it.type == MarkdownElementTypes.STRONG },
                    "AST must contain STRONG element for ${case.source}",
                )
            }
            if (case.assertItalic) {
                assertTrue(
                    allNodes.any { it.type == MarkdownElementTypes.EMPH },
                    "AST must contain EMPH element for ${case.source}",
                )
            }
            if (case.assertStrikethrough) {
                assertTrue(
                    allNodes.any { it.type == org.intellij.markdown.flavours.gfm.GFMElementTypes.STRIKETHROUGH },
                    "AST must contain STRIKETHROUGH element for ${case.source}",
                )
            }
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
    fun generatedInlineCodeUrlBoundariesStayVerbatimAndUnlinked() {
        generatedInlineCodeUrlPayloads().forEach { payload ->
            val source = "`$payload`"
            val rendered = renderTopParagraphsWithLinks(source).single()
            assertEquals(
                payload,
                rendered.text,
                "inline-code URL payload was not rendered verbatim; source=$source",
            )
            assertEquals(
                emptyList(),
                rendered.urls,
                "inline-code URL payload leaked link semantics; source=$source; rendered=${rendered.text}",
            )
        }
    }

    @Test
    fun cjkAdjacentInlineCodeUrlStaysCodeAndUnlinked() {
        val source = "回归测试从`http://internal/`、反代HTTP、恶意Host三种入站验证，最终`return_to`始终等于注册的外部HTTPS callback。"
        val parserContent = MarkdownParserContent.from(source).parserContent
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val allNodes = buildList {
            fun collect(node: ASTNode) {
                add(node)
                node.children.forEach(::collect)
            }
            collect(tree)
        }
        assertEquals(2, allNodes.count { it.type == MarkdownElementTypes.CODE_SPAN })
        assertEquals(0, allNodes.count { it.type == GFMTokenTypes.GFM_AUTOLINK })

        val rendered = renderTopParagraphsWithLinks(source).single()
        assertEquals(
            "回归测试从http://internal/、反代HTTP、恶意Host三种入站验证，最终return_to始终等于注册的外部HTTPS callback。",
            rendered.text,
        )
        assertEquals(emptyList(), rendered.urls)
    }

    @Test
    fun recoveredInlineCodeDoesNotSuppressFollowingBareUrl() {
        val bareUrl = "https://stamp.build/login/raft"
        val source = "从`http://internal/`验证。临时可直接开 <$bareUrl>。"
        val rendered = renderTopParagraphsWithLinks(source).single()
        assertEquals("从http://internal/验证。临时可直接开 $bareUrl。", rendered.text)
        assertEquals(listOf(bareUrl), rendered.urls)
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
    fun cjkPunctuationAdjacentBareUrlDoesNotAbsorbPunctuation() {
        val cases =
            listOf(
                "task #286 收尾：PR https://github.com/botiverse/slock/pull/8499（base staging）已开，@archer 审。" to
                    listOf("https://github.com/botiverse/slock/pull/8499"),
                "slock 深度用户 https://x.com/Trader_Pheneck，给了些反馈，在" to
                    listOf("https://x.com/Trader_Pheneck"),
                "来源：（https://www.facebook.com/business/help/407323696966570）、教程" to
                    listOf("https://www.facebook.com/business/help/407323696966570"),
                "read https://example.com/foo_(bar)、then" to
                    listOf("https://example.com/foo_(bar)"),
                "https://cua.ai, 稍后输出机会评分卡。" to
                    listOf("https://cua.ai"),
                "https://cua.ai,稍后输出机会评分卡。" to
                    listOf("https://cua.ai"),
                "see https://example.com/a,b?x=1&y=2，后续" to
                    listOf("https://example.com/a,b?x=1&y=2"),
                "【链接】https://example.com/foo【重要】" to
                    listOf("https://example.com/foo"),
            )

        for ((source, expectedUrls) in cases) {
            val rendered = renderTopParagraphsWithLinks(source).single()
            assertEquals(
                source,
                rendered.text,
                "Rendered text must preserve all source characters verbatim; source=$source",
            )
            assertEquals(
                expectedUrls,
                rendered.urls,
                "URL boundary detection failed; source=$source",
            )
        }
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
            }.text
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
                text = annotated.text,
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

    private fun generatedInlineCodeUrlPayloads(): List<String> {
        val bases =
            listOf(
                // Exact task #513 payload from the reported XiShi message.
                "http://internal/",
                "https://xxx.com/xx/asd",
            )
        val tails = listOf("", ".", ", next", "] now", ") now", "} now", "> now")
        return bases.flatMap { base ->
            generatedBalancedUrlTargets(base).flatMap { url -> tails.map { tail -> url + tail } }
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
