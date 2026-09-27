package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.RaftMarkdownDialect
import com.tencent.kuiklybase.markdown.model.RaftMarkdownTokenTypes
import com.tencent.kuiklybase.markdown.model.SlockGFMFlavourDescriptor
import com.tencent.kuiklybase.markdown.model.parseMarkdownInlineSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser

class RaftMarkdownDialectTest {
    @Test
    fun canonicalInlineSyntaxReturnsRefsAndCodeFromOneAstModel() {
        val source = "outside #42 and `code #99`"
        val syntax = parseMarkdownInlineSyntax(source)

        assertEquals(listOf("#42"), syntax.raftRefs.map { it.sourceText })
        assertEquals(listOf("`code #99`"), syntax.inlineCodeSpans.map { it.sourceText })
        assertEquals(listOf("code #99"), syntax.inlineCodeSpans.map { it.codeText })
        assertEquals(source.indexOf('`'), syntax.inlineCodeSpans.single().startOffset)
        assertEquals(source.length, syntax.inlineCodeSpans.single().endOffset)
    }

    @Test
    fun mentionKeepsInternalDotButReturnsTrailingSentenceDotToText() {
        val source = "Ping @release.bot and @artin."
        val syntax = parseMarkdownInlineSyntax(source)

        assertEquals(listOf("@release.bot", "@artin"), syntax.raftRefs.map { it.sourceText })
        assertEquals(source.lastIndexOf('.'), syntax.raftRefs.last().endOffset)
    }

    @Test
    fun canonicalInlineSyntaxUsesCommonMarkCodeSpanTextRules() {
        val syntax = parseMarkdownInlineSyntax("`` `inner` `` and `line1\nline2`")

        assertEquals(listOf("`inner`", "line1 line2"), syntax.inlineCodeSpans.map { it.codeText })
        assertEquals(listOf("`` `inner` ``", "`line1\nline2`"), syntax.inlineCodeSpans.map { it.sourceText })
    }

    @Test
    fun recognizesRaftRefsInOrdinaryGfmText() {
        assertEquals(
            listOf(
                "#1487",
                "@artin",
                "#visual-testing",
                "#visual_testing:c5c60593",
                "@release_bot",
            ),
            "Review #1487 with @artin in #visual-testing, #visual_testing:c5c60593, and @release_bot now."
                .raftRefs(),
        )
    }

    @Test
    fun recognizesCompleteDmThreadAsOneRefAndLeavesBareMentionSuffixSeparate() {
        val source = "Open dm:@artin:1c50c30a; keep @artin:1c50c30a as a profile mention."

        assertEquals(
            listOf("dm:@artin:1c50c30a", "@artin"),
            source.raftRefs(),
        )
    }

    @Test
    fun rejectsMalformedDmThreadFormsWithoutConsumingTheirMentionFallback() {
        val source =
            "dm:@artin:12345 dm:@artin:123456789 dm:@artin:12zzzz " +
                "dm:@发布者:DEADBEEF dm:@release.bot:DEADBEEF"

        assertEquals(
            listOf("@artin", "@artin", "@artin", "@发布者", "dm:@release.bot:DEADBEEF"),
            source.raftRefs(),
        )
    }

    @Test
    fun recognizesKindPinnedDmThreadRefsAsOneRef() {
        val source = "Open dm:@skyzh~agent:1c50c30a or dm:@skyzh~human:DEADBEEF now."

        assertEquals(
            listOf("dm:@skyzh~agent:1c50c30a", "dm:@skyzh~human:DEADBEEF"),
            source.raftRefs(),
        )
    }

