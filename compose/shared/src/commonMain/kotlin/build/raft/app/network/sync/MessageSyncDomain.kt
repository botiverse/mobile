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
    return state.withBucket(channelId, (bucket + row.withAbsentAggregatesCleared(present)).sortedByCanonicalOrder())
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
        fromSnapshot = { snapshot -> snapshot.state as? MessageDomainState ?: createInitialMessageDomainState() },
    )
