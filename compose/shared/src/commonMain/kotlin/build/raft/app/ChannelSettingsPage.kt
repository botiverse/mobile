package build.raft.app

import build.raft.app.resources.*
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
import build.raft.app.data.ARCHIVED_NAME_COLLISION
import build.raft.app.data.ArchivedNameCollision
import build.raft.app.data.EXTERNAL_CHANNELS_FEATURE_FLAG_KEY
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.ScreenError
import build.raft.designsystem.components.RaftTextFieldDefaults
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
import build.raft.designsystem.components.RaftSelectFitMaxWidth
import build.raft.designsystem.components.RaftSelectFitMinWidth
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
import build.raft.designsystem.components.RaftFormSection
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
import build.raft.app.data.serverRoleCanStopAgents
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
import androidx.compose.foundation.background
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
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftRiskTone
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftPointerInput
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight

internal const val CHANNEL_SETTINGS_PAGE = "compose_channel_settings"

internal data class ChannelSettingsRoute(
    val serverId: ServerId,
    val channelId: ChannelId
)

/** [threadLinkKey]: the [ThreadSettingsPageLinks] entry of the conversation that opened the page. */
internal fun channelSettingsPageData(
    serverId: ServerId,
    channelId: ChannelId,
    threadLinkKey: String? = null,
): JSONObject =
    JSONObject().apply {
        put("pageName", CHANNEL_SETTINGS_PAGE)
        put("serverId", serverId.value)
        put("channelId", channelId.value)
        threadLinkKey?.let { put(THREAD_SETTINGS_LINK_KEY, it) }
    }

/** Where the conversation goes once the settings page has closed. */
internal enum class ChannelSettingsCloseLanding {
    /** Archive and Unarchive: the channel is still readable (web keeps it open). */
    StayInChannel,

    /** Leaving a public channel: still readable, the composer offers Join (web EditChannelDialog). */
    StayNotJoined,

    /** Deleted, disconnected, or a private or joint channel left: nothing to read here any more. */
    LeaveConversation,
}

/**
 * Web EditChannelDialog: Delete and Disconnect go elsewhere; Leave stays with membership false. A
 * private or joint channel left is gone until someone invites you back, so that one goes too. A
 * Leave without a loaded channel can't show it is public: it goes rather than show a stale page.
 */
internal fun channelSettingsCloseLanding(
    closedBy: ChannelSettingsAction?,
    channel: HomeChannelRow?,
): ChannelSettingsCloseLanding =
    when (closedBy) {
        ChannelSettingsAction.Delete,
        ChannelSettingsAction.Disconnect -> ChannelSettingsCloseLanding.LeaveConversation
        ChannelSettingsAction.Leave ->
            if (channel == null || channelSettingsIsPrivate(channel)) {
                ChannelSettingsCloseLanding.LeaveConversation
            } else {
                ChannelSettingsCloseLanding.StayNotJoined
            }
        else -> ChannelSettingsCloseLanding.StayInChannel
    }

/** What Home does at once for [this] landing; its Refresh may read a replica that is behind. */
internal fun ChannelSettingsCloseLanding.homeIntent(channelId: ChannelId): SharedHomeIntent? =
    when (this) {
        ChannelSettingsCloseLanding.StayInChannel -> null
        ChannelSettingsCloseLanding.StayNotJoined -> SharedHomeIntent.ChannelMembershipEnded(channelId, keepListed = true)
        ChannelSettingsCloseLanding.LeaveConversation -> SharedHomeIntent.ChannelMembershipEnded(channelId, keepListed = false)
    }

/**
 * What Home does at once when the page closes. Archive keeps you on the page but the channel leaves
 * Home (fetches no longer list it), so its guards must not bring it back.
 */
