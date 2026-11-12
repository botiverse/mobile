package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.resources.*
import org.jetbrains.compose.resources.StringResource

/** The agent list's `blockedReason` for an agent on a Computer whose key was revoked. */
internal const val AgentBlockedReasonComputerAccessRevoked = "computer_access_revoked"

/**
 * The agent list's `blockedReason` values for an agent on a Computer below the enforced version
 * floor (shared `ComputerVersionGateReason`): too old, or reporting no SemVer version at all.
 */
internal const val AgentBlockedReasonComputerVersionUnsupported = "computer_version_unsupported"
internal const val AgentBlockedReasonComputerVersionUnknown = "computer_version_unknown"

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
 * - [ComputerUpdateRequired]: the server sets `computer_version_unsupported` or
 *   `computer_version_unknown` while the agent's Computer is below the version floor and the floor
 *   is enforced (web `computer_too_old`). It reads "Computer update required" (raft-design
 *   patterns/status.md, one word for each state): the step, on the Computer, not on the agent.
 *
 * The web's third held state, "Computer offline", comes from the machine list, not from
 * `blockedReason`; the app shows it once the server's agent verdict carries it.
 */
enum class AgentBlockedState {
    ComputerAccessRevoked,
    ComputerUpdateRequired,
}

/**
 * Web `agentBlockedState(blockedReason)` for the reasons the app shows; null for every other one
 * (an unknown reason keeps the ordinary state, as the web's additive rule says).
 */
fun agentBlockedState(blockedReason: String?): AgentBlockedState? =
    when (blockedReason?.trim()) {
        AgentBlockedReasonComputerAccessRevoked -> AgentBlockedState.ComputerAccessRevoked
        AgentBlockedReasonComputerVersionUnsupported,
        AgentBlockedReasonComputerVersionUnknown -> AgentBlockedState.ComputerUpdateRequired
        else -> null
    }

/**
 * The English status words of [this] (web `enFallback["agent.status.computerAccessRevoked"]`;
 * "Computer update required" from raft-design patterns/status.md):
 * the label of a held agent's status for surfaces that print it raw; localized surfaces read
 * [AgentBlockedState] itself.
 */
val AgentBlockedState.englishStatusLabel: String
    get() =
        when (this) {
            AgentBlockedState.ComputerAccessRevoked ->
                "Computer access revoked: move the agent to another computer (re-attaching adds a new one)"
            AgentBlockedState.ComputerUpdateRequired -> "Computer update required"
        }

/** The catalog words of [this], for surfaces that resolve them outside composition (the home widget). */
internal val AgentBlockedState.statusLabelRes: StringResource
    get() =
        when (this) {
            AgentBlockedState.ComputerAccessRevoked -> Res.string.agent_status_computer_access_revoked
            AgentBlockedState.ComputerUpdateRequired -> Res.string.agent_status_computer_update_required
        }

/**
 * Web `resolveAgentDisplayState`'s held branch: an agent the server runs nowhere reads that,
 * whatever its last activity, in error orange (someone has to move it or update its Computer),
 * never "Likely online".
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
