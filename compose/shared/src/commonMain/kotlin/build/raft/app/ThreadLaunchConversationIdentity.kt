package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId

internal data class ThreadLaunchConversationIdentity(
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val channelType: String? = null,
    val thread: ThreadConversation? = null,
    val kind: ConversationKind
)

internal fun ConversationSummary.toThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity =
    ThreadLaunchConversationIdentity(
        threadId = threadId,
        channelId = channelId,
        channelType = channelType,
        thread = thread,
        kind = kind
    )

internal fun ThreadLaunchConversationIdentity.resolveThreadRouteIdentity(): ThreadRouteIdentity =
    resolveThreadRouteIdentity(
        surfaceKind = threadSurfaceKind(),
        channelKind = threadRouteChannelKind(),
        instanceId = null,
        sourceThreadId = threadId.value,
        streamChannelId = threadRouteStreamChannelId(),
        parentChannelId = threadParentChannelId(),
        parentMessageId = threadParentMessageId()
    )

internal fun ThreadLaunchConversationIdentity.threadSurfaceKind(): String =
    when (kind) {
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        ConversationKind.Thread -> KuiklyThreadSurfaceKind.ReplyThread
        else -> KuiklyThreadSurfaceKind.Channel
    }

internal fun ThreadLaunchConversationIdentity.usesMemberScopedMentions(): Boolean =
    kind == ConversationKind.DirectMessage ||
        kind == ConversationKind.Joint ||
        channelType.isMemberScopedThreadChannelType()

internal fun ThreadLaunchConversationIdentity.threadStreamChannelId(): String? =
    thread?.streamChannelId?.value
        ?: if (kind == ConversationKind.Thread) {
            null
        } else {
            channelId?.value ?: threadId.value
        }

private fun ThreadLaunchConversationIdentity.threadRouteStreamChannelId(): String? =
    thread?.streamChannelId?.value
        ?: if (kind == ConversationKind.Thread) {
            threadId.value
        } else {
            channelId?.value ?: threadId.value
        }

internal fun ThreadLaunchConversationIdentity.threadParentChannelId(): String? =
    thread?.parentChannelId?.value

internal fun ThreadLaunchConversationIdentity.threadParentMessageId(): String? =
    thread?.parentMessageId?.value

internal fun ThreadLaunchConversationIdentity.threadParentSurfaceKind(): String? =
    thread?.parentSurfaceKind.threadParentSurfaceKindOrNull()
        ?: if (kind == ConversationKind.Thread) {
            channelType.threadParentSurfaceKindOrNull()
        } else {
            null
        }

internal fun ThreadLaunchConversationIdentity.threadParentChannelKind(): String? =
    thread?.parentChannelKind.normalizedThreadChannelKind()
        ?: if (kind == ConversationKind.Thread) {
            channelType.normalizedThreadChannelKind()
        } else {
            null
        }

internal fun ThreadLaunchConversationIdentity.threadRouteChannelKind(): String? =
    when (kind) {
        ConversationKind.Joint -> "joint"
        ConversationKind.Channel -> channelType.normalizedThreadChannelKind() ?: KuiklyThreadSurfaceKind.Channel
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        else -> channelType.normalizedThreadChannelKind()
    }

private fun String?.isMemberScopedThreadChannelType(): Boolean =
    when (this?.trim()?.lowercase()) {
        "dm", "direct", "direct_message", "direct-message", "private", "joint" -> true
        else -> false
    }

private fun String?.threadParentSurfaceKindOrNull(): String? =
    when (this?.trim()?.lowercase()) {
        KuiklyThreadSurfaceKind.DirectMessage,
        "direct_message",
        "direct-message",
        "directmessage",
        "direct" -> KuiklyThreadSurfaceKind.DirectMessage
        KuiklyThreadSurfaceKind.Channel,
        "public",
        "private",
        "joint",
        "group" -> KuiklyThreadSurfaceKind.Channel
        else -> null
    }