internal fun channelSettingsCloseHomeIntent(
    closedBy: ChannelSettingsAction?,
    landing: ChannelSettingsCloseLanding,
    channelId: ChannelId,
): SharedHomeIntent? =
    if (closedBy == ChannelSettingsAction.Archive) {
        SharedHomeIntent.ChannelMembershipEnded(channelId, keepListed = false)
    } else {
        landing.homeIntent(channelId)
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

/** Web ChatPanel `joined && capabilities.stopAgents` (owner/admin, slock #12029); channels only (a DM has no SOS). */
internal fun channelSettingsCanStopAllAgents(channel: HomeChannelRow, serverRole: String?): Boolean =
    channel.joined &&
        !channel.archived &&
        channel.kind != ConversationKind.DirectMessage &&
        serverRoleCanStopAgents(serverRole)

internal fun channelSettingsUsesStandalonePage(newDesignSettingsEnabled: Boolean): Boolean =
    newDesignSettingsEnabled

/** The channel's wire type, as `channelTypeSupportsAnnouncement` reads it ("channel", "private", "joint", "dm", ...). */
internal fun channelSettingsAnnouncementChannelType(channel: HomeChannelRow): String =
    when (channel.kind) {
        ConversationKind.Joint -> "joint"
        ConversationKind.Channel -> channel.channelType?.takeIf { it.isNotBlank() } ?: "channel"
        ConversationKind.DirectMessage -> "dm"
        else -> channel.kind.name.lowercase()
    }

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
 * Web EditChannelDialog: a completed joint channel shows "Disconnect channel" where other channels
 * show Delete, hidden once archived. The route itself is gated on `federateChannels` (owner/admin),
 * which is the same role set as [ChannelSettingsState.canManage].
 */
internal fun channelSettingsCanDisconnect(channel: HomeChannelRow): Boolean =
    channelSettingsIsJoint(channel) && !channel.archived

/**
 * Web EditChannelDialog `hostCannotDisconnect`: with `external_channels_v1` on, the host's copy of a
 * joint channel (the server's `jointRole`) offers no Disconnect (the server refuses it,
 * `host_cannot_disconnect`) and no Delete; Archive is what it has, and its confirm says it reaches
 * every server.
 */
internal fun channelSettingsHostCannotDisconnect(channel: HomeChannelRow, externalChannelsEnabled: Boolean): Boolean =
    externalChannelsEnabled && channelSettingsIsJoint(channel) && channel.jointRole == "host"

/**
 * Web EditChannelDialog `showPrivateConnectSection` (cindyz 38086073): with `external_channels_v1`
 * on, a private channel (never #all) the viewer is in shows Connected servers, with this server
 * as the host, when they have a server to connect to it (web `useMemberConnectRows`). The servers
 * and roles are the account's from `GET /api/servers`; the route decides.
 *
 * The web also hides it while a conversion to joint is in progress (`!conversionInProgress`: its
 * own Convert action, or the conversion receipt). The app has neither: it cannot start a
 * conversion and does not read `conversionJob` or conversion receipts, so there is nothing here to
 * gate on. A channel whose conversion has finished arrives as `joint` and is refused above.
 */
internal fun channelSettingsOffersPrivateMemberConnect(
    channel: HomeChannelRow,
    externalChannelsEnabled: Boolean,
    account: MemberConnectAccount?,
): Boolean {
    if (!externalChannelsEnabled || channelSettingsIsJoint(channel) || !channel.channelType.equals("private", ignoreCase = true)) {
        return false
    }
    val memberChannel =
        MemberConnectChannel(
            isJoint = false,
            isPrivate = true,
            name = channel.name,
            archived = channel.archived,
            joined = channel.joined,
            servers = emptyList(),
        )
    return memberConnectRows(memberChannel, account).any { it.state == MemberConnectRowState.Eligible }
}

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

/**
 * The settings store of one channel as the settings page and the conversation's settings drawer
 * both use it: seeded from Home's snapshot and the members store (no blank first frame), then
 * loading the channel's settings through the route's authority.
 */
internal fun createChannelSettingsStore(
    route: ChannelSettingsRoute,
    routeOwner: build.raft.app.data.AuthenticatedRouteOwner,
    sharedStores: build.raft.app.data.RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    scope: kotlinx.coroutines.CoroutineScope,
    logger: (String) -> Unit,
): ChannelSettingsStore {
    val homeStore = sharedStores.sharedHomeStore(storeScope)
    val membersStore = sharedStores.serverMembersStore(storeScope)
    val initialSharedHandoff =
        channelSettingsSharedHandoffSnapshot(
            owner = routeOwner,
            route = route,
            homeSnapshot = homeStore?.snapshotFor(route.serverId),
            membersHandoff = membersStore.currentChannelMembersHandoff(routeOwner, route.channelId),
        )
    val dataSource =
        RealChannelSettingsDataSource(
            route = route,
            authStore = sharedStores.authStore(storeScope),
            owner = routeOwner,
            gateway = sharedStores.authenticatedRouteAuthorityGateway(storeScope),
            logger = logger,
        )
    return ChannelSettingsStore(
        route = route,
        owner = routeOwner,
        dataSource = dataSource,
        snapshotCache = sharedStores.channelSettingsSnapshotCache,
        initialSharedHandoff = initialSharedHandoff,
        initialAuthorityAccepted = sharedStores.currentAuthenticatedRouteOwner(storeScope) == routeOwner,
        scope = scope,
    )
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
    val threadLinkKey = remember(params.toString()) { params.optString(THREAD_SETTINGS_LINK_KEY).takeIf(String::isNotBlank) }
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
    val homeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val membersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val scope = rememberCoroutineScope()
    val settingsStore = remember(route, routeOwner, sharedStores, storeScope, scope) {
        createChannelSettingsStore(route, routeOwner, sharedStores, storeScope, scope) { bridge.log("channel_settings_store $it") }
    }
    val settingsState by settingsStore.state.collectAsState()
    val authorityAccepted = liveOwner == routeOwner
    val channel = settingsState.channel.takeIf { authorityAccepted }
    val memberSlice = settingsState.memberSlice
    // Optimistic: a tapped switch shows its new value while the request runs.
    val pinned = settingsState.displayPinned(route.channelId.value)
    val muted = settingsState.displayMuted
    var confirm by remember(route) { mutableStateOf<ChannelSettingsConfirm?>(null) }
    // Move to section reads and writes MY sidebar through Home's store, as the sidebar does.
    val homeState by remember(homeStore) { homeStore?.state ?: kotlinx.coroutines.flow.MutableStateFlow(null) }.collectAsState()
    val homeSidebarOrder = (homeState?.content as? SharedHomeContentState.Content)?.snapshot?.sidebarOrder
    val sectionPin =
        ThreadConversationPinUiState(
            conversationId = route.channelId.value,
            pinnedKind = "channel",
            pinnedId = route.channelId.value,
            pinned = pinned,
            available = true,
        )
    val section =
        channelSettingsSectionPlacement(
            sidebarOrder = homeSidebarOrder ?: settingsState.sidebarOrder,
            channelId = route.channelId.value,
            pinned = homeSidebarOrder?.let { order -> channelSettingsSidebarPinned(order, route.channelId.value) } ?: pinned,
            joint = channel?.let(::channelSettingsIsJoint) == true,
        )
    val longMessageCollapseSetting =
        rememberRaftLongMessageCollapseConversationSetting(
            serverId = route.serverId.value,
            conversationId = route.channelId.value
        )
    // Web ChatPanel: the channel overflow offers "Stop All Agents" (SOS) to
    // joined members holding `controlAgentRuntime` (owner/admin/member).
    var viewerServerRole by remember(route) { mutableStateOf<String?>(null) }
    // Web `currentServerName`: the "{server} disconnected from #{channel}" notice after Disconnect.
    var viewerServerName by remember(route) { mutableStateOf<String?>(null) }
    // Connect your servers on a private channel: the account's servers and its role on each.
    var memberConnectAccount by remember(route) { mutableStateOf<MemberConnectAccount?>(null) }
    LaunchedEffect(authStore, route) {
        val session = authStore.readSession()
        val server = session?.servers?.firstOrNull { it.id == route.serverId.value }
        viewerServerRole = server?.role
        viewerServerName = server?.name
        memberConnectAccount = session?.memberConnectAccount(route.serverId.value)
    }
    // Web reads external_channels_v1 for the current server: the one whose copy is open.
    val externalChannels =
        rememberServerFeatureLatched(sharedStores.serverFeatureFlags, route.serverId, EXTERNAL_CHANNELS_FEATURE_FLAG_KEY)
    // Connected servers, a section of the details (web `serversSection`): its store lives here,
    // outside the lazy list, so the draft and an open confirm survive scrolling it away.
    val showsServers =
        channel != null &&
            (channelSettingsIsJoint(channel) || channelSettingsOffersPrivateMemberConnect(channel, externalChannels, memberConnectAccount))
    val connectedServers =
        rememberJointConnectedServers(
            route = route,
            routeOwner = routeOwner,
            liveOwner = liveOwner,
            sharedStores = sharedStores,
            storeScope = storeScope,
            bridge = bridge,
            externalChannels = externalChannels,
            active = showsServers,
        )
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
                    bridge.toast(
                        channelSettingsDisconnectGuestsPresentMessage(effect, settingsState.channel?.name)
                            ?: channelSettingsFailureMessage(effect, failureLabels, errorStrings)
                    )
                }
            }
            ChannelSettingsEffect.Close -> {
                confirm = null
                if (settingsState.closedWithAccessLost) {
                    sharedStores.publishChannelAccessLost(storeScope, route.channelId)
                }
                if (externalChannels && settingsState.closedBy == ChannelSettingsAction.Disconnect) {
                    channelSettingsDisconnectedToast(
                        serverName = viewerServerName ?: (homeState?.content as? SharedHomeContentState.Content)?.snapshot?.server?.name,
                        channelName = settingsState.channel?.name,
                    )?.let(bridge::toast)
                }
                val landing = channelSettingsCloseLanding(settingsState.closedBy, settingsState.channel)
                channelSettingsCloseHomeIntent(settingsState.closedBy, landing, route.channelId)?.let { intent -> homeStore?.dispatch(intent) }
                homeStore?.dispatch(SharedHomeIntent.Refresh)
                threadLinkKey?.let(ThreadSettingsPageLinks::get)?.sources?.onChannelSettingsClosed?.invoke(landing, route.channelId)
                router.closePage()
            }
            // Web ArchivedNameCollisionBanner: settings close, then the archived channel opens
            // (read-only), or restored after Unarchive.
            is ChannelSettingsEffect.OpenChannel -> {
                confirm = null
                if (effect.unarchived) homeStore?.dispatch(SharedHomeIntent.Refresh)
                val launch =
                    buildStandaloneConversationThreadLaunchPayload(
                        conversation = effect.conversation,
                        timeContext = null,
                        onWarn = bridge::logWarn,
                    )
                router.closePage()
                if (launch != null) {
                    pager.openRaftPage(THREAD_PAGE, threadLaunchPageData(launch), storeScopeOverride = storeScope)
                } else {
                    bridge.logWarn("channel_settings_archived_open_rejected reason=identity_unresolved")
                }
            }
            null -> Unit
        }
        if (settingsState.effect != null) settingsStore.dispatch(ChannelSettingsIntent.ConsumeEffect)
    }

    // Channel announcements (channel_announcement_v0): the Announcement row, for a channel that may have one.
    val announcementKey =
        rememberChannelAnnouncementKey(
            flags = sharedStores.serverFeatureFlags,
            serverId = route.serverId.value,
            channelId = route.channelId.value,
            channelType = channel?.let(::channelSettingsAnnouncementChannelType),
        )
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
                                profileErrorMessage = settingsState.profileErrorMessage,
                                profileCollision = settingsState.archivedCollisionFor(settingsStore),
                                slackBridge = settingsState.slackBridge,
                                section = section,
                                externalChannelsEnabled = externalChannels,
                                privateMemberConnect =
                                    channelSettingsOffersPrivateMemberConnect(channel, externalChannels, memberConnectAccount),
                                serverName =
                                    viewerServerName
                                        ?: (homeState?.content as? SharedHomeContentState.Content)?.snapshot?.server?.name,
                            ),
                        longMessageCollapseSetting = longMessageCollapseSetting,
                        agentStatusResolver = agentStatusResolver,
                        announcement =
                            if (announcementKey != null) {
                                @Composable {
                                    ChannelSettingsAnnouncementSection(
                                        sharedStores = sharedStores,
                                        storeScope = storeScope,
                                        key = announcementKey,
                                        channelName = channel.name,
                                        canEdit = settingsState.permissions().editMetadata && !channel.archived && authorityAccepted,
                                        active = true,
                                        // On the details' own inset, 24 from the sections around it.
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                null
                            },
                        servers = { JointConnectedServersSection(connectedServers) },
                        notificationLevelTarget =
                            RaftNotificationLevelTarget(
                                serverId = route.serverId.value,
                                scope = build.raft.app.data.RaftNotificationLevelScope.Channel,
                                id = route.channelId.value,
                                isDirectMessage = channel.kind == ConversationKind.DirectMessage,
                                muted = muted,
                                // A muted conversation keeps its Unmute even where mute is no longer offered.
                                muteSupported = channel.activityMuteSupported || muted
                            ),
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
                                onMoveSection = { moves ->
                                    if (settingsState.writeActionsEnabled && authorityAccepted) {
                                        channelSectionHomeIntents(sectionPin, moves).forEach { homeStore?.dispatch(it) }
                                    }
                                },
                                onCreateSection = { name, emoji ->
                                    if (settingsState.writeActionsEnabled && authorityAccepted) {
                                        homeStore?.dispatch(
                                            SharedHomeIntent.CreateSidebarSection(
                                                name = name,
                                                emoji = emoji,
                                                moveConversationId = route.channelId.value,
                                                insertBeforeSectionId = section?.currentSectionKey,
                                            )
                                        )
                                    }
                                },
                            ),
                    )
                settingsState.status == ChannelSettingsLoadStatus.Error ||
                    settingsState.status == ChannelSettingsLoadStatus.AuthorityRejected ->
                    ChannelSettingsMessage(
                        title = stringResource(Res.string.channel_settings_unavailable),
                        detail = stringResource(Res.string.channel_settings_unavailable_detail),
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
                leaveVariant = leaveChannelConfirmVariant(channel, memberSlice),
                hostArchiveHint = channelSettingsHostCannotDisconnect(channel, externalChannels),
                busy = settingsState.actionInFlight == activeConfirm.action,
                onDismiss = { if (settingsState.actionInFlight != activeConfirm.action) confirm = null },
                onConfirm = { settingsStore.dispatch(activeConfirm.intent) },
            )
        }

        if (showsServers) JointConnectedServersOverlays(connectedServers)

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
    val profileErrorMessage: String? = null,
    /** A rename refused for an archived channel's name (web `archivedCollision`); null hides the warning. */
    val profileCollision: ChannelInfoArchivedCollision? = null,
    /** The Slack channel pick (web ChannelSlackBridgeField); null hides it. */
    val slackBridge: ChannelSlackBridgeState? = null,
    /** Move to section (web ChannelSectionSelect, Pinned included); null keeps the Pin switch. */
    val section: ChannelSectionPlacement? = null,
    /** `external_channels_v1` for this server: the host's joint copy then has no Disconnect. */
    val externalChannelsEnabled: Boolean = false,
    /**
     * A private channel the viewer can connect one of their servers to
     * ([channelSettingsOffersPrivateMemberConnect]): it then lists Connected servers too.
     */
    val privateMemberConnect: Boolean = false,
    /** The server's name: who a public channel reaches, and who sees its name and description. */
    val serverName: String? = null,
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
    val onMoveSection: (List<ChannelSectionMove>) -> Unit = {},
    val onCreateSection: (name: String, emoji: String?) -> Unit = { _, _ -> },
)

