package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.model.AvatarReference
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
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
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.extension.bouncesEnable
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.pullToRefreshItem
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

const val SLOCK_HOME_PAGE = "compose_home"
private const val HOME_CHANNEL_ROW_VISUAL_PAGE = "compose_home_channel_row_visual"
private const val HOME_TITLEBAR_VISUAL_PAGE = "compose_home_titlebar_visual"
private const val HOME_NOTIFICATION_CENTER_VISUAL_PAGE = "compose_home_notification_center_visual"
private const val HOME_TOP_ACTIONS_VISUAL_PAGE = "compose_home_top_actions_visual"
private const val HOME_PAGE_DATA_EVENT = "slockPageData"

private val SidebarActionIconSize = 14.dp
private val SidebarActionIconBoxSize = 14.dp
private val SidebarActionRowHeight = 32.dp
private val HomeSmallShadowOffset = 2.dp
private val SidebarActivityBottomGap = 4.dp
private val SidebarRowHorizontalPadding = 8.dp
private val SidebarActionLabelGap = 6.dp
private val ChannelSymbolWidth = 18.dp
private val ChannelSymbolIconSize = 14.dp
private val ChannelAvatarSize = 20.dp
private val ChannelAvatarFontSize = 11.sp
private val ChannelRowHeight = 32.dp
private val ChannelRowHorizontalPadding = 8.dp
private val ChannelRowShadowOffset = 2.dp
private val SectionChevronSize = 12.dp
private val MiniActionSize = 30.dp
private val MiniActionIconSize = 16.dp
private val CountPillMinWidth = 18.dp
private val CountPillHeight = 16.dp
private val HomeUnreadBadgeRadius = 4.dp
private val ChannelUnreadTextOffsetY = (-1).dp
private val HomeListHorizontalPadding = SlockSpacing.Md
private val HomePanelShadowOffset = 4.dp
private const val SharedListDefaultBeyondBoundsItemCount = 3
private const val SharedListMaxBeyondBoundsItemCount = 96
private const val HomeRefreshFrameDelayMs = 16L
private const val HomeRefreshWaveStep = 0.26f

@Page(SLOCK_HOME_PAGE, supportInLocal = true)
internal class ComposeSlockHomePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockHomePageContent() }
    }
}

@Page(HOME_CHANNEL_ROW_VISUAL_PAGE, supportInLocal = true)
internal class ComposeHomeChannelRowVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeChannelRowVisualScreen() }
    }
}

@Page(HOME_TITLEBAR_VISUAL_PAGE, supportInLocal = true)
internal class ComposeHomeTitlebarVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeTitlebarVisualScreen() }
    }
}

@Page(HOME_NOTIFICATION_CENTER_VISUAL_PAGE, supportInLocal = true)
internal class ComposeHomeNotificationCenterVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeNotificationCenterVisualScreen() }
    }
}

@Page(HOME_TOP_ACTIONS_VISUAL_PAGE, supportInLocal = true)
internal class ComposeHomeTopActionsVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeTopActionsVisualScreen() }
    }
}

@Serializable
data class SlockServerSwitcherItem(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val unreadCount: Int = 0
)

@Serializable
data class SlockHomeConversationItem(
    val id: String = "",
    val name: String = "",
    val unreadCount: Int = 0,
    val avatarLabel: String = name.take(2).uppercase(),
    val avatarUrl: String? = null,
    val avatarWireValue: String? = avatarUrl,
    val presence: SlockPresence? = null,
    val hasDraft: Boolean = false,
    val joined: Boolean = true
)

@Serializable
data class SlockHomeNotificationItem(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val actionLabel: String = "View",
    val targetId: String? = null,
    val kind: SlockHomeNotificationKind = SlockHomeNotificationKind.Warning
)

@Serializable
enum class SlockHomeNotificationKind { Warning, Info, Error }

@Serializable
enum class SlockPresence { Online, Offline, Unknown }

@Serializable
data class SlockHomeShellLabels(
    val search: String = "Search",
    val inbox: String = "Activity",
    val followedThreads: String = "Followed threads",
    val saved: String = "Saved",
    val channels: String = "CHANNELS",
    val directMessages: String = "DIRECT MESSAGES",
    val addChannel: String = "Add channel",
    val noServersAvailable: String = "No servers available",
    val joinCommunityServer: String = "Join community server",
    val switchOrCreateServer: String = "Switch or create server"
) {
    companion object {
        @Composable
        fun defaults(): SlockHomeShellLabels = SlockHomeShellLabels()
    }
}

@Serializable
internal data class SlockHomePayload(
    val instanceId: String = "",
    val currentServerName: String = "Slock Android",
    val currentServerId: String = "",
    val servers: List<SlockServerSwitcherItem> = emptyList(),
    val channels: List<SlockHomeConversationItem> = emptyList(),
    val directMessages: List<SlockHomeConversationItem> = emptyList(),
    val selectedChannelId: String? = null,
    val serverUnreadCount: Int = 0,
    val inboxUnreadCount: Int = 0,
    val savedCount: Int = 0,
    val notifications: List<SlockHomeNotificationItem> = emptyList(),
    val serverSwitcherExpanded: Boolean = false,
    val contentRefreshing: Boolean = false,
    val contentRefreshEnabled: Boolean = true,
    val applyTopSafeArea: Boolean = true,
    val activitySelected: Boolean = false,
    val bottomContentPaddingDp: Float = 24f,
    val labels: SlockHomeShellLabels = SlockHomeShellLabels()
)

private enum class ChannelRowInteractionState { Default, Selected, Hovered, Focused }

