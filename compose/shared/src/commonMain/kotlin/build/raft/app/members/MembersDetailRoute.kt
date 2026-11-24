package build.raft.app

import androidx.compose.runtime.setValue
import build.raft.app.base.BridgeModule
import build.raft.app.data.RaftAgentControlsApi
import build.raft.app.data.PlatformFile
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentArchiveWorkspaceChoice
import build.raft.app.data.AgentIssueReportRepository
import build.raft.app.data.AgentIssueReportResult
import build.raft.app.data.AgentIssueReportSubmission
import build.raft.app.data.AgentIssueTicketRequest
import build.raft.app.data.AgentAvatarUploadRequest
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.AgentMachineAssignmentRequest
import build.raft.app.data.AgentResetMode
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.ChannelRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.MemberRepository
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailOwner
import build.raft.app.data.SharedAgentDetailState
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.SharedAgentIssueReportRequest
import build.raft.app.data.SharedAgentPanelTabOrderStore
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftAvatarCacheRuntime
import build.raft.app.data.RaftAvatarNativeCache
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsServerActionResult
import build.raft.app.data.SettingsServerMemberRoleUpdateRequest
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.sharedAgentDetailOwner
import build.raft.app.data.agentDetailCacheKey
import build.raft.app.data.canManageAgentAppAccess
import build.raft.app.model.ChannelId
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import build.raft.app.data.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface MemberDetailRouteState {
    data object Loading : MemberDetailRouteState
    data class Error(val error: ScreenError) : MemberDetailRouteState {
        constructor(code: String) : this(ScreenError(code = code, retryable = false))
    }
    data class Agent(
        val server: ServerSummary,
        val currentUserId: String?,
        val accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull
    ) : MemberDetailRouteState
    data class Human(
        val server: ServerSummary,
        val currentUserId: String?,
        val member: MemberProfile,
        val accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull
    ) : MemberDetailRouteState
}

internal fun memberDetailStatusResolverServerId(
    routeState: MemberDetailRouteState
): String? =
    when (routeState) {
        is MemberDetailRouteState.Agent ->
            routeState.server.id.value.takeIf { routeState.accessMode.allowsPrivateAgentSurfaces }
        is MemberDetailRouteState.Human -> routeState.server.id.value
        MemberDetailRouteState.Loading,
        is MemberDetailRouteState.Error -> null
    }
        ?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * Web `canViewPrivateAgentSurfaces`: an agent's tabs beyond Profile (and the details they carry) are
 * for the server's owners and admins and for the agent's creator. A cache replay answers the same
 * as the live page because the stored record keeps the viewer's role (#proj-mobile:#236).
 */
internal fun MemberDetailRouteState.Agent.viewerSeesPrivateAgentSurfaces(detail: AgentDetailSnapshot?): Boolean =
    accessMode.allowsPrivateAgentSurfaces &&
        (server.permissions.canManageSettings || detail?.summary?.creatorId == currentUserId)

/**
 * Web AgentDetailPanel `canManageAgentAccess` (botiverse/slock 5828cdce3e): who sees the Apps tab's
 * Revoke access and per-grant Revoke ([canManageAgentAppAccess]), never on another server's agent
 * (a bounded public projection). Everyone else reads the rows and their details.
 */
internal fun MemberDetailRouteState.Agent.viewerManagesAgentAppAccess(detail: AgentDetailSnapshot?): Boolean =
    accessMode.allowsPrivateAgentSurfaces &&
        canManageAgentAppAccess(
            serverRole = server.role,
            currentUserId = currentUserId,
            creatorType = detail?.summary?.creatorType,
            creatorId = detail?.summary?.creatorId,
        )

internal data class MemberDetailLoadPlan(
    val priority: DataRequestPriority,
    val showInitialLoading: Boolean,
    val keepCachedContentOnFailure: Boolean
)

/**
 * A refresh proving the account can no longer read the agent: gone (Empty / 404) or a non-auth
 * 403. The cached detail is evicted and the page shows the terminal state (Tenny rule 2).
 */
internal fun DataResult<*>.isAgentDetailAccessLost(): Boolean =
    this is DataResult.Empty || isConversationAccessLost()

internal fun memberDetailLoadPlan(hasCachedDetail: Boolean): MemberDetailLoadPlan =
    if (hasCachedDetail) {
        MemberDetailLoadPlan(
            priority = DataRequestPriority.Background,
            showInitialLoading = false,
            keepCachedContentOnFailure = true
        )
    } else {
        MemberDetailLoadPlan(
            priority = DataRequestPriority.Visible,
            showInitialLoading = true,
            keepCachedContentOnFailure = false
        )
    }

internal enum class MemberDetailFailureKind {
    Empty,
    Error,
    NoPermission
}

internal enum class MemberDetailFailureTransition {
    KeepCached,
    SetDetailEmpty,
    SetDetailError,
    SetDetailNoPermission
}

internal data class MemberDetailFailureResolution(
    val transition: MemberDetailFailureTransition,
    val routeErrorCode: String? = null
)

/**
 * Keep an already rendered agent detail authoritative while its best-effort refresh fails.
 * A cold route must still surface the terminal result instead of presenting stale content.
 */
