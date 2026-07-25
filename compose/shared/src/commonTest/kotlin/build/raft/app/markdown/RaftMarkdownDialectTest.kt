package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.RaftMarkdownDialect
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMarkdownDialectTest {
    @Test
    fun recognizesRaftRefsInOrdinaryGfmText() {
        assertEquals(
            listOf("#1487", "@artin", "#visual-testing", "#visual-testing:c5c60593"),
            "Review #1487 with @artin in #visual-testing and #visual-testing:c5c60593."
                .raftRefs(),
        )
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
    fun preservesEscapingAndWordBoundaryContract() {
        assertEquals(
            listOf("#3", "#channel"),
            "\\#1 word#2 #3 snake_#4 汉字#5；#channel".raftRefs(),
        )
    }

    private fun String.raftRefs(): List<String> =
        RaftMarkdownDialect.parseInlineRefs(this).map { ref -> ref.sourceText }
}
