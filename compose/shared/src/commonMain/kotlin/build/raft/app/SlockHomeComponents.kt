package build.raft.app

import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalPanel
import build.raft.app.components.SlockSkeleton
import build.raft.app.components.SlockSkeletonVariant
import build.raft.app.data.ChannelRepository
import build.raft.app.data.DataResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.NetworkSlockAnnouncementsRemindersApi
import build.raft.app.data.NetworkSlockHomeApi
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.SharedHomeDataSource
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SlockComputersNetworkApi
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.parseSlockHome
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.model.appAvatarApiFallbackUrl
import build.raft.app.model.appAvatarWireValueOrNull
import build.raft.app.model.gravatarAvatarHttpUrlOrNull
import build.raft.app.model.gravatarHashOrNull
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.slockGravatarSessionLoadCache
import build.raft.app.resources.MR
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.module.RouterModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.coil3.AsyncImagePainter
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.extension.scrollToTop
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.draw.clipToBounds
import com.tencent.kuikly.compose.ui.draw.drawBehind
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectDragGesturesAfterLongPress
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.hoverable
import com.tencent.kuikly.compose.foundation.interaction.MutableInteractionSource
import com.tencent.kuikly.compose.foundation.interaction.collectIsHoveredAsState
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.input.pointer.positionChanged
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxWithConstraints
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
// #192 trace scope extension: reuse the markdown trace helper for avatar phase
// attribution (process-wide `expect/actual` infrastructure; one `traceMarkdownSection`
// overload set covers both `markdown.*` and `avatar.*` / future `composer.*` /
// `inbox.*` / `tabbar.*` phase names).
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.Path
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import kotlinx.coroutines.launch
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.resource.format
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SLOCK_HOME_PAGE = "compose_home"
private const val HOME_PAGE_DATA_EVENT = "slockPageData"

private val SidebarActionIconSize = 14.dp
private val SidebarActionIconBoxSize = 14.dp
private val SidebarActionRowHeight = 32.dp
private val HomeSmallShadowOffset = 2.dp
private val SidebarRowHorizontalPadding = 8.dp
private val SidebarActionLabelGap = 6.dp
private val ChannelSymbolWidth = 18.dp
private val ChannelSymbolIconSize = 14.dp
private val ChannelAvatarSize = 20.dp
private val ChannelAvatarFontSize = 11.sp
private val ChannelRowHeight = 32.dp
private const val EdgeAutoScrollStepPx = 18f
private val ChannelRowHorizontalPadding = 8.dp
private val ChannelRowShadowOffset = 2.dp
private val ChannelRowMutedIconSize = 12.dp
private val ChannelRowMutedIconGap = 4.dp
private val DirectMessageStatusDotSize = SlockStatusIndicatorTokens.InlineSize
private val CountPillMinWidth = 18.dp
private val CountPillHeight = 16.dp
private val HomeUnreadBadgeRadius = 4.dp
private val HomeUnreadBadgeTextOffsetY = 0.dp
private val HomeListHorizontalPadding = SlockSpacing.Md
// #372 React parity: the post-sign-in loading state shows skeleton rows
// under the CHANNELS / DIRECT MESSAGES section headers (4 + 3 rows in the
// React app) instead of a bare shell.
private const val HomeLoadingChannelSkeletonRows = 4
private const val HomeLoadingDirectMessageSkeletonRows = 3
private const val HomeLoadingSkeletonLineWeight = 0.6f
private val HomeLoadingSkeletonLineHeight = 12.dp
private val HomePanelShadowOffset = 4.dp
private val HomeServerSwitcherTopGap = 4.dp
private val HomeServerSwitcherRowMinHeight = 52.dp
private val HomeServerSwitcherDragHandleWidth = 32.dp
internal val HomeServerSwitcherDragHandleIconName = SlockLucideIconName.GripVertical
private const val HomeServerSwitcherMaxVisibleRows = 6
private const val HomeServerBadgeTiltDegrees = -2f
private val HomeServerBadgeHorizontalInset = 14.dp
private val HomeServerBadgeVerticalInset = 6.dp
private val HomeServerBadgeContentGap = 6.dp
private val HomeServerBadgeChevronSize = 16.dp
private val SlockSortEdgeScrollZone = 56.dp
private val SlockSortEdgeScrollStep = 10.dp
private const val SharedListMaxBeyondBoundsItemCount = 96
private const val HomeListMinBeyondBoundsItemCount = 6
private const val HomeListMaxBeyondBoundsItemCount = 24
private const val HomeListOverscanViewportFraction = 1f
private const val HomeListRepresentativeRowStepDp = 34f
private const val HomeListServerSwitchRampStep = 3
private const val HomeServerSwitcherMaxBeyondBoundsItemCount = 6

internal enum class HomeSidebarBadgeTone {
    Loud,
    Quiet
}

internal data class HomeSidebarBadgeProjection(
    val label: String?,
    val tone: HomeSidebarBadgeTone = HomeSidebarBadgeTone.Loud,
    val hasUnread: Boolean = false,
    val hasAttention: Boolean = false
)

internal data class HomeConversationBadgeInput(
    val unreadCount: Int,
    val joined: Boolean = true,
    val muted: Boolean = false,
    val mentionPierced: Boolean = false,
    val followed: Boolean = false
)

internal fun homeConversationTitleColor(
    muted: Boolean,
    joined: Boolean = true
): Color =
    when {
        muted -> SlockColor.TextMutedGray
        joined -> SlockColor.TextBlack
        else -> SlockColor.TextBlack.copy(alpha = 0.46f)
    }

internal fun homeConversationMutedIndicatorIcon(muted: Boolean): SlockLucideIconName? =
    if (muted) SlockLucideIconName.BellOff else null

internal data class HomeActivitySidebarProjection(
    val unreadLabel: String?,
    val totalLabel: String?
)

@Page(SLOCK_HOME_PAGE, supportInLocal = true)
internal class ComposeSlockHomePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockHomePageContent(slockPageVisibleResumeTick) }
    }
}

@Serializable
data class SlockServerSwitcherItem(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val avatarUrl: String? = null,
    val unreadCount: Int = 0
)

@Serializable
data class SlockHomeConversationItem(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val channelType: String? = null,
    val unreadCount: Int = 0,
    val lastMessageAt: String? = null,
    val createdAt: String? = null,
    val description: String? = null,
    val avatarLabel: String = name.take(2).uppercase(),
    val peerType: String? = null,
    val presenceMemberId: String? = null,
    val hasDraft: Boolean = false,
    val joined: Boolean = true,
    val archived: Boolean = false,
    val activityMuted: Boolean = false,
    val activityMuteSupported: Boolean = false
)

@Serializable
data class SlockHomeNotificationItem(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val actionLabel: String = "",
    val primaryActionActive: Boolean = false,
    val targetId: String? = null,
    val kind: SlockHomeNotificationKind = SlockHomeNotificationKind.Warning,
    val icon: SlockHomeNotificationIcon = SlockHomeNotificationIcon.Default
)

@Serializable
enum class SlockHomeNotificationKind { Warning, Info, Error }

@Serializable
enum class SlockHomeNotificationIcon { Default, WifiOff, CircleArrowUp, Info }

data class SlockHomeInlineError(
    val title: String,
    val detail: String,
    val code: String,
    val actionLabel: String = "",
)

@Serializable
enum class SlockPresence { Online, Offline, Unknown }

enum class SlockHomeConversationSection {
    Channel,
    JointChannel,
    DirectMessage,
    Pinned
}

enum class SlockHomeSidebarTopSelection {
    Search,
    Activity,
    Saved
}

@Serializable
enum class SlockSidebarSortMode(val wireValue: String) {
    Manual("manual"),
    Recent("recent"),
    Az("az");

    companion object {
        fun fromWireValue(value: String?): SlockSidebarSortMode =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Manual
    }
}

@Serializable
data class SlockHomeShellLabels(
    val search: String = "Search",
    val inbox: String = "Activity",
    val followedThreads: String = "Followed threads",
    val saved: String = "Saved",
    val channels: String = "Channels",
    val jointChannels: String = "Joint Channels",
    val directMessages: String = "Direct Messages",
    val pinned: String = "Pinned",
    val pin: String = "Pin",
    val unpin: String = "Unpin",
    val markAsRead: String = "Mark as Read",
    val markAsUnread: String = "Mark as Unread",
    val mute: String = "Mute",
    val unmute: String = "Unmute",
    val closeChat: String = "Close Chat",
    val archive: String = "Archive",
    val sortChannels: String = "Sort",
    val saveSort: String = "Save sort",
    val sortManual: String = "Manual",
    val sortRecent: String = "Recent",
    val sortAz: String = "A-Z",
    val addChannel: String = "Add channel",
    val noServersAvailable: String = "No servers available",
    val joinChineseCommunityServer: String = "Join Chinese Community",
    val joinCommunityServer: String = "Join community server",
    val switchOrCreateServer: String = "Switch or create server"
) {
    companion object {
        @Composable
        fun localized(): SlockHomeShellLabels = SlockHomeShellLabels(
            search = stringResource(MR.strings.home_nav_search),
            inbox = stringResource(MR.strings.home_nav_activity),
            followedThreads = stringResource(MR.strings.home_nav_followed_threads),
            saved = stringResource(MR.strings.home_nav_saved),
            channels = stringResource(MR.strings.home_section_channels),
            jointChannels = stringResource(MR.strings.home_section_joint_channels),
            directMessages = stringResource(MR.strings.home_section_direct_messages),
            pinned = stringResource(MR.strings.home_section_pinned),
            pin = stringResource(MR.strings.home_action_pin),
            unpin = stringResource(MR.strings.home_action_unpin),
            markAsRead = stringResource(MR.strings.home_action_mark_as_read),
            markAsUnread = stringResource(MR.strings.home_action_mark_as_unread),
            mute = stringResource(MR.strings.home_action_mute),
            unmute = stringResource(MR.strings.home_action_unmute),
            closeChat = stringResource(MR.strings.home_action_close_chat),
            archive = stringResource(MR.strings.home_action_archive),
            sortChannels = stringResource(MR.strings.home_action_sort),
            saveSort = stringResource(MR.strings.home_action_save_sort),
            sortManual = stringResource(MR.strings.home_sort_manual),
            sortRecent = stringResource(MR.strings.home_sort_recent),
            sortAz = stringResource(MR.strings.home_sort_az),
            addChannel = stringResource(MR.strings.home_action_add_channel),
            noServersAvailable = stringResource(MR.strings.home_state_no_servers),
            joinChineseCommunityServer = stringResource(MR.strings.home_server_switcher_join_chinese_community),
            joinCommunityServer = stringResource(MR.strings.home_server_switcher_join_community),
            switchOrCreateServer = stringResource(MR.strings.home_server_switcher_switch_or_create)
        )
    }
}

@Serializable
internal data class SlockHomePayload(
    val instanceId: String = "",
    val currentServerName: String = "Raft Android",
    val currentServerId: String = "",
    val servers: List<SlockServerSwitcherItem> = emptyList(),
    val channels: List<SlockHomeConversationItem> = emptyList(),
    val directMessages: List<SlockHomeConversationItem> = emptyList(),
    val selectedChannelId: String? = null,
    val inboxUnreadCount: Int = 0,
    val savedCount: Int = 0,
    val notifications: List<SlockHomeNotificationItem> = emptyList(),
    val serverSwitcherExpanded: Boolean = false,
    val contentRefreshing: Boolean = false,
    val contentRefreshEnabled: Boolean = true,
    val applyTopSafeArea: Boolean = true,
    val activitySelected: Boolean = false,
    val channelOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
    val dmOrder: List<String> = emptyList(),
    val dmSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedAgentIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val pinned: List<SettingsServerSidebarPinnedItem> = emptyList(),
    val pinnedSortMode: String = "manual",
    val hiddenDmIds: List<String> = emptyList(),
    val canArchiveChannels: Boolean = false,
    val bottomContentPaddingDp: Float = 24f,
    val labels: SlockHomeShellLabels = SlockHomeShellLabels()
)

private enum class ChannelRowInteractionState { Default, Selected, Hovered, Focused, Pressed }

private val homeConversationOpenBarrier = HomeConversationOpenBarrier()

private const val HomePullToRefreshEnabled = true

internal fun homePullToRefreshEnabled(
    contentRefreshEnabled: Boolean,
    platformSupportsPullToRefresh: Boolean = slockPlatformSupportsKuiklyPullToRefreshItem()
): Boolean =
    contentRefreshEnabled && HomePullToRefreshEnabled && platformSupportsPullToRefresh

internal fun homeHeaderRefreshIndicatorVisible(contentRefreshing: Boolean, pullRefreshing: Boolean): Boolean =
    contentRefreshing || pullRefreshing

@Composable
private fun SlockHomePageContent(routeVisibleTick: Int = 0) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    var payloadText by remember { mutableStateOf(pager.pageData.params.optString("payload")) }
    var instanceId by remember { mutableStateOf(pager.pageData.params.optString("instanceId")) }
    DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != HOME_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let {
                        payloadText = it
                    }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let {
                        instanceId = it
                    }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payloadTrace = rememberHomePayloadTrace(
        payloadText,
        instanceId
    )
    val payload = payloadTrace.payload
    LaunchedEffect(payloadText, instanceId) {
        bridge.log(
            "home_launch_payload_parse source=pageData result=${payloadTrace.result} " +
                "responseBytes=${payloadTrace.responseBytes} durationMs=${payloadTrace.durationMs} dispatcher=caller"
        )
    }
    LaunchedEffect(payload.instanceId, payload.channels.size, payload.directMessages.size) {
        bridge.log("home_render instanceId=${payload.instanceId} channels=${payload.channels.size} dms=${payload.directMessages.size}")
    }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val standaloneHomeStore =
        remember(sharedStores) {
            val homeDataSource =
                SharedHomeDataSource(
                    sessionStore = sharedStores.authStore,
                    api = NetworkSlockHomeApi(sharedStores.networkClient),
                    announcementsApi = NetworkSlockAnnouncementsRemindersApi(sharedStores.networkClient),
                    computersApi = SlockComputersNetworkApi(sharedStores.networkClient),
                    serverMembersStore = sharedStores.serverMembersStore,
                    parseHome = { networkPayload, session ->
                        parseSlockHome(networkPayload, session) { message -> bridge.log(message) }
                    },
                    diagnosticsLogger = { message -> bridge.log("standalone_home $message") }
                )
            sharedStores.getOrCreateHomeStore(
                dataSource = homeDataSource,
                diagnosticsLogger = { message -> bridge.log("standalone_home $message") }
            )
        }
    LaunchedEffect(routeVisibleTick, payload.instanceId) {
        if (
            activityPageDidAppearShouldRefresh(routeVisibleTick) &&
            payload.instanceId.isNotBlank()
        ) {
            standaloneHomeStore.dispatch(SharedHomeIntent.ForegroundRefresh)
        }
    }
    val agentStatusResolver =
        remember(sharedStores.serverMembersStore, payload.currentServerId) {
            slockAgentStatusResolver(sharedStores.serverMembersStore, payload.currentServerId)
        }
    val memberAvatarResolver =
        remember(sharedStores.serverMembersStore, payload.currentServerId) {
            slockMemberAvatarResolver(sharedStores.serverMembersStore, payload.currentServerId)
        }
    val serverMembersState by sharedStores.serverMembersStore.state.collectAsState()
    val pinnedMemberProfileResolver: (String) -> SlockHomePinnedMemberItem? =
        remember(serverMembersState, payload.currentServerId) {
            { memberId ->
                serverMembersState
                    .memberProfile(ServerId(payload.currentServerId), memberId)
                    ?.toSlockHomePinnedMemberItem()
            }
        }
    val standaloneChannelRepository =
        remember(sharedStores) {
            RealChannelRepository(
                sessionStore = sharedStores.authStore,
                networkClient = sharedStores.networkClient,
                warningLogger = { message -> bridge.log("standalone_home_channel_repository $message") }
            )
        }
    val standaloneScope = rememberCoroutineScope()
    val unableToOpenDirectMessage = stringResource(MR.strings.home_error_open_direct_message)
    val failedToOpenDirectMessage = stringResource(MR.strings.home_error_open_direct_message_failed)
    val noDirectMessagePermission = stringResource(MR.strings.home_error_open_direct_message_permission)
    LaunchedEffect(sharedStores.serverMembersStore, payload.currentServerId) {
        val serverId = payload.currentServerId.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        sharedStores.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
    }
    fun unsupportedStandaloneHomeAction(action: String, itemId: String? = null) {
        bridge.log("standalone_home_unsupported_action action=$action itemId=${itemId.orEmpty()}")
    }
    SlockStructuralHomeShell(
        currentServerName = payload.currentServerName,
        currentServerId = payload.currentServerId,
        servers = payload.servers,
        channels = payload.channels,
        directMessages = payload.directMessages,
        selectedChannelId = payload.selectedChannelId,
        inboxUnreadCount = payload.inboxUnreadCount,
        savedCount = payload.savedCount,
        notifications = payload.notifications,
        contentRefreshing = payload.contentRefreshing,
        contentRefreshEnabled = payload.contentRefreshEnabled,
        serverSwitcherExpanded = payload.serverSwitcherExpanded,
        applyTopSafeArea = payload.applyTopSafeArea,
        activitySelected = payload.activitySelected,
        channelOrder = payload.channelOrder,
        channelSortMode = payload.channelSortMode,
        dmOrder = payload.dmOrder,
        dmSortMode = payload.dmSortMode,
        pinnedChannelIds = payload.pinnedChannelIds,
        pinnedAgentIds = payload.pinnedAgentIds,
        pinnedOrder = payload.pinnedOrder,
        pinnedItems = payload.pinned,
        pinnedSortMode = payload.pinnedSortMode,
        hiddenDmIds = payload.hiddenDmIds,
        canArchiveChannels = payload.canArchiveChannels,
        showBottomBar = false,
        bottomContentPadding = payload.bottomContentPaddingDp.dp,
        outerPadding = 0.dp,
        labels = SlockHomeShellLabels.localized(),
        onServerBadgeClick = { unsupportedStandaloneHomeAction("toggleServerSwitcher") },
        onServerSwitcherDismiss = { unsupportedStandaloneHomeAction("dismissServerSwitcher") },
        onServerSelected = { unsupportedStandaloneHomeAction("selectServer", it) },
        onServerOrderChange = { order -> standaloneHomeStore.dispatch(SharedHomeIntent.ReorderServers(order)) },
        onCreateServerClick = { unsupportedStandaloneHomeAction("createServer") },
        onCreateChannelClick = { unsupportedStandaloneHomeAction("createChannel") },
        onInboxClick = { unsupportedStandaloneHomeAction("openInbox") },
        onFollowedThreadsClick = { unsupportedStandaloneHomeAction("openFollowedThreads") },
        onSavedClick = { unsupportedStandaloneHomeAction("openSaved") },
        onSearchClick = { unsupportedStandaloneHomeAction("openSearch") },
        onNotificationView = { unsupportedStandaloneHomeAction("viewNotification", it.id) },
        onNotificationDismiss = { unsupportedStandaloneHomeAction("dismissNotification", it.id) },
        onRefresh = { standaloneHomeStore.dispatch(SharedHomeIntent.Refresh) },
        onChannelSortModeChange = { unsupportedStandaloneHomeAction("setChannelSortMode", it.wireValue) },
        onSidebarOrderChange = { target, order -> standaloneHomeStore.dispatch(target.toReorderSidebarIntent(order)) },
        onDmSortModeChange = { unsupportedStandaloneHomeAction("setDmSortMode", it.wireValue) },
        onPinnedSortModeChange = { unsupportedStandaloneHomeAction("setPinnedSortMode", it.wireValue) },
        onChannelClick = { conversationId -> openThreadPageFromConversationId(pager, payload, conversationId) },
        onPinnedMemberClick = { identity ->
            standaloneScope.launch {
                when (val result = standaloneChannelRepository.openPinnedMemberDirectMessage(identity)) {
                    is DataResult.Content -> {
                        standaloneHomeStore.dispatch(SharedHomeIntent.Refresh)
                        pager.openThreadPageFromConversation(
                            result.data.conversation,
                            "${payload.instanceId}:${identity.kind.memberKindWireValue()}:${identity.memberId.value}"
                        )
                    }
                    is DataResult.Empty -> bridge.toast(unableToOpenDirectMessage)
                    is DataResult.Error -> bridge.toast("$failedToOpenDirectMessage: ${result.error.code}")
                    is DataResult.NoPermission -> bridge.toast(noDirectMessagePermission)
                }
            }
        },
        onChannelLongPress = { _, _ -> },
        onPinToggle = { conversationId ->
            unsupportedStandaloneHomeAction("togglePinned", conversationId)
        },
        onReadToggle = { conversationId, markUnread ->
            unsupportedStandaloneHomeAction(if (markUnread) "markUnread" else "markRead", conversationId)
        },
        onChannelActivityMuteToggle = { conversationId, muted ->
            standaloneHomeStore.dispatch(SharedHomeIntent.SetChannelActivityMuted(conversationId, muted))
        },
        onCloseDirectMessage = { conversationId ->
            unsupportedStandaloneHomeAction("closeDm", conversationId)
        },
        onArchiveChannel = { conversationId ->
            unsupportedStandaloneHomeAction("archiveChannel", conversationId)
        },
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        pinnedMemberProfileResolver = pinnedMemberProfileResolver,
        diagnosticsLogger = bridge::log
    )
}

/**
 * #228: open the thread page for a conversation that the sidebar / pinned list
 * references by id. Replaces the prior host-bridge action path
 * which went through the host bridge and silently failed to preserve surfaceKind.
 * Direct scoped navigation to `compose_thread` keeps surfaceKind intact
 * so the thread page's `#226` showMembers/showSettings guards and `#227` header label
 * both see the correct kind (Channel / DirectMessage / ReplyThread).
 */
private fun openThreadPageFromConversationId(
    pager: Pager,
    payload: SlockHomePayload,
    conversationId: String
) {
    val launchPayload =
        buildHomeConversationThreadLaunchPayload(payload, conversationId)
            ?: return
    if (!homeConversationOpenBarrier.tryAcquire(launchPayload.instanceId)) return
    pager.openSlockPage(THREAD_PAGE, threadLaunchPageData(launchPayload))
}

private fun Pager.openThreadPageFromConversation(
    conversation: HomeDirectMessageRow,
    instanceIdOverride: String
) {
    val launchPayload =
        buildStandaloneConversationThreadLaunchPayload(
            conversation = conversation.toThreadLaunchConversationIdentity(),
            timeContext = null,
            instanceIdOverride = instanceIdOverride
        ) ?: return
    openSlockPage(THREAD_PAGE, threadLaunchPageData(launchPayload))
}