/** The rename's archived-name warning as [ChannelInfoForm] shows it, with its store hooks. */
internal class ChannelInfoArchivedCollision(
    val collision: ArchivedNameCollision,
    val unarchiving: Boolean,
    val onUnarchive: () -> Unit,
    val onOpen: () -> Unit,
    val onClear: () -> Unit,
)

/** The warning from the settings store's state, or null when there is none. */
internal fun ChannelSettingsState.archivedCollisionFor(store: ChannelSettingsStore): ChannelInfoArchivedCollision? =
    profileCollision?.let { collision ->
        ChannelInfoArchivedCollision(
            collision = collision,
            unarchiving = collisionUnarchiving,
            onUnarchive = { store.dispatch(ChannelSettingsIntent.UnarchiveArchivedCollision) },
            onOpen = { store.dispatch(ChannelSettingsIntent.OpenArchivedCollision) },
            onClear = { store.dispatch(ChannelSettingsIntent.ClearArchivedCollision) },
        )
    }

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
internal fun channelSettingsFailureLabels(): ChannelSettingsFailureLabels =
    ChannelSettingsFailureLabels(
        update = stringResource(Res.string.thread_settings_update_failed),
        visibility = stringResource(Res.string.channel_settings_visibility_failed),
        archive = stringResource(Res.string.thread_error_archive_channel),
        unarchive = stringResource(Res.string.channel_settings_unarchive_failed),
        delete = stringResource(Res.string.thread_error_delete_channel),
        disconnect = stringResource(Res.string.joint_disconnect_failed),
        guestAccess = stringResource(Res.string.channel_settings_guest_failed),
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
 * The details' frame (raft-design channel.md, "The channel details"): on a phone a pushed page
 * whose head is Back and Details (the channel's name is the page's first line, so the head doesn't
 * repeat it); on the wide layout the first page of the profile column's details cell, beside the
 * conversation in its row, whose head is Details and Close (×), with no Expand. The panel head is 44 under a
 * mouse or trackpad and 56 with a finger ([RaftPanelBackHeader]), over the canvas.
 */
@Composable
internal fun ChannelSettingsScaffold(
    refreshing: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    RaftDesignSystemTheme {
        Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
            val title = stringResource(Res.string.channel_details_title)
            if (LocalRaftPaneBackCloses.current) {
                RaftPanelBackHeader(
                    title = title,
                    subtitle = null,
                    onBack = null,
                    topSafeArea = raftTopSafeAreaPadding(),
                    refreshing = refreshing,
                    trailingContent = {
                        RaftBackButton(onClick = onBack, label = stringResource(Res.string.common_close), glyph = RaftGlyph.X)
                    },
                )
            } else {
                RaftPanelBackHeader(
                    title = title,
                    subtitle = null,
                    onBack = onBack,
                    topSafeArea = raftTopSafeAreaPadding(),
                    refreshing = refreshing,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** web `stack-y-6`: 24 between the details' sections. */
private val ChannelDetailsSectionGap = 24.dp

/** web `--surface-inset`: the details' side margin, 16 under a mouse or trackpad, 20 with a finger. */
@Composable
private fun channelDetailsSurfaceInset(): Dp =
    if (RaftTheme.pointerInput == RaftPointerInput.Fine) 16.dp else 20.dp

/**
 * The channel details' body (web EditChannelDialog in ChannelDetailsSurface, raft-design
 * channel.md), the same on a phone and on the wide layout, in the web order: the channel's icon,
 * name, tag and description (Edit turns them into fields in place), the members card, the
 * announcement, the connected servers, Channel settings (Public or Private with Make private or
 * Make public, Visible to visitors, Slack), Your preferences (Notifications or Mute, Sidebar
 * section, Collapse long messages), then the actions as rows colored by their risk. The body sits
 * on the surface inset, `py-4`, 24 between sections.
 */
@Composable
internal fun ChannelSettingsContent(
    model: ChannelSettingsUiModel,
    longMessageCollapseSetting: RaftLongMessageCollapseConversationSetting,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    /** The channel's notification level row (design §4.3: "Notifications" next to Mute); null hides it. */
    notificationLevelTarget: RaftNotificationLevelTarget? = null,
    /** Reload the server's notification levels when the select appears (off in fixtures). */
    refreshNotificationLevels: Boolean = true,
    /** Channel announcements: the Announcement row after the members (ChannelSettingsAnnouncementSection); null hides it. */
    announcement: (@Composable () -> Unit)? = null,
    /**
     * Connected servers (web `serversSection`, after the announcement): a joint channel's, or a
     * private one offering Connect your servers ([JointConnectedServersSection]); null hides it.
     */
    servers: (@Composable () -> Unit)? = null,
    callbacks: ChannelSettingsCallbacks,
) {
    // task #177: standalone page; the last row clears the system navigation bar.
    val bottomSafeArea = raftBottomSafeAreaPadding()
    val inset = channelDetailsSurfaceInset()
    val channel = model.channel
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = inset, top = 16.dp, end = inset, bottom = 16.dp + bottomSafeArea),
        verticalArrangement = Arrangement.spacedBy(ChannelDetailsSectionGap),
    ) {
        item(key = "identity") {
            ChannelDetailsIdentitySection(model = model, callbacks = callbacks)
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
        if (announcement != null) {
            item(key = "announcement") { announcement() }
        }
        if (servers != null && (channelSettingsIsJoint(channel) || model.privateMemberConnect)) {
            item(key = "joint-connections") { servers() }
        }
        if (channelDetailsHasChannelSettings(model)) {
            item(key = "channel-settings") {
                ChannelDetailsChannelSettingsSection(model = model, callbacks = callbacks)
            }
        }
        item(key = "preferences") {
            ChannelPreferencesSection(
                // A muted conversation keeps its Unmute even where mute is no longer offered.
                muteSupported = channel.activityMuteSupported || model.muted,
                muted = model.muted,
                pinned = model.pinned,
                enabled = model.writeActionsEnabled,
                longMessageCollapseSetting = longMessageCollapseSetting,
                onToggleMute = callbacks.onToggleMute,
                onTogglePin = callbacks.onTogglePin,
                notificationLevelTargetInput = notificationLevelTarget,
                refreshNotificationLevels = refreshNotificationLevels,
                section = model.section,
                onMoveSection = callbacks.onMoveSection,
                onCreateSection = callbacks.onCreateSection,
            )
        }
        val actions = channelSettingsActionRows(model).filterNot(::channelSettingsIsVisibilityAction)
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
    }
}

/** One action of the details, in the web order ([channelSettingsActionRows]). */
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
 * Delete (Disconnect on a joint channel). An archived channel offers only Unarchive. The visibility
 * action is drawn in Channel settings' Public or Private row ([channelSettingsIsVisibilityAction]),
 * the rest as the rows at the end of the details.
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
        if (
            channelSettingsShowsDeleteAction(channel, permissions) &&
            !channelSettingsHostCannotDisconnect(channel, model.externalChannelsEnabled)
        ) {
            when {
                channelSettingsCanDisconnect(channel) -> add(ChannelSettingsActionRow.Disconnect)
                channelSettingsCanDelete(channel) -> add(ChannelSettingsActionRow.Delete)
            }
        }
    }
}

/** Make private and Make public: the Public or Private row's button, not an action at the end (raft-design channel.md). */
internal fun channelSettingsIsVisibilityAction(row: ChannelSettingsActionRow): Boolean =
    row == ChannelSettingsActionRow.MakePrivate || row == ChannelSettingsActionRow.MakePublic

/** Whether the Channel settings group has a row: the visibility row, Visible to visitors or Slack. */
internal fun channelDetailsHasChannelSettings(model: ChannelSettingsUiModel): Boolean =
    channelSettingsActionRows(model).any(::channelSettingsIsVisibilityAction) ||
        model.showGuestAccess ||
        (model.slackBridge != null && channelSettingsSlackBridgeApplies(model.channel, model.permissions))

/**
 * The top of the details, read first (web ChannelDetailsIdentity): the channel's icon, name and
 * tag, and its description folded after three lines with Show more. People who can edit the
 * channel have Edit at the end of the name's line, which turns the block into the Name and
 * Description fields in place ([ChannelInfoForm]); a save that goes through, or Cancel, turns it back.
 */
@Composable
private fun ChannelDetailsIdentitySection(model: ChannelSettingsUiModel, callbacks: ChannelSettingsCallbacks) {
    val channel = model.channel
    val permissions = model.permissions
    val canEdit = channelSettingsShowsInfo(channel, permissions)
    var editing by remember(channel.channelId) { mutableStateOf(false) }
    LaunchedEffect(model.profileStatus) {
        if (model.profileStatus == ChannelProfileUpdateStatus.Succeeded && editing) editing = false
    }
    if (editing && canEdit) {
        ChannelInfoForm(
            channel = channel,
            nameEditable = permissions.editMetadata && !channelSettingsIsAllChannel(channel),
            enabled = model.writeActionsEnabled,
            status = model.profileStatus,
            errorCode = model.profileErrorCode,
            slackBridge = null,
            onSave = callbacks.onSaveProfile,
            onClearStatus = callbacks.onClearProfileStatus,
            archivedCollision = model.profileCollision,
            errorMessage = model.profileErrorMessage,
            whoSees = channelDetailsWhoSees(channel, model.serverName),
            onCancel = { editing = false },
        )
    } else {
        ChannelDetailsIdentity(channel = channel, onEdit = if (canEdit) ({ editing = true }) else null)
    }
}

/** Web `whoSees`: who sees the name and description, under the fields. */
@Composable
private fun channelDetailsWhoSees(channel: HomeChannelRow, serverName: String?): String? =
    when {
        channelSettingsIsJoint(channel) || channel.hasExternalRelation -> stringResource(Res.string.channel_details_shown_external)
        channelSettingsIsPrivate(channel) -> stringResource(Res.string.channel_details_shown_to_members)
        !serverName.isNullOrBlank() -> stringResource(Res.string.channel_details_shown_to_server, serverName)
        else -> null
    }

/**
 * Web ChannelDetailsIdentity: the channel's icon tile (the one icon source, [raftChannelKindGlyph]),
 * its name in bold, its tag (Public, Private or Joint Channel; and Archived), then Edit at the end
 * of the line ([onEdit] null: none). The description is body text folded after three lines, with
 * Show more and Show less. A long press copies the name or the description.
 */
@Composable
private fun ChannelDetailsIdentity(channel: HomeChannelRow, onEdit: (() -> Unit)?) {
    val colors = RaftTheme.colors
    val description = channel.subtitle?.trim().takeUnless { it.isNullOrBlank() }
    val name = channel.name.removePrefix("#")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RaftIconTile(size = RaftIconTileSize.Md) {
                    RaftChannelKindIcon(
                        channel.raftChannelKindGlyph(serverPublicPageLive = false),
                        size = RaftIconTileSize.Md.icon,
                        color = raftArgbHex(LocalRaftContentColor.current),
                    )
                }
                RaftText(
                    text = name,
                    modifier = Modifier.weight(1f, fill = false).raftCopyable(name),
                    style = RaftTheme.typography.headline.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RaftBadge(text = channelSettingsStatusLabel(channel), appearance = RaftBadgeAppearance.Outline)
                if (channel.archived) {
                    RaftBadge(
                        text = stringResource(Res.string.channel_settings_status_archived),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Muted,
                    )
                }
            }
            if (onEdit != null) {
                RaftButton(
                    text = stringResource(Res.string.common_edit),
                    onClick = onEdit,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Md,
                    leadingIcon = { RaftComputersIcon(RaftLucideIconName.Pencil) },
                )
            }
        }
        if (description != null) {
            var expanded by remember(description) { mutableStateOf(false) }
            var folded by remember(description) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftText(
                    text = description,
                    modifier = Modifier.raftCopyable(description),
                    style = RaftTheme.typography.callout,
                    color = colors.foreground,
                    maxLines = if (expanded) Int.MAX_VALUE else ChannelDetailsDescriptionLines,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout ->
                        if (!expanded && folded != layout.hasVisualOverflow) folded = layout.hasVisualOverflow
                    },
                )
                if (folded || expanded) {
                    RaftTextLink(
                        text = stringResource(if (expanded) Res.string.channel_details_show_less else Res.string.channel_details_show_more),
                        onClick = { expanded = !expanded },
                        style = RaftTheme.typography.callout,
                    )
                }
            }
        }
    }
}

