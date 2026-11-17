package build.raft.app

import build.raft.app.model.MessageId
import build.raft.app.model.ThreadUpdated

// Pure payload/value transforms used by [SharedThreadStore]: no store state, no logging, no effects.

internal fun RaftThreadScreenPayload.realtimeGapRouteIdentity(): ThreadRouteIdentity =
    resolveThreadRouteIdentity()

/**
 * Classifies the reply-thread parent write authority after an access
 * resolution pass. Non-reply surfaces never carry parent authority.
 */
internal fun resolveReplyParentAuthorityStatus(
    accessResolution: ThreadAccessResolution,
    payload: RaftThreadScreenPayload,
    candidate: ConversationAccessCandidate?,
): ReplyParentAuthorityStatus {
    if (payload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) {
        return ReplyParentAuthorityStatus.Writable
    }
    return when {
        accessResolution == ThreadAccessResolution.NoPermission ||
            accessResolution == ThreadAccessResolution.Error ->
            ReplyParentAuthorityStatus.Denied
        candidate != null && payload.acceptsInitialConversationAccessCandidate(candidate) ->
            if (payload.joined && payload.canSend) {
                ReplyParentAuthorityStatus.Writable
            } else {
                ReplyParentAuthorityStatus.Denied
            }
        else ->
            // This classification only runs from the apply pass, which by
            // definition completes the initial home access lookup.
            if (payload.hasReadableCanonicalReplyContent()) {
                ReplyParentAuthorityStatus.Unknown
            } else {
                ReplyParentAuthorityStatus.Resolving
            }
    }
}

internal fun RaftThreadScreenPayload.loadedReplyTimelineCount(): Int =
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        val parentId = originParentMessageId?.takeIf { it.isNotBlank() }
        messages.count { message -> message.id != parentId }
    } else {
        messages.size
    }

internal fun RaftThreadScreenPayload.knownReplyCountFor(
    event: ThreadUpdated,
    activeReplyParentMessageId: MessageId?
): Int? {
    val parentIds =
        listOfNotNull(
            originParentMessageId?.takeIf { it.isNotBlank() },
            parentMessageId?.takeIf { it.isNotBlank() },
            activeReplyParentMessageId?.value,
            event.parentMessageId.value
        ).toSet()
    return (listOfNotNull(originMessage) + messages)
        .filter { message -> message.id in parentIds }
        .map { message -> message.replyCount }
        .maxOrNull()
}

/** The thread parents loaded in [payload] when it is a channel's timeline, else none. */
internal fun loadedChannelThreadParents(payload: RaftThreadScreenPayload): Map<String, ThreadUnreadThreadParent> {
    val routeIdentity = payload.resolveThreadRouteIdentity()
    val channelId =
        routeIdentity.timelineChannelId?.takeIf { routeIdentity.surfaceKind == RaftThreadSurfaceKind.Channel }
            ?: return emptyMap()
    return payload.messages.toLoadedThreadParents(channelId)
}

internal fun ThreadAccessResolution.refusesReferenceTarget(): Boolean =
    this == ThreadAccessResolution.NoPermission || this == ThreadAccessResolution.Error