internal suspend fun ChannelRepository.openPinnedMemberDirectMessage(
    identity: MemberDetailIdentity
): DataResult<OpenDirectMessageResult> =
    when (identity.kind) {
        MemberKind.Agent -> openAgentDirectMessage(identity.memberId)
        MemberKind.Human -> openUserDirectMessage(identity.memberId)
        MemberKind.System -> DataResult.Empty(EmptyReason.NoChannels)
    }

internal class HomeConversationOpenBarrier(
    private val windowMillis: Long = 800L,
    private val nowMillis: () -> Long = { defaultNowMillis() }
) {
    private val lastOpenedAtByKey = mutableMapOf<String, Long>()

    fun tryAcquire(key: String): Boolean {
        val cleanKey = key.trim()
        if (cleanKey.isBlank()) return false
        val now = nowMillis()
        val lastOpenedAt = lastOpenedAtByKey[cleanKey]
        if (lastOpenedAt != null && now >= lastOpenedAt && now - lastOpenedAt < windowMillis) {
            return false
        }
        pruneExpired(now)
        lastOpenedAtByKey[cleanKey] = now
        return true
    }

    private fun pruneExpired(now: Long) {
        val iterator = lastOpenedAtByKey.iterator()
        while (iterator.hasNext()) {
            val openedAt = iterator.next().value
            if (now < openedAt || now - openedAt >= windowMillis) {
                iterator.remove()
            }
        }
    }
}

internal fun buildHomeConversationThreadLaunchPayload(
    payload: SlockHomePayload,
    conversationId: String
): WireThreadLaunchPayload? {
    val conversation =
        payload.channels.firstOrNull { it.id == conversationId }
            ?: payload.directMessages.firstOrNull { it.id == conversationId }
            ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = conversation.toThreadLaunchConversationIdentity() ?: return null,
        timeContext = null,
        instanceIdOverride = "${payload.instanceId}:$conversationId"
    )
}

private fun SlockHomeConversationItem.toThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity? {
    val cleanId = id.takeIf { it.isNotBlank() } ?: return null
    val conversationKind = parseConversationKindOrNull(kind) ?: return null
    val threadId = ThreadId(cleanId)
    val channelId = ChannelId(cleanId)
    return ThreadLaunchConversationIdentity(
        threadId = threadId,
        channelId = channelId,
        channelType =
            when (conversationKind) {
                ConversationKind.Channel -> channelType?.takeIf { it.isNotBlank() } ?: kind
                ConversationKind.Joint -> conversationKind.toWireChannelKindOrNull()
                else -> null
            },
        threadStreamChannelIdHint =
            if (conversationKind == ConversationKind.Thread) {
                channelId
            } else {
                null
            },
        kind = conversationKind,
        dmPeerIdentity =
            if (conversationKind == ConversationKind.DirectMessage) {
                memberDetailIdentityFromWire(
                    memberId = presenceMemberId,
                    kindWireValue = peerType,
                    source = MemberDetailIdentitySource.DirectoryDmPeer
                )
            } else {
                null
            }
    )
}

@Composable
internal fun HomeTitlebarVisualScreen() {
    Box(modifier = Modifier.width(342.dp).height(110.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp)) {
        HomeTitlebar(
            serverName = "Raft Design",
            hasOtherServerUnread = true,
            notifications = visualHomeNotifications(),
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onNotificationToggle = {}
        )
    }
}

@Composable
internal fun HomeTitlebarResizeVisualScreen() {
    var useLongServerName by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        useLongServerName = true
        delay(300)
        useLongServerName = false
        delay(300)
        useLongServerName = true
    }
    Box(modifier = Modifier.width(342.dp).height(110.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp)) {
        HomeTitlebar(
            serverName = if (useLongServerName) "Raft Android Workspace" else "Raft",
            hasOtherServerUnread = true,
            notifications = emptyList(),
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = { useLongServerName = !useLongServerName },
            onNotificationToggle = {}
        )
    }
}