/** raft-design channel.md: the description folds after three lines. */
private const val ChannelDetailsDescriptionLines = 3

/** The header's channel name, shown and copied as `#name` (one `#`, whatever the stored form). */
internal fun channelSettingsHeaderName(name: String): String = "#${name.removePrefix("#")}"

@Composable
private fun channelSettingsStatusLabel(channel: HomeChannelRow): String =
    when {
        channelSettingsIsJoint(channel) -> stringResource(Res.string.channel_settings_status_joint)
        channelSettingsIsPrivate(channel) -> stringResource(Res.string.channel_settings_status_private)
        else -> stringResource(Res.string.channel_settings_status_public)
    }

/**
 * Web MembersStrip, a card (raft-design channel.md): its heading row ("Members", then "N humans ·
 * M agents" and a chevron), then up to three rows of 35dp faces under it (agents carry their live
 * activity light), a `+N` tile when they overflow and the dashed add tile, which opens Add Member
 * directly. The whole card hovers and opens the member list as one (slock #14364); in the wide
 * column the list opens in the same column, with Back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelMembersSection(
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
    RaftListRowGroup(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().raftClickable(onClick = onOpen, tier = RaftHoverTier.Subtle)) {
            RaftListRow(
                title = stringResource(Res.string.channel_settings_members),
                variant = RaftListRowVariant.Plain,
                trailing = {
                    RaftListRowValue(summary, maxWidth = 200.dp)
                    RaftListRowChevron()
                },
            )
            // A row that belongs to the heading (ListRow `attached`): no top inset, the row's 12 under it.
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
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
                                // An external participant (web `type="app"`): their image, else initials.
                                kind = RaftAvatarKind.Labeled,
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
                            contentDescription = stringResource(Res.string.thread_action_add_member),
                            enabled = !loading,
                            onClick = onAdd,
                        )
                    }
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
        return stringResource(Res.string.common_loading)
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
                if (humans == 1) stringResource(Res.string.channel_settings_members_humans_one, humans)
                else stringResource(Res.string.channel_settings_members_humans_other, humans)
            )
            add(
                if (agents == 1) stringResource(Res.string.channel_settings_members_agents_one, agents)
                else stringResource(Res.string.channel_settings_members_agents_other, agents)
            )
            if (slice.externalMembers.isNotEmpty()) {
                add(stringResource(Res.string.channel_settings_members_slack, slice.externalMembers.size))
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
        RaftMemberAvatar(
            identity = RaftMemberAvatarIdentity(member.kind, member.id.value, member.serverId?.value),
            label = member.raftAvatarName(),
            avatarSize = ChannelSettingsStripTile,
            carriedAvatarWireValue = member.avatarWireValueForAvatarSlot(),
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
        // The face's cutout is the members card under it.
        cutoutColor = RaftTheme.surfaceColor,
    ) { avatar() }
}

/**
 * The Name and Description fields Edit opens in place (web EditChannelDialog identity form): the
 * archived-name warning above the name when a rename hit one, Name, Description with who sees them
 * under it ([whoSees]), the failure banner, then Save and Cancel at the start of the line. Save
 * stays off until something changed; Cancel drops the draft and ends the edit ([onCancel]).
 */
