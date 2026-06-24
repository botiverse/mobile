package ai.slock.compose

internal data class ThreadRouteIdentity(
    val surfaceKind: String,
    val instanceKey: String,
    val timelineKey: String?,
    val canRenderTimelineCandidates: Boolean
)

internal fun KuiklyThreadScreenPayload.resolveThreadRouteIdentity(): ThreadRouteIdentity {
    val surface = surfaceKind.takeIf { it.isNotBlank() } ?: KuiklyThreadSurfaceKind.Channel
    val parentChannel = parentChannelId.nonBlank()
    val parentMessage = parentMessageId.nonBlank() ?: originParentMessageId.nonBlank()
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
