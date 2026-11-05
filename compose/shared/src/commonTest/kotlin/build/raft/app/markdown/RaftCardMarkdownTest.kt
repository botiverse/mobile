package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC 082 §4.8 card Markdown on this App's parse: what is outside the subset, and what is drawn
 * (each node outside it as its source text). Every vector case runs in MessageCardVectorTest.
 */
class RaftCardMarkdownTest {
    private fun nodes(content: String) = raftCardMarkdownViolations(content).map { it.node }

    @Test
    fun `the subset is allowed`() {
        val content = "**bold**, _italic_, ~~gone~~, `code` and [a link](https://example.com).\n\n- one\n- two\n\n> quoted\n\n```sh\nnpm run build\n```"
        assertEquals(emptyList(), nodes(content))
        assertEquals(
            setOf("blockquote", "code", "delete", "emphasis", "inlineCode", "link", "list", "listItem", "paragraph", "strong", "text"),
            raftCardMarkdownCheck(content).nodeTypes,
        )
    }

    @Test
    fun `nodes outside the subset are found outermost first`() {
        assertEquals(listOf("heading"), nodes("# Release notes"))
        assertEquals(listOf("image"), nodes("![logo](https://example.com/logo.png)"))
        assertEquals(listOf("html", "html"), nodes("x <b>bold</b> y"))
        assertEquals(listOf("link"), nodes("[x](javascript:alert(1))"))
        assertEquals(listOf("taskListItem", "taskListItem"), nodes("- [ ] write tests\n- [x] ship"))
        assertEquals(listOf("list"), nodes("- a\n  - b\n    - c\n      - d"))
    }

    @Test
    fun `what is drawn holds nothing outside the subset and keeps its words`() {
        for (content in listOf("# Release notes", "Title\n=====", "a\n\n***\n\nb", "x <b>bold</b> y", "| a | b |\n| - | - |\n| 1 | 2 |", "- [ ] write tests")) {
            val drawn = raftCardMarkdownRenderSource(content)
            assertEquals(emptyList(), raftCardMarkdownViolations(drawn), drawn)
        }
        assertTrue("Release notes" in raftCardMarkdownRenderSource("# Release notes"))
        assertEquals("Plain **words** stay as written.", raftCardMarkdownRenderSource("Plain **words** stay as written."))
        assertEquals("[x](https://EXAMPLE.COM/)", raftCardMarkdownRenderSource("[x](HTTPS://EXAMPLE.COM/)"))
    }
}
