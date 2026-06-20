package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.explicitMessageStreamChannelId
import ai.slock.compose.model.isSameConversationAs
import ai.slock.compose.model.messageStreamChannelId
import ai.slock.compose.model.parentChannelIdOrNull
import ai.slock.compose.model.parentMessageIdOrNull
import ai.slock.compose.model.threadIdentity

internal data class ThreadCandidateIdentity(
    val serverId: ServerId? = null,
    val threadId: ThreadId? = null,
    val streamChannelId: ChannelId? = null,
    val parentChannelId: ChannelId? = null,
    val parentMessageId: MessageId? = null,
    val replyThread: Boolean = false
)

internal data class ThreadCandidateProof(
    val accepted: Boolean,
    val reason: String
)

internal object ThreadCandidateProofGate {
    fun prove(
        route: ThreadCandidateIdentity,
        candidate: ThreadCandidateIdentity
    ): ThreadCandidateProof {
        if (route.serverId != null && candidate.serverId != null && route.serverId != candidate.serverId) {
            return ThreadCandidateProof(accepted = false, reason = "server_mismatch")
        }
        val routeParentPair = route.parentPair()
        val candidateParentPair = candidate.parentPair()
        val routeStream = route.streamChannelId
        val candidateStream = candidate.streamChannelId
        val replyScoped =
            route.replyThread ||
                candidate.replyThread ||
                routeParentPair != null ||
                candidateParentPair != null
        if (replyScoped) {
            if (routeParentPair != null && routeParentPair == candidateParentPair) {
                return ThreadCandidateProof(accepted = true, reason = "parent_pair")
            }
            if (routeParentPair == null && routeStream != null && routeStream == candidateStream) {
                return ThreadCandidateProof(accepted = true, reason = "stream_channel")
            }
            return ThreadCandidateProof(accepted = false, reason = "reply_identity_mismatch")
        }
        if (routeStream != null && routeStream == candidateStream) {
            return ThreadCandidateProof(accepted = true, reason = "stream_channel")
        }
        val routeThread = route.threadId
        val candidateThread = candidate.threadId
        if (routeThread != null && routeThread == candidateThread) {
            return ThreadCandidateProof(accepted = true, reason = "thread_id")
        }
        return ThreadCandidateProof(accepted = false, reason = "identity_unproven")
    }

    private fun ThreadCandidateIdentity.parentPair(): Pair<ChannelId, MessageId>? {
        val channel = parentChannelId ?: return null
        val message = parentMessageId ?: return null
        return channel to message
    }
}

internal fun ThreadSnapshot.threadCandidateIdentity(serverId: ServerId? = null): ThreadCandidateIdentity {
    val conversationThread = conversation.thread
    val parent = parentMessage
    val parentChannel =
        parent?.channelId
            ?: conversationThread?.parentChannelId
            ?: conversation.threadId.parentChannelIdOrNull()
    val parentMessageId =
        parent?.id
            ?: conversationThread?.parentMessageId
            ?: conversation.threadId.parentMessageIdOrNull()
    val replyThread =
        conversation.kind == ConversationKind.Thread ||
            conversationThread != null ||
            (parentChannel != null && parentMessageId != null)
    return ThreadCandidateIdentity(
        serverId = serverId,
        threadId = conversation.threadId,
        streamChannelId = conversation.messageStreamChannelId(),
        parentChannelId = parentChannel,
        parentMessageId = parentMessageId,
        replyThread = replyThread
    )
}

