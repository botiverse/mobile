package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.model.explicitMessageStreamChannelId
import build.raft.app.model.isSameConversationAs
import build.raft.app.model.messageStreamChannelId
import build.raft.app.model.parentChannelIdOrNull
import build.raft.app.model.parentMessageIdOrNull
import build.raft.app.model.threadIdentity

/**
 * Sealed thread route identity. Each subclass owns the kind-specific required identity
 * fields so callers cannot silently fall back across kinds.
 *
 * Phase 3 #3 #2 of Launch contract refactor (#280). Migrated from a flat
 * `data class ThreadCandidateIdentity(...)` to a sealed hierarchy so the compiler
 * enforces per-kind dispatch instead of nullable-field `?:` chains.
 *
 * - [ReplyThread]: v0.2 thread route; `(parentChannelId, parentMessageId, threadChannelId)`
 *   are required and non-null. `focusedMessageId` is optional.
 * - [FocusedThread]: route carries an explicit `focusedMessageId`; `channelId` is required.
 * - [ChannelThread]: plain channel route (top-level conversation); `channelId` is required.
 */
sealed class ThreadRouteId {
    abstract val serverId: ServerId?

    data class ReplyThread(
        val parentChannelId: ChannelId,
        val parentMessageId: MessageId,
        val threadChannelId: ChannelId,
        val focusedMessageId: MessageId? = null,
        override val serverId: ServerId? = null
    ) : ThreadRouteId()

    data class FocusedThread(
        val channelId: ChannelId,
        val focusedMessageId: MessageId,
        override val serverId: ServerId? = null
    ) : ThreadRouteId()

    data class ChannelThread(
        val channelId: ChannelId,
        override val serverId: ServerId? = null
    ) : ThreadRouteId()
}

/**
 * Sealed proof result. Replaces the prior `(accepted: Boolean, reason: String)` overload
 * where `reason` carried both acceptance metadata and rejection diagnostics. Now:
 * - [Accepted] carries the canonical route id (which kind + which fields)
 * - [Rejected] carries the sealed [ThreadIdentityError] describing why
 *
 * Phase 3 #3 #2 of Launch contract refactor (#280).
 */
sealed class ThreadIdentityProof {
    data class Accepted(val routeId: ThreadRouteId) : ThreadIdentityProof()
    data class Rejected(val error: ThreadIdentityError) : ThreadIdentityProof()
}

/**
 * Sealed thread identity error. Maps to the prior `reason: String` rejection values plus
 * the new `ReplyIdentityMismatch.{ParentMismatch, StreamMismatch}` split (per @Codex review)
 * and `FocusedMessageMissing` / `FocusedMessageUnproven` (per #268 resolved identity path).
 *
 * `reason` field preserves the legacy wire string for any caller / log that still reads it.
 */
sealed class ThreadIdentityError(val reason: String) {
    object ServerMismatch : ThreadIdentityError("server_mismatch")
    object ThreadStreamMissing : ThreadIdentityError("header_unproven")
    object ParentPairMissing : ThreadIdentityError("parent_unproven")
    object AliasUnproven : ThreadIdentityError("alias_unproven")
    object IdentityUnproven : ThreadIdentityError("identity_unproven")
    sealed class ReplyIdentityMismatch(reason: String) : ThreadIdentityError(reason) {
        object ParentMismatch : ReplyIdentityMismatch("reply_identity_mismatch_parent")
        object StreamMismatch : ReplyIdentityMismatch("reply_identity_mismatch_stream")
    }
    object FocusedMessageMissing : ThreadIdentityError("focused_message_missing")
    object FocusedMessageUnproven : ThreadIdentityError("focused_message_unproven")
}

internal object ThreadCandidateProofGate {
    fun prove(
        route: ThreadRouteId,
        candidate: ThreadRouteId
    ): ThreadIdentityProof {
        if (route.serverId != null && candidate.serverId != null && route.serverId != candidate.serverId) {
            return ThreadIdentityProof.Rejected(ThreadIdentityError.ServerMismatch)
        }
        return when {
            route is ThreadRouteId.ReplyThread || candidate is ThreadRouteId.ReplyThread ->
                proveReply(route, candidate)
            route is ThreadRouteId.FocusedThread || candidate is ThreadRouteId.FocusedThread ->
                proveFocused(route, candidate)
            else -> proveChannel(route as ThreadRouteId.ChannelThread, candidate as ThreadRouteId.ChannelThread)
        }
    }

    private fun proveReply(
        route: ThreadRouteId,
        candidate: ThreadRouteId
    ): ThreadIdentityProof {
        val routeParentPair = route.parentPair()
        val candidateParentPair = candidate.parentPair()
        val routeStream = route.streamChannelIdOrNull()
        val candidateStream = candidate.streamChannelIdOrNull()
        val routeResolvedStream = route.resolvedReplyThreadStreamOrNull()
        val candidateResolvedStream = candidate.resolvedReplyThreadStreamOrNull()
        return when {
            routeParentPair != null && routeParentPair == candidateParentPair &&
                routeResolvedStream != null &&
                candidateResolvedStream != null &&
                routeResolvedStream != candidateResolvedStream ->
                ThreadIdentityProof.Rejected(ThreadIdentityError.ReplyIdentityMismatch.StreamMismatch)
            routeParentPair != null && routeParentPair == candidateParentPair ->
                ThreadIdentityProof.Accepted(route)
            routeParentPair == null && candidateParentPair == null &&
                routeStream != null && routeStream == candidateStream ->
                ThreadIdentityProof.Accepted(route)
            routeParentPair != null && candidateParentPair == null ->
                ThreadIdentityProof.Rejected(ThreadIdentityError.ReplyIdentityMismatch.ParentMismatch)
            routeStream != null && candidateStream != null && routeStream != candidateStream ->
                ThreadIdentityProof.Rejected(ThreadIdentityError.ReplyIdentityMismatch.StreamMismatch)
            else -> ThreadIdentityProof.Rejected(ThreadIdentityError.ReplyIdentityMismatch.ParentMismatch)
        }
    }

