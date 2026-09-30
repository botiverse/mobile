package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.AuthenticatedRouteOperationRunner
import build.raft.app.data.ChannelActivityMuteSettings
import build.raft.app.data.ChannelProfileUpdateRequest
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.ChannelVisibility
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarOrderUpdateRequest
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerChannelMembersHandoff
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockOwnerScopedBoundedCache
import build.raft.app.data.SlockOwnerScopedCacheOwner
import build.raft.app.data.map
import build.raft.app.data.normalizedOrNull
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.typedPinnedOrLegacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ChannelSettingsLoadStatus {
    Loading,
    Content,
    Error,
    AuthorityRejected,
}

internal const val CHANNEL_SETTINGS_IDENTITY_MISMATCH = "channel_settings_identity_mismatch"

internal enum class ChannelSettingsAction {
    Mute,
    Pin,
    Profile,
    Visibility,
    Archive,
    Delete,
}

internal sealed interface ChannelSettingsEffect {
    data class Failure(val action: ChannelSettingsAction, val code: String) : ChannelSettingsEffect
    data class Updated(val action: ChannelSettingsAction) : ChannelSettingsEffect
    data object ProfileSaved : ChannelSettingsEffect
    data object Close : ChannelSettingsEffect
}

internal data class ChannelSettingsSnapshot(
    val channel: HomeChannelRow,
    val sidebarOrder: SettingsServerSidebarOrderSnapshot,
    val canManage: Boolean,
    val memberSlice: SharedServerChannelMembersSlice,
)

internal sealed interface ChannelSettingsSharedHandoff {
    data class Accepted(val snapshot: ChannelSettingsSnapshot) : ChannelSettingsSharedHandoff

    data object Unavailable : ChannelSettingsSharedHandoff

    data object Rejected : ChannelSettingsSharedHandoff
}

internal fun channelSettingsSharedHandoffSnapshot(
    owner: AuthenticatedRouteOwner,
    route: ChannelSettingsRoute,
    homeSnapshot: HomeSnapshot?,
    membersHandoff: SharedServerChannelMembersHandoff,
): ChannelSettingsSharedHandoff {
    val normalizedOwner = owner.normalizedOrNull() ?: return ChannelSettingsSharedHandoff.Rejected
    if (normalizedOwner.serverId != route.serverId.value.trim()) {
        return ChannelSettingsSharedHandoff.Rejected
    }
    val home = homeSnapshot ?: return ChannelSettingsSharedHandoff.Unavailable
    if (
        home.server.id.value.trim() != normalizedOwner.serverId ||
        home.currentMember.id.value.trim() != normalizedOwner.accountId
    ) {
        return ChannelSettingsSharedHandoff.Rejected
    }
    val channel =
        home.channels.firstOrNull { candidate ->
            candidate.channelId.value.trim() == route.channelId.value.trim()
        } ?: return ChannelSettingsSharedHandoff.Rejected
    val sidebarOrder = home.sidebarOrder ?: return ChannelSettingsSharedHandoff.Unavailable
    val acceptedMemberSlice =
        when (membersHandoff) {
            is SharedServerChannelMembersHandoff.Accepted -> membersHandoff.slice
            SharedServerChannelMembersHandoff.Unavailable ->
                SharedServerChannelMembersSlice(
                    environmentId = normalizedOwner.environmentId,
                    accountId = normalizedOwner.accountId,
                    serverId = normalizedOwner.serverId,
                    channelId = channel.channelId.value.trim(),
                    status = SharedServerMembersLoadStatus.InFlight,
                )
            SharedServerChannelMembersHandoff.Rejected -> return ChannelSettingsSharedHandoff.Rejected
        }
    if (
        acceptedMemberSlice.environmentId?.trim()?.trimEnd('/') != normalizedOwner.environmentId ||
        acceptedMemberSlice.accountId?.trim() != normalizedOwner.accountId ||
        acceptedMemberSlice.serverId?.trim() != normalizedOwner.serverId ||
        acceptedMemberSlice.channelId.trim() != channel.channelId.value.trim()
    ) {
        return ChannelSettingsSharedHandoff.Rejected
    }
    return ChannelSettingsSharedHandoff.Accepted(
        ChannelSettingsSnapshot(
            channel = channel,
            sidebarOrder = sidebarOrder,
            canManage = home.server.permissions.canManageSettings,
            memberSlice = acceptedMemberSlice,
        )
    )
}

