package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Message Markdown read as a one-line preview (Activity rows, Saved, search, banners, local
 * notifications, widget, inline thread replies). xxchan task #67: a thread title showed
 * "**`#wg-rust-rewrite` 开场**".
 */
class RaftMarkdownPreviewTextTest {
    private class Case(val name: String, val markdown: String, val expected: String)

    /**
     * botiverse/slock `packages/shared/src/notificationDisplay.test.ts` at staging
     * c8c1b49f4ffad715734b778fa78c06ea8eceb356: `notificationPlainText` inputs and outputs, verbatim.
     * Every preview then collapses whitespace (`boundNotificationPreview`), so the web output is
     * compared collapsed.
     */
    private val webNotificationVectors =
        listOf(
            Case("emphasis and code", "**bold** and _em_ and `code`", "bold and em and code"),
            Case("link", "see [the docs](https://x.test) now", "see the docs now"),
            Case("code fence", "```ts\nconst a = 1;\n```", "const a = 1;"),
            Case("heading and list", "# Title\n\n- one\n- two", "Title\none\ntwo"),
            Case("html and strike", "a <b>tag</b> ~~gone~~", "a tag gone"),
            Case("image alt", "![chart](x.png)", "chart"),
            Case("quote", "> quoted", "quoted"),
            Case("cjk strong in quotes", "这是**“重点”**。", "这是“重点”。"),
            Case("cjk strong in brackets", "日本語の**「強調」**です", "日本語の「強調」です"),
            Case("ascii quotes stay literal", "a**\"b\"**c", "a**\"b\"**c"),
            Case("table", "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |", "a: 1\nb: 2\na: 3\nb: 4"),
            Case("table with markup", "| **h** | b |\n|---|---|\n| `x` | [l](u) |", "h: x\nb: l"),
        )

    private val mobileCases =
        listOf(
            Case("task #67 screenshot", "**`#wg-rust-rewrite` 开场**", "#wg-rust-rewrite 开场"),
            Case("cut before the closing star", "**`#wg-rust-rewrite` 开场*", "#wg-rust-rewrite 开场"),
            Case("unterminated strong", "开场 **unfinished bold", "开场 unfinished bold"),
            Case("unterminated strike", "~~cut off", "cut off"),
            Case("unterminated code", "run `cargo build", "run cargo build"),
            Case("lone star between spaces", "2 * 3 = 6", "2 * 3 = 6"),
            Case("star inside a word", "a*b", "a*b"),
            Case("snake case", "rename snake_case_name now", "rename snake_case_name now"),
            Case("stars inside code", "`a**b**c` and `*`", "a**b**c and *"),
            Case("stars inside a code block", "```\n**not bold**\n```", "**not bold**"),
            Case("bare url with underscores", "see https://x.com/a_b_c/d_e", "see https://x.com/a_b_c/d_e"),
            Case("link url with underscores", "[docs](https://x.com/a_b_c)", "docs"),
            Case("angle autolink", "<https://a.b/x_y>", "https://a.b/x_y"),
            Case("inline math keeps its source", "area \$\\pi r^2\$ here", "area \$\\pi r^2\$ here"),
            Case("mentions and refs", "@alice see #general and #12", "@alice see #general and #12"),
            Case("dm ref", "ask dm:@bob~agent", "ask dm:@bob~agent"),
            Case("emoji shortcode stays", "ship it :rocket:", "ship it :rocket:"),
            Case("cjk emphasis", "中文*强调*中文", "中文强调中文"),
            Case("nested emphasis", "**bold _italic_ bold** ***both***", "bold italic bold both"),
            Case("entities and escapes", "&amp; &copy; &#35;1 \\*lit\\* \\_x\\_", "& © #1 *lit* _x_"),
            Case("soft and hard breaks", "one\ntwo  \nthree\\\nfour", "one two three four"),
            Case("block boundaries", "para one\n\n> quote\n\n1. first\n2. second", "para one quote first second"),
            Case("task list", "- [x] done\n- [ ] todo", "done todo"),
            Case("html block", "<div>\nblock\n</div>\n\nafter", "after"),
            Case("image without alt", "look ![](y.png) here", "look Image here"),
            Case("reference link and definition", "[ref][1] ok\n\n[1]: http://u", "ref ok"),
            Case("setext heading and rule", "Title\n===\n\n---\n\nbody", "Title body"),
            Case("indented code", "    let x = 1", "let x = 1"),
            Case("whitespace only", " \n\t ", ""),
        )

    @Test
    fun matchesWebNotificationPlainText() {
        webNotificationVectors.forEach { case ->
            assertEquals(case.expected.split(Regex("\\s+")).joinToString(" "), markdownToPreviewText(case.markdown), case.name)
        }
    }

    @Test
    fun flattensMessageMarkdown() {
        mobileCases.forEach { case ->
            assertEquals(case.expected, markdownToPreviewText(case.markdown), case.name)
        }
    }

    /**
     * botiverse/slock #16158 (staging 87ab82603b3c530eb8d96c4359c8109b662de793): a quote reply's
     * one-line preview leads with the reply, never the quote or its `> msg=<id>` line.
     * notificationDisplay.test.ts:61-68 and web tests/quoteReply.behavior.test.tsx (InlineMarkdownPreview).
     */
    @Test
    fun aQuoteReplyReadsAsItsReply() {
        val reply = "> Status page banner goes live at 9:00 PT\n> msg=c54e1234\n\nCan we move it to **8:30**?"
        assertEquals("Can we move it to 8:30?", markdownToPreviewText(reply))
        assertEquals("Can we move it to 8:30?", markdownToPreviewText(reply, RaftMarkdownPreviewNotificationLength))
        // Nothing but the quote: its words.
        assertEquals("Status page banner goes live at 9:00 PT", markdownToPreviewText("> Status page banner goes live at 9:00 PT\n> msg=c54e1234"))
        // A legacy preview folded to one line and cut inside the ref.
        assertEquals("Status page banner goes live at 9:00 PT…", markdownToPreviewText("> Status page banner goes live at 9:00 PT > msg=c54e1…"))
    }
}