    private fun proveFocused(
        route: ThreadRouteId,
        candidate: ThreadRouteId
    ): ThreadIdentityProof {
        val routeFocused = route.focusedMessageIdOrNull() ?: return ThreadIdentityProof.Rejected(
            ThreadIdentityError.FocusedMessageMissing
        )
        val candidateFocused = candidate.focusedMessageIdOrNull() ?: return ThreadIdentityProof.Rejected(
            ThreadIdentityError.FocusedMessageUnproven
        )
        val routeChannel = route.channelIdOrNull()
        val candidateChannel = candidate.channelIdOrNull()
        return when {
            routeFocused == candidateFocused &&
                routeChannel != null && routeChannel == candidateChannel ->
                ThreadIdentityProof.Accepted(route)
            else -> ThreadIdentityProof.Rejected(ThreadIdentityError.FocusedMessageUnproven)
        }
    }

    private fun proveChannel(
        route: ThreadRouteId.ChannelThread,
        candidate: ThreadRouteId.ChannelThread
    ): ThreadIdentityProof = when {
        route.channelId == candidate.channelId -> ThreadIdentityProof.Accepted(route)
        else -> ThreadIdentityProof.Rejected(ThreadIdentityError.IdentityUnproven)
    }
}

private fun ThreadRouteId.parentPair(): Pair<ChannelId, MessageId>? =
    when (this) {
        is ThreadRouteId.ReplyThread -> parentChannelId to parentMessageId
        else -> null
    }

private fun ThreadRouteId.streamChannelIdOrNull(): ChannelId? =
    when (this) {
        is ThreadRouteId.ReplyThread -> threadChannelId
        else -> null
    }

private fun ThreadRouteId.resolvedReplyThreadStreamOrNull(): ChannelId? =
    when (this) {
        is ThreadRouteId.ReplyThread -> threadChannelId.takeUnless { it == parentChannelId }
        else -> null
    }

private fun ThreadRouteId.channelIdOrNull(): ChannelId? =
    when (this) {
        is ThreadRouteId.ReplyThread -> threadChannelId
        is ThreadRouteId.FocusedThread -> channelId
        is ThreadRouteId.ChannelThread -> channelId
    }

private fun ThreadRouteId.focusedMessageIdOrNull(): MessageId? =
    when (this) {
        is ThreadRouteId.ReplyThread -> focusedMessageId
        is ThreadRouteId.FocusedThread -> focusedMessageId
        is ThreadRouteId.ChannelThread -> null
    }

internal fun ThreadSnapshot.toThreadRouteId(serverId: ServerId? = null): ThreadRouteId {
    val conversationThread = conversation.thread
    val parent = parentMessage
    val isThreadConversation = conversation.kind == ConversationKind.Thread
    val parentChannel =
        parent?.channelId
            ?: if (isThreadConversation) conversationThread?.parentChannelId else null
            ?: conversation.threadId.parentChannelIdOrNull()
    val parentMessageId =
        parent?.id
            ?: if (isThreadConversation) conversationThread?.parentMessageId else null
            ?: conversation.threadId.parentMessageIdOrNull()
    val isReplyThread =
        isThreadConversation ||
            parent != null ||
            (parentChannel != null && parentMessageId != null)
    val streamChannelId = conversation.messageStreamChannelId()
    return if (isReplyThread && parentChannel != null && parentMessageId != null && streamChannelId != null) {
        ThreadRouteId.ReplyThread(
            parentChannelId = parentChannel,
            parentMessageId = parentMessageId,
            threadChannelId = streamChannelId,
            serverId = serverId
        )
    } else if (conversation.kind == ConversationKind.Thread && parentChannel != null && parentMessageId != null) {
        // ReplyThread without explicit stream: still ReplyThread per v0.2 if parent pair proven.
        ThreadRouteId.ReplyThread(
            parentChannelId = parentChannel,
            parentMessageId = parentMessageId,
            threadChannelId = streamChannelId ?: parentChannel,
            serverId = serverId
        )
    } else {
        ThreadRouteId.ChannelThread(
            channelId = conversation.threadId.let { ChannelId(it.value) },
            serverId = serverId
        )
    }
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
            val snapshotIsThread = snapshotConversation.kind == ConversationKind.Thread
            val fallbackHeaderIsThread = fallbackHeader?.kind == ConversationKind.Thread
            val snapshotStreamChannelId =
                if (snapshotIsThread) {
                    snapshotConversation.messageStreamChannelId()
                } else {
                    snapshotConversation.channelId ?: ChannelId(snapshotConversation.threadId.value)
                }
            val snapshotParentChannelId =
                snapshotParentMessage?.channelId
                    ?: if (snapshotIsThread) snapshotConversation.thread?.parentChannelId else null
                    ?: snapshotConversation.threadId.parentChannelIdOrNull()
            val snapshotParentMessageId =
                snapshotParentMessage?.id
                    ?: if (snapshotIsThread) snapshotConversation.thread?.parentMessageId else null
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
                            fallbackThreadId.value == snapshotStreamChannelId?.value
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
                fallbackHeaderIsThread &&
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
                fallbackHeaderIsThread &&
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
