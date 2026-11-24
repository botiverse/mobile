package build.raft.app

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
