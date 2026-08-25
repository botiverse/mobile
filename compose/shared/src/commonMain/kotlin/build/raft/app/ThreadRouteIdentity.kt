package build.raft.app

import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.messageStreamChannelId

internal data class ThreadRouteIdentity(
    val surfaceKind: String,
    val channelKind: String? = null,
    val parentConversationKind: ConversationKind? = null,
    val instanceKey: String,
    val timelineKey: String?,
    val parentKey: String? = null,
    val canRenderTimelineCandidates: Boolean
) {
    val timelineChannelId: String?
        get() = timelineKey?.takeIf { it.isNotBlank() }

    val routeKind: String
        get() =
            if (surfaceKind == KuiklyThreadSurfaceKind.Channel) {
                channelKind.normalizedThreadChannelKind() ?: KuiklyThreadSurfaceKind.Channel
            } else {
                surfaceKind
            }

    val hasCompleteReplyIdentity: Boolean
        get() =
            surfaceKind != KuiklyThreadSurfaceKind.ReplyThread ||
                (parentKey != null && parentConversationKind != null)
}

/**
 * Structural guard for rows crossing the durable pending-send boundary.
 * Route identity is persisted as strings for SQL portability, so do not let an
 * unknown/corrupt surface or parent kind become a new wildcard route merely
 * because it happens to compare equal to a similarly malformed payload.
 */
internal fun ThreadRouteIdentity.isValidPendingSendRoute(): Boolean {
    if (surfaceKind !in setOf(
            KuiklyThreadSurfaceKind.Channel,
            KuiklyThreadSurfaceKind.DirectMessage,
            KuiklyThreadSurfaceKind.ReplyThread,
        )
    ) return false
    if (instanceKey.isBlank()) return false
    if (timelineKey.isNullOrBlank() != !canRenderTimelineCandidates) return false
    return when (surfaceKind) {
        KuiklyThreadSurfaceKind.Channel ->
            channelKind.normalizedThreadChannelKind() != null &&
                parentConversationKind == null &&
                parentKey == null
        KuiklyThreadSurfaceKind.DirectMessage ->
            channelKind == null &&
                parentConversationKind == null &&
                parentKey == null
        KuiklyThreadSurfaceKind.ReplyThread ->
            channelKind == null &&
                parentConversationKind in setOf(
                    ConversationKind.Channel,
                    ConversationKind.Joint,
                    ConversationKind.DirectMessage,
                ) &&
                !parentKey.isNullOrBlank()
        else -> false
    }
}

/**
 * Equality used by the pending-send journal and send authority fence.
 *
 * A reply route can be admitted before the server resolves its stream id
 * (`reply-parent:<channel>:<message>`) and later be observed with the
 * canonical stream (`reply-stream:<thread>`).  Those two identities are the
 * same send surface only when their typed parent is the same.  Once both
 * sides have a stream, however, a different stream is a hard identity
 * mismatch even if the parent happens to match.  Channel and DM routes do
 * not have this unresolved/resolved equivalence and therefore require exact
 * identity equality.
 */
internal fun ThreadRouteIdentity.isPendingSendEquivalentTo(
    other: ThreadRouteIdentity,
): Boolean {
    if (!isValidPendingSendRoute() || !other.isValidPendingSendRoute()) return false
    if (surfaceKind != other.surfaceKind) return false
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return this == other
    // A pending send must never be admitted or consumed from an untyped reply
    // parent.  Treating null as a wildcard would let a Channel-shaped route
    // and a DM-shaped route share the same local row when one side has lost its
    // typed parent metadata during a rebuild.
    if (!hasCompleteReplyIdentity || !other.hasCompleteReplyIdentity) return false
    if (this == other) return true
    if (
        parentConversationKind != other.parentConversationKind
    ) {
        return false
    }
    if (parentKey != null && other.parentKey != null && parentKey != other.parentKey) return false
    val leftStream = timelineKey?.takeIf { it.isNotBlank() }
    val rightStream = other.timelineKey?.takeIf { it.isNotBlank() }
    if (leftStream != null && rightStream != null) return leftStream == rightStream
    // An unresolved route may safely become resolved only through the same
    // typed parent identity.  A route with no parent proof cannot be
    // upgraded by a stream observation.
    return (leftStream == null || rightStream == null) &&
        parentKey != null &&
        parentKey == other.parentKey
}

internal fun ThreadRouteIdentity.canRetainThreadPageFor(incoming: ThreadRouteIdentity): Boolean {
    if (this == incoming) return true
    if (routeKind != incoming.routeKind) return false
    if (instanceKey == incoming.instanceKey) return true
    if (
        surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
        incoming.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
    ) {
        val sameParent = parentKey != null && parentKey == incoming.parentKey
        val sameTimeline = timelineKey != null && timelineKey == incoming.timelineKey
        return sameParent || sameTimeline
    }
    return false
}

