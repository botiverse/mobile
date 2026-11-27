package build.raft.app.agents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.members.RaftMemberAvatarResolver
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.agentLoungeDmAgentId
import build.raft.app.data.AGENT_VOICE_FEATURE_FLAG_KEY
import build.raft.app.data.AgentVoiceCallee
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedAgentVoiceCallStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.agentVoiceCallEntryEligibility
import build.raft.app.data.loungeAgentDisplayName
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.data.selectComputerServerSlice
import build.raft.app.members.membersComputerId
import build.raft.app.model.ServerId
import build.raft.app.members.rememberRaftMemberAvatarWireValue

// Call entry-point helpers (`#wg-mobile-sync:#95`), mirroring web
// `packages/web/src/features/agent/agentVoiceCallEntry.ts`.

/** Mirrors web `isMachineOffline`: true only when [machineId] matches a known non-online Computer. */
internal fun isComputerOfflineForVoiceCall(
    machineId: String?,
    machines: List<ComputerSummary>,
): Boolean {
    val cleanId = machineId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val machine = machines.firstOrNull { it.id == cleanId } ?: return false
    return machine.status != ComputerStatus.Online
}

/**
 * Returns a callback that starts a voice call to the agent, or `null` when the Call button must
 * stay hidden (`agentVoiceCallEntryEligibility`).
 */
@Composable
internal fun rememberAgentVoiceCallAction(
    flags: SharedServerFeatureFlags?,
    callStore: SharedAgentVoiceCallStore?,
    serverId: String?,
    serverRole: String?,
    agentId: String?,
    agentName: String?,
    avatarWireValue: String?,
    deletedAt: String?,
    managedByAppInstallId: String?,
    agentStatus: String?,
    machineId: String?,
    computerOffline: Boolean,
): (() -> Unit)? {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
    val featureEnabled =
        rememberServerFeatureLatched(
            flags = flags,
            serverId = cleanServerId?.let(::ServerId),
            key = AGENT_VOICE_FEATURE_FLAG_KEY,
        )
    val cleanAgentId = agentId?.trim()?.takeIf { it.isNotEmpty() }
    val eligible =
        callStore != null &&
            agentVoiceCallEntryEligibility(
                featureEnabled = featureEnabled,
                serverId = cleanServerId,
                serverRole = serverRole,
                agentId = cleanAgentId,
                deletedAt = deletedAt,
                managedByAppInstallId = managedByAppInstallId,
                agentStatus = agentStatus,
                machineId = machineId,
                computerOffline = computerOffline,
            )
    if (!eligible || cleanServerId == null || cleanAgentId == null || callStore == null) return null
    val resolvedName = agentName?.trim()?.takeIf { it.isNotEmpty() } ?: cleanAgentId
    return remember(callStore, cleanServerId, cleanAgentId, resolvedName, avatarWireValue) {
        {
            callStore.startCall(
                agent =
                    AgentVoiceCallee(
                        id = cleanAgentId,
                        name = resolvedName,
                        avatarWireValue = avatarWireValue,
                    ),
                serverId = cleanServerId,
            )
        }
    }
}

/**
 * Resolves the Call action for a 1:1 Agent DM thread header (null for channels, human DMs,
 * lounges, reply threads, task conversations, or ineligible agents).
 */
@Composable
internal fun rememberThreadDmAgentVoiceCallAction(
    payload: RaftThreadScreenPayload,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    serverRole: String?,
    serverMembersStore: SharedServerMembersStore?,
    memberAvatarResolver: RaftMemberAvatarResolver,
): (() -> Unit)? {
    val dmAgentId =
        if (payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
            payload.agentLoungeDmAgentId()
        } else {
            null
        }
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
    val membersState = serverMembersStore?.state?.collectAsState()?.value
    val memberProfile =
        remember(membersState, cleanServerId, dmAgentId) {
            if (cleanServerId != null && dmAgentId != null) {
                membersState?.entryFor(ServerId(cleanServerId))?.members?.firstOrNull { it.id.value == dmAgentId }
            } else {
                null
            }
        }
    val computersStore = remember(sharedStores, storeScope) { sharedStores.computersStore(storeScope) }
    val computersState by computersStore.state.collectAsState()
    val machines =
        remember(computersState, cleanServerId) {
            if (cleanServerId != null) {
                computersState.selectComputerServerSlice(cleanServerId).machines
            } else {
                emptyList()
            }
        }
    val machineId = memberProfile?.membersComputerId
    val computerOffline = isComputerOfflineForVoiceCall(machineId, machines)
    val resolvedAvatar = rememberRaftMemberAvatarWireValue(memberAvatarResolver, dmAgentId)
    val agentName =
        loungeAgentDisplayName(memberProfile?.displayName)
            ?: loungeAgentDisplayName(payload.title.trim().removePrefix("@"))
    return rememberAgentVoiceCallAction(
        flags = sharedStores.serverFeatureFlags,
        callStore = sharedStores.agentVoiceCallStore,
        serverId = cleanServerId,
        serverRole = serverRole,
        agentId = dmAgentId,
        agentName = agentName,
        avatarWireValue = resolvedAvatar,
        deletedAt = memberProfile?.agentDeletedAt,
        managedByAppInstallId = memberProfile?.managedByAppInstallId,
        agentStatus = memberProfile?.agentLifecycleStatus,
        machineId = machineId,
        computerOffline = computerOffline,
    )
}

/**
 * Renders [AgentVoiceCallOverlayHost] on standalone pushed pages (e.g. phone ThreadPage or
 * MembersDetailPage outside the app shell). No-op when an ancestor shell already mounts it.
 */
@Composable
internal fun StandaloneAgentVoiceCallOverlay() {
    if (LocalAgentVoiceCallHostMounted.current) return
    val store = remember { RaftSharedStoreRegistry.currentOrNull()?.agentVoiceCallStore }
    AgentVoiceCallOverlayHost(store = store)
}
