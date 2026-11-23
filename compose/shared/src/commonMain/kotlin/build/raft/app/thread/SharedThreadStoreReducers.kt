package build.raft.app

import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
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

/**
 * The conversation [event]'s parent message lives in: the one its reply-window envelope names
 * (server `parentMsgForThread.channelId`), else this page's parent conversation when the page is
 * that parent's reply thread. Null when neither says.
 */
internal fun RaftThreadScreenPayload.threadUpdatedParentChannelId(event: ThreadUpdated): String? {
    event.syncCoreReplyWindow?.parentScopeId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread) return null
    val pageParentMessageId =
        parentMessageId?.takeIf { it.isNotBlank() } ?: originParentMessageId?.takeIf { it.isNotBlank() }
    return parentChannelId?.takeIf { it.isNotBlank() && pageParentMessageId == event.parentMessageId.value }
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

/**
 * Projects a reply thread's parent directory row from [homeState] into an
 * [SharedThreadIntent.ApplyParentDirectoryHint]. Only a Home snapshot belonging to
 * [expectedServerId] (when known) may attest parent membership or absence, preventing a
 * cross-server or pre-refresh snapshot from downgrading a writable reply thread or marking
 * its parent deleted.
 */
internal fun resolveReplyParentDirectoryHint(
    payload: RaftThreadScreenPayload,
    homeState: SharedHomeState,
    expectedServerId: String?,
    loungeLookup: (serverId: String?, parentChannelId: String) -> RaftAgentLounge? = { serverId, parentId ->
        serverId?.let { RaftAgentLounges.lounge(it, parentId) } ?: RaftAgentLounges.loungeAnyServer(parentId)
    },
): SharedThreadIntent.ApplyParentDirectoryHint? {
    if (payload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return null
    val parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return null
    val parentMessageId =
        payload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    val normalizedServerId = expectedServerId?.trim()?.takeIf { it.isNotEmpty() }
    val homeContent =
        if (normalizedServerId != null) {
            (homeState.content as? SharedHomeContentState.Content)?.takeIf { content ->
                content.baseSnapshot.server.id.value == normalizedServerId
            }
        } else {
            null
        }
    val snapshot = homeContent?.baseSnapshot
    val parentRow =
        snapshot?.channels?.firstOrNull { it.channelId?.value == parentChannelId }
            ?: snapshot?.directMessages?.firstOrNull { it.channelId?.value == parentChannelId }
    val parentLounge = if (parentRow == null) loungeLookup(normalizedServerId, parentChannelId) else null
    if (parentLounge != null) {
        return SharedThreadIntent.ApplyParentDirectoryHint(
            parentChannelId = parentChannelId,
            parentMessageId = parentMessageId,
            present = true,
            joined = parentLounge.postable,
            canSend = parentLounge.postable,
            authoritative = true,
        )
    }
    return SharedThreadIntent.ApplyParentDirectoryHint(
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        present = parentRow != null,
        joined = parentRow?.joined == true,
        canSend = parentRow?.permissions?.canSend == true && parentRow.archived == false,
        // Only a live network snapshot for this server (not the durable identity cache,
        // and not one still awaiting refresh) may attest that a missing or unjoined parent row
        // is authoritative.
        authoritative =
            homeContent != null &&
                !homeContent.isDurableIdentityCache &&
                !homeContent.needsNetworkRefresh,
    )
}

/**
 * Keeps [existingCandidate] synchronized when the user explicitly joins or leaves [channelId]
 * from this page so a subsequent [SharedThreadIntent.Refresh] cannot re-apply a stale pre-join
 * `joined = false` candidate onto a reply thread or task conversation.
 */
internal fun updatedMembershipAccessCandidate(
    payload: RaftThreadScreenPayload,
    existingCandidate: ConversationAccessCandidate?,
    channelId: String,
    joined: Boolean,
): ConversationAccessCandidate? {
    if (existingCandidate != null && channelId in existingCandidate.ids) {
        val writable = joined && !existingCandidate.archived
        return existingCandidate.copy(
            joined = joined,
            permissions =
                existingCandidate.permissions.copy(
                    canSend = writable,
                    canAttach = writable,
                    canCreateTask = writable && !payload.isTaskConversationSurface(),
                ),
        )
    }
    if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread && payload.parentChannelId == channelId) {
        val conversationKind = payload.resolveThreadRouteIdentity().parentConversationKind ?: ConversationKind.Channel
        val archived = payload.channelArchived
        val writable = joined && !archived
        val synthesized =
            ConversationAccessCandidate(
                ids = setOf(channelId),
                conversationKind = conversationKind,
                surfaceKind = "channel",
                joined = joined,
                archived = archived,
                permissions =
                    ConversationPermissions(
                        canRead = true,
                        canSend = writable,
                        canAttach = writable,
                        canCreateTask = writable,
                    ),
            )
        if (payload.acceptsInitialConversationAccessCandidate(synthesized)) {
            return synthesized
        }
    }
    return existingCandidate
}