internal fun KuiklyThreadScreenPayload.resolveThreadRouteIdentity(): ThreadRouteIdentity {
    val parentMessage = parentMessageId.nonBlank() ?: originParentMessageId.nonBlank()
    return resolveThreadRouteIdentity(
        surfaceKind = surfaceKind,
        channelKind = channelKind,
        instanceId = instanceId,
        sourceThreadId = sourceThreadId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessage,
        parentConversationKind =
            typedReplyParentConversationKindOrNull(parentSurfaceKind, parentChannelKind)
    )
}

internal fun ConversationSummary.resolveThreadRouteIdentity(): ThreadRouteIdentity {
    val isReplyThread = kind == ConversationKind.Thread
    val surface =
        when (kind) {
            ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
            ConversationKind.Thread -> KuiklyThreadSurfaceKind.ReplyThread
            else -> KuiklyThreadSurfaceKind.Channel
        }
    val streamChannelId =
        if (isReplyThread) {
            messageStreamChannelId().value
        } else {
            channelId?.value ?: threadId.value
        }
    return resolveThreadRouteIdentity(
        surfaceKind = surface,
        channelKind = conversationThreadChannelKind(),
        instanceId = null,
        sourceThreadId = threadId.value,
        streamChannelId = streamChannelId,
        parentChannelId = if (isReplyThread) thread?.parentChannelId?.value else null,
        parentMessageId = if (isReplyThread) thread?.parentMessageId?.value else null,
        parentConversationKind =
            if (isReplyThread) {
                typedReplyParentConversationKindOrNull(
                    thread?.parentSurfaceKind,
                    thread?.parentChannelKind
                )
            } else {
                null
            }
    )
}

internal fun resolveThreadRouteIdentity(
    surfaceKind: String,
    channelKind: String?,
    instanceId: String?,
    sourceThreadId: String?,
    streamChannelId: String?,
    parentChannelId: String?,
    parentMessageId: String?,
    parentConversationKind: ConversationKind? = null
): ThreadRouteIdentity {
    val surface = surfaceKind.takeIf { it.isNotBlank() } ?: KuiklyThreadSurfaceKind.Channel
    val routeKind =
        if (surface == KuiklyThreadSurfaceKind.Channel) {
            channelKind.normalizedThreadChannelKind() ?: KuiklyThreadSurfaceKind.Channel
        } else {
            surface
        }
    val parentChannel = parentChannelId.nonBlank()
    val parentMessage = parentMessageId.nonBlank()
    val parentKey =
        if (parentChannel != null && parentMessage != null) {
            "$parentChannel:$parentMessage"
        } else {
            null
        }
    val isReplyThread =
        surface == KuiklyThreadSurfaceKind.ReplyThread ||
            (parentChannel != null && parentMessage != null)
    val streamCandidate =
        streamChannelId.nonBlank()
            ?: sourceThreadId.nonBlank()
    return if (isReplyThread) {
        val resolvedStream =
            streamCandidate?.takeUnless { parentChannel != null && it == parentChannel }
        when {
            resolvedStream != null ->
                ThreadRouteIdentity(
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    channelKind = null,
                    parentConversationKind = parentConversationKind,
                    instanceKey = "reply-stream:$resolvedStream",
                    timelineKey = resolvedStream,
                    parentKey = parentKey,
                    canRenderTimelineCandidates = true
                )
            parentChannel != null && parentMessage != null ->
                ThreadRouteIdentity(
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    channelKind = null,
                    parentConversationKind = parentConversationKind,
                    instanceKey = "reply-parent:$parentChannel:$parentMessage",
                    timelineKey = null,
                    parentKey = parentKey,
                    canRenderTimelineCandidates = false
                )
            else ->
                ThreadRouteIdentity(
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    channelKind = null,
                    parentConversationKind = parentConversationKind,
                    instanceKey = instanceId.nonBlank() ?: "reply-pending",
                    timelineKey = null,
                    parentKey = parentKey,
                    canRenderTimelineCandidates = false
                )
        }
    } else {
        val timelineKey = streamCandidate
        ThreadRouteIdentity(
            surfaceKind = surface,
            channelKind = routeKind.takeIf { surface == KuiklyThreadSurfaceKind.Channel },
            parentConversationKind = null,
            instanceKey = timelineKey?.let { "$routeKind:$it" } ?: instanceId.nonBlank() ?: "$routeKind:pending",
            timelineKey = timelineKey,
            parentKey = parentKey,
            canRenderTimelineCandidates = timelineKey != null
        )
    }
}

private fun ConversationSummary.conversationThreadChannelKind(): String? =
    when (kind) {
        ConversationKind.Joint -> "joint"
        ConversationKind.Channel -> channelType.normalizedThreadChannelKind() ?: KuiklyThreadSurfaceKind.Channel
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        else -> channelType.normalizedThreadChannelKind()
    }