@Composable
internal fun HomeNotificationCenterVisualScreen() {
    Box(modifier = Modifier.width(342.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp).padding(16.dp)) {
        HomeNotificationPopover(
            notifications = visualHomeNotifications(),
            onView = {},
            onDismiss = {},
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Composable
internal fun HomeAppUpdateBannerVisualScreen() {
    Column(modifier = Modifier.width(390.dp).height(844.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp)) {
        HomeTitlebar(
            serverName = "Raft Design",
            hasOtherServerUnread = false,
            notifications = emptyList(),
            refreshing = false,
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onNotificationToggle = {}
        )
        HomeAppUpdateBanner(
            notification =
                SlockHomeNotificationItem(
                    id = APP_UPDATE_HOME_NOTIFICATION_ID,
                    title = "Raft 1.6.0 is available",
                    body = "See what's new, then download and install the update.",
                    actionLabel = "View",
                    kind = SlockHomeNotificationKind.Info,
                    icon = SlockHomeNotificationIcon.CircleArrowUp
                ),
            onClick = {}
        )
        Box(modifier = Modifier.fillMaxSize().background(SlockColor.AppBgLightBlue).padding(16.dp)) {
            Text(
                text = "HOME",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
    }
}

private fun visualHomeNotifications(): List<SlockHomeNotificationItem> =
    listOf(
        SlockHomeNotificationItem(
            id = "visual-agent-offline",
            title = "Android-Developer lost connection",
            body = "Last heartbeat was 4 minutes ago.",
            actionLabel = "View",
            kind = SlockHomeNotificationKind.Warning
        ),
        SlockHomeNotificationItem(
            id = "visual-release-blocker",
            title = "Release gate needs review",
            body = "Two visual cases changed in the latest run.",
            actionLabel = "Open",
            kind = SlockHomeNotificationKind.Info
        )
    )

@Composable
private fun rememberHomePayloadTrace(payload: String, instanceId: String): HomePayloadParseTrace =
    remember(payload, instanceId) {
        val startedAt = TimeSource.Monotonic.markNow()
        val parsed = runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(SlockHomePayload.serializer(), payload)
                .let { if (it.instanceId.isBlank()) it.copy(instanceId = instanceId) else it }
        }
        HomePayloadParseTrace(
            payload = parsed.getOrElse { SlockHomePayload(instanceId = instanceId) },
            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
            responseBytes = payload.encodeToByteArray().size,
            result = if (parsed.isSuccess) "success" else "failure"
        )
    }

private data class HomePayloadParseTrace(
    val payload: SlockHomePayload,
    val durationMs: Long,
    val responseBytes: Int,
    val result: String
)

@Composable
internal fun SlockStructuralHomeShell(
    modifier: Modifier = Modifier,
    currentServerName: String = "Raft Android",
    currentServerId: String = "slock-android",
    servers: List<SlockServerSwitcherItem> = emptyList(),
    channels: List<SlockHomeConversationItem> = emptyList(),
    directMessages: List<SlockHomeConversationItem> = emptyList(),
    selectedChannelId: String? = null,
    selectedConversationSection: SlockHomeConversationSection? = null,
    inboxUnreadCount: Int = 0,
    inboxTotalCount: Int = 0,
    savedCount: Int = 0,
    notifications: List<SlockHomeNotificationItem> = emptyList(),
    inlineError: SlockHomeInlineError? = null,
    contentRefreshing: Boolean = false,
    contentRefreshEnabled: Boolean = true,
    serverSwitcherExpanded: Boolean = false,
    pendingServerId: String? = null,
    prefersChineseCommunity: Boolean = false,
    applyTopSafeArea: Boolean = true,
    activitySelected: Boolean = false,
    searchSelected: Boolean = false,
    savedSelected: Boolean = false,
    channelOrder: List<String> = emptyList(),
    channelSortMode: String = "manual",
    dmOrder: List<String> = emptyList(),
    dmSortMode: String = "manual",
    pinnedChannelIds: List<String> = emptyList(),
    pinnedAgentIds: List<String> = emptyList(),
    pinnedOrder: List<String> = emptyList(),
    pinnedItems: List<SettingsServerSidebarPinnedItem> = emptyList(),
    pinnedSortMode: String = "manual",
    hiddenDmIds: List<String> = emptyList(),
    canArchiveChannels: Boolean = false,
    // #372 React parity: while home content is loading (session known,
    // channels / directMessages still empty) render static skeleton rows
    // under the CHANNELS and DIRECT MESSAGES headers instead of a bare shell.
    showLoadingSkeletons: Boolean = false,
    selectedTabKey: String = "home",
    showBottomBar: Boolean = true,
    externalFocusLossDismissEpoch: Int = 0,
    bottomContentPadding: Dp = if (showBottomBar) SlockSize.BottomBarHeight + SlockSpacing.Md else SlockSpacing.Lg,
    outerPadding: Dp = SlockSpacing.Md,
    labels: SlockHomeShellLabels = SlockHomeShellLabels(),
    onServerBadgeClick: () -> Unit = {},
    onServerSwitcherDismiss: () -> Unit = {},
    onServerSelected: (String) -> Unit = {},
    onServerOrderChange: (List<String>) -> Unit = {},
    onCreateServerClick: () -> Unit = {},
    onCreateChannelClick: () -> Unit = {},
    onTabSelected: (String) -> Unit = {},
    onInboxClick: () -> Unit = {},
    onFollowedThreadsClick: () -> Unit = {},
    onSavedClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationView: (SlockHomeNotificationItem) -> Unit = {},
    onNotificationDismiss: (SlockHomeNotificationItem) -> Unit = {},
    onInlineErrorAction: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onChannelSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    onSidebarOrderChange: (target: SlockSidebarSortTarget, order: List<String>) -> Unit = { _, _ -> },
    onDmSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    onPinnedSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    onChannelClick: (String) -> Unit = {},
    onPinnedMemberClick: (MemberDetailIdentity) -> Unit = {},
    onChannelLongPress: (String, Offset) -> Unit = { _, _ -> },
    onPinToggle: (String) -> Unit = {},
    onReadToggle: (conversationId: String, markUnread: Boolean) -> Unit = { _, _ -> },
    onChannelActivityMuteToggle: (conversationId: String, muted: Boolean) -> Unit = { _, _ -> },
    onCloseDirectMessage: (String) -> Unit = {},
    onArchiveChannel: (String) -> Unit = {},
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    pinnedMemberProfileResolver: (String) -> SlockHomePinnedMemberItem? = { null },
    diagnosticsLogger: (String) -> Unit = {},
    onPinMenuDismiss: () -> Unit = {},
    scrollToTopRequest: Int = 0,
    listPositionServerId: String = currentServerId,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (String?, AppShellLazyListPosition) -> Unit = { _, _ -> }
) {
    var channelsExpanded by remember { mutableStateOf(true) }
    var jointChannelsExpanded by remember { mutableStateOf(true) }
    var directMessagesExpanded by remember { mutableStateOf(true) }
    var pinnedExpanded by remember(currentServerId) { mutableStateOf(true) }
    // #211 review (Cindy): single source of truth for which row is
    // currently being pressed. Set by the row's onPress callback, cleared
    // by onPressEnd (after tryAwaitRelease returns) so the visual only
    // spans the actual pointer-down window. Never overrides a real
    // Selected highlight — the row picks Pressed only when not Selected.
    // #211 React parity (revised): each rendered copy of a pinned
    // conversation (pinned + original section) needs its own press
    // key, otherwise pressing the pinned copy would also press the
    // original copy. Row key format is `<section>:<conversationId>`.
    var pressedRowKey by remember(currentServerId) { mutableStateOf<String?>(null) }
    var channelSortModeState by remember(currentServerId) { mutableStateOf(SlockSidebarSortMode.fromWireValue(channelSortMode)) }
    var directMessageSortMode by remember(currentServerId) { mutableStateOf(SlockSidebarSortMode.fromWireValue(dmSortMode)) }
    var pinnedSortModeState by remember(currentServerId) { mutableStateOf(SlockSidebarSortMode.fromWireValue(pinnedSortMode)) }
    var activeSortMenu by remember(currentServerId) { mutableStateOf<SlockHomeSortSection?>(null) }
    var sortMenuAnchor by remember(currentServerId) { mutableStateOf(Offset.Zero) }
    var activePinMenuConversationId by remember(currentServerId) { mutableStateOf<String?>(null) }
    var activePinnedMemberMenuKey by remember(currentServerId) { mutableStateOf<String?>(null) }
    var pinMenuAnchor by remember(currentServerId) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(currentServerId, channelSortMode, dmSortMode, pinnedSortMode) {
        channelSortModeState = SlockSidebarSortMode.fromWireValue(channelSortMode)
        directMessageSortMode = SlockSidebarSortMode.fromWireValue(dmSortMode)
        pinnedSortModeState = SlockSidebarSortMode.fromWireValue(pinnedSortMode)
        activeSortMenu = null
        activePinnedMemberMenuKey = null
    }
    LaunchedEffect(currentServerId, channels, directMessages) {
        // If the active row menu target was removed, close the menu.
        val activeId = activePinMenuConversationId
        if (activeId != null && channels.none { it.id == activeId } && directMessages.none { it.id == activeId }) {
            activePinMenuConversationId = null
        }
    }
    LaunchedEffect(currentServerId, directMessages) {
        val sample =
            directMessages
                .take(6)
                .joinToString(separator = ";") { dm ->
                    "id=${dm.id.take(36)}:" +
                        "peer=${dm.peerType.orEmpty()}:" +
                        "member=${dm.presenceMemberId.orEmpty()}"
                }
        diagnosticsLogger(
            "shared_home_dm_status_rows serverId=$currentServerId " +
                "count=${directMessages.size} agentRows=${directMessages.count { it.peerType?.equals("agent", ignoreCase = true) == true }} " +
                "withMemberId=${directMessages.count { !it.presenceMemberId.isNullOrBlank() }} sample=$sample"
        )
    }
    // #211 React parity (revised by @artin 23:38): pinned rows are
    // shown alongside the original section copy, not deduped. The user
    // sees both, can click either, and either copy is independently
    // pressed / selected. Dedup still applies *inside* the pinned
    // section itself (handled by `pinnedItemsForSidebar`).
    @Suppress("UNUSED_VARIABLE")
    val pinnedIdSet =
        remember(directMessages, pinnedChannelIds, pinnedAgentIds, pinnedItems) {
            buildSet {
                addAll(pinnedChannelIds)
                addAll(pinnedAgentIds)
                pinnedItems.forEach { item ->
                    when (item.kind.trim().lowercase()) {
                        "channel" -> add(item.id)
                        "agent", "human" -> {
                            add(item.id)
                            directMessages
                                .firstOrNull { dm -> dm.presenceMemberId == item.id }
                                ?.let { add(it.id) }
                        }
                    }
                }
                val pinnedAgentIdSet = pinnedAgentIds.toSet()
                directMessages.forEach { dm ->
                    if (dm.peerType.equals("agent", ignoreCase = true) && dm.presenceMemberId in pinnedAgentIdSet) {
                        add(dm.id)
                    }
                }
            }
        }
    var sortOverlaySection by remember(currentServerId) { mutableStateOf<SlockHomeSortSection?>(null) }
    var activeMenuSection by remember(currentServerId) { mutableStateOf<SlockHomeSortSection?>(null) }
    val allChannelId = remember(channels) { channels.firstOrNull { it.kind != JointChannelKindWireValue && it.name == "all" }?.id }
    val baseDisplayedChannels = remember(channels, channelOrder, channelSortModeState) {
        val regularChannels = channels.filter { it.kind != JointChannelKindWireValue }
        regularChannels
            .orderedBySidebarIds(channelOrder, pinnedFirstId = allChannelId)
            .sortedForSidebar(channelSortModeState, pinnedFirstId = allChannelId) { it.name }
    }
    val displayedChannels = baseDisplayedChannels
    val sortableDisplayedChannels = remember(baseDisplayedChannels, allChannelId) {
        baseDisplayedChannels.withoutSidebarPinnedItem(allChannelId)
    }
    val displayedJointChannels = remember(channels, channelOrder, channelSortModeState) {
        channels
            .filter { it.kind == JointChannelKindWireValue }
            .orderedBySidebarIds(channelOrder)
            .sortedForSidebar(channelSortModeState) { it.name }
    }
    val hiddenDmIdSet = remember(hiddenDmIds) { hiddenDmIds.toSet() }
    val displayedDirectMessages = remember(directMessages, dmOrder, directMessageSortMode, hiddenDmIdSet) {
        directMessages
            .orderedBySidebarIds(dmOrder)
            .filter { dm -> dm.id !in hiddenDmIdSet || dm.homeConversationBadgeProjection().hasUnread }
            .sortedForSidebar(directMessageSortMode) { it.name }
    }
    val displayedPinned = remember(channels, directMessages, pinnedChannelIds, pinnedAgentIds, pinnedOrder, pinnedItems, pinnedSortModeState, pinnedMemberProfileResolver) {
        pinnedRowsForSidebar(
            channels = channels,
            directMessages = directMessages,
            pinnedIds = pinnedChannelIds,
            pinnedAgentIds = pinnedAgentIds,
            pinnedOrder = pinnedOrder,
            typedPinnedItems = pinnedItems,
            pinnedMemberProfileResolver = pinnedMemberProfileResolver,
            mode = pinnedSortModeState
        )
    }
    val pinnedItemsForSort = remember(displayedPinned) { displayedPinned.mapNotNull { it.toSidebarSortItem() } }
    val pinnedSectionHasUnread = remember(displayedPinned) { displayedPinned.any { it.conversation?.homeConversationBadgeProjection()?.hasUnread == true } }
    val channelSectionHasUnread = remember(displayedChannels) { displayedChannels.any { it.homeConversationBadgeProjection().hasUnread } }
    val jointChannelSectionHasUnread = remember(displayedJointChannels) { displayedJointChannels.any { it.homeConversationBadgeProjection().hasUnread } }
    val directMessageSectionHasUnread = remember(displayedDirectMessages) { displayedDirectMessages.any { it.homeConversationBadgeProjection().hasUnread } }
    val serverSwitcherHasOtherUnread =
        remember(currentServerId, servers) {
            homeServerSwitcherHasOtherUnread(currentServerId, servers)
        }
    val serverSwitcherAvatarPainters = rememberServerSwitcherAvatarPainters(servers)
    val sidebarSelection =
        homeSidebarSelection(
            selectedChannelId = selectedChannelId,
            selectedConversationSection = selectedConversationSection,
            channels = channels,
            directMessages = directMessages,
            searchSelected = searchSelected,
            activitySelected = activitySelected,
            savedSelected = savedSelected,
            pinnedChannelIds = pinnedIdSet
        )
    // #211 React parity (revised by @artin 23:38): pinned row and
    // its original-section copy both render the same conversationId,
    // and both must show the Selected highlight at the same time.
    // Use the conversationId match directly so the per-section
    // gating in `sectionSelection()` doesn't filter out the second
    // copy. Pressed state still uses the per-row `pressedRowKey`
    // so the two copies don't press together.
    val channelSectionSelection = sidebarSelection.sectionSelection(SlockHomeConversationSection.Channel)
    val jointChannelSectionSelection = sidebarSelection.sectionSelection(SlockHomeConversationSection.JointChannel)
    val directMessageSectionSelection = sidebarSelection.sectionSelection(SlockHomeConversationSection.DirectMessage)
    val pinnedSectionSelection = sidebarSelection.sectionSelection(SlockHomeConversationSection.Pinned)
    val pullToRefreshEnabled = homePullToRefreshEnabled(contentRefreshEnabled)
    val lazyItemCount =
        homeLazyItemCount(
            channelsCount = displayedChannels.size,
            jointCount = displayedJointChannels.size,
            directMessagesCount = displayedDirectMessages.size,
            pinnedCount = displayedPinned.size,
            channelsExpanded = channelsExpanded,
            jointChannelsExpanded = jointChannelsExpanded,
            directMessagesExpanded = directMessagesExpanded,
            pinnedExpanded = pinnedExpanded,
            hasPullToRefreshItem = pullToRefreshEnabled,
            hasInlineError = inlineError != null,
            channelSkeletonCount =
                if (channelsExpanded && showLoadingSkeletons) HomeLoadingChannelSkeletonRows else 0,
            directMessageSkeletonCount =
                if (directMessagesExpanded && showLoadingSkeletons) HomeLoadingDirectMessageSkeletonRows else 0
        )
    val lazyKeyRegistry = remember { HomeSidebarLazyKeyRegistry() }
    val pinnedLazyKeys =
        lazyKeyRegistry.keysFor(
            serverId = currentServerId,
            section = "pinned",
            identities = displayedPinned.map { it.key }
        )
    val jointChannelLazyKeys =
        lazyKeyRegistry.keysFor(
            serverId = currentServerId,
            section = "joint",
            identities = displayedJointChannels.map { it.id }
        )
    val channelLazyKeys =
        lazyKeyRegistry.keysFor(
            serverId = currentServerId,
            section = "channel",
            identities = displayedChannels.map { it.id }
        )
    val directMessageLazyKeys =
        lazyKeyRegistry.keysFor(
            serverId = currentServerId,
            section = "dm",
            identities = displayedDirectMessages.map { it.id }
        )
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialListPosition.firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = initialListPosition.firstVisibleItemScrollOffset
        )
    var listStateServerId by remember { mutableStateOf(listPositionServerId) }
    var listPremeasureServerId by remember { mutableStateOf(currentServerId) }
    var listPremeasureLimit by remember { mutableStateOf(homeListInitialBeyondBoundsItemCount()) }
    val homeUserScrollGeneration = remember { HomeUserScrollGeneration() }
    val listPositionRestoreStartUserScrollGeneration =
        remember(
            listPositionServerId,
            initialListPosition.firstVisibleItemIndex,
            initialListPosition.firstVisibleItemScrollOffset
        ) {
            // Capture the restore intent at composition time. A pointer move
            // before the effect gets the viewport retires this intent.
            homeUserScrollGeneration.value
        }
    val captureRowAnchors = !listState.isScrollInProgress
    val latestOnListPositionSnapshot by rememberUpdatedState(onListPositionSnapshot)
    DisposableEffect(listState, currentServerId) {
        val snapshotServerId = currentServerId
        onDispose {
            if (homeListStateOwnerMatches(snapshotServerId, listStateServerId)) {
                latestOnListPositionSnapshot(
                    snapshotServerId,
                    listState.toAppShellLazyListPosition()
                )
            }
        }
    }
    val listScope = rememberCoroutineScope()
    val scrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequestFence.consume(scrollToTopRequest)) {
            listState.animateScrollToTopExpressive()
        }
    }
    var pullRefreshActive by remember(currentServerId) { mutableStateOf(false) }
    LaunchedEffect(contentRefreshing, pullToRefreshEnabled) {
        if (!contentRefreshing || !pullToRefreshEnabled) {
            pullRefreshActive = false
        }
    }
    val pullRefreshing = contentRefreshing && pullToRefreshEnabled && pullRefreshActive
    val headerRefreshing = homeHeaderRefreshIndicatorVisible(
        contentRefreshing = contentRefreshing,
        pullRefreshing = pullRefreshing
    )
    val appUpdateBanner = remember(notifications) { notifications.homeAppUpdateBannerNotification() }
    val visibleNotifications = remember(notifications) { notifications.nonAppUpdateHomeNotifications() }
    var notificationCenterExpanded by remember(currentServerId) { mutableStateOf(false) }
    LaunchedEffect(visibleNotifications.size) {
        if (visibleNotifications.isEmpty()) {
            notificationCenterExpanded = false
        }
    }
    LaunchedEffect(externalFocusLossDismissEpoch) {
        if (externalFocusLossDismissEpoch > 0) {
            notificationCenterExpanded = false
        }
    }
    val pullToRefreshState = rememberPullToRefreshState(pullRefreshing)
    val sidebarInteractionsEnabled = true
    val disabledListAction: () -> Unit = {}
    val topSafeArea = if (applyTopSafeArea) slockTopSafeAreaPadding() else 0.dp
    val bottomSafeArea = if (showBottomBar) slockBottomSafeAreaPadding() else 0.dp
    val effectiveBottomContentPadding =
        if (showBottomBar) {
            bottomContentPadding.max(slockAppBottomTabBarContentInset(bottomSafeArea))
        } else {
            bottomContentPadding
        }
    val bottomBarOverlaysContent = showBottomBar && slockAppBottomTabBarOverlaysContent()
    val dismissNotificationCenterForFocusLoss = {
        if (notificationCenterExpanded) {
            notificationCenterExpanded = false
        }
    }
    val onServerBadgeClickWithDismiss: () -> Unit = {
        dismissNotificationCenterForFocusLoss()
        onServerBadgeClick()
    }
    val onSearchClickWithDismiss: () -> Unit = {
        dismissNotificationCenterForFocusLoss()
        onSearchClick()
    }
    val onInboxClickWithDismiss: () -> Unit = {
        dismissNotificationCenterForFocusLoss()
        onInboxClick()
    }
    val onSavedClickWithDismiss: () -> Unit = {
        dismissNotificationCenterForFocusLoss()
        onSavedClick()
    }
    val onChannelClickWithDismiss: (String) -> Unit = { conversationId ->
        dismissNotificationCenterForFocusLoss()
        onChannelClick(conversationId)
    }
    val onChannelLongPressWithDismiss: (String, Offset) -> Unit = { conversationId, anchor ->
        dismissNotificationCenterForFocusLoss()
        onChannelLongPress(conversationId, anchor)
    }
    val onBottomTabSelected: (String) -> Unit = { tabKey ->
        if (shouldDismissHomeNotificationCenterForTabSelection(
                expanded = notificationCenterExpanded,
                selectedTabKey = selectedTabKey,
                targetTabKey = tabKey
            )
        ) {
            notificationCenterExpanded = false
        }
        onTabSelected(tabKey)
    }
    Box(
        modifier = modifier.fillMaxSize().background(SlockColor.AppBgLightBlue).padding(outerPadding)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(SlockColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                traceMarkdownSection(SlockHomeTraceNames.TITLEBAR_RENDER, notifications.size) {
                    HomeTitlebar(
                        serverName = currentServerName,
                        hasOtherServerUnread = serverSwitcherHasOtherUnread,
                        notifications = visibleNotifications,
                        refreshing = headerRefreshing,
                        topSafeArea = topSafeArea,
                        onServerBadgeClick = onServerBadgeClickWithDismiss,
                        onBlankClick = {
                            dismissNotificationCenterForFocusLoss()
                            listScope.launch {
                                listState.animateScrollToTopExpressive()
                            }
                        },
                        onNotificationToggle = { notificationCenterExpanded = !notificationCenterExpanded }
                    )
                }
                SlockAppShellBodyTopBanner()
                appUpdateBanner?.let { update ->
                    HomeAppUpdateBanner(
                        notification = update,
                        onClick = { onNotificationView(update) }
                    )
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val targetHomeBeyondBoundsItemCount =
                        homeListBeyondBoundsItemCount(
                            itemCount = lazyItemCount,
                            viewportHeightDp = maxHeight.value
                        )
                    val homeBeyondBoundsItemCount =
                        if (listPremeasureServerId == currentServerId) {
                            listPremeasureLimit.coerceAtMost(targetHomeBeyondBoundsItemCount)
                        } else {
                            HomeListMinBeyondBoundsItemCount.coerceAtMost(targetHomeBeyondBoundsItemCount)
                        }
                    LaunchedEffect(
                        currentServerId,
                        listPositionServerId,
                        initialListPosition.firstVisibleItemIndex,
                        initialListPosition.firstVisibleItemScrollOffset,
                        targetHomeBeyondBoundsItemCount
                    ) {
                        if (listStateServerId != listPositionServerId) {
                            latestOnListPositionSnapshot(
                                listStateServerId,
                                listState.toAppShellLazyListPosition()
                            )
                            if (
                                shouldRestoreHomeListPosition(
                                    listStateServerId = listStateServerId,
                                    listPositionServerId = listPositionServerId,
                                    restoreStartUserScrollGeneration = listPositionRestoreStartUserScrollGeneration,
                                    currentUserScrollGeneration = homeUserScrollGeneration.value,
                                    isScrollInProgress = listState.isScrollInProgress
                                )
                            ) {
                                listState.scrollToItem(
                                    initialListPosition.firstVisibleItemIndex,
                                    initialListPosition.firstVisibleItemScrollOffset
                                )
                            }
                            // A skipped restore is consumed too: otherwise a later,
                            // unrelated effect restart could pull the user back.
                            listStateServerId = listPositionServerId
                        }
                        if (listPremeasureServerId != currentServerId) {
                            listPremeasureLimit = homeListInitialBeyondBoundsItemCount()
                            listPremeasureServerId = currentServerId
                        }
                        if (listPremeasureLimit > targetHomeBeyondBoundsItemCount) {
                            listPremeasureLimit = targetHomeBeyondBoundsItemCount
                        }
                        while (listPremeasureLimit < targetHomeBeyondBoundsItemCount) {
                            withFrameNanos { }
                            listPremeasureLimit =
                                homeListNextServerSwitchBeyondBoundsItemCount(
                                    current = listPremeasureLimit,
                                    target = targetHomeBeyondBoundsItemCount
                                )
                        }
                    }
                    traceMarkdownSection(SlockHomeTraceNames.LIST_RENDER, lazyItemCount) {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .touchListener { type, _ ->
                                        if (type == TouchType.Move) {
                                            homeUserScrollGeneration.value += 1
                                        }
                                    }
                                    .scrollToTop {
                                        listScope.launch {
                                            listState.animateScrollToTopExpressive()
                                        }
                                    },
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            beyondBoundsItemCount = homeBeyondBoundsItemCount
                        ) {
                            if (pullToRefreshEnabled) {
                                slockRaftPullToRefreshItem(
                                    state = pullToRefreshState,
                                    scrollState = listState,
                                    onRefresh = {
                                        pullRefreshActive = true
                                        onRefresh()
                                    }
                                )
                            }
                            item { Spacer(Modifier.height(SlockSpacing.Md)) }
                            item { SidebarActionRow(SlockLucideIconName.Search, labels.search, selected = sidebarSelection.topAction == SlockHomeSidebarTopSelection.Search, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding), onClick = if (sidebarInteractionsEnabled) onSearchClickWithDismiss else null) }
                            item {
                                val activityProjection =
                                    homeActivitySidebarProjection(
                                        activityUnreadCount = inboxUnreadCount,
                                        activityTotalCount = inboxTotalCount
                                    )
                                SidebarActivityRow(
                                    labels.inbox,
                                    unreadCount = activityProjection.unreadLabel,
                                    totalCount = activityProjection.totalLabel,
                                    selected = sidebarSelection.topAction == SlockHomeSidebarTopSelection.Activity,
                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                    onClick = if (sidebarInteractionsEnabled) onInboxClickWithDismiss else null
                                )
                            }
                            item { SidebarActionRow(SlockLucideIconName.Bookmark, labels.saved, selected = sidebarSelection.topAction == SlockHomeSidebarTopSelection.Saved, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding), trailingText = formatHomeSavedCount(savedCount), onClick = if (sidebarInteractionsEnabled) onSavedClickWithDismiss else null) }
                            inlineError?.let { error ->
                                item {
                                    HomeInlineErrorCard(
                                        error = error,
                                        modifier =
                                            Modifier
                                                .padding(horizontal = HomeListHorizontalPadding, vertical = SlockSpacing.Sm),
                                        onAction = onInlineErrorAction
                                    )
                                }
                            }
                            if (displayedPinned.isNotEmpty()) {
                                item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                                item {
                                    SlockCollapsibleSectionHeader(
                                        label = labels.pinned,
                                        count = displayedPinned.size,
                                        expanded = pinnedExpanded,
                                        onExpandedChange = { if (sidebarInteractionsEnabled) pinnedExpanded = it },
                                        showCollapsedUnreadDot = pinnedSectionHasUnread,
                                        sortContentDescription = "${labels.sortChannels} ${labels.pinned}",
                                        onSortClick = { anchor ->
                                            if (sidebarInteractionsEnabled) {
                                                activeSortMenu = if (activeSortMenu == SlockHomeSortSection.Pinned) null else SlockHomeSortSection.Pinned
                                                sortMenuAnchor = anchor
                                            }
                                        },
                                        modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
                                    )
                                }
                                if (pinnedExpanded) {
                                    items(displayedPinned.size, key = { index -> pinnedLazyKeys[index] }) { index ->
                                        val pinnedRow = displayedPinned[index]
                                        val pinned = pinnedRow.conversation
                                        val pinnedMember = pinnedRow.member
                                        val isDirectMessage = pinned?.isDirectMessageConversation() == true || pinnedMember != null
                                        // #211 React parity (revised by @artin 23:38): pinned copy and
                                        // original-section copy of the same conversation both
                                        // render Selected when their conversationId matches.
                                        // Selected wins over pressed so the user never confuses
                                        // "I'm pressing" with "this is the current conversation".
                                        val rowInteractionState = when {
                                            pinned != null && sidebarSelection.conversationId == pinned.id -> ChannelRowInteractionState.Selected
                                            pressedRowKey == "pinned:${pinnedRow.key}" -> ChannelRowInteractionState.Pressed
                                            else -> ChannelRowInteractionState.Default
                                        }
                                        val handleLongPress: (Offset) -> Unit = { anchor ->
                                            when {
                                                pinned != null -> {
                                                    onChannelLongPressWithDismiss(pinned.id, anchor)
                                                    activePinnedMemberMenuKey = null
                                                    activePinMenuConversationId = pinned.id
                                                    activeMenuSection = SlockHomeSortSection.Pinned
                                                    pinMenuAnchor = anchor
                                                }
                                                pinnedMember != null -> {
                                                    // Typed pinned members can exist before a DM
                                                    // conversation is materialized. They still own
                                                    // a stable member id in the per-user pinned
                                                    // order, so expose the section-level Sort action
                                                    // without inventing conversation operations.
                                                    dismissNotificationCenterForFocusLoss()
                                                    activePinMenuConversationId = null
                                                    activePinnedMemberMenuKey = pinnedRow.key
                                                    activeMenuSection = SlockHomeSortSection.Pinned
                                                    pinMenuAnchor = anchor
                                                }
                                            }
                                            // Menu opens: clear press so the visual doesn't stick
                                            // while the user reads it.
                                            pressedRowKey = null
                                        }
                                        if (isDirectMessage) {
                                            val badgeProjection = pinned?.homeConversationBadgeProjection()
                                            DirectMessageRow(
                                                interactionIdentity = homeSidebarInteractionIdentity("pinned", pinnedRow.key),
                                                name = pinned?.name ?: pinnedMember?.name.orEmpty(),
                                                description = pinned?.description ?: pinnedMember?.description,
                                                avatarLabel = pinned?.avatarLabel ?: pinnedMember?.avatarLabel.orEmpty(),
                                                peerType =
                                                    pinned?.peerType
                                                        ?: pinnedMember?.identity?.kind?.memberKindWireValue(),
                                                memberId =
                                                    pinned?.presenceMemberId
                                                        ?: pinnedMember?.identity?.memberId?.value,
                                                agentStatusResolver = agentStatusResolver,
                                                memberAvatarResolver = memberAvatarResolver,
                                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                hasDraft = pinned?.hasDraft == true,
                                                muted = pinned?.activityMuted == true,
                                                badge = badgeProjection?.label,
                                                badgeTone = badgeProjection?.tone ?: HomeSidebarBadgeTone.Loud,
                                                interactionState = rowInteractionState,
                                                captureLongPressAnchor = captureRowAnchors,
                                                onClick =
                                                    if (sidebarInteractionsEnabled) {
                                                        {
                                                            if (pinned != null) {
                                                                onChannelClickWithDismiss(pinned.id)
                                                            } else if (pinnedMember != null) {
                                                                dismissNotificationCenterForFocusLoss()
                                                                onPinnedMemberClick(pinnedMember.identity)
                                                            }
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                onLongPress = if (sidebarInteractionsEnabled && pinnedRow.supportsLongPress()) handleLongPress else null,
                                                onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "pinned:${pinnedRow.key}" }) else null,
                                                onPressEnd = { if (pressedRowKey == "pinned:${pinnedRow.key}") pressedRowKey = null }
                                            )
                                        } else if (pinned != null) {
                                            val badgeProjection = pinned.homeConversationBadgeProjection()
                                            ChannelRow(
                                                interactionIdentity = homeSidebarInteractionIdentity("pinned", pinnedRow.key),
                                                name = pinned.name,
                                                symbol = pinned.homeChannelSymbol(),
                                                joined = pinned.joined,
                                                hasDraft = pinned.hasDraft,
                                                muted = pinned.activityMuted,
                                                badge = badgeProjection.label,
                                                badgeTone = badgeProjection.tone,
                                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                interactionState = rowInteractionState,
                                                captureLongPressAnchor = captureRowAnchors,
                                                onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(pinned.id) }) else null,
                                                onLongPress = if (sidebarInteractionsEnabled) handleLongPress else null,
                                                onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "pinned:${pinnedRow.key}" }) else null,
                                                onPressEnd = { if (pressedRowKey == "pinned:${pinnedRow.key}") pressedRowKey = null }
                                            )
                                        }
                                    }
                                }
                            }
                        homeChannelSectionOrder(displayedJointChannels.isNotEmpty()).forEach { section ->
                            when (section) {
                                SlockHomeChannelSection.JointChannels -> {
                                    item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                                    item {
                                        SlockCollapsibleSectionHeader(
                                            label = labels.jointChannels,
                                            count = displayedJointChannels.size,
                                            expanded = jointChannelsExpanded,
                                            onExpandedChange = { if (sidebarInteractionsEnabled) jointChannelsExpanded = it },
                                            showCollapsedUnreadDot = jointChannelSectionHasUnread,
                                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
                                        )
                                    }
                                    if (jointChannelsExpanded) {
                                        items(displayedJointChannels.size, key = { index -> jointChannelLazyKeys[index] }) { index ->
                                            val channel = displayedJointChannels[index]
                                            val badgeProjection = channel.homeConversationBadgeProjection()
                                            val jointInteractionState = when {
                                                sidebarSelection.conversationId == channel.id -> ChannelRowInteractionState.Selected
                                                pressedRowKey == "channels:${channel.id}" -> ChannelRowInteractionState.Pressed
                                                else -> ChannelRowInteractionState.Default
                                            }
                                            ChannelRow(
                                                interactionIdentity = homeSidebarInteractionIdentity("joint", channel.id),
                                                name = channel.name,
                                                symbol = channel.homeChannelSymbol(),
                                                joined = channel.joined,
                                                hasDraft = channel.hasDraft,
                                                muted = channel.activityMuted,
                                                badge = badgeProjection.label,
                                                badgeTone = badgeProjection.tone,
                                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                interactionState = jointInteractionState,
                                                captureLongPressAnchor = captureRowAnchors,
                                                onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(channel.id) }) else null,
                                                onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                                    onChannelLongPressWithDismiss(channel.id, anchor)
                                                    activePinMenuConversationId = channel.id
                                                    activeMenuSection = SlockHomeSortSection.JointChannels
                                                    pinMenuAnchor = anchor
                                                    pressedRowKey = null
                                                }) else null,
                                                onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "channels:${channel.id}" }) else null,
                                                onPressEnd = { if (pressedRowKey == "channels:${channel.id}") pressedRowKey = null }
                                            )
                                        }
                                    }
                                }

                                SlockHomeChannelSection.Channels -> {
                                    item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                                    item {
                                        SlockCollapsibleSectionHeader(
                                            label = labels.channels,
                                            count = displayedChannels.size,
                                            expanded = channelsExpanded,
                                            onExpandedChange = { if (sidebarInteractionsEnabled) channelsExpanded = it },
                                            showCollapsedUnreadDot = channelSectionHasUnread,
                                            sortContentDescription = "${labels.sortChannels} ${labels.channels}",
                                            onSortClick = { anchor ->
                                                if (sidebarInteractionsEnabled) {
                                                    activeSortMenu = if (activeSortMenu == SlockHomeSortSection.Channels) null else SlockHomeSortSection.Channels
                                                    sortMenuAnchor = anchor
                                                }
                                            },
                                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
                                        ) {
                                            SlockSectionHeaderIconAction(SlockLucideIconName.Plus, labels.addChannel, if (sidebarInteractionsEnabled) onCreateChannelClick else disabledListAction)
                                        }
                                    }
                                    if (channelsExpanded) {
                                        items(displayedChannels.size, key = { index -> channelLazyKeys[index] }) { index ->
                                            val channel = displayedChannels[index]
                                            val badgeProjection = channel.homeConversationBadgeProjection()
                                            // #211 review (Cindy): selected wins over pressed.
                                            val channelInteractionState = when {
                                                sidebarSelection.conversationId == channel.id -> ChannelRowInteractionState.Selected
                                                pressedRowKey == "channels:${channel.id}" -> ChannelRowInteractionState.Pressed
                                                else -> ChannelRowInteractionState.Default
                                            }
                                            ChannelRow(
                                                interactionIdentity = homeSidebarInteractionIdentity("channel", channel.id),
                                                name = channel.name,
                                                symbol = channel.homeChannelSymbol(),
                                                joined = channel.joined,
                                                hasDraft = channel.hasDraft,
                                                muted = channel.activityMuted,
                                                badge = badgeProjection.label,
                                                badgeTone = badgeProjection.tone,
                                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                                interactionState = channelInteractionState,
                                                captureLongPressAnchor = captureRowAnchors,
                                                onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(channel.id) }) else null,
                                                onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                                    onChannelLongPressWithDismiss(channel.id, anchor)
                                                    activePinMenuConversationId = channel.id
                                                    activeMenuSection = SlockHomeSortSection.Channels
                                                    pinMenuAnchor = anchor
                                                    pressedRowKey = null
                                                }) else null,
                                                onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "channels:${channel.id}" }) else null,
                                                onPressEnd = { if (pressedRowKey == "channels:${channel.id}") pressedRowKey = null }
                                            )
                                        }
                                    }
                                    if (channelsExpanded && showLoadingSkeletons) {
                                        items(HomeLoadingChannelSkeletonRows, key = { index -> "channel-skeleton-$index" }) {
                                            HomeSidebarSkeletonRow(modifier = Modifier.padding(horizontal = HomeListHorizontalPadding))
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                        item {
                            SlockCollapsibleSectionHeader(
                                label = labels.directMessages,
                                count = displayedDirectMessages.size,
                                expanded = directMessagesExpanded,
                                onExpandedChange = { if (sidebarInteractionsEnabled) directMessagesExpanded = it },
                                showCollapsedUnreadDot = directMessageSectionHasUnread,
                                sortContentDescription = "${labels.sortChannels} ${labels.directMessages}",
                                onSortClick = { anchor ->
                                    if (sidebarInteractionsEnabled) {
                                        activeSortMenu = if (activeSortMenu == SlockHomeSortSection.DirectMessages) null else SlockHomeSortSection.DirectMessages
                                        sortMenuAnchor = anchor
                                    }
                                },
                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                trailingContent = null
                            )
                        }
                        if (directMessagesExpanded) {
                            items(displayedDirectMessages.size, key = { index -> directMessageLazyKeys[index] }) { index ->
                                val dm = displayedDirectMessages[index]
                                val badgeProjection = dm.homeConversationBadgeProjection()
                                val dmInteractionState = when {
                                    sidebarSelection.conversationId == dm.id -> ChannelRowInteractionState.Selected
                                    pressedRowKey == "dms:${dm.id}" -> ChannelRowInteractionState.Pressed
                                    else -> ChannelRowInteractionState.Default
                                }
                                DirectMessageRow(
                                    interactionIdentity = homeSidebarInteractionIdentity("dm", dm.id),
                                    name = dm.name,
                                    description = dm.description,
                                    avatarLabel = dm.avatarLabel,
                                    peerType = dm.peerType,
                                    memberId = dm.presenceMemberId,
                                    agentStatusResolver = agentStatusResolver,
                                    memberAvatarResolver = memberAvatarResolver,
                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                    hasDraft = dm.hasDraft,
                                    muted = dm.activityMuted,
                                    badge = badgeProjection.label,
                                    badgeTone = badgeProjection.tone,
                                    interactionState = dmInteractionState,
                                    captureLongPressAnchor = captureRowAnchors,
                                    onClick = if (sidebarInteractionsEnabled) ({ onChannelClickWithDismiss(dm.id) }) else null,
                                    onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                        onChannelLongPressWithDismiss(dm.id, anchor)
                                        activePinMenuConversationId = dm.id
                                        activeMenuSection = SlockHomeSortSection.DirectMessages
                                        pinMenuAnchor = anchor
                                        pressedRowKey = null
                                    }) else null,
                                    onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "dms:${dm.id}" }) else null,
                                    onPressEnd = { if (pressedRowKey == "dms:${dm.id}") pressedRowKey = null }
                                )
                            }
                        }
                        if (directMessagesExpanded && showLoadingSkeletons) {
                            items(HomeLoadingDirectMessageSkeletonRows, key = { index -> "dm-skeleton-$index" }) {
                                HomeSidebarSkeletonRow(modifier = Modifier.padding(horizontal = HomeListHorizontalPadding))
                            }
                        }
                        item { Spacer(Modifier.height(effectiveBottomContentPadding)) }
                    }
                }
            }
            }
            ServerSwitcherAvatarPrewarmSurface(serverSwitcherAvatarPainters)
            if (serverSwitcherExpanded) {
                // task #436: both home popovers ride the shared floating-overlay
                // infrastructure (touch-guard barrier + tap-outside dismiss) instead
                // of per-popover dismiss layers, so nothing beneath stays interactive.
                SlockFloatingOverlayHost(
                    onDismiss = onServerSwitcherDismiss,
                    modifier = Modifier.padding(bottom = bottomContentPadding),
                    dismissActivationDelayMillis = SlockFloatingOverlayClickTriggeredDismissDelayMillis
                ) { _ ->
                    ServerSwitcherPanel(
                        servers,
                        currentServerId,
                        pendingServerId,
                        prefersChineseCommunity,
                        labels,
                        onServerSelected,
                        onServerOrderChange,
                        onCreateServerClick,
                        Modifier
                            .padding(
                                top = homeServerSwitcherPanelTopOffset(topSafeArea),
                                start = SlockSpacing.Lg
                            )
                            // The switcher contains a LazyColumn. Using
                            // slockFloatingContentClickTarget() here installs
                            // nativeDispatchCapture on Android/OHOS, which can
                            // intercept the list's drag stream before Kuikly's
                            // lazy-list scroll handler sees it. A plain no-op
                            // clickable still prevents taps on empty panel space
                            // from dismissing through the overlay without
                            // starving child scroll gestures.
                            .clickable(onClick = {})
                    )
                }
            }
            if (notificationCenterExpanded && visibleNotifications.isNotEmpty()) {
                SlockFloatingOverlayHost(
                    onDismiss = { notificationCenterExpanded = false },
                    modifier = Modifier.padding(bottom = bottomContentPadding),
                    dismissActivationDelayMillis = SlockFloatingOverlayClickTriggeredDismissDelayMillis
                ) { _ ->
                    Box(Modifier.fillMaxSize()) {
                        HomeNotificationPopover(
                            visibleNotifications,
                            onNotificationView,
                            onNotificationDismiss,
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(
                                    top = homeNotificationPopoverTopOffset(topSafeArea),
                                    end = SlockSpacing.Md
                                )
                                .slockFloatingContentClickTarget()
                        )
                    }
                }
            }
            activeSortMenu?.let { section ->
                SlockBaseMenuOverlay(
                    anchor = sortMenuAnchor,
                    items = slockHomeSortMenuItems(labels),
                    selectedId = when (section) {
                        SlockHomeSortSection.Channels, SlockHomeSortSection.JointChannels -> channelSortModeState.wireValue
                        SlockHomeSortSection.DirectMessages -> directMessageSortMode.wireValue
                        SlockHomeSortSection.Pinned -> pinnedSortModeState.wireValue
                    },
                    onDismiss = { activeSortMenu = null },
                    onSelect = { selected ->
                        val nextMode = SlockSidebarSortMode.fromWireValue(selected)
                        when (section) {
                            SlockHomeSortSection.Channels, SlockHomeSortSection.JointChannels -> {
                                channelSortModeState = nextMode
                                onChannelSortModeChange(nextMode)
                            }
                            SlockHomeSortSection.DirectMessages -> {
                                directMessageSortMode = nextMode
                                onDmSortModeChange(nextMode)
                            }
                            SlockHomeSortSection.Pinned -> {
                                pinnedSortModeState = nextMode
                                onPinnedSortModeChange(nextMode)
                            }
                        }
                        activeSortMenu = null
                    }
                )
            }
            val activeConversationId = activePinMenuConversationId
            val activeConversation =
                activeConversationId?.let { id ->
                    channels.firstOrNull { it.id == id } ?: directMessages.firstOrNull { it.id == id }
                }
            val activeIsDirectMessage = activeConversation?.isDirectMessageConversation() == true
            val activeIsJoint = activeConversation?.homeChannelKind() == JointChannelKindWireValue
            val activeCanArchive =
                activeConversation != null &&
                    !activeIsDirectMessage &&
                    !activeIsJoint &&
                    activeConversation.name != "all" &&
                    !activeConversation.archived &&
                    canArchiveChannels
            val pinTargetIsPinned = activeConversationId != null && activeConversationId in pinnedIdSet
            val sortableFromMenu =
                when (activeMenuSection) {
                    SlockHomeSortSection.Pinned -> pinnedItemsForSort.size > 1
                    SlockHomeSortSection.Channels -> sortableDisplayedChannels.size > 1
                    SlockHomeSortSection.JointChannels -> displayedJointChannels.size > 1
                    SlockHomeSortSection.DirectMessages -> displayedDirectMessages.size > 1
                    null -> false
                }
            val pinMenuItems = activeConversation?.let { conversation ->
                slockHomeConversationMenuItems(
                    conversation = conversation,
                    labels = labels,
                    pinTargetIsPinned = pinTargetIsPinned,
                    sortableFromMenu = sortableFromMenu && sortOverlaySection == null,
                    activeIsDirectMessage = activeIsDirectMessage,
                    activeCanArchive = activeCanArchive
                )
            }
            if (activeConversationId != null && pinMenuItems != null) {
                SlockBaseMenuOverlay(
                    anchor = pinMenuAnchor,
                    items = pinMenuItems,
                    selectedId = "",
                    onDismiss = {
                        activePinMenuConversationId = null
                        onPinMenuDismiss()
                    },
                    onSelect = { actionId ->
                        when (actionId) {
                            "mark-read" -> onReadToggle(activeConversationId, false)
                            "mark-unread" -> onReadToggle(activeConversationId, true)
                            "mute-channel" -> onChannelActivityMuteToggle(activeConversationId, true)
                            "unmute-channel" -> onChannelActivityMuteToggle(activeConversationId, false)
                            "toggle-pin" -> onPinToggle(activeConversationId)
                            "close-dm" -> onCloseDirectMessage(activeConversationId)
                            "archive-channel" -> onArchiveChannel(activeConversationId)
                            "sort-channels" -> {
                                sortOverlaySection = activeMenuSection
                            }
                        }
                        activePinMenuConversationId = null
                    }
                )
            }
            if (activePinnedMemberMenuKey != null) {
                SlockBaseMenuOverlay(
                    anchor = pinMenuAnchor,
                    items = slockHomePinnedMemberMenuItems(labels),
                    selectedId = "",
                    onDismiss = {
                        activePinnedMemberMenuKey = null
                        onPinMenuDismiss()
                    },
                    onSelect = { actionId ->
                        if (actionId == "sort-channels") {
                            sortOverlaySection = SlockHomeSortSection.Pinned
                        }
                        activePinnedMemberMenuKey = null
                    }
                )
            }
            sortOverlaySection?.let { section ->
                val sortItems =
                    when (section) {
                        SlockHomeSortSection.Pinned -> pinnedItemsForSort
                        SlockHomeSortSection.JointChannels -> displayedJointChannels
                        SlockHomeSortSection.DirectMessages -> displayedDirectMessages
                        SlockHomeSortSection.Channels -> sortableDisplayedChannels
                    }
                SlockSidebarSortOverlay(
                    title = when (section) {
                        SlockHomeSortSection.Pinned -> labels.pinned
                        SlockHomeSortSection.JointChannels -> labels.jointChannels
                        SlockHomeSortSection.DirectMessages -> labels.directMessages
                        SlockHomeSortSection.Channels -> labels.channels
                    },
                    items = sortItems,
                    saveLabel = labels.saveSort,
                    onDismiss = { sortOverlaySection = null },
                    onSave = { orderedIds ->
                        sortOverlaySection = null
                        val initialIds = sortItems.map { it.id }
                        if (orderedIds != initialIds) {
                            when (section) {
                                SlockHomeSortSection.Pinned -> onSidebarOrderChange(SlockSidebarSortTarget.Pinned, orderedIds)
                                SlockHomeSortSection.JointChannels -> onSidebarOrderChange(SlockSidebarSortTarget.JointChannels, orderedIds)
                                SlockHomeSortSection.DirectMessages -> onSidebarOrderChange(SlockSidebarSortTarget.DirectMessages, orderedIds)
                                SlockHomeSortSection.Channels -> onSidebarOrderChange(SlockSidebarSortTarget.Channels, orderedIds)
                            }
                        }
                    }
                )
            }
        }
        if (showBottomBar && !bottomBarOverlaysContent) {
            val homeTabLabel = stringResource(MR.strings.tab_home)
            val tasksTabLabel = stringResource(MR.strings.tab_tasks)
            val membersTabLabel = stringResource(MR.strings.tab_members)
            val settingsTabLabel = stringResource(MR.strings.tab_settings)
            val bottomTabItems = remember(
                selectedTabKey,
                homeTabLabel,
                tasksTabLabel,
                membersTabLabel,
                settingsTabLabel
            ) {
                listOf(
                    SlockBottomTabItemPayload("home", homeTabLabel, SlockLucideIconName.House, selected = selectedTabKey == "home"),
                    SlockBottomTabItemPayload("tasks", tasksTabLabel, SlockLucideIconName.SquareCheckBig, selected = selectedTabKey == "tasks"),
                    SlockBottomTabItemPayload("members", membersTabLabel, SlockLucideIconName.Users, selected = selectedTabKey == "members"),
                    SlockBottomTabItemPayload("settings", settingsTabLabel, SlockLucideIconName.Settings, selected = selectedTabKey == "settings")
                )
            }
            SlockAppBottomTabBarHost(
                items = bottomTabItems,
                onSelect = onBottomTabSelected,
                modifier = Modifier.fillMaxWidth(),
                bottomSafeArea = bottomSafeArea
            )
        }
        }
        if (bottomBarOverlaysContent) {
            val homeTabLabel = stringResource(MR.strings.tab_home)
            val tasksTabLabel = stringResource(MR.strings.tab_tasks)
            val membersTabLabel = stringResource(MR.strings.tab_members)
            val settingsTabLabel = stringResource(MR.strings.tab_settings)
            val bottomTabItems = remember(
                selectedTabKey,
                homeTabLabel,
                tasksTabLabel,
                membersTabLabel,
                settingsTabLabel
            ) {
                listOf(
                    SlockBottomTabItemPayload("home", homeTabLabel, SlockLucideIconName.House, selected = selectedTabKey == "home"),
                    SlockBottomTabItemPayload("tasks", tasksTabLabel, SlockLucideIconName.SquareCheckBig, selected = selectedTabKey == "tasks"),
                    SlockBottomTabItemPayload("members", membersTabLabel, SlockLucideIconName.Users, selected = selectedTabKey == "members"),
                    SlockBottomTabItemPayload("settings", settingsTabLabel, SlockLucideIconName.Settings, selected = selectedTabKey == "settings")
                )
            }
            SlockAppBottomTabBarHost(
                items = bottomTabItems,
                onSelect = onBottomTabSelected,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                bottomSafeArea = bottomSafeArea
            )
        }
    }
}

