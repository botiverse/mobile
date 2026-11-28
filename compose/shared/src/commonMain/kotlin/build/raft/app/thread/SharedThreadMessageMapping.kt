package build.raft.app.thread

import build.raft.app.MemberKindWireContract
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ReplyCacheProvenance
import build.raft.app.commentHostRouteKindOrNull
import build.raft.app.commentHostTypeOrNull
import build.raft.app.data.externalMessageAuthorOrFallback
import build.raft.app.data.DataResult
import build.raft.app.data.channelTaskSurfacesShown
import build.raft.app.data.channelTaskWritesAllowed
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.isEdgeBlockedMarker
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.RawMessagePage
import build.raft.app.data.map
import build.raft.app.get
import build.raft.app.memberKindWireValue
import build.raft.app.messages
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageCommentHostSource
import build.raft.app.model.MessageCommentRef
import build.raft.app.model.MessageDeliveryState
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageType
import build.raft.app.model.Presence
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.ThreadDurableEnteredPage
import build.raft.app.normalizedThreadChannelKind
import build.raft.app.parsedThreadParentSurfaceKindOrNull
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictMessageTypeOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.strictTaskStatusOrNull
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.threadParentChannelKind
import build.raft.app.threadParentSurfaceKind
import build.raft.app.toRaftAvatarLabel
import build.raft.app.usesMemberScopedMentions
import build.raft.app.model.providerLabel
import build.raft.app.model.resolveAvatarSlot
import build.raft.app.model.safeDisplayNameOrNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun ThreadSnapshot.toRaftThreadPayload(
    base: RaftThreadScreenPayload,
    currentUserId: String?
): RaftThreadScreenPayload {
    val baseHasReplyThreadIdentity =
        base.surfaceKind == RaftThreadSurfaceKind.ReplyThread ||
            (
                base.parentChannelId?.isNotBlank() == true &&
                    (base.parentMessageId?.isNotBlank() == true || base.originParentMessageId?.isNotBlank() == true)
                )
    val baseTargetsParentConversation =
        !baseHasReplyThreadIdentity &&
            base.parentChannelId.isNullOrBlank() &&
            base.focusedMessageId?.isNotBlank() == true &&
            (
                base.surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
                    base.surfaceKind == RaftThreadSurfaceKind.Channel
                )
    val routeIsReplyThread =
        baseHasReplyThreadIdentity ||
            (
                !baseTargetsParentConversation &&
                    conversation.kind == ConversationKind.Thread
                )
    val routeParentMessageId =
        base.parentMessageId?.takeIf { it.isNotBlank() }
            ?: base.originParentMessageId?.takeIf { it.isNotBlank() }
    val parentId =
        if (routeIsReplyThread) {
            routeParentMessageId
                ?: conversation.thread?.parentMessageId?.value
                ?: parentMessage?.id?.value
        } else {
            null
        }
    val resolvedParentMessage =
        parentMessage
            ?.takeIf { message -> parentId == null || message.id.value == parentId }
            ?.takeUnless { it.isSparseReplyOriginParentCandidate() }
            ?: parentId?.let { id ->
                messages.firstOrNull { message ->
                    message.id.value == id && !message.isSparseReplyOriginParentCandidate()
                }
            }
    val surfaceKind =
        when {
            conversation.kind == ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
            routeIsReplyThread &&
                (resolvedParentMessage != null || !base.parentMessageId.isNullOrBlank() || !base.originParentMessageId.isNullOrBlank()) ->
                RaftThreadSurfaceKind.ReplyThread
            else -> base.surfaceKind
        }
    val channelKind =
        if (surfaceKind == RaftThreadSurfaceKind.Channel) {
            conversation.resolveThreadRouteIdentity().channelKind
                .withRouteChannelKindFallback(base.resolveThreadRouteIdentity().channelKind ?: base.channelKind)
                ?: base.channelKind
        } else {
            base.channelKind
        }
    val streamChannelId =
        base.streamChannelId
            ?: if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                conversation.thread?.streamChannelId?.value
            } else {
                conversation.channelId?.value ?: conversation.threadId.value
            }
    val parentChannelId =
        if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            base.parentChannelId ?: conversation.thread?.parentChannelId?.value
        } else {
            null
        }
    val parentMessageId =
        if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            base.parentMessageId ?: resolvedParentMessage?.id?.value ?: conversation.thread?.parentMessageId?.value
        } else {
            null
        }
    val parentSurfaceKind =
        if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            // Parent shape is immutable route intent. Snapshot content may omit it, but it must
            // never erase a valid launch identity before the directory projection renders the
            // header. Preserve malformed non-blank input too so display enrichment still rejects
            // it fail-closed instead of silently guessing Channel.
            base.parentSurfaceKind?.takeIf { it.isNotBlank() }
                ?: base.parentChannelKind.parsedThreadParentSurfaceKindOrNull()
                ?: conversation.threadParentSurfaceKind()
        } else {
            null
        }
    val parentChannelKind =
        if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            base.parentChannelKind.normalizedThreadChannelKind()
                ?: conversation.threadParentChannelKind()
        } else {
            null
        }
    val sourceThreadId =
        base.sourceThreadId
            ?: if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                conversation.thread?.streamChannelId?.value
            } else {
                conversation.channelId?.value ?: conversation.threadId.value
            }
    val replyThreadContentChannelId =
        base.copy(
            surfaceKind = surfaceKind,
            sourceThreadId = sourceThreadId,
            streamChannelId = streamChannelId
        ).replyThreadContentChannelId()
    val parentConversationMessagePatch =
        if (baseTargetsParentConversation) {
            parentMessage?.toRaftThreadMessageItem(currentUserId)
        } else {
            null
        }
    val timelineMessages =
        if (
            parentConversationMessagePatch != null &&
            messages.none { message -> message.id.value == parentConversationMessagePatch.id }
        ) {
            messages + listOfNotNull(parentMessage)
        } else {
            messages
        }
    val cachedMessages =
        timelineMessages
            .filterNot { message -> parentId != null && message.id.value == parentId }
            .filter { message ->
                replyThreadContentChannelId == null || message.belongsToReplyThreadContent(replyThreadContentChannelId)
            }
            .map { message ->
                val item = message.toRaftThreadMessageItem(currentUserId)
                if (parentConversationMessagePatch?.id == item.id) {
                    item.mergeRealtimeUpdate(parentConversationMessagePatch)
                } else {
                    item
                }
            }
            .sortedByThreadTimeline()
    val isDirectMessage = surfaceKind == RaftThreadSurfaceKind.DirectMessage
    val safeDirectMessagePeerName = conversation.safeDisplayNameOrNull().takeIf { isDirectMessage }
    val headerContext =
        SharedThreadHeaderContextStore.conversationContext(
            surfaceKind = surfaceKind,
            conversation = conversation,
            originMessage = resolvedParentMessage?.toRaftThreadMessageItem(currentUserId),
            parentMessageId = parentId,
            originPending =
                surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                    resolvedParentMessage == null &&
                    parentId != null &&
                    base.originStatus == "loading"
        )
    val resolvedComposer =
        if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            base.composer.copy(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                mentionChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                streamChannelId = streamChannelId
            )
        } else {
            base.composer
        }
    // Joint channels have task surfaces like any channel (server task surface mapping).
    val supportsChannelTasks = conversation.channelTaskSurfacesShown()
    // Task writes (send as task, the message menu's task items) only where the reader may write
    // the channel's tasks (web canWriteTasksInChannel).
    val writesChannelTasks = conversation.channelTaskWritesAllowed()
    val resolvedCapabilityComposer =
        resolvedComposer.copy(
            showAsTaskToggle = resolvedComposer.showAsTaskToggle && writesChannelTasks
        )
    return base.copy(
        title = headerContext.title,
        subtitle = headerContext.subtitle,
        status =
            if (cachedMessages.isEmpty() && headerContext.originMessage == null && tasks.isEmpty()) {
                "empty"
            } else {
                "content"
            },
        sourceThreadId = sourceThreadId,
        surfaceKind = surfaceKind,
        channelKind = channelKind ?: base.channelKind,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind = parentSurfaceKind,
        parentChannelKind = parentChannelKind,
        memberScopedMentions = base.memberScopedMentions || conversation.usesMemberScopedMentions(),
        messages = cachedMessages,
        originMessage = headerContext.originMessage,
        originPreview = headerContext.originPreview,
        originParentMessageId = headerContext.originParentMessageId,
        focusedMessageId = base.focusedMessageId,
        focusedMessageRequestNonce = base.focusedMessageRequestNonce,
        originStatus = headerContext.originStatus,
        originErrorCode = headerContext.originErrorCode,
        emptyTitle =
            if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                ThreadReplyEmptyTitle
            } else if (
                cachedMessages.isEmpty() && headerContext.originMessage == null && tasks.isEmpty() &&
                    base.emptyTitle == ThreadRouteLoadingPlaceholderTitle
            ) {
                // task #326: the route placeholder's "Loading..." must not survive into a
                // resolved empty channel frame, or the empty state shows "加载中" forever.
                ThreadEmptyConversationTitle
            } else {
                base.emptyTitle
            },
        emptyDetail =
            if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                ThreadReplyEmptyDetail
            } else if (
                cachedMessages.isEmpty() && headerContext.originMessage == null && tasks.isEmpty() &&
                    base.emptyTitle == ThreadRouteLoadingPlaceholderTitle
            ) {
                ThreadEmptyConversationDetail
            } else {
                base.emptyDetail
            },
        composer = resolvedCapabilityComposer,
        showTaskOpenActions = base.showTaskOpenActions && supportsChannelTasks,
        showTaskStatusActions = base.showTaskStatusActions && writesChannelTasks,
        tasks = tasks.map { task -> task.toRaftThreadTaskItem() },
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        // Agent Lounge v0 (web ChatPanel `joined = isDM || isThread || isLounge ? true : ...`): a
        // Lounge has no Join step; the first post joins, and its permissions say whether you may post.
        joined = conversation.joined || (surfaceKind == RaftThreadSurfaceKind.Channel && !conversation.loungeAgentId.isNullOrBlank()),
        canSend = conversation.permissions.canSend,
        channelArchived = conversation.archived,
        // A reply thread's capabilities are its parent channel's (the canonical detail).
        channelCanUnarchive = (canonicalConversationDetail ?: conversation).channelCapabilities?.archiveChannels,
        membersCount = conversation.memberCount ?: base.membersCount,
        errorCode = null,
        dmPeerName = safeDirectMessagePeerName,
        dmPeerAvatarUrl = conversation.directMessagePeerAvatarWireValue?.takeIf { it.isNotBlank() },
        dmPeerAvatarLabel = safeDirectMessagePeerName?.take(2)?.uppercase(),
        activityMuted = conversation.activityMuted,
        muteFromSeq = conversation.muteFromSeq,
        activityMuteSupported = conversation.activityMuteSupported,
        replySummariesAuthority = replySummariesAuthority,
        canonicalAccessKnown = conversation.membershipKnown,
    )
        .withOriginReplyCountAtLeastLoadedReplies()
        .withReplySummaries(replySummaries, replySummariesAuthority)
}

