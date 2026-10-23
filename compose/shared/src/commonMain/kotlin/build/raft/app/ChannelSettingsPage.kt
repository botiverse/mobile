package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.ScreenError
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.app.data.isSubmittableChannelName
import build.raft.app.model.MemberKind
import build.raft.designsystem.components.RaftAvatarEmpty
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftSelect
import build.raft.app.data.ChannelSlackBridgeOption
import build.raft.app.data.ChannelSlackBridgeState
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowValue
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftAvatarGroupCount
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonList
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.data.ChannelSosStore
import build.raft.app.data.RaftAgentControlsApi
import build.raft.app.data.serverRoleCanControlAgentRuntime
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

internal const val CHANNEL_SETTINGS_PAGE = "compose_channel_settings"

internal data class ChannelSettingsRoute(
    val serverId: ServerId,
    val channelId: ChannelId
)

internal fun channelSettingsPageData(serverId: ServerId, channelId: ChannelId): JSONObject =
    JSONObject().apply {
        put("pageName", CHANNEL_SETTINGS_PAGE)
        put("serverId", serverId.value)
        put("channelId", channelId.value)
    }

internal fun JSONObject.toChannelSettingsRouteOrNull(): ChannelSettingsRoute? {
    val serverId = optString("serverId").trim().takeIf(String::isNotBlank)?.let(::ServerId) ?: return null
    val channelId = optString("channelId").trim().takeIf(String::isNotBlank)?.let(::ChannelId) ?: return null
    return ChannelSettingsRoute(serverId = serverId, channelId = channelId)
}

internal fun channelSettingsCanManage(content: SharedHomeContentState): Boolean =
    (content as? SharedHomeContentState.Content)?.snapshot?.server?.permissions?.canManageSettings == true

internal fun channelSettingsChannel(
    content: SharedHomeContentState,
    route: ChannelSettingsRoute
): HomeChannelRow? =
    (content as? SharedHomeContentState.Content)
        ?.snapshot
        ?.takeIf { it.server.id == route.serverId }
        ?.channels
        ?.firstOrNull { it.channelId == route.channelId }

internal fun channelSettingsSectionKeys(canManage: Boolean): List<String> =
    buildList {
        add("identity")
        add("members")
        add("preferences")
        if (canManage) add("management")
    }

/** Web ChatPanel `joined && capabilities.controlAgentRuntime`; channels only (a DM has no SOS). */
internal fun channelSettingsCanStopAllAgents(channel: HomeChannelRow, serverRole: String?): Boolean =
    channel.joined &&
        !channel.archived &&
        channel.kind != ConversationKind.DirectMessage &&
        serverRoleCanControlAgentRuntime(serverRole)

internal fun channelSettingsUsesStandalonePage(newDesignSettingsEnabled: Boolean): Boolean =
    newDesignSettingsEnabled

internal fun channelSettingsIsJoint(channel: HomeChannelRow): Boolean =
    channel.kind == ConversationKind.Joint ||
        channel.channelType.equals("joint", ignoreCase = true)

internal fun channelSettingsIsPrivate(channel: HomeChannelRow): Boolean =
    channelSettingsIsJoint(channel) ||
        channel.channelType.equals("private", ignoreCase = true)

internal fun channelSettingsCanMakePrivate(channel: HomeChannelRow): Boolean =
    !channelSettingsIsPrivate(channel)

internal fun channelSettingsCanDelete(channel: HomeChannelRow): Boolean =
    !channelSettingsIsJoint(channel)

/** Web `#all` (`initialName === "all"`): no leave, archive or delete; its visibility lives in Server settings. */
internal fun channelSettingsIsAllChannel(channel: HomeChannelRow): Boolean =
    channel.name.removePrefix("#").equals("all", ignoreCase = true)

/** Web `currentVisibility === "private"` on a regular (non-joint) channel: Make Public. */
internal fun channelSettingsCanMakePublic(channel: HomeChannelRow): Boolean =
    !channelSettingsIsJoint(channel) && channel.channelType.equals("private", ignoreCase = true)

/**
 * Web ChatPanel `onLeaveChannel` (`joinedNow && !isAll`) and EditChannelDialog `showLeaveAction`
 * (`!isAllChannel && !isArchived`); the server refuses DMs and archived channels (`POST
 * channels/:id/leave`). Joint channels can be left like any other.
 */
internal fun channelSettingsCanLeave(channel: HomeChannelRow): Boolean =
    channel.joined &&
        !channel.archived &&
        channel.kind != ConversationKind.DirectMessage &&
        !channelSettingsIsAllChannel(channel)

/**
 * Web EditChannelDialog: a completed joint channel shows "Disconnect Channel" where other channels
 * show Delete, hidden once archived. The route itself is gated on `federateChannels` (owner/admin),
 * which is the same role set as [ChannelSettingsState.canManage].
 */
internal fun channelSettingsCanDisconnect(channel: HomeChannelRow): Boolean =
    channelSettingsIsJoint(channel) && !channel.archived

/**
 * The channel-management capabilities the settings page gates on (web EditChannelDialog
 * `effectiveCapabilities = channel.channelCapabilities ?? serverCapabilities`). Mobile's server
 * capability is [ChannelSettingsState.canManage] (`canManageSettings`), standing in for every key
 * when the channel carries no `channelCapabilities`.
 */
internal data class ChannelSettingsPermissions(
    val editMetadata: Boolean = false,
    val changeVisibility: Boolean = false,
    val archive: Boolean = false,
    val delete: Boolean = false,
    val manageGuestAccess: Boolean = false,
    val federate: Boolean = false,
) {
    /** Web `canEditChannel`. */
    val canEditChannel: Boolean
        get() = editMetadata || changeVisibility || archive || delete || federate
}

internal fun channelSettingsPermissions(channel: HomeChannelRow, canManage: Boolean): ChannelSettingsPermissions {
    val capabilities = channel.channelCapabilities ?: return ChannelSettingsPermissions(
        editMetadata = canManage,
        changeVisibility = canManage,
        archive = canManage,
        delete = canManage,
        manageGuestAccess = canManage,
        federate = canManage,
    )
    return ChannelSettingsPermissions(
        editMetadata = capabilities.editChannelMetadata == true,
        changeVisibility = capabilities.changeChannelVisibility == true,
        archive = capabilities.archiveChannels == true,
        delete = capabilities.deleteChannels == true,
        manageGuestAccess = capabilities.manageGuestAccess == true,
        federate = capabilities.federateChannels == true,
    )
}

/** Web `showInfoSection`: the inline name / description form (`canEditChannel && !isArchived`). */
internal fun channelSettingsShowsInfo(channel: HomeChannelRow, permissions: ChannelSettingsPermissions): Boolean =
    permissions.canEditChannel && !channel.archived

/**
 * Web `visibilityActionRow` (`showManageActions && changeChannelVisibility && !isJointChannel`);
 * `#all` is hidden or restored from Settings › Server on mobile, not here.
 */
internal fun channelSettingsShowsVisibilityAction(channel: HomeChannelRow, permissions: ChannelSettingsPermissions): Boolean =
    permissions.canEditChannel &&
        permissions.changeVisibility &&
        !channel.archived &&
        !channelSettingsIsJoint(channel) &&
        !channelSettingsIsAllChannel(channel)

