package ai.slock.android.feature.thread

import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.markdown.normalizeThreadMarkdownBody
import ai.slock.compose.markdown.threadInlineCodeMatches
import ai.slock.compose.markdown.threadInlineTagMatches
import ai.slock.compose.markdown.threadMarkdownTagDisplayLabel
import ai.slock.compose.markdown.threadMarkdownTaskReferenceKey
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadInlineTagParsingTest {

    private fun String.matchTags(): List<ThreadMarkdownTag> =
            threadInlineTagMatches().map { it.tag }

    private fun channel(label: String) = ThreadMarkdownTag(label, ThreadMarkdownTagKind.Channel)
    private fun task(label: String) = ThreadMarkdownTag(label, ThreadMarkdownTagKind.Task)
    private fun mention(label: String) = ThreadMarkdownTag(label, ThreadMarkdownTagKind.UserMention)

    @Test
    fun `bare hash-number becomes task reference`() {
        assertEquals(listOf(task("#22")), "#22".matchTags())
    }

    @Test
    fun `task reference keeps full numeric label`() {
        assertEquals(listOf(task("#3")), "#3".matchTags())
        assertEquals(listOf(task("#123")), "#123".matchTags())
        assertEquals("#123", threadMarkdownTagDisplayLabel("##123"))
    }

    @Test
    fun `hyphen between numeric task references yields two task tags`() {
        assertEquals(listOf(task("#382"), task("#383")), "#382-#383".matchTags())
    }

    @Test
    fun `bare hash-number in sentence stays task reference`() {
        assertEquals(listOf(task("#22")), "please review #22 asap".matchTags())
    }

    @Test
    fun `hash-word becomes channel tag`() {
        assertEquals(listOf(channel("#general")), "#general".matchTags())
    }

    @Test
    fun `hash channel with Chinese characters keeps full label`() {
        assertEquals(listOf(channel("#对话流专修")), "#对话流专修".matchTags())
    }

    @Test
    fun `list item hash word becomes channel tag`() {
        assertEquals(listOf(channel("#xx")), "- #xx".matchTags())
    }

    @Test
    fun `literal #task text becomes channel tag`() {
        assertEquals(listOf(channel("#task")), "#task".matchTags())
    }

    @Test
    fun `at-mention becomes user mention tag`() {
        assertEquals(listOf(mention("@alice")), "@alice".matchTags())
    }

    @Test
    fun `at-mention with Chinese characters keeps full label`() {
        assertEquals(listOf(mention("@KMP-专家")), "大家好！我是 @KMP-专家".matchTags())
    }

    @Test
    fun `mixed tags keep source order`() {
        assertEquals(
                listOf(mention("@alice"), channel("#general"), task("#42")),
                "ping @alice on #general about #42".matchTags()
        )
    }

    @Test
    fun `plain markdown without tags has no matches`() {
        assertEquals(emptyList<ThreadMarkdownTag>(), "hello **bold text** world".matchTags())
    }

    @Test
    fun `base offset is included in placeholder id`() {
        assertEquals("slock:25", "review #42".threadInlineTagMatches(baseOffset = 18).single().id)
    }

    @Test
    fun `escaped double newlines normalize into markdown paragraphs`() {
        assertEquals("hello\n\nworld", "hello\\n\\nworld".normalizeThreadMarkdownBody())
    }

    @Test
    fun `escaped list item markdown normalizes before tag parsing`() {
        val normalized = "todo\\n- #xx".normalizeThreadMarkdownBody()

        assertEquals("todo\n- #xx", normalized)
        assertEquals(listOf(channel("#xx")), normalized.matchTags())
    }

    @Test
    fun `inline code match id starts at opening backtick`() {
        val match = "use `String()` here".threadInlineCodeMatches().single()

        assertEquals("slock-code:4", match.id)
        assertEquals("String()", match.code)
        assertEquals(4..13, match.range)
    }

    @Test
    fun `inline code normalizes one surrounding space`() {
        assertEquals("`", "`` ` ``".threadInlineCodeMatches().single().code)
    }

    @Test
    fun `task reference key normalizes labels for chooser matching`() {
        assertEquals("3", threadMarkdownTaskReferenceKey("#3"))
        assertEquals("release", threadMarkdownTaskReferenceKey("#Release"))
    }

    @Test
    fun `tag display label keeps exactly one marker prefix`() {
        assertEquals("#3", threadMarkdownTagDisplayLabel("#3"))
        assertEquals("#3", threadMarkdownTagDisplayLabel("##3"))
        assertEquals("#general", threadMarkdownTagDisplayLabel("#general"))
        assertEquals("#general", threadMarkdownTagDisplayLabel("##general"))
        assertEquals("@alice", threadMarkdownTagDisplayLabel("@@alice"))
    }

    @Test
    fun `thread timestamp formatter handles standard and space separated datetimes`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 5, 9)

        assertEquals("22:00", "2026-05-09T22:00:00+08:00".toThreadTimestampLabel(zone, today))
        assertEquals("22:00", "2026-05-09 22:00:00+08:00".toThreadTimestampLabel(zone, today))
        assertEquals("22:00", "2026-05-09 22:00:00.000+08:00".toThreadTimestampLabel(zone, today))
        assertEquals("22:00", "2026-05-09T22:00:00.000+0800".toThreadTimestampLabel(zone, today))
        assertEquals("22:00", "2026-05-09 22:00:00+0800".toThreadTimestampLabel(zone, today))
        assertEquals("22:00", "1778335200000".toThreadTimestampLabel(zone, today))
        assertEquals("22:00", "1778335200".toThreadTimestampLabel(zone, today))
    }

    @Test
    fun `thread timestamp formatter handles server microsecond utc offset hour`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 5, 10)

        assertEquals(
                "05:53",
                "2026-05-09 21:53:33.024426+00".toThreadTimestampLabel(zone, today)
        )
    }

    @Test
    fun `thread timestamp formatter includes date outside today`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 5, 10)

        assertEquals("22:00", "2026-05-10T22:00:00+08:00".toThreadTimestampLabel(zone, today))
        assertEquals("05-09 22:00", "2026-05-09T22:00:00+08:00".toThreadTimestampLabel(zone, today))
        assertEquals(
                "2025-12-31 23:58",
                "2025-12-31T23:58:00+08:00".toThreadTimestampLabel(zone, today)
        )
    }
}
