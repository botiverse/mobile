package build.raft.app.network.sync

import build.raft.app.MemberKindWireContract
import build.raft.app.canonical.message.CanonicalAttachmentWire
import build.raft.app.canonical.message.CanonicalCommentHostSourceWire
import build.raft.app.canonical.message.CanonicalCommentRefWire
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalMentionWire
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.commentHostRouteKindOrNull
import build.raft.app.commentHostTypeOrNull
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.WireActionCardAction
import build.raft.app.data.WireActionCardResult
import build.raft.app.data.WireForwardedBundleAttachment
import build.raft.app.data.WireForwardedBundleAuthor
import build.raft.app.data.WireForwardedBundleItem
import build.raft.app.data.WireForwardedBundleTarget
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
import build.raft.app.model.Presence
import build.raft.app.model.ThreadId
import build.raft.app.parseConversationKindOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictMessageTypeOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

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
            contract = MemberKindWireContract.InternalActor,
        ) ?: return null
    val authorKind = if (resolvedMessageType == MessageType.System) MemberKind.System else senderKind
    val fallbackAuthor = MemberProfile(
        id = MemberId(senderId),
        displayName = senderName.ifBlank { senderId },
        handle = senderName.ifBlank { senderId },
        kind = authorKind,
        description = senderDescription,
        avatarLabel = senderName.ifBlank { senderId }.take(1).uppercase(),
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
    )
}

fun CanonicalMessageEnvelopeV2.toAppMessage(
    scope: SyncScopeKey,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): Message? {
    val contextWire = when (val typed = context) {
        is CanonicalConversationContextV2.TopLevel ->
            CanonicalConversationContextWire(channelType = typed.scope.scopeKindForProjection())
        is CanonicalConversationContextV2.Thread ->
            CanonicalConversationContextWire(
                channelType = "thread",
                parentChannelId = typed.parentTopLevelScope.scopeId,
                parentChannelType = typed.parentTopLevelScope.scopeKindForProjection(),
                parentMessageId = typed.rootMessageRef.messageId,
            )
    }
    val row = CanonicalMessageRow(
        id = fact.id,
        channelId = scope.scopeId,
        seq = fact.seq,
        senderType = fact.senderType,
        senderId = fact.senderId,
        content = fact.content,
        messageType = fact.messageType,
        createdAt = fact.createdAt,
        randomId = fact.randomId,
        threadId = childReplyScope?.scopeId,
        senderName = fact.senderName,
        senderDescription = fact.senderDescription,
        senderMembershipStatus = fact.senderMembershipStatus,
        mentions = fact.mentions.map { CanonicalMentionWire(it.id, it.name, it.type) },
        attachments = fact.attachments.map {
            CanonicalAttachmentWire(
                id = it.id,
                filename = it.filename,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                width = it.width,
                height = it.height,
                thumbnailUrl = it.thumbnailUrl,
            )
        },
        reactions = fact.reactions.map { reaction ->
            CanonicalReactionWire(
                emoji = reaction.emoji,
                count = reaction.count,
                reactorIds = reaction.previewK.map { it.id },
                reactorNames = reaction.previewK.map { it.displayName },
            )
        },
        actionMetadata = fact.actionMetadata.toWireActionMetadataJson(),
        conversationContext = contextWire,
    )
    return row.toAppMessage(diagnostics)?.copy(
        threadChannelId = childReplyScope?.scopeId?.let(::ChannelId),
        replyCount = fact.replyCount,
        parentMessageId =
            (context as? CanonicalConversationContextV2.Thread)
                ?.rootMessageRef
                ?.messageId
                ?.let(::MessageId),
    )
}

private fun TopLevelScopeRefV2.scopeKindForProjection(): String =
    when (this) {
        is TopLevelScopeRefV2.Channel -> "channel"
        is TopLevelScopeRefV2.Private -> "private"
        is TopLevelScopeRefV2.DirectMessage -> "dm"
        is TopLevelScopeRefV2.Joint -> "joint"
    }

private fun CanonicalActionMetadataV2?.toWireActionMetadataJson(): JsonElement? {
    val wire = when (val metadata = this) {
        null -> return null
        is CanonicalActionMetadataV2.ActionCard ->
            WireActionCardMetadata(
                kind = "action-card",
                version = metadata.version,
                action = canonicalProjectionJson.encodeToJsonElement(
                    WireActionCardAction.serializer(),
                    WireActionCardAction(
                        type = metadata.action.type,
                        name = metadata.action.name,
                        description = metadata.action.description,
                        visibility = metadata.action.visibility,
                        channel = metadata.action.channel,
                        suggestedComputer = metadata.action.suggestedComputer,
                        requiredComputer = metadata.action.requiredComputer,
                        humans = metadata.action.humans,
                        agents = metadata.action.agents,
                        initialHumans = metadata.action.initialHumans,
                        initialAgents = metadata.action.initialAgents,
                        draftHint = metadata.action.draftHint,
                        requestId = metadata.action.requestId,
                        agentId = metadata.action.agentId,
                        agentName = metadata.action.agentName,
                        clientId = metadata.action.clientId,
                        clientKey = metadata.action.clientKey,
                        clientName = metadata.action.clientName,
                        scopes = metadata.action.scopes,
                    ),
                ).jsonObject,
                state = metadata.state,
                executedAt = metadata.executedAt,
                executedByUserName = metadata.executedByUserName,
                result = metadata.result?.let {
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
        is CanonicalActionMetadataV2.ForwardedBundle ->
            WireActionCardMetadata(
                kind = "forwarded-bundle",
                version = metadata.version,
                forwardedItems = metadata.items.map { item ->
                    WireForwardedBundleItem(
                        index = item.index,
                        sourceAuthorSnapshot = item.author?.let {
                            WireForwardedBundleAuthor(
                                type = it.type,
                                name = it.name,
                                uniqueName = it.uniqueName,
                            )
                        },
                        sourceCreatedAt = item.createdAt,
                        sourceTargetSnapshot = item.target?.let {
                            WireForwardedBundleTarget(
                                type = it.type,
                                label = it.label,
                                labelVisibility = it.labelVisibility,
                            )
                        },
                        contentSnapshot = item.content,
                        attachmentSnapshots = item.attachments.map {
                            WireForwardedBundleAttachment(it.filename, it.mimeType)
                        },
                    )
                },
            )
    }
    return canonicalProjectionJson.encodeToJsonElement(WireActionCardMetadata.serializer(), wire)
}

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