private fun Message.isSparseReplyOriginParentCandidate(): Boolean =
    needsReplyOriginContextHydration()

private fun String?.withRouteChannelKindFallback(routeKind: String?): String? {
    val normalized = normalizedThreadChannelKind()
    val route = routeKind.normalizedThreadChannelKind()
    return if (normalized == RaftThreadSurfaceKind.Channel && route != null && route != RaftThreadSurfaceKind.Channel) {
        route
    } else {
        normalized ?: route
    }
}

internal fun ThreadSnapshot.matchesFocusedThreadRoute(
    routeThreadId: ThreadId,
    focusedMessageId: String
): Boolean {
    val routeId = routeThreadId.value.takeIf { it.isNotBlank() } ?: return false
    val focusedMessage =
        messages.firstOrNull { message -> message.id.value == focusedMessageId }
            ?: parentMessage?.takeIf { message -> message.id.value == focusedMessageId }
            ?: return false
    return focusedMessage.channelId?.value == routeId || focusedMessage.threadId.value == routeId
}

internal fun RaftThreadScreenPayload.focusedMessageContextChannelIdOrNull(): ChannelId? {
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread) return null
    return listOfNotNull(streamChannelId, sourceThreadId)
        .firstNotNullOfOrNull { value -> value.takeIf { it.isNotBlank() }?.let(::ChannelId) }
}

