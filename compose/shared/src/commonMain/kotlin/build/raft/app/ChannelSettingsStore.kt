package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.AuthenticatedRouteOperationRunner
import build.raft.app.data.ChannelActivityMuteSettings
import build.raft.app.data.ChannelGuestPolicyUpdate
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
import build.raft.app.data.encodeServerPublicGuestGateEvaluateBody
import build.raft.app.data.parseSharedServerGuestGateEnabled
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftOwnerScopedBoundedCache
import build.raft.app.data.RaftOwnerScopedCacheOwner
import build.raft.app.data.ChannelSlackBridgeState
import build.raft.app.data.SlackBridgeChannelPairingRepository
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

/** Web `handleGuestVisibleChange`: `{ guestVisible: true }` or `{ guestVisible: false, guestJoinable: false }`. */
internal fun channelSettingsGuestVisibleUpdate(visible: Boolean): ChannelGuestPolicyUpdate =
    if (visible) ChannelGuestPolicyUpdate(guestVisible = true) else ChannelGuestPolicyUpdate(guestVisible = false, guestJoinable = false)

/** Web `handleGuestJoinableChange`: `{ guestVisible: true, guestJoinable: true }` or `{ guestJoinable: false }`. */
internal fun channelSettingsGuestJoinableUpdate(joinable: Boolean): ChannelGuestPolicyUpdate =
    if (joinable) ChannelGuestPolicyUpdate(guestVisible = true, guestJoinable = true) else ChannelGuestPolicyUpdate(guestJoinable = false)

/** A Slack channel pick to apply on Save; [slackChannelId] null disconnects. */
internal data class ChannelSlackBridgeSelection(val slackChannelId: String?)

/** What Save changed: the profile as the server has it, and the Slack pick when it was applied. */
internal data class ChannelSettingsSaveResult(
    val profile: ChannelProfileUpdateResult,
    val slackBridge: ChannelSlackBridgeState?,
    val slackBridgeApplied: Boolean,
)

/** Save went through for the name / description but the Slack Bridge step did not (web `channel.bridge.partialFailure`). */
internal const val CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE = "slack_bridge_partial_failure"

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
    /** Joint channels: `POST channels/:id/disconnect` takes the place of Delete (web EditChannelDialog). */
    Disconnect,
    /** `POST channels/:id/leave` (web ChatPanel `leaveChannel`). */
    Leave,
    /** `POST channels/:id/unarchive` (web EditChannelDialog `handleUnarchive`). */
    Unarchive,
    /** `PATCH channels/:id` `{ guestVisible?, guestJoinable? }` (web EditChannelDialog `updateGuestPolicy`). */
    GuestAccess,
}

internal sealed interface ChannelSettingsEffect {
    data class Failure(
        val action: ChannelSettingsAction,
        val code: String,
        val serverMessage: String? = null,
    ) : ChannelSettingsEffect
    data class Updated(val action: ChannelSettingsAction) : ChannelSettingsEffect
    data object ProfileSaved : ChannelSettingsEffect
    data object Close : ChannelSettingsEffect
}

