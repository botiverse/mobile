package build.raft.app

import build.raft.app.data.MessageQuoteSegment
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.parseMessageQuoteSegments
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ResolvedInlineQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun structuredMenuAndSelectionQuotesRespectFlagAndWholeVsPartial() {
        val message =
            RaftThreadMessageItem(
                id = sentId,
                authorId = "agent-sage",
                authorName = "Sage",
                authorKind = "agent",
                timestampLabel = "2026-11-22T09:00:00Z",
                body = "First line.\nSecond line.",
            )
        val pending = threadMessageMenuPendingQuote(message)
        assertNotNull(pending)
        assertEquals(sentId, pending.messageId)
        assertEquals("Sage", pending.authorName)
        assertEquals("agent", pending.authorType)
        assertEquals("First line.…", pending.excerpt)
        assertFalse(pending.isPartial)

        val partialInsert =
            threadSelectionQuoteInsert(
                selectedText = "First",
                markdown = message.body,
                message = message,
                quoteReplyEnabled = true,
            )
        assertNotNull(partialInsert.quote)
        assertEquals("First", partialInsert.quote?.excerpt)
        assertTrue(partialInsert.quote?.isPartial == true)

        val flagOffInsert =
            threadSelectionQuoteInsert(
                selectedText = "First",
                markdown = message.body,
                message = message,
                quoteReplyEnabled = false,
            )
        assertNull(flagOffInsert.quote)
        assertEquals("> First", flagOffInsert.fallbackMarkdown)
    }

    @Test
    fun quoteChipAvatarResolutionMatchesLoadedMessageOrSingleUniqueNameAndNeverGuesses() {
        val aliceOne = MemberProfile(id = MemberId("u-alice-1"), displayName = "Alice", handle = "alice1", kind = MemberKind.Human, avatar = AvatarReference.Url("https://cdn/a1.png"))
        val aliceTwo = MemberProfile(id = MemberId("u-alice-2"), displayName = "Alice", handle = "alice2", kind = MemberKind.Human, avatar = AvatarReference.Url("https://cdn/a2.png"))
        val bob = MemberProfile(id = MemberId("u-bob"), displayName = "Bob", handle = "bob", kind = MemberKind.Human, avatar = AvatarReference.Url("https://cdn/bob.png"))
        val sage = MemberProfile(id = MemberId("a-sage"), displayName = "Sage", handle = "sage", kind = MemberKind.Agent, avatar = AvatarReference.Url("https://cdn/sage.png"))
        val selfUser = StoredAuthUser(id = "u-bob", email = "bob@example.com", name = "Bob", avatarUrl = "https://cdn/bob-self.png")

        // Ambiguous duplicate display name without loaded message -> null (never guess).
        assertNull(
            resolveQuoteAuthorAvatar(
                messageId = "aaaaaaaa-0000-4000-8000-000000000001",
                authorName = "Alice",
                authorType = "user",
                loadedMessages = emptyList(),
                members = listOf(aliceOne, aliceTwo, bob, sage),
                currentUser = selfUser,
            )
        )

        // Loaded message disambiguates by messageId even when two people share the name "Alice".
        val loadedAliceTwo =
            RaftThreadMessageItem(
                id = "aaaaaaaa-0000-4000-8000-000000000001",
                authorId = "u-alice-2",
                authorName = "Alice",
                authorKind = "human",
                avatarWireValue = "https://cdn/a2.png",
                body = "Hi",
            )
        val resolvedFromLoaded =
            resolveQuoteAuthorAvatar(
                messageId = "aaaaaaaa-0000-4000-8000-000000000001",
                authorName = "Alice",
                authorType = "user",
                loadedMessages = listOf(loadedAliceTwo),
                members = listOf(aliceOne, aliceTwo, bob, sage),
                currentUser = selfUser,
            )
        assertEquals(
            QuoteChipAuthorAvatar(kind = RaftAvatarKind.Human, avatarWireValue = "https://cdn/a2.png", label = "Alice"),
            resolvedFromLoaded,
        )

        // Unique name match works without a loaded message and prefers currentUser's own avatarUrl.
        val resolvedSelf =
            resolveQuoteAuthorAvatar(
                messageId = "bbbbbbbb-0000-4000-8000-000000000002",
                authorName = "Bob",
                authorType = "user",
                loadedMessages = emptyList(),
                members = listOf(aliceOne, aliceTwo, bob, sage),
                currentUser = selfUser,
            )
        assertEquals(
            QuoteChipAuthorAvatar(kind = RaftAvatarKind.Human, avatarWireValue = "https://cdn/bob-self.png", label = "Bob"),
            resolvedSelf,
        )

        // Agent lookup checks only agent members.
        val resolvedAgent =
            resolveQuoteAuthorAvatar(
                messageId = "cccccccc-0000-4000-8000-000000000003",
                authorName = "Sage",
                authorType = "agent",
                loadedMessages = emptyList(),
                members = listOf(aliceOne, bob, sage),
                currentUser = selfUser,
            )
        assertEquals(
            QuoteChipAuthorAvatar(kind = RaftAvatarKind.Agent, avatarWireValue = "https://cdn/sage.png", label = "Sage"),
            resolvedAgent,
        )

        // Unknown name -> null.
        assertNull(
            resolveQuoteAuthorAvatar(
                messageId = "dddddddd-0000-4000-8000-000000000004",
                authorName = "Ghost",
                authorType = "user",
                loadedMessages = emptyList(),
                members = listOf(aliceOne, bob, sage),
                currentUser = selfUser,
            )
        )
    }
}
