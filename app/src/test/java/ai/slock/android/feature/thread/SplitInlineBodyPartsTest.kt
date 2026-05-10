package ai.slock.android.feature.thread

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitInlineBodyPartsTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun plain(text: String) = InlineBodyPart.MarkdownText(text)
    private fun tag(label: String, kind: ThreadMarkdownTagKind) =
        InlineBodyPart.Tag(ThreadMarkdownTag(label, kind))
    private fun code(code: String) = InlineBodyPart.Code(code)

    private fun channel(label: String) = tag(label, ThreadMarkdownTagKind.Channel)
    private fun task(label: String) = tag(label, ThreadMarkdownTagKind.Task)
    private fun mention(label: String) = tag(label, ThreadMarkdownTagKind.UserMention)

    // ── inline code ──────────────────────────────────────────────────────────

    @Test
    fun `backtick span with hash-number becomes Code not Task`() {
        // `#22` should remain code text, not a task tag.
        val parts = "`#22`".splitInlineBodyParts()
        assertEquals(listOf(code("#22")), parts)
    }

    @Test
    fun `backtick span with channel-like token becomes Code not Channel`() {
        val parts = "`#general`".splitInlineBodyParts()
        assertEquals(listOf(code("#general")), parts)
    }

    @Test
    fun `backtick span with mention-like token becomes Code not Mention`() {
        val parts = "`@alice`".splitInlineBodyParts()
        assertEquals(listOf(code("@alice")), parts)
    }

    @Test
    fun `bare inline code with surrounding text`() {
        val parts = "see `#22` for details".splitInlineBodyParts()
        assertEquals(
            listOf(plain("see "), code("#22"), plain(" for details")),
            parts
        )
    }

    // ── task references ──────────────────────────────────────────────────────

    @Test
    fun `bare hash-number becomes clickable task reference`() {
        val parts = "#22".splitInlineBodyParts()
        assertEquals(listOf(task("#22")), parts)
    }

    @Test
    fun `single digit hash-number becomes task reference without double hash label`() {
        val parts = "#3".splitInlineBodyParts()
        assertEquals(listOf(task("#3")), parts)
    }

    @Test
    fun `three digit hash-number keeps full task reference label`() {
        val parts = "#123".splitInlineBodyParts()
        assertEquals(listOf(task("#123")), parts)
        assertEquals("#123", threadMarkdownTagDisplayLabel("##123"))
    }

    @Test
    fun `hyphen between numeric task references remains plain text`() {
        val parts = "#382-#383".splitInlineBodyParts()
        assertEquals(listOf(task("#382"), plain("-"), task("#383")), parts)
    }

    @Test
    fun `bare hash-number in sentence stays clickable task reference`() {
        val parts = "please review #22 asap".splitInlineBodyParts()
        assertEquals(
            listOf(plain("please review "), task("#22"), plain(" asap")),
            parts
        )
    }

    // ── channel chips ────────────────────────────────────────────────────────

    @Test
    fun `hash-word becomes channel chip`() {
        val parts = "#general".splitInlineBodyParts()
        assertEquals(listOf(channel("#general")), parts)
    }

    @Test
    fun `hash channel with Chinese characters keeps full label`() {
        val parts = "#对话流专修".splitInlineBodyParts()
        assertEquals(listOf(channel("#对话流专修")), parts)
    }

    @Test
    fun `literal #task text is kept as plain`() {
        val parts = "#task".splitInlineBodyParts()
        assertEquals(listOf(plain("#task")), parts)
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
    fun `inline task placeholder width grows for three digit labels`() {
        val twoDigitWidth =
            threadInlineTagEstimatedWidthSp(label = "#12", hasIcon = false)
        val threeDigitWidth =
            threadInlineTagEstimatedWidthSp(label = "#123", hasIcon = false)

        assertEquals(9f, threeDigitWidth - twoDigitWidth, 0.01f)
    }

    @Test
    fun `inline code placeholder width leaves room for underscores`() {
        val shortWidth = threadInlineCodeEstimatedWidthSp(code = "in")
        val fullWidth = threadInlineCodeEstimatedWidthSp(code = "in_progress")

        assertEquals(9 * 9f, fullWidth - shortWidth, 0.01f)
    }

    @Test
    fun `thread timestamp formatter handles standard and space separated datetimes`() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.of(2026, 5, 9)

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
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.of(2026, 5, 10)

        assertEquals(
            "05:53",
            "2026-05-09 21:53:33.024426+00".toThreadTimestampLabel(zone, today)
        )
    }

    @Test
    fun `thread timestamp formatter includes date outside today`() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.of(2026, 5, 10)

        assertEquals("22:00", "2026-05-10T22:00:00+08:00".toThreadTimestampLabel(zone, today))
        assertEquals("05-09 22:00", "2026-05-09T22:00:00+08:00".toThreadTimestampLabel(zone, today))
        assertEquals(
                "2025-12-31 23:58",
                "2025-12-31T23:58:00+08:00".toThreadTimestampLabel(zone, today)
        )
    }

    // ── mention chips ────────────────────────────────────────────────────────

    @Test
    fun `at-mention becomes mention chip`() {
        val parts = "@alice".splitInlineBodyParts()
        assertEquals(listOf(mention("@alice")), parts)
    }

    // ── mixed ────────────────────────────────────────────────────────────────

    @Test
    fun `mixed message with bare task and backtick task`() {
        // bare #22 -> inline reference; `#22` -> code text
        val parts = "review #22 and `#22`".splitInlineBodyParts()
        assertEquals(
            listOf(plain("review "), task("#22"), plain(" and "), code("#22")),
            parts
        )
    }

    @Test
    fun `mixed tags in full sentence`() {
        val parts = "ping @alice on #general about #42".splitInlineBodyParts()
        assertEquals(
            listOf(
                plain("ping "),
                mention("@alice"),
                plain(" on "),
                channel("#general"),
                plain(" about "),
                task("#42")
            ),
            parts
        )
    }

    @Test
    fun `plain text with no tags returns single MarkdownText`() {
        val parts = "hello world".splitInlineBodyParts()
        assertEquals(listOf(plain("hello world")), parts)
    }

    @Test
    fun `double star span stays in markdown text`() {
        val parts = "hello **bold text** world".splitInlineBodyParts()
        assertEquals(listOf(plain("hello **bold text** world")), parts)
    }

    @Test
    fun `markdown span can coexist with inline task and code`() {
        val parts = "**Important** for #42 and `status`".splitInlineBodyParts()
        assertEquals(
                listOf(plain("**Important** for "), task("#42"), plain(" and "), code("status")),
                parts
        )
    }

    @Test
    fun `multiple inline code spans`() {
        val parts = "`foo` and `bar`".splitInlineBodyParts()
        assertEquals(
            listOf(code("foo"), plain(" and "), code("bar")),
            parts
        )
    }

    @Test
    fun `long code inserts zero width breaks at separators`() {
        val wrapped =
                "./gradlew :app:testDebugUnitTest --tests foo_bar-baz".wrapLongCodeForDisplay()

        assertEquals(
                ".\u200B/\u200Bgradlew :\u200Bapp:\u200BtestDebugUnitTest -\u200B-\u200Btests foo_\u200Bbar-\u200Bbaz",
                wrapped
        )
    }
}