internal data class ChannelSettingsSnapshot(
    val channel: HomeChannelRow,
    val sidebarOrder: SettingsServerSidebarOrderSnapshot,
    val canManage: Boolean,
    val memberSlice: SharedServerChannelMembersSlice,
    /** `server_guest_v0` for this server (web `serverGuestEnabled`); off until evaluated. */
    val guestAccessEnabled: Boolean = false,
    /** The Slack channel pick when the Slack Bridge is on and at `health`; null otherwise. */
    val slackBridge: ChannelSlackBridgeState? = null,
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
        RaftOwnerScopedBoundedCache<ChannelSettingsSnapshotCacheResource, ChannelSettingsSnapshot>(
            maxEntries = maxEntries,
            acceptsResource = { owner, resource -> owner.serverId == resource.serverId },
            acceptsValue = { owner, resource, snapshot ->
                snapshot.matchesCacheIdentity(owner, resource)
            },
        )

    fun get(owner: AuthenticatedRouteOwner, route: ChannelSettingsRoute): ChannelSettingsSnapshot? {
        val cacheOwner = RaftOwnerScopedCacheOwner.from(owner) ?: return null
        val resource = cacheResource(cacheOwner, route) ?: return null
        return cache.get(cacheOwner, resource)
    }

    fun put(
        owner: AuthenticatedRouteOwner,
        route: ChannelSettingsRoute,
        snapshot: ChannelSettingsSnapshot,
    ): Boolean {
        val cacheOwner = RaftOwnerScopedCacheOwner.from(owner) ?: return false
        val resource = cacheResource(cacheOwner, route) ?: return false
        return cache.put(cacheOwner, resource, snapshot)
    }

    fun remove(owner: AuthenticatedRouteOwner, route: ChannelSettingsRoute) {
        val cacheOwner = RaftOwnerScopedCacheOwner.from(owner) ?: return
        val resource = cacheResource(cacheOwner, route) ?: return
        cache.remove(cacheOwner, resource)
    }

    private fun cacheResource(
        owner: RaftOwnerScopedCacheOwner,
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
        owner: RaftOwnerScopedCacheOwner,
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
    val guestAccessEnabled: Boolean = false,
    val slackBridge: ChannelSlackBridgeState? = null,
    val memberSlice: SharedServerChannelMembersSlice = SharedServerChannelMembersSlice(channelId = ""),
    val refreshing: Boolean = false,
    val writeActionsEnabled: Boolean = false,
    val actionInFlight: ChannelSettingsAction? = null,
    /**
     * Optimistic toggles (artin: the switches waited for the server): the value a tap asked for
     * while its request runs, shown at once and dropped when the request ends (the result then
     * holds the value, or the failure puts the old one back).
     */
    val pendingMuted: Boolean? = null,
    val pendingPinned: Boolean? = null,
    val profileStatus: ChannelProfileUpdateStatus = ChannelProfileUpdateStatus.Idle,
    val profileErrorCode: String? = null,
    val errorCode: String? = null,
    val effect: ChannelSettingsEffect? = null,
) {
    fun permissions(): ChannelSettingsPermissions =
        channel?.let { channelSettingsPermissions(it, canManage) } ?: ChannelSettingsPermissions()

    /** Web `canManageGuestAccess`: the flag, the capability, a live regular channel with a guest policy. */
    fun showsGuestAccess(): Boolean {
        val current = channel ?: return false
        return guestAccessEnabled &&
            permissions().manageGuestAccess &&
            !current.archived &&
            channelSettingsGuestPolicyApplies(current)
    }

    fun isPinned(channelId: String): Boolean =
        sidebarOrder?.typedPinnedOrLegacy()?.any {
            it.kind == "channel" && it.id == channelId
        } == true

    /** The mute switch: the pending value of a tap in flight, else the channel's. */
    val displayMuted: Boolean
        get() = pendingMuted ?: (channel?.activityMuted == true)

    /** The pin switch: the pending value of a tap in flight, else the sidebar order's. */
    fun displayPinned(channelId: String): Boolean = pendingPinned ?: isPinned(channelId)
}

internal sealed interface ChannelSettingsIntent {
    data object Load : ChannelSettingsIntent
    data object Refresh : ChannelSettingsIntent
    data object ToggleMute : ChannelSettingsIntent
    data object TogglePin : ChannelSettingsIntent
    /**
     * Web panel Save: name / description, and with [slackBridge] the Slack channel pick
     * ([ChannelSlackBridgeSelection]); unchanged parts are not sent.
     */
    data class UpdateProfile(
        val name: String,
        val description: String,
        val slackBridge: ChannelSlackBridgeSelection? = null,
    ) : ChannelSettingsIntent
    data object MakePrivate : ChannelSettingsIntent
    data object MakePublic : ChannelSettingsIntent
    data object Leave : ChannelSettingsIntent
    data object Unarchive : ChannelSettingsIntent
    /** Web `handleGuestVisibleChange`: off also turns guest joining off. */
    data class SetGuestVisible(val visible: Boolean) : ChannelSettingsIntent
    /** Web `handleGuestJoinableChange`: on also makes the channel guest-visible. */
    data class SetGuestJoinable(val joinable: Boolean) : ChannelSettingsIntent
    data object Archive : ChannelSettingsIntent
    data object Delete : ChannelSettingsIntent
    data object Disconnect : ChannelSettingsIntent
    data object ClearProfileStatus : ChannelSettingsIntent
    data object ConsumeEffect : ChannelSettingsIntent
}

internal interface ChannelSettingsDataSource {
    suspend fun load(): DataResult<ChannelSettingsSnapshot>
    suspend fun setMuted(muted: Boolean): DataResult<ChannelActivityMuteSettings>
    suspend fun setPinned(pinned: Boolean): DataResult<SettingsServerSidebarOrderSnapshot>
    suspend fun updateProfile(name: String, description: String): DataResult<ChannelProfileUpdateResult>
    suspend fun applySlackBridge(channel: HomeChannelRow, slackChannelId: String?): DataResult<ChannelSlackBridgeState?>
    suspend fun makePrivate(): DataResult<Unit>
    suspend fun makePublic(): DataResult<Unit>
    suspend fun leave(): DataResult<Unit>
    suspend fun unarchive(): DataResult<HomeChannelRow>
    suspend fun updateGuestPolicy(update: ChannelGuestPolicyUpdate): DataResult<HomeChannelRow>
    suspend fun archive(): DataResult<Unit>
    suspend fun delete(): DataResult<Unit>
    suspend fun disconnect(): DataResult<Unit>
}

internal class RealChannelSettingsDataSource(
    private val route: ChannelSettingsRoute,
    private val authStore: RaftAuthSessionStore,
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
            val permissions = channelSettingsPermissions(channel, receipt.canManageSettings)
            val guestAccessEnabled =
                permissions.manageGuestAccess &&
                    channelSettingsGuestPolicyApplies(channel) &&
                    serverGuestFeatureEnabled(networkClient)
            val slackBridge =
                if (channelSettingsSlackBridgeApplies(channel, permissions)) {
                    SlackBridgeChannelPairingRepository(networkClient, route.serverId)
                        .loadState(route.channelId, channelSettingsSlackVisibility(channel))
                } else {
                    null
                }
            DataResult.Content(
                ChannelSettingsSnapshot(
                    channel = channel,
                    sidebarOrder = sidebarOrder,
                    canManage = receipt.canManageSettings,
                    memberSlice = memberResult.toChannelSettingsMemberSlice(route),
                    guestAccessEnabled = guestAccessEnabled,
                    slackBridge = slackBridge,
                )
            )
        }

    override suspend fun setMuted(muted: Boolean): DataResult<ChannelActivityMuteSettings> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).setChannelActivityMuted(route.channelId, muted)
        }

    override suspend fun setPinned(pinned: Boolean): DataResult<SettingsServerSidebarOrderSnapshot> =
        runner.run { _, networkClient ->
            val settingsRepository = settingsRepository(networkClient)
            val currentResult = settingsRepository.getServerSidebarOrder(route.serverId)
            val current = (currentResult as? DataResult.Content)?.data
                ?: return@run currentResult.withoutContent()
            val target = SettingsServerSidebarPinnedItem(kind = "channel", id = route.channelId.value)
            // The value the switch shows, not a flip of the server's: a tap is idempotent.
            val others = current.typedPinnedOrLegacy().filterNot { it == target }
            val nextPinned = if (pinned) others + target else others
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

    override suspend fun applySlackBridge(
        channel: HomeChannelRow,
        slackChannelId: String?,
    ): DataResult<ChannelSlackBridgeState?> =
        runner.run { _, networkClient ->
            SlackBridgeChannelPairingRepository(networkClient, route.serverId)
                .apply(route.channelId, channelSettingsSlackVisibility(channel), slackChannelId)
        }

    override suspend fun makePublic(): DataResult<Unit> =
        runner.run { _, networkClient ->
            channelRepository(networkClient)
                .updateChannel(route.channelId, ChannelVisibility.Public)
                .map { Unit }
        }

    override suspend fun leave(): DataResult<Unit> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).leaveChannel(route.channelId).map { Unit }
        }

    override suspend fun unarchive(): DataResult<HomeChannelRow> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).unarchiveChannel(route.channelId)
        }

    override suspend fun updateGuestPolicy(update: ChannelGuestPolicyUpdate): DataResult<HomeChannelRow> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).updateChannelGuestPolicy(route.channelId, update)
        }

    override suspend fun archive(): DataResult<Unit> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).archiveChannel(route.channelId).map { Unit }
        }

    override suspend fun delete(): DataResult<Unit> =
        runner.run { _, networkClient ->
            channelRepository(networkClient).deleteChannel(route.channelId).map { Unit }
        }

    override suspend fun disconnect(): DataResult<Unit> =
        runner.run { _, networkClient ->
            build.raft.app.data.RealJointChannelRepository(networkClient) { logger("joint $it") }
                .disconnect(route.channelId)
        }

    /** `server_guest_v0` evaluated like Settings › Server public page does (platform "web"); failures read as off. */
    private suspend fun serverGuestFeatureEnabled(networkClient: build.raft.app.data.RaftNetworkClient): Boolean {
        val response =
            try {
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = "feature-flags/evaluate",
                    method = "POST",
                    serverIdOverride = route.serverId,
                    bodyText = encodeServerPublicGuestGateEvaluateBody(route.serverId),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return false
            }
        if (!response.isSuccessful) return false
        return response.bodyText?.let(::parseSharedServerGuestGateEnabled) == true
    }

    private fun channelRepository(networkClient: build.raft.app.data.RaftNetworkClient): RealChannelRepository =
        RealChannelRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            warningLogger = { logger("channel $it") },
        )

    private fun memberRepository(networkClient: build.raft.app.data.RaftNetworkClient): RealMemberRepository =
        RealMemberRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            warningLogger = { logger("members_warning $it") },
            logger = { logger("members $it") },
        )

    private fun settingsRepository(networkClient: build.raft.app.data.RaftNetworkClient): RealSettingsRepository =
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
                if (mutableState.value.channel == null) return
                val muted = !mutableState.value.displayMuted
                launchAction(ChannelSettingsAction.Mute, optimistic = { copy(pendingMuted = muted) }) {
                    dataSource.setMuted(muted)
                }
            }
            ChannelSettingsIntent.TogglePin -> {
                if (!mutableState.value.writeActionsEnabled) return
                val pinned = !mutableState.value.displayPinned(route.channelId.value)
                launchAction(ChannelSettingsAction.Pin, optimistic = { copy(pendingPinned = pinned) }) {
                    dataSource.setPinned(pinned)
                }
            }
            is ChannelSettingsIntent.UpdateProfile -> updateProfile(intent)
            ChannelSettingsIntent.MakePrivate -> {
                if (!mutableState.value.writeActionsEnabled) return
                val channel = mutableState.value.channel ?: return
                if (channelSettingsShowsVisibilityAction(channel, mutableState.value.permissions()) && channelSettingsCanMakePrivate(channel)) {
                    launchAction(ChannelSettingsAction.Visibility, call = dataSource::makePrivate)
                }
            }
            ChannelSettingsIntent.MakePublic -> {
                if (!mutableState.value.writeActionsEnabled) return
                val channel = mutableState.value.channel ?: return
                if (channelSettingsShowsVisibilityAction(channel, mutableState.value.permissions()) && channelSettingsCanMakePublic(channel)) {
                    launchAction(ChannelSettingsAction.Visibility, call = dataSource::makePublic)
                }
            }
            ChannelSettingsIntent.Archive -> {
                if (!mutableState.value.writeActionsEnabled) return
                val channel = mutableState.value.channel ?: return
                if (channelSettingsShowsArchiveAction(channel, mutableState.value.permissions()) && !channel.archived) {
                    launchAction(ChannelSettingsAction.Archive, call = dataSource::archive)
                }
            }
            ChannelSettingsIntent.Leave -> {
                if (!mutableState.value.writeActionsEnabled) return
                val channel = mutableState.value.channel ?: return
                if (channelSettingsCanLeave(channel)) launchAction(ChannelSettingsAction.Leave, call = dataSource::leave)
            }
            ChannelSettingsIntent.Unarchive -> {
                if (!mutableState.value.writeActionsEnabled) return
                val channel = mutableState.value.channel ?: return
                if (channel.archived && channelSettingsShowsArchiveAction(channel, mutableState.value.permissions())) {
                    launchAction(ChannelSettingsAction.Unarchive, call = dataSource::unarchive)
                }
            }
            is ChannelSettingsIntent.SetGuestVisible -> {
                if (!mutableState.value.writeActionsEnabled || !mutableState.value.showsGuestAccess()) return
                val channel = mutableState.value.channel ?: return
                if (channel.guestVisible == intent.visible) return
                val update = channelSettingsGuestVisibleUpdate(intent.visible)
                launchAction(ChannelSettingsAction.GuestAccess) { dataSource.updateGuestPolicy(update) }
            }
            is ChannelSettingsIntent.SetGuestJoinable -> {
                if (!mutableState.value.writeActionsEnabled || !mutableState.value.showsGuestAccess()) return
                val channel = mutableState.value.channel ?: return
                if (channelSettingsIsAllChannel(channel)) return
                if (channel.guestJoinable == intent.joinable) return
                val update = channelSettingsGuestJoinableUpdate(intent.joinable)
                launchAction(ChannelSettingsAction.GuestAccess) { dataSource.updateGuestPolicy(update) }
            }
            ChannelSettingsIntent.Delete -> {
                if (!mutableState.value.writeActionsEnabled) return
                val channel = mutableState.value.channel ?: return
                if (channelSettingsCanDelete(channel) && channelSettingsShowsDeleteAction(channel, mutableState.value.permissions())) {
                    launchAction(ChannelSettingsAction.Delete, call = dataSource::delete)
                }
            }
            ChannelSettingsIntent.Disconnect -> {
                if (!mutableState.value.writeActionsEnabled || !mutableState.value.canManage) return
                val channel = mutableState.value.channel ?: return
                if (channelSettingsCanDisconnect(channel)) {
                    launchAction(ChannelSettingsAction.Disconnect, call = dataSource::disconnect)
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
            guestAccessEnabled = guestAccessEnabled,
            slackBridge = slackBridge,
            memberSlice = memberSlice,
            writeActionsEnabled = false,
        )

    private fun ChannelSettingsSnapshot.toResolvedState(): ChannelSettingsState =
        ChannelSettingsState(
            status = ChannelSettingsLoadStatus.Content,
            channel = channel,
            sidebarOrder = sidebarOrder,
            canManage = canManage,
            guestAccessEnabled = guestAccessEnabled,
            slackBridge = slackBridge,
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
        val channel = mutableState.value.channel
        val profileChanged =
            channel == null ||
                intent.name.trim() != channel.name.removePrefix("#").trim() ||
                intent.description.trim() != channel.subtitle.orEmpty().trim()
        val bridge = intent.slackBridge?.takeIf { mutableState.value.slackBridge != null && channel != null }
        partialProfile = null
        launchMutation<ChannelSettingsSaveResult>(ChannelSettingsAction.Profile) {
            val profile =
                if (profileChanged) {
                    when (val result = dataSource.updateProfile(intent.name, intent.description)) {
                        is DataResult.Content -> result.data
                        else -> return@launchMutation result.withoutContent<ChannelProfileUpdateResult, ChannelSettingsSaveResult>()
                    }
                } else {
                    ChannelProfileUpdateResult(route.channelId, channel!!.name, channel.subtitle)
                }
            if (bridge == null) {
                DataResult.Content(ChannelSettingsSaveResult(profile, slackBridge = null, slackBridgeApplied = false))
            } else {
                when (val applied = dataSource.applySlackBridge(channel!!, bridge.slackChannelId)) {
                    is DataResult.Content ->
                        DataResult.Content(ChannelSettingsSaveResult(profile, applied.data, slackBridgeApplied = true))
                    else -> {
                        partialProfile = profile.takeIf { profileChanged }
                        DataResult.Error(ScreenError(code = CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE, retryable = true))
                    }
                }
            }
        }
    }

    /** The profile Save did write when its Slack Bridge step failed afterwards. */
    private var partialProfile: ChannelProfileUpdateResult? = null

    private fun <T> launchAction(
        action: ChannelSettingsAction,
        optimistic: ChannelSettingsState.() -> ChannelSettingsState = { this },
        call: suspend () -> DataResult<T>,
    ) {
        if (
            mutableState.value.status != ChannelSettingsLoadStatus.Content ||
            !mutableState.value.writeActionsEnabled ||
            mutableState.value.actionInFlight != null
        ) {
            return
        }
        mutableState.value = mutableState.value.copy(actionInFlight = action, effect = null).optimistic()
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
                    is DataResult.Error -> applyMutationFailure(action, result.error.code, result.error.serverMessage)
                    is DataResult.NoPermission ->
                        applyMutationFailure(action, result.blocker.code, result.blocker.serverMessage)
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
                        pendingMuted = null,
                        effect = ChannelSettingsEffect.Updated(action),
                    )
                }
                ChannelSettingsAction.Pin ->
                    current.copy(
                        sidebarOrder = value as SettingsServerSidebarOrderSnapshot,
                        actionInFlight = null,
                        pendingPinned = null,
                        effect = ChannelSettingsEffect.Updated(action),
                    )
                ChannelSettingsAction.Profile -> {
                    val saved = value as ChannelSettingsSaveResult
                    val profile = saved.profile
                    current.copy(
                        channel =
                            current.channel?.copy(
                                name = profile.name,
                                subtitle = profile.description,
                            ),
                        slackBridge = if (saved.slackBridgeApplied) saved.slackBridge else current.slackBridge,
                        actionInFlight = null,
                        profileStatus = ChannelProfileUpdateStatus.Succeeded,
                        profileErrorCode = null,
                        effect = ChannelSettingsEffect.ProfileSaved,
                    )
                }
                ChannelSettingsAction.Visibility ->
                    current.copy(
                        channel =
                            current.channel?.let { channel ->
                                channel.copy(channelType = if (channelSettingsIsPrivate(channel)) "channel" else "private")
                            },
                        actionInFlight = null,
                        effect = ChannelSettingsEffect.Updated(action),
                    )
                ChannelSettingsAction.GuestAccess -> {
                    val updated = value as HomeChannelRow
                    current.copy(
                        channel =
                            current.channel?.copy(
                                guestVisible = updated.guestVisible,
                                guestJoinable = updated.guestJoinable,
                            ),
                        actionInFlight = null,
                        effect = ChannelSettingsEffect.Updated(action),
                    )
                }
                ChannelSettingsAction.Archive,
                ChannelSettingsAction.Delete,
                ChannelSettingsAction.Disconnect,
                ChannelSettingsAction.Leave,
                ChannelSettingsAction.Unarchive ->
                    current.copy(actionInFlight = null, effect = ChannelSettingsEffect.Close)
            }
        mutableState.value = next
        if (next.effect == ChannelSettingsEffect.Close) {
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
            guestAccessEnabled = guestAccessEnabled,
            slackBridge = slackBridge,
        )
    }

    private fun applyMutationFailure(action: ChannelSettingsAction, code: String, serverMessage: String? = null) {
        if (code == AUTHENTICATED_ROUTE_AUTHORITY_CHANGED) {
            snapshotCache.remove(owner, route)
            mutableState.value = authorityRejectedState()
            return
        }
        // Web: "The channel was saved, but its Slack bridge was not enabled" — keep the saved name.
        val saved = partialProfile.takeIf { action == ChannelSettingsAction.Profile }
        partialProfile = null
        mutableState.value =
            mutableState.value.copy(
                channel =
                    saved?.let { profile ->
                        mutableState.value.channel?.copy(name = profile.name, subtitle = profile.description)
                    } ?: mutableState.value.channel,
                actionInFlight = null,
                // A failed toggle shows its old value again.
                pendingMuted = null,
                pendingPinned = null,
                profileStatus =
                    if (action == ChannelSettingsAction.Profile) {
                        ChannelProfileUpdateStatus.Failed
                    } else {
                        mutableState.value.profileStatus
                    },
                profileErrorCode = code.takeIf { action == ChannelSettingsAction.Profile },
                effect = ChannelSettingsEffect.Failure(action, code, serverMessage),
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