internal fun ThreadSnapshot.withConversationAccessFallback(
    fallback: ConversationAccessCandidate?
): ThreadSnapshot {
    fallback ?: return this
    val current = conversation
    if (current.kind == ConversationKind.DirectMessage) return this
    if (fallback.joined || !current.matchesHeaderFallbackId(fallback.ids)) return this
    val nextConversation =
        current.copy(
            joined = false,
            permissions = fallback.permissions
        )
    return if (nextConversation == current) this else copy(conversation = nextConversation)
}

internal fun RaftThreadScreenPayload.conversationAccessFallbackIds(): List<String> =
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        listOfNotNull(replyParentAccessIdentityOrNull()?.first)
    } else {
        listOfNotNull(sourceThreadId, streamChannelId, parentChannelId)
            .mapNotNull { id -> id.takeIf { it.isNotBlank() } }
            .distinct()
    }

internal fun RaftThreadScreenPayload.acceptsInitialConversationAccessCandidate(
    candidate: ConversationAccessCandidate
): Boolean {
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread) {
        return acceptsConversationAccessCandidate(candidate)
    }
    val (parentId, parentKind) = replyParentAccessIdentityOrNull() ?: return false
    if (parentId !in candidate.ids) return false
    return candidate.conversationKind == parentKind
}