/** Web `archiveActionRow`: Archive while live, Unarchive once archived; never on `#all`. */
internal fun channelSettingsShowsArchiveAction(channel: HomeChannelRow, permissions: ChannelSettingsPermissions): Boolean =
    permissions.archive && !channelSettingsIsAllChannel(channel)

/** Web `deleteActionRow`: Delete (Disconnect on a joint channel) while live; never on `#all`. */
internal fun channelSettingsShowsDeleteAction(channel: HomeChannelRow, permissions: ChannelSettingsPermissions): Boolean =
    permissions.delete && !channel.archived && !channelSettingsIsAllChannel(channel)

/**
 * Web `ChannelSlackBridgeField` host condition (`canEditChannel && !isArchived && type is channel or
 * private`); the field itself then needs `slack_bridge_v0` and a Slack workspace at `health`.
 */
internal fun channelSettingsSlackBridgeApplies(channel: HomeChannelRow, permissions: ChannelSettingsPermissions): Boolean {
    if (!permissions.canEditChannel || channel.archived || channelSettingsIsJoint(channel)) return false
    val type = channel.channelType?.lowercase() ?: "channel"
    return type == "channel" || type == "private"
}

/** Web `currentVisibility` for Slack channel privacy matching. */
internal fun channelSettingsSlackVisibility(channel: HomeChannelRow): String =
    if (channel.channelType.equals("private", ignoreCase = true)) "private" else "public"

/**
 * Web `canManageGuestAccess` without the flag and capability: a regular channel with a guest
 * policy. A private channel has none (the server forces both flags off), except `#all`, whose
 * hidden state is `type: "private"` and keeps its guest-visible switch.
 */
internal fun channelSettingsGuestPolicyApplies(channel: HomeChannelRow): Boolean {
    if (channelSettingsIsJoint(channel)) return false
    val type = channel.channelType?.lowercase() ?: "channel"
    return type == "channel" || (type == "private" && channelSettingsIsAllChannel(channel))
}

@Page(CHANNEL_SETTINGS_PAGE, supportInLocal = true)
internal class ComposeChannelSettingsPage : AuthenticatedProductPager(AuthenticatedProductRoute.ChannelSettings) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            RaftInputFocusDismissHost { focusDismissModifier ->
                Box(modifier = focusDismissModifier.fillMaxSize()) {
                    ChannelSettingsPageScreen()
                }
            }
        }
    }
}

@Composable
private fun ChannelSettingsPageScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    val route = remember(params.toString()) { params.toChannelSettingsRouteOrNull() }
    val router = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    if (route == null) {
        ChannelSettingsInvalidRouteScreen(onBack = router::closePage)
        return
    }

    val storeScope = remember(params.toString(), pager.pagerId) {
        params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val routeOwner = remember(params.toString()) { params.authenticatedRouteOwnerOrNull() }
    if (routeOwner == null || routeOwner.serverId != route.serverId.value) {
        ChannelSettingsInvalidRouteScreen(onBack = router::closePage)
        return
    }
    val liveOwner by
        remember(sharedStores, storeScope) {
            sharedStores.authenticatedRouteOwnerFlow(storeScope)
        }.collectAsState(sharedStores.currentAuthenticatedRouteOwner(storeScope))
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val authorityGateway = remember(sharedStores, storeScope) {
        sharedStores.authenticatedRouteAuthorityGateway(storeScope)
    }
    val snapshotCache = remember(sharedStores) { sharedStores.channelSettingsSnapshotCache }
    val homeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val membersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val initialSharedHandoff = remember(route, routeOwner, homeStore, membersStore) {
        channelSettingsSharedHandoffSnapshot(
            owner = routeOwner,
            route = route,
            homeSnapshot = homeStore?.snapshotFor(route.serverId),
            membersHandoff = membersStore.currentChannelMembersHandoff(routeOwner, route.channelId),
        )
    }
    val scope = rememberCoroutineScope()
    val dataSource = remember(route, routeOwner, authStore, authorityGateway) {
        RealChannelSettingsDataSource(
            route = route,
            authStore = authStore,
            owner = routeOwner,
            gateway = authorityGateway,
            logger = { bridge.log("channel_settings_store $it") },
        )
    }
    val settingsStore = remember(
        route,
        routeOwner,
        dataSource,
        snapshotCache,
        sharedStores,
        storeScope,
        scope,
    ) {
        ChannelSettingsStore(
            route = route,
            owner = routeOwner,
            dataSource = dataSource,
            snapshotCache = snapshotCache,
            initialSharedHandoff = initialSharedHandoff,
            initialAuthorityAccepted =
                sharedStores.currentAuthenticatedRouteOwner(storeScope) == routeOwner,
            scope = scope,
        )
    }
    val settingsState by settingsStore.state.collectAsState()
    val authorityAccepted = liveOwner == routeOwner
    val channel = settingsState.channel.takeIf { authorityAccepted }
    val memberSlice = settingsState.memberSlice
    // Optimistic: a tapped switch shows its new value while the request runs.
    val pinned = settingsState.displayPinned(route.channelId.value)
    val muted = settingsState.displayMuted
    var confirm by remember(route) { mutableStateOf<ChannelSettingsConfirm?>(null) }
    val longMessageCollapseSetting =
        rememberRaftLongMessageCollapseConversationSetting(
            serverId = route.serverId.value,
            conversationId = route.channelId.value
        )
    // Web ChatPanel: the channel overflow offers "Stop All Agents" (SOS) to
    // joined members holding `controlAgentRuntime` (owner/admin/member).
    var viewerServerRole by remember(route) { mutableStateOf<String?>(null) }
    LaunchedEffect(authStore, route) {
        viewerServerRole =
            authStore.readSession()?.servers?.firstOrNull { it.id == route.serverId.value }?.role
    }
    var sosStore by remember(route) { mutableStateOf<ChannelSosStore?>(null) }
    val agentControlsApi = remember(sharedStores, storeScope) {
        RaftAgentControlsApi(sharedStores.networkClient(storeScope))
    }
    // Web members strip: live agent activity on agent faces (AgentActivityDot).
    val agentStatusResolver = remember(membersStore, route) {
        raftScopedAgentStatusResolver(membersStore, route.serverId.value)
    }
    val failureLabels = channelSettingsFailureLabels()
    val errorStrings = localizedErrorPresentationStrings()

    LaunchedEffect(settingsStore, liveOwner, routeOwner) {
        if (liveOwner == routeOwner) {
            settingsStore.dispatch(ChannelSettingsIntent.Refresh)
        } else {
            confirm = null
            settingsStore.invalidateAuthority()
        }
    }

    LaunchedEffect(settingsState.status) {
        if (settingsState.status == ChannelSettingsLoadStatus.AuthorityRejected) {
            confirm = null
        }
    }

    LaunchedEffect(settingsState.effect) {
        when (val effect = settingsState.effect) {
            ChannelSettingsEffect.ProfileSaved -> {
                settingsState.channel?.let { updated ->
                    homeStore?.dispatch(
                        SharedHomeIntent.ChannelProfileUpdated(
                            channelId = updated.channelId,
                            name = updated.name.removePrefix("#"),
                            description = updated.subtitle,
                        )
                    )
                }
            }
            is ChannelSettingsEffect.Updated -> {
                confirm = null
                homeStore?.dispatch(SharedHomeIntent.Refresh)
            }
            is ChannelSettingsEffect.Failure -> {
                confirm = null
                if (effect.action != ChannelSettingsAction.Profile) {
                    bridge.toast(channelSettingsFailureMessage(effect, failureLabels, errorStrings))
                }
            }
            ChannelSettingsEffect.Close -> {
                confirm = null
                homeStore?.dispatch(SharedHomeIntent.Refresh)
                router.closePage()
            }
            null -> Unit
        }
        if (settingsState.effect != null) settingsStore.dispatch(ChannelSettingsIntent.ConsumeEffect)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ChannelSettingsScaffold(refreshing = settingsState.refreshing, onBack = router::closePage) {
            when {
                channel != null ->
                    ChannelSettingsContent(
                        model =
                            ChannelSettingsUiModel(
                                channel = channel,
                                memberSlice = memberSlice,
                                pinned = pinned,
                                muted = muted,
                                permissions = settingsState.permissions(),
                                writeActionsEnabled = settingsState.writeActionsEnabled && authorityAccepted,
                                canStopAllAgents =
                                    channelSettingsCanStopAllAgents(channel, viewerServerRole) && authorityAccepted,
                                showGuestAccess = settingsState.showsGuestAccess(),
                                actionInFlight = settingsState.actionInFlight,
                                profileStatus = settingsState.profileStatus,
                                profileErrorCode = settingsState.profileErrorCode,
                                slackBridge = settingsState.slackBridge,
                            ),
                        longMessageCollapseSetting = longMessageCollapseSetting,
                        agentStatusResolver = agentStatusResolver,
                        callbacks =
                            ChannelSettingsCallbacks(
                                onOpenMembers = {
                                    pager.openRaftPage(
                                        pageName = THREAD_MEMBERS_PAGE,
                                        pageData = channelSettingsMembersPageData(route),
                                        storeScopeOverride = storeScope,
                                        authenticatedRouteAuthorityRequired = true,
                                    )
                                },
                                onAddMember = {
                                    pager.openRaftPage(
                                        pageName = THREAD_MEMBERS_PAGE,
                                        pageData = channelSettingsMembersPageData(route, openAddMember = true),
                                        storeScopeOverride = storeScope,
                                        authenticatedRouteAuthorityRequired = true,
                                    )
                                },
                                onToggleMute = { settingsStore.dispatch(ChannelSettingsIntent.ToggleMute) },
                                onTogglePin = { settingsStore.dispatch(ChannelSettingsIntent.TogglePin) },
                                onSaveProfile = { name, description, slackBridge ->
                                    settingsStore.dispatch(ChannelSettingsIntent.UpdateProfile(name, description, slackBridge))
                                },
                                onClearProfileStatus = { settingsStore.dispatch(ChannelSettingsIntent.ClearProfileStatus) },
                                onSetGuestVisible = { settingsStore.dispatch(ChannelSettingsIntent.SetGuestVisible(it)) },
                                onSetGuestJoinable = { settingsStore.dispatch(ChannelSettingsIntent.SetGuestJoinable(it)) },
                                onConfirm = { confirm = it },
                                onUnarchive = { settingsStore.dispatch(ChannelSettingsIntent.Unarchive) },
                                onStopAllAgents = {
                                    sosStore =
                                        ChannelSosStore(
                                            channelId = route.channelId,
                                            channelName = channel.name.removePrefix("#"),
                                            dataSource = agentControlsApi,
                                            scope = scope
                                        )
                                },
                                onOpenJointConnections = {
                                    pager.openRaftPage(
                                        pageName = JOINT_CHANNEL_CONNECTIONS_PAGE,
                                        pageData = jointChannelConnectionsPageData(route),
                                        storeScopeOverride = storeScope,
                                        authenticatedRouteAuthorityRequired = true,
                                    )
                                },
                            ),
                    )
                settingsState.status == ChannelSettingsLoadStatus.Error ||
                    settingsState.status == ChannelSettingsLoadStatus.AuthorityRejected ->
                    ChannelSettingsMessage(
                        title = stringResource(MR.strings.channel_settings_unavailable),
                        detail = stringResource(MR.strings.channel_settings_unavailable_detail),
                        onRetry = { settingsStore.dispatch(ChannelSettingsIntent.Refresh) }
                    )
                else -> ChannelSettingsLoading()
            }
        }

        val activeConfirm = confirm
        if (channel != null && activeConfirm != null) {
            ChannelSettingsConfirmDialog(
                confirm = activeConfirm,
                channelName = channel.name.removePrefix("#"),
                busy = settingsState.actionInFlight == activeConfirm.action,
                onDismiss = { if (settingsState.actionInFlight != activeConfirm.action) confirm = null },
                onConfirm = { settingsStore.dispatch(activeConfirm.intent) },
            )
        }

        val activeSosStore = sosStore
        if (channel != null && activeSosStore != null) {
            ChannelSosDialog(
                channelName = channel.name.removePrefix("#"),
                store = activeSosStore,
                onClose = { sosStore = null }
            )
        }
    }
}