private data class ChannelSettingsSnapshotCacheResource(
    val serverId: String,
    val channelId: String,
)

internal class ChannelSettingsSnapshotCache(
    maxEntries: Int = 64,
) {
    private val cache =
        SlockOwnerScopedBoundedCache<ChannelSettingsSnapshotCacheResource, ChannelSettingsSnapshot>(
            maxEntries = maxEntries,
            acceptsResource = { owner, resource -> owner.serverId == resource.serverId },
            acceptsValue = { owner, resource, snapshot ->
                snapshot.matchesCacheIdentity(owner, resource)
            },
        )

    fun get(owner: AuthenticatedRouteOwner, route: ChannelSettingsRoute): ChannelSettingsSnapshot? {
        val cacheOwner = SlockOwnerScopedCacheOwner.from(owner) ?: return null
        val resource = cacheResource(cacheOwner, route) ?: return null
        return cache.get(cacheOwner, resource)
    }

    fun put(
        owner: AuthenticatedRouteOwner,
        route: ChannelSettingsRoute,
        snapshot: ChannelSettingsSnapshot,
    ): Boolean {
        val cacheOwner = SlockOwnerScopedCacheOwner.from(owner) ?: return false
        val resource = cacheResource(cacheOwner, route) ?: return false
        return cache.put(cacheOwner, resource, snapshot)
    }

    fun remove(owner: AuthenticatedRouteOwner, route: ChannelSettingsRoute) {
        val cacheOwner = SlockOwnerScopedCacheOwner.from(owner) ?: return
        val resource = cacheResource(cacheOwner, route) ?: return
        cache.remove(cacheOwner, resource)
    }

    private fun cacheResource(
        owner: SlockOwnerScopedCacheOwner,
        route: ChannelSettingsRoute,
    ): ChannelSettingsSnapshotCacheResource? {
        val routeServerId = route.serverId.value.trim()
        val channelId = route.channelId.value.trim().takeIf(String::isNotBlank) ?: return null
        if (owner.serverId != routeServerId) return null
        return ChannelSettingsSnapshotCacheResource(
            serverId = owner.serverId,
            channelId = channelId,
        )
    }

    private fun ChannelSettingsSnapshot.matchesCacheIdentity(
        owner: SlockOwnerScopedCacheOwner,
        resource: ChannelSettingsSnapshotCacheResource,
    ): Boolean =
        owner.serverId == resource.serverId &&
            channel.channelId.value.trim() == resource.channelId &&
            memberSlice.serverId?.trim() == resource.serverId &&
            memberSlice.channelId.trim() == resource.channelId
}

internal data class ChannelSettingsState(
    val status: ChannelSettingsLoadStatus = ChannelSettingsLoadStatus.Loading,
    val channel: HomeChannelRow? = null,
    val sidebarOrder: SettingsServerSidebarOrderSnapshot? = null,
    val canManage: Boolean = false,
    val memberSlice: SharedServerChannelMembersSlice = SharedServerChannelMembersSlice(channelId = ""),
    val refreshing: Boolean = false,
    val writeActionsEnabled: Boolean = false,
    val actionInFlight: ChannelSettingsAction? = null,
    val profileStatus: ChannelProfileUpdateStatus = ChannelProfileUpdateStatus.Idle,
    val profileErrorCode: String? = null,
    val errorCode: String? = null,
    val effect: ChannelSettingsEffect? = null,
) {
    fun isPinned(channelId: String): Boolean =
        sidebarOrder?.typedPinnedOrLegacy()?.any {
            it.kind == "channel" && it.id == channelId
        } == true
}

