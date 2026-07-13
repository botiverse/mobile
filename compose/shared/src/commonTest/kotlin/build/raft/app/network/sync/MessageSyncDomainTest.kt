/*
 * #491 A-minimal T-message-fold (round-2, WIRE-SHAPED) — behavioral parity gate for the KMP
 * messages-domain fold over the canonical wire row, pinned to manifest v4 (slock #4589).
 *
 * Covers:
 *   - canonical (seq, id) ordering; merge-by-id (New-on-existing / Updated);
 *   - the THREE production faces via explicit presentAggregates:
 *       messageNew (all present) / enrichedUpdated (all except conversationContext=newOnly) /
 *       taskStatusUpdated (only senderName + actionMetadata present);
 *   - mergePolicy: present-overwrite (present incl []-clear -> overwrite, absent -> preserve) for
 *     the aggregates, and commentRef = shared-null-preserve (present-AND-non-null overwrites;
 *     present-null [privacy-strip] OR absent both preserve — never cleared on the shared fold);
 *   - identity-if-unchanged (redundant frame -> same state instance);
 *   - pure + total (unknown event shape folds to unchanged state, never throws);
 *   - §6.1 continuation: a post-restore taskStatus edit reaches a PRE-restore row, overwriting
 *     canonical while preserving the omitted aggregates (incl. the viewer commentRef overlay).
 */