internal data class ThreadFallbackProof(
    val header: ConversationSummary?,
    val parentMessage: Message?,
    val aliasThreadId: ThreadId?,
    val rejected: Boolean,
    val rejectionReason: String? = null
) {
    companion object {
        fun resolve(
            snapshotConversation: ConversationSummary,
            snapshotParentMessage: Message?,
            storedConversation: ConversationSummary?,
            fallbackThreadId: ThreadId?,
            fallbackHeader: ConversationSummary?,
            fallbackParentMessage: Message?
        ): ThreadFallbackProof {
            val snapshotStreamChannelId = snapshotConversation.messageStreamChannelId()
            val snapshotParentChannelId =
                snapshotParentMessage?.channelId
                    ?: snapshotConversation.thread?.parentChannelId
                    ?: snapshotConversation.threadId.parentChannelIdOrNull()
            val snapshotParentMessageId =
                snapshotParentMessage?.id
                    ?: snapshotConversation.thread?.parentMessageId
                    ?: snapshotConversation.threadId.parentMessageIdOrNull()
            val fallbackParentPairMatchesSnapshot =
                fallbackParentMessage != null &&
                    snapshotParentChannelId != null &&
                    snapshotParentMessageId != null &&
                    fallbackParentMessage.channelId == snapshotParentChannelId &&
                    fallbackParentMessage.id == snapshotParentMessageId
            val fallbackParentStreamMatchesSnapshot =
                fallbackParentMessage?.threadChannelId != null &&
                    fallbackParentMessage.threadChannelId == snapshotStreamChannelId
            val fallbackThreadIdMatchesSnapshot =
                fallbackThreadId != null &&
                    (
                        fallbackThreadId == snapshotConversation.threadId ||
                            fallbackThreadId.value == snapshotStreamChannelId.value
                    )
            val safeParentMessage =
                fallbackParentMessage?.takeIf {
                    fallbackParentPairMatchesSnapshot ||
                        fallbackParentStreamMatchesSnapshot
                }
            val safeParentChannelId = snapshotParentChannelId ?: safeParentMessage?.channelId
            val safeParentMessageId = snapshotParentMessageId ?: safeParentMessage?.id
            val fallbackHeaderThread = fallbackHeader?.thread
            val fallbackHeaderParentPairMatches =
                fallbackHeaderThread != null &&
                    safeParentChannelId != null &&
                    safeParentMessageId != null &&
                    fallbackHeaderThread.parentChannelId == safeParentChannelId &&
                    fallbackHeaderThread.parentMessageId == safeParentMessageId
            val fallbackHeaderSameConversation =
                fallbackHeader != null &&
                    (
                        snapshotConversation.isSameConversationAs(fallbackHeader) ||
                            storedConversation?.isSameConversationAs(fallbackHeader) == true
                    )
            val fallbackHeaderTopLevelParentMatches =
                fallbackHeader != null &&
                    fallbackHeader.kind != ConversationKind.Thread &&
                    safeParentMessage != null &&
                    fallbackHeader.channelId == safeParentMessage.channelId
            val fallbackHeaderStreamMatches =
                fallbackHeader?.explicitMessageStreamChannelId() != null &&
                    fallbackHeader.explicitMessageStreamChannelId() == snapshotStreamChannelId
            val safeHeader =
                fallbackHeader?.takeIf {
                    fallbackHeaderSameConversation ||
                        fallbackHeaderParentPairMatches ||
                        fallbackHeaderTopLevelParentMatches ||
                        fallbackHeaderStreamMatches
                }
            val safeAliasThreadId =
                fallbackThreadId?.takeIf {
                    fallbackThreadIdMatchesSnapshot ||
                        (
                            safeParentChannelId != null &&
                                safeParentMessageId != null &&
                                it == threadIdentity(safeParentChannelId, safeParentMessageId)
                            )
                }
            val rejectionReason =
                when {
                    fallbackHeader != null && safeHeader == null -> "header_unproven"
                    fallbackParentMessage != null && safeParentMessage == null -> "parent_unproven"
                    fallbackThreadId != null && safeAliasThreadId == null -> "alias_unproven"
                    else -> null
                }
            return ThreadFallbackProof(
                header = safeHeader,
                parentMessage = safeParentMessage,
                aliasThreadId = safeAliasThreadId,
                rejected = rejectionReason != null,
                rejectionReason = rejectionReason
            )
        }
    }
}
