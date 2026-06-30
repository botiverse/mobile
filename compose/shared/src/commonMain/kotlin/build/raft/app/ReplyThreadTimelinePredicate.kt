package build.raft.app

internal fun KuiklyThreadScreenPayload.replyThreadContentChannelId(): String? {
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
    return streamChannelId?.takeIf { it.isNotBlank() }
        ?: sourceThreadId?.takeIf { it.isNotBlank() }
}

internal fun matchesResolvedReplyThreadTimeline(
    resolvedThreadChannelId: String,
    messageChannelId: String?,
    messageThreadId: String?
): Boolean {
    val threadChannelId = resolvedThreadChannelId.takeIf { it.isNotBlank() } ?: return false
    return messageChannelId?.takeIf { it.isNotBlank() } == threadChannelId ||
        messageThreadId?.takeIf { it.isNotBlank() } == threadChannelId
}