private fun Dp.max(other: Dp): Dp =
    if (this >= other) this else other

internal fun sharedListBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceAtLeast(0).coerceAtMost(SharedListMaxBeyondBoundsItemCount)

internal fun homeListBeyondBoundsItemCount(
    itemCount: Int,
    viewportHeightDp: Float,
    representativeRowStepDp: Float = HomeListRepresentativeRowStepDp
): Int =
    slockViewportBeyondBoundsItemCount(
        itemCount = itemCount,
        viewportHeightDp = viewportHeightDp,
        representativeRowStepDp = representativeRowStepDp,
        minimumItems = HomeListMinBeyondBoundsItemCount,
        maximumItems = HomeListMaxBeyondBoundsItemCount,
        viewportFraction = HomeListOverscanViewportFraction
    )

internal fun homeListInitialBeyondBoundsItemCount(): Int = HomeListMinBeyondBoundsItemCount

internal fun homeListNextServerSwitchBeyondBoundsItemCount(current: Int, target: Int): Int =
    (current.coerceAtLeast(HomeListMinBeyondBoundsItemCount) + HomeListServerSwitchRampStep)
        .coerceAtMost(target.coerceAtLeast(HomeListMinBeyondBoundsItemCount))

internal fun homeSidebarSlotKey(section: String, index: Int): String =
    "home-$section-slot-$index"

internal fun homeSidebarInteractionIdentity(section: String, identity: String): String =
    "$section:$identity"

internal fun homeListStateOwnerMatches(snapshotServerId: String?, listStateServerId: String?): Boolean =
    snapshotServerId?.trim()?.takeIf { it.isNotEmpty() } ==
        listStateServerId?.trim()?.takeIf { it.isNotEmpty() }

internal fun shouldRestoreHomeListPosition(
    listStateServerId: String?,
    listPositionServerId: String?,
    restoreStartUserScrollGeneration: Int,
    currentUserScrollGeneration: Int,
    isScrollInProgress: Boolean
): Boolean =
    listStateServerId != listPositionServerId &&
        restoreStartUserScrollGeneration == currentUserScrollGeneration &&
        !isScrollInProgress

private class HomeUserScrollGeneration(
    var value: Int = 0
)

internal class HomeSidebarLazyKeyRegistry {
    private data class SectionKeys(
        val serverId: String,
        val keysByIdentity: Map<String, String>
    )

    private val sectionKeys = mutableMapOf<String, SectionKeys>()

    fun keysFor(serverId: String, section: String, identities: List<String>): List<String> {
        val normalizedServerId = serverId.trim()
        val previous = sectionKeys[section]
        val keys =
            if (previous == null || previous.serverId != normalizedServerId) {
                identities.mapIndexed { index, _ -> homeSidebarSlotKey(section, index) }
            } else {
                identities.map { identity ->
                    previous.keysByIdentity[identity]
                        ?: "home-$section-identity-$identity"
                }
            }
        sectionKeys[section] =
            SectionKeys(
                serverId = normalizedServerId,
                keysByIdentity = identities.zip(keys).toMap()
            )
        return keys
    }
}

internal fun homeServerSwitcherBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceAtLeast(0).coerceAtMost(HomeServerSwitcherMaxBeyondBoundsItemCount)

internal fun homeLazyItemCount(
    channelsCount: Int,
    jointCount: Int,
    directMessagesCount: Int,
    pinnedCount: Int = 0,
    channelsExpanded: Boolean,
    jointChannelsExpanded: Boolean,
    directMessagesExpanded: Boolean,
    pinnedExpanded: Boolean = false,
    hasPullToRefreshItem: Boolean = true,
    hasInlineError: Boolean = false,
    channelSkeletonCount: Int = 0,
    directMessageSkeletonCount: Int = 0
): Int {
    val fixedItems =
        (if (hasPullToRefreshItem) 1 else 0) +
            1 + // top spacer
            1 + // search
            1 + // activity
            1 + // saved
            1 + // section spacer
            1 + // channels header
            1 + // section spacer
            1 + // direct messages header
            1 // bottom spacer
    val jointSectionItems = if (jointCount > 0) 2 else 0 // spacer + header
    val pinnedSectionItems = if (pinnedCount > 0) 2 else 0 // header + (rows below); rows counted conditionally
    return fixedItems +
        jointSectionItems +
        pinnedSectionItems +
        (if (hasInlineError) 1 else 0) +
        channelSkeletonCount.coerceAtLeast(0) +
        directMessageSkeletonCount.coerceAtLeast(0) +
        (if (channelsExpanded) channelsCount else 0) +
        (if (jointChannelsExpanded) jointCount else 0) +
        (if (directMessagesExpanded) directMessagesCount else 0) +
        (if (pinnedExpanded) pinnedCount else 0)
}

internal enum class SlockHomeChannelSection { JointChannels, Channels }

internal fun homeChannelSectionOrder(hasJointChannels: Boolean): List<SlockHomeChannelSection> =
    if (hasJointChannels) {
        listOf(SlockHomeChannelSection.JointChannels, SlockHomeChannelSection.Channels)
    } else {
        listOf(SlockHomeChannelSection.Channels)
    }

private enum class SlockHomeSortSection { Channels, JointChannels, DirectMessages, Pinned }

internal fun slockHomeConversationMenuItems(
    conversation: SlockHomeConversationItem,
    labels: SlockHomeShellLabels = SlockHomeShellLabels(),
    pinTargetIsPinned: Boolean,
    sortableFromMenu: Boolean,
    activeIsDirectMessage: Boolean,
    activeCanArchive: Boolean
): List<SlockBaseMenuItem> =
    buildList {
        val hasUnread = conversation.homeConversationBadgeProjection().hasUnread
        add(
            SlockBaseMenuItem(
                id = if (hasUnread) "mark-read" else "mark-unread",
                label = if (hasUnread) labels.markAsRead else labels.markAsUnread,
                icon = if (hasUnread) SlockLucideIconName.MailOpen else SlockLucideIconName.Mail
            )
        )
        if (conversation.activityMuteSupported) {
            add(
                SlockBaseMenuItem(
                    id = if (conversation.activityMuted) "unmute-channel" else "mute-channel",
                    label = if (conversation.activityMuted) labels.unmute else labels.mute,
                    icon = if (conversation.activityMuted) SlockLucideIconName.Bell else SlockLucideIconName.BellOff
                )
            )
        }
        add(
            SlockBaseMenuItem(
                id = "toggle-pin",
                label = if (pinTargetIsPinned) labels.unpin else labels.pin,
                icon = if (pinTargetIsPinned) SlockLucideIconName.PinOff else SlockLucideIconName.Pin
            )
        )
        if (sortableFromMenu) {
            add(
                SlockBaseMenuItem(
                    id = "sort-channels",
                    label = labels.sortChannels,
                    icon = SlockLucideIconName.ArrowUpDown
                )
            )
        }
        if (activeIsDirectMessage) {
            add(SlockBaseMenuItem(id = "close-dm", label = labels.closeChat, icon = SlockLucideIconName.X))
        } else if (activeCanArchive) {
            add(
                SlockBaseMenuItem(
                    id = "archive-channel",
                    label = labels.archive,
                    icon = SlockLucideIconName.Archive,
                    dividerBefore = true
                )
            )
        }
    }

internal fun slockHomePinnedMemberMenuItems(
    labels: SlockHomeShellLabels = SlockHomeShellLabels()
): List<SlockBaseMenuItem> =
    listOf(
        SlockBaseMenuItem(
            id = "sort-channels",
            label = labels.sortChannels,
            icon = SlockLucideIconName.ArrowUpDown
        )
    )

enum class SlockSidebarSortTarget { Channels, JointChannels, DirectMessages, Pinned }

internal fun SlockSidebarSortTarget.toReorderSidebarIntent(order: List<String>): SharedHomeIntent.ReorderSidebar =
    when (this) {
        SlockSidebarSortTarget.Channels -> SharedHomeIntent.ReorderSidebar(channelOrder = order)
        SlockSidebarSortTarget.JointChannels -> SharedHomeIntent.ReorderSidebar(jointChannelOrder = order)
        SlockSidebarSortTarget.DirectMessages -> SharedHomeIntent.ReorderSidebar(dmOrder = order)
        SlockSidebarSortTarget.Pinned -> SharedHomeIntent.ReorderSidebar(pinnedOrder = order)
    }

internal fun slockHomeSortMenuItems(labels: SlockHomeShellLabels = SlockHomeShellLabels()): List<SlockBaseMenuItem> =
    SlockSidebarSortMode.entries.map { mode ->
        SlockBaseMenuItem(
            id = mode.wireValue,
            label =
                when (mode) {
                    SlockSidebarSortMode.Manual -> labels.sortManual
                    SlockSidebarSortMode.Recent -> labels.sortRecent
                    SlockSidebarSortMode.Az -> labels.sortAz
                }
        )
    }

internal fun homeActivitySidebarUnreadCount(
    activityUnreadCount: Int
): Int =
    activityUnreadCount.coerceAtLeast(0)

internal fun homeActivitySidebarTotalCount(
    activityUnreadCount: Int,
    activityTotalCount: Int
): Int =
    activityTotalCount.takeIf { activityUnreadCount <= 0 && it > 0 } ?: 0

internal fun formatHomeSidebarBadgeCount(count: Int): String? =
    when {
        count <= 0 -> null
        count >= 100 -> "99+"
        else -> count.toString()
    }

internal fun homeConversationBadgeProjection(input: HomeConversationBadgeInput): HomeSidebarBadgeProjection {
    val label = formatHomeSidebarBadgeCount(input.unreadCount)
    if (label == null) return HomeSidebarBadgeProjection(label = null)
    val loud =
        input.joined &&
            (!input.muted || input.mentionPierced || input.followed)
    return HomeSidebarBadgeProjection(
        label = label,
        tone = if (loud) HomeSidebarBadgeTone.Loud else HomeSidebarBadgeTone.Quiet,
        hasUnread = true,
        hasAttention = loud
    )
}

internal fun SlockHomeConversationItem.homeConversationBadgeProjection(
    muted: Boolean = activityMuted,
    mentionPierced: Boolean = false,
    followed: Boolean = false
): HomeSidebarBadgeProjection =
    homeConversationBadgeProjection(
        HomeConversationBadgeInput(
            unreadCount = unreadCount,
            joined = joined,
            muted = muted,
            mentionPierced = mentionPierced,
            followed = followed
        )
    )

internal fun homeActivitySidebarProjection(
    activityUnreadCount: Int,
    activityTotalCount: Int
): HomeActivitySidebarProjection =
    HomeActivitySidebarProjection(
        unreadLabel = formatHomeSidebarBadgeCount(homeActivitySidebarUnreadCount(activityUnreadCount)),
        totalLabel = formatHomeSidebarBadgeCount(homeActivitySidebarTotalCount(activityUnreadCount, activityTotalCount))
    )

internal fun homeServerSwitcherHasOtherUnread(
    currentServerId: String,
    servers: List<SlockServerSwitcherItem>
): Boolean =
    servers.any { server ->
        server.id != currentServerId &&
            homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = server.unreadCount)).hasUnread
    }

private const val DefaultCommunityServerSlug = "community"
private const val ChineseCommunityServerSlug = "community-cn"

internal data class SlockServerSwitcherCommunityAction(
    val slug: String,
    val label: String
)

internal fun homeServerSwitcherHasJoinedCommunity(
    servers: List<SlockServerSwitcherItem>,
    slug: String
): Boolean =
    servers.any { it.slug == slug }

internal fun homeServerSwitcherJoinCommunityActions(
    servers: List<SlockServerSwitcherItem>,
    labels: SlockHomeShellLabels,
    includeChineseCommunity: Boolean
): List<SlockServerSwitcherCommunityAction> =
    buildList {
        if (
            includeChineseCommunity &&
            !homeServerSwitcherHasJoinedCommunity(servers, ChineseCommunityServerSlug)
        ) {
            add(SlockServerSwitcherCommunityAction(ChineseCommunityServerSlug, labels.joinChineseCommunityServer))
        }
        if (!homeServerSwitcherHasJoinedCommunity(servers, DefaultCommunityServerSlug)) {
            add(SlockServerSwitcherCommunityAction(DefaultCommunityServerSlug, labels.joinCommunityServer))
        }
    }

internal fun homeServerSwitcherPanelTopOffset(topSafeArea: Dp): Dp =
    topSafeArea + SlockSize.PageHeaderHeight + HomeServerSwitcherTopGap

internal fun homeServerSwitcherServerListMaxHeight(serverCount: Int): Dp? {
    if (serverCount <= HomeServerSwitcherMaxVisibleRows) return null
    return (HomeServerSwitcherRowMinHeight.value * HomeServerSwitcherMaxVisibleRows).dp
}

