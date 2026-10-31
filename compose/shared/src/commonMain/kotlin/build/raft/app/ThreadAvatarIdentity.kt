package build.raft.app

import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.resolveAvatarSlot
import build.raft.app.network.sync.ReplyPreview

/**
 * Converts message-author snapshots into candidates for the server-scoped member-profile SSOT.
 *
 * Message snapshots are ingest hints only. Rows never call this function and never read the
 * snapshot avatar directly; [RaftMemberAvatarResolver] is the only row avatar read edge.
 */
internal fun RaftThreadMessageItem.toThreadAuthorProfileCandidate(
    serverId: ServerId
): MemberProfile? {
    val memberId = authorId.trim().takeIf { it.isNotBlank() && it != "unknown" } ?: return null
    val kind = authorKind.projectedMemberKindOrNull()
        ?.takeIf { it == MemberKind.Human || it == MemberKind.Agent }
        ?: return null
    val displayName = authorName.trim().takeIf { it.isNotBlank() } ?: memberId
    // Only concrete identity facts belong in the member SSOT: the resolved
    // display value (explicit avatar, or a real gravatar hash projection). The
    // pixel fallback is a render-time default, not member data — seeding it
    // would let `avatar ?: seed.avatar` fill a cached profile's empty avatar
    // slot with `pixel:robot` and then outrank a real avatar learned later.
    val avatarCandidate = threadAuthorSeedAvatarWireValue()
    return MemberProfile(
        id = MemberId(memberId),
        displayName = displayName,
        handle = authorHandle.trim().takeIf { it.isNotBlank() } ?: displayName.removePrefix("@"),
        kind = kind,
        avatar = AvatarReference.fromWireValue(avatarCandidate),
        avatarLabel = avatarLabel.ifBlank { displayName.toRaftAvatarLabel() },
        serverId = serverId,
        membershipStatus = senderMembershipStatus?.takeIf { it.isNotBlank() },
        agentDeletedAt = authorDeletedAt?.takeIf { it.isNotBlank() }
    )
}

internal fun SharedServerMembersStore.seedThreadAuthorProfiles(
    serverId: ServerId,
    messages: Iterable<RaftThreadMessageItem>,
    source: String,
    diagnosticsLogger: (String) -> Unit = {}
) {
    val profiles =
        messages.flatMap { message ->
            listOfNotNull(message.toThreadAuthorProfileCandidate(serverId)) +
                message.inlineReplies
                    ?.replies
                    .orEmpty()
                    .mapNotNull { reply -> reply.toThreadAuthorProfileCandidate(serverId) }
        }
    if (profiles.isEmpty()) return
    seedProfileCandidates(serverId = serverId, profiles = profiles)
    diagnosticsLogger(
        "thread_avatar_member_seed source=$source serverHash=${serverId.value.raftAvatarDiagnosticsHash()} " +
            "candidates=${profiles.size} authors=${profiles.map { it.id.value }.distinct().size} " +
            "urlCandidates=${profiles.count { profile -> profile.avatar is AvatarReference.Url }}"
    )
}

/**
 * Writes a live inline-reply author only after the replies SyncCore host has accepted that frame's
 * route/scope identity. The row remains resolver-only; rejected/stale frames never call this edge.
 */
internal fun SharedServerMembersStore.seedAcceptedThreadReplyAuthorProfile(
    serverId: ServerId,
    reply: ReplyPreview,
    source: String,
    diagnosticsLogger: (String) -> Unit = {}
) {
    val profile = reply.toThreadAuthorProfileCandidate(serverId) ?: return
    seedProfileCandidates(serverId = serverId, profiles = listOf(profile))
    diagnosticsLogger(
        "thread_avatar_member_seed source=$source serverHash=${serverId.value.raftAvatarDiagnosticsHash()} " +
            "candidates=1 authors=1 urlCandidates=${if (profile.avatar is AvatarReference.Url) 1 else 0}"
    )
}

private fun ReplyPreview.toThreadAuthorProfileCandidate(serverId: ServerId): MemberProfile? {
    val projectedKind =
        when (senderType.trim().lowercase()) {
            "user", "human" -> "human"
            "agent" -> "agent"
            else -> return null
        }
    val projectedDisplayName =
        senderDisplayName?.trim()?.takeIf { it.isNotBlank() }
            ?: senderName.trim().takeIf { it.isNotBlank() }
            ?: senderId
    return RaftThreadMessageItem(
        id = messageId,
        authorId = senderId,
        authorName = projectedDisplayName,
        authorHandle = senderName,
        authorKind = projectedKind,
        avatarLabel = projectedDisplayName.toRaftAvatarLabel(),
        avatarWireValue = senderAvatarUrl
    ).toThreadAuthorProfileCandidate(serverId)
}

/**
 * The seed lane mirrors a message author's *displayed* avatar only when it is
 * a real identity fact. `resolvedThreadAvatarWireValue` adds render fallbacks
 * (`pixel:robot` for avatar-less agents); those must never reach the member
 * profile SSOT.
 */
internal fun RaftThreadMessageItem.threadAuthorSeedAvatarWireValue(): String? {
    if (threadAuthorBadgeLabel() != null) return null
    val authorMemberKind = authorKind.projectedMemberKindOrNull() ?: return null
    val resolution = resolveAvatarSlot(
        kind = authorMemberKind,
        id = authorId.ifBlank { authorName },
        avatarWireValue = avatarWireValue
    )
    return resolution.displayAvatarWireValue
}

internal fun RaftThreadScreenPayload.threadAuthorProfileCandidates(): List<RaftThreadMessageItem> =
    (listOfNotNull(originMessage) + messages).distinctBy { it.id }

/** The sole message-row read contract: canonical member state in, render source out. */
internal fun resolveThreadRowAvatarWireValue(canonicalMemberAvatarWireValue: String?): String? =
    canonicalMemberAvatarWireValue?.trim()?.takeIf { it.isNotBlank() }
