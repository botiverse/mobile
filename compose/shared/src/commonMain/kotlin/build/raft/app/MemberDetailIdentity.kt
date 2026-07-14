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

internal enum class MemberDetailAccessMode {
    LocalFull,
    RemoteProfileOnly;

    val allowsPrivateAgentSurfaces: Boolean
        get() = this == LocalFull
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

internal fun MemberDetailIdentity.memberDetailAccessMode(
    currentServerId: String?
): MemberDetailAccessMode {
    val sourceServerId = sourceServerId?.value?.trim().orEmpty()
    val activeServerId = currentServerId?.trim().orEmpty()
    return if (
        sourceServerId.isNotBlank() &&
        activeServerId.isNotBlank() &&
        sourceServerId != activeServerId
    ) {
        MemberDetailAccessMode.RemoteProfileOnly
    } else {
        MemberDetailAccessMode.LocalFull
    }
}

/** Member kind selects the repository family; access mode is only passed to that selected family. */
internal suspend fun <T> MemberDetailIdentity.dispatchMemberDetailLookup(
    currentServerId: String?,
    loadHuman: suspend (MemberId, ServerId?, MemberDetailAccessMode) -> T,
    loadAgent: suspend (MemberId, MemberDetailAccessMode) -> T,
    unsupported: suspend (MemberId, MemberKind) -> T
): T {
    val accessMode = memberDetailAccessMode(currentServerId)
    return when (kind) {
        MemberKind.Human -> loadHuman(memberId, sourceServerId, accessMode)
        MemberKind.Agent -> loadAgent(memberId, accessMode)
        MemberKind.System -> unsupported(memberId, kind)
    }
}