internal fun channelSettingsMembersPageData(route: ChannelSettingsRoute, openAddMember: Boolean = false): JSONObject =
    JSONObject().apply {
        put("pageName", THREAD_MEMBERS_PAGE)
        put("instanceId", "channel-settings-members-${route.channelId.value}")
        put("serverId", route.serverId.value)
        put("channelId", route.channelId.value)
        if (openAddMember) put(THREAD_MEMBERS_INITIAL_VIEW_KEY, "add")
    }

/** The confirmations web EditChannelDialog shows before a lifecycle action (`confirmDialogs`). */
internal enum class ChannelSettingsConfirm(
    val action: ChannelSettingsAction,
    val intent: ChannelSettingsIntent,
) {
    MakePrivate(ChannelSettingsAction.Visibility, ChannelSettingsIntent.MakePrivate),
    MakePublic(ChannelSettingsAction.Visibility, ChannelSettingsIntent.MakePublic),
    Archive(ChannelSettingsAction.Archive, ChannelSettingsIntent.Archive),
    Leave(ChannelSettingsAction.Leave, ChannelSettingsIntent.Leave),
    Delete(ChannelSettingsAction.Delete, ChannelSettingsIntent.Delete),
    Disconnect(ChannelSettingsAction.Disconnect, ChannelSettingsIntent.Disconnect),
}

/** What the page renders, resolved from [ChannelSettingsState] (and fixtures in screenshot tests). */
internal data class ChannelSettingsUiModel(
    val channel: HomeChannelRow,
    val memberSlice: SharedServerChannelMembersSlice,
    val pinned: Boolean,
    val muted: Boolean,
    val permissions: ChannelSettingsPermissions,
    val writeActionsEnabled: Boolean,
    val canStopAllAgents: Boolean,
    val showGuestAccess: Boolean,
    val actionInFlight: ChannelSettingsAction? = null,
    val profileStatus: ChannelProfileUpdateStatus = ChannelProfileUpdateStatus.Idle,
    val profileErrorCode: String? = null,
    /** The Slack channel pick (web ChannelSlackBridgeField); null hides it. */
    val slackBridge: ChannelSlackBridgeState? = null,
)