@Composable
private fun SlockHomePageContent() {
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
    val payload = rememberHomePayload(
        payloadText,
        instanceId
    )
    LaunchedEffect(payload) {
        bridge.log("home_render instanceId=${payload.instanceId} channels=${payload.channels.size} dms=${payload.directMessages.size}")
    }
    SlockStructuralHomeShell(
        currentServerName = payload.currentServerName,
        currentServerId = payload.currentServerId,
        servers = payload.servers,
        channels = payload.channels,
        directMessages = payload.directMessages,
        selectedChannelId = payload.selectedChannelId,
        serverUnreadCount = payload.serverUnreadCount,
        inboxUnreadCount = payload.inboxUnreadCount,
        savedCount = payload.savedCount,
        notifications = payload.notifications,
        serverSwitcherExpanded = payload.serverSwitcherExpanded,
        contentRefreshing = payload.contentRefreshing,
        contentRefreshEnabled = payload.contentRefreshEnabled,
        applyTopSafeArea = payload.applyTopSafeArea,
        activitySelected = payload.activitySelected,
        showBottomBar = false,
        bottomContentPadding = payload.bottomContentPaddingDp.dp,
        outerPadding = 0.dp,
        labels = payload.labels,
        onServerBadgeClick = { bridge.settingsEvent(payload.instanceId, "toggleServerSwitcher", null) },
        onServerSwitcherDismiss = { bridge.settingsEvent(payload.instanceId, "dismissServerSwitcher", null) },
        onServerSelected = { bridge.settingsEvent(payload.instanceId, "selectServer", it) },
        onCreateServerClick = { bridge.settingsEvent(payload.instanceId, "createServer", null) },
        onCreateChannelClick = { bridge.settingsEvent(payload.instanceId, "createChannel", null) },
        onInboxClick = { bridge.settingsEvent(payload.instanceId, "openInbox", null) },
        onFollowedThreadsClick = { bridge.settingsEvent(payload.instanceId, "openFollowedThreads", null) },
        onSavedClick = { bridge.settingsEvent(payload.instanceId, "openSaved", null) },
        onSearchClick = { bridge.settingsEvent(payload.instanceId, "openSearch", null) },
        onContentRefresh = {
            bridge.log("home_refresh_request instanceId=${payload.instanceId}")
            bridge.settingsEvent(payload.instanceId, "refreshHome", null)
        },
        onNotificationView = { bridge.settingsEvent(payload.instanceId, "viewNotification", it.id) },
        onNotificationDismiss = { bridge.settingsEvent(payload.instanceId, "dismissNotification", it.id) },
        onChannelClick = { bridge.settingsEvent(payload.instanceId, "openChannel", it) }
    )
}

@Composable
private fun HomeTitlebarVisualScreen() {
    Box(modifier = Modifier.width(342.dp).height(110.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp)) {
        HomeTitlebar(
            serverName = "Slock Design",
            serverUnreadCount = 24,
            notifications = visualHomeNotifications(),
            topSafeArea = 0.dp,
            onServerBadgeClick = {},
            onView = {},
            onDismiss = {}
        )
    }
}

@Composable
private fun HomeNotificationCenterVisualScreen() {
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
private fun HomeTopActionsVisualScreen() {
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(top = 48.dp)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SidebarActionRow("search", "Search")
        SidebarActivityRow("Activity", "7", selected = false)
        SidebarActionRow("bookmark", "Saved", trailingText = "3")
    }
}

@Composable
private fun HomeChannelRowVisualScreen() {
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(top = 48.dp)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SidebarSectionHeader(
            label = "Channels",
            count = 2,
            expanded = true,
            onExpandedChange = {},
            onSortClick = {},
            action = {
                HeaderIconAction("plus", "Add channel") {}
            }
        )
        ChannelRow(
            name = "design",
            badge = "3",
            joined = true,
            interactionState = ChannelRowInteractionState.Selected
        )
        ChannelRow(
            name = "android-artifacts",
            joined = true,
            interactionState = ChannelRowInteractionState.Default
        )
        ChannelRow(
            name = "release-gate",
            badge = "12",
            joined = false,
            interactionState = ChannelRowInteractionState.Default
        )
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
private fun rememberHomePayload(payload: String, instanceId: String): SlockHomePayload =
    remember(payload, instanceId) {
        runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(SlockHomePayload.serializer(), payload)
                .let { if (it.instanceId.isBlank()) it.copy(instanceId = instanceId) else it }
        }.getOrElse { SlockHomePayload(instanceId = instanceId) }
    }

