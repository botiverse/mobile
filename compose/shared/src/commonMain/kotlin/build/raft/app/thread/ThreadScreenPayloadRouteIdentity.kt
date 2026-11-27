package build.raft.app.thread

import build.raft.app.ThreadRouteIdentity
import build.raft.app.nonBlank
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.typedReplyParentConversationKindOrNull

internal fun RaftThreadScreenPayload.resolveThreadRouteIdentity(): ThreadRouteIdentity {
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