internal class ChannelSettingsCallbacks(
    val onOpenMembers: () -> Unit = {},
    val onAddMember: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onTogglePin: () -> Unit = {},
    val onSaveProfile: (name: String, description: String, slackBridge: ChannelSlackBridgeSelection?) -> Unit = { _, _, _ -> },
    val onClearProfileStatus: () -> Unit = {},
    val onSetGuestVisible: (Boolean) -> Unit = {},
    val onSetGuestJoinable: (Boolean) -> Unit = {},
    val onConfirm: (ChannelSettingsConfirm) -> Unit = {},
    val onUnarchive: () -> Unit = {},
    val onStopAllAgents: () -> Unit = {},
    val onOpenJointConnections: () -> Unit = {},
)

internal data class ChannelSettingsFailureLabels(
    val update: String,
    val visibility: String,
    val archive: String,
    val unarchive: String,
    val delete: String,
    val disconnect: String,
    val guestAccess: String,
)

@Composable
private fun channelSettingsFailureLabels(): ChannelSettingsFailureLabels =
    ChannelSettingsFailureLabels(
        update = stringResource(MR.strings.thread_settings_update_failed),
        visibility = stringResource(MR.strings.channel_settings_visibility_failed),
        archive = stringResource(MR.strings.thread_error_archive_channel),
        unarchive = stringResource(MR.strings.channel_settings_unarchive_failed),
        delete = stringResource(MR.strings.thread_error_delete_channel),
        disconnect = stringResource(MR.strings.joint_disconnect_failed),
        guestAccess = stringResource(MR.strings.channel_settings_guest_failed),
    )

/**
 * Web keeps the server's `{ error }` where it has one (`axiosErr.response?.data?.error ||
 * fallback`) and its own fallback otherwise.
 */
internal fun channelSettingsFailureMessage(
    failure: ChannelSettingsEffect.Failure,
    labels: ChannelSettingsFailureLabels,
    strings: ErrorPresentationStrings,
): String {
    val fallback =
        when (failure.action) {
            ChannelSettingsAction.Visibility -> labels.visibility
            ChannelSettingsAction.Archive -> labels.archive
            ChannelSettingsAction.Unarchive -> labels.unarchive
            ChannelSettingsAction.Delete -> labels.delete
            ChannelSettingsAction.Disconnect -> labels.disconnect
            ChannelSettingsAction.GuestAccess -> labels.guestAccess
            else -> labels.update
        }
    return when (failure.action) {
        ChannelSettingsAction.Disconnect,
        ChannelSettingsAction.Visibility,
        ChannelSettingsAction.Unarchive,
        ChannelSettingsAction.GuestAccess,
        ChannelSettingsAction.Leave -> channelSettingsDisconnectFailureMessage(failure, strings, fallback)
        else -> fallback
    }
}

/**
 * The page frame: the white top bar (Back, the page title, the refresh mark) over the
 * design-system canvas, mobile's settings-detail pattern ([SettingsDetailShell]), all inside
 * [RaftDesignSystemTheme].
 */
