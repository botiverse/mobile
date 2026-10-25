@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package build.raft.app.network.sync

import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Full normalized-message V2 discriminator. Schema v5 remains the reaction-only tripwire. */
const val CANONICAL_MESSAGE_FULL_V2_SCHEMA_VERSION: Int = 6
const val CANONICAL_MESSAGE_FULL_V2_ENVELOPE_KIND: String = "normalized-message-v2"
const val CANONICAL_MESSAGE_FULL_V2_MANIFEST_RESOURCE: String = "sync/canonicalMessageManifest.v6.json"
const val CANONICAL_MESSAGE_V2_UNSUPPORTED_ACTION_METADATA_KIND: String = "unsupported_action_metadata_kind"
const val CANONICAL_MESSAGE_V2_CONTEXT_MEMBERSHIP_MISMATCH: String = "context_reply_membership_mismatch"
const val CANONICAL_MESSAGE_V2_BASELINE_REQUIRED: String = "v2_baseline_required"

/**
 * Web `normalizeLegacyRetraction` (slock `canonicalMessageV2.ts`): the producer row's `deletedAt`
 * (ISO string; `null` = live; absent on the task-status projector) as the canonical retraction
 * value — a non-empty, trimmed, parseable timestamp — else null (the V2 fact omits the key).
 */
fun normalizeCanonicalRetraction(deletedAt: String?): String? =
    deletedAt?.takeIf {
        it.isNotEmpty() && it == it.trim() && parseRaftTimestampEpochMillisOrNull(it) != null
    }

/** Durable generation identity. V2 never seeds from or writes the V1 consumer partition. */
const val SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID: String = "sync-core.messages.v2"

/** A server-qualified message identity used by typed discussion relations. */
@Serializable
data class MessageRefV2(
    val serverId: String,
    val messageId: String,
)

/** A server-qualified reply/thread scope identity. */
@Serializable
data class ReplyScopeRefV2(
    val serverId: String,
    val scopeId: String,
)

/** Durable Message x Replies membership carried by each reply child. */
@Serializable
data class ReplyMembershipV2(
    val root: MessageRefV2,
    val parentScope: ReplyScopeRefV2,
)

/** Lossless typed replacement for the producer's top-level channel/private/dm/joint union. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface TopLevelScopeRefV2 {
    val serverId: String
    val scopeId: String

    @Serializable
    @SerialName("channel")
    data class Channel(
        override val serverId: String,
        override val scopeId: String,
    ) : TopLevelScopeRefV2

    @Serializable
    @SerialName("private")
    data class Private(
        override val serverId: String,
        override val scopeId: String,
    ) : TopLevelScopeRefV2

    @Serializable
    @SerialName("dm")
    data class DirectMessage(
        override val serverId: String,
        override val scopeId: String,
    ) : TopLevelScopeRefV2

    @Serializable
    @SerialName("joint")
    data class Joint(
        override val serverId: String,
        override val scopeId: String,
    ) : TopLevelScopeRefV2
}

/**
 * Conversation ownership is separate from the message fact. A top-level message may independently carry
 * [CanonicalMessageEnvelopeV2.childReplyScope]; a reply carries both [Thread] context and exact-equal
 * [ReplyMembershipV2] relation facts.
 */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface CanonicalConversationContextV2 {
    @Serializable
    @SerialName("top-level")
    data class TopLevel(
        val scope: TopLevelScopeRefV2,
    ) : CanonicalConversationContextV2

    @Serializable
    @SerialName("thread")
    data class Thread(
        val replyScope: ReplyScopeRefV2,
        val parentTopLevelScope: TopLevelScopeRefV2,
        val rootMessageRef: MessageRefV2,
    ) : CanonicalConversationContextV2
}

@Serializable
data class CanonicalMentionV2(
    val id: String,
    val name: String,
    val type: String,
)

/** Shared attachment fact. Viewer-filtered comment count/ref is deliberately absent. */
@Serializable
data class CanonicalAttachmentV2(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
)

/** Exact typed action-card action shared by the authoritative V2 node. */
@Serializable
data class CanonicalActionCardActionV2(
    val type: String,
    val name: String? = null,
    val description: String? = null,
    val visibility: String? = null,
    val channel: String? = null,
    val suggestedComputer: String? = null,
    val requiredComputer: String? = null,
    val humans: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val initialHumans: List<String> = emptyList(),
    val initialAgents: List<String> = emptyList(),
    val draftHint: String? = null,
    val requestId: String? = null,
    val agentId: String? = null,
    val agentName: String? = null,
    val clientId: String? = null,
    val clientKey: String? = null,
    val clientName: String? = null,
    val scopes: List<String> = emptyList(),
)