@Composable
fun SlockStructuralHomeShell(
    modifier: Modifier = Modifier,
    currentServerName: String = "Slock Android",
    currentServerId: String = "slock-android",
    servers: List<SlockServerSwitcherItem> = emptyList(),
    channels: List<SlockHomeConversationItem> = emptyList(),
    directMessages: List<SlockHomeConversationItem> = emptyList(),
    selectedChannelId: String? = null,
    serverUnreadCount: Int = 0,
    inboxUnreadCount: Int = 0,
    savedCount: Int = 0,
    notifications: List<SlockHomeNotificationItem> = emptyList(),
    serverSwitcherExpanded: Boolean = false,
    pendingServerId: String? = null,
    contentRefreshing: Boolean = false,
    contentRefreshEnabled: Boolean = true,
    applyTopSafeArea: Boolean = true,
    activitySelected: Boolean = false,
    searchSelected: Boolean = false,
    savedSelected: Boolean = false,
    selectedTabKey: String = "home",
    showBottomBar: Boolean = true,
    bottomContentPadding: Dp = if (showBottomBar) SlockSize.BottomBarHeight + SlockSpacing.Md else SlockSpacing.Lg,
    outerPadding: Dp = SlockSpacing.Md,
    labels: SlockHomeShellLabels = SlockHomeShellLabels.defaults(),
    onContentRefresh: () -> Unit = {},
    onServerBadgeClick: () -> Unit = {},
    onServerSwitcherDismiss: () -> Unit = {},
    onServerSelected: (String) -> Unit = {},
    onCreateServerClick: () -> Unit = {},
    onCreateChannelClick: () -> Unit = {},
    onTabSelected: (String) -> Unit = {},
    onInboxClick: () -> Unit = {},
    onFollowedThreadsClick: () -> Unit = {},
    onSavedClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationView: (SlockHomeNotificationItem) -> Unit = {},
    onNotificationDismiss: (SlockHomeNotificationItem) -> Unit = {},
    onChannelClick: (String) -> Unit = {}
) {
    var channelsExpanded by remember { mutableStateOf(true) }
    var directMessagesExpanded by remember { mutableStateOf(true) }
    var channelsReversed by remember { mutableStateOf(false) }
    var directMessagesReversed by remember { mutableStateOf(false) }
    var refreshIndicatorTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(contentRefreshing) {
        refreshIndicatorTimedOut = false
        if (contentRefreshing) {
            delay(12_000)
            refreshIndicatorTimedOut = true
        }
    }
    val indicatorRefreshing = contentRefreshing && !refreshIndicatorTimedOut
    val displayedChannels = if (channelsReversed) channels.asReversed() else channels
    val displayedDirectMessages = if (directMessagesReversed) directMessages.asReversed() else directMessages
    val lazyItemCount =
        homeLazyItemCount(
            channelsCount = displayedChannels.size,
            directMessagesCount = displayedDirectMessages.size,
            channelsExpanded = channelsExpanded,
            directMessagesExpanded = directMessagesExpanded,
            contentRefreshEnabled = contentRefreshEnabled
        )
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState(indicatorRefreshing)
    val topSafeArea = if (applyTopSafeArea) slockTopSafeAreaPadding() else 0.dp
    Column(
        modifier = modifier.fillMaxSize().background(SlockColor.AppBgLightBlue).padding(outerPadding)
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(SlockColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                HomeTitlebar(
                    serverName = currentServerName,
                    serverUnreadCount = serverUnreadCount,
                    notifications = notifications,
                    topSafeArea = topSafeArea,
                    onServerBadgeClick = onServerBadgeClick,
                    onView = onNotificationView,
                    onDismiss = onNotificationDismiss
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().bouncesEnable(false),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        beyondBoundsItemCount = sharedListBeyondBoundsItemCount(lazyItemCount)
                    ) {
                        if (contentRefreshEnabled) {
                            pullToRefreshItem(
                                state = pullToRefreshState,
                                onRefresh = {
                                    if (!contentRefreshing) {
                                        onContentRefresh()
                                    }
                                },
                                scrollState = listState,
                                refreshThreshold = 64.dp
                            ) { pullProgress, isRefreshing, _ ->
                                HomePullRefreshIndicator(
                                    pullProgress = pullProgress,
                                    refreshing = isRefreshing || indicatorRefreshing
                                )
                            }
                        }
                        item { Spacer(Modifier.height(SlockSpacing.Md)) }
                        item { SidebarActionRow("search", labels.search, selected = searchSelected, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding), onClick = onSearchClick) }
                        item {
                            SidebarActivityRow(
                                labels.inbox,
                                inboxUnreadCount.takeIf { it > 0 }?.toString(),
                                selected = activitySelected,
                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                onClick = onInboxClick
                            )
                        }
                        item { SidebarActionRow("bookmark", labels.saved, selected = savedSelected, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding), trailingText = savedCount.takeIf { it > 0 }?.toString(), onClick = onSavedClick) }
                        item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                        item {
                            SidebarSectionHeader(labels.channels, channels.size, channelsExpanded, { channelsExpanded = it }, { channelsReversed = !channelsReversed }, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)) {
                                HeaderIconAction("plus", labels.addChannel, onCreateChannelClick)
                            }
                        }
                        if (channelsExpanded) {
                            items(displayedChannels.size, key = { index -> "channel-${displayedChannels[index].id}" }) { index ->
                                val channel = displayedChannels[index]
                                ChannelRow(
                                    name = channel.name,
                                    joined = channel.joined,
                                    hasDraft = channel.hasDraft,
                                    badge = channel.unreadCount.takeIf { it > 0 }?.toString(),
                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                    interactionState = if (channel.id == selectedChannelId) ChannelRowInteractionState.Selected else ChannelRowInteractionState.Default,
                                    onClick = { onChannelClick(channel.id) }
                                )
                            }
                        }
                        item {
                            SidebarSectionHeader(labels.directMessages, directMessages.size, directMessagesExpanded, { directMessagesExpanded = it }, { directMessagesReversed = !directMessagesReversed }, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding), action = null)
                        }
                        if (directMessagesExpanded) {
                            items(displayedDirectMessages.size, key = { index -> "dm-${displayedDirectMessages[index].id}" }) { index ->
                                val dm = displayedDirectMessages[index]
                                ChannelRow(
                                    name = dm.name,
                                    avatarLabel = dm.avatarLabel,
                                    avatarUrl = dm.avatarUrl,
                                    avatarWireValue = dm.avatarWireValue,
                                    trailingPresence = homeDmStatusPresence(dm.presence),
                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                    hasDraft = dm.hasDraft,
                                    badge = dm.unreadCount.takeIf { it > 0 }?.toString(),
                                    interactionState = if (dm.id == selectedChannelId) ChannelRowInteractionState.Selected else ChannelRowInteractionState.Default,
                                    onClick = { onChannelClick(dm.id) }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(bottomContentPadding)) }
                    }
                }
            }
            if (serverSwitcherExpanded) {
                HomeServerSwitcherDismissLayer(onServerSwitcherDismiss)
                ServerSwitcherPanel(servers, currentServerId, pendingServerId, labels, onServerSelected, onCreateServerClick, Modifier.padding(top = topSafeArea + SlockSize.PageHeaderHeight, start = SlockSpacing.Lg))
            }
        }
        if (showBottomBar) {
            SlockBottomTabBar(
                items = listOf(
                    SlockBottomTabItemPayload("home", "HOME", "house", selected = selectedTabKey == "home"),
                    SlockBottomTabItemPayload("tasks", "TASKS", "square-check-big", selected = selectedTabKey == "tasks"),
                    SlockBottomTabItemPayload("members", "MEMBERS", "users", selected = selectedTabKey == "members"),
                    SlockBottomTabItemPayload("settings", "SETTINGS", "settings", selected = selectedTabKey == "settings")
                ),
                onSelect = onTabSelected,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

internal fun sharedListBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceIn(
        minimumValue = SharedListDefaultBeyondBoundsItemCount,
        maximumValue = SharedListMaxBeyondBoundsItemCount
    )

internal fun homeLazyItemCount(
    channelsCount: Int,
    directMessagesCount: Int,
    channelsExpanded: Boolean,
    directMessagesExpanded: Boolean,
    contentRefreshEnabled: Boolean
): Int {
    val fixedItems =
        1 + // top spacer
            1 + // search
            1 + // activity
            1 + // saved
            1 + // section spacer
            1 + // channels header
            1 + // direct messages header
            1 // bottom spacer
    return fixedItems +
        (if (contentRefreshEnabled) 1 else 0) +
        (if (channelsExpanded) channelsCount else 0) +
        (if (directMessagesExpanded) directMessagesCount else 0)
}

@Composable
private fun HomeServerSwitcherDismissLayer(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.TextBlack.copy(alpha = 0.01f))
            .clickable(onClick = onDismiss)
    )
}