@Composable
internal fun ChannelSettingsScaffold(
    refreshing: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    RaftDesignSystemTheme {
        Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
            RaftTopPageHeader(
                title = stringResource(MR.strings.channel_settings_title),
                iconName = RaftLucideIconName.Settings,
                backgroundColor = RaftColor.SurfaceWhite,
                refreshing = refreshing,
                onBack = onBack
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/**
 * Web channel settings (ChannelOverflowMenu + EditChannelDialog `presentation="panel"`), in
 * its order: the identity (name, visibility tag, description), the members strip, the
 * connected servers of a joint channel, Info (the inline name / description form), Guest
 * access, Preferences, then one Actions group — visibility, Archive / Unarchive, Leave, Stop All
 * Agents, and Delete / Disconnect last. Reversible actions are outlined; only the irreversible
 * one is red.
 */
@Composable
internal fun ChannelSettingsContent(
    model: ChannelSettingsUiModel,
    longMessageCollapseSetting: RaftLongMessageCollapseConversationSetting,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    callbacks: ChannelSettingsCallbacks,
) {
    // task #177: standalone page; the last row clears the system navigation bar.
    val bottomSafeArea = raftBottomSafeAreaPadding()
    val channel = model.channel
    val permissions = model.permissions
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "identity") {
            ChannelIdentityHeader(channel = channel)
        }
        item(key = "members") {
            ChannelMembersSection(
                slice = model.memberSlice,
                fallbackCount = channel.memberCount,
                canAdd = model.memberSlice.canAddMembers && !channel.archived,
                agentStatusResolver = agentStatusResolver,
                onOpen = callbacks.onOpenMembers,
                onAdd = callbacks.onAddMember,
            )
        }
        if (channelSettingsIsJoint(channel)) {
            item(key = "joint-connections") {
                ChannelJointConnectionsEntry(onClick = callbacks.onOpenJointConnections)
            }
        }
        if (channelSettingsShowsInfo(channel, permissions)) {
            item(key = "info") {
                ChannelInfoSection(
                    channel = channel,
                    nameEditable = permissions.editMetadata && !channelSettingsIsAllChannel(channel),
                    enabled = model.writeActionsEnabled,
                    status = model.profileStatus,
                    errorCode = model.profileErrorCode,
                    slackBridge = model.slackBridge?.takeIf { channelSettingsSlackBridgeApplies(channel, permissions) },
                    onSave = callbacks.onSaveProfile,
                    onClearStatus = callbacks.onClearProfileStatus,
                )
            }
        }
        if (model.showGuestAccess) {
            item(key = "guest-access") {
                ChannelGuestAccessSection(
                    channel = channel,
                    enabled = model.writeActionsEnabled && model.actionInFlight != ChannelSettingsAction.GuestAccess,
                    onSetVisible = callbacks.onSetGuestVisible,
                    onSetJoinable = callbacks.onSetGuestJoinable,
                )
            }
        }
        item(key = "preferences") {
            ChannelPreferencesSection(
                muteSupported = channel.activityMuteSupported,
                muted = model.muted,
                pinned = model.pinned,
                enabled = model.writeActionsEnabled,
                longMessageCollapseSetting = longMessageCollapseSetting,
                onToggleMute = callbacks.onToggleMute,
                onTogglePin = callbacks.onTogglePin
            )
        }
        val actions = channelSettingsActionRows(model)
        if (actions.isNotEmpty()) {
            item(key = "actions") {
                ChannelActionsSection(
                    actions = actions,
                    enabled = model.writeActionsEnabled,
                    actionInFlight = model.actionInFlight,
                    callbacks = callbacks,
                )
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(RaftAppSpacing.Lg + bottomSafeArea)) }
    }
}

/** One button of the Actions group, in the web order. */
internal enum class ChannelSettingsActionRow {
    MakePrivate,
    MakePublic,
    Archive,
    Unarchive,
    Leave,
    StopAllAgents,
    Delete,
    Disconnect,
}

/**
 * Web `channel-settings-action-zone`: visibility → Archive / Unarchive → Leave → Stop All Agents →
 * Delete (Disconnect on a joint channel). An archived channel offers only Unarchive.
 */
internal fun channelSettingsActionRows(model: ChannelSettingsUiModel): List<ChannelSettingsActionRow> {
    val channel = model.channel
    val permissions = model.permissions
    return buildList {
        if (channelSettingsShowsVisibilityAction(channel, permissions)) {
            add(if (channelSettingsCanMakePublic(channel)) ChannelSettingsActionRow.MakePublic else ChannelSettingsActionRow.MakePrivate)
        }
        if (channelSettingsShowsArchiveAction(channel, permissions)) {
            add(if (channel.archived) ChannelSettingsActionRow.Unarchive else ChannelSettingsActionRow.Archive)
        }
        if (channelSettingsCanLeave(channel)) add(ChannelSettingsActionRow.Leave)
        if (model.canStopAllAgents) add(ChannelSettingsActionRow.StopAllAgents)
        if (channelSettingsShowsDeleteAction(channel, permissions)) {
            when {
                channelSettingsCanDisconnect(channel) -> add(ChannelSettingsActionRow.Disconnect)
                channelSettingsCanDelete(channel) -> add(ChannelSettingsActionRow.Delete)
            }
        }
    }
}

/**
 * Web overflow header: `#name` with its visibility as a neutral outline tag, the description under it.
 * A long press copies the name or the description, as shown (`#name` with its `#`).
 */
@Composable
private fun ChannelIdentityHeader(channel: HomeChannelRow) {
    val colors = RaftTheme.colors
    val description = channel.subtitle?.trim().takeUnless { it.isNullOrBlank() }
    val shownName = channelSettingsHeaderName(channel.name)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftText(
                text = shownName,
                modifier = Modifier.weight(1f, fill = false).raftCopyable(shownName),
                style = RaftTheme.typography.headline,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            RaftBadge(text = channelSettingsStatusLabel(channel), appearance = RaftBadgeAppearance.Outline)
        }
        RaftText(
            text = description ?: stringResource(MR.strings.channel_settings_description_empty),
            modifier = Modifier.raftCopyable(description),
            style = RaftTheme.typography.callout,
            color = if (description != null) colors.foregroundMuted else colors.foregroundHint,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The header's channel name, shown and copied as `#name` (one `#`, whatever the stored form). */
internal fun channelSettingsHeaderName(name: String): String = "#${name.removePrefix("#")}"

@Composable
private fun channelSettingsStatusLabel(channel: HomeChannelRow): String =
    when {
        channel.archived -> stringResource(MR.strings.channel_settings_status_archived)
        channelSettingsIsJoint(channel) -> stringResource(MR.strings.channel_settings_status_joint)
        channelSettingsIsPrivate(channel) -> stringResource(MR.strings.channel_settings_status_private)
        else -> stringResource(MR.strings.channel_settings_status_public)
    }

/**
 * Web members strip: the heading row ("Members", then "N humans · M agents" and a chevron) opens
 * the members page; up to three rows of 35dp faces (agents carry their live activity light), a
 * `+N` tile when they overflow and the dashed add tile, which opens Add Member directly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelMembersSection(
    slice: SharedServerChannelMembersSlice,
    fallbackCount: Int?,
    canAdd: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
) {
    val members = slice.members.distinctBy { it.id }
    val loading = slice.status == SharedServerMembersLoadStatus.InFlight && members.isEmpty()
    val summary = channelSettingsMemberSummary(slice = slice, fallbackCount = fallbackCount)
    RaftSection(
        title = stringResource(MR.strings.channel_settings_members),
        modifier = Modifier.clickable(onClick = onOpen),
        action = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(summary, style = RaftTheme.typography.caption, color = RaftTheme.colors.foregroundHint)
                RaftListRowChevron()
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = channelSettingsMemberStripColumns(maxWidth)
            val visible = channelSettingsMemberStripVisibleCount(members.size + slice.externalMembers.size, columns, canAdd)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ChannelSettingsStripGap),
                verticalArrangement = Arrangement.spacedBy(ChannelSettingsStripGap),
            ) {
                if (loading) {
                    repeat(3) {
                        RaftSkeleton(Modifier.size(ChannelSettingsStripTile), variant = RaftSkeletonVariant.Circle)
                    }
                } else {
                    members.take(visible).forEach { member -> ChannelSettingsMemberAvatar(member, agentStatusResolver) }
                    slice.externalMembers.take((visible - members.size).coerceAtLeast(0)).forEach { external ->
                        RaftAppAvatar(
                            avatarWireValue = external.avatarUrl,
                            label = external.displayName,
                            avatarSize = ChannelSettingsStripTile,
                        )
                    }
                    val overflow = members.size + slice.externalMembers.size - visible
                    if (overflow > 0) RaftAvatarGroupCount(text = "+$overflow", size = RaftAvatarSize.Md)
                }
                if (canAdd) {
                    RaftAvatarEmpty(
                        size = RaftAvatarSize.Md,
                        contentDescription = stringResource(MR.strings.thread_action_add_member),
                        enabled = !loading,
                        onClick = onAdd,
                    )
                }
            }
        }
    }
}

private val ChannelSettingsStripTile = 35.dp
private val ChannelSettingsStripGap = 8.dp
private const val ChannelSettingsStripMaxRows = 3

internal fun channelSettingsMemberStripColumns(width: Dp): Int =
    ((width + ChannelSettingsStripGap) / (ChannelSettingsStripTile + ChannelSettingsStripGap)).toInt().coerceAtLeast(1)

/**
 * Web `getVisibleMemberLimit`: three rows; the add slot is always kept, and once members overflow
 * one more slot goes to `+N`.
 */
internal fun channelSettingsMemberStripVisibleCount(total: Int, columns: Int, withAddSlot: Boolean = false): Int {
    val capacity = ChannelSettingsStripMaxRows * columns
    val addSlots = if (withAddSlot) 1 else 0
    return if (total + addSlots <= capacity) total else (capacity - addSlots - 1).coerceAtLeast(0)
}

/** Web `membersSummary` / `membersSummaryWithSlack` ("4 humans · 2 agents · 1 Slack"), or loading. */
@Composable
private fun channelSettingsMemberSummary(slice: SharedServerChannelMembersSlice, fallbackCount: Int?): String {
    if (slice.status == SharedServerMembersLoadStatus.InFlight && slice.members.isEmpty()) {
        return stringResource(MR.strings.common_loading)
    }
    if (slice.status != SharedServerMembersLoadStatus.Loaded && slice.members.isEmpty()) {
        return (fallbackCount ?: 0).toString()
    }
    val members = slice.members.distinctBy { it.id }
    val humans = members.count { it.kind == MemberKind.Human }
    val agents = members.count { it.kind == MemberKind.Agent }
    val parts =
        buildList {
            add(
                if (humans == 1) stringResource(MR.strings.channel_settings_members_humans_one, humans)
                else stringResource(MR.strings.channel_settings_members_humans_other, humans)
            )
            add(
                if (agents == 1) stringResource(MR.strings.channel_settings_members_agents_one, agents)
                else stringResource(MR.strings.channel_settings_members_agents_other, agents)
            )
            if (slice.externalMembers.isNotEmpty()) {
                add(stringResource(MR.strings.channel_settings_members_slack, slice.externalMembers.size))
            }
        }
    return parts.joinToString(" · ")
}

internal fun channelSettingsMemberCountLabel(
    slice: SharedServerChannelMembersSlice,
    fallbackCount: Int?,
    loadingLabel: String,
): String =
    when {
        slice.status == SharedServerMembersLoadStatus.Loaded -> slice.members.size.toString()
        slice.status == SharedServerMembersLoadStatus.InFlight && slice.members.isEmpty() -> loadingLabel
        else -> (fallbackCount ?: slice.members.size).toString()
    }

@Composable
private fun ChannelSettingsMemberAvatar(member: MemberProfile, agentStatusResolver: RaftAgentStatusResolver) {
    val avatar: @Composable () -> Unit = {
        RaftAppAvatar(
            avatarWireValue = member.avatarWireValueForAvatarSlot(),
            label = member.raftAvatarName(),
            avatarSize = ChannelSettingsStripTile,
            cacheSubject = member.raftAvatarCacheSubject(),
            cacheServerId = member.serverId?.value,
            fallbackFill = raftAvatarFallbackFill(member.kind)
        )
    }
    if (member.kind != MemberKind.Agent) {
        avatar()
        return
    }
    // Web AgentActivityDot: the agent's live activity in the corner; it never pulses.
    val indicator = rememberRaftAgentStatusIndicatorState(agentStatusResolver, member.id.value)
    RaftAvatarWithStatus(
        statusColor = indicator?.color,
        avatarSize = ChannelSettingsStripTile,
        statusPulsing = false,
        cutoutColor = RaftTheme.colors.layerCanvas,
    ) { avatar() }
}

/**
 * Web Info section (EditChannelDialog panel form): Name and Description edited in place, with
 * Cancel and Save under them; Save stays off until something changed. `#all` keeps its name.
 */
@Composable
private fun ChannelInfoSection(
    channel: HomeChannelRow,
    nameEditable: Boolean,
    enabled: Boolean,
    status: ChannelProfileUpdateStatus,
    errorCode: String?,
    slackBridge: ChannelSlackBridgeState?,
    onSave: (name: String, description: String, slackBridge: ChannelSlackBridgeSelection?) -> Unit,
    onClearStatus: () -> Unit,
) {
    val savedSlack = slackBridge?.pairedSlackChannelId
    var slackPick by remember(channel.channelId, savedSlack) { mutableStateOf(savedSlack) }
    val slackDirty = slackBridge != null && slackPick != savedSlack
    val savedName = channel.name.removePrefix("#")
    val savedDescription = channel.subtitle.orEmpty()
    var name by remember(channel.channelId) { mutableStateOf(savedName) }
    var description by remember(channel.channelId) { mutableStateOf(savedDescription) }
    // A background reload moves the saved values; the fields follow them only while untouched.
    var baseline by remember(channel.channelId) { mutableStateOf(savedName to savedDescription) }
    LaunchedEffect(savedName, savedDescription) {
        if (baseline != savedName to savedDescription) {
            if (name.trim() == baseline.first.trim()) name = savedName
            if (description.trim() == baseline.second.trim()) description = savedDescription
            baseline = savedName to savedDescription
        }
    }
    val saving = status == ChannelProfileUpdateStatus.InFlight
    val trimmedName = name.trim()
    val trimmedDescription = description.trim()
    val dirty = trimmedName != savedName.trim() || trimmedDescription != savedDescription.trim() || slackDirty
    val nameInvalid = trimmedName.isNotEmpty() && !trimmedName.isSubmittableChannelName()
    val nameError =
        when {
            nameInvalid -> stringResource(MR.strings.channel_create_error_name_format)
            status == ChannelProfileUpdateStatus.Failed && errorCode == "channel_name_invalid" ->
                stringResource(MR.strings.channel_create_error_name_format)
            else -> null
        }
    val clearStatusOnEdit: () -> Unit = { if (status != ChannelProfileUpdateStatus.Idle && !saving) onClearStatus() }
    RaftSection(title = stringResource(MR.strings.channel_settings_info)) {
        RaftText(
            stringResource(MR.strings.channel_settings_info_detail),
            modifier = Modifier.padding(bottom = 12.dp),
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundHint,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftField(
                label = stringResource(MR.strings.thread_settings_name),
                required = true,
                error = nameError,
                enabled = enabled && nameEditable && !saving,
            ) {
                RaftTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        clearStatusOnEdit()
                    },
                    placeholder = stringResource(MR.strings.thread_settings_name_placeholder),
                )
            }
            RaftField(
                label = stringResource(MR.strings.thread_settings_description),
                optional = true,
                enabled = enabled && !saving,
            ) {
                RaftTextarea(
                    value = description,
                    onValueChange = {
                        description = it
                        clearStatusOnEdit()
                    },
                    placeholder = stringResource(MR.strings.thread_settings_description_placeholder),
                    limit = ChannelSettingsDescriptionLimit,
                )
            }
            if (slackBridge != null) {
                ChannelSlackBridgeField(
                    state = slackBridge,
                    selected = slackPick,
                    enabled = enabled && !saving,
                    onSelect = {
                        slackPick = it
                        clearStatusOnEdit()
                    },
                )
            }
            if (status == ChannelProfileUpdateStatus.Failed && errorCode != "channel_name_invalid") {
                RaftBanner(
                    description =
                        stringResource(
                            if (errorCode == CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE) {
                                MR.strings.channel_settings_slack_partial_failure
                            } else {
                                MR.strings.thread_settings_update_failed
                            }
                        ),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (status == ChannelProfileUpdateStatus.Succeeded && !dirty) {
                    RaftText(
                        stringResource(MR.strings.channel_settings_save_success),
                        modifier = Modifier.weight(1f),
                        style = RaftTheme.typography.footnote,
                        color = RaftTheme.colors.foregroundHint,
                    )
                }
                RaftButton(
                    text = stringResource(MR.strings.common_cancel),
                    onClick = {
                        name = savedName
                        description = savedDescription
                        slackPick = savedSlack
                        clearStatusOnEdit()
                    },
                    variant = RaftButtonVariant.Ghost,
                    enabled = dirty && !saving,
                )
                RaftButton(
                    text = stringResource(MR.strings.common_save),
                    onClick = {
                        onSave(trimmedName, trimmedDescription, if (slackDirty) ChannelSlackBridgeSelection(slackPick) else null)
                    },
                    variant = RaftButtonVariant.Primary,
                    enabled =
                        enabled &&
                            dirty &&
                            trimmedName.isSubmittableChannelName() &&
                            trimmedDescription.length <= ChannelSettingsDescriptionLimit,
                    loading = saving,
                    loadingLabel = stringResource(MR.strings.thread_settings_saving),
                )
            }
        }
    }
}

/**
 * Web ChannelSlackBridgeField: "Connect to Slack" with its line, and the Slack channel select —
 * "Do not connect to Slack" first, then the Slack channels of the channel's visibility; one the
 * Raft app is not in yet, or paired with another channel, is listed but can't be picked. Saved
 * with the Info form's Save, as on the web.
 */
@Composable
private fun ChannelSlackBridgeField(
    state: ChannelSlackBridgeState,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    val notConnected = stringResource(MR.strings.channel_settings_slack_not_connected)
    val options: List<ChannelSlackBridgeOption?> = listOf(null) + state.options
    val labels =
        options.associateWith { option ->
            when {
                option == null -> notConnected
                option.membershipRequired -> stringResource(MR.strings.channel_settings_slack_membership_required, option.name)
                option.pairedElsewhere && option.slackChannelId != selected ->
                    stringResource(MR.strings.channel_settings_slack_already_paired, option.name)
                else -> stringResource(MR.strings.channel_settings_slack_channel, option.name)
            }
        }
    RaftField(
        label = stringResource(MR.strings.channel_settings_slack_title),
        description = stringResource(MR.strings.channel_settings_slack_detail),
        enabled = enabled,
        labelTrailing = { RaftBadge(text = stringResource(MR.strings.channel_bridge_provider_slack), appearance = RaftBadgeAppearance.Outline) },
    ) {
        RaftSelect(
            value = options.firstOrNull { it?.slackChannelId == selected },
            options = options,
            onValueChange = { onSelect(it?.slackChannelId) },
            optionLabel = { labels.getValue(it) },
            optionEnabled = { option ->
                option == null || (!option.membershipRequired && (!option.pairedElsewhere || option.slackChannelId == selected))
            },
        )
    }
}

/** Server PATCH `/channels/:id`: "Description must be a string of at most 500 characters". */
private const val ChannelSettingsDescriptionLimit = 500

/**
 * Web Guest access section (`canManageGuestAccess`): Visible to Guests, then Allow Guests to
 * join (not on `#all`). Turning visibility off also stops joining; allowing joining also makes
 * the channel visible (the server requires it). The usage line is web's plan limit, unlimited
 * (`-1`) on every plan today, so it is not shown.
 */
@Composable
private fun ChannelGuestAccessSection(
    channel: HomeChannelRow,
    enabled: Boolean,
    onSetVisible: (Boolean) -> Unit,
    onSetJoinable: (Boolean) -> Unit,
) {
    RaftSection(title = stringResource(MR.strings.channel_settings_guest_title)) {
        RaftText(
            stringResource(MR.strings.channel_settings_guest_detail),
            modifier = Modifier.padding(bottom = 12.dp),
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundHint,
        )
        RaftListRowGroup(Modifier.fillMaxWidth()) {
            ChannelPreferenceRow(
                title = stringResource(MR.strings.channel_settings_guest_visible),
                detail = stringResource(MR.strings.channel_settings_guest_visible_detail),
                checked = channel.guestVisible,
                enabled = enabled,
                divider = false,
                onToggle = { onSetVisible(!channel.guestVisible) },
            )
            if (!channelSettingsIsAllChannel(channel)) {
                ChannelPreferenceRow(
                    title = stringResource(MR.strings.channel_settings_guest_joinable),
                    detail = stringResource(MR.strings.channel_settings_guest_joinable_detail),
                    checked = channel.guestJoinable,
                    enabled = enabled,
                    divider = true,
                    onToggle = { onSetJoinable(!channel.guestJoinable) },
                )
            }
        }
    }
}

/** Web ChannelPreferencesSection: Pin, Mute, Collapse long messages — switches that apply at once. */
@Composable
private fun ChannelPreferencesSection(
    muteSupported: Boolean,
    muted: Boolean,
    pinned: Boolean,
    enabled: Boolean,
    longMessageCollapseSetting: RaftLongMessageCollapseConversationSetting,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit
) {
    RaftSection(title = stringResource(MR.strings.channel_settings_preferences)) {
        RaftListRowGroup(Modifier.fillMaxWidth()) {
            ChannelPreferenceRow(
                title = stringResource(MR.strings.channel_settings_pin),
                detail = stringResource(MR.strings.channel_settings_pin_detail),
                checked = pinned,
                enabled = enabled,
                divider = false,
                onToggle = onTogglePin
            )
            ChannelPreferenceRow(
                title = stringResource(MR.strings.channel_settings_mute),
                detail = stringResource(MR.strings.channel_settings_mute_detail),
                checked = muted,
                enabled = muteSupported && enabled,
                divider = true,
                onToggle = onToggleMute
            )
            // Device-local display preference: available without write permission on the channel.
            ChannelPreferenceRow(
                title = stringResource(MR.strings.conversation_long_message_collapse),
                detail = longMessageCollapseSetting.preference.localizedDetail(),
                checked = longMessageCollapseSetting.effective,
                enabled = longMessageCollapseSetting.available,
                divider = true,
                onToggle = { longMessageCollapseSetting.onSetEnabled(!longMessageCollapseSetting.effective) }
            )
        }
    }
}

@Composable
private fun ChannelPreferenceRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    divider: Boolean,
    onToggle: () -> Unit
) {
    RaftListRow(
        title = title,
        variant = RaftListRowVariant.Plain,
        onClick = onToggle,
        enabled = enabled,
        divider = divider,
        description = detail,
        wrapDescription = true,
        trailing = {
            RaftSwitch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled, label = title)
        },
    )
}