internal data class SlockHomeSidebarSelection(
    val topAction: SlockHomeSidebarTopSelection? = null,
    val conversationId: String? = null,
    val conversationSection: SlockHomeConversationSection? = null
)

internal data class SlockHomeSectionSelection(
    val section: SlockHomeConversationSection,
    val selectedId: String?
)

internal fun homeSidebarSelection(
    selectedChannelId: String?,
    selectedConversationSection: SlockHomeConversationSection?,
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    searchSelected: Boolean,
    activitySelected: Boolean,
    savedSelected: Boolean,
    pinnedChannelIds: Set<String> = emptySet()
): SlockHomeSidebarSelection =
    when {
        searchSelected -> SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Search)
        activitySelected -> SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Activity)
        savedSelected -> SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Saved)
        selectedChannelId != null -> {
            val section =
                selectedConversationSection
                    ?: homeConversationSectionForSelection(
                        selectedChannelId = selectedChannelId,
                        channels = channels,
                        directMessages = directMessages,
                        pinnedChannelIds = pinnedChannelIds
                    )
            SlockHomeSidebarSelection(
                conversationId = selectedChannelId,
                conversationSection = section
            )
        }
        else -> SlockHomeSidebarSelection()
    }

internal fun homeConversationSectionForSelection(
    selectedChannelId: String,
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    pinnedChannelIds: Set<String> = emptySet()
): SlockHomeConversationSection? {
    // #211: a pinned id should always resolve to the Pinned section, even
    // if it is still present in the raw `channels` / `directMessages` lists
    // (server returns the full set, the visual layer hides the original
    // copies). Otherwise the section-aware selection would point at the
    // Channel / JointChannel / DirectMessage row and the Pinned row would
    // not be highlighted, leaving the user with two un-highlighted sections
    // and one phantom highlight in the original section.
    if (selectedChannelId in pinnedChannelIds) {
        return SlockHomeConversationSection.Pinned
    }
    val channel = channels.firstOrNull { it.id == selectedChannelId }
    if (channel != null) {
        return if (channel.homeChannelKind() == JointChannelKindWireValue) {
            SlockHomeConversationSection.JointChannel
        } else {
            SlockHomeConversationSection.Channel
        }
    }
    return if (directMessages.any { it.id == selectedChannelId }) {
        SlockHomeConversationSection.DirectMessage
    } else {
        null
    }
}

internal fun SlockHomeConversationItem.homeChannelKind(): String =
    when (parseConversationKindOrNull(kind)) {
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        ConversationKind.Joint -> JointChannelKindWireValue
        else -> {
            channelType?.takeIf { it.isNotBlank() }?.lowercase()
                ?: kind.lowercase()
        }
    }

internal fun SlockHomeConversationItem.isDirectMessageConversation(): Boolean =
    parseConversationKindOrNull(kind) == ConversationKind.DirectMessage

internal fun SlockHomeConversationItem.homeChannelSymbol(): String =
    homeChannelKind().toSlockChannelSymbol()

internal fun String?.toSlockChannelSymbol(): String =
    when (this?.lowercase()) {
        "private" -> "lock"
        JointChannelKindWireValue -> "git-branch"
        else -> "#"
    }

internal fun SlockHomeSidebarSelection.sectionSelection(
    section: SlockHomeConversationSection
): SlockHomeSectionSelection =
    SlockHomeSectionSelection(
        section = section,
        selectedId = conversationId.takeIf { conversationSection == section }
    )

internal fun SlockHomeSectionSelection.selects(id: String): Boolean =
    selectedId == id

internal fun List<SlockHomeConversationItem>.sortedForSidebar(
    mode: SlockSidebarSortMode,
    pinnedFirstId: String? = null,
    label: (SlockHomeConversationItem) -> String
): List<SlockHomeConversationItem> {
    if (mode == SlockSidebarSortMode.Manual) return withPinnedFirst(pinnedFirstId)
    val pinned = pinnedFirstId?.let { id -> firstOrNull { it.id == id } }
    val sortable = pinned?.let { item -> filterNot { it.id == item.id } } ?: this
    val sorted =
        when (mode) {
            SlockSidebarSortMode.Manual -> sortable
            SlockSidebarSortMode.Recent ->
                sortable.sortedWith(
                    compareByDescending<SlockHomeConversationItem> {
                        parseSlockTimestampEpochMillisOrNull(it.lastMessageAt ?: it.createdAt) ?: Long.MIN_VALUE
                    }.thenBy { label(it).trim().lowercase() }.thenBy { it.id }
                )
            SlockSidebarSortMode.Az ->
                sortable.sortedWith(compareBy<SlockHomeConversationItem> { label(it).trim().lowercase() }.thenBy { it.id })
        }
    return if (pinned != null) listOf(pinned) + sorted else sorted
}

internal fun List<SlockHomeConversationItem>.orderedBySidebarIds(
    order: List<String>,
    pinnedFirstId: String? = null
): List<SlockHomeConversationItem> {
    if (isEmpty()) return this
    if (order.isEmpty()) return withPinnedFirst(pinnedFirstId)
    val byId = associateBy { it.id }
    val ordered = LinkedHashMap<String, SlockHomeConversationItem>()
    pinnedFirstId?.let { id ->
        byId[id]?.let { ordered[it.id] = it }
    }
    order.forEach { id ->
        if (id == pinnedFirstId) return@forEach
        byId[id]?.let { ordered[it.id] = it }
    }
    forEach { item ->
        if (!ordered.containsKey(item.id)) {
            ordered[item.id] = item
        }
    }
    return ordered.values.toList()
}

private fun List<SlockHomeConversationItem>.withPinnedFirst(pinnedFirstId: String?): List<SlockHomeConversationItem> {
    if (pinnedFirstId.isNullOrBlank() || size < 2 || firstOrNull()?.id == pinnedFirstId) return this
    val pinned = firstOrNull { it.id == pinnedFirstId } ?: return this
    return listOf(pinned) + filterNot { it.id == pinnedFirstId }
}

internal fun List<SlockHomeConversationItem>.withoutSidebarPinnedItem(
    pinnedFirstId: String?
): List<SlockHomeConversationItem> {
    if (pinnedFirstId.isNullOrBlank()) return this
    return filterNot { it.id == pinnedFirstId }
}

internal fun List<String>.moveItem(id: String, toIndex: Int): List<String> {
    val fromIndex = indexOf(id)
    if (fromIndex < 0 || toIndex !in indices || fromIndex == toIndex) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex.coerceIn(0, mutable.size), item)
    return mutable
}

/** A visible sort-overlay row's laid-out vertical bounds, in viewport space. */
internal data class SlockSortRowBounds(val index: Int, val top: Float, val size: Float)

/**
 * Resolve which list index the finger is over, in CONTENT space. [pointerY] is
 * the finger's viewport Y; [rows] are the currently visible rows (their tops
 * move as the list scrolls, so the same finger Y maps to a new index after an
 * auto-scroll — this is what keeps drag + auto-scroll consistent). Above the
 * first visible row clamps to it; below the last clamps to it; result is
 * clamped to [0, lastIndex].
 */
internal fun resolveSortTargetIndex(
    pointerY: Float,
    rows: List<SlockSortRowBounds>,
    lastIndex: Int
): Int {
    if (rows.isEmpty() || lastIndex < 0) return 0
    val sorted = rows.sortedBy { it.index }
    val first = sorted.first()
    val last = sorted.last()
    val raw =
        when {
            pointerY <= first.top -> first.index
            pointerY >= last.top + last.size -> last.index
            // Rows are laid out with spacing between them: a pointer inside the
            // gap between two rows belongs to the next row down. "First row whose
            // bottom edge is below the pointer" covers both cases; the old
            // strict-containment check missed the gaps and fell back to the last
            // visible row, snapping the insertion indicator to the end whenever
            // the finger crossed a gap (task #356 device feedback).
            else -> sorted.first { pointerY < it.top + it.size }.index
        }
    return raw.coerceIn(0, lastIndex)
}

internal fun resolveSortAutoScrollDelta(
    pointerY: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgeScrollZone: Float,
    edgeScrollStep: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean
): Float {
    if (viewportEnd <= viewportStart || edgeScrollZone <= 0f || edgeScrollStep <= 0f) return 0f
    return when {
        pointerY < viewportStart + edgeScrollZone && canScrollBackward -> -edgeScrollStep
        pointerY > viewportEnd - edgeScrollZone && canScrollForward -> edgeScrollStep
        else -> 0f
    }
}

internal fun resolveSortDraggedRowTop(
    pointerY: Float,
    viewportStart: Float,
    viewportEnd: Float,
    rowSize: Float
): Float {
    // The raw pointer remains unbounded for edge auto-scroll; only the floating
    // copy is clamped so it cannot stretch a Kuikly host past the list viewport.
    if (viewportEnd <= viewportStart || rowSize <= 0f) return viewportStart
    val maxTop = viewportEnd - rowSize
    if (maxTop <= viewportStart) return viewportStart
    return (pointerY - rowSize / 2f).coerceIn(viewportStart, maxTop)
}

private suspend fun runSlockSortAutoScrollLoop(
    listState: LazyListState,
    isDragging: () -> Boolean,
    pointerViewportY: () -> Float,
    edgeScrollZonePx: Float,
    edgeScrollStepPx: Float,
    onScrolled: (delta: Float, consumed: Float) -> Unit
) {
    while (isDragging()) {
        val info = listState.layoutInfo
        val delta =
            resolveSortAutoScrollDelta(
                pointerY = pointerViewportY(),
                viewportStart = info.viewportStartOffset.toFloat(),
                viewportEnd = info.viewportEndOffset.toFloat(),
                edgeScrollZone = edgeScrollZonePx,
                edgeScrollStep = edgeScrollStepPx,
                canScrollBackward = listState.canScrollBackward,
                canScrollForward = listState.canScrollForward
            )
        if (delta != 0f) {
            val consumed = listState.dispatchRawDelta(delta)
            onScrolled(delta, consumed)
        }
        withFrameNanos { }
    }
}

/**
 * Reconstruct the Pinned section from raw `pinnedChannelIds`, `pinnedAgentIds`,
 * and `pinnedOrder`
 * (server side `SettingsServerSidebarOrderSnapshot`).
 *
 * Manual mode: items in `pinnedOrder` (drop unknown ids), then any remaining
 * pinned ids not present in `pinnedOrder` appended in input order.
 * Recent mode: sorted by `lastMessageAt ?: createdAt` desc, then name.
 * A-Z mode: sorted by display name asc.
 *
 * Items must be present in either `channels` (regular or joint) or
 * `directMessages`; pinned ids that no longer exist are dropped. Agent ids
 * are resolved to their corresponding agent DM row through the row's
 * `presenceMemberId`, matching React's `pinnedAgentIds -> agentDmById` path.
 */
internal data class SlockHomePinnedMemberItem(
    val identity: MemberDetailIdentity,
    val name: String,
    val description: String? = null,
    val avatarLabel: String
)

internal fun MemberProfile.toSlockHomePinnedMemberItem(): SlockHomePinnedMemberItem? {
    if (kind == MemberKind.System) return null
    return SlockHomePinnedMemberItem(
        identity = MemberDetailIdentity(id, kind, serverId),
        name = displayName.ifBlank { handle.ifBlank { id.value } },
        description = handle.takeIf { it.isNotBlank() }?.let { "@$it" },
        avatarLabel = avatarLabel.ifBlank { displayName.take(1).uppercase().ifBlank { "?" } }
    )
}

internal data class SlockHomePinnedRow(
    val key: String,
    val conversation: SlockHomeConversationItem? = null,
    val member: SlockHomePinnedMemberItem? = null
) {
    val sortLabel: String = conversation?.name ?: member?.name.orEmpty()
    val sortTimestamp: String? = conversation?.lastMessageAt ?: conversation?.createdAt
}

internal fun SlockHomePinnedRow.supportsLongPress(): Boolean = conversation != null || member != null

internal fun SlockHomePinnedRow.toSidebarSortItem(): SlockHomeConversationItem? =
    conversation ?: member?.let { pinnedMember ->
        SlockHomeConversationItem(
            id = pinnedMember.identity.memberId.value,
            name = pinnedMember.name,
            kind = KuiklyThreadSurfaceKind.DirectMessage,
            channelType = "dm",
            description = pinnedMember.description,
            avatarLabel = pinnedMember.avatarLabel,
            peerType = pinnedMember.identity.kind.memberKindWireValue(),
            presenceMemberId = pinnedMember.identity.memberId.value
        )
    }

internal fun pinnedItemsForSidebar(
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    pinnedIds: List<String>,
    pinnedAgentIds: List<String> = emptyList(),
    pinnedOrder: List<String>,
    mode: SlockSidebarSortMode
): List<SlockHomeConversationItem> =
    pinnedRowsForSidebar(
        channels = channels,
        directMessages = directMessages,
        pinnedIds = pinnedIds,
        pinnedAgentIds = pinnedAgentIds,
        pinnedOrder = pinnedOrder,
        mode = mode
    ).mapNotNull { it.conversation }

internal fun pinnedRowsForSidebar(
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    pinnedIds: List<String>,
    pinnedAgentIds: List<String> = emptyList(),
    pinnedOrder: List<String>,
    typedPinnedItems: List<SettingsServerSidebarPinnedItem> = emptyList(),
    pinnedMemberProfileResolver: (String) -> SlockHomePinnedMemberItem? = { null },
    mode: SlockSidebarSortMode
): List<SlockHomePinnedRow> {
    val byId: Map<String, SlockHomeConversationItem> = buildMap {
        channels.forEach { put(it.id, it) }
        directMessages.forEach { put(it.id, it) }
        directMessages.forEach { dm ->
            if (dm.peerType.equals("agent", ignoreCase = true) || dm.peerType.equals("human", ignoreCase = true)) {
                dm.presenceMemberId?.takeIf { it.isNotBlank() }?.let { put(it, dm) }
            }
        }
    }
    val typedRows =
        typedPinnedItems.mapNotNull { item ->
            val kind = item.kind.trim().lowercase()
            val id = item.id.trim()
            when (kind) {
                "channel" ->
                    byId[id]?.let { SlockHomePinnedRow(key = "channel:$id", conversation = it) }
                "agent", "human" -> {
                    val conversation =
                        directMessages.firstOrNull { dm ->
                            dm.presenceMemberId == id && dm.peerType.equals(kind, ignoreCase = true)
                        } ?: byId[id]?.takeIf { it.isDirectMessageConversation() }
                    if (conversation != null) {
                        SlockHomePinnedRow(key = "$kind:$id", conversation = conversation)
                    } else {
                        pinnedMemberProfileResolver(id)
                            ?.takeIf { it.identity.kind.memberKindWireValue() == kind }
                            ?.let { member -> SlockHomePinnedRow(key = "$kind:$id", member = member) }
                    }
                }
                else -> null
            }
        }.distinctBy { row -> row.conversation?.id ?: row.member?.identity?.memberId?.value ?: row.key }
    if (typedRows.isNotEmpty()) {
        return typedRows.sortedPinnedRows(mode)
    }
    if (pinnedIds.isEmpty() && pinnedAgentIds.isEmpty()) return emptyList()
    val allPinnedIds = (pinnedIds + pinnedAgentIds).distinct()
    val pinnedSet = allPinnedIds.toSet()
    val existingPinned = allPinnedIds.mapNotNull { byId[it] }.distinctBy { it.id }
    return when (mode) {
        SlockSidebarSortMode.Manual -> {
            val ordered = LinkedHashMap<String, SlockHomePinnedRow>()
            pinnedOrder.forEach { id ->
                byId[id]?.takeIf { id in pinnedSet }?.let { ordered.put(it.id, SlockHomePinnedRow(key = id, conversation = it)) }
            }
            existingPinned.forEach { item ->
                if (!ordered.containsKey(item.id)) {
                    ordered.put(item.id, SlockHomePinnedRow(key = item.id, conversation = item))
                }
            }
            ordered.values.toList()
        }
        SlockSidebarSortMode.Recent ->
            existingPinned.sortedWith(
                compareByDescending<SlockHomeConversationItem> {
                    parseSlockTimestampEpochMillisOrNull(it.lastMessageAt ?: it.createdAt) ?: Long.MIN_VALUE
                }.thenBy { it.name.trim().lowercase() }.thenBy { it.id }
            ).map { SlockHomePinnedRow(key = it.id, conversation = it) }
        SlockSidebarSortMode.Az ->
            existingPinned.sortedWith(
                compareBy<SlockHomeConversationItem> { it.name.trim().lowercase() }.thenBy { it.id }
            ).map { SlockHomePinnedRow(key = it.id, conversation = it) }
    }
}

private fun List<SlockHomePinnedRow>.sortedPinnedRows(mode: SlockSidebarSortMode): List<SlockHomePinnedRow> =
    when (mode) {
        SlockSidebarSortMode.Manual -> this
        SlockSidebarSortMode.Recent ->
            sortedWith(
                compareByDescending<SlockHomePinnedRow> {
                    parseSlockTimestampEpochMillisOrNull(it.sortTimestamp) ?: Long.MIN_VALUE
                }.thenBy { it.sortLabel.trim().lowercase() }.thenBy { it.key }
            )
        SlockSidebarSortMode.Az ->
            sortedWith(compareBy<SlockHomePinnedRow> { it.sortLabel.trim().lowercase() }.thenBy { it.key })
    }
@Composable
private fun HomeTitlebar(
    serverName: String,
    hasOtherServerUnread: Boolean,
    notifications: List<SlockHomeNotificationItem>,
    refreshing: Boolean,
    topSafeArea: Dp,
    onServerBadgeClick: () -> Unit,
    onBlankClick: (() -> Unit)? = null,
    onNotificationToggle: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().height(topSafeArea + SlockSize.PageHeaderHeight).background(SlockColor.BrandYellow)) {
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack).align(Alignment.BottomCenter))
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .padding(top = topSafeArea)
                    .height(SlockSize.PageHeaderContentHeight)
                    .padding(horizontal = SlockSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.clickable(onClick = onServerBadgeClick)) {
                SlockLogoBadge(
                    text = serverName,
                    showDropdown = true,
                    tilted = true,
                    unread = hasOtherServerUnread,
                    textStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 24.sp)
                )
            }
            val blankModifier =
                if (onBlankClick != null) {
                    Modifier.weight(1f).height(SlockSize.PageHeaderContentHeight).clickable(onClick = onBlankClick)
                } else {
                    Modifier.weight(1f)
                }
            Spacer(blankModifier)
            if (refreshing) {
                SlockHeaderRefreshIndicator()
            }
            HomeNotificationCenter(
                notifications = notifications,
                onToggle = onNotificationToggle
            )
        }
    }
}

@Composable
private fun SlockLogoBadge(
    text: String,
    modifier: Modifier = Modifier,
    showDropdown: Boolean = false,
    tilted: Boolean = true,
    unread: Boolean = false,
    textStyle: TextStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 24.sp)
) {
    if (tilted) {
        SlockTiltedLogoBadge(text = text, modifier = modifier, showDropdown = showDropdown, unread = unread, textStyle = textStyle)
        return
    }

    Box(modifier = modifier.padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier = Modifier.background(SlockColor.TextBlack).border(SlockStroke.Default, SlockColor.BorderBlack).padding(horizontal = 16.dp, vertical = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, color = SlockColor.BrandYellow, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            if (showDropdown) {
                Spacer(Modifier.width(SlockSpacing.Xs))
                SlockLucideIcon(SlockLucideIconName.ChevronDown, Modifier.size(18.dp), color = SlockColor.BrandYellowArgb)
            }
        }
    }
}

