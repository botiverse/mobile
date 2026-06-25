package ai.slock.compose

import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.messageStreamChannelId

internal data class ThreadRouteIdentity(
    val surfaceKind: String,
    val instanceKey: String,
    val timelineKey: String?,
    val canRenderTimelineCandidates: Boolean
) {
    val timelineChannelId: String?
        get() = timelineKey?.takeIf { it.isNotBlank() }
}

internal fun KuiklyThreadScreenPayload.resolveThreadRouteIdentity(): ThreadRouteIdentity {
    val parentMessage = parentMessageId.nonBlank() ?: originParentMessageId.nonBlank()
    return resolveThreadRouteIdentity(
        surfaceKind = surfaceKind,
        instanceId = instanceId,
        sourceThreadId = sourceThreadId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessage
    )
}

internal fun ConversationSummary.resolveThreadRouteIdentity(): ThreadRouteIdentity {
    val surface =
        when (kind) {
            ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
            ConversationKind.Thread -> KuiklyThreadSurfaceKind.ReplyThread
            else -> KuiklyThreadSurfaceKind.Channel
        }
    return resolveThreadRouteIdentity(
        surfaceKind = surface,
        instanceId = null,
        sourceThreadId = threadId.value,
        streamChannelId = messageStreamChannelId().value,
        parentChannelId = thread?.parentChannelId?.value,
        parentMessageId = thread?.parentMessageId?.value
    )
}

private fun resolveThreadRouteIdentity(
    surfaceKind: String,
    instanceId: String?,
    sourceThreadId: String?,
    streamChannelId: String?,
    parentChannelId: String?,
    parentMessageId: String?
): ThreadRouteIdentity {
    val surface = surfaceKind.takeIf { it.isNotBlank() } ?: KuiklyThreadSurfaceKind.Channel
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
                    instanceKey = "reply-stream:$resolvedStream",
                    timelineKey = resolvedStream,
                    canRenderTimelineCandidates = true
                )
            parentChannel != null && parentMessage != null ->
                ThreadRouteIdentity(
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    instanceKey = "reply-parent:$parentChannel:$parentMessage",
                    timelineKey = null,
                    canRenderTimelineCandidates = false
                )
            else ->
                ThreadRouteIdentity(
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    instanceKey = instanceId.nonBlank() ?: "reply-pending",
                    timelineKey = null,
                    canRenderTimelineCandidates = false
                )
        }
    } else {
        val timelineKey = streamCandidate
        ThreadRouteIdentity(
            surfaceKind = surface,
            instanceKey = timelineKey?.let { "$surface:$it" } ?: instanceId.nonBlank() ?: "$surface:pending",
            timelineKey = timelineKey,
            canRenderTimelineCandidates = timelineKey != null
        )
    }
}

private fun String?.nonBlank(): String? =
    this?.takeIf { it.isNotBlank() }