/**
 * Web lifecycle group ("Actions"): full-width buttons in the web order. Reversible actions are
 * outlined; Delete / Disconnect, the irreversible one, is the only red one. Each asks first
 * (web `confirmDialogs`), except Unarchive and Stop All Agents (its own dialog).
 */
@Composable
private fun ChannelActionsSection(
    actions: List<ChannelSettingsActionRow>,
    enabled: Boolean,
    actionInFlight: ChannelSettingsAction?,
    callbacks: ChannelSettingsCallbacks,
) {
    RaftSection(title = stringResource(MR.strings.channel_settings_actions)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { row ->
                val buttonEnabled = enabled && actionInFlight == null
                when (row) {
                    ChannelSettingsActionRow.MakePrivate ->
                        ChannelActionButton(
                            stringResource(MR.strings.thread_settings_make_private),
                            RaftLucideIconName.Lock,
                            enabled = buttonEnabled,
                        ) { callbacks.onConfirm(ChannelSettingsConfirm.MakePrivate) }
                    ChannelSettingsActionRow.MakePublic ->
                        ChannelActionButton(
                            stringResource(MR.strings.channel_settings_make_public),
                            RaftLucideIconName.Hash,
                            enabled = buttonEnabled,
                        ) { callbacks.onConfirm(ChannelSettingsConfirm.MakePublic) }
                    ChannelSettingsActionRow.Archive ->
                        ChannelActionButton(
                            stringResource(MR.strings.thread_settings_archive),
                            RaftLucideIconName.Archive,
                            enabled = buttonEnabled,
                        ) { callbacks.onConfirm(ChannelSettingsConfirm.Archive) }
                    ChannelSettingsActionRow.Unarchive ->
                        ChannelActionButton(
                            stringResource(MR.strings.channel_settings_unarchive),
                            RaftLucideIconName.Archive,
                            enabled = enabled && (actionInFlight == null || actionInFlight == ChannelSettingsAction.Unarchive),
                            loading = actionInFlight == ChannelSettingsAction.Unarchive,
                            loadingLabel = stringResource(MR.strings.channel_settings_unarchiving),
                            onClick = callbacks.onUnarchive,
                        )
                    ChannelSettingsActionRow.Leave ->
                        ChannelActionButton(
                            stringResource(MR.strings.channel_settings_leave),
                            RaftLucideIconName.LogOut,
                            enabled = buttonEnabled,
                        ) { callbacks.onConfirm(ChannelSettingsConfirm.Leave) }
                    ChannelSettingsActionRow.StopAllAgents ->
                        ChannelActionButton(
                            stringResource(MR.strings.channel_sos_entry),
                            RaftLucideIconName.Square,
                            onClick = callbacks.onStopAllAgents,
                        )
                    ChannelSettingsActionRow.Delete ->
                        ChannelActionButton(
                            stringResource(MR.strings.thread_settings_delete),
                            RaftLucideIconName.Trash2,
                            variant = RaftButtonVariant.Danger,
                            enabled = buttonEnabled,
                        ) { callbacks.onConfirm(ChannelSettingsConfirm.Delete) }
                    ChannelSettingsActionRow.Disconnect ->
                        ChannelActionButton(
                            stringResource(MR.strings.joint_disconnect_channel),
                            RaftLucideIconName.Unlink,
                            variant = RaftButtonVariant.Danger,
                            enabled = buttonEnabled,
                        ) { callbacks.onConfirm(ChannelSettingsConfirm.Disconnect) }
                }
            }
        }
    }
}

