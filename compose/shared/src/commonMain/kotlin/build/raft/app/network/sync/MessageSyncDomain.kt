package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField

/**
 * #491 A-minimal T-message-fold — the KMP messages-domain fold, a behavioral mirror of the web
 * `messageSyncDomain.ts` (slock #4412/#4560). Round-2 is WIRE-SHAPED: the canonical row is built from
 * the RAW producer wire/envelope (never reverse-derived from the lossy app `Message` projection), and
 * its exact field set + types + mergePolicy + presence are pinned by the machine-readable
 * `canonicalMessageManifest.json` v4 (slock #4589, producerRegistryAnchor slock#4593). Pure + total.
 *
 * ## Contract (manifest v4, all tonight's rulings mechanized)
 *
 *   - **19 canonical fields** = 10 CanonicalRequired (`overwrite`) + 9 OptionalAggregate. NO task*
 *     (task-domain, ingress dual-dispatch), NO updatedAt (emittedUnconsumed), NO commentCount
 *     (viewer overlay), NO internal storage cols (sealed server-side). All wire-shaped raw types.
 *   - **mergePolicy** per field: `overwrite` (CanonicalRequired) / `present-overwrite`
 *     (OptionalAggregate: present→overwrite incl `[]`=clear, absent→preserve) / `shared-null-preserve`
 *     (commentRef only: shared-broadcast null = privacy-strip = ALWAYS preserve; a trusted clear can
 *     only arrive on a receiver-private surface, never the shared fold).
 *   - **Presence is EXPLICIT** ([MessageDomainEvent.presentAggregates]) — supplied by the ingress from
 *     raw-JSON key presence, NEVER inferred from a decoded value. The fold is face-agnostic: it merges
 *     by the presence set, so the 3 production faces (messageNew / enrichedUpdated / taskStatusUpdated)
 *     are just different presence sets, not fold branches.
 *
 * MemberProfile / MessageType enum / metadata map / ForwardMessageBundle / TaskSummary are all derived
 * at the PROJECTION layer (row → app `Message`), not stored here.
 *
 * The PURE DATA contract types ([CanonicalMessageRow] + its nested wire DTOs, [MessageAggregateField])
 * live in the neutral `build.raft.app.canonical.message` package (#607); the fold behavior stays here.
 */
const val MESSAGES_SYNC_DOMAIN: SyncDomainName = "messages"
const val SYNC_CORE_MESSAGES_FLAG_KEY: String = "sync_core_messages_v0"

sealed interface MessageDomainEvent {
    val row: CanonicalMessageRow

    /** Which OptionalAggregate fields were present on the wire frame (raw-JSON presence, not value). */
    val presentAggregates: Set<MessageAggregateField>

    /** A new message fact (append, or merge-by-id if already present). presentAggregates REQUIRED. */
    data class New(
        override val row: CanonicalMessageRow,
        override val presentAggregates: Set<MessageAggregateField>,
    ) : MessageDomainEvent

    /** A full-row update to an existing message (merge by id). presentAggregates REQUIRED. */
    data class Updated(
        override val row: CanonicalMessageRow,
        override val presentAggregates: Set<MessageAggregateField>,
    ) : MessageDomainEvent

    companion object {
        /** messageNew production face: every OptionalAggregate present. */
        val PRESENT_MESSAGE_NEW: Set<MessageAggregateField> = MessageAggregateField.entries.toSet()

        /** enrichedUpdated face (getMessageContext overload): all present EXCEPT conversationContext (newOnly). */
        val PRESENT_ENRICHED_UPDATED: Set<MessageAggregateField> =
            PRESENT_MESSAGE_NEW - MessageAggregateField.ConversationContext

        /** taskStatusUpdated face (bare projector): only senderName + actionMetadata present. */
        val PRESENT_TASK_STATUS_UPDATED: Set<MessageAggregateField> =
            setOf(MessageAggregateField.SenderName, MessageAggregateField.ActionMetadata)
    }
}