@Composable
internal fun ChannelInfoForm(
    channel: HomeChannelRow,
    nameEditable: Boolean,
    enabled: Boolean,
    status: ChannelProfileUpdateStatus,
    errorCode: String?,
    slackBridge: ChannelSlackBridgeState?,
    onSave: (name: String, description: String, slackBridge: ChannelSlackBridgeSelection?) -> Unit,
    onClearStatus: () -> Unit,
    /** Unsaved edits. */
    onDirtyChange: (Boolean) -> Unit = {},
    /** The rename's archived-name warning (web EditChannelDialog `archivedCollision`); null hides it. */
    archivedCollision: ChannelInfoArchivedCollision? = null,
    /** The server's reason for a failed Unarchive from that warning, shown in the failure banner. */
    errorMessage: String? = null,
    /** Who sees the name and description (web `whoSees`), under the Description field; null shows none. */
    whoSees: String? = null,
    /** Cancel: the edit ends. */
    onCancel: () -> Unit = {},
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
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    val nameInvalid = trimmedName.isNotEmpty() && !trimmedName.isSubmittableChannelName()
    val nameError =
        when {
            nameInvalid -> stringResource(Res.string.channel_create_error_name_format)
            status == ChannelProfileUpdateStatus.Failed && errorCode == "channel_name_invalid" ->
                stringResource(Res.string.channel_create_error_name_format)
            else -> null
        }
    val clearStatusOnEdit: () -> Unit = { if (status != ChannelProfileUpdateStatus.Idle && !saving) onClearStatus() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Web: the warning sits above the name it is about, with Unarchive (when allowed) and Open
        // Channel; no Change Name on a rename, the field is right below.
        archivedCollision?.let { warning ->
            ArchivedNameCollisionBanner(
                archivedChannelName = warning.collision.archivedChannelName,
                canUnarchive = warning.collision.canUnarchive,
                unarchiving = warning.unarchiving,
                onUnarchive = warning.onUnarchive,
                onOpen = warning.onOpen,
                onChangeName = null,
            )
        }
        RaftField(
            label = stringResource(Res.string.thread_settings_name),
            required = true,
            error = nameError,
            enabled = enabled && nameEditable && !saving,
        ) {
            RaftTextField(
                value = name,
                keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                onValueChange = {
                    name = it
                    clearStatusOnEdit()
                    // Web `handleNameChange`: a new name drops the warning.
                    archivedCollision?.onClear?.invoke()
                },
                placeholder = stringResource(Res.string.thread_settings_name_placeholder),
            )
        }
        RaftField(
            label = stringResource(Res.string.thread_settings_description),
            description = whoSees,
            optional = true,
            enabled = enabled && !saving,
        ) {
            RaftTextarea(
                value = description,
                onValueChange = {
                    description = it
                    clearStatusOnEdit()
                },
                placeholder = stringResource(Res.string.thread_settings_description_placeholder),
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
        // Web: the warning takes the place of the error for the refused rename.
        val namedByWarning = errorCode == ARCHIVED_NAME_COLLISION && archivedCollision != null
        if (status == ChannelProfileUpdateStatus.Failed && errorCode != "channel_name_invalid" && !namedByWarning) {
            RaftBanner(
                description =
                    when (errorCode) {
                        CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE ->
                            stringResource(Res.string.channel_settings_slack_partial_failure)
                        // Web ArchivedNameCollisionBanner `onError`: the server's reason, else its own line.
                        CHANNEL_SETTINGS_ARCHIVED_UNARCHIVE_FAILED ->
                            errorMessage ?: stringResource(Res.string.channel_create_failed_unarchive)
                        else -> stringResource(Res.string.thread_settings_update_failed)
                    },
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
            )
        }
        // Web: Save, then Cancel, at the start of the line under the fields.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftButton(
                text = stringResource(Res.string.common_save),
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
                loadingLabel = stringResource(Res.string.thread_settings_saving),
            )
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = {
                    name = savedName
                    description = savedDescription
                    slackPick = savedSlack
                    clearStatusOnEdit()
                    // Web `resetDraft` drops the warning too.
                    archivedCollision?.onClear?.invoke()
                    onCancel()
                },
                variant = RaftButtonVariant.Ghost,
                enabled = !saving,
            )
        }
    }
}

/**
 * Web ChannelSlackBridgeField: "Connect to Slack" with its line, and the Slack channel select
 * ([ChannelSlackBridgeSelect]).
 */
@Composable
private fun ChannelSlackBridgeField(
    state: ChannelSlackBridgeState,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    RaftField(
        label = stringResource(Res.string.channel_settings_slack_title),
        description = stringResource(Res.string.channel_settings_slack_detail),
        enabled = enabled,
        labelTrailing = { RaftBadge(text = stringResource(Res.string.channel_bridge_provider_slack), appearance = RaftBadgeAppearance.Outline) },
    ) {
        ChannelSlackBridgeSelect(state = state, selected = selected, enabled = enabled, onSelect = onSelect)
    }
}

