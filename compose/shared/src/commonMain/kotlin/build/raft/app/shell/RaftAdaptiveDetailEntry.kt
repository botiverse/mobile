package build.raft.app

import build.raft.app.model.ConversationKind

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

internal data class RaftAdaptiveHomeLandingCandidate(
    val threadId: String,
    val name: String,
    val kind: ConversationKind? = null,
)

internal fun resolveRaftAdaptiveHomeLandingId(
    selectedThreadId: String?,
    candidates: List<RaftAdaptiveHomeLandingCandidate>,
): String? =
    (resolveRaftAdaptiveHomeLanding(selectedThreadId, remembered = null, candidates).landing as? RaftAdaptiveHomeLanding.Row)
        ?.threadId
