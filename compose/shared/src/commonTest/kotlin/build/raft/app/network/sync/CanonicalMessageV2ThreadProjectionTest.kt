package build.raft.app.network.sync

import build.raft.app.ThreadMessageDeliverySent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalMessageV2ThreadProjectionTest {
    @Test
    fun projectsTopLevelFactLikeTheRestParserWithCountOnlyReactions() {
        val diagnostics = mutableListOf<String>()
        val item = assertNotNull(
            envelope(
                fact = fact().copy(
                    randomId = "r1",
                    senderDescription = "Builder",
                    senderMembershipStatus = "active",
                    replyCount = 3,
                    reactions = listOf(
                        ReactionFact("🔥", 2, previewK = listOf(ReactionActorRef("human-a", "Ada"))),
                        ReactionFact("", 5),
                        ReactionFact("👀", 0),
                    ),
                    attachments = listOf(
                        CanonicalAttachmentV2("a1", "image.png", "image/png", 1536, width = 640, height = 480, thumbnailUrl = "thumb"),
                        CanonicalAttachmentV2("a2", "", "application/octet-stream", 7),
                    ),
                ),
                childReplyScope = ReplyScopeRefV2("server-a", "thread-a"),
            ).toThreadMessageItem(currentUserId = "human-a", diagnostics = diagnostics::add),
        )

        assertTrue(diagnostics.isEmpty())
        assertEquals("message-a", item.id)
        assertEquals("human-a", item.authorId)
        assertEquals("Ada", item.authorName)
        assertEquals("Builder", item.authorDescription)
        assertEquals("human", item.authorKind)
        assertTrue(item.isOwn)
        assertEquals("A", item.avatarLabel)
        assertEquals("active", item.senderMembershipStatus)
        assertEquals("2026-07-12T04:00:00Z", item.timestampLabel)
        assertEquals(1_783_828_800_000L, item.createdAtEpochMillis)
        assertEquals(42L, item.seq)
        assertEquals("Chat", item.messageType)
        assertEquals("hello", item.body)
        assertEquals("r1", item.randomId)
        assertEquals("thread-a", item.threadChannelId)
        assertEquals(3, item.replyCount)
        assertEquals(ThreadMessageDeliverySent, item.deliveryState)
        assertTrue(item.metadata.isEmpty())
        assertNull(item.forwardBundle)
        assertNull(item.commentRef)

        // Ruling (3): the offline frame carries emoji + count only. Even though the fact's preview
        // names the current user, the roster and `selected` wait for the next network refresh.
        val reaction = item.reactions.single()
        assertEquals("🔥", reaction.emoji)
        assertEquals(2, reaction.count)
        assertFalse(reaction.selected)
        assertTrue(reaction.reactorIds.isEmpty())
        assertTrue(reaction.reactorNames.isEmpty())

        assertEquals(listOf("a1", "a2"), item.attachments.map { it.id })
        assertEquals("image.png", item.attachments[0].name)
        assertEquals(1536L, item.attachments[0].sizeBytes)
        assertEquals("thumb", item.attachments[0].thumbnailUrl)
        assertEquals("Attachment 2", item.attachments[1].name)
        assertEquals(0, item.attachments[0].commentCount)
        // The inline image reserves its box from these before it loads.
        assertEquals(640 to 480, item.attachments[0].width to item.attachments[0].height)
        assertEquals(null, item.attachments[1].width)
    }

    @Test
    fun retractedFactProjectsFlaggedWithoutAttachmentsOrReactions() {
        val item = assertNotNull(
            envelope(
                fact = fact().copy(
                    deletedAt = "2026-10-20T08:15:30.123Z",
                    reactions = listOf(ReactionFact("🔥", 2)),
                    attachments = listOf(CanonicalAttachmentV2("a1", "image.png", "image/png", 1536)),
                ),
            ).toThreadMessageItem(currentUserId = null),
        )
        assertEquals("2026-10-20T08:15:30.123Z", item.deletedAt)
        assertTrue(item.reactions.isEmpty())
        assertTrue(item.attachments.isEmpty())
        // Negative control: a live fact has no deletedAt.
        assertNull(assertNotNull(envelope().toThreadMessageItem(currentUserId = null)).deletedAt)
    }

    @Test
    fun otherUsersMessageIsNotOwnAndBlankNameFallsBack() {
        val item = assertNotNull(
            envelope(fact = fact().copy(senderName = " ")).toThreadMessageItem(currentUserId = "human-z"),
        )
        assertFalse(item.isOwn)
        assertEquals("Unknown", item.authorName)
        assertEquals("U", item.avatarLabel)
        assertNull(item.threadChannelId)
        assertFalse(assertNotNull(envelope().toThreadMessageItem(currentUserId = null)).isOwn)
    }

    @Test
    fun systemMessageRendersAsSystemAuthorWithActionCardMetadata() {
        val item = assertNotNull(
            envelope(
                fact = fact().copy(
                    senderType = "user",
                    messageType = "system",
                    actionMetadata = CanonicalActionMetadataV2.ActionCard(
                        action = CanonicalActionCardActionV2(type = "create-channel", name = "Launch", humans = listOf("u1")),
                        result = CanonicalActionCardResultV2(kind = "channel", id = "c9", name = "Launch"),
                    ),
                ),
            ).toThreadMessageItem(currentUserId = "human-a"),
        )

        assertEquals("System", item.messageType)
        assertEquals("system", item.authorKind)
        assertEquals("action-card", item.metadata["actionCard.kind"])
        assertEquals("create-channel", item.metadata["actionCard.type"])
        assertEquals("Launch", item.metadata["actionCard.name"])
        assertNull(item.forwardBundle)
    }

    @Test
    fun forwardedBundleRendersLikeRestDecodedBundle() {
        val item = assertNotNull(
            envelope(
                fact = fact().copy(
                    actionMetadata = CanonicalActionMetadataV2.ForwardedBundle(
                        items = listOf(
                            CanonicalForwardedBundleItemV2(
                                index = 0,
                                author = CanonicalForwardedAuthorSnapshotV2(type = "human", name = "Nina"),
                                createdAt = "t1",
                                target = CanonicalForwardedTargetSnapshotV2(type = "channel", label = "#general"),
                                content = "forwarded body",
                                attachments = listOf(CanonicalForwardedAttachmentSnapshotV2("f.txt", "text/plain")),
                            ),
                        ),
                    ),
                ),
            ).toThreadMessageItem(currentUserId = "human-a"),
        )

        assertTrue(item.metadata.isEmpty())
        val bundle = assertNotNull(item.forwardBundle)
        assertEquals("forwarded body", bundle.messages.single().body)
        assertEquals("Nina", bundle.messages.single().authorName)
        assertEquals(1, bundle.messages.single().attachmentCount)
    }

    @Test
    fun unknownKindsFailClosedInsteadOfGuessingAnAuthor() {
        val diagnostics = mutableListOf<String>()
        // "system" is only the agent-facing projection's vocabulary; the producer never sends it.
        assertNull(envelope(fact = fact().copy(senderType = "system")).toThreadMessageItem(currentUserId = "human-a") {})
        assertNull(
            envelope(fact = fact().copy(senderType = "future-sender")).toThreadMessageItem(
                currentUserId = "human-a",
                diagnostics = diagnostics::add,
            ),
        )
        assertNull(
            envelope(fact = fact().copy(messageType = "future-type")).toThreadMessageItem(
                currentUserId = "human-a",
                diagnostics = diagnostics::add,
            ),
        )
        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.any { "canonical_v2_message_sender" in it }, diagnostics.toString())
        assertTrue(diagnostics.any { "canonical_v2_message_type" in it }, diagnostics.toString())
    }

    private fun fact() = CanonicalMessageNodeV2(
        id = "message-a",
        seq = 42,
        // Producer closed set is {user, agent} (docs/projection-contract.md); "human" is the projected value.
        senderType = "user",
        senderId = "human-a",
        content = "hello",
        messageType = "chat",
        createdAt = "2026-07-12T04:00:00Z",
        senderName = "Ada",
    )

    private fun envelope(
        fact: CanonicalMessageNodeV2 = fact(),
        childReplyScope: ReplyScopeRefV2? = null,
    ) = CanonicalMessageEnvelopeV2(
        fact = fact,
        context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", "channel-a")),
        childReplyScope = childReplyScope,
    )
}
