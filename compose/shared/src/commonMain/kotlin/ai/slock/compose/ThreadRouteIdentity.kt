package ai.slock.compose

import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.messageStreamChannelId

internal data class ThreadRouteIdentity(
    val surfaceKind: String,
    val channelKind: String? = null,
    val instanceKey: String,
    val timelineKey: String?,
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
        parentMessageId = parentMessage
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
        parentMessageId = if (isReplyThread) thread?.parentMessageId?.value else null
    )
}

private fun resolveThreadRouteIdentity(
    surfaceKind: String,
    channelKind: String?,
    instanceId: String?,
    sourceThreadId: String?,
    streamChannelId: String?,
    parentChannelId: String?,
    parentMessageId: String?
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
                    instanceKey = "reply-stream:$resolvedStream",
                    timelineKey = resolvedStream,
                    canRenderTimelineCandidates = true
                )
            parentChannel != null && parentMessage != null ->
                ThreadRouteIdentity(
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    channelKind = null,
                    instanceKey = "reply-parent:$parentChannel:$parentMessage",
                    timelineKey = null,
                    canRenderTimelineCandidates = false
                )
            else ->
                ThreadRouteIdentity(
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    channelKind = null,
                    instanceKey = instanceId.nonBlank() ?: "reply-pending",
                    timelineKey = null,
                    canRenderTimelineCandidates = false
                )
        }
    } else {
        val timelineKey = streamCandidate
        ThreadRouteIdentity(
            surfaceKind = surface,
            channelKind = routeKind.takeIf { surface == KuiklyThreadSurfaceKind.Channel },
            instanceKey = timelineKey?.let { "$routeKind:$it" } ?: instanceId.nonBlank() ?: "$routeKind:pending",
            timelineKey = timelineKey,
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
