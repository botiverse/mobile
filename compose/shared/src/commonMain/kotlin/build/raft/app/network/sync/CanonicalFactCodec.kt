package build.raft.app.network.sync

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Versioned codec for the T-wrapper canonical fact BLOB (`messageEntity.canonicalFactJson`, form A).
 *
 * This is the restore/continuation SOLE source of truth. It is a DEDICATED versioned envelope
 * ([CanonicalMessagePersistedV1]) — NOT a bare serialization of the fold domain [CanonicalMessageRow]
 * (which carries value-class ids + a raw JsonElement). Decoupling is the point: a fold-side field rename
 * cannot silently reshape stored bytes; the mapping functions would fail to compile, and the golden-bytes
 * pin ([CanonicalFactCodecTest]) fails on any write drift while the `version` field guards read-old.
 *
 * The codec stores RESOLVED post-state only, never presence (§5.3): presence (absent) is consumed in the
 * fold, so a resolved row has only nullable null/value + list empty/non-empty. `explicitNulls = true`
 * keeps a resolved null as `null` and an empty aggregate as `[]` — the KMP `explicitNulls = false` /
 * non-null-default trap that flattens null→[] is exactly what byte-faithful round-trip forbids.
 */

private const val CANONICAL_FACT_VERSION_V1 = 1

/** The byte-stable codec Json. Do NOT change without re-pinning the golden fixture. */
internal val canonicalFactCodecJson: Json = Json {
    explicitNulls = true // resolved null must serialize as null (not be dropped) — three-state byte-faithful
    encodeDefaults = true // emit every field incl. version + empty lists — byte-stable for the golden pin
    prettyPrint = false
}

@Serializable
data class CanonicalMessagePersistedV1(
    val version: Int = CANONICAL_FACT_VERSION_V1,
    // -- CanonicalRequired --
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
    // -- OptionalAggregate (resolved values only) --
    val senderName: String,
    val senderDescription: String? = null,
    val senderMembershipStatus: String? = null,
    val mentions: List<CanonicalMentionV1> = emptyList(),
    val attachments: List<CanonicalAttachmentV1> = emptyList(),
    val reactions: List<CanonicalReactionV1> = emptyList(),
    val commentRef: CanonicalCommentRefV1? = null,
    val actionMetadata: JsonElement? = null,
    val conversationContext: CanonicalConversationContextV1? = null,
)

@Serializable
data class CanonicalMentionV1(val id: String, val name: String, val type: String)

@Serializable
data class CanonicalAttachmentV1(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
data class CanonicalReactionV1(
    val emoji: String,
    val count: Int,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList(),
)

@Serializable
data class CanonicalCommentHostSourceV1(
    val type: String,
    val routeKind: String,
    val channelId: String,
    val parentMessageId: String? = null,
    val threadChannelId: String? = null,
    val rootThreadChannelId: String? = null,
)

@Serializable
data class CanonicalCommentRefV1(
    val attachmentId: String,
    val filename: String,
    val hostMessageId: String? = null,
    val hostSource: CanonicalCommentHostSourceV1? = null,
    val anchorLabel: String? = null,
    val anchorQuote: String? = null,
)

@Serializable
data class CanonicalConversationContextV1(
    val channelType: String,
    val parentChannelId: String? = null,
    val parentChannelType: String? = null,
    val parentMessageId: String? = null,
)

// ── domain <-> envelope (total field mapping; a missing field fails to compile — the drift guard) ──

fun CanonicalMessageRow.toPersistedV1(): CanonicalMessagePersistedV1 = CanonicalMessagePersistedV1(
    id = id.value,
    channelId = channelId.value,
    seq = seq,
    senderType = senderType,
    senderId = senderId,
    content = content,
    messageType = messageType,
    createdAt = createdAt,
    randomId = randomId,
    threadId = threadId,
    senderName = senderName,
    senderDescription = senderDescription,
    senderMembershipStatus = senderMembershipStatus,
    mentions = mentions.map { CanonicalMentionV1(it.id, it.name, it.type) },
    attachments = attachments.map {
        CanonicalAttachmentV1(it.id, it.filename, it.mimeType, it.sizeBytes, it.width, it.height, it.thumbnailUrl)
    },
    reactions = reactions.map { CanonicalReactionV1(it.emoji, it.count, it.reactorIds, it.reactorNames) },
    commentRef = commentRef?.let { ref ->
        CanonicalCommentRefV1(
            attachmentId = ref.attachmentId,
            filename = ref.filename,
            hostMessageId = ref.hostMessageId,
            hostSource = ref.hostSource?.let {
                CanonicalCommentHostSourceV1(it.type, it.routeKind, it.channelId, it.parentMessageId, it.threadChannelId, it.rootThreadChannelId)
            },
            anchorLabel = ref.anchorLabel,
            anchorQuote = ref.anchorQuote,
        )
    },
    actionMetadata = actionMetadata,
    conversationContext = conversationContext?.let {
        CanonicalConversationContextV1(it.channelType, it.parentChannelId, it.parentChannelType, it.parentMessageId)
    },
)

fun CanonicalMessagePersistedV1.toCanonicalRow(): CanonicalMessageRow = CanonicalMessageRow(
    id = MessageId(id),
    channelId = ChannelId(channelId),
    seq = seq,
    senderType = senderType,
    senderId = senderId,
    content = content,
    messageType = messageType,
    createdAt = createdAt,
    randomId = randomId,
    threadId = threadId,
    senderName = senderName,
    senderDescription = senderDescription,
    senderMembershipStatus = senderMembershipStatus,
    mentions = mentions.map { CanonicalMentionWire(it.id, it.name, it.type) },
    attachments = attachments.map {
        CanonicalAttachmentWire(it.id, it.filename, it.mimeType, it.sizeBytes, it.width, it.height, it.thumbnailUrl)
    },
    reactions = reactions.map { CanonicalReactionWire(it.emoji, it.count, it.reactorIds, it.reactorNames) },
    commentRef = commentRef?.let { ref ->
        CanonicalCommentRefWire(
            attachmentId = ref.attachmentId,
            filename = ref.filename,
            hostMessageId = ref.hostMessageId,
            hostSource = ref.hostSource?.let {
                CanonicalCommentHostSourceWire(it.type, it.routeKind, it.channelId, it.parentMessageId, it.threadChannelId, it.rootThreadChannelId)
            },
            anchorLabel = ref.anchorLabel,
            anchorQuote = ref.anchorQuote,
        )
    },
    actionMetadata = actionMetadata,
    conversationContext = conversationContext?.let {
        CanonicalConversationContextWire(it.channelType, it.parentChannelId, it.parentChannelType, it.parentMessageId)
    },
)

/** Encode the resolved canonical fact to its stored BLOB string. Byte-stable (golden-pinned). */
fun encodeCanonicalFact(row: CanonicalMessageRow): String =
    canonicalFactCodecJson.encodeToString(row.toPersistedV1())

/**
 * Decode a stored BLOB back to the canonical row. FAIL-CLOSED: a malformed/unknown-version payload returns
 * null (the restore path treats that as a per-row fail-closed input to the poison gate, never a silent
 * drop). Restore reads ONLY this payload — never the projected columns (projection-tamper immune).
 */
fun decodeCanonicalFact(text: String): CanonicalMessageRow? =
    try {
        val envelope = canonicalFactCodecJson.decodeFromString<CanonicalMessagePersistedV1>(text)
        if (envelope.version != CANONICAL_FACT_VERSION_V1) null else envelope.toCanonicalRow()
    } catch (_: SerializationException) {
        null
    }