@Composable
private fun SlockTiltedLogoBadge(
    text: String,
    modifier: Modifier = Modifier,
    showDropdown: Boolean = false,
    unread: Boolean = false,
    textStyle: TextStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 24.sp)
) {
    // Render the chrome as final vector geometry, then tilt the complete
    // foreground as one layer so text, icon, and unread marker share the angle.
    key(text, showDropdown) {
        Box(modifier = modifier) {
            TiltedBadgeChrome(modifier = Modifier.matchParentSize())
            Box(modifier = Modifier.padding(end = HomeSmallShadowOffset, bottom = HomeSmallShadowOffset)) {
                Box(modifier = Modifier.rotate(HomeServerBadgeTiltDegrees)) {
                    com.tencent.kuikly.compose.foundation.layout.Row(
                        modifier = Modifier.padding(
                            horizontal = HomeServerBadgeHorizontalInset,
                            vertical = HomeServerBadgeVerticalInset
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text, color = SlockColor.BrandYellow, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                        if (showDropdown) {
                            Spacer(Modifier.width(HomeServerBadgeContentGap))
                            SlockLucideIcon(
                                SlockLucideIconName.ChevronDown,
                                Modifier.size(HomeServerBadgeChevronSize),
                                color = SlockColor.BrandYellowArgb
                            )
                        }
                    }
                    if (unread) ServerUnreadMarker(modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }
}

@Composable
private fun TiltedBadgeChrome(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val layers = homeServerBadgeChromeLayers(
            widthPx = size.width,
            heightPx = size.height,
            shadowOffsetPx = HomeSmallShadowOffset.toPx()
        )
        drawPath(path = homeServerBadgePath(layers.shadowCorners), color = SlockColor.ShadowBlack)
        drawPath(path = homeServerBadgePath(layers.mainCorners), color = SlockColor.TextBlack)
    }
}

private fun homeServerBadgePath(corners: List<Offset>): Path =
    Path().apply {
        moveTo(corners[0].x, corners[0].y)
        lineTo(corners[1].x, corners[1].y)
        lineTo(corners[2].x, corners[2].y)
        lineTo(corners[3].x, corners[3].y)
        close()
    }

internal data class HomeServerBadgeChromeLayers(
    val mainCorners: List<Offset>,
    val shadowCorners: List<Offset>
)

internal fun homeServerBadgeChromeLayers(
    widthPx: Float,
    heightPx: Float,
    shadowOffsetPx: Float
): HomeServerBadgeChromeLayers {
    val safeOffset = shadowOffsetPx.coerceAtLeast(0f)
    val chromeWidth = (widthPx - safeOffset).coerceAtLeast(1f)
    val chromeHeight = (heightPx - safeOffset).coerceAtLeast(1f)
    val mainCorners = homeServerBadgeTiltCorners(chromeWidth, chromeHeight)
    return HomeServerBadgeChromeLayers(
        mainCorners = mainCorners,
        shadowCorners = mainCorners.map { corner ->
            Offset(x = corner.x + safeOffset, y = corner.y + safeOffset)
        }
    )
}

internal fun homeServerBadgeTiltCorners(
    widthPx: Float,
    heightPx: Float,
    tiltDegrees: Float = HomeServerBadgeTiltDegrees
): List<Offset> {
    val safeWidth = widthPx.coerceAtLeast(1f)
    val safeHeight = heightPx.coerceAtLeast(1f)
    val angle = (tiltDegrees * PI / 180.0).toFloat()
    val cosAngle = cos(angle)
    val sinAngle = sin(angle)
    val sinMagnitude = abs(sinAngle)
    val denominator = (cosAngle * cosAngle - sinMagnitude * sinMagnitude).coerceAtLeast(0.01f)
    val rectWidth = ((safeWidth * cosAngle - safeHeight * sinMagnitude) / denominator).coerceAtLeast(1f)
    val rectHeight = ((safeHeight * cosAngle - safeWidth * sinMagnitude) / denominator).coerceAtLeast(1f)
    val centerX = safeWidth / 2f
    val centerY = safeHeight / 2f
    val halfWidth = rectWidth / 2f
    val halfHeight = rectHeight / 2f
    return listOf(
        rotateHomeBadgePoint(-halfWidth, -halfHeight, cosAngle, sinAngle, centerX, centerY),
        rotateHomeBadgePoint(halfWidth, -halfHeight, cosAngle, sinAngle, centerX, centerY),
        rotateHomeBadgePoint(halfWidth, halfHeight, cosAngle, sinAngle, centerX, centerY),
        rotateHomeBadgePoint(-halfWidth, halfHeight, cosAngle, sinAngle, centerX, centerY)
    )
}

private fun rotateHomeBadgePoint(
    x: Float,
    y: Float,
    cosAngle: Float,
    sinAngle: Float,
    centerX: Float,
    centerY: Float
): Offset =
    Offset(
        x = centerX + x * cosAngle - y * sinAngle,
        y = centerY + x * sinAngle + y * cosAngle
    )

@Composable
private fun ServerUnreadMarker(modifier: Modifier = Modifier) {
    SlockStatusIndicator(
        color = SlockColor.AccentPink,
        size = SlockStatusIndicatorTokens.ServerUnreadSize,
        modifier = modifier.offset(x = 4.dp, y = (-4).dp)
    )
}

@Composable
private fun HomeNotificationCenter(notifications: List<SlockHomeNotificationItem>, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val visibleNotifications = remember(notifications) { notifications.visibleHomeNotifications() }
    if (visibleNotifications.isEmpty()) return
    val activeKind = visibleNotifications.first().kind
    Box(modifier = modifier.size(HomeNotificationTriggerSlotSize)) {
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = HomeSmallShadowOffset, bottom = HomeSmallShadowOffset)) {
            Box(
                modifier = Modifier
                    .size(HomeNotificationTriggerSize)
                    .offset(x = HomeSmallShadowOffset, y = HomeSmallShadowOffset)
                    .background(SlockColor.ShadowBlack)
            )
            Button(
                onClick = onToggle,
                modifier =
                    Modifier
                        .size(HomeNotificationTriggerSize)
                        .background(activeKind.notificationColor())
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
            ) {
                SlockLucideIcon(visibleNotifications.homeNotificationTriggerIconName(), Modifier.size(16.dp))
            }
        }
        ServerUnreadMarker(Modifier.align(Alignment.TopEnd))
    }
}

private val HomeNotificationTriggerSize = 32.dp
private val HomeNotificationTriggerSlotSize = HomeNotificationTriggerSize + HomeSmallShadowOffset
private val HomeNotificationPopoverTopGap = 4.dp
internal fun homeNotificationPopoverTopOffset(topSafeArea: Dp): Dp =
    topSafeArea + SlockSize.PageHeaderHeight + HomeNotificationPopoverTopGap

internal fun shouldDismissHomeNotificationCenterForTabSelection(
    expanded: Boolean,
    selectedTabKey: String,
    targetTabKey: String
): Boolean =
    expanded && selectedTabKey != targetTabKey

internal fun List<SlockHomeNotificationItem>.visibleHomeNotifications(): List<SlockHomeNotificationItem> =
    filter { notification ->
        notification.title.isNotBlank() || notification.body.isNotBlank()
    }

internal fun List<SlockHomeNotificationItem>.homeAppUpdateBannerNotification(): SlockHomeNotificationItem? =
    visibleHomeNotifications().firstOrNull { it.id == APP_UPDATE_HOME_NOTIFICATION_ID }

internal fun List<SlockHomeNotificationItem>.nonAppUpdateHomeNotifications(): List<SlockHomeNotificationItem> =
    visibleHomeNotifications().filterNot { it.id == APP_UPDATE_HOME_NOTIFICATION_ID }

@Composable
private fun HomeAppUpdateBanner(
    notification: SlockHomeNotificationItem,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(32.dp).background(SlockColor.BrandYellow),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon(
                SlockLucideIconName.CircleArrowUp,
                modifier = Modifier.size(17.dp),
                color = "#FF000000"
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = notification.title.ifBlank { notification.body },
                style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (notification.body.isNotBlank() && notification.title.isNotBlank()) {
                Text(
                    text = notification.body,
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = stringResource(MR.strings.app_update_view),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
        SlockLucideIcon(SlockLucideIconName.ChevronRight, modifier = Modifier.size(16.dp), color = "#FF000000")
    }
    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
}

internal fun List<SlockHomeNotificationItem>.homeNotificationTriggerIconName(): SlockLucideIconName =
    visibleHomeNotifications()
        .firstOrNull()
        ?.let { notification -> notification.icon.notificationIconName(notification.kind) }
        ?: SlockLucideIconName.Bell

@Composable
private fun HomeNotificationPopover(notifications: List<SlockHomeNotificationItem>, onView: (SlockHomeNotificationItem) -> Unit, onDismiss: (SlockHomeNotificationItem) -> Unit, modifier: Modifier = Modifier) {
    HardShadowPanel(modifier = modifier, minWidth = 320.dp, maxWidth = 320.dp) {
        com.tencent.kuikly.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(MR.strings.home_notification_center_title), style = SlockType.SectionLabel, color = SlockColor.TextMutedGray, maxLines = 1)
            Text(
                if (notifications.size == 1) {
                    MR.strings.home_notification_center_item_count_one.format(listOf(notifications.size)).localized()
                } else {
                    MR.strings.home_notification_center_item_count_many.format(listOf(notifications.size)).localized()
                },
                style = SlockType.Button,
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
        }
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        notifications.forEachIndexed { index, notification ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
            }
            HomeNotificationRow(notification, { onView(notification) }, { onDismiss(notification) })
        }
    }
}

@Composable
private fun HomeNotificationRow(notification: SlockHomeNotificationItem, onView: () -> Unit, onDismiss: () -> Unit) {
    val title = notification.title.ifBlank { notification.body }
    val body = if (notification.title.isBlank()) "" else notification.body
    com.tencent.kuikly.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().padding(SlockSpacing.Md), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        Box(modifier = Modifier.size(24.dp).background(notification.kind.notificationColor()).border(SlockStroke.Default, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
            SlockLucideIcon(notification.icon.notificationIconName(notification.kind), Modifier.size(16.dp))
        }
        Column(modifier = Modifier.width(HomeNotificationTextWidth), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
            Text(title, style = SlockType.Button, color = SlockColor.TextBlack, overflow = TextOverflow.Ellipsis, maxLines = 3)
            if (body.isNotBlank()) {
                Text(body, style = SlockType.Button, color = SlockColor.TextBlack, overflow = TextOverflow.Ellipsis, maxLines = 3)
            }
            com.tencent.kuikly.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                if (notification.actionLabel.isNotBlank()) {
                    HomeNotificationActionButton(
                        notification.actionLabel,
                        SlockColor.AccentPink,
                        onView,
                        shadowOffset = notification.primaryActionShadowOffset()
                    )
                }
                HomeNotificationActionButton(stringResource(MR.strings.common_dismiss), SlockColor.SurfaceWhite, onDismiss)
            }
        }
    }
}

private val HomeNotificationTextWidth = 260.dp
private val HomeNotificationActionShadowOffset = 2.dp
private val HomeNotificationActionActiveShadowOffset = HomeNotificationActionShadowOffset * 2

internal fun SlockHomeNotificationIcon.notificationIconName(kind: SlockHomeNotificationKind): SlockLucideIconName =
    when (this) {
        SlockHomeNotificationIcon.WifiOff -> SlockLucideIconName.TriangleAlert
        SlockHomeNotificationIcon.CircleArrowUp -> SlockLucideIconName.CircleArrowUp
        SlockHomeNotificationIcon.Info -> SlockLucideIconName.Info
        SlockHomeNotificationIcon.Default ->
            when (kind) {
                SlockHomeNotificationKind.Warning -> SlockLucideIconName.TriangleAlert
                SlockHomeNotificationKind.Info -> SlockLucideIconName.Info
                SlockHomeNotificationKind.Error -> SlockLucideIconName.TriangleAlert
            }
    }

internal fun SlockHomeNotificationItem.primaryActionShadowOffset(): Dp =
    if (isAppUpdateActionActive()) {
        HomeNotificationActionActiveShadowOffset
    } else {
        HomeNotificationActionShadowOffset
    }

private fun SlockHomeNotificationItem.isAppUpdateActionActive(): Boolean =
    id == APP_UPDATE_HOME_NOTIFICATION_ID &&
        (primaryActionActive || actionLabel.trim().lowercase() in setOf("downloading", "install"))

@Composable
private fun HomeNotificationActionButton(text: String, color: Color, onClick: () -> Unit, shadowOffset: Dp = HomeNotificationActionShadowOffset) {
    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
        backgroundColor = color,
        minWidth = 72.dp,
        minHeight = 32.dp,
        shadowOffset = shadowOffset,
        textStyle = HomeNotificationActionButtonTextStyle
    )
}

internal val HomeNotificationActionButtonTextStyle =
    SlockType.Caption.withSlockFontWeight(FontWeight.Bold)

private fun SlockHomeNotificationKind.notificationColor(): Color = when (this) {
    SlockHomeNotificationKind.Warning -> SlockColor.AccentOrange
    SlockHomeNotificationKind.Info -> SlockColor.BrandYellow
    SlockHomeNotificationKind.Error -> SlockColor.AccentRed
}

@Composable
internal fun HomeInlineErrorCard(
    error: SlockHomeInlineError,
    modifier: Modifier = Modifier,
    onAction: () -> Unit
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.Top
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.TriangleAlert,
                    modifier = Modifier.size(18.dp),
                    color = SlockColor.TextBlackArgb
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = error.title,
                        style = SlockType.ButtonBold,
                        color = SlockColor.TextBlack,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = error.detail,
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = error.code,
                        style = SlockType.Caption,
                        color = SlockColor.AccentRed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            SlockBrutalActionButton(
                label = error.actionLabel,
                onClick = onAction,
                leadingIcon = SlockLucideIconName.RefreshCw,
                backgroundColor = SlockColor.SurfaceWhite
            )
        }
    }
}

@Composable
private fun ServerSwitcherPanel(
    servers: List<SlockServerSwitcherItem>,
    currentServerId: String,
    pendingServerId: String?,
    prefersChineseCommunity: Boolean,
    labels: SlockHomeShellLabels,
    onServerSelected: (String) -> Unit,
    onServerOrderChange: (List<String>) -> Unit,
    onCreateServerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val joinCommunityActions =
        homeServerSwitcherJoinCommunityActions(
            servers = servers,
            labels = labels,
            includeChineseCommunity = prefersChineseCommunity
        )
    val incomingOrder = servers.map { it.id }
    var order by remember { mutableStateOf(incomingOrder) }
    var pendingIncomingOrder by remember { mutableStateOf<List<String>?>(null) }
    val serversById = remember(servers) { servers.associateBy { it.id } }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val handleWidthPx = with(density) { HomeServerSwitcherDragHandleWidth.toPx() }
    val rowStepPx = with(density) { HomeServerSwitcherRowMinHeight.toPx().coerceAtLeast(1f) }
    val edgeScrollZonePx = with(density) { SlockSortEdgeScrollZone.toPx() }
    val edgeScrollStepPx = with(density) { SlockSortEdgeScrollStep.toPx() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedStartIndex by remember { mutableStateOf(0) }
    var pointerViewportY by remember { mutableStateOf(0f) }
    var targetIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(incomingOrder) {
        if (draggedId == null) {
            order = incomingOrder
            pendingIncomingOrder = null
        } else {
            pendingIncomingOrder = incomingOrder
        }
    }
    LaunchedEffect(draggedId) {
        if (draggedId == null) {
            pendingIncomingOrder?.let { nextOrder ->
                order = nextOrder
                pendingIncomingOrder = null
            }
        }
    }

    fun visibleServerRowBounds(): List<SlockSortRowBounds> =
        listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val key = info.key as? String ?: return@mapNotNull null
            val id = key.removePrefix("server-").takeIf { key.startsWith("server-") } ?: return@mapNotNull null
            val index = order.indexOf(id).takeIf { it >= 0 } ?: return@mapNotNull null
            SlockSortRowBounds(index = index, top = info.offset.toFloat(), size = info.size.toFloat())
        }

    fun recomputeDrag() {
        if (draggedId == null) return
        targetIndex = resolveSortTargetIndex(pointerViewportY, visibleServerRowBounds(), order.lastIndex)
    }

    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        runSlockSortAutoScrollLoop(
            listState = listState,
            isDragging = { draggedId != null },
            pointerViewportY = { pointerViewportY },
            edgeScrollZonePx = edgeScrollZonePx,
            edgeScrollStepPx = edgeScrollStepPx
        ) { delta, consumed ->
            recomputeDrag()
            slockLogInfo(
                "SlockServerSwitcher",
                "autoscroll delta=$delta consumed=$consumed pointerY=$pointerViewportY " +
                    "target=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
            )
        }
    }

    HardShadowPanel(modifier = modifier, minWidth = 276.dp, maxWidth = 328.dp) {
        if (servers.isEmpty()) {
            Text(labels.noServersAvailable, style = SlockType.Caption, color = SlockColor.TextMutedGray, modifier = Modifier.padding(SlockSpacing.Md))
        } else {
            val serverListMaxHeight = homeServerSwitcherServerListMaxHeight(servers.size)
            val serverListModifier =
                if (serverListMaxHeight == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth().height(serverListMaxHeight)
                }
            val serverLazyListModifier =
                if (serverListMaxHeight == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxSize()
                }
            Box(modifier = serverListModifier.clipToBounds()) {
                LazyColumn(
                    state = listState,
                    modifier =
                        serverLazyListModifier
                            .pointerInput(order) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { pos ->
                                        if (pos.x < size.width - handleWidthPx) return@detectDragGesturesAfterLongPress
                                        val target = resolveSortTargetIndex(pos.y, visibleServerRowBounds(), order.lastIndex)
                                        val id = order.getOrNull(target)
                                        if (id != null) {
                                            draggedId = id
                                            draggedStartIndex = target
                                            pointerViewportY = pos.y
                                            targetIndex = target
                                            slockLogInfo("SlockServerSwitcher", "dragStart id=$id index=$target size=${order.size}")
                                        }
                                    },
                                    onDragEnd = {
                                        val active = draggedId
                                        var changed = false
                                        if (active != null && targetIndex >= 0 && targetIndex != draggedStartIndex) {
                                            changed = true
                                            val next = order.moveItem(active, targetIndex)
                                            order = next
                                            onServerOrderChange(next)
                                        }
                                        slockLogInfo("SlockServerSwitcher", "dragEnd changed=$changed from=$draggedStartIndex to=$targetIndex")
                                        draggedId = null
                                        targetIndex = -1
                                    },
                                    onDragCancel = {
                                        slockLogInfo("SlockServerSwitcher", "dragCancel id=$draggedId")
                                        draggedId = null
                                        targetIndex = -1
                                    }
                                ) { change, dragAmount ->
                                    if (draggedId != null) {
                                        change.consume()
                                        pointerViewportY += dragAmount.y
                                        recomputeDrag()
                                    }
                                }
                            },
                    beyondBoundsItemCount = homeServerSwitcherBeyondBoundsItemCount(order.size),
                    userScrollEnabled = draggedId == null
                ) {
                    items(order.size, key = { index -> "server-${order[index]}" }) { index ->
                        val server = serversById[order[index]] ?: return@items
                        if (draggedId == server.id) {
                            Spacer(Modifier.fillMaxWidth().height(HomeServerSwitcherRowMinHeight))
                        } else {
                            ServerSwitcherRow(
                                server,
                                server.id == currentServerId,
                                server.id == pendingServerId,
                                dragHandleVisible = order.size > 1,
                                onClick = if (draggedId == null) ({ onServerSelected(server.id) }) else null
                            )
                        }
                    }
                }
                val active = draggedId
                if (active != null && targetIndex >= 0) {
                    val bounds = visibleServerRowBounds()
                    val targetRow = bounds.firstOrNull { it.index == targetIndex }
                    val lineY =
                        when {
                            targetRow != null && targetIndex >= draggedStartIndex -> targetRow.top + targetRow.size
                            targetRow != null -> targetRow.top
                            else -> bounds.maxByOrNull { it.index }?.let { it.top + it.size }
                        }
                    if (lineY != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .offset { IntOffset(0, lineY.roundToInt()) }
                                    .height(2.dp)
                                    .background(SlockColor.AccentPink)
                        )
                    }
                    serversById[active]?.let { server ->
                        ServerSwitcherRow(
                            server,
                            server.id == currentServerId,
                            server.id == pendingServerId,
                            dragHandleVisible = true,
                            pressed = true,
                            onClick = null,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .offset {
                                        val info = listState.layoutInfo
                                        IntOffset(
                                            0,
                                            resolveSortDraggedRowTop(
                                                pointerY = pointerViewportY,
                                                viewportStart = info.viewportStartOffset.toFloat(),
                                                viewportEnd = info.viewportEndOffset.toFloat(),
                                                rowSize = rowStepPx
                                            ).roundToInt()
                                        )
                                    }
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        joinCommunityActions.forEach { action ->
            ServerSwitcherActionRow(action.label, onCreateServerClick)
        }
        ServerSwitcherActionRow(labels.switchOrCreateServer, onCreateServerClick)
    }
}

@Composable
private fun HardShadowPanel(
    modifier: Modifier = Modifier,
    minWidth: Dp,
    maxWidth: Dp,
    content: @Composable com.tencent.kuikly.compose.foundation.layout.ColumnScope.() -> Unit
) {
    SlockBrutalPanel(
        modifier = modifier,
        minWidth = minWidth,
        maxWidth = maxWidth,
        shadowOffset = HomePanelShadowOffset,
        content = content
    )
}

@Composable
private fun ServerSwitcherRow(
    server: SlockServerSwitcherItem,
    selected: Boolean,
    pending: Boolean,
    dragHandleVisible: Boolean = false,
    pressed: Boolean = false,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val clickModifier = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier
    com.tencent.kuikly.compose.foundation.layout.Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = HomeServerSwitcherRowMinHeight)
                .background(
                    when {
                        selected || pressed -> SlockColor.BrandYellow
                        else -> SlockColor.SurfaceWhite
                    }
                )
                .then(clickModifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            when {
                pending -> Text("...", style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(lineHeight = 14.sp), color = SlockColor.TextBlack, maxLines = 1, textAlign = TextAlign.Center)
                selected -> SlockLucideIcon(SlockLucideIconName.Check, Modifier.size(14.dp))
                else -> Spacer(Modifier.size(14.dp))
            }
        }
        ServerSwitcherAvatarTile(server)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(server.name, style = SlockType.Button, color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "/${server.slug}",
                style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                color = SlockColor.TextBlack.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val badgeLabel =
            homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = server.unreadCount))
                .label
        if (badgeLabel != null) HomeUnreadBadge(badgeLabel)
        if (dragHandleVisible) {
            Box(modifier = Modifier.width(HomeServerSwitcherDragHandleWidth), contentAlignment = Alignment.Center) {
                SlockLucideIcon(
                    HomeServerSwitcherDragHandleIconName,
                    Modifier.size(14.dp),
                    color = SlockColor.TextMutedGrayArgb
                )
            }
        }
    }
}

