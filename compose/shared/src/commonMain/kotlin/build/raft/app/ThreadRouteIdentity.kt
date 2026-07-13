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