/** channelId -> the channel's messages, kept in canonical (seq, id) order. */
data class MessageDomainState(
    val channelMessages: Map<String, List<CanonicalMessageRow>> = emptyMap(),
    /**
     * Authoritative tail coverage for each channel. This lives in the same state value as the rows so
     * snapshot persistence cannot restore one without the other. A missing entry means rows may still
     * be useful as a sparse cache, but they cannot prove a read-state projection complete.
     */
    val scopeCoverage: Map<String, MessageScopeCoverage> = emptyMap(),
)

/**
 * Proof that one receiver-scoped message tail is covered. [coveredFromSeq] is the first sequence the
 * authoritative hydrate promises to cover; [coveredThroughSeq] is the end of that continuous window.
 * It is deliberately separate from the rows because deleted/non-projectable messages may leave legal
 * sequence holes in the materialized row list. [hasGap] records an observed break in that promise.
 */
data class MessageScopeCoverage(
    val serverId: String,
    val memberId: String,
    val scopeId: String,
    val epoch: String?,
    val coveredFromSeq: Long,
    val coveredThroughSeq: Long,
    val remoteHighWaterSeq: Long,
    val hasGap: Boolean,
    val completeThroughLatest: Boolean,
)

/** The sole authoritative entry point for a conversation hydrate/rebaseline. */
data class MessageScopeCoverageSnapshot(
    val serverId: String,
    val memberId: String,
    val scopeId: String,
    val epoch: String?,
    val rows: List<CanonicalMessageRow>,
    val coveredFromSeq: Long,
    val remoteHighWaterSeq: Long,
    val hasGap: Boolean = false,
    val hasNewer: Boolean = false,
)

data class MessageReadStateCheckpoint(
    val readStateVersion: Long,
    val maxReadSeq: Long,
)

data class MessageReadStateProjection(
    val readStateVersion: Long,
    val maxReadSeq: Long,
    val unreadCount: Int,
    val hasMention: Boolean,
    val firstUnreadMessageId: String?,
    val firstMentionMessageId: String?,
    val complete: Boolean,
)

fun createInitialMessageDomainState(): MessageDomainState = MessageDomainState()

/**
 * mergePolicy-based full-row merge. CanonicalRequired = overwrite from [incoming]. Each
 * OptionalAggregate = `present-overwrite` (overwrite iff present, else preserve), EXCEPT [commentRef]
 * = `shared-null-preserve`: a present-but-null commentRef on the shared fold is a privacy-strip and
 * must PRESERVE (only a receiver-private surface — never this shared fold — may clear it). Returns
 * [existing] unchanged (identity) when nothing differs.
 */
fun mergeCanonicalRow(
    existing: CanonicalMessageRow,
    incoming: CanonicalMessageRow,
    present: Set<MessageAggregateField>,
): CanonicalMessageRow {
    fun <T> gate(field: MessageAggregateField, inc: T, exist: T): T = if (field in present) inc else exist
    val merged = incoming.copy(
        senderName = gate(MessageAggregateField.SenderName, incoming.senderName, existing.senderName),
        senderDescription = gate(MessageAggregateField.SenderDescription, incoming.senderDescription, existing.senderDescription),
        senderMembershipStatus = gate(MessageAggregateField.SenderMembershipStatus, incoming.senderMembershipStatus, existing.senderMembershipStatus),
        mentions = gate(MessageAggregateField.Mentions, incoming.mentions, existing.mentions),
        attachments = gate(MessageAggregateField.Attachments, incoming.attachments, existing.attachments),
        reactions = gate(MessageAggregateField.Reactions, incoming.reactions, existing.reactions),
        actionMetadata = gate(MessageAggregateField.ActionMetadata, incoming.actionMetadata, existing.actionMetadata),
        conversationContext = gate(MessageAggregateField.ConversationContext, incoming.conversationContext, existing.conversationContext),
        // commentRef: shared-null-preserve — present AND non-null overwrites; present-null (privacy
        // strip) OR absent both preserve. Never cleared on the shared fold.
        commentRef =
            if (MessageAggregateField.CommentRef in present && incoming.commentRef != null) incoming.commentRef
            else existing.commentRef,
    )
    return if (merged == existing) existing else merged
}