@Composable
private fun ChannelActionButton(
    text: String,
    icon: RaftLucideIconName,
    variant: RaftButtonVariant = RaftButtonVariant.Default,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String? = null,
    onClick: () -> Unit,
) {
    RaftButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        variant = variant,
        size = RaftButtonSize.Lg,
        enabled = enabled,
        loading = loading,
        loadingLabel = loadingLabel ?: text,
        leadingIcon = { RaftComputersIcon(icon) },
    )
}

/** Joint channels: the entry to Connected servers (list, resend, invite another server). */
@Composable
private fun ChannelJointConnectionsEntry(onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        RaftSeparator()
        RaftListRowGroup(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            RaftListRow(
                title = stringResource(MR.strings.joint_connections_title),
                onClick = onClick,
                description = stringResource(MR.strings.joint_connections_rule),
                wrapDescription = true,
                descriptionMaxLines = 2,
                leading = { RaftComputersIcon(RaftLucideIconName.GitBranch, size = 20.dp) },
                trailing = { RaftListRowChevron() },
            )
        }
    }
}

/**
 * Web EditChannelDialog `confirmDialogs` (raft-ui ConfirmDialog): the action's title, the web
 * consequence copy, Cancel and the confirm button — orange (Warning) for what can be undone,
 * red (Danger) for Delete and Disconnect. The confirm button shows the web's busy label while
 * the request runs; the dialog closes when it ends.
 */
