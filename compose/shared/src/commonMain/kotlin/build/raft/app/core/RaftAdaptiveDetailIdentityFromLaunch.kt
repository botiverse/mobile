package build.raft.app

internal fun WireThreadLaunchPayload.toRaftAdaptiveDetailIdentity(
    serverId: String,
): RaftAdaptiveDetailIdentity? {
    val normalizedServerId = serverId.trim().takeIf(String::isNotBlank) ?: return null
    val resolvedThreadId =
        sourceThreadId?.trim()?.takeIf(String::isNotBlank)
            ?: streamChannelId?.trim()?.takeIf(String::isNotBlank)
            ?: threadId?.trim()?.takeIf(String::isNotBlank)
            ?: parentMessageId?.trim()?.takeIf(String::isNotBlank)
            ?: return null
    return RaftAdaptiveDetailIdentity(
        serverId = normalizedServerId,
        threadId = resolvedThreadId,
        surfaceKind = surfaceKind,
        channelId = threadId?.trim()?.takeIf(String::isNotBlank),
        streamChannelId = streamChannelId?.trim()?.takeIf(String::isNotBlank),
        parentChannelId = parentChannelId?.trim()?.takeIf(String::isNotBlank),
        parentMessageId = parentMessageId?.trim()?.takeIf(String::isNotBlank),
        focusedMessageId = focusedMessageId?.trim()?.takeIf(String::isNotBlank),
    )
}
