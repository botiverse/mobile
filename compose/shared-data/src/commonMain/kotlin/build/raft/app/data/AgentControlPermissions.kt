package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

// Server capability matrix (botiverse/slock 7d349b509d6511eb922f7870099017fdda0d3f61
// packages/shared/src/serverPermissions.ts): `controlAgentRuntime` is granted to
// owner/admin/member (not guest); `migrateAgents` and `editAgents` to owner/admin.
// `stopAgents` (slock #12029) is owner/admin only: members keep start, restart,
// reset session and Stop Current Turn, but may stop only agents they created.
// Agent-scoped routes also accept the agent's human creator
// (`currentUserCanActOnAgent` / web `canViewAgentPrivateSurfaces`).

@RaftCoreInternalApi fun serverRoleCanControlAgentRuntime(role: String?): Boolean =
    role == "owner" || role == "admin" || role == "member"

@RaftCoreInternalApi fun serverRoleCanStopAgents(role: String?): Boolean =
    role == "owner" || role == "admin"

@RaftCoreInternalApi fun serverRoleCanMigrateAgents(role: String?): Boolean =
    role == "owner" || role == "admin"

/**
 * `manageExternalAuth` (botiverse/slock 5828cdce3e serverPermissions.ts: every admin capability
 * but billing, SSO and data export, so owner and admin): approving, denying, granting and revoking
 * an App's access to an agent, with the agent's human creator (routes/integrations.ts `canManageAgentAccess`).
 */
@RaftCoreInternalApi fun serverRoleCanManageExternalAuth(role: String?): Boolean =
    when (role?.trim()?.lowercase()) {
        "owner", "admin" -> true
        else -> false
    }

/**
 * Web AgentDetailPanel `canManageAgentAccess` = `canViewAgentPrivateSurfaces(agent, user,
 * capabilities.manageExternalAuth)`: the capability, or the agent's creator when a person created it.
 * (The server also lets a personal assistant's owner only; it refuses anyone else with a 403.)
 */
@RaftCoreInternalApi fun canManageAgentAppAccess(
    serverRole: String?,
    currentUserId: String?,
    creatorType: String?,
    creatorId: String?,
): Boolean =
    serverRoleCanManageExternalAuth(serverRole) ||
        (!currentUserId.isNullOrBlank() && creatorType == "user" && creatorId == currentUserId)

/**
 * Web `canStopTurn` for a Computer agent: the controlAgentRuntime capability
 * or creator authority, on a live (not archived) agent that is online. Cloud
 * (external) agents are left out: mobile does not model their hosted runtime
 * state, which web uses to decide whether a turn exists to stop.
 */
@RaftCoreInternalApi fun canStopAgentTurn(
    serverRole: String?,
    isCreator: Boolean,
    isExternalAgent: Boolean,
    deleted: Boolean,
    online: Boolean
): Boolean =
    !deleted && !isExternalAgent && online &&
        (serverRoleCanControlAgentRuntime(serverRole) || isCreator)

/**
 * Worker v1 Stop authority (`POST /api/agents/:id/workers/:worker/stop`, web AgentDetailPanel
 * `workersPanel.canStop`): live Computer agent, `controlAgentRuntime` or creator authority,
 * and for a personal assistant only its owner (`routes/agents.ts:6452`).
 */
@RaftCoreInternalApi fun canStopAgentWorkers(
    serverRole: String?,
    isCreator: Boolean,
    isExternalAgent: Boolean,
    deleted: Boolean,
    assistantOwnerUserId: String? = null,
    currentUserId: String? = null,
): Boolean {
    if (deleted || isExternalAgent) return false
    val cleanAssistantOwner = assistantOwnerUserId?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanAssistantOwner != null && currentUserId?.trim() != cleanAssistantOwner) return false
    return serverRoleCanControlAgentRuntime(serverRole) || isCreator
}

/**
 * Web `AgentDetailPanel` `canPauseAssistant` (`packages/web/src/features/agent/AgentDetailPanel.tsx`):
 * server owner or admin may pause another member's active (not yet paused, not archived, not
 * bounded public projection) personal assistant. The assistant's own owner never sees Pause.
 */
@RaftCoreInternalApi fun canPausePersonalAssistant(
    serverRole: String?,
    currentUserId: String?,
    assistantOwnerUserId: String?,
    assistantPausedAt: String?,
    creatorType: String?,
    creatorId: String?,
    deleted: Boolean,
    isBoundedPublicProjection: Boolean,
): Boolean {
    val cleanOwner = assistantOwnerUserId?.trim()?.takeIf { it.isNotEmpty() }
    val isPaused = !assistantPausedAt.isNullOrBlank()
    val isAssistant = cleanOwner != null || isPaused
    if (!isAssistant || isPaused || deleted || isBoundedPublicProjection) return false
    val cleanUser = currentUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val isAssistantOwner =
        if (cleanOwner != null) {
            cleanOwner == cleanUser
        } else {
            creatorType?.trim()?.equals("user", ignoreCase = true) == true && creatorId?.trim() == cleanUser
        }
    if (isAssistantOwner) return false
    return serverRoleCanStopAgents(serverRole?.trim()?.lowercase())
}
