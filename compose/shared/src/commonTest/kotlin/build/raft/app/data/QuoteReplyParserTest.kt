package build.raft.app.data

import build.raft.app.markdown.markdownToPreviewText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Quote reply Markdown, ported from botiverse/slock `packages/shared/src/quoteReply.test.ts` (staging
 * b0b65a4d1263c9bd39adf28a2f2553c5bacb1913): what reads as a quote block, what the composer writes,
 * and what previews show.
 */
class QuoteReplyParserTest {
    @Test
    fun readsQuoteBlocksInOrderWithTheTextAroundThem() {
        val content =
            listOf(
                "@Sage two quick answers:",
                "",
                "> first quoted sentence",
                "> msg=54a733de",
                "",
                "Reply to point 1.",
                "",
                "> second quoted sentence",
                "> second line of quote",
                "> msg=91C2B8AA",
                "",
                "Reply to point 2.",
            ).joinToString("\n")

        assertTrue(hasSameConversationQuoteRef(content))
        assertEquals(
            listOf(
                MessageQuoteSegment.Text("@Sage two quick answers:"),
                MessageQuoteSegment.QuoteBlock(
                    blockIndex = 0,
                    excerpt = "first quoted sentence",
                    refId = "54a733de",
                    rawMarkdown = "> first quoted sentence\n> msg=54a733de",
                ),
                MessageQuoteSegment.Text("Reply to point 1."),
                MessageQuoteSegment.QuoteBlock(
                    blockIndex = 1,
                    excerpt = "second quoted sentence\nsecond line of quote",
                    refId = "91c2b8aa",
                    rawMarkdown = "> second quoted sentence\n> second line of quote\n> msg=91C2B8AA",
                ),
                MessageQuoteSegment.Text("Reply to point 2."),
            ),
            parseMessageQuoteSegments(content),
        )
    }

    @Test
    fun aRefIsAShortIdOrAFullUuidAndNothingElse() {
        val content = "Before the quote.\n> Line one\n> msg=54A733DE-0001-4000-8000-000000000001\n>\nAfter it."
        assertEquals(
            listOf(
                MessageQuoteSegment.Text("Before the quote."),
                MessageQuoteSegment.QuoteBlock(
                    blockIndex = 0,
                    excerpt = "Line one",
                    refId = "54a733de-0001-4000-8000-000000000001",
                    rawMarkdown = "> Line one\n> msg=54A733DE-0001-4000-8000-000000000001\n>",
                ),
                MessageQuoteSegment.Text("After it."),
            ),
            parseMessageQuoteSegments(content),
        )
        // Nine hex digits are no short id: the quote stays a plain one.
        assertFalse(hasSameConversationQuoteRef("> Line one\n> msg=54a733dee"))
    }

    @Test
    fun plainQuotesBareOrCrossChannelRefsAndFencedCodeAreNoQuoteBlocks() {
        val content =
            listOf(
                "> legacy plain quote",
                "",
                "> #proj-frontend msg=f901c2d3",
                "",
                "> msg=54a733de",
                "",
                "```markdown",
                "> code example quote",
                "> msg=54a733de",
                "```",
                "",
                "~~~txt",
                "> tilde fence quote",
                "> msg=deadbeef",
                "~~~",
            ).joinToString("\n")

        val segments = parseMessageQuoteSegments(content)
        assertTrue(segments.none { it is MessageQuoteSegment.QuoteBlock }, "$segments")
        // Nothing to take out: a preview reads it as written.
        assertEquals(content, stripQuoteReplyRefsForPreview(content))
    }

    @Test
    fun theComposerWritesTheExcerptLinesAndTheShortIdRef() {
        val serialized = serializeInlineQuoteReply("Line one\r\n  Line two  \n", "54A733DE-0001-4000-8000-000000000001", "Sounds good.")
        assertEquals("> Line one\n> Line two\n> msg=54a733de\n\nSounds good.", serialized)
        assertEquals("> Just the quote\n> msg=54a733de", serializeInlineQuoteReply("Just the quote", "54a733de-0001-4000-8000-000000000001", " "))
        // It reads back as the same quote.
        val block = parseMessageQuoteSegments(serialized).filterIsInstance<MessageQuoteSegment.QuoteBlock>().single()
        assertEquals("Line one\nLine two", block.excerpt)
        assertEquals("54a733de", block.refId)
    }

    @Test
    fun previewsReadTheQuotesWithoutTheirRefLines() {
        val content = "> first question\n> msg=54a733de\n\nAnswer 1\n\n> second question\n>\n> second line\n> msg=91c2b8aa\n\nAnswer 2"
        assertEquals(
            "> first question\n\nAnswer 1\n\n> second question\n>\n> second line\n\nAnswer 2",
            stripQuoteReplyRefsForPreview(content),
        )
        // The one-line preview every list row, banner and notification reads.
        val preview = markdownToPreviewText(content)
        assertFalse("msg=" in preview, preview)
        assertTrue("first question" in preview && "Answer 2" in preview, preview)
    }

    @Test
    fun aWholeMessageQuoteIsItsOwnFirstLineCutAt120() {
        assertEquals(
            WholeMessageQuoteExcerpt(excerpt = "${"a".repeat(120)}…", truncated = true),
            buildWholeMessageQuoteExcerpt("a".repeat(140) + "\nsecond line"),
        )
        // Quoting a reply quotes its own words, not what it quoted.
        val reply = "> ancestor quote\n> msg=11111111\n\nMy own answer here."
        assertEquals("My own answer here.", stripLeadingQuoteReplyBlocks(reply))
        assertEquals(WholeMessageQuoteExcerpt(excerpt = "My own answer here.", truncated = false), buildWholeMessageQuoteExcerpt(reply))
    }
}
