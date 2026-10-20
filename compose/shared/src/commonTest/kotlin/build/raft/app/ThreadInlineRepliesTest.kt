package build.raft.app

import androidx.compose.ui.graphics.compositeOver
import build.raft.app.data.ThreadSnapshot
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.initialThreadRepliesReadModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadInlineRepliesTest {
    @Test
    fun inlineRepliesAreVisibleWithoutAFeatureFlagWhenReplyActionsAreAvailable() {
        val model =
            initialThreadRepliesReadModel(
                replies = listOf(reply("r-1", 1)),
                replyCount = 1,
                limit = 3,
            )

        val presentation =
            assertNotNull(
                threadInlineReplyPresentationForMessage(
                    model = model,
                    replyMetadata = metadata(replyCount = 1),
                    hasDraft = false,
                    showReplyAction = true,
                )
            )

        assertEquals(listOf("r-1"), presentation.replies.map { it.messageId })
        assertNull(
            threadInlineReplyPresentationForMessage(
                model = model,
                replyMetadata = metadata(replyCount = 1),
                hasDraft = false,
                showReplyAction = false,
            )
        )
    }

    @Test
    fun replyMetadataDoesNotQualifyForTheRetiredFooterChip() {
        assertFalse(
            RaftThreadMessageItem(
                id = "parent-1",
                authorId = "user-1",
                authorName = "User",
                replyCount = 1,
            ).hasThreadFooterMeta()
        )
    }

    @Test
    fun neutralMessageSurfacesMatchWebBlackOpacityContract() {
        assertEquals(
            RaftColor.TextBlack.copy(alpha = 0.03f),
            threadNeutralMessageSurfaceColor(emphasized = false),
        )
        assertEquals(
            RaftColor.TextBlack.copy(alpha = 0.08f),
            threadNeutralMessageSurfaceColor(emphasized = true),
        )
        assertEquals(RaftAppThemeState.colors.fillMuted, threadReactionChipBackground(selected = false))
        assertEquals(
            RaftAppThemeState.colors.fillPressed.compositeOver(RaftAppThemeState.colors.fillMuted),
            threadReactionChipBackground(selected = false, emphasized = true),
        )
        assertEquals(RaftAppThemeState.colors.attentionSoft, threadReactionChipBackground(selected = true))
        assertEquals(RaftAppThemeState.colors.fillMuted, threadReactionAddChipBackground())
        assertEquals(
            RaftAppThemeState.colors.fillPressed.compositeOver(RaftAppThemeState.colors.fillMuted),
            threadReactionAddChipBackground(emphasized = true),
        )
    }

    @Test
    fun metaChipsRenderBeforeInlineRepliesWhenBothAreVisible() {
        assertEquals(
            listOf(
                ThreadMessageTrailingSection.MetaChips,
                ThreadMessageTrailingSection.InlineReplies,
            ),
            threadMessageTrailingSectionOrder(
                hasMetaChips = true,
                hasInlineReplies = true,
            ),
        )
    }

    @Test
    fun systemRowsAdvanceSnapshotSeamButDoNotConsumeLatestThreeUiSlots() {
        val model =
            initialThreadRepliesReadModel(
                replies =
                    listOf(
                        reply("r-1", 1),
                        reply("r-2", 2),
                        reply("r-3", 3),
                        reply("system-4", 4, senderType = "system"),
                        reply("r-5", 5),
                    ),
                replyCount = 5,
                limit = 3,
            )

        assertEquals(listOf("r-2", "r-3", "r-5"), model.replies.map { it.messageId })
        assertEquals(5L, model.snapshotSeq)
        assertEquals(5, model.replyCount)
    }

    @Test
    fun presentationUsesAuthoritativeCountUnreadAndDraftWhileKeepingThreeRows() {
        val model =
            initialThreadRepliesReadModel(
                replies = (1L..4L).map { reply("r-$it", it) },
                replyCount = 9,
                limit = 3,
            )

        val presentation =
            assertNotNull(
                threadInlineReplyPresentation(
                    model = model,
                    replyMetadata = metadata(replyCount = 9, unreadReplyCount = 2),
                    hasDraft = true,
                )
            )

        assertEquals(listOf("r-2", "r-3", "r-4"), presentation.replies.map { it.messageId })
        assertEquals(9, presentation.replyCount)
        assertEquals(
            ThreadInlineReplyCountLabel(ThreadInlineReplyCountLabel.Kind.Many, 9),
            presentation.countLabel(),
        )
        assertEquals(2, presentation.unreadCount)
        assertEquals(true, presentation.hasDraft)
    }

    @Test
    fun countLabelIsAlwaysTheAuthoritativeTotalNeverTotalMinusVisiblePreviews() {
        // 3 visible rows of a 9-total thread: label must stay the full total 9. The retired
        // "earlier replies" branch derived 9 - 3 = 6; that mutation must fail here.
        val overflow =
            assertNotNull(
                threadInlineReplyPresentation(
                    model =
                        initialThreadRepliesReadModel(
                            replies = (1L..9L).map { reply("r-$it", it) },
                            replyCount = 9,
                            limit = 3,
                        ),
                    replyMetadata = metadata(replyCount = 9),
                    hasDraft = false,
                )
            )
        assertEquals(3, overflow.replies.size)
        assertEquals(
            ThreadInlineReplyCountLabel(ThreadInlineReplyCountLabel.Kind.Many, 9),
            overflow.countLabel(),
        )

        // Fully visible thread (3 of 3): same total label, no special branch.
        val exact =
            assertNotNull(
                threadInlineReplyPresentation(
                    model =
                        initialThreadRepliesReadModel(
                            replies = (1L..3L).map { reply("r-$it", it) },
                            replyCount = 3,
                            limit = 3,
                        ),
                    replyMetadata = metadata(replyCount = 3),
                    hasDraft = false,
                )
            )
        assertEquals(
            ThreadInlineReplyCountLabel(ThreadInlineReplyCountLabel.Kind.Many, 3),
            exact.countLabel(),
        )

        // Single reply keeps the singular key.
        val single =
            assertNotNull(
                threadInlineReplyPresentation(
                    model =
                        initialThreadRepliesReadModel(
                            replies = listOf(reply("r-1", 1)),
                            replyCount = 1,
                            limit = 3,
                        ),
                    replyMetadata = metadata(replyCount = 1),
                    hasDraft = false,
                )
            )
        assertEquals(
            ThreadInlineReplyCountLabel(ThreadInlineReplyCountLabel.Kind.One, 1),
            single.countLabel(),
        )
    }

    @Test
    fun inlineReplyRowHeightKeepsBreathingRoomAtDefaultAndLargeFontScales() {
        assertEquals(20f, threadInlineReplyRowHeightDp(fontScale = 1f))
        assertEquals(
            24.8f,
            threadInlineReplyRowHeightDp(fontScale = 1.3f),
            absoluteTolerance = 0.001f,
        )
        assertEquals(20f, threadInlineReplyRowHeightDp(fontScale = 0.85f))
    }

    @Test
    fun inlineReplyTimestampUsesTheSharedMonospaceFamily() {
        assertEquals(RaftMonoFamily, ThreadInlineReplyTimestampStyle.fontFamily)
    }

    @Test
    fun multilineReplyPreviewUsesTheAvailableSingleLineWidth() {
        assertEquals(
            "New activity - 14:10 final Android carrier is ready",
            threadInlineReplyPreviewText(
                """
                New activity

                - 14:10 final Android carrier is ready
                """.trimIndent()
            ),
        )
    }

    @Test
    fun zeroReplyCountHasNoSurfaceAndCachedPreviewCannotAdmitGeometry() {
        assertNull(
            threadInlineReplyPresentation(
                model = null,
                replyMetadata = metadata(replyCount = 0),
                hasDraft = true,
            )
        )
        val staleCachedPreview =
            initialThreadRepliesReadModel(
                replies = listOf(reply("reply-9", 9)),
                replyCount = 9,
                limit = 3,
            )
        assertNull(
            threadInlineReplyPresentation(
                model = staleCachedPreview,
                replyMetadata = metadata(replyCount = 0),
                hasDraft = false,
            )
        )
    }

    @Test
    fun systemOnlyPreviewHasNoInlineSurface() {
        val systemOnly =
            initialThreadRepliesReadModel(
                replies = listOf(reply("system-1", 1, senderType = "system")),
                replyCount = 1,
                limit = 3,
            )
        assertNull(
            threadInlineReplyPresentation(
                model = systemOnly,
                replyMetadata = metadata(replyCount = 1),
                hasDraft = false,
            )
        )
    }

    @Test
    fun derivedPreviewNeverEntersSerializedRouteOrPageDataMessageState() {
        val model =
            initialThreadRepliesReadModel(
                replies = listOf(reply("reply-secret-xyz", 1)),
                replyCount = 1,
                limit = 3,
            )
        val message =
            RaftThreadMessageItem(
                id = "parent-1",
                authorId = "user-1",
                authorName = "User",
                inlineReplies = model,
            )

        val encoded = Json.encodeToString(message)

        assertFalse("inlineReplies" in encoded)
        assertFalse("reply-secret-xyz" in encoded)
    }

    @Test
    fun pageCoherentReplySummariesStayOutsideSerializableThreadSnapshotState() {
        val descriptor = ThreadSnapshot.serializer().descriptor
        val serializedFields =
            (0 until descriptor.elementsCount).map { index -> descriptor.getElementName(index) }

        assertFalse("replySummaries" in serializedFields)
    }

    private fun reply(
        id: String,
        seq: Long,
        senderType: String = "user",
    ): ReplyPreview =
        ReplyPreview(
            messageId = id,
            seq = seq,
            content = "reply $seq",
            senderId = "sender-$seq",
            senderType = senderType,
            senderName = "handle-$seq",
            senderAvatarUrl = null,
            createdAt = "2026-07-24T08:00:00Z",
            senderDisplayName = "Display $seq",
        )

    private fun metadata(
        replyCount: Int,
        unreadReplyCount: Int = 0,
    ): ThreadReplyMetadata =
        ThreadReplyMetadata(
            parentMessageId = "parent-1",
            threadChannelId = "thread-1",
            replyCount = replyCount,
            unreadReplyCount = unreadReplyCount,
            firstUnreadReplyMessageId = null,
            isThreadFollowed = false,
        )
}
