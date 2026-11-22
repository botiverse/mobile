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
    fun `what is drawn holds nothing outside the subset and keeps its words`() {
        for (content in listOf("# Release notes", "Title\n=====", "a\n\n***\n\nb", "x <b>bold</b> y", "| a | b |\n| - | - |\n| 1 | 2 |", "- [ ] write tests")) {
            val drawn = raftCardMarkdownRenderSource(content)
            assertEquals(emptyList(), raftCardMarkdownViolations(drawn), drawn)
        }
        assertTrue("Release notes" in raftCardMarkdownRenderSource("# Release notes"))
        assertEquals("Plain **words** stay as written.", raftCardMarkdownRenderSource("Plain **words** stay as written."))
        assertEquals("[x](https://EXAMPLE.COM/)", raftCardMarkdownRenderSource("[x](HTTPS://EXAMPLE.COM/)"))
        // Links may be http, https or mailto (as a message's); an e-mail literal is drawn as a mailto autolink.
        assertEquals(emptyList(), nodes("[x](mailto:a@b) and <a@example.com> and mail ci@example.com"))
        assertEquals("mail <ci@example.com>", raftCardMarkdownRenderSource("mail ci@example.com"))
        assertEquals(listOf("link", "link"), nodes("[x](tel:+15551234567) <tel:+15551234567>"))
    }
}
