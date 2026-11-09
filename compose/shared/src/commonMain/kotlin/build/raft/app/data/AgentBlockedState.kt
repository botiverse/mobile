package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence

/** The agent list's `blockedReason` for an agent on a Computer whose key was revoked. */
internal const val AgentBlockedReasonComputerAccessRevoked = "computer_access_revoked"

/** The `code` of the 409 the server answers for a start or a workspace read on such an agent. */
internal const val ComputerAccessRevokedErrorCode = "computer_access_revoked"

/**
 * Why an agent is held from running, as its status says (web `utils/agentBlockedState.ts`
 * `AgentBlockedState`, botiverse/slock #13575).
 *
 * - [ComputerAccessRevoked]: reconciler step 15 (B15). The agent's Computer has no live key on
 *   the server (revoked, e.g. its attacher left or was removed). The agent stays bound to it and
 *   gets no wakes; it never comes back by itself, and attaching that machine again adds a new
 *   computer, so it reads "Computer access revoked: move the agent", not "Offline".
 *
 * The web's other two held states are not here: "Computer too old" (`computer_version_*`) and
 * "Computer offline" are not shown by the app yet, so those agents keep their ordinary status.
 */
enum class AgentBlockedState {
    ComputerAccessRevoked
}

/**
 * Web `agentBlockedState(blockedReason)` for the reasons the app shows; null for every other one
 * (an unknown reason keeps the ordinary state, as the web's additive rule says).
 */
fun agentBlockedState(blockedReason: String?): AgentBlockedState? =
    when (blockedReason?.trim()) {
        AgentBlockedReasonComputerAccessRevoked -> AgentBlockedState.ComputerAccessRevoked
        else -> null
    }

/**
 * The English status words of [this] (web `enFallback["agent.status.computerAccessRevoked"]`):
 * the label of a held agent's status for surfaces that print it raw; localized surfaces read
 * [AgentBlockedState] itself.
 */
val AgentBlockedState.englishStatusLabel: String
    get() =
        when (this) {
            AgentBlockedState.ComputerAccessRevoked ->
                "Computer access revoked: move the agent to another computer (re-attaching adds a new one)"
        }

/**
 * Web `resolveAgentDisplayState`'s held branch: an agent the server runs nowhere reads that,
 * whatever its last activity, in error orange (someone has to move it), never "Likely online".
 * An external agent keeps its own presence (the web checks it first); so does an App Bot or an
 * archived agent, which carry no runtime status.
 */
internal fun MemberProfile?.blockedMemberStatusOrNull(): SharedServerMemberStatus? {
    val member = this ?: return null
    if (member.kind != MemberKind.Agent || member.isExternal) return null
    if (!member.managedByAppInstallId.isNullOrBlank()) return null
    if (!member.agentDeletedAt.isNullOrBlank() || member.membershipStatus == "deleted") return null
    val blocked = agentBlockedState(member.agentBlockedReason) ?: return null
    return SharedServerMemberStatus(
        status = AgentStatus.Error,
        presence = Presence.Offline,
        statusLabel = blocked.englishStatusLabel,
        runtime = member.roleDescription?.trim()?.takeIf { it.isNotBlank() },
        external = false,
        blocked = blocked
    )
}