@Composable
private fun ServerSwitcherAvatarTile(server: SlockServerSwitcherItem) {
    val presentation = server.toServerSwitcherAvatarPresentation()
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .background(SlockColor.TextBlack)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            presentation.fallbackLabel,
            style =
                SlockType.Button.copy(
                    fontFamily = slockMonoFamilyForWeight(FontWeight.Bold),
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal
                ),
            color = SlockColor.BrandYellow,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        presentation.avatarWireValue?.let { source ->
            // The invisible prewarm Image owns its painter. Give the visible row a distinct
            // painter so Kuikly's stateful image node can draw the already-warm cache entry.
            SlockAvatarHttpImage(
                source = source,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

internal data class ServerSwitcherAvatarPresentation(
    val avatarWireValue: String?,
    val fallbackLabel: String
)

internal fun SlockServerSwitcherItem.toServerSwitcherAvatarPresentation(): ServerSwitcherAvatarPresentation =
    ServerSwitcherAvatarPresentation(
        avatarWireValue = avatarUrl?.takeIf { it.isNotBlank() },
        fallbackLabel = name.take(1).uppercase()
    )

@Composable
private fun rememberServerSwitcherAvatarPainters(
    servers: List<SlockServerSwitcherItem>
): Map<String, AsyncImagePainter> {
    val painters = linkedMapOf<String, AsyncImagePainter>()
    servers.forEach { server ->
        val source = server.toServerSwitcherAvatarPresentation().avatarWireValue ?: return@forEach
        key(server.id, source) {
            painters[server.id] = rememberSlockAvatarHttpPainter(source)
        }
    }
    return painters
}

@Composable
private fun ServerSwitcherAvatarPrewarmSurface(
    avatarPainters: Map<String, AsyncImagePainter>
) {
    if (avatarPainters.isEmpty()) return
    Box(
        modifier =
            Modifier
                .size(1.dp)
                .alpha(0f)
                .clipToBounds()
    ) {
        avatarPainters.values.forEach { painter ->
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ServerSwitcherActionRow(label: String, onClick: () -> Unit) {
    com.tencent.kuikly.compose.foundation.layout.Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .background(SlockColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockLucideIcon(SlockLucideIconName.Plus, Modifier.size(14.dp))
        Text(label, style = SlockType.ButtonBold, color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SidebarActivityRow(
    label: String,
    unreadCount: String?,
    totalCount: String?,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    SidebarActionRow(
        icon = SlockLucideIconName.Activity,
        label = label,
        selected = selected,
        modifier = modifier,
        badge = unreadCount,
        trailingText = totalCount,
        onClick = onClick
    )
}

@Composable
private fun SidebarActionRow(icon: SlockLucideIconName, label: String, modifier: Modifier = Modifier, selected: Boolean = false, badge: String? = null, trailingText: String? = null, onClick: (() -> Unit)? = null) {
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    var pressed by remember { mutableStateOf(false) }
    val effectiveInteractionState = when {
        selected -> ChannelRowInteractionState.Selected
        pressed -> ChannelRowInteractionState.Pressed
        isHovered -> ChannelRowInteractionState.Hovered
        else -> ChannelRowInteractionState.Default
    }
    val rowBackground = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Selected -> SlockColor.AccentPink
        ChannelRowInteractionState.Hovered -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Pressed -> SlockColor.SurfaceWhite
    }
    val borderColor = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Hovered, ChannelRowInteractionState.Pressed, ChannelRowInteractionState.Selected, ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    val clickModifier =
        if (onClick != null) {
            val currentOnClick by rememberUpdatedState(onClick)
            Modifier.pointerInput(icon, label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = { currentOnClick?.invoke() }
                )
            }
        } else {
            Modifier
        }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.padding(end = ChannelRowShadowOffset, bottom = ChannelRowShadowOffset) else Modifier)
            .then(clickModifier)
            .hoverable(hoverInteractionSource)
    ) {
        if (selected || pressed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SidebarActionRowHeight)
                    .offset(x = ChannelRowShadowOffset, y = ChannelRowShadowOffset)
                    .background(SlockColor.ShadowBlack)
            )
        }
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SidebarActionRowHeight)
                    .background(rowBackground)
                    .border(SlockStroke.Default, borderColor)
                    .padding(horizontal = SidebarRowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(SidebarActionIconBoxSize), contentAlignment = Alignment.Center) { SlockLucideIcon(icon, Modifier.size(SidebarActionIconSize)) }
            Text(label, style = SlockType.Button, color = SlockColor.TextBlack, modifier = Modifier.padding(start = SidebarActionLabelGap).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) CountPill(badge, SlockColor.AccentPink) else if (trailingText != null) SidebarTrailingNumberText(trailingText, color = SlockColor.TextBlack)
        }
    }
}

internal fun formatHomeSavedCount(savedCount: Int): String? =
    formatHomeSidebarBadgeCount(savedCount)

@Composable
private fun SidebarTrailingNumberText(text: String, color: Color) {
    Box(modifier = Modifier.height(SidebarActionIconBoxSize), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 10.sp, lineHeight = 10.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun Modifier.homeRowAnchorCapture(
    enabled: Boolean,
    anchorRef: SlockMutableOffsetRef
): Modifier =
    if (enabled) {
        onGloballyPositioned { coordinates ->
            val pos = coordinates.positionInRoot()
            anchorRef.update(Offset(x = pos.x, y = pos.y))
        }
    } else {
        this
    }

private fun homeRowLongPressAnchor(
    rowTopLeft: SlockMutableOffsetRef,
    pressOffset: Offset,
    rowHeightPx: Int
): Offset {
    val base = rowTopLeft.value.takeIf { rowTopLeft.hasValue } ?: Offset.Zero
    return Offset(
        x = base.x + pressOffset.x,
        y = base.y + rowHeightPx.toFloat()
    )
}

@Composable
private fun ChannelRow(name: String, modifier: Modifier = Modifier, positionPrefix: String? = null, symbol: String = "#", avatarLabel: String? = null, avatarUrl: String? = null, avatarWireValue: String? = avatarUrl, presence: SlockPresence? = null, showPresence: Boolean = true, badge: String? = null, badgeTone: HomeSidebarBadgeTone = HomeSidebarBadgeTone.Loud, hasDraft: Boolean = false, muted: Boolean = false, joined: Boolean = true, interactionIdentity: String = "channel:$symbol:$name", interactionState: ChannelRowInteractionState = ChannelRowInteractionState.Default, captureLongPressAnchor: Boolean = true, onClick: (() -> Unit)? = null, onLongPress: ((Offset) -> Unit)? = null, onPress: (() -> Unit)? = null, onPressEnd: (() -> Unit)? = null, dragEnabled: Boolean = false, dragOffsetY: Float = 0f, onDragStart: (() -> Unit)? = null, onDragMove: ((Float) -> Unit)? = null, onDragEnd: (() -> Unit)? = null, onDragCancel: (() -> Unit)? = null, rowDragEnabled: Boolean = false) {
    // #211 review (Cindy / @Android-Developer-2 01:27): hover is
    // detected locally so the visual layer can react without the shell
    // having to track per-row hover. Selected still wins: a currently
    // selected row keeps its white brutal-shadow treatment on hover.
    val hoverInteractionSource = remember(interactionIdentity) { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val selected = interactionState == ChannelRowInteractionState.Selected
    val pressed = interactionState == ChannelRowInteractionState.Pressed
    // #211 review (Cindy): when the row is being pressed but not yet
    // selected, show a light overlay so the user sees the gesture was
    // accepted. Pressed wins over the default / hovered / focused
    // background but never overwrites a real Selected highlight.
    val effectiveInteractionState = when {
        selected -> ChannelRowInteractionState.Selected
        pressed -> ChannelRowInteractionState.Pressed
        isHovered -> ChannelRowInteractionState.Hovered
        else -> interactionState
    }
    val rowBackground = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Selected -> SlockColor.AccentPink
        ChannelRowInteractionState.Hovered -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Pressed -> SlockColor.SurfaceWhite
    }
    val borderColor = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Hovered, ChannelRowInteractionState.Pressed, ChannelRowInteractionState.Selected, ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    val titleColor = homeConversationTitleColor(muted = muted, joined = joined)
    val mutedIndicatorIcon = homeConversationMutedIndicatorIcon(muted)
    val rowTopLeft = remember(interactionIdentity) { SlockMutableOffsetRef() }
    var captureAnchorForPress by remember(interactionIdentity) { mutableStateOf(false) }
    val hoverableModifier = Modifier.hoverable(hoverInteractionSource)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnPressEnd by rememberUpdatedState(onPressEnd)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val rowDragModifier =
        if (rowDragEnabled && dragEnabled && onDragStart != null && onDragMove != null && onDragEnd != null) {
            Modifier.pointerInput(interactionIdentity, rowDragEnabled, dragEnabled) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart?.invoke() },
                    onDragEnd = { currentOnDragEnd?.invoke() },
                    onDragCancel = { (currentOnDragCancel ?: currentOnDragEnd)?.invoke() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnDragMove?.invoke(dragAmount.y)
                }
            }
        } else {
            Modifier
        }
    val dragHandleModifier =
        if (dragEnabled && onDragStart != null && onDragMove != null && onDragEnd != null) {
            Modifier.pointerInput(interactionIdentity, dragEnabled) {
                // Sort mode is explicit, so the handle drags immediately —
                // no long-press gate (task #356: the v2 long-press-drag felt stuck).
                detectDragGestures(
                    onDragStart = { currentOnDragStart?.invoke() },
                    onDragEnd = { currentOnDragEnd?.invoke() },
                    onDragCancel = { (currentOnDragCancel ?: currentOnDragEnd)?.invoke() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnDragMove?.invoke(dragAmount.y)
                }
            }
        } else {
            Modifier
        }
    val rowClickIdentity = interactionIdentity
    val clickModifier = remember(rowClickIdentity, onClick != null, onLongPress != null) {
        if (onLongPress != null) {
            Modifier.pointerInput(rowClickIdentity, true) {
                detectTapGestures(
                    onPress = {
                        captureAnchorForPress = true
                        currentOnPress?.invoke()
                        try {
                            tryAwaitRelease()
                        } finally {
                            captureAnchorForPress = false
                            currentOnPressEnd?.invoke()
                        }
                    },
                    onTap = { currentOnClick?.invoke() },
                    onLongPress = { pressOffset ->
                        val rowHeightPx = this.size.height
                        currentOnLongPress?.invoke(homeRowLongPressAnchor(rowTopLeft, pressOffset, rowHeightPx))
                    }
                )
            }
        } else if (onClick != null) {
            Modifier.pointerInput(rowClickIdentity, false) {
                detectTapGestures(
                    onPress = {
                        currentOnPress?.invoke()
                        try {
                            tryAwaitRelease()
                        } finally {
                            currentOnPressEnd?.invoke()
                        }
                    },
                    onTap = { currentOnClick?.invoke() }
                )
            }
        } else {
            Modifier
        }
    }
    traceMarkdownSection(SlockHomeTraceNames.CHANNEL_ROW_RENDER, name.length) {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                    .homeRowAnchorCapture(captureLongPressAnchor || captureAnchorForPress, rowTopLeft)
                    .then(if (selected) Modifier.padding(end = ChannelRowShadowOffset, bottom = ChannelRowShadowOffset) else Modifier)
                    .then(clickModifier)
                    .then(rowDragModifier)
                    .then(hoverableModifier)
        ) {
        if (selected || pressed) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(ChannelRowHeight)
                        .offset(x = ChannelRowShadowOffset, y = ChannelRowShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
        }
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ChannelRowHeight)
                    .background(rowBackground)
                    .border(SlockStroke.Default, borderColor)
                    .padding(start = ChannelRowHorizontalPadding, end = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (positionPrefix != null) {
                Text(
                    positionPrefix,
                    style = SlockType.Button,
                    color = SlockColor.TextMutedGray,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 22.dp).padding(end = SlockSpacing.Xs)
                )
            }
            Box(modifier = Modifier.size(width = ChannelAvatarSize, height = ChannelAvatarSize), contentAlignment = Alignment.Center) {
                if (avatarLabel != null) {
                    ChannelAvatar(avatarWireValue, avatarLabel, presence, showPresence)
                } else if (symbol == "#") {
                    SlockLucideIcon(
                        "hash",
                        Modifier.size(ChannelSymbolIconSize),
                        color = if (joined) SlockColor.TextBlackArgb else "#75000000"
                    )
                } else if (symbol == "git-branch") {
                    SlockLucideIcon(
                        "git-branch",
                        Modifier.size(ChannelSymbolIconSize),
                        color = if (joined) SlockColor.TextBlackArgb else "#75000000"
                    )
                } else if (symbol == "lock") {
                    SlockLucideIcon(
                        "lock",
                        Modifier.size(ChannelSymbolIconSize),
                        color = if (joined) SlockColor.TextBlackArgb else "#75000000"
                    )
                } else {
                    Text(symbol, style = SlockType.Button, color = SlockColor.TextBlack.copy(alpha = if (joined) 1f else 0.46f), textAlign = TextAlign.Center)
                }
            }
            Row(
                modifier =
                    Modifier
                        .padding(start = SidebarActionLabelGap)
                        .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    name,
                    style = SlockType.Button,
                    color = titleColor,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (mutedIndicatorIcon != null) {
                    Spacer(Modifier.width(ChannelRowMutedIconGap))
                    SlockLucideIcon(
                        mutedIndicatorIcon,
                        Modifier.size(ChannelRowMutedIconSize),
                        color = SlockColor.TextMutedGrayArgb
                    )
                }
            }
            if (badge != null) Spacer(Modifier.width(SlockSpacing.Sm)) else if (hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
            if (hasDraft) SlockLucideIcon(SlockLucideIconName.Pencil, Modifier.size(14.dp), color = SlockColor.TextMutedGrayArgb)
            if (badge != null) {
                if (hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
                when (badgeTone) {
                    HomeSidebarBadgeTone.Loud -> CountPill(badge, SlockColor.AccentPink)
                    HomeSidebarBadgeTone.Quiet -> DimmedUnreadCount(badge)
                }
            }
            if (dragEnabled) {
                Spacer(Modifier.width(SlockSpacing.Xs))
                Box(
                    modifier =
                        Modifier
                            .size(width = 18.dp, height = ChannelAvatarSize)
                            .then(dragHandleModifier),
                    contentAlignment = Alignment.Center
                ) {
                    SlockLucideIcon(
                        "grip-vertical",
                        Modifier.size(14.dp),
                        color = SlockColor.TextMutedGrayArgb
                    )
                }
            }
        }
        }
    }
}

// #372 React parity: outlined-circle + gray-bar skeleton row sized like
// ChannelRow (same height / paddings) so the loaded conversation list
// replaces the skeletons in place. SlockSkeleton is fully static (no
// shimmer), which keeps visual captures deterministic.
@Composable
private fun HomeSidebarSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ChannelRowHeight)
                .padding(start = ChannelRowHorizontalPadding, end = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockSkeleton(modifier = Modifier.size(ChannelAvatarSize), variant = SlockSkeletonVariant.Circle)
        SlockSkeleton(
            modifier =
                Modifier
                    .padding(start = SidebarActionLabelGap)
                    .weight(HomeLoadingSkeletonLineWeight)
                    .height(HomeLoadingSkeletonLineHeight),
            variant = SlockSkeletonVariant.Line
        )
        Spacer(Modifier.weight(1f - HomeLoadingSkeletonLineWeight))
    }
}

@Composable
private fun SlockSidebarSortOverlay(
    title: String,
    items: List<SlockHomeConversationItem>,
    saveLabel: String,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var order by remember(items) { mutableStateOf(items.map { it.id }) }
    val itemsById = remember(items) { items.associateBy { it.id } }
    val density = LocalDensity.current
    val rowStepPx = with(density) { (ChannelRowHeight + 2.dp).toPx().coerceAtLeast(1f) }
    val edgeScrollZonePx = with(density) { SlockSortEdgeScrollZone.toPx() }
    val edgeScrollStepPx = with(density) { SlockSortEdgeScrollStep.toPx() }
    val listState = rememberLazyListState()

    // Content-space drag model. The LazyColumn item order and layout stay FIXED
    // while a row is dragged: the list does NOT reflow. Reflowing every row each
    // frame (the previous approach) was inherently jittery under auto-scroll —
    // the target changed every frame, and the dragged placeholder disposing and
    // recomposing as it scrolled off/back churned the reflow basis and shoved the
    // list (task #356 device feedback). Instead we track the finger's viewport Y,
    // resolve a single drop target against the current visible-row layout, draw a
    // floating copy under the finger + an insertion line at the target, and
    // commit the reorder once on drop.
    var draggedId by remember(items) { mutableStateOf<String?>(null) }
    var draggedStartIndex by remember(items) { mutableStateOf(0) }
    var pointerViewportY by remember(items) { mutableStateOf(0f) }
    var targetIndex by remember(items) { mutableStateOf(-1) }
    // Debounced copy of targetIndex that drives the live position numbers. The
    // raw target changes every frame under auto-scroll, which made the numbers
    // flicker wildly (device feedback: "dragging 6 kept becoming 13"). Numbers
    // only renumber after the target has been stable for a beat; the insertion
    // line and the actual drop keep using the raw, immediate target.
    var settledTargetIndex by remember(items) { mutableStateOf(-1) }
    LaunchedEffect(draggedId, targetIndex) {
        if (draggedId == null) {
            settledTargetIndex = -1
        } else if (settledTargetIndex < 0) {
            settledTargetIndex = targetIndex
        } else if (targetIndex != settledTargetIndex) {
            delay(200)
            settledTargetIndex = targetIndex
        }
    }

    fun visibleSortRowBounds(): List<SlockSortRowBounds> =
        listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val key = info.key as? String ?: return@mapNotNull null
            val id = key.removePrefix("sort-").takeIf { key.startsWith("sort-") } ?: return@mapNotNull null
            val index = order.indexOf(id).takeIf { it >= 0 } ?: return@mapNotNull null
            SlockSortRowBounds(index = index, top = info.offset.toFloat(), size = info.size.toFloat())
        }

    fun recomputeDrag() {
        if (draggedId == null) return
        targetIndex = resolveSortTargetIndex(pointerViewportY, visibleSortRowBounds(), order.lastIndex)
    }

    // Continuous auto-scroll while the finger sits in an edge zone — required
    // for long sections (140+ channels): the user holds near the edge and the
    // list keeps scrolling even without further finger movement. The drag
    // gesture lives on the CONTAINER (below), not on a list row, so scrolling
    // the dragged row off-screen no longer disposes the row and cancels the
    // gesture (task #356 device feedback: auto-release + jitter).
    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        runSlockSortAutoScrollLoop(
            listState = listState,
            isDragging = { draggedId != null },
            pointerViewportY = { pointerViewportY },
            edgeScrollZonePx = edgeScrollZonePx,
            edgeScrollStepPx = edgeScrollStepPx
        ) { delta, consumed ->
            recomputeDrag()
            slockLogInfo(
                "SlockHomeSort",
                "autoscroll delta=$delta consumed=$consumed pointerY=$pointerViewportY " +
                    "target=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
            )
        }
    }

    // Live position numbers from the SETTLED preview: where each row would sit
    // if the drag dropped at the debounced target. O(n) rebuild only when the
    // settled target moves (not per frame), O(1) lookup per row.
    val previewPositionById: Map<String, Int> =
        draggedId?.takeIf { settledTargetIndex >= 0 }?.let { active ->
            order.moveItem(active, settledTargetIndex)
                .withIndex()
                .associate { (i, id) -> id to i + 1 }
        } ?: emptyMap()

    SlockUnifiedDialog(
        title = title,
        onDismiss = onDismiss,
        dismissEnabled = draggedId == null,
        contentSpacing = 12.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clipToBounds()) {
            // Container-level long-press drag: identify the row under the finger
            // at long-press, then track the finger. Because the gesture is on
            // the container it survives list scrolling and preview reordering.
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(order) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    val rows = visibleSortRowBounds()
                                    val target = resolveSortTargetIndex(pos.y, rows, order.lastIndex)
                                    val id = order.getOrNull(target)
                                    if (id != null) {
                                        draggedId = id
                                        draggedStartIndex = target
                                        pointerViewportY = pos.y
                                        targetIndex = target
                                        slockLogInfo(
                                            "SlockHomeSort",
                                            "dragStart id=$id index=$target posY=${pos.y} size=${order.size}"
                                        )
                                    }
                                },
                                onDragEnd = {
                                    val active = draggedId
                                    var changed = false
                                    if (active != null && targetIndex >= 0 && targetIndex != draggedStartIndex) {
                                        changed = true
                                        order = order.moveItem(active, targetIndex)
                                    }
                                    slockLogInfo(
                                        "SlockHomeSort",
                                        "dragEnd changed=$changed from=$draggedStartIndex to=$targetIndex"
                                    )
                                    draggedId = null
                                    targetIndex = -1
                                },
                                onDragCancel = {
                                    slockLogInfo("SlockHomeSort", "dragCancel id=$draggedId")
                                    draggedId = null
                                    targetIndex = -1
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                if (draggedId != null) {
                                    pointerViewportY += dragAmount.y
                                    recomputeDrag()
                                }
                            }
                        },
                verticalArrangement = Arrangement.spacedBy(2.dp),
                userScrollEnabled = draggedId == null
            ) {
                items(order.size, key = { index -> "sort-${order[index]}" }) { index ->
                    val itemId = order[index]
                    val item = itemsById[itemId] ?: return@items
                    val isDirectMessage = item.isDirectMessageConversation()
                    // The list never reflows during a drag. The dragged row keeps
                    // its slot as an invisible placeholder (the visible copy is the
                    // floating overlay); every other row stays exactly in place, so
                    // there is no per-frame reflow to jitter under auto-scroll.
                    if (draggedId == itemId) {
                        Spacer(Modifier.fillMaxWidth().height(ChannelRowHeight))
                    } else {
                        // 1-based position before the row icon: live from the settled
                        // preview during a drag (debounced, so no frantic jumping),
                        // fixed slot index otherwise.
                        val position = previewPositionById[itemId] ?: (index + 1)
                        ChannelRow(
                            name = item.name,
                            positionPrefix = "$position",
                            symbol = if (isDirectMessage) "#" else item.homeChannelSymbol(),
                            avatarLabel = if (isDirectMessage) item.avatarLabel else null,
                            joined = item.joined || isDirectMessage,
                            showPresence = false,
                            interactionState = ChannelRowInteractionState.Default,
                            captureLongPressAnchor = false,
                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
                        )
                    }
                }
            }

            // Insertion line: a thin bar at the drop target, so where the row will
            // land is explicit without reflowing the list. Drawn at the top of the
            // visible row currently at targetIndex (or the bottom of the last
            // visible row when the target is below the fold).
            val active = draggedId
            if (active != null && targetIndex >= 0) {
                val bounds = visibleSortRowBounds()
                // Direction-aware so the line matches where moveItem actually drops:
                // dragging DOWN lands after the target row (line at its bottom),
                // dragging UP lands before it (line at its top).
                val targetRow = bounds.firstOrNull { it.index == targetIndex }
                val lineY =
                    when {
                        targetRow != null && targetIndex >= draggedStartIndex -> targetRow.top + targetRow.size
                        targetRow != null -> targetRow.top
                        else -> bounds.maxByOrNull { it.index }?.let { it.top + it.size }
                    }
                if (lineY != null) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = HomeListHorizontalPadding)
                                .offset { IntOffset(0, lineY.roundToInt()) }
                                .height(2.dp)
                                .background(SlockColor.AccentPink)
                    )
                }
            }

            // Floating copy of the dragged row, pinned under the finger. Never
            // part of the LazyColumn, so it is never disposed mid-drag.
            if (active != null) {
                val item = itemsById[active]
                if (item != null) {
                    val isDirectMessage = item.isDirectMessageConversation()
                    // Held row shows its settled landing position (debounced target).
                    val position = previewPositionById[active] ?: (draggedStartIndex + 1)
                    ChannelRow(
                        name = item.name,
                        positionPrefix = "$position",
                        symbol = if (isDirectMessage) "#" else item.homeChannelSymbol(),
                        avatarLabel = if (isDirectMessage) item.avatarLabel else null,
                        joined = item.joined || isDirectMessage,
                        showPresence = false,
                        interactionState = ChannelRowInteractionState.Pressed,
                        captureLongPressAnchor = false,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = HomeListHorizontalPadding)
                                .offset {
                                    val info = listState.layoutInfo
                                    IntOffset(
                                        0,
                                        resolveSortDraggedRowTop(
                                            pointerY = pointerViewportY,
                                            viewportStart = info.viewportStartOffset.toFloat(),
                                            viewportEnd = info.viewportEndOffset.toFloat(),
                                            rowSize = rowStepPx
                                        ).roundToInt()
                                    )
                                }
                    )
                }
            }
        }
        SlockModalFullWidthActionButton(
            label = saveLabel,
            icon = SlockLucideIconName.Check,
            color = SlockColor.AccentPink,
            enabled = draggedId == null,
            onClick = { onSave(order) }
        )
    }
}