internal fun memberDetailFailureResolution(
    loadPlan: MemberDetailLoadPlan,
    failure: MemberDetailFailureKind,
    code: String = ""
): MemberDetailFailureResolution {
    if (loadPlan.keepCachedContentOnFailure) {
        return MemberDetailFailureResolution(MemberDetailFailureTransition.KeepCached)
    }
    return when (failure) {
        MemberDetailFailureKind.Empty ->
            MemberDetailFailureResolution(
                transition = MemberDetailFailureTransition.SetDetailEmpty,
                routeErrorCode = "member_not_found"
            )
        MemberDetailFailureKind.Error ->
            MemberDetailFailureResolution(
                transition = MemberDetailFailureTransition.SetDetailError,
                routeErrorCode = code
            )
        MemberDetailFailureKind.NoPermission ->
            MemberDetailFailureResolution(
                transition = MemberDetailFailureTransition.SetDetailNoPermission,
                routeErrorCode = code
            )
    }
}

internal fun memberDetailInitialRouteState(
    identity: MemberDetailIdentity?,
    activeServerId: String?,
    agentDetailState: SharedAgentDetailState,
    serverMembersState: SharedServerMembersState,
    /**
     * The signed-in account ([MemberDetailRouteProvider.accountBinding]): a person's roster seed
     * knows the viewer, so their own profile shows "you" and Edit description from the first frame.
     */
    currentUserId: String? = null
): MemberDetailRouteState? {
    identity ?: return null
    val sourceServerId = identity.sourceServerId?.value?.trim()?.takeIf { it.isNotBlank() }
    val activeServer = activeServerId?.trim()?.takeIf { it.isNotBlank() }
    val expectedServerId = sourceServerId ?: activeServer
    val entries =
        when {
            sourceServerId != null -> listOfNotNull(serverMembersState.entriesByServerId[sourceServerId])
            activeServer != null -> listOfNotNull(serverMembersState.entriesByServerId[activeServer])
            else -> serverMembersState.entriesByServerId.values.toList()
        }
    val matches =
        entries.mapNotNull { entry ->
            val server = entry.server ?: return@mapNotNull null
            val serverId = server.id.value.trim()
            if (entry.serverId.trim() != serverId || (expectedServerId != null && serverId != expectedServerId)) {
                return@mapNotNull null
            }
            val member =
                entry.members.firstOrNull {
                    it.id == identity.memberId && it.kind == identity.kind
                } ?: return@mapNotNull null
            val memberServerId = member.serverId?.value?.trim()?.takeIf { it.isNotBlank() }
            if (memberServerId != null && memberServerId != serverId) return@mapNotNull null
            server to member
        }
    if (matches.size != 1) return null
    val (server, member) = matches.single()
    return when (identity.kind) {
        // Roster projections are not authoritative agent-profile cache entries. In particular,
        // their `roleDescription` historically carried a runtime-shaped string and could make the
        // first frame look Loaded before GET /agents/:id completed. Agent detail therefore always
        // starts cold; only the generation-fenced canonical response may enter Content.
        MemberKind.Agent -> null
        MemberKind.Human ->
            MemberDetailRouteState.Human(
                server = server,
                currentUserId = currentUserId?.trim()?.takeIf { it.isNotBlank() },
                member = member.copy(serverId = identity.sourceServerId ?: member.serverId),
                accessMode = identity.memberDetailAccessMode(activeServer)
            )
        MemberKind.System -> null
        // External projections never open a member detail route.
        MemberKind.External -> null
    }
}

/**
 * Whether the member-detail route takes the state its load answered ([loaded]) over [shown].
 *
 * An agent's answer lands only while its request is still current ([agentRequestCurrent], the
 * [MembersAgentProfileRequestFence]). That fence never matches a person, so a person's answer is
 * not gated on it: it lands, and with it the viewer ([MemberDetailRouteState.Human.currentUserId]).
 * A failed read keeps the person's roster seed already on screen, as a cached agent page keeps
 * its cache; a cold open shows the failure.
 */
internal fun memberDetailAppliesLoadedRoute(
    identity: MemberDetailIdentity,
    shown: MemberDetailRouteState,
    loaded: MemberDetailRouteState,
    agentRequestCurrent: Boolean
): Boolean =
    if (identity.kind == MemberKind.Agent) {
        agentRequestCurrent
    } else {
        loaded !is MemberDetailRouteState.Error ||
            (shown as? MemberDetailRouteState.Human)?.member?.id != identity.memberId
    }

internal fun seedRemoteAgentProfile(
    agentDetailStore: SharedAgentDetailStore,
    member: MemberProfile
) {
    // Agent detail state is keyed by member id, while joint-channel identities are
    // server-scoped. Clear a same-id local detail before installing the remote public
    // profile so data from the active server cannot win the seed no-op guard.
    agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
    agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(member))
}

internal fun membersAgentDetailOwnerForSession(
    environmentId: String?,
    accountId: String?,
    currentServerId: String?,
    joinedServerIds: Collection<String>,
    expectedServerId: ServerId,
    requireCurrentServer: Boolean
): SharedAgentDetailOwner? {
    val normalizedCurrentServerId =
        currentServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedExpectedServerId =
        expectedServerId.value.trim().takeIf { it.isNotBlank() } ?: return null
    if (
        requireCurrentServer &&
        (
            normalizedCurrentServerId != normalizedExpectedServerId ||
                joinedServerIds.none { it.trim() == normalizedExpectedServerId }
        )
    ) {
        return null
    }
    return sharedAgentDetailOwner(
        environmentId = environmentId,
        accountId = accountId,
        serverId = ServerId(normalizedExpectedServerId)
    )
}