/** Absent-aggregate values on a brand-new row (no existing to preserve) collapse to empty/null. */
private fun CanonicalMessageRow.withAbsentAggregatesCleared(present: Set<MessageAggregateField>): CanonicalMessageRow =
    copy(
        senderName = if (MessageAggregateField.SenderName in present) senderName else "",
        senderDescription = if (MessageAggregateField.SenderDescription in present) senderDescription else null,
        senderMembershipStatus = if (MessageAggregateField.SenderMembershipStatus in present) senderMembershipStatus else null,
        mentions = if (MessageAggregateField.Mentions in present) mentions else emptyList(),
        attachments = if (MessageAggregateField.Attachments in present) attachments else emptyList(),
        reactions = if (MessageAggregateField.Reactions in present) reactions else emptyList(),
        actionMetadata = if (MessageAggregateField.ActionMetadata in present) actionMetadata else null,
        conversationContext = if (MessageAggregateField.ConversationContext in present) conversationContext else null,
        commentRef = if (MessageAggregateField.CommentRef in present) commentRef else null,
    )

/** Server-backed order: seq orders, ties break by id. (All canonical rows have a non-null seq.) */
private fun List<CanonicalMessageRow>.sortedByCanonicalOrder(): List<CanonicalMessageRow> =
    sortedWith(compareBy({ it.seq }, { it.id }))

private fun MessageDomainState.withBucket(channelId: String, bucket: List<CanonicalMessageRow>): MessageDomainState =
    copy(channelMessages = channelMessages + (channelId to bucket))

private fun MessageDomainState.withCoverageAfterRealtimeNew(row: CanonicalMessageRow): MessageDomainState {
    val coverage = scopeCoverage[row.channelId] ?: return this
    if (row.seq <= coverage.coveredThroughSeq) return this

    val nextCoverage =
        if (
            coverage.completeThroughLatest &&
            !coverage.hasGap &&
            row.seq == coverage.coveredThroughSeq + 1L
        ) {
            coverage.copy(
                coveredThroughSeq = row.seq,
                remoteHighWaterSeq = maxOf(coverage.remoteHighWaterSeq, row.seq),
            )
        } else {
            coverage.copy(
                remoteHighWaterSeq = maxOf(coverage.remoteHighWaterSeq, row.seq),
                hasGap = coverage.hasGap || row.seq > coverage.coveredThroughSeq + 1L,
                completeThroughLatest = false,
            )
        }
    return if (nextCoverage == coverage) this else copy(scopeCoverage = scopeCoverage + (row.channelId to nextCoverage))
}

private fun applyMessageNew(
    state: MessageDomainState,
    row: CanonicalMessageRow,
    present: Set<MessageAggregateField>,
): MessageDomainState {
    val channelId = row.channelId
    val bucket = state.channelMessages[channelId] ?: emptyList()
    val existingIndex = bucket.indexOfFirst { it.id == row.id }
    if (existingIndex >= 0) {
        val merged = mergeCanonicalRow(bucket[existingIndex], row, present)
        if (merged === bucket[existingIndex]) return state
        return state.withBucket(channelId, bucket.mapIndexed { i, r -> if (i == existingIndex) merged else r })
    }
    return state
        .withBucket(channelId, (bucket + row.withAbsentAggregatesCleared(present)).sortedByCanonicalOrder())
        .withCoverageAfterRealtimeNew(row)
}

private fun applyMessageUpdated(
    state: MessageDomainState,
    row: CanonicalMessageRow,
    present: Set<MessageAggregateField>,
): MessageDomainState {
    val channelId = row.channelId
    val bucket = state.channelMessages[channelId] ?: return state
    var changed = false
    val mergedBucket = bucket.map { r ->
        if (r.id != row.id) return@map r
        val merged = mergeCanonicalRow(r, row, present)
        if (merged !== r) changed = true
        merged
    }
    return if (!changed) state else state.withBucket(channelId, mergedBucket)
}