internal sealed interface ChannelSettingsIntent {
    data object Load : ChannelSettingsIntent
    data object Refresh : ChannelSettingsIntent
    data object ToggleMute : ChannelSettingsIntent
    data object TogglePin : ChannelSettingsIntent
    data class UpdateProfile(val name: String, val description: String) : ChannelSettingsIntent
    data object MakePrivate : ChannelSettingsIntent
    data object Archive : ChannelSettingsIntent
    data object Delete : ChannelSettingsIntent
    data object ClearProfileStatus : ChannelSettingsIntent
    data object ConsumeEffect : ChannelSettingsIntent
}

internal interface ChannelSettingsDataSource {
    suspend fun load(): DataResult<ChannelSettingsSnapshot>
    suspend fun setMuted(muted: Boolean): DataResult<ChannelActivityMuteSettings>
    suspend fun togglePinned(): DataResult<SettingsServerSidebarOrderSnapshot>
    suspend fun updateProfile(name: String, description: String): DataResult<ChannelProfileUpdateResult>
    suspend fun makePrivate(): DataResult<Unit>
    suspend fun archive(): DataResult<Unit>
    suspend fun delete(): DataResult<Unit>
}

internal class RealChannelSettingsDataSource(
    private val route: ChannelSettingsRoute,
    private val authStore: SlockAuthSessionStore,
    owner: build.raft.app.data.AuthenticatedRouteOwner,
    gateway: AuthenticatedRouteAuthorityGateway,
    private val logger: (String) -> Unit = {},
) : ChannelSettingsDataSource {
    private val runner = AuthenticatedRouteOperationRunner(owner, gateway)

    override suspend fun load(): DataResult<ChannelSettingsSnapshot> =
        runner.run { receipt, networkClient ->
            val detail =
                RealConversationRepository(
                    sessionStore = authStore,
                    networkClient = networkClient,
                    routeServerIdProvider = { route.serverId.value },
                    warningLogger = { logger("conversation $it") },
                ).getConversationDetail(route.channelId, DataRequestPriority.Visible)
            val channel = (detail as? DataResult.Content)?.data?.toHomeChannelRow()
                ?: return@run detail.withoutContent()
            if (channel.channelId != route.channelId) {
                return@run DataResult.Error(
                    ScreenError(code = CHANNEL_SETTINGS_IDENTITY_MISMATCH, retryable = false)
                )
            }

            val settingsRepository = settingsRepository(networkClient)
            val sidebar = settingsRepository.getServerSidebarOrder(route.serverId)
            val sidebarOrder = (sidebar as? DataResult.Content)?.data
                ?: return@run sidebar.withoutContent()

            val memberResult = memberRepository(networkClient).getChannelMembers(
                priority = DataRequestPriority.Visible,
                channelId = route.channelId,
            )
            DataResult.Content(
                ChannelSettingsSnapshot(
                    channel = channel,
                    sidebarOrder = sidebarOrder,
                    canManage = receipt.canManageSettings,
                    memberSlice = memberResult.toChannelSettingsMemberSlice(route),
                )
            )
        }

    override suspend fun setMuted(muted: Boolean): DataResult<ChannelActivityMuteSettings> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).setChannelActivityMuted(route.channelId, muted)
        }

    override suspend fun togglePinned(): DataResult<SettingsServerSidebarOrderSnapshot> =
        runner.run { _, networkClient ->
            val settingsRepository = settingsRepository(networkClient)
            val currentResult = settingsRepository.getServerSidebarOrder(route.serverId)
            val current = (currentResult as? DataResult.Content)?.data
                ?: return@run currentResult.withoutContent()
            val target = SettingsServerSidebarPinnedItem(kind = "channel", id = route.channelId.value)
            val pinned = current.typedPinnedOrLegacy()
            val nextPinned =
                if (pinned.any { it == target }) pinned.filterNot { it == target } else pinned + target
            settingsRepository.updateServerSidebarOrder(
                serverId = route.serverId,
                request = SettingsServerSidebarOrderUpdateRequest(pinned = nextPinned),
            )
        }

    override suspend fun updateProfile(
        name: String,
        description: String,
    ): DataResult<ChannelProfileUpdateResult> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).updateChannelProfile(
                ChannelProfileUpdateRequest(route.channelId, name, description)
            )
        }

    override suspend fun makePrivate(): DataResult<Unit> =
        runner.run { _, networkClient ->
            channelRepository(networkClient)
                .updateChannel(route.channelId, ChannelVisibility.Private)
                .map { Unit }
        }

    override suspend fun archive(): DataResult<Unit> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).archiveChannel(route.channelId).map { Unit }
        }

    override suspend fun delete(): DataResult<Unit> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).deleteChannel(route.channelId).map { Unit }
        }

    private fun channelRepository(networkClient: build.raft.app.data.SlockNetworkClient): RealChannelRepository =
        RealChannelRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            warningLogger = { logger("channel $it") },
        )

    private fun memberRepository(networkClient: build.raft.app.data.SlockNetworkClient): RealMemberRepository =
        RealMemberRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            warningLogger = { logger("members_warning $it") },
            logger = { logger("members $it") },
        )

    private fun settingsRepository(networkClient: build.raft.app.data.SlockNetworkClient): RealSettingsRepository =
        RealSettingsRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            logger = { logger("settings $it") },
            warningLogger = { logger("settings_warning $it") },
        )
}