@Serializable
data class CanonicalActionCardResultV2(
    val kind: String,
    val id: String? = null,
    val name: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val addedHumanIds: List<String> = emptyList(),
    val addedAgentIds: List<String> = emptyList(),
)

/** Privacy-safe forwarded author snapshot. Source/user pointer ids are intentionally absent. */
@Serializable
data class CanonicalForwardedAuthorSnapshotV2(
    val type: String? = null,
    val name: String? = null,
    val uniqueName: String? = null,
)

/** Privacy-safe forwarded target snapshot. Source target pointer ids are intentionally absent. */
@Serializable
data class CanonicalForwardedTargetSnapshotV2(
    val type: String? = null,
    val label: String,
    val labelVisibility: String? = null,
)

@Serializable
data class CanonicalForwardedAttachmentSnapshotV2(
    val filename: String,
    val mimeType: String? = null,
)

/** Shared forwarded content snapshot. sourceMessageId/provenanceState never enter authoritative bytes. */
@Serializable
data class CanonicalForwardedBundleItemV2(
    val index: Int,
    val author: CanonicalForwardedAuthorSnapshotV2? = null,
    val createdAt: String? = null,
    val target: CanonicalForwardedTargetSnapshotV2? = null,
    val content: String,
    val attachments: List<CanonicalForwardedAttachmentSnapshotV2> = emptyList(),
)

/** Closed known action-metadata family. There is no Unknown/raw-json variant. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface CanonicalActionMetadataV2 {
    @Serializable
    @SerialName("action-card")
    data class ActionCard(
        val version: Int? = null,
        val action: CanonicalActionCardActionV2,
        val state: String = "prepared",
        val executedAt: String? = null,
        val executedByUserName: String? = null,
        val result: CanonicalActionCardResultV2? = null,
    ) : CanonicalActionMetadataV2

    @Serializable
    @SerialName("forwarded-bundle")
    data class ForwardedBundle(
        val version: Int? = null,
        val items: List<CanonicalForwardedBundleItemV2>,
    ) : CanonicalActionMetadataV2
}

/**
 * Complete shared V2 node. Container/thread identity and receiver-private fields live in their separate
 * typed owners; raw threadId/commentRef/conversationContext and full reaction rosters cannot be expressed.
 */
@Serializable
data class CanonicalMessageNodeV2(
    val id: String,
    val seq: Long,
    val senderType: String,
    val senderId: String,
    val content: String,
    val messageType: String,
    val createdAt: String,
    val randomId: String? = null,
    val senderName: String,
    val senderDescription: String? = null,
    val senderMembershipStatus: String? = null,
    val mentions: List<CanonicalMentionV2> = emptyList(),
    val attachments: List<CanonicalAttachmentV2> = emptyList(),
    val reactions: List<ReactionFact> = emptyList(),
    val actionMetadata: CanonicalActionMetadataV2? = null,
    val replyCount: Int = 0,
    /**
     * RFC 077 retraction (slock 704d69f4, web `CanonicalRetractionFact`): an additive OPTIONAL key —
     * present only for a retracted message (a canonical ISO timestamp), omitted otherwise, never
     * `null`. Retraction is one-way: the store keeps a stored value when a later frame omits it.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deletedAt: String? = null,
)

/** Full schema-v6 authoritative message envelope. */
@Serializable
data class CanonicalMessageEnvelopeV2(
    val schemaVersion: Int = CANONICAL_MESSAGE_FULL_V2_SCHEMA_VERSION,
    val kind: String = CANONICAL_MESSAGE_FULL_V2_ENVELOPE_KIND,
    val fact: CanonicalMessageNodeV2,
    val context: CanonicalConversationContextV2,
    val childReplyScope: ReplyScopeRefV2? = null,
    val replyMembership: ReplyMembershipV2? = null,
)

/** Typed receiver-private read-cache identity for Message x ReactionActors(emoji). */
data class ReactionActorsReadKeyV2(
    val partition: DurablePartitionKey,
    val parentScopeKind: String,
    val parentScopeId: String,
    val messageId: String,
    val emoji: String,
)

/** Typed receiver-private read-cache identity for Attachment x AttachmentComments. */
data class AttachmentCommentsReadKeyV2(
    val partition: DurablePartitionKey,
    val parentScopeKind: String,
    val parentScopeId: String,
    val messageId: String,
    val attachmentId: String,
)