fun applyMessageDomainEvent(state: MessageDomainState, event: MessageDomainEvent): MessageDomainState =
    when (event) {
        is MessageDomainEvent.New -> applyMessageNew(state, event.row, event.presentAggregates)
        is MessageDomainEvent.Updated -> applyMessageUpdated(state, event.row, event.presentAggregates)
    }

/**
 * Atomically replace one scope's canonical rows and coverage from an authoritative conversation
 * hydrate/rebaseline. Invalid snapshots clear that scope instead of retaining a misleading partial
 * proof. Realtime folds never call this function and therefore cannot manufacture initial coverage.
 */
fun rebaselineMessageScope(
    state: MessageDomainState,
    snapshot: MessageScopeCoverageSnapshot,
): MessageDomainState {
    val sortedRows = snapshot.rows.sortedByCanonicalOrder()
    val validIdentity =
        snapshot.serverId.isNotBlank() && snapshot.memberId.isNotBlank() && snapshot.scopeId.isNotBlank()
    val validBounds =
        snapshot.coveredFromSeq >= 0L &&
            snapshot.remoteHighWaterSeq >= 0L &&
            (
                snapshot.coveredFromSeq <= snapshot.remoteHighWaterSeq ||
                    snapshot.coveredFromSeq == snapshot.remoteHighWaterSeq.saturatedIncrement()
                )
    val rowsValid =
        sortedRows.all {
            it.channelId == snapshot.scopeId &&
                it.seq >= snapshot.coveredFromSeq &&
                it.seq <= snapshot.remoteHighWaterSeq
        } && sortedRows.map { it.id }.distinct().size == sortedRows.size

    if (!validIdentity || !validBounds || !rowsValid) {
        return state.copy(
            channelMessages = state.channelMessages - snapshot.scopeId,
            scopeCoverage = state.scopeCoverage - snapshot.scopeId,
        )
    }

    val completeThroughLatest = !snapshot.hasGap && !snapshot.hasNewer
    val coveredThroughSeq =
        if (completeThroughLatest) snapshot.remoteHighWaterSeq
        else sortedRows.maxOfOrNull { it.seq } ?: snapshot.coveredFromSeq - 1L
    val coverage =
        MessageScopeCoverage(
            serverId = snapshot.serverId,
            memberId = snapshot.memberId,
            scopeId = snapshot.scopeId,
            epoch = snapshot.epoch,
            coveredFromSeq = snapshot.coveredFromSeq,
            coveredThroughSeq = coveredThroughSeq,
            remoteHighWaterSeq = snapshot.remoteHighWaterSeq,
            hasGap = snapshot.hasGap,
            completeThroughLatest = completeThroughLatest,
        )
    return state.copy(
        channelMessages = state.channelMessages + (snapshot.scopeId to sortedRows),
        scopeCoverage = state.scopeCoverage + (snapshot.scopeId to coverage),
    )
}

/**
 * Drop only the proof when paging/gap repair fails, a cache window is evicted, or receiver identity
 * changes. Sparse rows may remain useful for rendering, but projection stays fail-closed until the
 * next authoritative [rebaselineMessageScope].
 */
fun invalidateMessageScopeCoverage(state: MessageDomainState, scopeId: String): MessageDomainState =
    if (scopeId !in state.scopeCoverage) state
    else state.copy(scopeCoverage = state.scopeCoverage - scopeId)

private fun incompleteReadStateProjection(checkpoint: MessageReadStateCheckpoint): MessageReadStateProjection =
    MessageReadStateProjection(
        readStateVersion = checkpoint.readStateVersion,
        maxReadSeq = checkpoint.maxReadSeq,
        unreadCount = 0,
        hasMention = false,
        firstUnreadMessageId = null,
        firstMentionMessageId = null,
        complete = false,
    )

