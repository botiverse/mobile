package build.raft.app

import build.raft.app.data.ThreadSnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId

internal fun ThreadSnapshot.withReplyParentMessage(
    parentChannelId: String,
    parentMessageId: String,
    parentMessage: Message?
): ThreadSnapshot {
    val parentChannel = ChannelId(parentChannelId)
    val targetMessageId = MessageId(parentMessageId)
    val existingParent =
        this.parentMessage?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, targetMessageId) }
            ?: messages.firstOrNull { message -> message.matchesReplyOriginParent(parentChannel, targetMessageId) }
    if (existingParent != null) {
        return copy(parentMessage = existingParent)
    }
    return parentMessage
        ?.takeIf { message -> message.matchesReplyOriginParent(parentChannel, targetMessageId) }
        ?.let { parent -> copy(parentMessage = parent) }
        ?: this
}

internal fun Message.needsReplyOriginContextHydration(): Boolean =
    body.isBlank() && attachments.isEmpty()

internal fun RaftThreadMessageItem.needsReplyOriginContextHydration(): Boolean =
    body.isBlank() && attachments.isEmpty()

internal fun Message.matchesReplyOriginParent(
    parentChannelId: ChannelId,
    parentMessageId: MessageId
): Boolean =
    id == parentMessageId &&
        !needsReplyOriginContextHydration() &&
        (channelId == parentChannelId || threadId == ThreadId(parentChannelId.value))

internal fun ThreadSnapshot.hasRenderableReplyThreadMessages(
    basePayload: RaftThreadScreenPayload
): Boolean {
    val parentMessageId =
        basePayload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: basePayload.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: conversation.thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }
    val replyThreadContentChannelId =
        basePayload.copy(
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId =
                basePayload.sourceThreadId
                    ?: conversation.thread?.streamChannelId?.value
                    ?: conversation.channelId?.value
                    ?: conversation.threadId.value,
            streamChannelId =
                basePayload.streamChannelId
                    ?: conversation.thread?.streamChannelId?.value
                    ?: conversation.channelId?.value
                    ?: conversation.threadId.value
        ).replyThreadContentChannelId()
    return messages.any { message ->
        message.id.value != parentMessageId &&
            (replyThreadContentChannelId == null || message.belongsToReplyThreadContent(replyThreadContentChannelId))
    }
}
