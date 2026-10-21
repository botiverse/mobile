package build.raft.app.data

// Server capability matrix (botiverse/slock 7d349b509d6511eb922f7870099017fdda0d3f61
// packages/shared/src/serverPermissions.ts): `controlAgentRuntime` is granted to
// owner/admin/member (not guest); `migrateAgents` and `editAgents` to owner/admin.
// Agent-scoped routes also accept the agent's human creator
// (`currentUserCanActOnAgent` / web `canViewAgentPrivateSurfaces`).

internal fun serverRoleCanControlAgentRuntime(role: String?): Boolean =
    role == "owner" || role == "admin" || role == "member"

internal fun serverRoleCanMigrateAgents(role: String?): Boolean =
    role == "owner" || role == "admin"

/**
 * Web `canStopTurn` for a Computer agent: the controlAgentRuntime capability
 * or creator authority, on a live (not archived) agent that is online. Cloud
 * (external) agents are left out: mobile does not model their hosted runtime
 * state, which web uses to decide whether a turn exists to stop.
 */
internal fun canStopAgentTurn(
    serverRole: String?,
    isCreator: Boolean,
    isExternalAgent: Boolean,
    deleted: Boolean,
    online: Boolean
): Boolean =
    !deleted && !isExternalAgent && online &&
        (serverRoleCanControlAgentRuntime(serverRole) || isCreator)
