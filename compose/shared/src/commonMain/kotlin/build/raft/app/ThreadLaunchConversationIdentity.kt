package build.raft.app

import build.raft.app.data.HomeConversationRow
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.MessageId
import build.raft.app.model.threadIdentity
import build.raft.app.model.topLevelThreadId

internal data class ThreadLaunchConversationIdentity(
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val channelType: String? = null,
    val thread: ThreadConversation? = null,
    val threadStreamChannelIdHint: ChannelId? = null,
    val kind: ConversationKind,
    val dmPeerIdentity: MemberDetailIdentity? = null
)

internal fun buildThreadLaunchConversationIdentity(
    kind: ConversationKind,
    channelId: ChannelId,
    channelType: String? = null,
    threadChannelId: ChannelId? = null,
    parentChannelId: ChannelId? = null,
    parentMessageId: MessageId? = null,
    parentSurfaceKind: String? = null,
    parentChannelKind: String? = null,
    dmPeerIdentity: MemberDetailIdentity? = null
): ThreadLaunchConversationIdentity {
    val isReplyThread = kind == ConversationKind.Thread
    val stableThreadIdentity =
        if (isReplyThread && parentChannelId != null && parentMessageId != null) {
            threadIdentity(parentChannelId, parentMessageId)
        } else {
            null
        }
    return ThreadLaunchConversationIdentity(
        threadId = stableThreadIdentity ?: topLevelThreadId(channelId),
        channelId = if (isReplyThread) parentChannelId else channelId,
        channelType = if (isReplyThread) null else channelType,
        thread =
            if (isReplyThread && parentChannelId != null && parentMessageId != null) {
                ThreadConversation(
                    parentChannelId = parentChannelId,
                    parentMessageId = parentMessageId,
                    streamChannelId = threadChannelId,
                    parentSurfaceKind = parentSurfaceKind.parsedThreadParentSurfaceKindOrNull(),
                    parentChannelKind = parentChannelKind.normalizedThreadChannelKind()
                )
            } else {
                null
            },
        kind = kind,
        dmPeerIdentity = dmPeerIdentity.takeIf { kind == ConversationKind.DirectMessage }
    )
}

/**
 * Build a launch identity from a directory projection. Returns null for a reply thread
 * whose parent conversation cannot be resolved — the caller (via [resolveOpenRequest])
 * then decides drop vs. trusted raw rebuild instead of silently assuming Channel and
 * misrouting a DM thread onto the channel surface (#415, t5/#419).
 *
 * Kind + parent surface/channel kind route through the #417 contract
 * ([parseConversationKindOrNull] / [toRaftSurfaceKind] / [toWireChannelKindOrNull])
 * instead of the old scattered converters and `?: Channel` fallbacks.
 */
internal fun SharedConversationDirectoryProjection.toThreadLaunchConversationIdentity(
    directory: SharedConversationDirectoryState
): ThreadLaunchConversationIdentity? {
    val isReplyThread = surfaceKind == RaftThreadSurfaceKind.ReplyThread
    val parentProjection =
        if (isReplyThread) {
            parentChannelId
                ?.let(directory::resolve)
                // The parent id is also an alias of the reply thread's OWN projection, so a
                // resolve can return the thread itself. A parent is never a reply thread, and
                // never the thread's own canonical id — treat either as an unresolved parent.
                ?.takeUnless {
                    it.surfaceKind == RaftThreadSurfaceKind.ReplyThread ||
                        it.canonicalChannelId == canonicalChannelId
                }
        } else {
            null
        }
    if (isReplyThread && parentProjection == null) {
        // Parent conversation isn't resolvable; its DM-vs-channel shape is unknowable. Drop
        // rather than misroute — the caller applies the two-tier drop policy.
        return null
    }
    val kind =
        when (surfaceKind) {
            RaftThreadSurfaceKind.DirectMessage -> ConversationKind.DirectMessage
            RaftThreadSurfaceKind.ReplyThread -> ConversationKind.Thread
            // Channel surface: Joint vs Channel comes from channelKind; an unparsable
            // channelKind on a channel surface is genuinely a channel (product default,
            // not a parent-kind fallback).
            else -> parseConversationKindOrNull(channelKind) ?: ConversationKind.Channel
        }
    val parentKind =
        parentProjection?.let {
            parseConversationKindOrNull(it.channelKind) ?: parseConversationKindOrNull(it.surfaceKind)
        }
    return buildThreadLaunchConversationIdentity(
        kind = kind,
        channelId = ChannelId(canonicalChannelId),
        channelType = channelKind.takeIf { it.isNotBlank() },
        threadChannelId = canonicalChannelId.takeIf { it.isNotBlank() }?.let(::ChannelId),
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        parentSurfaceKind = parentKind?.toRaftSurfaceKind(),
        parentChannelKind = parentKind?.toWireChannelKindOrNull(),
        dmPeerIdentity = peerIdentity
    )
}

/**
 * The reply-thread origin fallback carried by a directory projection. Shared by the
 * foreground banner and the notification-tap open paths, which build identical
 * `HomeMessageOpenRequest.originFallback` values from the same projection fields — the
 * nullable receiver subsumes both (the notification caller passes a non-null projection).
 */