    @Test
    fun unknownDmPeerKindClaimsTheWholeTokenInsteadOfFallingBackToMention() {
        // Web `createRaftMalformedDmKindRefRegex`: the token is claimed so the
        // app layer renders it as plain text, never as an `@skyzh` mention.
        val source = "dm:@skyzh~bot:1c50c30a dm:@skyzh~agentx:1c50c30a dm:@skyzh~:1c50c30a dm:@skyzh~agent~human:1c50c30a"

        assertEquals(
            listOf(
                "dm:@skyzh~bot:1c50c30a",
                "dm:@skyzh~agentx:1c50c30a",
                "dm:@skyzh~:1c50c30a",
                "dm:@skyzh~agent~human:1c50c30a",
            ),
            source.raftRefs(),
        )
    }

    @Test
    fun kindPinnedDmRefWithoutThreadSuffixKeepsMentionFallback() {
        assertEquals(listOf("@skyzh"), "dm:@skyzh~agent later".raftRefs())
    }

    @Test
    fun markdownOwnedRangesSuppressDmThreadRefs() {
        val source =
            "`dm:@artin:1c50c30a` [dm:@artin:1c50c30a](https://example.test) " +
                "outside dm:@artin:1c50c30a"

        assertEquals(listOf("dm:@artin:1c50c30a"), source.raftRefs())
    }

    @Test
    fun flavourParseEmitsFirstClassRaftReferenceTokens() {
        val source = "**Review #1487 with @artin**"
        val tree = MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val tokenTexts = mutableListOf<String>()
        fun collect(node: ASTNode) {
            if (node.type == RaftMarkdownTokenTypes.REFERENCE) {
                tokenTexts += source.substring(node.startOffset, node.endOffset)
            }
            node.children.forEach(::collect)
        }
        collect(tree)
        assertEquals(listOf("#1487", "@artin"), tokenTexts)
        assertTrue(tree.children.isNotEmpty())
    }

    @Test
    fun markdownLinksOwnLabelsAndDestinations() {
        val source =
            "[#1487 feat(search): pin](https://github.com/botiverse/mobile/pull/1487#task-1487) then #1487"
        assertEquals(listOf("#1487"), source.raftRefs())
    }

    @Test
    fun imageCodeAutolinkAndHtmlOwnTheirNestedText() {
        val source =
            "![#12](https://example.test/#34) `#56 @artin` <https://example.test/#78> " +
                "<span data-task=\"#90\">html</span> then #91"
        assertEquals(listOf("#91"), source.raftRefs())
    }

    @Test
    fun fencedCodeOwnsRefsAcrossLines() {
        val source = """
            ```text
            #12 @artin #channel:deadbeef
            ```
            outside #13
        """.trimIndent()
        assertEquals(listOf("#13"), source.raftRefs())
    }

    @Test
    fun htmlBlocksAndLinkDefinitionsOwnTheirSourceRanges() {
        val source = """
            <div>
            #12 inside html
            </div>

            [release]: https://example.test/#34

            outside #56
        """.trimIndent()
        assertEquals(listOf("#56"), source.raftRefs())
    }

    @Test
    fun taskShapedLinkDefinitionLabelRemainsADefinitionAndNeverBecomesRaftAction() {
        val source = "[#123]: https://example.test/release\n\nUse [#123] then outside #56"
        val tree = MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val definitions = mutableListOf<ASTNode>()
        fun collect(node: ASTNode) {
            if (node.type == MarkdownElementTypes.LINK_DEFINITION) definitions += node
            node.children.forEach(::collect)
        }
        collect(tree)

        assertEquals(1, definitions.size)
        assertTrue(source.substring(definitions.single().startOffset, definitions.single().endOffset).startsWith("[#123]:"))
        assertEquals(listOf("#56"), RaftMarkdownDialect.inlineRefs(tree, source).map { it.sourceText })
    }

    @Test
    fun preservesEscapingAndWordBoundaryContract() {
        assertEquals(
            listOf("#3", "#channel"),
            "\\#1 word#2 #3 snake_#4 汉字#5；#channel".raftRefs(),
        )
    }

    private fun String.raftRefs(): List<String> =
        RaftMarkdownDialect.inlineRefs(
            MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(this),
            this,
        ).map { ref -> ref.sourceText }
}