@Composable
private fun HomePullRefreshIndicator(pullProgress: Float, refreshing: Boolean) {
    var wavePhase by remember { mutableStateOf(0f) }
    LaunchedEffect(refreshing) {
        if (!refreshing) {
            wavePhase = 0f
            return@LaunchedEffect
        }
        while (true) {
            wavePhase = (wavePhase + HomeRefreshWaveStep) % (PI.toFloat() * 2f)
            delay(HomeRefreshFrameDelayMs)
        }
    }
    val pullOffset = ((1f - pullProgress.coerceIn(0f, 1f)) * 8f).dp
    Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
        SlockJumpingLogoBadge(
            wavePhase = if (refreshing) wavePhase else null,
            modifier = Modifier.offset(y = if (refreshing) 0.dp else pullOffset)
        )
    }
}

private const val SlockRefreshLetters = "SLOCK"

@Composable
private fun SlockJumpingLogoBadge(wavePhase: Float?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier = Modifier.background(SlockColor.TextBlack).border(SlockStroke.Default, SlockColor.BorderBlack).padding(horizontal = 14.dp, vertical = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SlockRefreshLetters.forEachIndexed { index, letter ->
                val letterOffset =
                    if (wavePhase == null) {
                        0.dp
                    } else {
                        val wave = sin((wavePhase - index * 0.72f).toDouble()).toFloat()
                        val up = max(0f, wave) * 5f
                        val down = max(0f, -wave) * 2f
                        (down - up).dp
                    }
                Text(
                    letter.toString(),
                    color = SlockColor.BrandYellow,
                    style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp),
                    modifier = Modifier.offset(y = letterOffset)
                )
            }
        }
    }
}