internal class ChannelSettingsStore(
    private val route: ChannelSettingsRoute,
    private val owner: AuthenticatedRouteOwner,
    private val dataSource: ChannelSettingsDataSource,
    private val snapshotCache: ChannelSettingsSnapshotCache,
    initialSharedHandoff: ChannelSettingsSharedHandoff = ChannelSettingsSharedHandoff.Unavailable,
    initialAuthorityAccepted: Boolean,
    private val scope: CoroutineScope,
) {
    private val initialSnapshot =
        if (initialAuthorityAccepted) {
            when (initialSharedHandoff) {
                is ChannelSettingsSharedHandoff.Accepted ->
                    initialSharedHandoff.snapshot
                        .takeIf { snapshot -> snapshotCache.put(owner, route, snapshot) }
                ChannelSettingsSharedHandoff.Unavailable -> snapshotCache.get(owner, route)
                ChannelSettingsSharedHandoff.Rejected -> null
            }
        } else {
            null
        }
    private val mutableState =
        MutableStateFlow(
            initialSnapshot?.toCachedState()
                ?: ChannelSettingsState(
                    memberSlice =
                        SharedServerChannelMembersSlice(
                            serverId = route.serverId.value,
                            channelId = route.channelId.value,
                        )
                )
        )
    val state: StateFlow<ChannelSettingsState> = mutableState.asStateFlow()

    private var loadGeneration = 0L
    private var actionGeneration = 0L
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    fun dispatch(intent: ChannelSettingsIntent) {
        when (intent) {
            ChannelSettingsIntent.Load -> load(force = false)
            ChannelSettingsIntent.Refresh -> load(force = true)
            ChannelSettingsIntent.ToggleMute -> {
                if (!mutableState.value.writeActionsEnabled) return
                val channel = mutableState.value.channel ?: return
                launchAction(ChannelSettingsAction.Mute) { dataSource.setMuted(!channel.activityMuted) }
            }
            ChannelSettingsIntent.TogglePin -> {
                if (!mutableState.value.writeActionsEnabled) return
                launchAction(ChannelSettingsAction.Pin, dataSource::togglePinned)
            }
            is ChannelSettingsIntent.UpdateProfile -> updateProfile(intent)
            ChannelSettingsIntent.MakePrivate -> {
                if (!mutableState.value.writeActionsEnabled) return
                val channel = mutableState.value.channel ?: return
                if (channelSettingsCanMakePrivate(channel)) {
                    launchAction(ChannelSettingsAction.Visibility, dataSource::makePrivate)
                }
            }
            ChannelSettingsIntent.Archive -> {
                if (!mutableState.value.writeActionsEnabled) return
                launchAction(ChannelSettingsAction.Archive, dataSource::archive)
            }
            ChannelSettingsIntent.Delete -> {
                if (!mutableState.value.writeActionsEnabled) return
                val channel = mutableState.value.channel ?: return
                if (channelSettingsCanDelete(channel)) {
                    launchAction(ChannelSettingsAction.Delete, dataSource::delete)
                }
            }
            ChannelSettingsIntent.ClearProfileStatus ->
                mutableState.value =
                    mutableState.value.copy(
                        profileStatus = ChannelProfileUpdateStatus.Idle,
                        profileErrorCode = null,
                    )
            ChannelSettingsIntent.ConsumeEffect ->
                mutableState.value = mutableState.value.copy(effect = null)
        }
    }

    fun invalidateAuthority() {
        loadGeneration += 1L
        actionGeneration += 1L
        loadJob?.cancel()
        actionJob?.cancel()
        loadJob = null
        actionJob = null
        snapshotCache.remove(owner, route)
        mutableState.value =
            ChannelSettingsState(
                status = ChannelSettingsLoadStatus.AuthorityRejected,
                memberSlice =
                    SharedServerChannelMembersSlice(
                        serverId = route.serverId.value,
                        channelId = route.channelId.value,
                        status = SharedServerMembersLoadStatus.Error,
                        errorCode = AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
                    ),
                errorCode = AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
            )
    }

    private fun load(force: Boolean) {
        if (mutableState.value.actionInFlight != null) return
        if (!force && (loadJob?.isActive == true || mutableState.value.status == ChannelSettingsLoadStatus.Content)) {
            return
        }
        loadJob?.cancel()
        loadGeneration += 1L
        val requestGeneration = loadGeneration
        val previous = mutableState.value
        mutableState.value =
            if (previous.channel == null) {
                previous.copy(
                    status = ChannelSettingsLoadStatus.Loading,
                    refreshing = false,
                    errorCode = null,
                )
            } else {
                previous.copy(refreshing = true, errorCode = null)
            }
        loadJob =
            scope.launch {
                val result = runDataSourceCall(dataSource::load)
                if (requestGeneration != loadGeneration) return@launch
                mutableState.value =
                    when (result) {
                        is DataResult.Content ->
                            if (snapshotCache.put(owner, route, result.data)) {
                                result.data.toResolvedState()
                            } else {
                                terminalReadFailureState(CHANNEL_SETTINGS_IDENTITY_MISMATCH)
                            }
                        is DataResult.Error ->
                            if (result.error.code == AUTHENTICATED_ROUTE_AUTHORITY_CHANGED) {
                                snapshotCache.remove(owner, route)
                                authorityRejectedState()
                            } else if (result.error.code == CHANNEL_SETTINGS_IDENTITY_MISMATCH) {
                                snapshotCache.remove(owner, route)
                                terminalReadFailureState(result.error.code)
                            } else if (previous.channel != null) {
                                previous.copy(refreshing = false, errorCode = result.error.code)
                            } else {
                                previous.copy(
                                    status = ChannelSettingsLoadStatus.Error,
                                    refreshing = false,
                                    errorCode = result.error.code,
                                )
                            }
                        is DataResult.NoPermission -> {
                            snapshotCache.remove(owner, route)
                            terminalReadFailureState(result.blocker.code)
                        }
                        is DataResult.Empty -> {
                            snapshotCache.remove(owner, route)
                            terminalReadFailureState(result.reason.name)
                        }
                    }
            }
    }

    private fun ChannelSettingsSnapshot.toCachedState(): ChannelSettingsState =
        ChannelSettingsState(
            status = ChannelSettingsLoadStatus.Content,
            channel = channel,
            sidebarOrder = sidebarOrder,
            canManage = canManage,
            memberSlice = memberSlice,
            writeActionsEnabled = false,
        )

    private fun ChannelSettingsSnapshot.toResolvedState(): ChannelSettingsState =
        ChannelSettingsState(
            status = ChannelSettingsLoadStatus.Content,
            channel = channel,
            sidebarOrder = sidebarOrder,
            canManage = canManage,
            memberSlice = memberSlice,
            writeActionsEnabled = true,
        )

    private fun terminalReadFailureState(code: String): ChannelSettingsState =
        ChannelSettingsState(
            status = ChannelSettingsLoadStatus.Error,
            memberSlice =
                SharedServerChannelMembersSlice(
                    serverId = route.serverId.value,
                    channelId = route.channelId.value,
                    status = SharedServerMembersLoadStatus.Error,
                    errorCode = code,
                ),
            errorCode = code,
        )

    private fun updateProfile(intent: ChannelSettingsIntent.UpdateProfile) {
        if (
            mutableState.value.status != ChannelSettingsLoadStatus.Content ||
            !mutableState.value.writeActionsEnabled ||
            mutableState.value.actionInFlight != null
        ) {
            return
        }
        mutableState.value =
            mutableState.value.copy(
                actionInFlight = ChannelSettingsAction.Profile,
                profileStatus = ChannelProfileUpdateStatus.InFlight,
                profileErrorCode = null,
                effect = null,
            )
        launchMutation(ChannelSettingsAction.Profile) {
            dataSource.updateProfile(intent.name, intent.description)
        }
    }

    private fun <T> launchAction(
        action: ChannelSettingsAction,
        call: suspend () -> DataResult<T>,
    ) {
        if (
            mutableState.value.status != ChannelSettingsLoadStatus.Content ||
            !mutableState.value.writeActionsEnabled ||
            mutableState.value.actionInFlight != null
        ) {
            return
        }
        mutableState.value = mutableState.value.copy(actionInFlight = action, effect = null)
        launchMutation(action, call)
    }

    private fun <T> launchMutation(
        action: ChannelSettingsAction,
        call: suspend () -> DataResult<T>,
    ) {
        loadGeneration += 1L
        loadJob?.cancel()
        loadJob = null
        actionJob?.cancel()
        actionGeneration += 1L
        val requestGeneration = actionGeneration
        actionJob =
            scope.launch {
                val result = runDataSourceCall(call)
                if (requestGeneration != actionGeneration) return@launch
                when (result) {
                    is DataResult.Content -> applyMutationSuccess(action, result.data)
                    is DataResult.Error -> applyMutationFailure(action, result.error.code)
                    is DataResult.NoPermission -> applyMutationFailure(action, result.blocker.code)
                    is DataResult.Empty -> applyMutationFailure(action, result.reason.name)
                }
            }
    }

    private fun applyMutationSuccess(action: ChannelSettingsAction, value: Any?) {
        val current = mutableState.value
        val next =
            when (action) {
                ChannelSettingsAction.Mute -> {
                    val settings = value as ChannelActivityMuteSettings
                    current.copy(
                        channel =
                            current.channel?.copy(
                                activityNotificationPreference = settings.activityNotificationPreference
                            ),
                        actionInFlight = null,
                        effect = ChannelSettingsEffect.Updated(action),
                    )
                }
                ChannelSettingsAction.Pin ->
                    current.copy(
                        sidebarOrder = value as SettingsServerSidebarOrderSnapshot,
                        actionInFlight = null,
                        effect = ChannelSettingsEffect.Updated(action),
                    )
                ChannelSettingsAction.Profile -> {
                    val profile = value as ChannelProfileUpdateResult
                    current.copy(
                        channel =
                            current.channel?.copy(
                                name = profile.name,
                                subtitle = profile.description,
                            ),
                        actionInFlight = null,
                        profileStatus = ChannelProfileUpdateStatus.Succeeded,
                        profileErrorCode = null,
                        effect = ChannelSettingsEffect.ProfileSaved,
                    )
                }
                ChannelSettingsAction.Visibility ->
                    current.copy(
                        channel = current.channel?.copy(channelType = "private"),
                        actionInFlight = null,
                        effect = ChannelSettingsEffect.Updated(action),
                    )
                ChannelSettingsAction.Archive,
                ChannelSettingsAction.Delete ->
                    current.copy(actionInFlight = null, effect = ChannelSettingsEffect.Close)
            }
        mutableState.value = next
        if (action == ChannelSettingsAction.Archive || action == ChannelSettingsAction.Delete) {
            snapshotCache.remove(owner, route)
        } else {
            next.toSnapshotOrNull()?.let { snapshot -> snapshotCache.put(owner, route, snapshot) }
        }
    }

    private fun ChannelSettingsState.toSnapshotOrNull(): ChannelSettingsSnapshot? {
        val currentChannel = channel ?: return null
        val currentSidebarOrder = sidebarOrder ?: return null
        return ChannelSettingsSnapshot(
            channel = currentChannel,
            sidebarOrder = currentSidebarOrder,
            canManage = canManage,
            memberSlice = memberSlice,
        )
    }

    private fun applyMutationFailure(action: ChannelSettingsAction, code: String) {
        if (code == AUTHENTICATED_ROUTE_AUTHORITY_CHANGED) {
            snapshotCache.remove(owner, route)
            mutableState.value = authorityRejectedState()
            return
        }
        mutableState.value =
            mutableState.value.copy(
                actionInFlight = null,
                profileStatus =
                    if (action == ChannelSettingsAction.Profile) {
                        ChannelProfileUpdateStatus.Failed
                    } else {
                        mutableState.value.profileStatus
                    },
                profileErrorCode = code.takeIf { action == ChannelSettingsAction.Profile },
                effect = ChannelSettingsEffect.Failure(action, code),
            )
    }

    private fun authorityRejectedState(): ChannelSettingsState =
        ChannelSettingsState(
            status = ChannelSettingsLoadStatus.AuthorityRejected,
            memberSlice =
                SharedServerChannelMembersSlice(
                    serverId = route.serverId.value,
                    channelId = route.channelId.value,
                    status = SharedServerMembersLoadStatus.Error,
                    errorCode = AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
                ),
            errorCode = AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
        )

    fun clear() {
        loadGeneration += 1L
        actionGeneration += 1L
        loadJob?.cancel()
        actionJob?.cancel()
        loadJob = null
        actionJob = null
    }
}

