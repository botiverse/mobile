package build.raft.app

import build.raft.app.data.MessageQuoteSegment
import build.raft.app.data.parseMessageQuoteSegments
import build.raft.app.model.ResolvedInlineQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Quote replies in the thread (web QuoteReplyBlocks, MessageItem's Quote, the selection quote): which
 * quote blocks become cards, and what Quote puts in the composer.
 */
class ThreadQuoteReplyTest {
    private val quoted =
        ResolvedInlineQuote(
            messageId = "54a733de-0001-4000-8000-000000000001",
            shortId = "54a733de",
            authorName = "Alice",
            authorType = "user",
            createdAt = "2026-11-22T08:00:00.000Z",
            isPartial = true,
        )
    private val sentId = "91c2b8aa-2222-4222-8222-222222222222"

    @Test
    fun aResolvedQuoteIsACardAndAnyOtherAPlainQuoteWithoutItsRef() {
        val body = "> first\n> msg=54a733de\n\nReply one.\n\n> second\n> msg=91c2b8aa\n\nReply two."
        assertEquals(
            listOf(
                ThreadQuoteReplyBlock.Card(blockIndex = 0, excerpt = "first", quote = quoted),
                ThreadQuoteReplyBlock.Markdown("Reply one.\n\n> second\n\nReply two."),
            ),
            threadQuoteReplyBlocks(parseMessageQuoteSegments(body), mapOf("0" to quoted)),
        )
    }

    @Test
    fun onlyTheFirstTenQuoteBlocksBecomeCards() {
        val body = (0 until 12).joinToString("\n\n") { "> point $it\n> msg=54a733de" }
        val segments = parseMessageQuoteSegments(body)
        assertEquals((0 until 12).toList(), segments.filterIsInstance<MessageQuoteSegment.QuoteBlock>().map { it.blockIndex })
        val blocks = threadQuoteReplyBlocks(segments, (0 until 12).associate { it.toString() to quoted })
        assertEquals((0 until 10).toList(), blocks.filterIsInstance<ThreadQuoteReplyBlock.Card>().map { it.blockIndex })
        // Past the cap they stay plain blockquotes, without their `msg=` lines.
        assertEquals(ThreadQuoteReplyBlock.Markdown("> point 10\n\n> point 11"), blocks.last())
    }

    @Test
    fun theMenuQuotesASentMessagesOwnOpeningLineWithItsRef() {
        val message =
            RaftThreadMessageItem(
                id = sentId,
                authorId = "user-ada",
                authorName = "Ada",
                body = "> an older quote\n> msg=54a733de\n\nShip the **sheet** today.\nMore below.",
            )
        assertEquals("> Ship the **sheet** today.…\n> msg=91c2b8aa", threadMessageMenuQuote(message))
        // A send still on its way has no message to point at: no Quote.
        assertNull(threadMessageMenuQuote(message.copy(id = "local-send-thread-abc-1", deliveryState = ThreadMessageDeliverySending)))
    }

    @Test
    fun aPickedPassageQuotesWithItsRefOnlyForASentMessage() {
        assertEquals("> the sheet\n> msg=91c2b8aa", threadSelectionQuote("  the sheet ", sentId))
        assertEquals("> the sheet", threadSelectionQuote("the sheet", "local-send-thread-abc-1"))
        // Select Text with nothing picked quotes the message's opening line.
        assertEquals(
            "> Ship it.…\n> msg=91c2b8aa",
            threadSelectTextSheetQuote(selectedText = "", markdown = "Ship it.\nToday.", quoteMessageId = sentId),
        )
    }
}