/**
 * The Slack channel select: "Do not connect to Slack" first, then the Slack channels of the
 * channel's visibility; one the Raft app is not in yet, or paired with another channel, is listed
 * but can't be picked.
 */
@Composable
private fun ChannelSlackBridgeSelect(
    state: ChannelSlackBridgeState,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    label: String? = null,
) {
    val notConnected = stringResource(Res.string.channel_settings_slack_not_connected)
    val options: List<ChannelSlackBridgeOption?> = listOf(null) + state.options
    val labels =
        options.associateWith { option ->
            when {
                option == null -> notConnected
                option.membershipRequired -> stringResource(Res.string.channel_settings_slack_membership_required, option.name)
                option.pairedElsewhere && option.slackChannelId != selected ->
                    stringResource(Res.string.channel_settings_slack_already_paired, option.name)
                else -> stringResource(Res.string.channel_settings_slack_channel, option.name)
            }
        }
    RaftSelect(
        value = options.firstOrNull { it?.slackChannelId == selected },
        options = options,
        onValueChange = { onSelect(it?.slackChannelId) },
        optionLabel = { labels.getValue(it) },
        enabled = enabled,
        label = label,
        optionEnabled = { option ->
            option == null || (!option.membershipRequired && (!option.pairedElsewhere || option.slackChannelId == selected))
        },
    )
}

/** Server PATCH `/channels/:id`: "Description must be a string of at most 500 characters". */
private const val ChannelSettingsDescriptionLimit = 500

/**
 * Channel settings (web EditChannelDialog `channelSettingsSection`): the same for everyone in the
 * channel, for who can manage it. Public or Private with Make private or Make public at its end
 * (it keeps its confirmation), Visible to visitors, then Slack with Connect or Change, which opens
 * a dialog of its own. Its heading carries no hint: its name says whom it reaches.
 */
@Composable
private fun ChannelDetailsChannelSettingsSection(model: ChannelSettingsUiModel, callbacks: ChannelSettingsCallbacks) {
    val channel = model.channel
    val visibility = channelSettingsActionRows(model).firstOrNull(::channelSettingsIsVisibilityAction)
    val slack = model.slackBridge?.takeIf { channelSettingsSlackBridgeApplies(channel, model.permissions) }
    var slackDialogOpen by remember(channel.channelId) { mutableStateOf(false) }
    LaunchedEffect(model.profileStatus) {
        if (model.profileStatus == ChannelProfileUpdateStatus.Succeeded && slackDialogOpen) slackDialogOpen = false
    }
    ChannelDetailsSection(
        title = stringResource(Res.string.channel_details_channel_settings),
        icon = RaftLucideIconName.SlidersHorizontal,
    ) {
        var rowAfterFirst = false
        if (visibility != null) {
            val makePublic = visibility == ChannelSettingsActionRow.MakePublic
            val serverName = model.serverName?.takeIf { it.isNotBlank() }
            RaftListRow(
                title =
                    stringResource(
                        if (makePublic) Res.string.channel_settings_status_private else Res.string.channel_settings_status_public
                    ),
                variant = RaftListRowVariant.Plain,
                description =
                    when {
                        makePublic -> stringResource(Res.string.channel_details_private_description)
                        serverName != null -> stringResource(Res.string.channel_details_public_description, serverName)
                        else -> null
                    },
                wrapDescription = true,
                trailing = {
                    RaftButton(
                        text =
                            stringResource(
                                if (makePublic) Res.string.channel_settings_make_public else Res.string.thread_settings_make_private
                            ),
                        onClick = {
                            callbacks.onConfirm(if (makePublic) ChannelSettingsConfirm.MakePublic else ChannelSettingsConfirm.MakePrivate)
                        },
                        size = RaftButtonSize.Md,
                        enabled = model.writeActionsEnabled && model.actionInFlight == null,
                    )
                },
            )
            rowAfterFirst = true
        }
        if (model.showGuestAccess) {
            // Web ChannelVisitorVisibleField: the channel on the server's public page for signed-out
            // visitors (`guestVisible`, the server's name for it before `visitorVisible`).
            ChannelPreferenceRow(
                title = stringResource(Res.string.channel_details_visitor_visible),
                detail = stringResource(Res.string.channel_details_visitor_visible_detail),
                checked = channel.guestVisible,
                enabled = model.writeActionsEnabled && model.actionInFlight != ChannelSettingsAction.GuestAccess,
                divider = rowAfterFirst,
                onToggle = { callbacks.onSetGuestVisible(!channel.guestVisible) },
            )
            rowAfterFirst = true
        }
        if (slack != null) {
            val paired = slack.options.firstOrNull { it.slackChannelId == slack.pairedSlackChannelId }
            RaftListRow(
                title = stringResource(Res.string.channel_details_slack_title),
                variant = RaftListRowVariant.Plain,
                divider = rowAfterFirst,
                description =
                    when {
                        slack.pairedSlackChannelId == null -> stringResource(Res.string.channel_details_slack_not_connected)
                        paired != null -> stringResource(Res.string.channel_details_slack_connected, paired.name)
                        else -> stringResource(Res.string.channel_details_slack_connected_unnamed)
                    },
                wrapDescription = true,
                trailing = {
                    RaftButton(
                        text =
                            stringResource(
                                if (slack.pairedSlackChannelId == null) Res.string.channel_details_slack_connect else Res.string.channel_details_slack_change
                            ),
                        onClick = { slackDialogOpen = true },
                        size = RaftButtonSize.Md,
                        enabled = model.writeActionsEnabled && model.actionInFlight == null,
                    )
                },
            )
        }
    }
    if (slack != null && slackDialogOpen) {
        ChannelSlackBridgeDialog(
            channel = channel,
            state = slack,
            saving = model.profileStatus == ChannelProfileUpdateStatus.InFlight,
            failed = model.profileStatus == ChannelProfileUpdateStatus.Failed,
            enabled = model.writeActionsEnabled,
            onDismiss = {
                if (model.profileStatus != ChannelProfileUpdateStatus.InFlight) {
                    slackDialogOpen = false
                    callbacks.onClearProfileStatus()
                }
            },
            onSubmit = { pick ->
                // Name and description unchanged: the Save sends the Slack pair alone.
                callbacks.onSaveProfile(channel.name.removePrefix("#"), channel.subtitle.orEmpty(), ChannelSlackBridgeSelection(pick))
            },
        )
    }
}

/**
 * Web ChannelSlackBridgeRow's dialog: the Slack channel, with its line, submitted by the dialog's
 * own Connect (Save when it changes a pair): connecting has a side effect, so it is a button that
 * names it, never a part of another Save (raft-design channel.md).
 */
@Composable
private fun ChannelSlackBridgeDialog(
    channel: HomeChannelRow,
    state: ChannelSlackBridgeState,
    saving: Boolean,
    failed: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String?) -> Unit,
) {
    var pick by remember(state.pairedSlackChannelId) { mutableStateOf(state.pairedSlackChannelId) }
    val title = stringResource(Res.string.channel_details_slack_dialog_title, channel.name.removePrefix("#"))
    RaftDesignSystemTheme {
        RaftDialog(
            onDismissRequest = onDismiss,
            title = title,
            description = stringResource(Res.string.channel_details_slack_dialog_lead),
            size = RaftDialogSize.Sm,
            dismissOnClickOutside = !saving,
            closeLabel = stringResource(Res.string.common_close),
            footer = {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !saving,
                )
                RaftButton(
                    text =
                        stringResource(
                            if (state.pairedSlackChannelId == null) Res.string.channel_details_slack_connect else Res.string.common_save
                        ),
                    onClick = { onSubmit(pick) },
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    enabled = enabled && pick != state.pairedSlackChannelId,
                    loading = saving,
                    loadingLabel = stringResource(Res.string.thread_settings_saving),
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChannelSlackBridgeSelect(
                    state = state,
                    selected = pick,
                    enabled = enabled && !saving,
                    onSelect = { pick = it },
                    label = stringResource(Res.string.channel_details_slack_title),
                )
                if (failed) {
                    RaftBanner(
                        description = stringResource(Res.string.channel_details_slack_failed),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                    )
                }
            }
        }
    }
}