private suspend fun <T> runDataSourceCall(call: suspend () -> DataResult<T>): DataResult<T> =
    try {
        call()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        DataResult.Error(ScreenError(code = "channel_settings_network", retryable = true))
    }

private fun DataResult<build.raft.app.data.ChannelMemberSnapshot>.toChannelSettingsMemberSlice(
    route: ChannelSettingsRoute,
): SharedServerChannelMembersSlice =
    when (this) {
        is DataResult.Content ->
            SharedServerChannelMembersSlice(
                serverId = route.serverId.value,
                channelId = route.channelId.value,
                members = data.members,
                externalMembers = data.externalMembers,
                canAddMembers = data.canAddMembers,
                status = SharedServerMembersLoadStatus.Loaded,
            )
        is DataResult.Empty ->
            SharedServerChannelMembersSlice(
                serverId = route.serverId.value,
                channelId = route.channelId.value,
                status = SharedServerMembersLoadStatus.Loaded,
            )
        is DataResult.Error ->
            SharedServerChannelMembersSlice(
                serverId = route.serverId.value,
                channelId = route.channelId.value,
                status = SharedServerMembersLoadStatus.Error,
                errorCode = error.code,
            )
        is DataResult.NoPermission ->
            SharedServerChannelMembersSlice(
                serverId = route.serverId.value,
                channelId = route.channelId.value,
                status = SharedServerMembersLoadStatus.Error,
                errorCode = blocker.code,
            )
    }

private fun <T, R> DataResult<T>.withoutContent(): DataResult<R> =
    when (this) {
        is DataResult.Content -> error("Expected a non-content result")
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }
