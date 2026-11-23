package build.raft.app

import androidx.compose.ui.input.key.Key
import build.raft.app.data.MAX_INLINE_QUOTES_PER_MESSAGE
import build.raft.app.model.ResolvedInlineQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Multi-quote interleaved composer model & key state machine parity with botiverse/slock
 * `composerQuoteModel.test.ts`, `useComposerQuoteController.ts`, and `quoteReply.behavior.test.tsx`
 * (#16027 / #16059 / #16210).
 */
class ThreadComposerQuoteModelTest {
    private val qA =
        PendingComposerQuote(
            messageId = "11111111-1111-4111-8111-111111111111",
            authorName = "Alice",
            authorType = "user",
            excerpt = "first question",
            isPartial = true,
            createdAt = "2026-11-22T08:00:00.000Z",
        )
    private val qB =
        PendingComposerQuote(
            messageId = "22222222-2222-4222-8222-222222222222",
            authorName = "Sage",
            authorType = "agent",
            excerpt = "second question",
            isPartial = false,
            createdAt = "2026-11-22T08:01:00.000Z",
        )

    @Test
    fun insertQuoteAtCursorPlacesQuoteFirstAndFocusesReplyBlockBelow() {
        val initial = createTextOnlyBlocks("")
        val res = insertQuoteAtCursor(initial, qA)
        val inserted = assertIs<InsertQuoteResult.Inserted>(res)
        assertEquals(2, inserted.blocks.size)
        assertIs<ComposerBlock.Quote>(inserted.blocks[0])
        val tail = assertIs<ComposerBlock.Text>(inserted.blocks[1])
        assertEquals(tail.id, inserted.focusBlockId)
        assertEquals(0, inserted.focusOffset)
        assertEquals("> first question\n> msg=11111111", serializeComposerBlocks(inserted.blocks))
    }

    @Test
    fun insertingMultipleQuotesStacksThemAndSplitsMidTextCleanly() {
        val text0 = ComposerBlock.Text(id = "t-init", text = "Intro here   Tail here")
        val first = assertIs<InsertQuoteResult.Inserted>(
            insertQuoteAtCursor(
                listOf(text0),
                qA,
                ComposerBlockCursor(blockId = "t-init", offset = 13),
            )
        )
        assertEquals(
            listOf("Intro here", "QUOTE:11111111", "Tail here"),
            first.blocks.map {
                when (it) {
                    is ComposerBlock.Text -> it.text
                    is ComposerBlock.Quote -> "QUOTE:${it.quote.messageId.take(8)}"
                }
            },
        )
        // Inserting qB directly into an empty trailing slot stacks qA and qB without an empty gap.
        val stacked0 = assertIs<InsertQuoteResult.Inserted>(insertQuoteAtCursor(createTextOnlyBlocks(""), qA))
        val stacked1 = assertIs<InsertQuoteResult.Inserted>(
            insertQuoteAtCursor(stacked0.blocks, qB, ComposerBlockCursor(stacked0.focusBlockId, 0))
        )
        assertEquals(3, stacked1.blocks.size)
        assertIs<ComposerBlock.Quote>(stacked1.blocks[0])
        assertIs<ComposerBlock.Quote>(stacked1.blocks[1])
        assertIs<ComposerBlock.Text>(stacked1.blocks[2])
        assertEquals(
            "> first question\n> msg=11111111\n\n> second question\n> msg=22222222",
            serializeComposerBlocks(stacked1.blocks),
        )
    }

    @Test
    fun duplicateDetectionMatchesShortIdAndNormalizedExcerptOnly() {
        val first = assertIs<InsertQuoteResult.Inserted>(insertQuoteAtCursor(createTextOnlyBlocks(""), qA))
        val sameWhitespaceVariant = qA.copy(messageId = "11111111", excerpt = "  FIRST   Question \n ")
        val dup = assertIs<InsertQuoteResult.Duplicate>(insertQuoteAtCursor(first.blocks, sameWhitespaceVariant))
        assertEquals(first.quoteBlockId, dup.duplicateBlockId)

        // Different excerpt from the SAME message is allowed (multi-excerpt from one long message).
        val otherPassage = qA.copy(excerpt = "another sentence from the same message")
        val allowed = assertIs<InsertQuoteResult.Inserted>(insertQuoteAtCursor(first.blocks, otherPassage))
        assertEquals(2, countComposerQuotes(allowed.blocks))
    }

    @Test
    fun enforcesMaxTenQuotesPerMessage() {
        var blocks = createTextOnlyBlocks("")
        for (i in 0 until MAX_INLINE_QUOTES_PER_MESSAGE) {
            val quote =
                qA.copy(
                    messageId = "${i.toString().padStart(8, 'a')}-1111-4111-8111-111111111111",
                    excerpt = "point $i",
                )
            blocks = assertIs<InsertQuoteResult.Inserted>(insertQuoteAtCursor(blocks, quote)).blocks
        }
        assertEquals(MAX_INLINE_QUOTES_PER_MESSAGE, countComposerQuotes(blocks))
        val overflow =
            insertQuoteAtCursor(
                blocks,
                qA.copy(messageId = "ffffffff-1111-4111-8111-111111111111", excerpt = "point 10"),
            )
        assertIs<InsertQuoteResult.Limit>(overflow)
    }

    @Test
    fun replyBelowOpensExplicitGapAndBlurOrBackspaceCollapsesEmptyGapOnly() {
        val s1 = assertIs<InsertQuoteResult.Inserted>(insertQuoteAtCursor(createTextOnlyBlocks(""), qA))
        val s2 = assertIs<InsertQuoteResult.Inserted>(insertQuoteAtCursor(s1.blocks, qB))
        val qAId = (s2.blocks[0] as ComposerBlock.Quote).id

        val opened = insertTextGapAfterQuote(s2.blocks, qAId)
        assertEquals(4, opened.blocks.size)
        val gap = assertIs<ComposerBlock.Text>(opened.blocks[1])
        assertTrue(gap.explicitGap)
        assertEquals(gap.id, opened.focusBlockId)

        // Pressing Backspace inside the empty interleaved gap collapses ONLY that gap (never arming qA).
        val keyDecision =
            threadComposerQuoteBlockKeyDecision(
                blocks = opened.blocks,
                blockIndex = 1,
                key = Key.Backspace,
                isComposing = false,
                shift = false,
                alt = false,
                ctrl = false,
                meta = false,
                selectionStart = 0,
                selectionEnd = 0,
                textLength = 0,
                armedDeleteQuoteId = null,
            )
        assertEquals(ThreadComposerQuoteKeyDecision.CollapseEmptyTextBlock(gap.id), keyDecision)

        val collapsedByBackspace = removeEmptyTextBlock(opened.blocks, gap.id)
        assertEquals(3, collapsedByBackspace.blocks.size)
        assertEquals(2, countComposerQuotes(collapsedByBackspace.blocks))

        // Blur also collapses an untouched empty explicit gap.
        val collapsedByBlur = collapseEmptyExplicitGap(opened.blocks, gap.id)
        assertEquals(3, collapsedByBlur.size)
        assertEquals(2, countComposerQuotes(collapsedByBlur))
    }

    @Test
    fun backspaceBelowQuoteArmsOnFirstPressRemovesOnSecondAndDisarmsOnOtherKeys() {
        val s1 = assertIs<InsertQuoteResult.Inserted>(insertQuoteAtCursor(createTextOnlyBlocks(""), qA))
        val qAId = (s1.blocks[0] as ComposerBlock.Quote).id

        val firstBackspace =
            threadComposerQuoteBlockKeyDecision(
                blocks = s1.blocks,
                blockIndex = 1,
                key = Key.Backspace,
                isComposing = false,
                shift = false,
                alt = false,
                ctrl = false,
                meta = false,
                selectionStart = 0,
                selectionEnd = 0,
                textLength = 0,
                armedDeleteQuoteId = null,
            )
        assertEquals(ThreadComposerQuoteKeyDecision.ArmQuote(qAId), firstBackspace)

        val disarmOnTyping =
            threadComposerQuoteBlockKeyDecision(
                blocks = s1.blocks,
                blockIndex = 1,
                key = Key.A,
                isComposing = false,
                shift = false,
                alt = false,
                ctrl = false,
                meta = false,
                selectionStart = 0,
                selectionEnd = 0,
                textLength = 0,
                armedDeleteQuoteId = qAId,
            )
        assertEquals(ThreadComposerQuoteKeyDecision.DisarmAndPassThrough, disarmOnTyping)

        val secondBackspace =
            threadComposerQuoteBlockKeyDecision(
                blocks = s1.blocks,
                blockIndex = 1,
                key = Key.Backspace,
                isComposing = false,
                shift = false,
                alt = false,
                ctrl = false,
                meta = false,
                selectionStart = 0,
                selectionEnd = 0,
                textLength = 0,
                armedDeleteQuoteId = qAId,
            )
        assertEquals(ThreadComposerQuoteKeyDecision.RemoveArmedQuote(qAId), secondBackspace)
    }

    @Test
    fun arrowUpOpensIntroGapAboveFirstQuoteAndNavigatesBetweenTextBlocks() {
        val s1 = assertIs<InsertQuoteResult.Inserted>(insertQuoteAtCursor(createTextOnlyBlocks(""), qA))
        val arrowUp =
            threadComposerQuoteBlockKeyDecision(
                blocks = s1.blocks,
                blockIndex = 1,
                key = Key.DirectionUp,
                isComposing = false,
                shift = false,
                alt = false,
                ctrl = false,
                meta = false,
                selectionStart = 0,
                selectionEnd = 0,
                textLength = 0,
                armedDeleteQuoteId = null,
            )
        assertEquals(ThreadComposerQuoteKeyDecision.InsertIntroGapBeforeFirstQuote, arrowUp)

        val withIntro = insertTextGapBeforeIndex(s1.blocks, 0)
        assertEquals(3, withIntro.blocks.size)
        val intro = assertIs<ComposerBlock.Text>(withIntro.blocks[0])
        val tail = assertIs<ComposerBlock.Text>(withIntro.blocks[2])
        assertTrue(intro.explicitGap)

        val arrowDown =
            threadComposerQuoteBlockKeyDecision(
                blocks = withIntro.blocks,
                blockIndex = 0,
                key = Key.DirectionDown,
                isComposing = false,
                shift = false,
                alt = false,
                ctrl = false,
                meta = false,
                selectionStart = 0,
                selectionEnd = 0,
                textLength = 0,
                armedDeleteQuoteId = null,
            )
        assertEquals(ThreadComposerQuoteKeyDecision.FocusTextBlock(tail.id, 0), arrowDown)
    }

    @Test
    fun removeQuoteBlockMergesSurroundingTextLosslesslyAtTheSeam() {
        val blocks =
            listOf(
                ComposerBlock.Text(id = "t1", text = "Before"),
                ComposerBlock.Quote(id = "q1", quote = qA),
                ComposerBlock.Text(id = "t2", text = "After"),
            )
        val removed = removeQuoteBlock(blocks, "q1")
        assertEquals(1, removed.blocks.size)
        val merged = assertIs<ComposerBlock.Text>(removed.blocks[0])
        assertEquals("Before\nAfter", merged.text)
        assertEquals("t1", removed.focusBlockId)
        assertEquals("Before".length, removed.focusOffset)
    }

    @Test
    fun roundTripsDraftMetadataAndOptimisticResolvedQuotes() {
        ThreadComposerQuoteMetaCache.clear()
        try {
            val loaded =
                listOf(
                    RaftThreadMessageItem(
                        id = qB.messageId,
                        authorId = "agent-sage",
                        authorName = "Sage",
                        authorKind = "agent",
                        timestampLabel = qB.createdAt,
                        body = "second question",
                    )
                )
            ThreadComposerQuoteMetaCache.remember(qA)

            val draft = "> first question\n> msg=11111111\n\nAnswer 1\n\n> second question\n> msg=22222222\n\nAnswer 2"
            val hydrated =
                parseDraftToComposerBlocks(draft) { refId, excerpt ->
                    resolveQuoteMetaForDraft(refId, excerpt, loaded, fallbackAuthorName = "Someone")
                }
            assertEquals(4, hydrated.size)
            val firstQuote = assertIs<ComposerBlock.Quote>(hydrated[0]).quote
            val secondQuote = assertIs<ComposerBlock.Quote>(hydrated[2]).quote
            assertEquals("Alice", firstQuote.authorName)
            assertEquals(qA.messageId, firstQuote.messageId)
            assertTrue(firstQuote.isPartial)
            assertEquals("Sage", secondQuote.authorName)
            assertEquals("agent", secondQuote.authorType)
            assertEquals(qB.messageId, secondQuote.messageId)
            assertFalse(secondQuote.isPartial)

            assertEquals(draft, serializeComposerBlocks(hydrated))
            assertTrue(hasComposerReplyText(hydrated))
            assertEquals("Answer 1\n\nAnswer 2", extractComposerReplyText(hydrated))

            val optimistic = buildOptimisticResolvedQuotesFromBlocks(hydrated)
            assertEquals(
                mapOf(
                    "0" to
                        ResolvedInlineQuote(
                            messageId = qA.messageId,
                            shortId = "11111111",
                            authorName = "Alice",
                            authorType = "user",
                            createdAt = qA.createdAt,
                            isPartial = true,
                        ),
                    "1" to
                        ResolvedInlineQuote(
                            messageId = qB.messageId,
                            shortId = "22222222",
                            authorName = "Sage",
                            authorType = "agent",
                            createdAt = qB.createdAt,
                            isPartial = false,
                        ),
                ),
                optimistic,
            )
            assertNull(buildOptimisticResolvedQuotesFromBlocks(createTextOnlyBlocks("plain message")))
        } finally {
            ThreadComposerQuoteMetaCache.clear()
        }
    }
}