@Composable
private fun HomeTitlebar(
    serverName: String,
    serverUnreadCount: Int,
    notifications: List<SlockHomeNotificationItem>,
    topSafeArea: Dp,
    onServerBadgeClick: () -> Unit,
    onView: (SlockHomeNotificationItem) -> Unit,
    onDismiss: (SlockHomeNotificationItem) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().height(topSafeArea + SlockSize.PageHeaderHeight).background(SlockColor.BrandYellow)) {
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack).align(Alignment.BottomCenter))
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .padding(top = topSafeArea)
                    .height(SlockSize.PageHeaderHeight)
                    .padding(horizontal = SlockSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.clickable(onClick = onServerBadgeClick)) {
                SlockLogoBadge(
                    text = serverName,
                    showDropdown = true,
                    tilted = false,
                    unread = serverUnreadCount > 0,
                    textStyle = SlockType.Button.copy(fontSize = 16.sp, lineHeight = 20.sp)
                )
            }
            Spacer(Modifier.weight(1f))
            HomeNotificationCenter(notifications, onView, onDismiss)
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
    textStyle: TextStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp)
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
                SlockLucideIcon("chevron-down", Modifier.size(18.dp), color = "#FFFFD440")
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
    textStyle: TextStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp)
) {
    Box(modifier = modifier.padding(end = HomeSmallShadowOffset, bottom = HomeSmallShadowOffset).rotate(-2f)) {
        Box(Modifier.matchParentSize().offset(x = HomeSmallShadowOffset, y = HomeSmallShadowOffset).background(SlockColor.ShadowBlack))
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier.background(SlockColor.TextBlack)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, color = SlockColor.BrandYellow, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            if (showDropdown) {
                Spacer(Modifier.width(SlockSpacing.Xs))
                SlockLucideIcon("chevron-down", Modifier.size(16.dp).rotate(2f), color = "#FFFFD440")
            }
        }
        if (unread) ServerUnreadMarker(modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun ServerUnreadMarker(modifier: Modifier = Modifier) {
    Box(modifier = modifier.offset(x = 4.dp, y = (-4).dp).size(10.dp).background(SlockColor.AccentPink).border(SlockStroke.Thin, SlockColor.BorderBlack))
}

@Composable
private fun HomeNotificationCenter(notifications: List<SlockHomeNotificationItem>, onView: (SlockHomeNotificationItem) -> Unit, onDismiss: (SlockHomeNotificationItem) -> Unit, modifier: Modifier = Modifier) {
    if (notifications.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val activeKind = notifications.first().kind
    Box(modifier = modifier) {
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(end = HomeSmallShadowOffset, bottom = HomeSmallShadowOffset)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .offset(x = HomeSmallShadowOffset, y = HomeSmallShadowOffset)
                    .background(SlockColor.ShadowBlack)
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(activeKind.notificationColor())
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon("triangle-alert", Modifier.size(16.dp))
            }
        }
        ServerUnreadMarker(Modifier.align(Alignment.TopEnd))
        if (expanded) HomeNotificationPopover(notifications, onView, onDismiss, Modifier.align(Alignment.TopEnd).offset(y = 44.dp))
    }
}

@Composable
private fun HomeNotificationPopover(notifications: List<SlockHomeNotificationItem>, onView: (SlockHomeNotificationItem) -> Unit, onDismiss: (SlockHomeNotificationItem) -> Unit, modifier: Modifier = Modifier) {
    HardShadowPanel(modifier = modifier, minWidth = 280.dp, maxWidth = 340.dp) {
        com.tencent.kuikly.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("NOTIFICATIONS", style = SlockType.SectionLabel, color = SlockColor.TextMutedGray, maxLines = 1)
            Text("${notifications.size} ${if (notifications.size == 1) "item" else "items"}", style = SlockType.Button, color = SlockColor.TextMutedGray, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        notifications.forEach { notification -> HomeNotificationRow(notification, { onView(notification) }, { onDismiss(notification) }) }
    }
}

@Composable
private fun HomeNotificationRow(notification: SlockHomeNotificationItem, onView: () -> Unit, onDismiss: () -> Unit) {
    com.tencent.kuikly.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().padding(SlockSpacing.Md), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        Box(modifier = Modifier.size(24.dp).background(notification.kind.notificationColor()).border(SlockStroke.Default, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
            SlockLucideIcon(when (notification.kind) { SlockHomeNotificationKind.Warning -> "circle-arrow-up"; SlockHomeNotificationKind.Info -> "bell-ring"; SlockHomeNotificationKind.Error -> "triangle-alert" }, Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
            Text(notification.title, style = SlockType.Button, color = SlockColor.TextBlack, overflow = TextOverflow.Ellipsis, maxLines = 3)
            Text(notification.body, style = SlockType.Button, color = SlockColor.TextMutedGray, overflow = TextOverflow.Ellipsis, maxLines = 3)
            com.tencent.kuikly.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                HomeNotificationActionButton(notification.actionLabel, SlockColor.AccentPink, onView)
                HomeNotificationActionButton("Dismiss", SlockColor.SurfaceWhite, onDismiss)
            }
        }
    }
}

@Composable
private fun HomeNotificationActionButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier =
            Modifier
                .heightIn(min = 32.dp)
                .widthIn(min = 72.dp)
                .background(color)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = SlockSpacing.Sm)
    ) {
        Text(text, style = SlockType.Caption.copy(fontWeight = FontWeight.Medium), color = SlockColor.TextBlack, maxLines = 1, textAlign = TextAlign.Center)
    }
}

private fun SlockHomeNotificationKind.notificationColor(): Color = when (this) {
    SlockHomeNotificationKind.Warning -> SlockColor.AccentOrange
    SlockHomeNotificationKind.Info -> SlockColor.BrandYellow
    SlockHomeNotificationKind.Error -> SlockColor.AccentRed
}

@Composable
private fun ServerSwitcherPanel(servers: List<SlockServerSwitcherItem>, currentServerId: String, pendingServerId: String?, labels: SlockHomeShellLabels, onServerSelected: (String) -> Unit, onCreateServerClick: () -> Unit, modifier: Modifier = Modifier) {
    val hasCommunityServer = servers.any { it.slug == "community" }
    HardShadowPanel(modifier = modifier, minWidth = 276.dp, maxWidth = 328.dp) {
        if (servers.isEmpty()) {
            Text(labels.noServersAvailable, style = SlockType.Caption, color = SlockColor.TextMutedGray, modifier = Modifier.padding(SlockSpacing.Md))
        } else {
            servers.forEach { ServerSwitcherRow(it, it.id == currentServerId, it.id == pendingServerId) { onServerSelected(it.id) } }
        }
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        if (!hasCommunityServer) {
            ServerSwitcherActionRow(labels.joinCommunityServer, onCreateServerClick)
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
    Box(
        modifier = modifier
            .padding(end = HomePanelShadowOffset, bottom = HomePanelShadowOffset)
            .widthIn(min = minWidth, max = maxWidth)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = HomePanelShadowOffset, y = HomePanelShadowOffset)
                .background(SlockColor.ShadowBlack)
        )
        Column(
            modifier = Modifier
                .widthIn(min = minWidth, max = maxWidth)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
            content = content
        )
    }
}

