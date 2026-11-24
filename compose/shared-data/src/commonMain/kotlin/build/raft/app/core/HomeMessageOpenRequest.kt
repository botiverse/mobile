package build.raft.app

import build.raft.app.model.MemberKind

@RaftCoreInternalApi data class ThreadLaunchOriginFallback(
    val parentChannelId: String?,
    val parentMessageId: String,
    val preview: String?,
    val senderId: String?,
    val senderKind: MemberKind?,
    val expected: Boolean = true
)

@RaftCoreInternalApi data class HomeMessageOpenRequest(
    val conversationIdentity: ThreadLaunchConversationIdentity,
    val anchorMessageId: String?,
    val originFallback: ThreadLaunchOriginFallback? = null
)