internal fun SharedConversationDirectoryProjection?.toThreadLaunchOriginFallback(): ThreadLaunchOriginFallback? {
    if (this?.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return null
    val parentId = parentMessageId?.takeIf { it.isNotBlank() } ?: return null
    return ThreadLaunchOriginFallback(
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() },
        parentMessageId = parentId,
        preview = subtitle?.takeIf { it.isNotBlank() },
        senderId = null,
        senderKind = null,
        expected = true
    )
}

internal fun ConversationSummary.toThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity =
    ThreadLaunchConversationIdentity(
        threadId = threadId,
        channelId = channelId,
        channelType = channelType,
        thread = thread,
        kind = kind,
        dmPeerIdentity = directMessagePeerIdentity()
    )

internal fun HomeConversationRow.toThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity =
    ThreadLaunchConversationIdentity(
        threadId = threadId,
        channelId = channelId,
        channelType = channelType,
        thread = thread,
        kind = kind,
        dmPeerIdentity = directMessagePeerIdentity()
    )

private fun ConversationSummary.directMessagePeerIdentity(): MemberDetailIdentity? =
    directMessagePeerIdentity(kind, presenceMemberId?.value, directMessagePeerType)

private fun HomeConversationRow.directMessagePeerIdentity(): MemberDetailIdentity? =
    directMessagePeerIdentity(kind, presenceMemberId?.value, directMessagePeerType)

private fun directMessagePeerIdentity(
    kind: ConversationKind,
    memberId: String?,
    peerType: String?
): MemberDetailIdentity? =
    if (kind == ConversationKind.DirectMessage) {
        memberDetailIdentityFromWire(
            memberId = memberId,
            kindWireValue = peerType,
            source = MemberDetailIdentitySource.DirectoryDmPeer
        )
    } else {
        null
    }

internal fun ThreadLaunchConversationIdentity.resolveThreadRouteIdentity(): ThreadRouteIdentity {
    val surfaceKind = threadSurfaceKind()
    val isReplyThread = surfaceKind == RaftThreadSurfaceKind.ReplyThread
    return resolveThreadRouteIdentity(
        surfaceKind = surfaceKind,
        channelKind = threadRouteChannelKind(),
        instanceId = null,
        sourceThreadId = threadId.value,
        streamChannelId =
            if (isReplyThread) {
                threadRouteStreamChannelId()
            } else {
                threadStreamChannelId()
            },
        parentChannelId = if (isReplyThread) threadParentChannelId() else null,
        parentMessageId = if (isReplyThread) threadParentMessageId() else null,
        parentConversationKind =
            if (isReplyThread) {
                typedReplyParentConversationKindOrNull(
                    threadParentSurfaceKind(),
                    threadParentChannelKind()
                )
            } else {
                null
            }
    )
}

internal fun ThreadLaunchConversationIdentity.threadSurfaceKind(): String =
    kind.toRaftSurfaceKind()

internal fun ThreadLaunchConversationIdentity.usesMemberScopedMentions(): Boolean =
    kind == ConversationKind.DirectMessage ||
        kind == ConversationKind.Joint ||
        channelType.isMemberScopedThreadChannelType()

internal fun ThreadLaunchConversationIdentity.threadStreamChannelId(): String? =
    threadStreamChannelIdHint?.value
        ?: thread?.streamChannelId?.value
        ?: if (kind == ConversationKind.Thread) {
            null
        } else {
            channelId?.value ?: threadId.value
        }

private fun ThreadLaunchConversationIdentity.threadRouteStreamChannelId(): String? =
    threadStreamChannelIdHint?.value
        ?: thread?.streamChannelId?.value
        ?: if (kind == ConversationKind.Thread) {
            threadId.value
        } else {
            channelId?.value ?: threadId.value
        }

internal fun ThreadLaunchConversationIdentity.threadParentChannelId(): String? =
    thread?.parentChannelId?.value

internal fun ThreadLaunchConversationIdentity.threadParentMessageId(): String? =
    thread?.parentMessageId?.value

internal fun ThreadLaunchConversationIdentity.threadParentSurfaceKind(): String? =
    thread?.parentSurfaceKind.parsedThreadParentSurfaceKindOrNull()
        ?: if (kind == ConversationKind.Thread) {
            channelType.parsedThreadParentSurfaceKindOrNull()
        } else {
            null
        }

internal fun ThreadLaunchConversationIdentity.threadParentChannelKind(): String? =
    thread?.parentChannelKind.normalizedThreadChannelKind()
        ?: if (kind == ConversationKind.Thread) {
            channelType.normalizedThreadChannelKind()
        } else {
            null
        }

internal fun ThreadLaunchConversationIdentity.threadRouteChannelKind(): String? =
    when (kind) {
        ConversationKind.Joint -> "joint"
        // The kind IS Channel here; the `?: Channel` is that tautology made explicit for a
        // blank channelType (product default, not a parent-kind parse fallback — C-group).
        ConversationKind.Channel -> channelType.normalizedThreadChannelKind() ?: RaftThreadSurfaceKind.Channel
        ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
        else -> channelType.normalizedThreadChannelKind()
    }