internal fun RaftThreadScreenPayload.replyParentAccessIdentityOrNull(): Pair<String, ConversationKind>? {
    val routeIdentity = resolveThreadRouteIdentity()
    if (
        routeIdentity.surfaceKind != RaftThreadSurfaceKind.ReplyThread ||
        !routeIdentity.hasCompleteReplyIdentity
    ) {
        return null
    }
    val parentId = parentChannelId?.takeIf { it.isNotBlank() } ?: return null
    val parentMessage =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    if (routeIdentity.parentKey != "$parentId:$parentMessage") return null
    return parentId to (routeIdentity.parentConversationKind ?: return null)
}

private fun ConversationSummary.matchesHeaderFallbackId(ids: Set<String>): Boolean =
    headerFallbackIds().any { it in ids }

private fun ConversationSummary.headerFallbackIds(): Set<String> =
    listOfNotNull(
        threadId.value,
        channelId?.value,
        thread?.streamChannelId?.value
    ).filter { it.isNotBlank() }.toSet()

internal fun RaftThreadMessageItem.toMessage(
    threadId: ThreadId,
    channelId: ChannelId,
    currentUserId: String? = null,
    currentUserName: String? = null,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): Message? {
    val authorMemberKind =
        authorKind.strictMemberKindOrNull(
            source = "thread_item_author",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.ProjectedMessageAuthor,
        ) ?: return null
    // An External row without its frozen author (older cached rows) keeps rendering with the fallback author.
    val resolvedExternalAuthor =
        if (authorMemberKind == MemberKind.External) {
            externalAuthor
                ?: externalMessageAuthorOrFallback(null, authorName, "thread_item_author", id, diagnostics)
        } else {
            null
        }
    val resolvedMessageType = messageType.strictMessageTypeOrNull("thread_item_message", diagnostics) ?: return null
    val hasTaskProjection = taskId != null || taskNumber != null || taskStatus != null
    val resolvedTaskStatus =
        if (hasTaskProjection) taskStatus.strictTaskStatusOrNull("thread_item_task", diagnostics) else null
    val taskSummary =
        if (hasTaskProjection && resolvedTaskStatus != null) {
            TaskSummary(
                id = TaskId(taskId ?: "task-${taskNumber ?: id}"),
                title = body.ifBlank { taskNumber?.let { "Task #$it" } ?: "Task" },
                status = resolvedTaskStatus,
                taskNumber = taskNumber,
                messageId = MessageId(id),
                channelId = channelId,
                threadId = threadId,
                assignee = taskAssigneeId?.takeIf { it.isNotBlank() }?.let { id ->
                    val name = taskAssigneeName?.takeIf { it.isDisplaySafeTaskAssigneeName(id) } ?: id
                    taskAssigneeKind.strictMemberKindOrNull(
                        source = "thread_item_task_assignee",
                        diagnostics = diagnostics,
                        contract = MemberKindWireContract.Projected,
                    )
                        ?.let { kind ->
                            MemberProfile(
                                id = MemberId(id),
                                displayName = name,
                                handle = name.removePrefix("@"),
                                kind = kind,
                            )
                        }
                },
                updatedAtLabel = timestampLabel
            )
        } else {
            null
        }
    return Message(
        id = MessageId(id),
        threadId = threadId,
        channelId = channelId,
        threadChannelId = threadChannelId?.let(::ChannelId),
        author = toMemberProfile(authorMemberKind),
        seq = seq,
        timestampLabel = timestampLabel,
        createdAtEpochMillis = createdAtEpochMillis,
        body = body,
        messageType = resolvedMessageType,
        systemEventType = systemEventType,
        systemSubtype = systemSubtype,
        metadata = metadata.withLocalSendMetadata(pendingSendContext, pendingSendErrorCode, pendingSendErrorMessage),
        attachments = attachments.map { attachment -> attachment.toAttachment() },
        deliveryState = deliveryState.toMessageDeliveryState(),
        task = taskSummary,
        externalAuthor = resolvedExternalAuthor,
        delegatedBy = if (authorMemberKind == MemberKind.Human) delegatedBy else null,
        deletedAt = deletedAt,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.let(::MessageId),
        isThreadFollowed = isThreadFollowed,
        isSaved = isSaved,
        reactions = reactions.toMessageReactions(currentUserId, currentUserName),
        senderMembershipStatus = senderMembershipStatus,
        commentRef = commentRef?.toMessageCommentRef(),
        forwardBundle = forwardBundle,
        randomId = randomId,
        mentions = mentions,
        externalMentionSpans = if (authorMemberKind == MemberKind.External) externalMentionSpans else emptyList(),
        resolvedQuotes = resolvedQuotes
    )
}