internal class MemberDetailRouteProvider(
    val bridge: BridgeModule,
    val authStore: RaftAuthSessionStore,
    val apiEnvironmentStore: SharedApiEnvironmentStore,
    val memberRepository: MemberRepository,
    val channelRepository: ChannelRepository,
    val settingsRepository: build.raft.app.data.SettingsServerManagementRepository,
    val agentIssueReportRepository: AgentIssueReportRepository,
    val agentDetailStore: SharedAgentDetailStore,
    val selectedServerStore: SelectedServerStore,
    val agentPanelTabOrderStore: SharedAgentPanelTabOrderStore,
    val serverMembersStore: build.raft.app.data.SharedServerMembersStore,
    val computersStore: SharedComputersStore,
    val createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator,
    val createAgentFormStore: SharedCreateAgentFormStore,
    val avatarCacheRuntime: RaftAvatarCacheRuntime,
    val avatarNativeCache: RaftAvatarNativeCache,
    val conversationIdentityProvider: build.raft.app.data.SharedConversationIdentityProvider?,
    val sharedHomeStore: SharedHomeStore?,
    val storeScope: RaftShellStoreScope,
    val agentsApiClient: build.raft.app.data.RaftAgentsApiClient? = null,
    val runtimeAccountUsageStore: SharedRuntimeAccountUsageStore? = null,
    val computersOwner: suspend () -> SharedComputersOwner? = { null },
    val serverFeatureFlags: build.raft.app.data.SharedServerFeatureFlags? = null,
    val activityOverviewRepository: build.raft.app.data.AgentActivityOverviewRepository? = null,
    val agentControlsApi: RaftAgentControlsApi? = null,
    /** Data-layer plan step 5: last canonical agent details, keyed by account + server + agent. */
    val agentDetailCache: build.raft.app.data.RaftAgentDetailCache? = null,
    /** Realtime `agent:updated`: the open Profile re-reads its agent. */
    val agentUpdatedSignals: build.raft.app.data.RaftAgentUpdatedSignals? = null,
    /** Synchronous (environment, account) of the process auth binding, for first-frame reads. */
    val accountBinding: () -> Pair<String, String>? = { null },
    /** DM opens: Home's DM at once, else one request per server + peer for any number of taps. */
    val directMessageOpens: build.raft.app.data.RaftDirectMessageOpens = build.raft.app.data.RaftDirectMessageOpens.shared,
    /** Remove member's confirmation: which of the person's Computers go offline (slock #13547). */
    val memberRemovalImpactRepository: build.raft.app.data.MemberRemovalImpactRepository? = null,
    /**
     * Runs the agent detail cache's write-through of confirmed edits: the route's composition scope (cancelled
     * with the route); a write-through already started finishes ([confirmCachedAgentDetailAsync]).
     */
    private val cacheScope: kotlinx.coroutines.CoroutineScope
) {
    /**
     * A confirmed edit of the shown agent (the server answered): the cached detail, in memory and on disk
     * (whitelisted record), follows the store's detail. Nothing cached / a cache replay: nothing to do.
     */
    private fun confirmCachedAgentDetailAsync() {
        if (!cacheScope.isActive) return
        // Started at once and finished even if the route closes right after the edit (one bounded write).
        cacheScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            // Off the main thread: one encode + SQL write.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Default) { confirmCachedAgentDetail() }
        }
    }

    private suspend fun confirmCachedAgentDetail() {
        val cache = agentDetailCache ?: return
        val state = agentDetailStore.state.value
        if (state.detailFromCache) return
        val owner = state.detailOwner ?: return
        val detail = state.detail ?: return
        if (state.detailServerId != owner.serverId || state.agentId != detail.agentId) return
        val key = owner.agentDetailCacheKey(detail.agentId)
        val cached = cache.peek(key) ?: return
        // A failed Apps re-read is this visit's news, not the agent's: the cached page leaves it out.
        cache.confirm(key, cached.copy(detail = detail.copy(appLogins = detail.appLogins.copy(refreshError = null))))
    }

    /**
     * First-frame read of [memberId]'s cached canonical detail on [serverId] for the bound account,
     * without suspending; null when nothing is cached or the account is not known yet.
     */
    fun peekCachedAgentDetail(memberId: MemberId, serverId: ServerId?): Pair<SharedAgentDetailOwner, build.raft.app.data.RaftCachedAgentDetail>? {
        val cache = agentDetailCache ?: return null
        val (environmentId, accountId) = accountBinding() ?: return null
        val owner = sharedAgentDetailOwner(environmentId, accountId, serverId) ?: return null
        val cached = cache.peek(owner.agentDetailCacheKey(memberId)) ?: return null
        if (cached.detail.agentId != memberId || cached.server.id.value.trim() != owner.serverId.value) return null
        return owner to cached
    }

    /** The identity the shared network client currently authenticates as (task #137 usage reads). */
    suspend fun currentRuntimeAccountUsageOwner(): SharedComputersOwner? = computersOwner()

    suspend fun currentAgentDetailOwner(
        expectedServerId: ServerId,
        requireCurrentServer: Boolean = false
    ): SharedAgentDetailOwner? {
        val session = authStore.readSession() ?: return null
        return membersAgentDetailOwnerForSession(
            environmentId = apiEnvironmentStore.current().apiBaseUrl,
            accountId = session.user.id,
            currentServerId = session.currentServerId,
            joinedServerIds = session.servers.map { it.id },
            expectedServerId = expectedServerId,
            requireCurrentServer = requireCurrentServer
        )
    }

    suspend fun applyRuntimeConfigProjection(
        authority: MembersAgentProfileEditAuthority,
        config: RuntimeConfig,
        /** The read-back's `modelLimits.defaultsReported`: the signal for the saved config. */
        modelLimitsDefaultsReported: Boolean? = null
    ): Boolean {
        val expectedOwner = authority.ownerOrNull() ?: return false
        if (currentAgentDetailOwner(authority.serverId, requireCurrentServer = true) != expectedOwner) return false
        agentDetailStore.dispatch(
            SharedAgentDetailIntent.ApplyRuntimeConfigProjection(
                owner = expectedOwner,
                agentId = authority.memberId,
                config = config,
                profileGeneration = authority.profileGeneration,
                modelLimitsDefaultsReported = modelLimitsDefaultsReported
            )
        )
        val projection = agentDetailStore.state.value.runtimeConfigProjection
        val applied =
            projection?.owner == expectedOwner &&
                projection.agentId == authority.memberId &&
                projection.profileGeneration == authority.profileGeneration &&
                projection.config == config
        // Confirmed (the save answered): the cached page follows (its disk record never holds the values).
        if (applied) confirmCachedAgentDetail()
        return applied
    }

    /**
     * Remove member (web HumanProfileActions `removeThisMember`, slock #16386):
     * `DELETE servers/:id/members/:memberId` (optionally with `{ revokeComputers: true, computerIds }`
     * to disconnect the person's attached Computers in one step) on the server the profile was opened
     * on; the roster drops the person once the server agrees.
     */
    suspend fun removeHumanMember(
        expectedServerId: String,
        memberId: String,
        revokeComputerIds: List<String> = emptyList(),
    ): DataResult<SettingsServerActionResult> {
        if (!membersHumanRoleUpdateServerMatches(expectedServerId, selectedServerStore.selectedServerId.value)) {
            return DataResult.Error(ScreenError("member_remove_server_changed", retryable = false))
        }
        val result = settingsRepository.removeServerMember(memberId, revokeComputerIds)
        if (result is DataResult.Content && result.data.success) {
            serverMembersStore.removeMember(serverId = ServerId(expectedServerId), memberId = MemberId(memberId))
        }
        return result
    }

    suspend fun updateHumanRole(
        expectedServerId: String,
        memberId: String,
        role: MembersHumanServerRole
    ): DataResult<SettingsServerActionResult> {
        if (!membersHumanRoleUpdateServerMatches(expectedServerId, selectedServerStore.selectedServerId.value)) {
            return DataResult.Error(ScreenError("member_role_server_changed", retryable = false))
        }
        return settingsRepository.updateServerMemberRole(
            SettingsServerMemberRoleUpdateRequest(memberId = memberId, role = role.wireValue)
        )
    }

    /**
     * Self profile description edit (web HumanDetailPanel, botiverse/slock #15664): `PATCH /api/auth/me`
     * with `{ description }` (explicit `null` clears), then patches all cached rosters/profiles for
     * this user so reopened profiles and member lists stay in sync.
     */
    suspend fun updateHumanDescription(
        memberId: MemberId,
        description: String?
    ): DataResult<build.raft.app.data.AccountDescriptionUpdateResult> {
        val targetId = memberId.value.trim()
        val startEnv = apiEnvironmentStore.current().apiBaseUrl
        val startUserId = authStore.readSession()?.user?.id?.trim()
        if (startUserId.isNullOrBlank() || startUserId != targetId) {
            return DataResult.Error(ScreenError("member_description_account_changed", retryable = false))
        }
        val result = settingsRepository.updateAccountDescription(description)
        if (result is DataResult.Content) {
            val endEnv = apiEnvironmentStore.current().apiBaseUrl
            val endUserId = authStore.readSession()?.user?.id?.trim()
            if (endEnv == startEnv && endUserId == startUserId) {
                serverMembersStore.updateHumanDescription(memberId, result.data.description)
            }
        }
        return result
    }

    suspend fun cachedContext(
        identity: MemberDetailIdentity
    ): MemberDetailRouteState.Agent? {
        if (identity.kind != MemberKind.Agent) return null
        val session = authStore.readSession() ?: return null
        val serverId = session.currentServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!identity.memberDetailAccessMode(serverId).allowsPrivateAgentSurfaces) return null
        val selectedId = identity.memberId
        val state = agentDetailStore.state.value
        val detail = state.detail
        if (
            state.agentId != selectedId ||
            state.detailServerId?.value?.trim() != serverId ||
            detail == null ||
            detail.agentId != selectedId ||
            detail.summary?.id?.trim()?.takeIf { it.isNotBlank() }?.let { it != selectedId.value } == true
        ) {
            return null
        }
        val server = session.servers.firstOrNull { it.id == serverId }?.toServerSummary() ?: return null
        return MemberDetailRouteState.Agent(
            server = server,
            currentUserId = session.user.id
        )
    }

    suspend fun loadMember(
        identity: MemberDetailIdentity,
        agentOwner: SharedAgentDetailOwner? = null,
        agentProfileGeneration: Long = 0L,
        isCurrentRequest: () -> Boolean = { true },
        hasCachedAgentDetail: Boolean = false,
        /** The roster seed the opener left for a remote agent, read before the route cleared it. */
        remoteAgentSeed: AgentDetailSnapshot? = null,
        /** The opener's label for the remote member's server (joint roster `serverName`). */
        sourceServerName: String? = null,
        /** The channel a person's profile was opened from ([RaftMemberDetailPayload.profileChannelId]). */
        profileChannelId: ChannelId? = null
    ): MemberDetailRouteState {
        val session = authStore.readSession()
        val serverId = session?.currentServerId
        val server = session?.servers?.firstOrNull { it.id == serverId }?.toServerSummary()
        if (session == null || serverId.isNullOrBlank() || server == null) {
            return MemberDetailRouteState.Error("server_required")
        }
        return identity.dispatchMemberDetailLookup(
            currentServerId = serverId,
            loadHuman = { selectedId, sourceServerId, accessMode ->
                loadHumanMember(
                    selectedId = selectedId,
                    profileServerId = sourceServerId ?: ServerId(serverId),
                    server = server,
                    currentUserId = session.user.id,
                    accessMode = accessMode,
                    profileChannelId = profileChannelId
                )
            },
            loadAgent = { selectedId, accessMode ->
                when (accessMode) {
                    MemberDetailAccessMode.LocalFull ->
                        loadAgentMember(
                            selectedId,
                            server,
                            session.user.id,
                            agentOwner,
                            agentProfileGeneration,
                            isCurrentRequest,
                            hasCachedDetail = hasCachedAgentDetail
                        )
                    MemberDetailAccessMode.RemoteProfileOnly -> {
                        val sourceServerId = checkNotNull(identity.sourceServerId)
                        loadAgentProfileOnly(
                            selectedId = selectedId,
                            profileServerId = sourceServerId,
                            viewerServerId = ServerId(serverId),
                            profileServer =
                                session.servers
                                    .firstOrNull { it.id == sourceServerId.value }
                                    ?.toServerSummary()
                                    ?: ServerSummary(
                                        id = sourceServerId,
                                        // Not a server of this account: the joint roster's label,
                                        // else none (the "From" row never shows a raw id).
                                        name = sourceServerName?.trim().orEmpty()
                            ),
                            seed = remoteAgentSeed,
                            currentUserId = session.user.id,
                            owner = agentOwner,
                            profileGeneration = agentProfileGeneration,
                            isCurrentRequest = isCurrentRequest
                        )
                    }
                }
            },
            unsupported = { _, _ -> MemberDetailRouteState.Error("member_kind_unsupported") }
        )
    }

    private suspend fun loadAgentProfileOnly(
        selectedId: MemberId,
        profileServerId: ServerId,
        viewerServerId: ServerId,
        profileServer: ServerSummary,
        seed: AgentDetailSnapshot?,
        currentUserId: String?,
        owner: SharedAgentDetailOwner?,
        profileGeneration: Long,
        isCurrentRequest: () -> Boolean
    ): MemberDetailRouteState {
        // Web AgentDetailPanel `isRemoteJointAgent` (agentStore `ensureAgentProfile`): the
        // viewer's server answers `GET /agents/:id` for a peer agent of a joint channel the viewer
        // shares (routes/agents.ts `canUserSeeAgentThroughJointChannel` -> `toJointAgentProfile`:
        // description, status and activity, no runtime/control plane). The roster facts the joint
        // channel holds (no description, no presence) are only the fallback when that read fails.
        val rosterProfile =
            serverMembersStore.memberProfile(profileServerId, selectedId.value)
                ?: serverMembersStore.memberProfile(viewerServerId, selectedId.value)
        val known =
            membersRemoteAgentProfileSnapshot(
                agentId = selectedId,
                seed = seed,
                rosterProfile = rosterProfile,
                jointActor = serverMembersStore.jointActors?.current(viewerServerId, selectedId.value, MemberKind.Agent)
            )
        val fetched =
            agentsApiClient?.publicAgentDetail(selectedId)
                ?: DataResult.Error(ScreenError("agent_profile_unavailable", retryable = false))
        val fetchedDetail =
            (fetched as? DataResult.Content)?.data?.takeIf {
                it.agentId == selectedId && it.summary?.id == selectedId.value
            }
        val detail =
            fetchedDetail?.let { remote -> remote.withRemoteRosterFallback(known) }
                ?: known
                ?: return when (fetched) {
                    is DataResult.Content -> MemberDetailRouteState.Error("member_identity_mismatch")
                    is DataResult.Empty -> MemberDetailRouteState.Error("member_not_found")
                    is DataResult.Error -> MemberDetailRouteState.Error(fetched.error)
                    is DataResult.NoPermission -> MemberDetailRouteState.Error(fetched.blocker.code)
                }
        if (!isCurrentRequest() || owner == null || currentAgentDetailOwner(profileServerId) != owner) {
            return MemberDetailRouteState.Error("member_request_stale")
        }
        agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
        agentDetailStore.dispatch(SharedAgentDetailIntent.SelectMember(selectedId, owner, profileGeneration))
        agentDetailStore.dispatch(
            SharedAgentDetailIntent.SetDetail(
                agentId = selectedId,
                detail = detail,
                serverId = profileServerId,
                owner = owner,
                profileGeneration = profileGeneration
            )
        )
        val serverName =
            profileServer.name.takeIf { it.isNotBlank() && it != profileServerId.value }
                ?: rosterProfile?.serverName?.takeIf { it.isNotBlank() }
                ?: rosterProfile?.serverSlug?.takeIf { it.isNotBlank() }
                ?: ""
        if (fetchedDetail != null) {
            // Agent detail hydration carries the canonical avatarUrl and description; seed it back
            // so the scoped roster cache drops a stale pixel fallback in-session.
            serverMembersStore.seedProfiles(
                profileServerId,
                listOf(detail.toStaticMembersProfile(profileServer.copy(id = profileServerId, name = serverName)))
            )
        }
        return MemberDetailRouteState.Agent(
            server = profileServer.copy(id = profileServerId, name = serverName),
            currentUserId = currentUserId,
            accessMode = MemberDetailAccessMode.RemoteProfileOnly
        )
    }

    private suspend fun loadAgentMember(
        selectedId: MemberId,
        server: ServerSummary,
        currentUserId: String?,
        owner: SharedAgentDetailOwner?,
        profileGeneration: Long,
        isCurrentRequest: () -> Boolean,
        hasCachedDetail: Boolean = false
    ): MemberDetailRouteState {
        // A roster seed has no revision or canonical runtime authority and is never kept as if it
        // were a successful response. Only a cached canonical response for this account + server
        // + agent (data-layer plan step 5) makes this a background refresh.
        val loadPlan = memberDetailLoadPlan(hasCachedDetail = hasCachedDetail)
        val presenceRequest =
            serverMembersStore.beginAgentDetailPresenceProjection(server.id, selectedId)
        val cacheTicket = agentDetailCache?.ticket()
        val detailResult = memberRepository.getAgentDetail(loadPlan.priority, selectedId)
        // Tenny rule 1: the answer is the requesting account's data for this agent even when the
        // page moved on (server switch); the cache keeps it under that key. Rule 2: lost access
        // (non-auth 403 / 404 / gone) evicts it.
        if (owner != null && agentDetailCache != null && cacheTicket != null) {
            val cacheKey = owner.agentDetailCacheKey(selectedId)
            when {
                detailResult is DataResult.Content && detailResult.data.agentId == selectedId ->
                    agentDetailCache.write(
                        cacheKey,
                        build.raft.app.data.RaftCachedAgentDetail(server, currentUserId, detailResult.data),
                        cacheTicket
                    )
                detailResult.isAgentDetailAccessLost() -> agentDetailCache.evict(cacheKey)
            }
        }
        if (
            !isCurrentRequest() ||
            owner == null ||
            currentAgentDetailOwner(server.id, requireCurrentServer = true) != owner
        ) {
            return MemberDetailRouteState.Error("member_request_stale")
        }
        // Lost access is terminal even over a cached page: the evicted detail must not stay.
        val failurePlan =
            if (detailResult.isAgentDetailAccessLost()) memberDetailLoadPlan(hasCachedDetail = false) else loadPlan
        return when (detailResult) {
            is DataResult.Content -> {
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SelectMember(selectedId, owner, profileGeneration)
                )
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetail(
                        agentId = selectedId,
                        detail = detailResult.data,
                        serverId = server.id,
                        owner = owner,
                        profileGeneration = profileGeneration
                    )
                )
                serverMembersStore.applyAgentDetailSnapshot(presenceRequest, detailResult.data)
                // Same reason as the remote-profile branch: publish the freshly loaded
                // avatar/profile fields so in-session roster observers update.
                serverMembersStore.seedProfiles(
                    server.id,
                    listOf(detailResult.data.toStaticMembersProfile(server))
                )
                MemberDetailRouteState.Agent(
                    server = server,
                    currentUserId = currentUserId
                )
            }
            is DataResult.Empty -> {
                resolveAgentDetailFailure(
                    selectedId = selectedId,
                    server = server,
                    currentUserId = currentUserId,
                    resolution = memberDetailFailureResolution(failurePlan, MemberDetailFailureKind.Empty),
                    resultLabel = "empty"
                )
            }
            is DataResult.Error -> {
                resolveAgentDetailFailure(
                    selectedId = selectedId,
                    server = server,
                    currentUserId = currentUserId,
                    resolution = memberDetailFailureResolution(
                        failurePlan,
                        MemberDetailFailureKind.Error,
                        detailResult.error.code
                    ),
                    resultLabel = "error",
                    diagnosticCode = detailResult.error.code,
                    screenError = detailResult.error
                )
            }
            is DataResult.NoPermission -> {
                resolveAgentDetailFailure(
                    selectedId = selectedId,
                    server = server,
                    currentUserId = currentUserId,
                    resolution = memberDetailFailureResolution(
                        failurePlan,
                        MemberDetailFailureKind.NoPermission,
                        detailResult.blocker.code
                    ),
                    resultLabel = "no_permission",
                    diagnosticCode = detailResult.blocker.code,
                    permissionBlocker = detailResult.blocker
                )
            }
        }
    }

    private fun resolveAgentDetailFailure(
        selectedId: MemberId,
        server: ServerSummary,
        currentUserId: String?,
        resolution: MemberDetailFailureResolution,
        resultLabel: String,
        diagnosticCode: String? = null,
        screenError: ScreenError? = null,
        permissionBlocker: PermissionBlocker? = null
    ): MemberDetailRouteState =
        when (resolution.transition) {
            MemberDetailFailureTransition.KeepCached -> {
                bridge.log(
                    "member_detail_background_refresh_kept_cached agentId=${selectedId.value} " +
                        "result=$resultLabel" +
                        diagnosticCode?.let { " code=$it" }.orEmpty()
                )
                MemberDetailRouteState.Agent(server = server, currentUserId = currentUserId)
            }
            MemberDetailFailureTransition.SetDetailEmpty -> {
                agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailEmpty(selectedId))
                MemberDetailRouteState.Error(requireNotNull(resolution.routeErrorCode))
            }
            MemberDetailFailureTransition.SetDetailError -> {
                val resolvedError = screenError
                    ?: ScreenError(requireNotNull(resolution.routeErrorCode), retryable = false)
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetailError(selectedId, resolvedError)
                )
                MemberDetailRouteState.Error(resolvedError)
            }
            MemberDetailFailureTransition.SetDetailNoPermission -> {
                val resolvedBlocker = permissionBlocker
                    ?: PermissionBlocker(requireNotNull(resolution.routeErrorCode))
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetailNoPermission(selectedId, resolvedBlocker)
                )
                MemberDetailRouteState.Error(
                    ScreenError(resolvedBlocker.code, retryable = false)
                )
            }
        }

    suspend fun loadAgentActivity(
        agentId: MemberId
    ): DataResult<List<AgentActivityEntry>> {
        return when (val activityResult = memberRepository.getAgentActivity(DataRequestPriority.Visible, agentId)) {
            is DataResult.Content -> DataResult.Content(activityResult.data.activity)
            is DataResult.Empty -> activityResult
            is DataResult.Error -> {
                bridge.log("member_detail_agent_activity_load_failed agentId=${agentId.value} code=${activityResult.error.code}")
                activityResult
            }
            is DataResult.NoPermission -> {
                bridge.log("member_detail_agent_activity_load_blocked agentId=${agentId.value} code=${activityResult.blocker.code}")
                activityResult
            }
        }
    }

    private suspend fun loadHumanMember(
        selectedId: MemberId,
        profileServerId: ServerId,
        server: ServerSummary,
        currentUserId: String?,
        accessMode: MemberDetailAccessMode,
        profileChannelId: ChannelId? = null
    ): MemberDetailRouteState {
        agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
        // Opened from a channel (web ProfilePanel `?channelId=`): read in the channel's server, the
        // viewer's, which names the person's servers in it; the answer's server fields stand.
        val memberResult =
            if (profileChannelId != null) {
                memberRepository.getMemberProfileInChannel(DataRequestPriority.Visible, selectedId, server.id, profileChannelId)
            } else {
                memberRepository.getMemberProfile(DataRequestPriority.Visible, selectedId, profileServerId)
            }
        return when (memberResult) {
            is DataResult.Content -> {
                val member =
                    if (profileChannelId != null) {
                        memberResult.data.copy(serverId = memberResult.data.serverId ?: profileServerId)
                    } else {
                        memberResult.data.copy(serverId = profileServerId)
                    }
                // The channel's `servers` answer belongs to this open, not to the server roster.
                serverMembersStore.seedProfiles(profileServerId, listOf(member.copy(servers = null)))
                MemberDetailRouteState.Human(server, currentUserId, member, accessMode)
            }
            is DataResult.Empty ->
                MemberDetailRouteState.Error("member_not_found")
            is DataResult.Error ->
                MemberDetailRouteState.Error(memberResult.error)
            is DataResult.NoPermission ->
                MemberDetailRouteState.Error(memberResult.blocker.code)
        }
    }

    fun updateCachedMemberProfile(member: MemberProfile, expectedServerId: ServerId) {
        val state = agentDetailStore.state.value
        val detail = state.detail ?: return
        if (
            state.agentId != member.id ||
            state.detailServerId != expectedServerId ||
            detail.agentId != member.id
        ) {
            return
        }
        agentDetailStore.dispatch(
            SharedAgentDetailIntent.SetDetail(
                member.id,
                detail.withMemberProfileProjection(member),
                expectedServerId,
                owner = state.detailOwner,
                profileGeneration = state.detailProfileGeneration
            )
        )
        // Confirmed profile / avatar edit: the cached page (memory and disk) follows.
        confirmCachedAgentDetailAsync()
    }

    /**
     * The Apps tab's revoke changed the shown agent's App grants ([AgentAppGrantsRevokeStore]): the
     * store's detail takes [intent], and the cached page follows, so a revoked App doesn't come back
     * from the cache on the next open.
     */
    fun updateAgentAppLogins(intent: SharedAgentDetailIntent) {
        agentDetailStore.dispatch(intent)
        confirmCachedAgentDetailAsync()
    }

    suspend fun runLifecycleAction(
        serverId: ServerId,
        agentId: MemberId,
        action: MembersAgentLifecycleAction,
        workspaceChoice: AgentArchiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep
    ): DataResult<AgentLifecycleActionResult> {
        val result =
            when (action) {
                MembersAgentLifecycleAction.Start ->
                    memberRepository.startAgent(DataRequestPriority.Foreground, agentId)
                MembersAgentLifecycleAction.Stop ->
                    memberRepository.stopAgent(DataRequestPriority.Foreground, agentId)
                MembersAgentLifecycleAction.Restart,
                MembersAgentLifecycleAction.ResetSession,
                MembersAgentLifecycleAction.ResetFull ->
                    memberRepository.resetAgent(
                        DataRequestPriority.Foreground,
                        build.raft.app.data.AgentResetRequest(
                            agentId,
                            action.resetMode ?: AgentResetMode.Restart
                        )
                    )
                MembersAgentLifecycleAction.Delete ->
                    serverMembersStore.deleteAgent(
                        serverId = serverId,
                        agentId = agentId,
                        workspaceChoice = workspaceChoice,
                        priority = DataRequestPriority.Foreground
                    )
                MembersAgentLifecycleAction.UnassignMachine ->
                    memberRepository.assignAgentMachine(
                        DataRequestPriority.Foreground,
                        AgentMachineAssignmentRequest(agentId, machineId = null)
                    )
            }
        // A deleted agent's page must not come back from the cache (memory or disk).
        if (action == MembersAgentLifecycleAction.Delete && result is DataResult.Content && result.data.success) {
            currentAgentDetailOwner(serverId)?.let { owner -> agentDetailCache?.evict(owner.agentDetailCacheKey(agentId)) }
        }
        return result
    }

    suspend fun updateAgentProfile(
        serverId: ServerId,
        request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile> =
        serverMembersStore.updateAgentProfile(
            serverId = serverId,
            request = request,
            priority = DataRequestPriority.Foreground
        )

    /**
     * The DM to open, or null while an earlier tap is still opening it (that tap opens the page).
     * A DM Home already lists opens without waiting on the server.
     */
    suspend fun openDirectMessage(member: MemberProfile): DataResult<OpenDirectMessageResult>? =
        openMemberDirectMessage(member, authStore, channelRepository, sharedHomeStore, directMessageOpens)

    suspend fun uploadAgentAvatar(
        serverId: ServerId,
        agentId: MemberId,
        file: PlatformFile
    ): DataResult<MemberProfile> =
        withContext(Dispatchers.Default) {
            serverMembersStore.uploadAgentAvatarWithAvatarCacheInvalidation(
                serverId = serverId,
                priority = DataRequestPriority.Foreground,
                request = AgentAvatarUploadRequest(agentId = agentId, file = file),
                avatarCacheRuntime = avatarCacheRuntime,
                avatarCacheNativeCache = avatarNativeCache
            )
        }

    suspend fun pickAgentAvatarFile(): DataResult<PlatformFile> {
        val response = bridge.threadAttachmentUpload(channelId = "agent-avatar-picker", imageOnly = true)
        val code = response?.optInt("code", 0) ?: 0
        return if (code == 204) {
            DataResult.Empty(EmptyReason.NoMembers)
        } else if (code !in 200..299) {
            val errorCode = response?.optString("errorCode").orEmpty().ifBlank { "agent_avatar_picker_failed" }
            DataResult.Error(ScreenError(code = errorCode, retryable = true))
        } else {
            val file = parseSharedThreadPickedPlatformFiles(response).firstOrNull()
            if (file != null) {
                DataResult.Content(file)
            } else {
                DataResult.Error(ScreenError(code = "agent_avatar_picker_empty", retryable = true))
            }
        }
    }

    fun copyMemberId(member: MemberProfile, successMessage: String) {
        bridge.copyToPasteboard(member.id.value)
        bridge.toast(successMessage)
    }

    fun copyText(text: String, successMessage: String) {
        bridge.copyToPasteboard(text)
        bridge.toast(successMessage)
    }

    fun copyDiagnostics(diagnostics: String, successMessage: String) =
        copyText(diagnostics, successMessage)

    suspend fun loadAgentPanelTabOrder(): DataResult<List<String>> =
        when (val result = settingsRepository.getServerSidebarOrder()) {
            is DataResult.Content -> DataResult.Content(result.data.agentPanelTabOrder)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }

    suspend fun updateAgentPanelTabOrder(tabs: List<MembersAgentDetailTab>): DataResult<List<String>> =
        when (
            val result = settingsRepository.updateServerSidebarOrder(
                build.raft.app.data.SettingsServerSidebarOrderUpdateRequest(
                    agentPanelTabOrder = tabs.toMembersAgentPanelTabOrderWireKeys()
                )
            )
        ) {
            is DataResult.Content -> DataResult.Content(result.data.agentPanelTabOrder)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }

    suspend fun reportIssue(
        server: ServerSummary,
        member: MemberProfile,
        detail: AgentDetailSnapshot?,
        request: SharedAgentIssueReportRequest
    ): DataResult<AgentIssueReportResult> {
        bridge.log("agent_diagnostics_report ${request.diagnostics.toMemberDetailDiagnosticsLine()}")
        val result = agentIssueReportRepository.submit(
            AgentIssueReportSubmission(
                server = server,
                member = member,
                detail = detail,
                description = request.description,
                diagnostics = request.diagnostics,
                includeRecentMessages = request.includeRecentMessages,
                includeLiveActivity = request.includeLiveActivity,
                includeActivityHistory = request.includeActivityHistory,
                includeRuntimeTranscript = request.includeRuntimeTranscript,
                liveActivity = request.liveActivity,
                title = request.title,
                locale = request.locale,
                includeMachineLogTail = request.includeMachineLogTail,
                canIncludeMachineLog = request.canIncludeMachineLog
            )
        )
        // Web shows a failed report only inline in the panel (with its trace ID), no toast.
        return result
    }

    suspend fun retryIssueTicket(server: ServerSummary, request: AgentIssueTicketRequest): DataResult<String> =
        agentIssueReportRepository.fileTicket(server.id, request)
}

private fun String.toMemberDetailDiagnosticsLine(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" | ")
        .take(1200)
