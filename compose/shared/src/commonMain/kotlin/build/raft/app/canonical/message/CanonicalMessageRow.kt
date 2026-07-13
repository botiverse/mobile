/*
 * #607 neutral canonical-message contract — PURE DATA only. This package is the compile-time seam
 * between the realtime ingress (`data.realtime`), the domain events (`model`) and the sync-core fold
 * (`network.sync`): all three may depend on it, it depends on nothing but kotlinx JSON types. The seam
 * is ONE-WAY (`model → canonical.message` only, never back): ids are raw wire strings here and are
 * wrapped into the `model` value classes only at the projection/fold-to-app boundaries. NO feature
 * flags, loggers, stores, or behavior live here — decode/compare/fold stay in `network.sync`.
 */
package build.raft.app.canonical.message

import kotlinx.serialization.json.JsonElement

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
    val id: String,
    val channelId: String,
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
