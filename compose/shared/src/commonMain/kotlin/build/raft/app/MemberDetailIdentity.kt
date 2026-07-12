package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable

/** Complete identity required by production member-detail click and launch boundaries. */
@Serializable
internal data class MemberDetailIdentity(
    val memberId: MemberId,
    val kind: MemberKind,
    val sourceServerId: ServerId? = null
)

internal fun memberDetailIdentity(
    memberId: String?,
    kind: MemberKind?,
    sourceServerId: String? = null
): MemberDetailIdentity? {
    val cleanMemberId = memberId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val resolvedKind = kind ?: return null
    return MemberDetailIdentity(
        memberId = MemberId(cleanMemberId),
        kind = resolvedKind,
        sourceServerId = sourceServerId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
    )
}

internal fun String?.parsedMemberKindOrNull(): MemberKind? =
    when (this?.trim()?.lowercase()) {
        "agent" -> MemberKind.Agent
        "human", "user", "member" -> MemberKind.Human
        "system" -> MemberKind.System
        else -> null
    }

internal fun MemberKind.memberKindWireValue(): String =
    when (this) {
        MemberKind.Agent -> "agent"
        MemberKind.Human -> "human"
        MemberKind.System -> "system"
    }

internal suspend fun <T> MemberDetailIdentity.dispatchMemberDetailLookup(
    loadHuman: suspend (MemberId, ServerId?) -> T,
    loadAgent: suspend (MemberId) -> T,
    unsupported: suspend (MemberId, MemberKind) -> T
): T =
    when (kind) {
        MemberKind.Human -> loadHuman(memberId, sourceServerId)
        MemberKind.Agent -> loadAgent(memberId)
        MemberKind.System -> unsupported(memberId, kind)
    }
