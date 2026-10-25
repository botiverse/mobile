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
import build.raft.app.canonical.message.CanonicalExternalAuthorWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalMentionWire
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        senderType: String = "user",
        senderId: String = "u1",
        mentions: List<CanonicalMentionWire> = emptyList(),
        reactions: List<CanonicalReactionWire> = emptyList(),
        attachments: List<CanonicalAttachmentWire> = emptyList(),
        commentRef: CanonicalCommentRefWire? = null,
    ): CanonicalMessageRow = CanonicalMessageRow(
        id = id,
        channelId = channel,
        seq = seq,
        senderType = senderType,
        senderId = senderId,
        content = content,
        messageType = "chat",
        createdAt = "t0",
        senderName = senderName,
        mentions = mentions,
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

    // --- externalAuthor = present-overwrite (nullable) ------------------------------------------

    private fun externalAuthor(displayName: String = "jinlu", revision: Long = 1) = CanonicalExternalAuthorWire(
        actorKind = "human",
        actorProjectionRevision = revision,
        appRegistrationId = "app-1",
        displayName = displayName,
        externalActorId = "U123",
        externalConversationId = "C42",
        externalMessageId = "1700000000.000100",
        installId = "inst-1",
        projectionId = "proj-1",
        provider = "slack",
        workspaceId = "T1",
    )

    private fun externalRow(author: CanonicalExternalAuthorWire?, content: String = "b") =
        row("m1", seq = 5, content = content, senderType = "external_projection", senderId = "ext_slack_U123")
            .copy(externalAuthor = author)

    @Test
    fun externalAuthorPresentValueOverwrites() {
        var s = createInitialMessageDomainState()
        s = new(s, externalRow(externalAuthor()))
        assertEquals(externalAuthor(), bucket(s).single().externalAuthor)
        s = updated(s, externalRow(externalAuthor("jinlu (renamed)", revision = 2)), ENRICHED)
        assertEquals(externalAuthor("jinlu (renamed)", revision = 2), bucket(s).single().externalAuthor)
    }

    @Test
    fun externalAuthorAbsentOnTaskStatusFacePreserves() {
        assertFalse(MessageAggregateField.ExternalAuthor in TASK)
        var s = createInitialMessageDomainState()
        s = new(s, externalRow(externalAuthor()))
        s = updated(s, externalRow(null, content = "task-updated"), TASK)
        val r = bucket(s).single()
        assertEquals("task-updated", r.content)
        assertEquals(externalAuthor(), r.externalAuthor)
    }

    @Test
    fun externalAuthorPresentNullClears() {
        // Unlike commentRef (shared-null-preserve) and deletedAt (one-way), a present null clears.
        assertTrue(MessageAggregateField.ExternalAuthor in ENRICHED && MessageAggregateField.ExternalAuthor in MESSAGE_NEW)
        var s = createInitialMessageDomainState()
        s = new(s, externalRow(externalAuthor()))
        s = updated(s, externalRow(null), ENRICHED)
        assertNull(bucket(s).single().externalAuthor)
    }

    @Test
    fun externalAuthorAbsentOnABrandNewRowIsNull() {
        val s = new(createInitialMessageDomainState(), externalRow(externalAuthor()), TASK)
        assertNull(bucket(s).single().externalAuthor)
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
        assertSame(initial, config.fold(initial, "not-a-message-event", SyncFoldFrame("c1", 1uL)))
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
        val state0 = config.fromSnapshot(SyncSnapshot("c1", watermark = 6uL, epoch = null, state = restored)) as MessageDomainState

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

    // --- coverage-aware read-state projection -------------------------------------------------

    private fun baseline(
        state: MessageDomainState = createInitialMessageDomainState(),
        rows: List<CanonicalMessageRow>,
        serverId: String = "s1",
        memberId: String = "me",
        receiverKind: MessageReceiverKind = MessageReceiverKind.User,
        scopeId: String = "c1",
        epoch: String = "e1",
        coveredFromSeq: Long = rows.minOfOrNull { it.seq } ?: 1L,
        remoteHighWaterSeq: Long = rows.maxOfOrNull { it.seq } ?: 0L,
        hasGap: Boolean = false,
        hasNewer: Boolean = false,
    ): MessageDomainState =
        rebaselineMessageScope(
            state = state,
            snapshot = MessageScopeCoverageSnapshot(
                serverId = serverId,
                memberId = memberId,
                receiverKind = receiverKind,
                scopeId = scopeId,
                epoch = epoch,
                rows = rows,
                coveredFromSeq = coveredFromSeq,
                remoteHighWaterSeq = remoteHighWaterSeq,
                hasGap = hasGap,
                hasNewer = hasNewer,
            ),
        )

    private fun project(
        state: MessageDomainState,
        maxReadSeq: Long,
        serverId: String = "s1",
        memberId: String = "me",
        receiverKind: MessageReceiverKind? = MessageReceiverKind.User,
        scopeId: String = "c1",
        epoch: String = "e1",
        version: Long = 1L,
    ): MessageReadStateProjection =
        applyReadStateProjection(
            state = state,
            serverId = serverId,
            memberId = memberId,
            receiverKind = receiverKind,
            scopeId = scopeId,
            epoch = epoch,
            checkpoint = MessageReadStateCheckpoint(readStateVersion = version, maxReadSeq = maxReadSeq),
        )

    @Test
    fun completeTailProjectsUnreadMentionAndFirstAnchorsWithWebSemantics() {
        val state = baseline(
            rows = listOf(
                row("read", seq = 10, senderId = "u2"),
                row("self", seq = 11, senderId = "me"),
                row(
                    "mention",
                    seq = 12,
                    senderId = "u2",
                    mentions = listOf(CanonicalMentionWire(id = "me", name = "Me", type = "user")),
                ),
                row("unread", seq = 13, senderId = "u3"),
            ),
        )

        assertEquals(
            MessageReadStateProjection(
                readStateVersion = 7,
                maxReadSeq = 10,
                unreadCount = 2,
                hasMention = true,
                firstUnreadMessageId = "mention",
                firstMentionMessageId = "mention",
                complete = true,
            ),
            project(state, maxReadSeq = 10, version = 7),
        )
    }

    @Test
    fun agentReceiverExcludesAgentSelfAndRecognizesAgentMention() {
        val state = baseline(
            receiverKind = MessageReceiverKind.Agent,
            rows = listOf(
                row("self", seq = 11, senderType = "agent", senderId = "me"),
                row(
                    "mention",
                    seq = 12,
                    senderType = "user",
                    senderId = "u2",
                    mentions = listOf(CanonicalMentionWire(id = "me", name = "Me", type = "agent")),
                ),
            ),
        )

        val projection = project(
            state = state,
            maxReadSeq = 10,
            receiverKind = MessageReceiverKind.Agent,
        )

        assertEquals(1, projection.unreadCount)
        assertTrue(projection.hasMention)
        assertEquals("mention", projection.firstUnreadMessageId)
        assertEquals("mention", projection.firstMentionMessageId)
    }

    @Test
    fun missingOrMismatchedReceiverKindFailsClosed() {
        val state = baseline(rows = listOf(row("m11", seq = 11)))

        assertFalse(project(state, maxReadSeq = 10, receiverKind = null).complete)
        assertFalse(project(state, maxReadSeq = 10, receiverKind = MessageReceiverKind.Agent).complete)
    }

    @Test
    fun projectionFailsClosedWhenTailStartsAfterReadCursor() {
        val state = baseline(
            rows = listOf(row("m12", seq = 12)),
            coveredFromSeq = 12,
            remoteHighWaterSeq = 12,
        )

        assertFalse(project(state, maxReadSeq = 10).complete)
    }

    @Test
    fun projectionFailsClosedForKnownGapOrUnloadedNewerTail() {
        val rows = listOf(row("m11", seq = 11), row("m12", seq = 12))
        assertFalse(project(baseline(rows = rows, hasGap = true), maxReadSeq = 10).complete)
        assertFalse(project(baseline(rows = rows, hasNewer = true), maxReadSeq = 10).complete)
    }

    @Test
    fun coverageIsBoundToServerMemberScopeAndEpoch() {
        val state = baseline(rows = listOf(row("m11", seq = 11)))

        assertFalse(project(state, maxReadSeq = 10, serverId = "other").complete)
        assertFalse(project(state, maxReadSeq = 10, memberId = "other").complete)
        assertFalse(project(state, maxReadSeq = 10, scopeId = "other").complete)
        assertFalse(project(state, maxReadSeq = 10, epoch = "other").complete)
    }

    @Test
    fun sparseRealtimeCanExtendContinuousCompleteTailButCannotBridgeGap() {
        var state = baseline(rows = listOf(row("m11", seq = 11)))
        state = new(state, row("m12", seq = 12))
        assertTrue(project(state, maxReadSeq = 10).complete)
        assertEquals(2, project(state, maxReadSeq = 10).unreadCount)

        state = new(state, row("m14", seq = 14))
        assertFalse(project(state, maxReadSeq = 10).complete)
        assertTrue(state.scopeCoverage.getValue("c1").hasGap)
    }

    @Test
    fun sparseRealtimeWithoutAuthoritativeBaselineNeverDeclaresCoverageComplete() {
        val state = new(createInitialMessageDomainState(), row("m11", seq = 11))

        assertNull(state.scopeCoverage["c1"])
        assertFalse(project(state, maxReadSeq = 10).complete)
    }

    @Test
    fun pagingFailureOrCacheEvictionInvalidatesProofButKeepsSparseRows() {
        val state = baseline(rows = listOf(row("m11", seq = 11)))

        val invalidated = invalidateMessageScopeCoverage(state, "c1")

        assertEquals(listOf("m11"), ids(invalidated))
        assertNull(invalidated.scopeCoverage["c1"])
        assertFalse(project(invalidated, maxReadSeq = 10).complete)
    }

    @Test
    fun snapshotRestoreTrustsRowsAndCoverageTogetherOnlyForMatchingEpoch() {
        val config = messagesSyncDomain()
        val persisted = baseline(rows = listOf(row("m11", seq = 11)))

        val restored = config.fromSnapshot(SyncSnapshot("c1", watermark = 11uL, epoch = "e1", state = persisted)) as MessageDomainState
        assertTrue(project(restored, maxReadSeq = 10).complete)

        val rejected = config.fromSnapshot(SyncSnapshot("c1", watermark = 11uL, epoch = "e2", state = persisted)) as MessageDomainState
        assertTrue(rejected.channelMessages.isEmpty())
        assertTrue(rejected.scopeCoverage.isEmpty())
    }

    @Test
    fun higherVersionCursorRewindIsProjectedWithoutMonotonicCursorAssumption() {
        val state = baseline(rows = listOf(row("m11", seq = 11), row("m12", seq = 12)))

        val readForward = project(state, maxReadSeq = 12, version = 4)
        val rewound = project(state, maxReadSeq = 10, version = 5)

        assertEquals(0, readForward.unreadCount)
        assertEquals(2, rewound.unreadCount)
        assertTrue(rewound.complete)
    }

    @Test
    fun invalidReadStateCheckpointFailsClosed() {
        val state = baseline(rows = listOf(row("m11", seq = 11)))

        assertFalse(project(state, maxReadSeq = 10, version = 0).complete)
        assertFalse(project(state, maxReadSeq = -1, version = 1).complete)
    }

    @Test
    fun realtimeFirstSameWatermarkAuthoritativeRebaselineInstallsCoverageThroughDomainHost() {
        val host = MessageDomainSyncHost()
        val realtime = row("m14", seq = 14, senderId = "u2")
        assertEquals(
            SyncIngestOutcome.MaxAdvanced("c1", 14uL),
            host.ingestFrame(
                SyncFrame(
                    scopeId = "c1",
                    seq = 14uL,
                    epoch = "e1",
                    event = MessageDomainEvent.New(realtime, MESSAGE_NEW),
                ),
            ),
        )
        val sparse = host.state("c1")
        assertNull(sparse.scopeCoverage["c1"])

        val authoritative = baseline(state = sparse, rows = listOf(realtime), coveredFromSeq = 14)
        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 1))
        assertEquals(
            SyncIngestOutcome.Applied("c1", 14uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot(
                    scopeId = "c1",
                    watermark = 14uL,
                    epoch = "e1",
                    state = authoritative,
                ),
                generation = 1,
            ),
        )
        val installed = host.state("c1")
        assertTrue(project(installed, maxReadSeq = 13).complete)

        val staleState = baseline(
            state = installed,
            rows = listOf(realtime, row("stale", seq = 15, senderId = "u3")),
            remoteHighWaterSeq = 15,
        )
        assertFalse(host.beginAuthoritativeRebaseline("c1", generation = 1))
        assertEquals(
            SyncIngestOutcome.DuplicateDropped("c1", 15uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot(
                    scopeId = "c1",
                    watermark = 15uL,
                    epoch = "e1",
                    state = staleState,
                ),
                generation = 1,
            ),
        )
        assertSame(installed, host.state("c1"))
    }

    @Test
    fun authoritativeRebaselineIsScopedAndGenerationFencedPerScope() {
        val host = MessageDomainSyncHost()
        val c1Row = row("c1-m11", channel = "c1", seq = 11, senderId = "u1")
        val c2Row = row("c2-m21", channel = "c2", seq = 21, senderId = "u2")
        host.ingestFrame(
            SyncFrame("c1", seq = 11uL, epoch = "e1", event = MessageDomainEvent.New(c1Row, MESSAGE_NEW)),
        )
        host.ingestFrame(
            SyncFrame("c2", seq = 21uL, epoch = "e1", event = MessageDomainEvent.New(c2Row, MESSAGE_NEW)),
        )
        val c2Before = host.state("c2")
        val c2CursorBefore = host.scopeSyncState("c2")

        val c1Authoritative = baseline(state = host.state("c1"), rows = listOf(c1Row), scopeId = "c1")
        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 2))
        assertEquals(
            SyncIngestOutcome.Applied("c1", 11uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 11uL, epoch = "e1", state = c1Authoritative),
                generation = 2,
            ),
        )
        assertSame(c2Before, host.state("c2"))
        assertEquals(c2CursorBefore, host.scopeSyncState("c2"))

        val c2Authoritative = baseline(
            state = c2Before,
            rows = listOf(c2Row),
            scopeId = "c2",
            coveredFromSeq = 21,
            remoteHighWaterSeq = 21,
        )
        assertTrue(host.beginAuthoritativeRebaseline("c2", generation = 1))
        assertEquals(
            SyncIngestOutcome.Applied("c2", 21uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c2", watermark = 21uL, epoch = "e1", state = c2Authoritative),
                generation = 1,
            ),
        )
        assertTrue(project(host.state("c2"), scopeId = "c2", maxReadSeq = 20).complete)

        val c1Installed = host.state("c1")
        assertFalse(host.beginAuthoritativeRebaseline("c1", generation = 1))
        assertEquals(
            SyncIngestOutcome.DuplicateDropped("c1", 12uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 12uL, epoch = "e1", state = c1Installed),
                generation = 1,
            ),
        )
        assertSame(c1Installed, host.state("c1"))
    }

    @Test
    fun realtimeAfterSnapshotRequestIsReplayedAboveSnapshotWatermark() {
        val host = MessageDomainSyncHost()
        val m14 = row("m14", seq = 14, senderId = "u1")
        val m15 = row("m15", seq = 15, senderId = "u2")
        host.ingestFrame(
            SyncFrame("c1", seq = 14uL, epoch = "e1", event = MessageDomainEvent.New(m14, MESSAGE_NEW)),
        )
        val snapshotAt14 = baseline(state = host.state("c1"), rows = listOf(m14), coveredFromSeq = 14)

        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 2))
        host.ingestFrame(
            SyncFrame("c1", seq = 15uL, epoch = "e1", event = MessageDomainEvent.New(m15, MESSAGE_NEW)),
        )
        assertEquals(
            SyncIngestOutcome.Applied("c1", 15uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = snapshotAt14),
                generation = 2,
            ),
        )

        val finalState = host.state("c1")
        assertEquals(listOf("m14", "m15"), ids(finalState))
        assertEquals(15uL, host.scopeSyncState("c1")?.appliedSeq)
        assertTrue(project(finalState, maxReadSeq = 13).complete)
        assertEquals(2, project(finalState, maxReadSeq = 13).unreadCount)
    }

    @Test
    fun ordinaryUpdateFoldsWithoutAdvancingCreationSeqWatermark() {
        val host = MessageDomainSyncHost()
        val m10Old = row("m10", seq = 10, content = "old", senderId = "u1")
        val m14 = row("m14", seq = 14, senderId = "u2")
        host.ingestFrame(
            SyncFrame("c1", seq = 10uL, epoch = "e1", event = MessageDomainEvent.New(m10Old, MESSAGE_NEW)),
        )
        host.ingestFrame(
            SyncFrame("c1", seq = 14uL, epoch = "e1", event = MessageDomainEvent.New(m14, MESSAGE_NEW)),
        )

        assertEquals(
            SyncIngestOutcome.Applied("c1", 14uL),
            host.ingestFrame(
                SyncFrame(
                    "c1",
                    seq = 10uL,
                    epoch = "e1",
                    event = MessageDomainEvent.Updated(m10Old.copy(content = "new"), ENRICHED),
                ),
            ),
        )

        assertEquals("new", host.state("c1").channelMessages.getValue("c1").first { it.id == "m10" }.content)
        assertEquals(14uL, host.scopeSyncState("c1")?.appliedSeq)
    }

    @Test
    fun updateDuringSnapshotFlightInvalidatesCoverageAndRequiresCleanRebaseline() {
        val host = MessageDomainSyncHost()
        val m10Old = row("m10", seq = 10, content = "old", senderId = "u1")
        val m10Mid = m10Old.copy(content = "mid")
        val m10Newer = m10Old.copy(content = "newer")
        val m14 = row("m14", seq = 14, senderId = "u2")
        host.ingestFrame(
            SyncFrame("c1", seq = 10uL, epoch = "e1", event = MessageDomainEvent.New(m10Old, MESSAGE_NEW)),
        )
        host.ingestFrame(
            SyncFrame("c1", seq = 14uL, epoch = "e1", event = MessageDomainEvent.New(m14, MESSAGE_NEW)),
        )
        val snapshotWithNewer = baseline(
            state = host.state("c1"),
            rows = listOf(m10Newer, m14),
            coveredFromSeq = 10,
            remoteHighWaterSeq = 14,
        )

        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 2))
        assertEquals(
            SyncIngestOutcome.Applied("c1", 14uL),
            host.ingestFrame(
                SyncFrame(
                    "c1",
                    seq = 10uL,
                    epoch = "e1",
                    event = MessageDomainEvent.Updated(m10Mid, ENRICHED),
                ),
            ),
        )
        assertEquals(
            SyncIngestOutcome.DuplicateDropped("c1", 14uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = snapshotWithNewer),
                generation = 2,
            ),
        )

        val dirtyState = host.state("c1")
        assertEquals("mid", dirtyState.channelMessages.getValue("c1").first { it.id == "m10" }.content)
        assertFalse(project(dirtyState, maxReadSeq = 9).complete)

        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 3))
        assertEquals(
            SyncIngestOutcome.Applied("c1", 14uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = snapshotWithNewer),
                generation = 3,
            ),
        )
        val cleanState = host.state("c1")
        assertEquals("newer", cleanState.channelMessages.getValue("c1").first { it.id == "m10" }.content)
        assertTrue(project(cleanState, maxReadSeq = 9).complete)
    }

    @Test
    fun newAtOrBelowSnapshotWatermarkCannotOverwriteAuthoritativeRow() {
        val host = MessageDomainSyncHost()
        val m10Old = row("m10", seq = 10, content = "old", senderId = "u1")
        val m10Mid = m10Old.copy(content = "mid")
        val m10Newer = m10Old.copy(content = "newer")
        val m14 = row("m14", seq = 14, senderId = "u2")
        host.ingestFrame(
            SyncFrame("c1", seq = 10uL, epoch = "e1", event = MessageDomainEvent.New(m10Old, MESSAGE_NEW)),
        )
        host.ingestFrame(
            SyncFrame("c1", seq = 14uL, epoch = "e1", event = MessageDomainEvent.New(m14, MESSAGE_NEW)),
        )
        val snapshotWithNewer = baseline(
            state = host.state("c1"),
            rows = listOf(m10Newer, m14),
            coveredFromSeq = 10,
            remoteHighWaterSeq = 14,
        )

        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 1))
        assertEquals(
            SyncIngestOutcome.DuplicateDropped("c1", 10uL),
            host.ingestFrame(
                SyncFrame("c1", seq = 10uL, epoch = "e1", event = MessageDomainEvent.New(m10Mid, MESSAGE_NEW)),
            ),
        )
        assertEquals(
            SyncIngestOutcome.Applied("c1", 14uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = snapshotWithNewer),
                generation = 1,
            ),
        )

        val finalState = host.state("c1")
        assertEquals("newer", finalState.channelMessages.getValue("c1").first { it.id == "m10" }.content)
        assertTrue(project(finalState, maxReadSeq = 9).complete)
    }

    @Test
    fun snapshotOlderThanPreexistingUnretainedTailIsZeroMutation() {
        val host = MessageDomainSyncHost()
        val m14 = row("m14", seq = 14, senderId = "u1")
        val m15 = row("m15", seq = 15, senderId = "u2")
        host.ingestFrame(
            SyncFrame("c1", seq = 14uL, epoch = "e1", event = MessageDomainEvent.New(m14, MESSAGE_NEW)),
        )
        val snapshotAt14 = baseline(state = host.state("c1"), rows = listOf(m14), coveredFromSeq = 14)
        host.ingestFrame(
            SyncFrame("c1", seq = 15uL, epoch = "e1", event = MessageDomainEvent.New(m15, MESSAGE_NEW)),
        )
        val before = host.state("c1")

        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 1))
        assertEquals(
            SyncIngestOutcome.DuplicateDropped("c1", 14uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = snapshotAt14),
                generation = 1,
            ),
        )
        assertSame(before, host.state("c1"))
        assertEquals(listOf("m14", "m15"), ids(host.state("c1")))
        assertFalse(project(host.state("c1"), maxReadSeq = 13).complete)
    }

    @Test
    fun newerGenerationSupersedesPendingOlderCompletionWithoutRollback() {
        val host = MessageDomainSyncHost()
        val m14 = row("m14", seq = 14, senderId = "u1")
        host.ingestFrame(
            SyncFrame("c1", seq = 14uL, epoch = "e1", event = MessageDomainEvent.New(m14, MESSAGE_NEW)),
        )
        val snapshotAt14 = baseline(state = host.state("c1"), rows = listOf(m14), coveredFromSeq = 14)

        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 1))
        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 2))
        assertEquals(
            SyncIngestOutcome.Applied("c1", 14uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = snapshotAt14),
                generation = 2,
            ),
        )
        val installed = host.state("c1")

        assertEquals(
            SyncIngestOutcome.DuplicateDropped("c1", 14uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = snapshotAt14),
                generation = 1,
            ),
        )
        assertSame(installed, host.state("c1"))
    }

    @Test
    fun mismatchedOuterAndInnerSnapshotEnvelopeIsZeroMutation() {
        val host = MessageDomainSyncHost()
        val m14 = row("m14", seq = 14, senderId = "u1")
        host.ingestFrame(
            SyncFrame("c1", seq = 14uL, epoch = "e1", event = MessageDomainEvent.New(m14, MESSAGE_NEW)),
        )
        val before = host.state("c1")
        val valid = baseline(state = before, rows = listOf(m14), coveredFromSeq = 14)
        val wrongScope = baseline(
            rows = listOf(row("c2-m14", channel = "c2", seq = 14)),
            scopeId = "c2",
            coveredFromSeq = 14,
        )
        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 1))

        listOf(
            SyncSnapshot("c1", watermark = 15uL, epoch = "e1", state = valid),
            SyncSnapshot("c1", watermark = 14uL, epoch = "e2", state = valid),
            SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = wrongScope),
            SyncSnapshot("c2", watermark = 14uL, epoch = "e1", state = valid),
        ).forEach { invalid ->
            assertEquals(
                SyncIngestOutcome.DuplicateDropped(invalid.scopeId, invalid.watermark),
                host.ingestAuthoritativeSnapshot(invalid, generation = 1),
            )
            assertSame(before, host.state("c1"))
            assertNull(host.state("c1").scopeCoverage["c1"])
        }

        assertEquals(
            SyncIngestOutcome.Applied("c1", 14uL),
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = valid),
                generation = 1,
            ),
        )
        assertTrue(project(host.state("c1"), maxReadSeq = 13).complete)
    }

    @Test
    fun realtimeAtSnapshotSwapWaitsForSameScopeCutAndAppliesToReplacementCore() = runBlocking {
        val snapshotSeeded = CompletableDeferred<Unit>()
        val allowSnapshotSwap = CompletableDeferred<Unit>()
        val frameAtLock = CompletableDeferred<Unit>()
        val host = MessageDomainSyncHost(
            beforeSnapshotSwap = {
                runBlocking {
                    snapshotSeeded.complete(Unit)
                    allowSnapshotSwap.await()
                }
            },
            beforeFrameLock = { frame ->
                if (frame.scopeId == "c1" && frame.seq == 15uL) frameAtLock.complete(Unit)
            },
        )
        val m14 = row("m14", seq = 14, senderId = "u1")
        val m15 = row("m15", seq = 15, senderId = "u2")
        host.ingestFrame(
            SyncFrame("c1", seq = 14uL, epoch = "e1", event = MessageDomainEvent.New(m14, MESSAGE_NEW)),
        )
        val authoritative = baseline(state = host.state("c1"), rows = listOf(m14), coveredFromSeq = 14)
        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 1))

        val snapshotResult = async(Dispatchers.Default) {
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = authoritative),
                generation = 1,
            )
        }
        snapshotSeeded.await()
        val frameResult = async(Dispatchers.Default) {
            host.ingestFrame(
                SyncFrame("c1", seq = 15uL, epoch = "e1", event = MessageDomainEvent.New(m15, MESSAGE_NEW)),
            )
        }
        frameAtLock.await()
        assertFalse(frameResult.isCompleted)

        allowSnapshotSwap.complete(Unit)
        assertEquals(SyncIngestOutcome.Applied("c1", 14uL), snapshotResult.await())
        assertEquals(SyncIngestOutcome.MaxAdvanced("c1", 15uL), frameResult.await())

        val finalState = host.state("c1")
        assertEquals(listOf("m14", "m15"), ids(finalState))
        assertTrue(project(finalState, maxReadSeq = 13).complete)
        assertEquals(2, project(finalState, maxReadSeq = 13).unreadCount)
    }

    @Test
    fun updateWaitingAtSnapshotSwapFoldsIntoReplacementWithoutAdvancingWatermark() = runBlocking {
        val snapshotSeeded = CompletableDeferred<Unit>()
        val allowSnapshotSwap = CompletableDeferred<Unit>()
        val frameAtLock = CompletableDeferred<Unit>()
        val host = MessageDomainSyncHost(
            beforeSnapshotSwap = {
                runBlocking {
                    snapshotSeeded.complete(Unit)
                    allowSnapshotSwap.await()
                }
            },
            beforeFrameLock = { frame ->
                if (frame.scopeId == "c1" && frame.event is MessageDomainEvent.Updated) {
                    frameAtLock.complete(Unit)
                }
            },
        )
        val m10Old = row("m10", seq = 10, content = "old", senderId = "u1")
        val m14 = row("m14", seq = 14, senderId = "u2")
        host.ingestFrame(
            SyncFrame("c1", seq = 10uL, epoch = "e1", event = MessageDomainEvent.New(m10Old, MESSAGE_NEW)),
        )
        host.ingestFrame(
            SyncFrame("c1", seq = 14uL, epoch = "e1", event = MessageDomainEvent.New(m14, MESSAGE_NEW)),
        )
        val authoritative = baseline(
            state = host.state("c1"),
            rows = listOf(m10Old, m14),
            coveredFromSeq = 10,
            remoteHighWaterSeq = 14,
        )
        assertTrue(host.beginAuthoritativeRebaseline("c1", generation = 1))

        val snapshotResult = async(Dispatchers.Default) {
            host.ingestAuthoritativeSnapshot(
                SyncSnapshot("c1", watermark = 14uL, epoch = "e1", state = authoritative),
                generation = 1,
            )
        }
        snapshotSeeded.await()
        val updateResult = async(Dispatchers.Default) {
            host.ingestFrame(
                SyncFrame(
                    "c1",
                    seq = 10uL,
                    epoch = "e1",
                    event = MessageDomainEvent.Updated(m10Old.copy(content = "new"), ENRICHED),
                ),
            )
        }
        frameAtLock.await()
        assertFalse(updateResult.isCompleted)

        allowSnapshotSwap.complete(Unit)
        assertEquals(SyncIngestOutcome.Applied("c1", 14uL), snapshotResult.await())
        assertEquals(SyncIngestOutcome.Applied("c1", 14uL), updateResult.await())

        val finalState = host.state("c1")
        assertEquals("new", finalState.channelMessages.getValue("c1").first { it.id == "m10" }.content)
        assertEquals(14uL, host.scopeSyncState("c1")?.appliedSeq)
        assertTrue(project(finalState, maxReadSeq = 9).complete)
    }
}
