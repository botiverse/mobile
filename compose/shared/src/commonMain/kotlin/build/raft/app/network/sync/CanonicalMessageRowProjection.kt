package build.raft.app.network.sync

import build.raft.app.model.AvatarReference
import build.raft.app.toRaftAvatarLabel
import build.raft.app.MemberKindWireContract
import build.raft.app.canonical.message.CanonicalAttachmentWire
import build.raft.app.canonical.message.CanonicalCommentHostSourceWire
import build.raft.app.canonical.message.CanonicalCommentRefWire
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalExternalAuthorWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.commentHostRouteKindOrNull
import build.raft.app.commentHostTypeOrNull
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.WireExternalAuthor
import build.raft.app.data.externalMessageAuthorOrFallback
import build.raft.app.data.externalSenderIdOrSyntheticKey
import build.raft.app.data.toForwardMessageBundle
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageCommentHostSource
import build.raft.app.model.MessageCommentRef
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageDeliveryState
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageType
import build.raft.app.model.providerLabel
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.Presence
import build.raft.app.model.ThreadId
import build.raft.app.parseConversationKindOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictMessageTypeOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Optional app-directory enrichment applied after the canonical flat sender has been rebuilt.
 * The canonical row remains the source for identity/kind and supplies a complete fallback when
 * the member directory has no matching entry.
 */
typealias CanonicalMemberHydration = (MemberProfile) -> MemberProfile

private val canonicalProjectionJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Lossy app projection of the wire-shaped canonical row. Task state is intentionally left null:
 * it is owned by the legacy task projection and joined after this boundary. Likewise, no
 * `updatedAtLabel` is synthesized from the canonical row; sync identity/order uses epoch + seq + id.
 */
fun CanonicalMessageRow.toAppMessage(
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
    memberHydration: CanonicalMemberHydration = { it },
): Message? {
    val resolvedMessageType = messageType.strictMessageTypeOrNull("canonical_message_type", diagnostics) ?: return null
    val senderKind =
        senderType.strictMemberKindOrNull(
            source = "canonical_message_sender",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.MessageSender,
        ) ?: return null
    // RealtimeEventMapper parity: an External sender keeps its row; a missing/partial frozen
    // author renders with the fallback author, and a blank sender id becomes a synthetic key.
    val external = senderKind == MemberKind.External
    val resolvedExternalAuthor =
        if (external) {
            externalMessageAuthorOrFallback(
                externalAuthor?.toWireExternalAuthor(),
                senderName.ifBlank { null },
                "canonical_message_sender",
                id,
                diagnostics,
            )
        } else {
            null
        }
    val authorId =
        if (resolvedExternalAuthor != null) externalSenderIdOrSyntheticKey(senderId, resolvedExternalAuthor, id) else senderId
    val authorKind = if (resolvedMessageType == MessageType.System) MemberKind.System else senderKind
    val fallbackAuthor = MemberProfile(
        id = MemberId(authorId),
        displayName = resolvedExternalAuthor?.displayName ?: senderName.ifBlank { senderId },
        handle = resolvedExternalAuthor?.let { senderName.ifBlank { it.displayName } } ?: senderName.ifBlank { senderId },
        kind = authorKind,
        description = if (resolvedExternalAuthor != null) resolvedExternalAuthor.providerLabel() else senderDescription,
        avatar = resolvedExternalAuthor?.avatarUrl?.let(AvatarReference::fromWireValue),
        avatarLabel = (resolvedExternalAuthor?.displayName ?: senderName.ifBlank { senderId }).toRaftAvatarLabel(),
        presence = Presence.Offline,
    )
    val action = actionMetadata.toAppActionProjection()
    return Message(
        id = MessageId(id),
        // The wire `threadId` is an anchor, not the app navigation ThreadId. Messages live in the
        // channel bucket, which remains the app navigation identity.
        threadId = ThreadId(channelId),
        channelId = ChannelId(channelId),
        // message:* does not carry app thread linkage; preserve RealtimeEventMapper parity.
        threadChannelId = null,
        seq = seq,
        author = memberHydration(fallbackAuthor),
        timestampLabel = createdAt,
        createdAtEpochMillis = parseRaftTimestampEpochMillisOrNull(createdAt),
        body = content,
        messageType = resolvedMessageType,
        metadata = action.metadata,
        attachments = attachments.map(CanonicalAttachmentWire::toAppAttachment),
        deliveryState = MessageDeliveryState.Sent,
        task = null,
        updatedAtLabel = null,
        reactions = reactions.mapNotNull(CanonicalReactionWire::toAppReaction).takeIf { it.isNotEmpty() },
        senderMembershipStatus = senderMembershipStatus,
        commentRef = commentRef?.toAppCommentRef(),
        forwardBundle = action.forwardBundle,
        conversationContext = conversationContext?.toAppConversationContext(),
        randomId = randomId?.takeIf { it.isNotBlank() },
        // Same as RealtimeEventMapper (parity): flagged here, emptied where it is shown.
        deletedAt = deletedAt,
        externalAuthor = resolvedExternalAuthor,
    )
}

