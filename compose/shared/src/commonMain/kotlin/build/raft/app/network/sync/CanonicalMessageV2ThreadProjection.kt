package build.raft.app.network.sync

import build.raft.app.KuiklyThreadAttachmentItem
import build.raft.app.KuiklyThreadMessageItem
import build.raft.app.KuiklyThreadReactionItem
import build.raft.app.MemberKindWireContract
import build.raft.app.ThreadMessageDeliverySent
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.WireActionCardResult
import build.raft.app.data.WireForwardedBundleAttachment
import build.raft.app.data.WireForwardedBundleAuthor
import build.raft.app.data.WireForwardedBundleItem
import build.raft.app.data.WireForwardedBundleTarget
import build.raft.app.data.toForwardMessageBundle
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import build.raft.app.memberKindWireValue
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageType
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictMessageTypeOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val canonicalV2ThreadProjectionJson = Json {
    encodeDefaults = true
}

/**
 * Lossy thread-screen projection of one durable V2 message fact (the "last known" offline frame).
 *
 * Mirrors the REST `WireMessage` → [KuiklyThreadMessageItem] mapping for the fields the shared V2
 * fact carries, and is deliberately narrower where the fact is narrower by design:
 * - reactions are emoji + count only; the reactor roster (and therefore `selected`) is a
 *   receiver-private read cache that arrives with the next network refresh, never from this frame;
 * - task projection, thread follow/unread state, comment refs and attachment comment counts are
 *   viewer-scoped and absent from the fact, so they stay at their "not loaded" defaults;
 * - sender/message kinds are parsed strictly (same contract as the wire parsers): an unknown kind
 *   drops the message with a diagnostic rather than rendering a guessed author.
 */
internal fun CanonicalMessageEnvelopeV2.toThreadMessageItem(
    currentUserId: String?,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): KuiklyThreadMessageItem? {
    val resolvedMessageType =
        fact.messageType.strictMessageTypeOrNull("canonical_v2_message_type", diagnostics) ?: return null
    val senderKind =
        fact.senderType.strictMemberKindOrNull(
            source = "canonical_v2_message_sender",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.InternalActor,
        ) ?: return null
    val authorKind = if (resolvedMessageType == MessageType.System) MemberKind.System else senderKind
    val resolvedName = fact.senderName.takeIf { it.isNotBlank() } ?: "Unknown"
    val wireAction = fact.actionMetadata?.toWireActionCardMetadata()
    return KuiklyThreadMessageItem(
        id = fact.id,
        authorId = fact.senderId.ifBlank { "unknown" },
        authorName = resolvedName,
        authorDescription = fact.senderDescription?.takeIf { it.isNotBlank() },
        authorKind = authorKind.memberKindWireValue(),
        isOwn = currentUserId != null && fact.senderId == currentUserId,
        avatarLabel = resolvedName.take(1).uppercase(),
        avatarWireValue = null,
        senderMembershipStatus = fact.senderMembershipStatus?.takeIf { it.isNotBlank() },
        timestampLabel = fact.createdAt,
        createdAtEpochMillis = parseSlockTimestampEpochMillisOrNull(fact.createdAt),
        seq = fact.seq,
        messageType = resolvedMessageType.name,
        systemEventType = null,
        metadata = wireAction.toThreadActionCard()?.toMessageMetadata().orEmpty(),
        body = fact.content,
        randomId = fact.randomId?.takeIf { it.isNotBlank() },
        threadChannelId = childReplyScope?.scopeId?.takeIf { it.isNotBlank() },
        replyCount = fact.replyCount.coerceAtLeast(0),
        reactions = fact.reactions.mapNotNull { it.toReactionItem() },
        attachments = fact.attachments.mapIndexed { index, attachment -> attachment.toAttachmentItem(index) },
        commentRef = null,
        forwardBundle = wireAction.toForwardMessageBundle(),
        deliveryState = ThreadMessageDeliverySent,
    )
}

/** Count-only reaction chip. The roster is not part of the shared fact; `selected` is unknown here. */
private fun ReactionFact.toReactionItem(): KuiklyThreadReactionItem? {
    val validEmoji = emoji.takeIf { it.isNotBlank() } ?: return null
    if (count <= 0) return null
    return KuiklyThreadReactionItem(emoji = validEmoji, count = count, selected = false)
}

private fun CanonicalAttachmentV2.toAttachmentItem(index: Int): KuiklyThreadAttachmentItem =
    KuiklyThreadAttachmentItem(
        id = id,
        name = filename.ifBlank { "Attachment ${index + 1}" },
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sizeLabel = "",
        thumbnailUrl = thumbnailUrl,
        // Viewer-scoped comment count is explicitly excluded from the shared fact.
        commentCount = 0,
    )

/**
 * Re-shapes the typed V2 action family into the wire metadata the existing card/bundle
 * projections consume, so V2 reads render exactly like a REST-decoded card. Field names of
 * [CanonicalActionCardActionV2] are the wire action's own names (the normalizer decodes the
 * producer object into it directly), so encoding it back yields the same object.
 */
private fun CanonicalActionMetadataV2.toWireActionCardMetadata(): WireActionCardMetadata =
    when (this) {
        is CanonicalActionMetadataV2.ActionCard -> WireActionCardMetadata(
            kind = "action-card",
            version = version,
            action = canonicalV2ThreadProjectionJson
                .encodeToJsonElement(CanonicalActionCardActionV2.serializer(), action)
                .jsonObject,
            state = state,
            executedAt = executedAt,
            executedByUserName = executedByUserName,
            result = result?.let {
                WireActionCardResult(
                    kind = it.kind,
                    id = it.id,
                    name = it.name,
                    channelId = it.channelId,
                    channelName = it.channelName,
                    addedHumanIds = it.addedHumanIds,
                    addedAgentIds = it.addedAgentIds,
                )
            },
        )
        is CanonicalActionMetadataV2.ForwardedBundle -> WireActionCardMetadata(
            kind = "forwarded-bundle",
            version = version,
            forwardedItems = items.map { item ->
                WireForwardedBundleItem(
                    index = item.index,
                    sourceAuthorSnapshot = item.author?.let {
                        WireForwardedBundleAuthor(type = it.type, name = it.name, uniqueName = it.uniqueName)
                    },
                    sourceCreatedAt = item.createdAt,
                    sourceTargetSnapshot = item.target?.let {
                        WireForwardedBundleTarget(type = it.type, label = it.label, labelVisibility = it.labelVisibility)
                    },
                    contentSnapshot = item.content,
                    attachmentSnapshots = item.attachments.map {
                        WireForwardedBundleAttachment(filename = it.filename, mimeType = it.mimeType)
                    },
                )
            },
        )
    }