@Composable
private fun ServerSwitcherRow(server: SlockServerSwitcherItem, selected: Boolean, pending: Boolean, onClick: () -> Unit) {
    com.tencent.kuikly.compose.foundation.layout.Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .background(SlockColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            when {
                pending -> Text("...", style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, lineHeight = 14.sp), color = SlockColor.TextBlack, maxLines = 1, textAlign = TextAlign.Center)
                selected -> SlockLucideIcon("check", Modifier.size(14.dp))
                else -> Spacer(Modifier.size(14.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(server.name, style = SlockType.Button, color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "/${server.slug}",
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                color = SlockColor.TextBlack.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!selected && server.unreadCount > 0) HomeUnreadBadge(server.unreadCount.toString())
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
        SlockLucideIcon("plus", Modifier.size(14.dp))
        Text(label, style = SlockType.ButtonBold, color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SidebarActivityRow(label: String, count: String?, selected: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    if (!selected) {
        SidebarActionRow(
            icon = "activity",
            label = label,
            modifier = modifier.padding(bottom = SidebarActivityBottomGap),
            badge = count,
            onClick = onClick
        )
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = SidebarActivityBottomGap)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(end = HomeSmallShadowOffset, bottom = HomeSmallShadowOffset)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SidebarActionRowHeight)
                    .offset(x = HomeSmallShadowOffset, y = HomeSmallShadowOffset)
                    .background(SlockColor.ShadowBlack)
            )
            com.tencent.kuikly.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SidebarActionRowHeight)
                    .background(SlockColor.AccentPink)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(horizontal = SidebarRowHorizontalPadding, vertical = SlockSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(SidebarActionIconBoxSize), contentAlignment = Alignment.Center) {
                    SlockLucideIcon("activity", Modifier.size(SidebarActionIconSize))
                }
                Text(
                    label,
                    style = SlockType.ButtonBold,
                    color = SlockColor.TextBlack,
                    modifier = Modifier.padding(start = SidebarActionLabelGap).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (count != null) {
                    SidebarTrailingNumberText(count, color = SlockColor.TextBlack.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun SidebarActionRow(icon: String, label: String, modifier: Modifier = Modifier, selected: Boolean = false, badge: String? = null, trailingText: String? = null, onClick: (() -> Unit)? = null) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SidebarActionRowHeight)
                    .offset(x = HomeSmallShadowOffset, y = HomeSmallShadowOffset)
                    .background(SlockColor.ShadowBlack)
            )
        }
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SidebarActionRowHeight)
                    .background(if (selected) SlockColor.AccentPink else Color.Transparent)
                    .then(if (selected) Modifier.border(SlockStroke.Default, SlockColor.BorderBlack) else Modifier)
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(horizontal = SidebarRowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(SidebarActionIconBoxSize), contentAlignment = Alignment.Center) { SlockLucideIcon(icon, Modifier.size(SidebarActionIconSize)) }
            Text(label, style = if (selected) SlockType.ButtonBold else SlockType.Button, color = SlockColor.TextBlack, modifier = Modifier.padding(start = SidebarActionLabelGap).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) CountPill(badge, SlockColor.AccentPink) else if (trailingText != null) SidebarTrailingNumberText(trailingText, color = SlockColor.TextBlack)
        }
    }
}

