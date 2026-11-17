package build.raft.app

import build.raft.app.model.conversationDisplayTitleCandidate
import build.raft.app.model.conversationIdentityLikeTitle
import build.raft.app.model.conversationSafeDisplayTitleOrNull

internal fun threadHeaderFallbackTitle(surfaceKind: String): String =
    when (surfaceKind) {
        RaftThreadSurfaceKind.ReplyThread -> ThreadReplyPageTitle
        RaftThreadSurfaceKind.DirectMessage -> "Direct Message"
        else -> "Channel"
    }

internal fun threadHeaderCandidateTitle(raw: String?): String? =
    conversationDisplayTitleCandidate(raw)

internal fun threadHeaderIdentityLikeTitle(
    title: String,
    identityIds: Set<String> = emptySet()
): Boolean {
    return conversationIdentityLikeTitle(title, identityIds)
}

internal fun threadHeaderSafeTitleOrNull(
    raw: String?,
    identityIds: Set<String> = emptySet()
): String? =
    conversationSafeDisplayTitleOrNull(raw, identityIds)

internal fun threadHeaderSafeTitle(
    raw: String?,
    surfaceKind: String,
    identityIds: Set<String> = emptySet()
): String =
    threadHeaderSafeTitleOrNull(raw, identityIds) ?: threadHeaderFallbackTitle(surfaceKind)

internal fun String.isGenericThreadHeaderFallbackLabel(): Boolean =
    equals("Channel", ignoreCase = true) ||
        equals("Direct Message", ignoreCase = true) ||
        equals("Thread", ignoreCase = true) ||
        equals(ThreadReplyPageTitle, ignoreCase = true)