/**
 * A group of the details (web SectionHeader + ListRowGroup, Forms "A setting's row"): the heading
 * with its 16dp icon, then 12 under it the card of rows.
 */
@Composable
private fun ChannelDetailsSection(
    title: String,
    icon: RaftLucideIconName,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftSectionEyebrow(text = title, icon = { RaftComputersIcon(icon, size = 16.dp) })
        RaftListRowGroup(Modifier.fillMaxWidth(), content = rows)
    }
}

/**
 * Your preferences (web ChannelPreferencesSection in the details): only for the person.
 * Notifications, a channel's level and its mute as one choice, All / Only what involves me / Mute
 * (slock #12231, as in Slack), or a Mute switch where the server has no levels; Sidebar section
 * (Pinned is one of its choices), or the Pin switch without the sidebar order; Collapse long
 * messages. Each row is its title and its control; only Mute keeps a line.
 */
@Composable
private fun ChannelPreferencesSection(
    muteSupported: Boolean,
    muted: Boolean,
    pinned: Boolean,
    enabled: Boolean,
    longMessageCollapseSetting: RaftLongMessageCollapseConversationSetting,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    notificationLevelTargetInput: RaftNotificationLevelTarget? = null,
    refreshNotificationLevels: Boolean = true,
    section: ChannelSectionPlacement? = null,
    onMoveSection: (List<ChannelSectionMove>) -> Unit = {},
    onCreateSection: (name: String, emoji: String?) -> Unit = { _, _ -> },
) {
    // An older server without the unified API shows the Mute switch instead.
    val notificationLevelTarget = notificationLevelTargetInput.whenLevelsSupported()
    ChannelDetailsSection(
        title = stringResource(Res.string.channel_details_your_preferences),
        icon = RaftLucideIconName.User,
    ) {
        if (notificationLevelTarget != null) {
            val title = stringResource(Res.string.notification_level_title)
            ChannelDetailsSelectRow(title = title, divider = false) { fit ->
                RaftNotificationLevelSelect(
                    target = notificationLevelTarget,
                    enabled = enabled,
                    refresh = refreshNotificationLevels,
                    label = title,
                    fit = fit,
                    onSetMuted = { next -> if (next != muted && muteSupported && enabled) onToggleMute() },
                )
            }
        } else {
            ChannelPreferenceRow(
                title = stringResource(Res.string.channel_settings_mute),
                detail = stringResource(Res.string.channel_settings_mute_detail),
                checked = muted,
                enabled = muteSupported && enabled,
                divider = false,
                onToggle = onToggleMute,
            )
        }
        if (section != null) {
            val title = stringResource(Res.string.channel_details_sidebar_section)
            ChannelDetailsSelectRow(title = title, divider = true) { fit ->
                ChannelSectionSelect(state = section, enabled = enabled, onMove = onMoveSection, onCreate = onCreateSection, label = title, fit = fit)
            }
        } else {
            ChannelPreferenceRow(
                title = stringResource(Res.string.channel_settings_pin),
                detail = stringResource(Res.string.channel_settings_pin_detail),
                checked = pinned,
                enabled = enabled,
                divider = true,
                onToggle = onTogglePin,
            )
        }
        // Device-local display preference: available without write permission on the channel.
        ChannelPreferenceRow(
            title = stringResource(Res.string.conversation_long_message_collapse),
            detail = null,
            checked = longMessageCollapseSetting.effective,
            enabled = longMessageCollapseSetting.available,
            divider = true,
            onToggle = { longMessageCollapseSetting.onSetEnabled(!longMessageCollapseSetting.effective) },
        )
    }
}

/** A setting with a switch: its title and line, the switch at the row's end, on every width. */
@Composable
private fun ChannelPreferenceRow(
    title: String,
    detail: String?,
    checked: Boolean,
    enabled: Boolean,
    divider: Boolean,
    onToggle: () -> Unit,
) {
    RaftListRow(
        title = title,
        variant = RaftListRowVariant.Plain,
        onClick = onToggle,
        enabled = enabled,
        divider = divider,
        description = detail,
        wrapDescription = true,
        titleMaxLines = 2,
        trailing = {
            RaftSwitch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled, label = title)
        },
    )
}

/** forms.md (raft-design #1204): a row this wide or wider has its select at its end. */
internal val ChannelDetailsWideRowMinWidth = 512.dp

/** A select under a narrow row's title: `max-w-sm`. */
private val ChannelDetailsUnderSelectMaxWidth = 384.dp

/**
 * Where a settings row of [rowWidth] puts its select or segmented control (Forms, "A setting's
 * row", raft-design #1204): at the row's end from 32rem of the row's own width, whatever its
 * options say; under the title in a narrower row (on a phone, and in the 400 wide details column).
 * A switch or a button stays at the end on every width.
 */
internal fun channelDetailsSelectAtRowEnd(rowWidth: Dp): Boolean = rowWidth >= ChannelDetailsWideRowMinWidth

/**
 * A setting whose control is a select (or a segmented control), laid out by its own width
 * ([channelDetailsSelectAtRowEnd]): its title (and [description]) then the control under it, at most
 * `max-w-sm` wide; or, in a row 32rem wide or wider, the control at the row's end. [control] gets
 * `fit` = at the row's end: a select there is as wide as its longest option, 11rem to 20rem
 * (raft-ui 0.95.1 `SelectTrigger fit`, [RaftSelectFitMinWidth]..[RaftSelectFitMaxWidth]), so the
 * row's end doesn't move with the chosen value.
 * Kept here, not in the design system: [RaftListRow] has no slot under its text yet.
 */
@Composable
private fun ChannelDetailsSelectRow(
    title: String,
    divider: Boolean,
    description: String? = null,
    control: @Composable (fit: Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val atEnd = channelDetailsSelectAtRowEnd(maxWidth)
        Column(Modifier.fillMaxWidth()) {
            // ListRow `plain`'s hairline, from where the text starts.
            if (divider) RaftSeparator(Modifier.padding(start = 16.dp))
            if (atEnd) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelDetailsRowText(title, description, Modifier.weight(1f))
                    Box(Modifier.widthIn(max = RaftSelectFitMaxWidth), contentAlignment = Alignment.CenterEnd) { control(true) }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChannelDetailsRowText(title, description)
                    Box(Modifier.fillMaxWidth().widthIn(max = ChannelDetailsUnderSelectMaxWidth)) { control(false) }
                }
            }
        }
    }
}

/** A settings row's text as [RaftListRow] sets it: the title 16/24 semibold, its line 14/20 muted. */
@Composable
private fun ChannelDetailsRowText(title: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(
            title,
            style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
        )
        if (description != null) {
            RaftText(description, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted)
        }
    }
}

/**
 * The actions at the end of the details (web EditChannelDialog `actionsSection`): a card of rows
 * with no heading, colored by their risk (raft-ui ListRow `tone`): Archive, Leave and Stop all
 * agents orange (they interrupt, but can be undone), Delete or Disconnect red (it can't be undone);
 * Unarchive takes nothing back, so it stays neutral. Each asks first as before (web
 * `confirmDialogs`), except Unarchive and Stop all agents (its own dialog).
 */