/**
 * Pure Web-parity projection from an already accepted receiver read-state fact. It fails closed unless
 * the identity/epoch match and coverage proves the entire tail after [MessageReadStateCheckpoint.maxReadSeq].
 * A higher read-state version may legally move the cursor backwards; this function never max-merges it.
 */
fun applyReadStateProjection(
    state: MessageDomainState,
    serverId: String,
    memberId: String,
    scopeId: String,
    epoch: String?,
    checkpoint: MessageReadStateCheckpoint,
): MessageReadStateProjection {
    if (checkpoint.readStateVersion <= 0L || checkpoint.maxReadSeq < 0L) {
        return incompleteReadStateProjection(checkpoint)
    }
    val coverage = state.scopeCoverage[scopeId] ?: return incompleteReadStateProjection(checkpoint)
    val identityMatches =
        coverage.serverId == serverId &&
            coverage.memberId == memberId &&
            coverage.scopeId == scopeId &&
            coverage.epoch == epoch
    val coversUnreadTail =
        coverage.completeThroughLatest &&
            !coverage.hasGap &&
            coverage.coveredThroughSeq >= coverage.remoteHighWaterSeq &&
            coverage.coveredFromSeq <= checkpoint.maxReadSeq.saturatedIncrement()
    if (!identityMatches || !coversUnreadTail) return incompleteReadStateProjection(checkpoint)

    var unreadCount = 0
    var hasMention = false
    var firstUnreadMessageId: String? = null
    var firstMentionMessageId: String? = null
    state.channelMessages[scopeId].orEmpty().forEach { row ->
        val selfAuthored = row.senderType == "user" && row.senderId == memberId
        if (row.seq <= checkpoint.maxReadSeq || selfAuthored) return@forEach
        unreadCount += 1
        if (firstUnreadMessageId == null) firstUnreadMessageId = row.id
        val mentionsMember = row.mentions.any { it.type == "user" && it.id == memberId }
        if (mentionsMember) {
            hasMention = true
            if (firstMentionMessageId == null) firstMentionMessageId = row.id
        }
    }
    return MessageReadStateProjection(
        readStateVersion = checkpoint.readStateVersion,
        maxReadSeq = checkpoint.maxReadSeq,
        unreadCount = unreadCount,
        hasMention = hasMention,
        firstUnreadMessageId = firstUnreadMessageId,
        firstMentionMessageId = firstMentionMessageId,
        complete = true,
    )
}

private fun Long.saturatedIncrement(): Long = if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L

private fun restoreMessageDomainSnapshot(snapshot: SyncSnapshot): MessageDomainState {
    val restored = snapshot.state as? MessageDomainState ?: return createInitialMessageDomainState()
    val coverage = restored.scopeCoverage[snapshot.scopeId] ?: return restored
    if (coverage.scopeId == snapshot.scopeId && coverage.epoch == snapshot.epoch) return restored
    return restored.copy(
        channelMessages = restored.channelMessages - snapshot.scopeId,
        scopeCoverage = restored.scopeCoverage - snapshot.scopeId,
    )
}

/**
 * The registered messages domain. `density = Sparse` is the compat slice: production delivery still
 * owns gap repair. Unknown event shapes fold to the unchanged state (pure + total).
 */
fun messagesSyncDomain(): SyncDomainConfig =
    SyncDomainConfig(
        name = MESSAGES_SYNC_DOMAIN,
        density = SyncDensity.Sparse,
        initialState = { createInitialMessageDomainState() },
        fold = { state, event, _ ->
            val current = state as? MessageDomainState ?: createInitialMessageDomainState()
            when (val domainEvent = event as? MessageDomainEvent) {
                null -> current // pure + total: unknown event shapes fold to the unchanged state
                else -> applyMessageDomainEvent(current, domainEvent)
            }
        },
        fromSnapshot = ::restoreMessageDomainSnapshot,
    )