@Composable
private fun SidebarTrailingNumberText(text: String, color: Color) {
    Box(modifier = Modifier.height(SidebarActionIconBoxSize), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 10.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SidebarSectionHeader(label: String, count: Int, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onSortClick: () -> Unit, modifier: Modifier = Modifier, action: (@Composable BoxScope.() -> Unit)?) {
    com.tencent.kuikly.compose.foundation.layout.Row(modifier = modifier.fillMaxWidth().heightIn(min = 30.dp).clickable { onExpandedChange(!expanded) }, verticalAlignment = Alignment.CenterVertically) {
        SlockLucideIcon(if (expanded) "chevron-down" else "chevron-right", Modifier.size(SectionChevronSize))
        Text(label, style = SlockType.SectionLabel, color = SlockColor.TextBlack, modifier = Modifier.padding(start = SlockSpacing.Xs))
        Text(count.toString(), style = SlockType.Caption, color = SlockColor.TextMutedGray, modifier = Modifier.padding(start = SlockSpacing.Xs))
        Spacer(Modifier.weight(1f))
        HeaderIconAction("arrow-up-down", "Sort $label", onSortClick)
        if (action != null) Box(content = action)
    }
}

@Composable
private fun HeaderIconAction(icon: String, contentDescription: String, onClick: () -> Unit) {
    Box(modifier = Modifier.size(MiniActionSize).clickable(onClick = onClick), contentAlignment = Alignment.Center) { SlockLucideIcon(icon, Modifier.size(MiniActionIconSize)) }
}

@Composable
private fun ChannelRow(name: String, modifier: Modifier = Modifier, symbol: String = "#", avatarLabel: String? = null, avatarUrl: String? = null, avatarWireValue: String? = avatarUrl, presence: SlockPresence? = null, trailingPresence: SlockPresence? = null, badge: String? = null, hasDraft: Boolean = false, joined: Boolean = true, interactionState: ChannelRowInteractionState = ChannelRowInteractionState.Default, onClick: (() -> Unit)? = null) {
    val selected = interactionState == ChannelRowInteractionState.Selected
    val rowBackground = when (interactionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Selected -> SlockColor.AccentPink
        ChannelRowInteractionState.Hovered -> SlockColor.EmptyStateGray
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
    }
    val borderColor = when (interactionState) {
        ChannelRowInteractionState.Default, ChannelRowInteractionState.Hovered -> Color.Transparent
        ChannelRowInteractionState.Selected, ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (selected) Modifier.padding(end = ChannelRowShadowOffset, bottom = ChannelRowShadowOffset) else Modifier)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        if (selected) {
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
            Box(modifier = Modifier.width(ChannelSymbolWidth), contentAlignment = Alignment.Center) {
                if (avatarLabel != null) {
                    ChannelAvatar(avatarWireValue, avatarLabel, presence)
                } else if (symbol == "#") {
                    SlockLucideIcon(
                        "hash",
                        Modifier.size(ChannelSymbolIconSize),
                        color = if (joined) "#FF000000" else "#75000000"
                    )
                } else {
                    Text(symbol, style = SlockType.Button, color = SlockColor.TextBlack.copy(alpha = if (joined) 1f else 0.46f), textAlign = TextAlign.Center)
                }
            }
            Text(name, style = SlockType.Button, color = SlockColor.TextBlack.copy(alpha = if (joined) 1f else 0.46f), modifier = Modifier.padding(start = SidebarActionLabelGap).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) Spacer(Modifier.width(SlockSpacing.Sm)) else if (hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
            if (hasDraft) SlockLucideIcon("pencil", Modifier.size(14.dp), color = "#FF737373")
            if (badge != null) {
                if (hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
                if (joined) CountPill(badge, SlockColor.AccentPink, textOffsetY = ChannelUnreadTextOffsetY) else DimmedUnreadCount(badge)
            }
            if (trailingPresence != null) {
                if (badge != null || hasDraft) Spacer(Modifier.width(SlockSpacing.Sm))
                HomeDmStatusDot(trailingPresence)
            }
        }
    }
}

@Composable
private fun DimmedUnreadCount(text: String) {
    Box(modifier = Modifier.height(CountPillHeight).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 10.sp, fontFamily = FontFamily.Monospace),
            color = SlockColor.TextBlack.copy(alpha = 0.5f),
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.offset(y = ChannelUnreadTextOffsetY)
        )
    }
}

@Composable
private fun ChannelAvatar(avatarWireValue: String?, label: String, presence: SlockPresence? = null) {
    Box(modifier = Modifier.size(ChannelAvatarSize)) {
        SlockAvatar(avatarWireValue, label, Modifier.align(Alignment.Center))
        if (presence != null) PresenceDot(presence, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
internal fun SlockAvatar(avatarWireValue: String?, label: String, modifier: Modifier = Modifier, avatarSize: Dp = ChannelAvatarSize) {
    val pixelSpec = remember(avatarWireValue) { avatarWireValue?.toPixelAvatarSpec() }
    when {
        pixelSpec != null -> PixelAvatar(pixelSpec, modifier, avatarSize)
        avatarWireValue?.startsWith("http://") == true || avatarWireValue?.startsWith("https://") == true -> Box(modifier = modifier.size(avatarSize).background(SlockColor.AccentLavender).border(SlockStroke.Thin, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
            SlockCachedImage(
                source = avatarWireValue,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }
        else -> InitialsAvatar(label, modifier, avatarSize)
    }
}

@Composable
private fun InitialsAvatar(label: String, modifier: Modifier = Modifier, avatarSize: Dp = ChannelAvatarSize) {
    Box(modifier = modifier.size(avatarSize).background(SlockColor.AccentLavender).border(SlockStroke.Thin, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
        Text(label.take(2).uppercase(), style = SlockType.Caption.copy(fontWeight = FontWeight.Medium, fontSize = ChannelAvatarFontSize), color = SlockColor.TextBlack, maxLines = 1)
    }
}

@Composable
private fun PresenceDot(presence: SlockPresence, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(8.dp).background(presence.homeRowColor).border(SlockStroke.Thin, SlockColor.BorderBlack))
}

@Composable
private fun HomeDmStatusDot(presence: SlockPresence) {
    Box(modifier = Modifier.size(10.dp).background(presence.homeRowColor).border(SlockStroke.Thin, SlockColor.BorderBlack))
}

private val SlockPresence.homeRowColor: Color
    get() = when (this) { SlockPresence.Online -> SlockColor.AccentLime; SlockPresence.Offline -> SlockColor.EmptyStateGray; SlockPresence.Unknown -> SlockColor.BrandYellow }

internal fun homeDmStatusPresence(presence: SlockPresence?): SlockPresence = presence ?: SlockPresence.Offline

@Composable
private fun PixelAvatar(spec: PixelAvatarSpec, modifier: Modifier = Modifier, avatarSize: Dp = ChannelAvatarSize) {
    Canvas(modifier = modifier.size(avatarSize).border(SlockStroke.Thin, SlockColor.BorderBlack)) {
        val cellSize = size.width / PixelAvatarGridSize
        drawRect(spec.background)
        spec.grid.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, key ->
                val color = PixelAvatarPalette[key] ?: Color.Transparent
                if (color != Color.Transparent) drawRect(color, topLeft = Offset(columnIndex * cellSize, rowIndex * cellSize), size = Size(cellSize, cellSize))
            }
        }
    }
}

private data class PixelAvatarSpec(val background: Color, val grid: List<String>)
private const val PixelAvatarGridSize = 8f
private const val PixelAvatarProtocolPrefix = "pixel:"
private const val PixelAvatarRandomPrefix = "random:"
private const val PixelAvatarDefaultKey = "robot"
private val PixelAvatarPalette = mapOf('_' to Color.Transparent, 'K' to Color(0xFF141111), 'W' to Color(0xFFFFFFFF), 'P' to Color(0xFFFE7DA8), 'O' to Color(0xFFF8A16F), 'Y' to Color(0xFFFFD440), 'L' to Color(0xFFA9D877), 'C' to Color(0xFF27CCF3), 'V' to Color(0xFFBBAFE6), 'B' to Color(0xFF576574), 'R' to Color(0xFFF97264), 'G' to Color(0xFF9DCAAA))
private val PixelAvatarFixedSpecs = mapOf(
    "robot" to PixelAvatarSpec(PixelAvatarPalette.getValue('C'), listOf("_KKKKKK_", "KCKWWKCK", "KWKWWKWK", "KKKKKKKK", "_KWKKWK_", "_KKKKKK_", "_KK__KK_", "_KK__KK_")),
    "cat" to PixelAvatarSpec(PixelAvatarPalette.getValue('Y'), listOf("K______K", "KK____KK", "KYKYYKYK", "KYYYYYYK", "KYKYYKYK", "_KYYYYK_", "__KYYK__", "___KK___")),
    "ghost" to PixelAvatarSpec(PixelAvatarPalette.getValue('V'), listOf("__KKKK__", "_KWWWWK_", "KWKWWKWK", "KWWWWWWK", "KWWWWWWK", "KWWKKWWK", "KWKWWKWK", "K_K__K_K")),
    "skull" to PixelAvatarSpec(PixelAvatarPalette.getValue('O'), listOf("_KKKKKK_", "KWWWWWWK", "KWKWWKWK", "KWWWWWWK", "_KWWWWK_", "_KWKWKK_", "__KKKK__", "___KK___")),
    "alien" to PixelAvatarSpec(PixelAvatarPalette.getValue('L'), listOf("_KKKKKK_", "KGGGGGGK", "KGKKKKGK", "KGGGGGGK", "_KGGGGK_", "__KGGK__", "__KGGK__", "_KK__KK_")),
    "heart" to PixelAvatarSpec(PixelAvatarPalette.getValue('P'), listOf("________", "_KK__KK_", "KRRKKRRK", "KRRRRRRK", "KRRRRRRK", "_KRRRRK_", "__KRRK__", "___KK___")),
    "star" to PixelAvatarSpec(PixelAvatarPalette.getValue('Y'), listOf("___KK___", "___KK___", "KKKKKKKK", "_KYYYYK_", "__KYYK__", "_KYKKYK_", "KYK__KYK", "KK____KK")),
    "flame" to PixelAvatarSpec(PixelAvatarPalette.getValue('O'), listOf("___K____", "__KRK___", "__KRKK__", "_KORRRK_", "_KOORRK_", "KYOOORK_", "KYYOOK__", "_KKKK___")),
    "diamond" to PixelAvatarSpec(PixelAvatarPalette.getValue('C'), listOf("___KK___", "__KCCK__", "_KCWCCK_", "KCWCCCCK", "KCCCCCCK", "_KCCCCK_", "__KCCK__", "___KK___")),
    "mushroom" to PixelAvatarSpec(PixelAvatarPalette.getValue('P'), listOf("__KKKK__", "_KRWWRK_", "KRRWWRRK", "KRRRRRRK", "_KKKKKK_", "__KWWK__", "__KWWK__", "_KKKKKK_")),
    "eye" to PixelAvatarSpec(PixelAvatarPalette.getValue('V'), listOf("________", "__KKKK__", "_KWWWWK_", "KWWKKWWK", "KWWKKWWK", "_KWWWWK_", "__KKKK__", "________")),
    "crown" to PixelAvatarSpec(PixelAvatarPalette.getValue('Y'), listOf("________", "_K_KK_K_", "_KKKKKK_", "_KYYYYK_", "_KYYYYK_", "KKKKKKKK", "KYYYYYYK", "KKKKKKKK")),
    "cloud" to PixelAvatarSpec(PixelAvatarPalette.getValue('C'), listOf("________", "__KK____", "_KWWKK__", "KWWWWWK_", "KWWWWWWK", "KWWWWWWK", "_KKKKKK_", "________")),
    "sun" to PixelAvatarSpec(PixelAvatarPalette.getValue('R'), listOf("_K_KK_K_", "K_KYYK_K", "_KYYYYK_", "KYYYYYYK", "KYYYYYYK", "_KYYYYK_", "K_KYYK_K", "_K_KK_K_")),
    "bell" to PixelAvatarSpec(PixelAvatarPalette.getValue('B'), listOf("___KK___", "__KYYK__", "_KYYYYK_", "_KYYYYK_", "_KYYYYK_", "KYYYYYYK", "KKKKKKKK", "___KK___")),
    "tree" to PixelAvatarSpec(PixelAvatarPalette.getValue('L'), listOf("___KK___", "__KGGK__", "_KGGGGK_", "KGGGGGGK", "KGGGGGGK", "_KKGGKK_", "__KOOK__", "___KK___"))
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
private fun String.toPixelAvatarSpec(): PixelAvatarSpec? = removePrefixOrNull(PixelAvatarProtocolPrefix)?.let { PixelAvatarFixedSpecs[it] ?: PixelAvatarFixedSpecs.getValue(PixelAvatarDefaultKey) } ?: removePrefixOrNull(PixelAvatarRandomPrefix)?.let(::randomPixelAvatar)
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
private fun CountPill(text: String, color: Color, textOffsetY: Dp = 0.dp) {
    HomeUnreadBadge(text = text, color = color, textOffsetY = textOffsetY)
}

@Composable
private fun HomeUnreadBadge(text: String, color: Color = SlockColor.AccentPink, textOffsetY: Dp = 0.dp) {
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
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 10.sp),
            color = SlockColor.SurfaceWhite,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.offset(y = textOffsetY)
        )
    }
}
