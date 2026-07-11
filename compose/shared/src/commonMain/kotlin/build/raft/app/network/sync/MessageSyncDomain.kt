package build.raft.app.network.sync

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.serialization.json.JsonElement

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
 */
const val MESSAGES_SYNC_DOMAIN: SyncDomainName = "messages"
const val SYNC_CORE_MESSAGES_FLAG_KEY: String = "sync_core_messages_v0"

// ── Wire-shaped nested DTOs (manifest v4 nestedWireShapes; raw types, no app-domain projection) ──

/** attachment (7) — raw wire; sizeBytes NOT formatted sizeLabel; NO commentCount (viewer overlay). */
data class CanonicalAttachmentWire(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
)

/** commentRef host source (6) — raw wire; type/routeKind are string-union-raw. */
data class CanonicalCommentHostSourceWire(
    val type: String,
    val routeKind: String,
    val channelId: String,
    val parentMessageId: String? = null,
    val threadChannelId: String? = null,
    val rootThreadChannelId: String? = null,
)

/** commentRef (6) — raw wire; includes anchorQuote (app MessageCommentRef lacks it). */
data class CanonicalCommentRefWire(
    val attachmentId: String,
    val filename: String,
    val hostMessageId: String? = null,
    val hostSource: CanonicalCommentHostSourceWire? = null,
    val anchorLabel: String? = null,
    val anchorQuote: String? = null,
)

/** conversationContext (4) — raw string-union kind values, NOT parsed enums (unknown-preserving). */
data class CanonicalConversationContextWire(
    val channelType: String,
    val parentChannelId: String? = null,
    val parentChannelType: String? = null,
    val parentMessageId: String? = null,
)

/** mention (3) — raw wire; type is string-union-raw. */
data class CanonicalMentionWire(
    val id: String,
    val name: String,
    val type: String,
)

/**
 * reaction (4) — raw wire. A dedicated wire DTO (NOT the app-domain `MessageReaction`): the canonical
 * fold row must stay wire-shaped so no UI-convenience field can silently change the persisted canonical
 * shape (LiBai §6.3). Field-identical to the manifest `nestedWireShapes.reaction`.
 */
data class CanonicalReactionWire(
    val emoji: String,
    val count: Int,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList(),
)

/**
 * The OptionalAggregate fields (manifest v4 `optionalAggregate`). The incoming event names which of
 * these were present on the wire frame (raw-JSON presence). Merge honors the presence set + each
 * field's mergePolicy; a bare task-status frame omits all but [SenderName]/[ActionMetadata].
 */
enum class MessageAggregateField {
    SenderName, SenderDescription, SenderMembershipStatus, Mentions, Attachments, Reactions, CommentRef,
    ActionMetadata, ConversationContext,
}

/**
 * The canonical fold row — the WIRE canonical shape (manifest v4). Both web and KMP build this from
 * the same socket frame; each projects to its own domain type afterward. Raw types throughout.
 *
 * CanonicalRequired (10) — 8 non-null + randomId/threadId nullable; threadId is the WIRE anchor
 * (raw, ≠ app nav ThreadId); createdAt is raw (≠ collapsed timestampLabel); messageType is a raw
 * string-union (≠ MessageType enum which maps unknown→Chat).
 * OptionalAggregate (9) — actionMetadata is raw structured JSON (unknown-key-preserving).
 */
data class CanonicalMessageRow(
    // -- CanonicalRequired (mergePolicy = overwrite) --
    val id: MessageId,
    val channelId: ChannelId,
    val seq: Long,
    val senderType: String,
    val senderId: String,
    val content: String,
    val messageType: String,
    val createdAt: String,
    val randomId: String? = null,
    val threadId: String? = null,
    // -- OptionalAggregate --
    val senderName: String,
    val senderDescription: String? = null,
    val senderMembershipStatus: String? = null,
    val mentions: List<CanonicalMentionWire> = emptyList(),
    val attachments: List<CanonicalAttachmentWire> = emptyList(),
    val reactions: List<CanonicalReactionWire> = emptyList(),
    val commentRef: CanonicalCommentRefWire? = null,
    val actionMetadata: JsonElement? = null,
    val conversationContext: CanonicalConversationContextWire? = null,
)

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
    sortedWith(compareBy({ it.seq }, { it.id.value }))

private fun MessageDomainState.withBucket(channelId: String, bucket: List<CanonicalMessageRow>): MessageDomainState =
    copy(channelMessages = channelMessages + (channelId to bucket))

private fun applyMessageNew(
    state: MessageDomainState,
    row: CanonicalMessageRow,
    present: Set<MessageAggregateField>,
): MessageDomainState {
    val channelId = row.channelId.value
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
    val channelId = row.channelId.value
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
