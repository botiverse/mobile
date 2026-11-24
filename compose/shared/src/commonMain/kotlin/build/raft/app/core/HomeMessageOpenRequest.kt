package build.raft.app

import build.raft.app.model.MemberKind

internal data class ThreadLaunchOriginFallback(
    val parentChannelId: String?,
    val parentMessageId: String,
    val preview: String?,
    val senderId: String?,
    val senderKind: MemberKind?,
    val expected: Boolean = true
)

internal data class HomeMessageOpenRequest(
    val conversationIdentity: ThreadLaunchConversationIdentity,
    val anchorMessageId: String?,
    val originFallback: ThreadLaunchOriginFallback? = null
)