@Composable
internal fun ChannelActionsSection(
    actions: List<ChannelSettingsActionRow>,
    enabled: Boolean,
    actionInFlight: ChannelSettingsAction?,
    callbacks: ChannelSettingsCallbacks,
) {
    val buttonEnabled = enabled && actionInFlight == null
    RaftListRowGroup(Modifier.fillMaxWidth()) {
        actions.forEachIndexed { index, row ->
            val divider = index > 0
            when (row) {
                // Drawn in Channel settings' Public or Private row.
                ChannelSettingsActionRow.MakePrivate,
                ChannelSettingsActionRow.MakePublic -> Unit
                ChannelSettingsActionRow.Archive ->
                    ChannelActionRow(
                        stringResource(Res.string.thread_settings_archive),
                        RaftLucideIconName.Archive,
                        risk = RaftRiskTone.Warning,
                        enabled = buttonEnabled,
                        divider = divider,
                    ) { callbacks.onConfirm(ChannelSettingsConfirm.Archive) }
                ChannelSettingsActionRow.Unarchive ->
                    ChannelActionRow(
                        stringResource(
                            if (actionInFlight == ChannelSettingsAction.Unarchive) {
                                Res.string.channel_settings_unarchiving
                            } else {
                                Res.string.channel_settings_unarchive
                            }
                        ),
                        RaftLucideIconName.Archive,
                        risk = null,
                        enabled = buttonEnabled,
                        divider = divider,
                        onClick = callbacks.onUnarchive,
                    )
                ChannelSettingsActionRow.Leave ->
                    ChannelActionRow(
                        stringResource(Res.string.channel_settings_leave),
                        RaftLucideIconName.LogOut,
                        risk = RaftRiskTone.Warning,
                        enabled = buttonEnabled,
                        divider = divider,
                    ) { callbacks.onConfirm(ChannelSettingsConfirm.Leave) }
                ChannelSettingsActionRow.StopAllAgents ->
                    ChannelActionRow(
                        stringResource(Res.string.channel_sos_entry),
                        RaftLucideIconName.Square,
                        risk = RaftRiskTone.Warning,
                        enabled = true,
                        divider = divider,
                        onClick = callbacks.onStopAllAgents,
                    )
                ChannelSettingsActionRow.Delete ->
                    ChannelActionRow(
                        stringResource(Res.string.thread_settings_delete),
                        RaftLucideIconName.Trash2,
                        risk = RaftRiskTone.Danger,
                        enabled = buttonEnabled,
                        divider = divider,
                    ) { callbacks.onConfirm(ChannelSettingsConfirm.Delete) }
                ChannelSettingsActionRow.Disconnect ->
                    ChannelActionRow(
                        stringResource(Res.string.joint_disconnect_channel),
                        RaftLucideIconName.Unplug,
                        risk = RaftRiskTone.Danger,
                        enabled = buttonEnabled,
                        divider = divider,
                    ) { callbacks.onConfirm(ChannelSettingsConfirm.Disconnect) }
            }
        }
    }
}

/** One action row: its 20dp icon and its name, both in the risk's ink. */
@Composable
private fun ChannelActionRow(
    text: String,
    icon: RaftLucideIconName,
    risk: RaftRiskTone?,
    enabled: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
) {
    RaftListRow(
        title = text,
        variant = RaftListRowVariant.Plain,
        onClick = onClick,
        risk = risk,
        enabled = enabled,
        divider = divider,
        leading = { RaftComputersIcon(icon, size = 20.dp) },
    )
}

/**
 * Web EditChannelDialog `confirmDialogs` (raft-ui ConfirmDialog): the action's title, the web
 * consequence copy, Cancel and the confirm button — orange (Warning) for what can be undone,
 * red (Danger) for Delete and Disconnect. The confirm button shows the web's busy label while
 * the request runs; the dialog closes when it ends.
 */
@Composable
internal fun ChannelSettingsConfirmDialog(
    confirm: ChannelSettingsConfirm,
    channelName: String,
    leaveVariant: LeaveChannelConfirmVariant,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    /** The host's joint copy ([channelSettingsHostCannotDisconnect]): Archive says it reaches every server. */
    hostArchiveHint: Boolean = false,
) {
    if (confirm == ChannelSettingsConfirm.Leave) {
        LeaveChannelConfirmDialog(leaveVariant, channelName, busy, onDismiss, onConfirm)
        return
    }
    val title: String
    val description: String
    val confirmLabel: String
    val loadingLabel: String
    val variant: RaftButtonVariant
    when (confirm) {
        ChannelSettingsConfirm.MakePrivate -> {
            title = stringResource(Res.string.thread_settings_make_private_title)
            description = stringResource(Res.string.channel_settings_make_private_confirm, channelName)
            confirmLabel = stringResource(Res.string.thread_settings_make_private)
            loadingLabel = stringResource(Res.string.channel_settings_updating)
            variant = RaftButtonVariant.Warning
        }
        ChannelSettingsConfirm.MakePublic -> {
            title = stringResource(Res.string.channel_settings_make_public_title)
            description = stringResource(Res.string.channel_settings_make_public_confirm, channelName)
            confirmLabel = stringResource(Res.string.channel_settings_make_public)
            loadingLabel = stringResource(Res.string.channel_settings_updating)
            variant = RaftButtonVariant.Warning
        }
        ChannelSettingsConfirm.Archive -> {
            title = stringResource(Res.string.thread_settings_archive_title)
            val archiveConfirm = stringResource(Res.string.channel_settings_archive_confirm, channelName)
            val hint = stringResource(Res.string.joint_host_archive_hint)
            // Web: the hint is a second paragraph of the confirm (cindyz).
            description = if (hostArchiveHint) "$archiveConfirm\n\n$hint" else archiveConfirm
            confirmLabel = stringResource(Res.string.thread_settings_archive_confirm)
            loadingLabel = stringResource(Res.string.channel_settings_archiving)
            variant = RaftButtonVariant.Warning
        }
        // Shown by LeaveChannelConfirmDialog above (web shares it with the sidebar menu).
        ChannelSettingsConfirm.Leave -> return
        ChannelSettingsConfirm.Delete -> {
            title = stringResource(Res.string.thread_settings_delete_title)
            description = stringResource(Res.string.channel_settings_delete_confirm, channelName)
            confirmLabel = stringResource(Res.string.thread_settings_delete)
            loadingLabel = stringResource(Res.string.channel_settings_deleting)
            variant = RaftButtonVariant.Danger
        }
        ChannelSettingsConfirm.Disconnect -> {
            title = stringResource(Res.string.joint_disconnect_title)
            description = stringResource(Res.string.joint_disconnect_confirm, channelName)
            confirmLabel = stringResource(Res.string.joint_disconnect_action)
            loadingLabel = stringResource(Res.string.joint_disconnecting)
            variant = RaftButtonVariant.Danger
        }
    }
    RaftDesignSystemTheme {
        RaftDialog(
            onDismissRequest = onDismiss,
            title = title,
            description = description,
            size = RaftDialogSize.Sm,
            dismissOnClickOutside = false,
            closeLabel = stringResource(Res.string.common_close),
            footer = {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
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

/**
 * Web `channel.edit.disconnectedToast` after this server let go of a joint channel: "{server}
 * disconnected from #{channel}", said before the page closes. Null without a server name.
 */
internal fun channelSettingsDisconnectedToast(serverName: String?, channelName: String?): String? {
    val server = serverName?.trim()?.takeIf(String::isNotBlank) ?: return null
    val channel = channelName?.trim()?.removePrefix("#")?.takeIf(String::isNotBlank) ?: return null
    return Res.string.joint_disconnected_toast.format(server, channel).resolve()
}

/**
 * Web `jointDisconnectGuestRefusalMessage` (botiverse/slock #13220): the server refuses to
 * disconnect a joint channel that has Guests bound to this copy (`409 guests_present`), since
 * they would be stranded. Say so in this locale, with the next step (remove the guests, or archive
 * the channel), instead of the server's English. Null for any other failure.
 */
internal fun channelSettingsDisconnectGuestsPresentMessage(
    failure: ChannelSettingsEffect.Failure,
    channelName: String?,
): String? {
    if (failure.action != ChannelSettingsAction.Disconnect || failure.code != "guests_present") return null
    val channel = channelName?.trim()?.removePrefix("#")?.takeIf(String::isNotBlank) ?: return null
    return Res.string.joint_disconnect_guests_present.format("#$channel").resolve()
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
            text = stringResource(Res.string.common_retry),
            onClick = onRetry,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.RefreshCw) },
        )
    }
}

@Composable
private fun ChannelSettingsInvalidRouteScreen(onBack: () -> Unit) {
    ChannelSettingsScaffold(refreshing = false, onBack = onBack) {
        ChannelSettingsMessage(
            title = stringResource(Res.string.channel_settings_unavailable),
            detail = stringResource(Res.string.channel_settings_invalid_route),
            onRetry = onBack
        )
    }
}
