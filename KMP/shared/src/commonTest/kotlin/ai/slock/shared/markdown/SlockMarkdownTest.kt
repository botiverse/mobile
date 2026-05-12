package ai.slock.shared.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockMarkdownTest {
    private fun String.matchTags(): List<ThreadMarkdownTag> =
        threadInlineTagMatches().map { it.tag }

    private fun channel(label: String) = ThreadMarkdownTag(label, ThreadMarkdownTagKind.Channel)
    private fun task(label: String) = ThreadMarkdownTag(label, ThreadMarkdownTagKind.Task)
    private fun mention(label: String) = ThreadMarkdownTag(label, ThreadMarkdownTagKind.UserMention)

    @Test
    fun inlineTagsKeepSharedThreadRules() {
        assertEquals(listOf(task("#22")), "#22".matchTags())
        assertEquals(listOf(channel("#task")), "#task".matchTags())
        assertEquals(listOf(channel("#对话流专修")), "#对话流专修".matchTags())
        assertEquals(listOf(mention("@KMP-专家")), "大家好！我是 @KMP-专家".matchTags())
        assertEquals(
            listOf(mention("@alice"), channel("#general"), task("#42")),
            "ping @alice on #general about #42".matchTags()
        )
    }

    @Test
    fun escapedMarkdownLineBreaksNormalizeForPreviewAndRendering() {
        assertEquals("hello\n\nworld", "hello\\n\\nworld".normalizeThreadMarkdownBody())
        assertEquals("todo\n- #xx", "todo\\n- #xx".normalizeThreadMarkdownBody())
    }

    @Test
    fun inlineCodeKeepsTagsLiteral() {
        val match = "use `#22` here".threadInlineCodeMatches().single()

        assertEquals("slock-code:4", match.id)
        assertEquals("#22", match.code)
        assertEquals(4..8, match.range)
    }

    @Test
    fun displayLabelsKeepExactlyOneMarkerPrefix() {
        assertEquals("#3", threadMarkdownTagDisplayLabel("##3"))
        assertEquals("#general", threadMarkdownTagDisplayLabel("##general"))
        assertEquals("@alice", threadMarkdownTagDisplayLabel("@@alice"))
    }
}