/** Same lossy app projection as the REST/realtime `WireExternalAuthor` (workspaceName is not shown).
 *  Blank strings are fields the decoder kept as absent; `toExternalMessageAuthor` reads them as absent. */
private fun CanonicalExternalAuthorWire.toWireExternalAuthor(): WireExternalAuthor = WireExternalAuthor(
    displayName = displayName,
    provider = provider,
    actorKind = actorKind,
    avatarUrl = avatarUrl,
    avatarDigest = avatarDigest,
    projectionId = projectionId,
    externalActorId = externalActorId,
    externalConversationId = externalConversationId,
    externalMessageId = externalMessageId,
    workspaceId = workspaceId,
    installId = installId,
    appRegistrationId = appRegistrationId,
    // The server requires revision > 0; the decoder stores 0 for an absent one.
    actorProjectionRevision = actorProjectionRevision.takeIf { it > 0 },
)

private fun CanonicalAttachmentWire.toAppAttachment(): Attachment = Attachment(
    id = AttachmentId(id),
    name = filename,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sizeLabel = "",
    thumbnailUrl = thumbnailUrl,
    width = width,
    height = height,
    // Viewer-scoped commentCount is explicitly excluded from the canonical shared row.
    commentCount = 0,
)

private fun CanonicalReactionWire.toAppReaction(): MessageReaction? {
    val validEmoji = emoji.takeIf { it.isNotBlank() } ?: return null
    if (count < 0) return null
    return MessageReaction(
        emoji = validEmoji,
        count = count,
        reactorIds = reactorIds,
        reactorNames = reactorNames,
    )
}

private fun CanonicalCommentRefWire.toAppCommentRef(): MessageCommentRef? {
    val validAttachmentId = attachmentId.takeIf { it.isNotBlank() } ?: return null
    val validFilename = filename.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentRef(
        attachmentId = AttachmentId(validAttachmentId),
        filename = validFilename,
        hostMessageId = hostMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        hostSource = hostSource?.toAppCommentHostSource(),
        anchorLabel = anchorLabel?.takeIf { it.isNotBlank() },
    )
}

private fun CanonicalCommentHostSourceWire.toAppCommentHostSource(): MessageCommentHostSource? {
    val validType = type.commentHostTypeOrNull() ?: return null
    val validRouteKind = routeKind.commentHostRouteKindOrNull() ?: return null
    val validChannelId = channelId.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentHostSource(
        type = validType,
        routeKind = validRouteKind,
        channelId = ChannelId(validChannelId),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        rootThreadChannelId = rootThreadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
    )
}

private fun CanonicalConversationContextWire.toAppConversationContext(): MessageConversationContext? {
    val context = MessageConversationContext(
        channelKind = parseConversationKindOrNull(channelType),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        parentChannelKind = parseConversationKindOrNull(parentChannelType),
    )
    return context.takeIf {
        it.channelKind != null ||
            it.parentMessageId != null ||
            it.parentChannelId != null ||
            it.parentChannelKind != null
    }
}

private data class AppActionProjection(
    val metadata: Map<String, String> = emptyMap(),
    val forwardBundle: build.raft.app.model.ForwardMessageBundle? = null,
)

private fun JsonElement?.toAppActionProjection(): AppActionProjection {
    val element = this ?: return AppActionProjection()
    val wire = runCatching {
        canonicalProjectionJson.decodeFromJsonElement(WireActionCardMetadata.serializer(), element)
    }.getOrNull() ?: return AppActionProjection()
    return AppActionProjection(
        metadata = wire.toThreadActionCard()?.toMessageMetadata().orEmpty(),
        forwardBundle = wire.toForwardMessageBundle(),
    )
}
