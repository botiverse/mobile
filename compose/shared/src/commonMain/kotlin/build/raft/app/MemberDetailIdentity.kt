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

internal enum class MemberDetailIdentitySource(val wireValue: String) {
    DirectoryDmPeer("directory_dm_peer"),
    ThreadMember("thread_member"),
    ThreadMessageAuthor("thread_message_author"),
    ThreadForwardAuthor("thread_forward_author"),
    LegacyPageData("legacy_page_data")
}

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

internal fun memberDetailIdentityFromWire(
    memberId: String?,
    kindWireValue: String?,
    sourceServerId: String? = null,
    source: MemberDetailIdentitySource,
    diagnostics: (String) -> Unit = { message -> slockLogWarn("MemberDetailIdentity", message) }
): MemberDetailIdentity? {
    val kind =
        kindWireValue.strictMemberKindOrNull(
            source = source.wireValue,
            diagnostics = diagnostics,
            contract = MemberKindWireContract.Identity,
        ) ?: return null
    return memberDetailIdentity(memberId, kind, sourceServerId)
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