private fun List<RaftThreadReactionItem>.toMessageReactions(
    currentUserId: String?,
    currentUserName: String?
): List<MessageReaction>? =
    mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        val reactorIds =
            reaction.reactorIds
                .plus(if (reaction.selected && currentUserId != null) listOf(currentUserId) else emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        val reactorNames =
            reaction.reactorNames
                .plus(if (reaction.selected && !currentUserName.isNullOrBlank()) listOf(currentUserName) else emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        MessageReaction(
            emoji = emoji,
            count = count,
            reactorIds = reactorIds,
            reactorNames = reactorNames
        )
    }.takeIf { it.isNotEmpty() }

private fun RaftThreadMessageItem.toMemberProfile(kind: MemberKind): MemberProfile {
    val cleanAuthorDeletedAt = authorDeletedAt?.takeIf { it.isNotBlank() }
    val cleanMembershipStatus =
        senderMembershipStatus?.takeIf { it.isNotBlank() }
            ?: if (authorKind.equals("agent", ignoreCase = true) && cleanAuthorDeletedAt != null) "deleted" else null
    val effectiveDescription =
        if (kind == MemberKind.External) {
            externalAuthor?.providerLabel() ?: authorDescription
        } else {
            authorDescription
        }
    // The row's own avatar first; an external author falls back to the avatar its provider sent.
    val effectiveAvatar =
        AvatarReference.fromWireValue(avatarWireValue)
            ?: externalAuthor?.avatarUrl?.takeIf { kind == MemberKind.External }?.let(AvatarReference::fromWireValue)
    return MemberProfile(
        id = MemberId(authorId),
        displayName = authorName,
        handle = authorName.removePrefix("@"),
        kind = kind,
        description = effectiveDescription,
        avatar = effectiveAvatar,
        avatarLabel = avatarLabel.ifBlank { authorName.toRaftAvatarLabel() },
        presence = Presence.Offline,
        membershipStatus = cleanMembershipStatus,
        agentDeletedAt = cleanAuthorDeletedAt
    )
}

private fun RaftThreadAttachmentItem.toAttachment(): Attachment =
    Attachment(
        id = AttachmentId(id),
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sizeLabel = sizeLabel,
        thumbnailUrl = thumbnailUrl,
        width = width,
        height = height,
        commentCount = commentCount
    )

internal fun StoredAuthSession.currentServerSlug(): String? =
    selectedServerSlug?.takeIf { it.isNotBlank() }
        ?: currentServerId
            ?.let { selectedId -> servers.firstOrNull { it.id == selectedId }?.slug }
            ?.takeIf { it.isNotBlank() }
        ?: servers.firstOrNull()?.slug?.takeIf { it.isNotBlank() }

private fun RaftThreadMessageCommentRef.toMessageCommentRef(): MessageCommentRef? {
    val cleanAttachmentId = attachmentId.takeIf { it.isNotBlank() } ?: return null
    val cleanFilename = filename.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentRef(
        attachmentId = AttachmentId(cleanAttachmentId),
        filename = cleanFilename,
        hostMessageId = hostMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        hostSource = hostSource?.toMessageCommentHostSource(),
        anchorLabel = anchorLabel?.takeIf { it.isNotBlank() }
    )
}

private fun RaftThreadMessageCommentHostSource.toMessageCommentHostSource(): MessageCommentHostSource? {
    val cleanType = type.commentHostTypeOrNull() ?: return null
    val cleanRouteKind = routeKind.commentHostRouteKindOrNull() ?: return null
    val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return null
    return MessageCommentHostSource(
        type = cleanType,
        routeKind = cleanRouteKind,
        channelId = ChannelId(cleanChannelId),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        rootThreadChannelId = rootThreadChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    )
}

internal fun Message.toRaftThreadMessageItem(currentUserId: String?): RaftThreadMessageItem {
    val pendingSendContext = metadata.localSendContextFromMetadata()
    val resolvedAvatar =
        resolveAvatarSlot(
            kind = author.kind,
            id = author.id.value,
            avatarWireValue = author.avatar?.wireValue
        )
    return RaftThreadMessageItem(
        id = id.value,
        authorId = author.id.value,
        authorName = author.displayName,
        authorHandle = author.handle,
        // The sender's description only: a role (Owner / Admin, an agent's runtime) is never
        // written on the author row (web #14535 / #14629).
        authorDescription = author.description?.takeIf { it.isNotBlank() },
        authorKind =
            when (author.kind) {
                MemberKind.Agent -> "agent"
                MemberKind.System -> "system"
                MemberKind.External -> "external"
                else -> "human"
            },
        isOwn = currentUserId != null && author.id.value == currentUserId,
        avatarLabel = author.avatarLabel,
        avatarWireValue = resolvedAvatar.effectiveAvatarWireValue,
        externalAuthor = externalAuthor,
        delegatedBy = delegatedBy,
        deletedAt = deletedAt,
        senderMembershipStatus =
            senderMembershipStatus?.takeIf { it.isNotBlank() }
                ?: author.membershipStatus?.takeIf { it.isNotBlank() },
        authorDeletedAt = author.agentDeletedAt?.takeIf { it.isNotBlank() },
        seq = seq,
        timestampLabel = timestampLabel,
        createdAtEpochMillis = createdAtEpochMillis,
        messageType = if (messageType == MessageType.System) "System" else "Chat",
        systemEventType = systemEventType,
        systemSubtype = systemSubtype,
        metadata = metadata.withoutLocalSendMetadata(),
        body = body,
        randomId = randomId?.takeIf { it.isNotBlank() } ?: pendingSendContext?.randomId?.takeIf { it.isNotBlank() },
        threadChannelId = threadChannelId?.value,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.value,
        isThreadFollowed = isThreadFollowed,
        isSaved = isSaved,
        taskId = task?.id?.value,
        taskNumber = task?.taskNumber,
        taskStatus = task?.status?.toWireValue(),
        taskAssigneeKind = task?.assignee?.kind?.memberKindWireValue(),
        taskAssigneeId = task?.assignee?.id?.value,
        taskAssigneeName = task?.assignee?.displayName,
        deliveryState = deliveryState.toRaftThreadDeliveryState(),
        attachments = attachments.map { attachment ->
            RaftThreadAttachmentItem(
                id = attachment.id.value,
                name = attachment.name,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                sizeLabel = attachment.sizeLabel,
                thumbnailUrl = attachment.thumbnailUrl,
                commentCount = attachment.commentCount,
                width = attachment.width,
                height = attachment.height
            )
        },
        reactions = reactions.toRealtimeThreadReactionItems(currentUserId).orEmpty(),
        commentRef = commentRef?.toRaftThreadCommentRef(),
        forwardBundle = forwardBundle,
        mentions = mentions,
        externalMentionSpans = externalMentionSpans,
        resolvedQuotes = resolvedQuotes,
        pendingSendContext = pendingSendContext,
        pendingSendErrorCode = metadata[ThreadLocalSendErrorMetadataKey]?.takeIf { it.isNotBlank() },
        pendingSendErrorMessage = metadata[ThreadLocalSendErrorMessageMetadataKey]?.takeIf { it.isNotBlank() }
    ).withoutRetractedContent()
}

private const val ThreadLocalSendContextMetadataKey = "localSend.context"
private const val ThreadLocalSendErrorMetadataKey = "localSend.errorCode"
private const val ThreadLocalSendErrorMessageMetadataKey = "localSend.errorMessage"

private val ThreadLocalSendMetadataJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

private fun Map<String, String>.withLocalSendMetadata(
    context: RaftThreadPendingSendContext?,
    errorCode: String?,
    errorMessage: String?
): Map<String, String> {
    val metadata = withoutLocalSendMetadata().toMutableMap()
    if (context != null) {
        metadata[ThreadLocalSendContextMetadataKey] = ThreadLocalSendMetadataJson.encodeToString(context)
    }
    if (!errorCode.isNullOrBlank()) {
        metadata[ThreadLocalSendErrorMetadataKey] = errorCode
    }
    if (!errorMessage.isNullOrBlank()) {
        metadata[ThreadLocalSendErrorMessageMetadataKey] = errorMessage
    }
    return metadata
}

private fun Map<String, String>.withoutLocalSendMetadata(): Map<String, String> =
    filterKeys { key ->
        key != ThreadLocalSendContextMetadataKey &&
            key != ThreadLocalSendErrorMetadataKey &&
            key != ThreadLocalSendErrorMessageMetadataKey
    }

private fun Map<String, String>.localSendContextFromMetadata(): RaftThreadPendingSendContext? =
    get(ThreadLocalSendContextMetadataKey)
        ?.let { value ->
            runCatching {
                ThreadLocalSendMetadataJson.decodeFromString<RaftThreadPendingSendContext>(value)
            }.getOrNull()
        }

private fun String.toMessageDeliveryState(): MessageDeliveryState =
    when (trim().lowercase()) {
        ThreadMessageDeliverySending -> MessageDeliveryState.Sending
        ThreadMessageDeliveryFailed -> MessageDeliveryState.Failed
        else -> MessageDeliveryState.Sent
    }

private fun MessageDeliveryState.toRaftThreadDeliveryState(): String =
    when (this) {
        MessageDeliveryState.Sending -> ThreadMessageDeliverySending
        MessageDeliveryState.Failed -> ThreadMessageDeliveryFailed
        MessageDeliveryState.Sent -> ThreadMessageDeliverySent
    }

private fun MessageCommentRef.toRaftThreadCommentRef(): RaftThreadMessageCommentRef =
    RaftThreadMessageCommentRef(
        attachmentId = attachmentId.value,
        filename = filename,
        hostMessageId = hostMessageId?.value,
        hostSource = hostSource?.toRaftThreadCommentHostSource(),
        anchorLabel = anchorLabel
    )

private fun MessageCommentHostSource.toRaftThreadCommentHostSource(): RaftThreadMessageCommentHostSource =
    RaftThreadMessageCommentHostSource(
        type = type,
        routeKind = routeKind,
        channelId = channelId.value,
        parentMessageId = parentMessageId?.value,
        threadChannelId = threadChannelId?.value,
        rootThreadChannelId = rootThreadChannelId?.value
    )

private fun TaskSummary.toRaftThreadTaskItem(): RaftThreadTaskItem =
    RaftThreadTaskItem(
        id = id.value,
        title = title,
        status = status.toWireValue(),
        taskNumber = taskNumber,
        messageId = messageId?.value,
        assigneeId = assignee?.id?.value,
        assigneeName = assignee?.displayName,
        updatedAtLabel = updatedAtLabel
    )

internal fun Throwable.safeThreadDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"

/** Resume catch-up retries after a retryable failure (feedback 4409cbc5): bounded, then the next resume asks again. */
internal const val RESUME_CATCH_UP_MAX_RETRIES = 3

internal fun resumeCatchUpRetryDelayMs(attempt: Int): Long = 2_000L shl attempt.coerceIn(0, 2)

internal fun ReplyCacheProvenance.storedConversationDetailOwner(): build.raft.app.data.StoredConversationDetailOwner =
    build.raft.app.data.StoredConversationDetailOwner(
        environmentId = environmentId,
        accountId = principalId,
        serverId = serverId,
    )

/** The conversation itself is gone for this account: not found, or a (non-session) permission loss. */
internal fun DataResult<*>.isConversationGone(): Boolean =
    when (this) {
        is DataResult.NoPermission -> !blocker.isAuthSessionBlocker() && !blocker.serverMessage.isEdgeBlockedMarker()
        is DataResult.Error -> error.code == "http_404"
        else -> false
    }


/** Message storage Phase 2b: a captured REST page as the durable writer's [ThreadDurableEnteredPage]. */
internal fun RawMessagePage.toEnteredPage(channelId: String, channelType: String?): ThreadDurableEnteredPage =
    ThreadDurableEnteredPage(
        channelId = channelId,
        rawMessages = messages,
        requestedLimit = requestedLimit,
        messageWindow = messageWindow,
        historyLimited = historyLimited,
        channelType = channelType?.takeIf { it.isNotBlank() },
        replyCountByParentId = replyCountsByParentId,
    )

/** How a [SharedThreadStore.jumpToLatest] ended, for the Back to Bottom button. */
internal enum class ThreadLatestJumpOutcome(val diagnosticName: String) {
    /** The canonical tail is on screen (it was already, or it loaded): scroll to the bottom. */
    Landed("applied"),

    /** The tail could not be loaded: the button comes back and the page says so. */
    Failed("failed"),

    /** Another jump, a route change or a refresh replaced this one: nothing to say. */
    Superseded("superseded"),

    /** A jump to the tail was already loading: this call started nothing. */
    AlreadyLoading("ignored"),
}

/** A jump to [messageId] that could not load the messages around it ([SharedThreadStore.jumpFailures]). */
internal data class ThreadJumpFailure(
    val messageId: String,
    val switchToTab: String?,
    val unreadThreadPill: Boolean,
)