package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalAttachmentWire
import build.raft.app.canonical.message.CanonicalCommentRefWire
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessageSyncDomainTest {

    private fun row(
        id: String,
        channel: String = "c1",
        seq: Long = 1L,
        content: String = "b",
        senderName: String = "U",
        reactions: List<CanonicalReactionWire> = emptyList(),
        attachments: List<CanonicalAttachmentWire> = emptyList(),
        commentRef: CanonicalCommentRefWire? = null,
    ): CanonicalMessageRow = CanonicalMessageRow(
        id = id,
        channelId = channel,
        seq = seq,
        senderType = "human",
        senderId = "u1",
        content = content,
        messageType = "chat",
        createdAt = "t0",
        senderName = senderName,
        reactions = reactions,
        attachments = attachments,
        commentRef = commentRef,
    )

    private fun reaction(emoji: String = ":+1:") = CanonicalReactionWire(emoji = emoji, count = 1)
    private fun attachment(id: String = "a1") =
        CanonicalAttachmentWire(id = id, filename = "f.png", mimeType = "image/png", sizeBytes = 1024)
    private fun commentRef(id: String = "a1") = CanonicalCommentRefWire(attachmentId = id, filename = "f.png")

    private val MESSAGE_NEW = MessageDomainEvent.PRESENT_MESSAGE_NEW
    private val ENRICHED = MessageDomainEvent.PRESENT_ENRICHED_UPDATED
    private val TASK = MessageDomainEvent.PRESENT_TASK_STATUS_UPDATED

    private fun bucket(s: MessageDomainState, channel: String = "c1") = s.channelMessages[channel].orEmpty()
    private fun ids(s: MessageDomainState, channel: String = "c1") = bucket(s, channel).map { it.id }
    private fun new(s: MessageDomainState, r: CanonicalMessageRow, p: Set<MessageAggregateField> = MESSAGE_NEW) =
        applyMessageDomainEvent(s, MessageDomainEvent.New(r, p))
    private fun updated(s: MessageDomainState, r: CanonicalMessageRow, p: Set<MessageAggregateField>) =
        applyMessageDomainEvent(s, MessageDomainEvent.Updated(r, p))

    // --- ordering + merge-by-id ----------------------------------------------------------------

    @Test
    fun newAppendsAndSortsBySeqThenId() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m2", seq = 7))
        s = new(s, row("m3", seq = 9))
        s = new(s, row("m1", seq = 7)) // same seq -> id tie-break
        assertEquals(listOf("m1", "m2", "m3"), ids(s))
    }

    @Test
    fun outOfOrderArrivalLandsInCanonicalPosition() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m3", seq = 30))
        s = new(s, row("m1", seq = 10))
        assertEquals(listOf("m1", "m3"), ids(s))
    }

    @Test
    fun newOnExistingIdMergesInPlaceNotDuplicate() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m1", seq = 5, content = "old"))
        s = new(s, row("m1", seq = 5, content = "new"))
        assertEquals(listOf("m1"), ids(s))
        assertEquals("new", bucket(s).single().content)
    }

    @Test
    fun updatedForUnknownIdIsNoOp() {
        val s = new(createInitialMessageDomainState(), row("m1", seq = 5))
        assertSame(s, updated(s, row("ghost", seq = 6), TASK))
    }

    // --- three production faces (present-overwrite) --------------------------------------------

    @Test
    fun taskStatusFaceOmittingAggregatesPreservesThem() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m1", seq = 5, content = "old", reactions = listOf(reaction()), attachments = listOf(attachment()), commentRef = commentRef()))
        // task-status frame: canonical (content/senderName/actionMetadata) present, other aggregates ABSENT.
        s = updated(s, row("m1", seq = 5, content = "task-updated"), TASK)
        val r = bucket(s).single()
        assertEquals("task-updated", r.content) // canonical overwritten
        assertEquals(listOf(reaction()), r.reactions) // preserved
        assertEquals(listOf(attachment()), r.attachments) // preserved
        assertEquals("a1", r.commentRef?.attachmentId) // preserved
    }

    @Test
    fun enrichedFacePresentEmptyReactionsClears() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m1", seq = 5, reactions = listOf(reaction())))
        s = updated(s, row("m1", seq = 5, reactions = emptyList()), ENRICHED) // present-[] = clear
        assertTrue(bucket(s).single().reactions.isEmpty())
    }

    @Test
    fun enrichedFaceOmitsConversationContextSoItPreservesFromNew() {
        var s = createInitialMessageDomainState()
        val ctx = CanonicalConversationContextWire(channelType = "thread")
        s = new(s, row("m1", seq = 5).copy(conversationContext = ctx)) // newOnly sets it
        s = updated(s, row("m1", seq = 5, content = "edit").copy(conversationContext = null), ENRICHED)
        val r = bucket(s).single()
        assertEquals("edit", r.content)
        assertEquals(ctx, r.conversationContext) // enriched omits conversationContext -> preserved
    }

    // --- commentRef = shared-null-preserve -----------------------------------------------------

    @Test
    fun commentRefPresentNonNullOverwrites() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m1", seq = 5, commentRef = commentRef("a1")))
        s = updated(s, row("m1", seq = 5, commentRef = commentRef("a2")), ENRICHED)
        assertEquals("a2", bucket(s).single().commentRef?.attachmentId)
    }

    @Test
    fun commentRefPresentNullIsPrivacyStripSoPreserves() {
        // shared-null-preserve: commentRef present in the aggregate set BUT value null (room-level
        // privacy strip) must NOT clear the local viewer overlay.
        var s = createInitialMessageDomainState()
        s = new(s, row("m1", seq = 5, commentRef = commentRef("a1")))
        s = updated(s, row("m1", seq = 5, content = "e", commentRef = null), ENRICHED)
        assertEquals("a1", bucket(s).single().commentRef?.attachmentId) // preserved despite present-null
    }

    @Test
    fun commentRefAbsentPreserves() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m1", seq = 5, commentRef = commentRef("a1")))
        s = updated(s, row("m1", seq = 5, content = "e", commentRef = null), TASK) // absent
        assertEquals("a1", bucket(s).single().commentRef?.attachmentId)
    }

    // --- identity + purity ---------------------------------------------------------------------

    @Test
    fun redundantUpdatedReturnsSameStateInstance() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m1", seq = 5, content = "b", commentRef = commentRef()))
        val after = updated(s, row("m1", seq = 5, content = "b", commentRef = null), TASK)
        assertSame(s, after)
    }

    @Test
    fun unknownEventFoldsToUnchangedStateWithoutThrowing() {
        val config = messagesSyncDomain()
        val initial = config.initialState()
        assertSame(initial, config.fold(initial, "not-a-message-event", SyncFoldFrame("c1", 1)))
    }

    // --- §6.1 continuation: post-restore taskStatus edit reaches a pre-restore row --------------

    @Test
    fun taskStatusEditAfterSnapshotRestoreHitsPreRestoreRowAndPreservesAggregates() {
        val config = messagesSyncDomain()
        val restored = MessageDomainState(
            channelMessages = mapOf(
                "c1" to listOf(
                    row("m1", seq = 5, content = "orig", reactions = listOf(reaction()), commentRef = commentRef()),
                    row("m2", seq = 6, content = "orig2"),
                ),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val state0 = config.fromSnapshot(SyncSnapshot("c1", watermark = 6, epoch = null, state = restored)) as MessageDomainState

        val edited = applyMessageDomainEvent(
            state0,
            MessageDomainEvent.Updated(row("m1", seq = 5, content = "edited-after-restore"), TASK),
        )
        val m1 = edited.channelMessages.getValue("c1").first { it.id == "m1" }
        assertEquals("edited-after-restore", m1.content) // reached restored row
        assertEquals(listOf(reaction()), m1.reactions) // aggregate preserved across restore+edit
        assertEquals("a1", m1.commentRef?.attachmentId) // viewer overlay preserved
        assertEquals("orig2", edited.channelMessages.getValue("c1").first { it.id == "m2" }.content)
    }

    @Test
    fun actionMetadataPresentOnAllFacesOverwrites() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m1", seq = 5).copy(actionMetadata = JsonPrimitive("v1")))
        s = updated(s, row("m1", seq = 5).copy(actionMetadata = JsonPrimitive("v2")), TASK) // task face carries actionMetadata
        assertEquals(JsonPrimitive("v2"), bucket(s).single().actionMetadata)
    }

    @Test
    fun foldIsChannelScoped() {
        var s = createInitialMessageDomainState()
        s = new(s, row("m1", channel = "c1", seq = 5))
        s = new(s, row("m2", channel = "c2", seq = 5))
        assertEquals(listOf("m1"), ids(s, "c1"))
        assertEquals(listOf("m2"), ids(s, "c2"))
    }

    @Test
    fun initialStateIsEmpty() {
        assertNull(createInitialMessageDomainState().channelMessages["c1"])
    }
}
