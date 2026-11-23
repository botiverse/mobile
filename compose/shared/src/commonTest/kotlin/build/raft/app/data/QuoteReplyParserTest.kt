package build.raft.app.data

import build.raft.app.markdown.markdownToPreviewText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Quote reply Markdown, ported from botiverse/slock `packages/shared/src/quoteReply.test.ts` (staging
 * b0b65a4d1263c9bd39adf28a2f2553c5bacb1913): what reads as a quote block, what the composer writes,
 * and what previews show. The preview text cases (`extractQuoteReplyPreviewText` and the hardened
 * `stripQuoteReplyRefsForPreview`, botiverse/slock #16158) are from staging
 * 87ab82603b3c530eb8d96c4359c8109b662de793, quoteReply.test.ts:187-260, every assertion, same inputs.
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
        // The one-line preview every list row, banner and notification reads: the answers, not the
        // questions they quote (web `notificationPlainText` runs `extractQuoteReplyPreviewText`, #16158).
        assertEquals("Answer 1 Answer 2", markdownToPreviewText(content))
    }

    @Test
    fun aPreviewLeadsWithTheReplyAndFallsBackToTheQuote() {
        // Single quote + reply (the web's InlineThreadReplies reproduction).
        val reply =
            serializeInlineQuoteReply(
                "Status page banner goes live at 9:00 PT",
                "c54e1234-1111-4111-8111-111111111111",
                "Can we move it to 8:30 so it's up before the flags flip?",
            )
        assertEquals("Can we move it to 8:30 so it's up before the flags flip?", extractQuoteReplyPreviewText(reply))

        // Multiple leading quotes + reply: what web `serializeInlineQuoteBlocks` writes for them.
        val multiLead = "> Point A\n> msg=11111111\n\n> Point B\n> msg=22222222\n\nAgree with both."
        assertEquals("Agree with both.", extractQuoteReplyPreviewText(multiLead))

        // Interleaved intro + quotes + answers (also `serializeInlineQuoteBlocks`).
        val interleaved =
            "@Sage three quick answers:\n\n> First question\n> msg=54a733de\n\nAnswer 1\n\n> Second question\n> msg=91c2b8aa\n\nAnswer 2"
        assertEquals("@Sage three quick answers:\n\nAnswer 1\n\nAnswer 2", extractQuoteReplyPreviewText(interleaved))

        // Quote only (no reply body): the quote's words, without `>` or `> msg=`.
        val quoteOnly = serializeInlineQuoteReply("Status page banner goes live at 9:00 PT", "c54e1234", "")
        assertEquals("Status page banner goes live at 9:00 PT", extractQuoteReplyPreviewText(quoteOnly))
    }

    @Test
    fun aPreviewCutInsideTheRefOrFoldedToOneLineNeverShowsTheRef() {
        // Multi-line, cut right inside `> msg=c54e1…`.
        assertEquals(
            "Status page banner goes live at 9:00 PT…",
            extractQuoteReplyPreviewText("> Status page banner goes live at 9:00 PT\n> msg=c54e1…"),
        )
        assertEquals(
            "> Status page banner goes live at 9:00 PT…",
            stripQuoteReplyRefsForPreview("> Status page banner goes live at 9:00 PT\n> msg=c54e1…"),
        )

        // One line, whitespace folded (legacy / SQL / search previews), whole and cut inside the ref.
        assertEquals(
            "Can we move it to 8:30?",
            extractQuoteReplyPreviewText("> Status page banner goes live at 9:00 PT > msg=c54e1234 Can we move it to 8:30?"),
        )
        assertEquals(
            "Status page banner goes live at 9:00 PT…",
            extractQuoteReplyPreviewText("> Status page banner goes live at 9:00 PT > msg=c54e1…"),
        )
        assertEquals(
            "> Status page banner goes live at 9:00 PT Can we move it to 8:30?",
            stripQuoteReplyRefsForPreview("> Status page banner goes live at 9:00 PT > msg=c54e1234 Can we move it to 8:30?"),
        )
        assertEquals(
            "> Status page banner goes live at 9:00 PT…",
            stripQuoteReplyRefsForPreview("> Status page banner goes live at 9:00 PT > msg=c54e1…"),
        )
    }

    @Test
    fun plainQuotesCrossChannelRefsAndCodeFencesStayAsWritten() {
        val plainQuote = "> legacy plain quote\n\nMy reply"
        assertEquals(plainQuote, extractQuoteReplyPreviewText(plainQuote))
        val fenced = "```markdown\n> code quote\n> msg=54a733de\n```"
        assertEquals(fenced, extractQuoteReplyPreviewText(fenced))
        assertEquals(fenced, stripQuoteReplyRefsForPreview(fenced))
        val prose = "Check #general msg=54a733de when x > y"
        assertEquals(prose, extractQuoteReplyPreviewText(prose))
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