@Composable
private fun DimmedUnreadCount(text: String) {
    Box(modifier = Modifier.height(CountPillHeight).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 10.sp, fontFamily = SlockMonoFamily),
            color = SlockColor.TextBlack.copy(alpha = 0.5f),
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DirectMessageRow(name: String, description: String? = null, modifier: Modifier = Modifier, avatarLabel: String, peerType: String? = null, memberId: String? = null, agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver, memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver, badge: String? = null, badgeTone: HomeSidebarBadgeTone = HomeSidebarBadgeTone.Loud, hasDraft: Boolean = false, muted: Boolean = false, interactionIdentity: String = "dm:${memberId.orEmpty()}:$name", interactionState: ChannelRowInteractionState = ChannelRowInteractionState.Default, captureLongPressAnchor: Boolean = true, onClick: (() -> Unit)? = null, onLongPress: ((Offset) -> Unit)? = null, onPress: (() -> Unit)? = null, onPressEnd: (() -> Unit)? = null) {
    val hoverInteractionSource = remember(interactionIdentity) { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val selected = interactionState == ChannelRowInteractionState.Selected
    val pressed = interactionState == ChannelRowInteractionState.Pressed
    val unread = badge != null
    val isAgent = peerType?.equals("agent", ignoreCase = true) == true
    val descriptionText = description?.takeIf { it.isNotBlank() }
    val avatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, memberId)
    val titleColor = homeConversationTitleColor(muted = muted)
    val mutedIndicatorIcon = homeConversationMutedIndicatorIcon(muted)
    val effectiveInteractionState = when {
        selected -> ChannelRowInteractionState.Selected
        pressed -> ChannelRowInteractionState.Pressed
        isHovered -> ChannelRowInteractionState.Hovered
        else -> interactionState
    }
    val rowBackground = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Selected -> SlockColor.AccentPink
        ChannelRowInteractionState.Hovered -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Pressed -> SlockColor.SurfaceWhite
    }
    val borderColor = when (interactionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Hovered, ChannelRowInteractionState.Pressed, ChannelRowInteractionState.Selected, ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    val rowTopLeft = remember(interactionIdentity) { SlockMutableOffsetRef() }
    var captureAnchorForPress by remember(interactionIdentity) { mutableStateOf(false) }
    val hoverableModifier = Modifier.hoverable(hoverInteractionSource)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnPressEnd by rememberUpdatedState(onPressEnd)
    val rowClickIdentity = interactionIdentity
    val clickModifier = remember(rowClickIdentity, onClick != null, onLongPress != null) {
        if (onLongPress != null) {
            Modifier.pointerInput(rowClickIdentity, true) {
                detectTapGestures(
                    onPress = {
                        captureAnchorForPress = true
                        currentOnPress?.invoke()
                        try {
                            tryAwaitRelease()
                        } finally {
                            captureAnchorForPress = false
                            currentOnPressEnd?.invoke()
                        }
                    },
                    onTap = { currentOnClick?.invoke() },
                    onLongPress = { pressOffset ->
                        val rowHeightPx = this.size.height
                        currentOnLongPress?.invoke(homeRowLongPressAnchor(rowTopLeft, pressOffset, rowHeightPx))
                    }
                )
            }
        } else if (onClick != null) {
            Modifier.pointerInput(rowClickIdentity, false) {
                detectTapGestures(
                    onPress = {
                        currentOnPress?.invoke()
                        try {
                            tryAwaitRelease()
                        } finally {
                            currentOnPressEnd?.invoke()
                        }
                    },
                    onTap = { currentOnClick?.invoke() }
                )
            }
        } else {
            Modifier
        }
    }
    traceMarkdownSection(SlockHomeTraceNames.DIRECT_MESSAGE_ROW_RENDER, name.length) {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .homeRowAnchorCapture(captureLongPressAnchor || captureAnchorForPress, rowTopLeft)
                    .then(clickModifier)
                    .then(hoverableModifier)
        ) {
        if (selected || pressed) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(ChannelRowHeight)
                        .offset(x = ChannelRowShadowOffset, y = ChannelRowShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
        }
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ChannelRowHeight)
                    .background(rowBackground)
                    .border(SlockStroke.Default, borderColor)
                    .padding(start = ChannelRowHorizontalPadding, end = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(width = ChannelAvatarSize, height = ChannelAvatarSize), contentAlignment = Alignment.Center) {
                SlockAvatar(avatarWireValue, avatarLabel)
            }
            com.tencent.kuikly.compose.foundation.layout.Row(
                modifier =
                    Modifier
                        .padding(start = SidebarActionLabelGap)
                        .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    name,
                    style = if (unread) SlockType.ButtonBold else SlockType.Button,
                    color = titleColor,
                    modifier =
                        if (descriptionText != null) {
                            Modifier.widthIn(max = 260.dp)
                        } else {
                            Modifier.weight(1f, fill = false)
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (mutedIndicatorIcon != null) {
                    Spacer(Modifier.width(ChannelRowMutedIconGap))
                    SlockLucideIcon(
                        mutedIndicatorIcon,
                        Modifier.size(ChannelRowMutedIconSize),
                        color = SlockColor.TextMutedGrayArgb
                    )
                }
                if (descriptionText != null) {
                    Spacer(Modifier.width(SlockSpacing.Xs))
                    Text(
                        descriptionText,
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextMutedGray,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isAgent || hasDraft || badge != null) {
                Spacer(Modifier.width(SlockSpacing.Sm))
            }
            if (isAgent) {
                memberId?.takeIf { it.isNotBlank() }?.let {
                    val status =
                        rememberSlockAgentStatusIndicatorState(agentStatusResolver, it)
                            ?: slockAgentStatusIndicatorState(status = null, presence = Presence.Offline)
                    DirectMessageStatusDot(status)
                }
            }
            if (hasDraft) {
                if (isAgent) Spacer(Modifier.width(SlockSpacing.Xs))
                SlockLucideIcon(SlockLucideIconName.Pencil, Modifier.size(14.dp), color = SlockColor.TextMutedGrayArgb)
            }
            if (badge != null) {
                if (isAgent || hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
                when (badgeTone) {
                    HomeSidebarBadgeTone.Loud -> CountPill(badge, SlockColor.AccentPink)
                    HomeSidebarBadgeTone.Quiet -> DimmedUnreadCount(badge)
                }
            }
        }
        }
    }
}

@Composable
private fun DirectMessageStatusDot(state: SlockAgentStatusIndicatorState) {
    SlockStatusIndicator(
        color = state.color,
        size = DirectMessageStatusDotSize,
        pulsing = state.pulsing
    )
}

@Composable
private fun ChannelAvatar(avatarWireValue: String?, label: String, presence: SlockPresence? = null, showPresence: Boolean = true) {
    SlockAvatarWithStatus(
        statusColor = if (showPresence) presence?.homeRowColor else null,
        avatarSize = ChannelAvatarSize,
        statusSize = 8.dp
    ) {
        SlockAvatar(avatarWireValue, label)
    }
}

@Composable
internal fun SlockAvatar(
    avatarWireValue: String?,
    label: String,
    modifier: Modifier = Modifier,
    avatarSize: Dp = ChannelAvatarSize,
    borderWidth: Dp = SlockStroke.Thin
) {
    // #192 trace scope extension: per-phase attribution for avatar rendering. The
    // avatar is rendered for every channel row in Home + every participant in
    // Thread — that's a lot of recompositions on scroll, so we want to know whether
    // it's the identify / http-fetch / render / fallback-default that's hot.
    val displayAvatarWireValue = avatarWireValue?.appAvatarWireValueOrNull()
    val httpAvatarSource = remember(displayAvatarWireValue) {
        displayAvatarWireValue?.gravatarAvatarHttpUrlOrNull()
            ?: displayAvatarWireValue?.takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }
    }
    val gravatarHash = remember(displayAvatarWireValue) {
        displayAvatarWireValue?.gravatarHashOrNull()
    }
    val pixelSpec = remember(displayAvatarWireValue) {
        traceMarkdownSectionNonComposable(SlockAvatarTraceNames.IDENTIFY) {
            displayAvatarWireValue?.toPixelAvatarSpec()
        }
    }
    when {
        pixelSpec != null -> traceMarkdownSection(SlockAvatarTraceNames.RENDER, displayAvatarWireValue?.length ?: 0) {
            PixelAvatar(pixelSpec, modifier, avatarSize, borderWidth)
        }
        httpAvatarSource != null -> Box(
            modifier = modifier
                .size(width = avatarSize, height = avatarSize)
                .background(SlockColor.AccentLavender)
                .border(borderWidth, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            DefaultUserAvatarIcon(avatarSize)
            SlockAvatarHttpImage(
                source = httpAvatarSource,
                gravatarHash = gravatarHash,
                modifier = Modifier.fillMaxSize()
            )
        }
        else -> traceMarkdownSection(SlockAvatarTraceNames.FALLBACK_DEFAULT, displayAvatarWireValue?.length ?: 0) {
            DefaultUserAvatar(modifier, avatarSize, borderWidth)
        }
    }
}

@Composable
private fun SlockAvatarHttpImage(
    source: String,
    gravatarHash: String? = null,
    modifier: Modifier = Modifier
) {
    // Implementation note: Kuikly's `KuiklyPainter` (returned by
    // `com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter`) drives
    // its `StateFlow<State>` from native `applyTo(view)` callbacks. The
    // Coil3 `onLoading` / `onSuccess` / `onError` callbacks never fire
    // through `Image(painter = ...)` in this overlay pattern because Compose
    // does not call `applyTo` directly. However, painting the Image
    // unconditionally *does* exercise the painter's native draw path
    // (`onDraw` -> `imageCacheManager.loadImage(src)`) and the underlying
    // `KuiklyImageBitmap.status` transitions correctly to `isReady = true`
    // once the fetch completes. We render the Image while a request is eligible
    // and keep the default app avatar underneath. Gravatar failures are shared
    // for this process. This painter API does not expose HTTP status, so
    // failures use a bounded cooldown instead of being treated as permanent
    // 404s; offline/DNS/timeout failures become retryable after the cooldown.
    var retryDelayMillis by remember(gravatarHash) {
        mutableStateOf(
            gravatarHash?.let(slockGravatarSessionLoadCache::retryDelayMillis) ?: 0L
        )
    }
    if (retryDelayMillis > 0L) {
        LaunchedEffect(gravatarHash, retryDelayMillis) {
            delay(retryDelayMillis)
            retryDelayMillis =
                gravatarHash?.let(slockGravatarSessionLoadCache::retryDelayMillis) ?: 0L
        }
        return
    }

    val painter = rememberSlockAvatarHttpPainter(source)
    val imageState by painter.state.collectAsState()
    LaunchedEffect(imageState, gravatarHash) {
        val hash = gravatarHash ?: return@LaunchedEffect
        when (imageState) {
            is AsyncImagePainter.State.Error -> {
                slockGravatarSessionLoadCache.recordFailure(hash)
                retryDelayMillis = slockGravatarSessionLoadCache.retryDelayMillis(hash)
            }
            is AsyncImagePainter.State.Success ->
                slockGravatarSessionLoadCache.recordSuccess(hash)
            else -> Unit
        }
    }
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.FillBounds
    )
}

@Composable
private fun rememberSlockAvatarHttpPainter(source: String): AsyncImagePainter {
    val fallbackSource =
        remember(source) {
            source.appAvatarApiFallbackUrl(
                SlockSharedStoreRegistry.currentOrNull()?.apiEnvironmentStore?.current()?.apiBaseUrl
            )
        }
    var activeSource by remember(source, fallbackSource) { mutableStateOf(source) }
    val painter = rememberAsyncImagePainter(model = activeSource)
    val imageState by painter.state.collectAsState()
    LaunchedEffect(imageState, activeSource, source, fallbackSource) {
        if (
            imageState is AsyncImagePainter.State.Error &&
            activeSource == source &&
            fallbackSource != null
        ) {
            activeSource = fallbackSource
        }
    }
    return painter
}

@Composable
private fun DefaultUserAvatar(
    modifier: Modifier = Modifier,
    avatarSize: Dp = ChannelAvatarSize,
    borderWidth: Dp = SlockStroke.Thin
) {
    Box(modifier = modifier.size(avatarSize).background(SlockColor.AccentLavender).border(borderWidth, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
        DefaultUserAvatarIcon(avatarSize)
    }
}

@Composable
private fun DefaultUserAvatarIcon(avatarSize: Dp) {
    SlockLucideIcon(SlockLucideIconName.User, modifier = Modifier.size((avatarSize.value * 0.56f).dp), color = SlockColor.TextBlackArgb)
}

private val SlockPresence.homeRowColor: Color
    get() = when (this) { SlockPresence.Online -> SlockColor.AccentLime; SlockPresence.Offline -> SlockColor.StatusNeutralGray; SlockPresence.Unknown -> SlockColor.BrandYellow }

@Composable
private fun PixelAvatar(
    spec: PixelAvatarSpec,
    modifier: Modifier = Modifier,
    avatarSize: Dp = ChannelAvatarSize,
    borderWidth: Dp = SlockStroke.Thin
) {
    SlockPixelAvatarDrawCache.onCompose()
    if (isSlockNativePixelAvatarViewSupported()) {
        SlockNativePixelAvatar(
            spec = spec,
            modifier = modifier,
            avatarSize = avatarSize,
            borderWidth = borderWidth
        )
        return
    }
    // Non-Android hosts keep the shared Canvas fallback. The Android host registers a native
    // render view that consumes the same precomputed spans and caches the bitmap by spec/size.
    Canvas(modifier = modifier.size(width = avatarSize, height = avatarSize).border(borderWidth, SlockColor.BorderBlack)) {
        drawRect(spec.background)
        spec.spans.forEach { span ->
            val left = pixelAvatarGridEdge(span.startColumnIndex, size.width)
            val right = pixelAvatarGridEdge(span.startColumnIndex + span.columnCount, size.width)
            val top = pixelAvatarGridEdge(span.rowIndex, size.height)
            val bottom = pixelAvatarGridEdge(span.rowIndex + 1, size.height)
            drawRect(
                span.color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top)
            )
        }
    }
}

private fun pixelAvatarGridEdge(index: Int, extent: Float): Float =
    when {
        index <= 0 -> 0f
        index >= PixelAvatarGridCellCount -> extent
        else -> (extent * index / PixelAvatarGridCellCount).roundToInt().toFloat()
    }

internal data class PixelAvatarSpec(
    val background: Color,
    val grid: List<String>,
    // Palette lookups resolved at construction. Defaults are the generated bundled table
    // (safe during SlockPixelAvatarDataKt class init: they are that file's own fields);
    // SlockPixelAvatarRuntime passes the downloaded document's own palette instead.
    val palette: Map<Char, Color> = PixelAvatarPalette,
    val paletteArgb: Map<Char, String> = PixelAvatarPaletteArgb,
    // Null means "use the bundled PixelAvatarKnownBackgroundArgb" — resolved lazily below,
    // NOT as a default argument, which would re-enter SlockHomeComponentsKt mid-init.
    val backgroundArgbLookup: Map<Color, String>? = null
) {
    val spans: List<PixelAvatarSpan> =
        buildList {
            grid.forEachIndexed { rowIndex, row ->
                var spanColor: Color? = null
                var spanColorArgb: String? = null
                var spanStart = 0
                var spanLength = 0
                fun flushSpan() {
                    val color = spanColor ?: return
                    add(
                        PixelAvatarSpan(
                            rowIndex = rowIndex,
                            startColumnIndex = spanStart,
                            columnCount = spanLength,
                            color = color,
                            colorArgb = spanColorArgb ?: SlockColor.TextBlackArgb
                        )
                    )
                    spanColor = null
                    spanColorArgb = null
                    spanLength = 0
                }
                row.forEachIndexed { columnIndex, key ->
                    val color = palette[key] ?: Color.Transparent
                    val colorArgb = paletteArgb[key]
                    when {
                        color == Color.Transparent -> flushSpan()
                        color == spanColor -> spanLength += 1
                        else -> {
                            flushSpan()
                            spanColor = color
                            spanColorArgb = colorArgb
                            spanStart = columnIndex
                            spanLength = 1
                        }
                    }
                }
                flushSpan()
            }
        }

    // Lazy: PixelAvatarFixedSpecs (generated file) constructs specs during its
    // class init, which SlockHomeComponentsKt's own init triggers — an eager
    // read here hits PixelAvatarKnownBackgroundArgb before it is assigned
    // (init cycle -> NPE in every unit test touching this file).
    val backgroundArgb: String by lazy(LazyThreadSafetyMode.NONE) {
        (backgroundArgbLookup ?: PixelAvatarKnownBackgroundArgb)[background] ?: SlockColor.BrandYellowArgb
    }

    val nativeSpanPayload: String =
        spans.joinToString(separator = ";") { span ->
            "${span.rowIndex},${span.startColumnIndex},${span.columnCount},${span.colorArgb}"
        }

    // Lazy for the same reason: its template reads backgroundArgb, which would
    // force that lazy right back during the generated file's class init.
    val nativeCacheKey: String by lazy(LazyThreadSafetyMode.NONE) {
        "$backgroundArgb|${grid.joinToString(separator = "/")}"
    }
}

internal data class PixelAvatarSpan(
    val rowIndex: Int,
    val startColumnIndex: Int,
    val columnCount: Int,
    val color: Color,
    val colorArgb: String
)
private const val PixelAvatarGridCellCount = 8
private const val PixelAvatarProtocolPrefix = "pixel:"
private const val PixelAvatarRandomPrefix = "random:"
// PixelAvatarDefaultKey / PixelAvatarReservedKeys / PixelAvatarPalette(Argb) /
// PixelAvatarFixedSpecs are GENERATED from the canonical web pixelAvatars.json —
// see SlockPixelAvatarData.kt (scripts/generate_pixel_avatars.mjs, task #352).

/**
 * #195/#199 diagnostic counter retained for avatar render churn. Android now routes pixel
 * avatars through a host bitmap cache; non-Android hosts keep the shared Canvas fallback.
 * Logging remains aggregate-only and does not include avatar content.
 */
private object SlockPixelAvatarDrawCache {
    private var composeCount: Int = 0
    private var rebuildCount: Int = 0

    fun onCompose() {
        composeCount += 1
        maybeLog()
    }

    fun onRebuild() {
        rebuildCount += 1
        maybeLog()
    }

    private var lastLogAtCompose: Int = 0

    private fun maybeLog() {
        // Log every 64 composes (~one screen worth of avatar rows). Avoid spamming logcat.
        if (composeCount - lastLogAtCompose < 64) return
        lastLogAtCompose = composeCount
        val hitRate =
            if (composeCount == 0) 0.0
            else 1.0 - rebuildCount.toDouble() / composeCount.toDouble()
        // Debug-only log; intentionally keep at info level so it's easy to grep in logcat.
        // OHOS / JS see the side effect of counters updating but no log line is emitted
        // (the android.util.Log call is platform-conditional below).
        // Note: avoid String.format for cross-platform compat (not in Kotlin/Native stdlib);
        // manual integer-truncation to two decimals is fine for diagnostic purposes.
        val hitRatePct = (hitRate * 100.0).toInt()
        logPixelAvatarDrawCache("composes=$composeCount rebuilds=$rebuildCount hitRatePct=$hitRatePct%")
    }

    fun snapshot(): Pair<Int, Int> = composeCount to rebuildCount

    fun resetForTest() {
        composeCount = 0
        rebuildCount = 0
        lastLogAtCompose = 0
    }
}

internal expect fun logPixelAvatarDrawCache(message: String)
internal expect fun isSlockNativePixelAvatarViewSupported(): Boolean
private val PixelAvatarKnownBackgroundArgb: Map<Color, String> =
    pixelAvatarKnownBackgroundArgb(
        palette = PixelAvatarPalette,
        paletteArgb = PixelAvatarPaletteArgb,
        literalBackgroundArgb = PixelAvatarLiteralBackgroundArgb
    )
private val PixelAvatarRandomOptions = listOf(
    PixelAvatarPalette.getValue('C') to 'K',
    PixelAvatarPalette.getValue('Y') to 'K',
    PixelAvatarPalette.getValue('L') to 'K',
    PixelAvatarPalette.getValue('P') to 'W',
    PixelAvatarPalette.getValue('V') to 'K',
    Color(0xFF1E1E1C) to 'C',
    Color(0xFF1E1E1C) to 'G',
    Color(0xFF1E1E1C) to 'P',
    Color(0xFF1E1E1C) to 'Y',
    Color(0xFF1E1E1C) to 'V',
    PixelAvatarPalette.getValue('O') to 'K',
    PixelAvatarPalette.getValue('C') to 'W'
)
private fun String.toPixelAvatarSpec(): PixelAvatarSpec? =
    pixelAvatarSpecForWireValue(this)

internal fun pixelAvatarSpecForWireValue(value: String): PixelAvatarSpec? {
    val normalized = value.trim().takeIf { it.isNotBlank() } ?: return null
    normalized.removePrefixOrNull(PixelAvatarProtocolPrefix)?.let { key ->
        key.removePrefixOrNull(PixelAvatarRandomPrefix)?.let { seed ->
            return seed.takeIf { it.isNotBlank() }?.let(::randomPixelAvatar)
        }
        return SlockPixelAvatarRuntime.current.let { data ->
            data.specs[key] ?: data.specs.getValue(data.defaultKey)
        }
    }
    return null
}

/**
 * Returns the picker keys in display order, mirroring React
 * `AVATAR_KEYS = Object.keys(AVATARS).filter(k => !RESERVED_AVATAR_KEYS.includes(k))`.
 * Includes the default key (`robot`) — React renders the default as a separate
 * second button in the picker (`title="Default (robot)"`) so callers prepend
 * the Upload cell and use this list directly for the remaining keys.
 * Used by the avatar picker modal grid in `MembersAgentProfileTab`.
 */
internal val PixelAvatarPickerKeys: List<String>
    get() = SlockPixelAvatarRuntime.current.let { data -> data.specs.keys.filterNot { it in data.reservedKeys } }
private fun String.removePrefixOrNull(prefix: String): String? = takeIf { it.startsWith(prefix) }?.substring(prefix.length)
private fun randomPixelAvatar(seed: String): PixelAvatarSpec {
    val random = PixelAvatarRandom(pixelHash(seed))
    val (background, fill) = PixelAvatarRandomOptions[(random.next() * PixelAvatarRandomOptions.size).toInt()]
    val grid = List(8) { val left = List(4) { if (random.next() < 0.45) fill else '_' }; (left + left.asReversed()).joinToString("") }
    return PixelAvatarSpec(background, grid)
}
private fun pixelHash(value: String): Int { var hash = 0; value.forEach { hash = (hash shl 5) - hash + it.code }; return hash }
private class PixelAvatarRandom(seed: Int) { private var value = seed; fun next(): Double { value += 1831565813; var mixed = (value xor (value ushr 15)) * (1 or value); mixed = (mixed + ((mixed xor (mixed ushr 7)) * (61 or mixed))) xor mixed; val unsigned = (mixed xor (mixed ushr 14)).toLong() and 0xffffffffL; return unsigned.toDouble() / 4294967296.0 } }

@Composable
private fun CountPill(text: String, color: Color) {
    HomeUnreadBadge(text = text, color = color)
}

@Composable
private fun HomeUnreadBadge(text: String, color: Color = SlockColor.AccentPink) {
    Box(
        modifier =
            Modifier
                .height(CountPillHeight)
                .widthIn(min = CountPillMinWidth)
                .borderRadius(HomeUnreadBadgeRadius)
                .background(color)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 10.sp),
            color = SlockColor.SurfaceWhite,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.offset(y = HomeUnreadBadgeTextOffsetY)
        )
    }
}