@Composable
private fun ChannelSettingsConfirmDialog(
    confirm: ChannelSettingsConfirm,
    channelName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title: String
    val description: String
    val confirmLabel: String
    val loadingLabel: String
    val variant: RaftButtonVariant
    when (confirm) {
        ChannelSettingsConfirm.MakePrivate -> {
            title = stringResource(MR.strings.thread_settings_make_private_title)
            description = stringResource(MR.strings.channel_settings_make_private_confirm, channelName)
            confirmLabel = stringResource(MR.strings.thread_settings_make_private)
            loadingLabel = stringResource(MR.strings.channel_settings_updating)
            variant = RaftButtonVariant.Warning
        }
        ChannelSettingsConfirm.MakePublic -> {
            title = stringResource(MR.strings.channel_settings_make_public_title)
            description = stringResource(MR.strings.channel_settings_make_public_confirm, channelName)
            confirmLabel = stringResource(MR.strings.channel_settings_make_public)
            loadingLabel = stringResource(MR.strings.channel_settings_updating)
            variant = RaftButtonVariant.Warning
        }
        ChannelSettingsConfirm.Archive -> {
            title = stringResource(MR.strings.thread_settings_archive_title)
            description = stringResource(MR.strings.channel_settings_archive_confirm, channelName)
            confirmLabel = stringResource(MR.strings.thread_settings_archive_confirm)
            loadingLabel = stringResource(MR.strings.channel_settings_archiving)
            variant = RaftButtonVariant.Warning
        }
        ChannelSettingsConfirm.Leave -> {
            title = stringResource(MR.strings.channel_settings_leave)
            description = stringResource(MR.strings.channel_settings_leave_confirm, channelName)
            confirmLabel = stringResource(MR.strings.channel_settings_leave_action)
            loadingLabel = stringResource(MR.strings.channel_settings_leaving)
            variant = RaftButtonVariant.Warning
        }
        ChannelSettingsConfirm.Delete -> {
            title = stringResource(MR.strings.thread_settings_delete_title)
            description = stringResource(MR.strings.channel_settings_delete_confirm, channelName)
            confirmLabel = stringResource(MR.strings.thread_settings_delete)
            loadingLabel = stringResource(MR.strings.channel_settings_deleting)
            variant = RaftButtonVariant.Danger
        }
        ChannelSettingsConfirm.Disconnect -> {
            title = stringResource(MR.strings.joint_disconnect_title)
            description = stringResource(MR.strings.joint_disconnect_confirm, channelName)
            confirmLabel = stringResource(MR.strings.joint_disconnect_action)
            loadingLabel = stringResource(MR.strings.joint_disconnecting)
            variant = RaftButtonVariant.Danger
        }
    }
    RaftDesignSystemTheme {
        RaftDialog(
            onDismissRequest = onDismiss,
            title = title,
            description = description,
            size = RaftDialogSize.Sm,
            dismissOnClickOutside = !busy,
            closeLabel = stringResource(MR.strings.common_close),
            footer = {
                RaftButton(
                    text = stringResource(MR.strings.common_cancel),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !busy,
                )
                RaftButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    variant = variant,
                    size = RaftButtonSize.Lg,
                    loading = busy,
                    loadingLabel = loadingLabel,
                )
            },
        )
    }
}

/** Disconnect failures keep the server's reason (web shows the route's `error`), via the app error pipeline. */
internal fun channelSettingsDisconnectFailureMessage(
    failure: ChannelSettingsEffect.Failure,
    strings: ErrorPresentationStrings,
    fallback: String,
): String =
    ScreenError(code = failure.code, retryable = false, serverMessage = failure.serverMessage)
        .userFacingMessage(strings)
        .takeIf(String::isNotBlank)
        ?: fallback

internal fun channelSettingsManagementActions(
    showMakePrivate: Boolean,
    showDelete: Boolean = true,
): List<RaftThreadSettingsAction> =
    buildList {
        if (showMakePrivate) {
            add(RaftThreadSettingsAction(id = "visibility"))
        }
        add(RaftThreadSettingsAction(id = "archive"))
        if (showDelete) {
            add(RaftThreadSettingsAction(id = "delete"))
        }
    }

/**
 * Placeholders in the page's shape while the first load runs, shown only after 1s of it
 * (raft-ui 0.22.1 #720), so a fast load doesn't flash them.
 */
@Composable
private fun ChannelSettingsLoading() {
    RaftSkeletonList(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RaftSkeleton(Modifier.width(160.dp), variant = RaftSkeletonVariant.Line)
        RaftSkeleton(Modifier.fillMaxWidth(0.8f), variant = RaftSkeletonVariant.Line)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(ChannelSettingsStripGap)) {
            repeat(3) { RaftSkeleton(Modifier.size(ChannelSettingsStripTile), variant = RaftSkeletonVariant.Circle) }
        }
        RaftSkeleton(Modifier.fillMaxWidth().height(180.dp))
    }
}

@Composable
private fun ChannelSettingsMessage(title: String, detail: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RaftText(title, style = RaftTheme.typography.headline, color = RaftTheme.colors.foreground, textAlign = TextAlign.Center)
        RaftText(detail, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted, textAlign = TextAlign.Center)
        RaftButton(
            text = stringResource(MR.strings.common_retry),
            onClick = onRetry,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.RefreshCw) },
        )
    }
}

@Composable
private fun ChannelSettingsInvalidRouteScreen(onBack: () -> Unit) {
    ChannelSettingsScaffold(refreshing = false, onBack = onBack) {
        ChannelSettingsMessage(
            title = stringResource(MR.strings.channel_settings_unavailable),
            detail = stringResource(MR.strings.channel_settings_invalid_route),
            onRetry = onBack
        )
    }
}
