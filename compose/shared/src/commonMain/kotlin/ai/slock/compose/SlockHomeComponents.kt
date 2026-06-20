package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.parseSlockTimestampEpochMillisOrNull
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.module.RouterModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.hoverable
import com.tencent.kuikly.compose.foundation.interaction.MutableInteractionSource
import com.tencent.kuikly.compose.foundation.interaction.collectIsHoveredAsState
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
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
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.Path
import com.tencent.kuikly.compose.ui.graphics.drawscope.Fill
import com.tencent.kuikly.compose.ui.graphics.drawscope.Stroke
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SLOCK_HOME_PAGE = "compose_home"
private const val HOME_TITLEBAR_VISUAL_PAGE = "compose_home_titlebar_visual"
private const val HOME_NOTIFICATION_CENTER_VISUAL_PAGE = "compose_home_notification_center_visual"
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
private val ChannelRowHorizontalPadding = 8.dp
private val ChannelRowShadowOffset = 2.dp
private val DirectMessageStatusDotSize = 8.dp
private val CountPillMinWidth = 18.dp
private val CountPillHeight = 16.dp
private val HomeUnreadBadgeRadius = 4.dp
private val HomeUnreadBadgeTextOffsetY = 0.dp
private val HomeListHorizontalPadding = SlockSpacing.Md
private val HomePanelShadowOffset = 4.dp
private val HomeTiltedBadgeSlant = 3.dp
private const val SharedListDefaultBeyondBoundsItemCount = 3
private const val SharedListMaxBeyondBoundsItemCount = 96
private const val HomeListMaxBeyondBoundsItemCount = 6

@Page(SLOCK_HOME_PAGE, supportInLocal = true)
internal class ComposeSlockHomePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockHomePageContent() }
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
    val kind: String = "",
    val unreadCount: Int = 0,
    val lastMessageAt: String? = null,
    val createdAt: String? = null,
    val description: String? = null,
    val avatarLabel: String = name.take(2).uppercase(),
    val avatarUrl: String? = null,
    val avatarWireValue: String? = avatarUrl,
    val gravatarUrl: String? = null,
    val peerType: String? = null,
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
enum class SlockSidebarSortMode(val wireValue: String, val label: String) {
    Manual("manual", "Manual"),
    Recent("recent", "Recent"),
    Az("az", "A-Z");

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
    val channels: String = "CHANNELS",
    val jointChannels: String = "Joint Channels",
    val directMessages: String = "DIRECT MESSAGES",
    val pinned: String = "PINNED",
    val pin: String = "Pin",
    val unpin: String = "Unpin",
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
    val currentServerName: String = "Raft Android",
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
    val channelSortMode: String = "manual",
    val dmSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val pinnedSortMode: String = "manual",
    val bottomContentPaddingDp: Float = 24f,
    val labels: SlockHomeShellLabels = SlockHomeShellLabels()
)

private enum class ChannelRowInteractionState { Default, Selected, Hovered, Focused, Pressed }

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
    LaunchedEffect(payload.instanceId, payload.channels.size, payload.directMessages.size) {
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
        channelSortMode = payload.channelSortMode,
        dmSortMode = payload.dmSortMode,
        pinnedChannelIds = payload.pinnedChannelIds,
        pinnedOrder = payload.pinnedOrder,
        pinnedSortMode = payload.pinnedSortMode,
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
        onChannelSortModeChange = { bridge.settingsEvent(payload.instanceId, "setChannelSortMode", it.wireValue) },
        onDmSortModeChange = { bridge.settingsEvent(payload.instanceId, "setDmSortMode", it.wireValue) },
        onPinnedSortModeChange = { bridge.settingsEvent(payload.instanceId, "setPinnedSortMode", it.wireValue) },
        onChannelClick = { conversationId -> openThreadPageFromConversationId(pager, payload, conversationId) },
        onChannelLongPress = { conversationId, _ ->
            bridge.settingsEvent(payload.instanceId, "openPinMenu", conversationId)
        },
        onPinToggle = { conversationId ->
            bridge.settingsEvent(payload.instanceId, "togglePinned", conversationId)
        }
    )
}

/**
 * #228: open the thread page for a conversation that the sidebar / pinned list
 * references by id. Replaces the prior `bridge.settingsEvent("openChannel", id)` path
 * which went through the host bridge and silently failed to preserve surfaceKind.
 * Direct `pager.openPage("compose_thread", payload)` route keeps surfaceKind intact
 * so the thread page's `#226` showMembers/showSettings guards and `#227` header label
 * both see the correct kind (Channel / DirectMessage / ReplyThread).
 */
private fun openThreadPageFromConversationId(
    pager: Pager,
    payload: SlockHomePayload,
    conversationId: String
) {
    val conversation =
        payload.channels.firstOrNull { it.id == conversationId }
            ?: payload.directMessages.firstOrNull { it.id == conversationId }
            ?: return
    val surfaceKind =
        when (conversation.kind) {
            "dm", "direct_message", "direct-message", "private" ->
                KuiklyThreadSurfaceKind.DirectMessage
            "thread" -> KuiklyThreadSurfaceKind.ReplyThread
            else -> KuiklyThreadSurfaceKind.Channel
        }
    val threadChannelId = conversationId.takeIf { surfaceKind != KuiklyThreadSurfaceKind.Channel }
    val launchPayload =
        WireThreadLaunchPayload(
            instanceId = "${payload.instanceId}:$conversationId",
            title = "#${conversation.name}",
            subtitle = null,
            surfaceKind = surfaceKind,
            threadId = threadChannelId,
            sourceThreadId = if (surfaceKind == KuiklyThreadSurfaceKind.Channel) conversationId else null,
            streamChannelId = threadChannelId,
            parentChannelId = if (surfaceKind != KuiklyThreadSurfaceKind.Channel) conversationId else null,
            parentMessageId = null
        )
    val pageData =
        JSONObject().apply {
            put("payload", threadPageJson.encodeToString(launchPayload))
            put("instanceId", launchPayload.instanceId)
        }
    pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("compose_thread", pageData)
}

private val threadPageJson = Json { ignoreUnknownKeys = true }

@Composable
private fun HomeTitlebarVisualScreen() {
    Box(modifier = Modifier.width(342.dp).height(110.dp).background(SlockColor.SurfaceWhite).padding(top = 48.dp)) {
        HomeTitlebar(
            serverName = "Raft Design",
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
    currentServerName: String = "Raft Android",
    currentServerId: String = "slock-android",
    servers: List<SlockServerSwitcherItem> = emptyList(),
    channels: List<SlockHomeConversationItem> = emptyList(),
    directMessages: List<SlockHomeConversationItem> = emptyList(),
    selectedChannelId: String? = null,
    selectedConversationSection: SlockHomeConversationSection? = null,
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
    channelSortMode: String = "manual",
    dmSortMode: String = "manual",
    pinnedChannelIds: List<String> = emptyList(),
    pinnedOrder: List<String> = emptyList(),
    pinnedSortMode: String = "manual",
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
    onChannelSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    onDmSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    onPinnedSortModeChange: (SlockSidebarSortMode) -> Unit = {},
    onChannelClick: (String) -> Unit = {},
    onChannelLongPress: (String, Offset) -> Unit = { _, _ -> },
    onPinToggle: (String) -> Unit = {},
    onPinMenuDismiss: () -> Unit = {}
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
    var pinMenuAnchor by remember(currentServerId) { mutableStateOf(Offset.Zero) }
    var refreshIndicatorTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(currentServerId, channelSortMode, dmSortMode, pinnedSortMode) {
        channelSortModeState = SlockSidebarSortMode.fromWireValue(channelSortMode)
        directMessageSortMode = SlockSidebarSortMode.fromWireValue(dmSortMode)
        pinnedSortModeState = SlockSidebarSortMode.fromWireValue(pinnedSortMode)
        activeSortMenu = null
    }
    LaunchedEffect(currentServerId, pinnedChannelIds) {
        // If the active pin menu target was just unpinned (or removed), close the menu
        val activeId = activePinMenuConversationId
        if (activeId != null && activeId !in pinnedChannelIds) {
            activePinMenuConversationId = null
        }
    }
    LaunchedEffect(contentRefreshing) {
        refreshIndicatorTimedOut = false
        if (contentRefreshing) {
            delay(12_000)
            refreshIndicatorTimedOut = true
        }
    }
    val indicatorRefreshing = contentRefreshing && !refreshIndicatorTimedOut
    // #211 React parity (revised by @artin 23:38): pinned rows are
    // shown alongside the original section copy, not deduped. The user
    // sees both, can click either, and either copy is independently
    // pressed / selected. Dedup still applies *inside* the pinned
    // section itself (handled by `pinnedItemsForSidebar`).
    @Suppress("UNUSED_VARIABLE")
    val pinnedIdSet = remember(pinnedChannelIds) { pinnedChannelIds.toSet() }
    val displayedChannels = remember(channels, channelSortModeState) {
        channels.filter { it.kind != "joint" }.sortedForSidebar(channelSortModeState) { it.name }
    }
    val displayedJointChannels = remember(channels, channelSortModeState) {
        channels.filter { it.kind == "joint" }.sortedForSidebar(channelSortModeState) { it.name }
    }
    val displayedDirectMessages = remember(directMessages, directMessageSortMode) {
        directMessages.sortedForSidebar(directMessageSortMode) { it.name }
    }
    val displayedPinned = remember(channels, directMessages, pinnedChannelIds, pinnedOrder, pinnedSortModeState) {
        pinnedItemsForSidebar(
            channels = channels,
            directMessages = directMessages,
            pinnedIds = pinnedChannelIds,
            pinnedOrder = pinnedOrder,
            mode = pinnedSortModeState
        )
    }
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
            contentRefreshEnabled = contentRefreshEnabled && indicatorRefreshing
        )
    val listState = rememberLazyListState()
    val sidebarInteractionsEnabled = !indicatorRefreshing
    val disabledListAction: () -> Unit = {}
    val topSafeArea = if (applyTopSafeArea) slockTopSafeAreaPadding() else 0.dp
    val bottomSafeArea = if (showBottomBar) slockBottomSafeAreaPadding() else 0.dp
    val effectiveBottomContentPadding =
        if (showBottomBar) {
            bottomContentPadding.max(slockAppBottomTabBarContentInset(bottomSafeArea))
        } else {
            bottomContentPadding
        }
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
                        beyondBoundsItemCount = homeListBeyondBoundsItemCount(lazyItemCount)
                    ) {
                        if (contentRefreshEnabled) {
                            if (indicatorRefreshing) {
                                item(key = "slock_refresh_indicator") {
                                    HomePullRefreshIndicator(
                                        pullProgress = 1f,
                                        refreshing = true
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(SlockSpacing.Md)) }
                        item { SidebarActionRow(SlockLucideIconName.Search, labels.search, selected = sidebarSelection.topAction == SlockHomeSidebarTopSelection.Search, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding), onClick = if (sidebarInteractionsEnabled) onSearchClick else null) }
                        item {
                            SidebarActivityRow(
                                labels.inbox,
                                inboxUnreadCount.takeIf { it > 0 }?.toString(),
                                selected = sidebarSelection.topAction == SlockHomeSidebarTopSelection.Activity,
                                modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                onClick = if (sidebarInteractionsEnabled) onInboxClick else null
                            )
                        }
                        item { SidebarActionRow(SlockLucideIconName.Bookmark, labels.saved, selected = sidebarSelection.topAction == SlockHomeSidebarTopSelection.Saved, modifier = Modifier.padding(horizontal = HomeListHorizontalPadding), trailingText = savedCount.takeIf { it > 0 }?.toString(), onClick = if (sidebarInteractionsEnabled) onSavedClick else null) }
                        if (displayedPinned.isNotEmpty()) {
                            item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                            item {
                                SlockCollapsibleSectionHeader(
                                    label = labels.pinned,
                                    count = displayedPinned.size,
                                    expanded = pinnedExpanded,
                                    onExpandedChange = { if (sidebarInteractionsEnabled) pinnedExpanded = it },
                                    sortContentDescription = "Sort ${labels.pinned}",
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
                                items(displayedPinned.size, key = { index -> "pinned-${displayedPinned[index].id}" }) { index ->
                                    val pinned = displayedPinned[index]
                                    val isJoint = pinned.kind.equals("joint", ignoreCase = true)
                                    val isDirectMessage = pinned.kind.equals("directmessage", ignoreCase = true)
                                    // #211 React parity (revised by @artin 23:38): pinned copy and
                                    // original-section copy of the same conversation both
                                    // render Selected when their conversationId matches.
                                    // Selected wins over pressed so the user never confuses
                                    // "I'm pressing" with "this is the current conversation".
                                    val rowInteractionState = when {
                                        sidebarSelection.conversationId == pinned.id -> ChannelRowInteractionState.Selected
                                        pressedRowKey == "pinned:${pinned.id}" -> ChannelRowInteractionState.Pressed
                                        else -> ChannelRowInteractionState.Default
                                    }
                                    val handleLongPress: (Offset) -> Unit = { anchor ->
                                        onChannelLongPress(pinned.id, anchor)
                                        activePinMenuConversationId = pinned.id
                                        pinMenuAnchor = anchor
                                        // Menu opens: clear press so the
                                        // visual doesn't stick while the
                                        // user reads the menu.
                                        pressedRowKey = null
                                    }
                                    if (isDirectMessage) {
                                        DirectMessageRow(
                                            name = pinned.name,
                                            description = pinned.description,
                                            avatarLabel = pinned.avatarLabel,
                                            avatarWireValue = pinned.avatarWireValue,
                                            gravatarUrl = pinned.gravatarUrl,
                                            peerType = pinned.peerType,
                                            presence = pinned.presence,
                                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                            hasDraft = pinned.hasDraft,
                                            badge = pinned.unreadCount.takeIf { it > 0 }?.toString(),
                                            interactionState = rowInteractionState,
                                            onClick = if (sidebarInteractionsEnabled) ({ onChannelClick(pinned.id) }) else null,
                                            onLongPress = if (sidebarInteractionsEnabled) handleLongPress else null,
                                            onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "pinned:${pinned.id}" }) else null,
                                            onPressEnd = { if (pressedRowKey == "pinned:${pinned.id}") pressedRowKey = null }
                                        )
                                    } else {
                                        ChannelRow(
                                            name = pinned.name,
                                            symbol = if (isJoint) "git-branch" else "#",
                                            joined = pinned.joined,
                                            hasDraft = pinned.hasDraft,
                                            badge = pinned.unreadCount.takeIf { it > 0 }?.toString(),
                                            modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                            interactionState = rowInteractionState,
                                            onClick = if (sidebarInteractionsEnabled) ({ onChannelClick(pinned.id) }) else null,
                                            onLongPress = if (sidebarInteractionsEnabled) handleLongPress else null,
                                            onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "pinned:${pinned.id}" }) else null,
                                            onPressEnd = { if (pressedRowKey == "pinned:${pinned.id}") pressedRowKey = null }
                                        )
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                        item {
                            SlockCollapsibleSectionHeader(
                                label = labels.channels,
                                count = displayedChannels.size,
                                expanded = channelsExpanded,
                                onExpandedChange = { if (sidebarInteractionsEnabled) channelsExpanded = it },
                                sortContentDescription = "Sort ${labels.channels}",
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
                            items(displayedChannels.size, key = { index -> "channel-${displayedChannels[index].id}" }) { index ->
                                val channel = displayedChannels[index]
                                // #211 review (Cindy): selected wins over pressed.
                                val channelInteractionState = when {
                                    sidebarSelection.conversationId == channel.id -> ChannelRowInteractionState.Selected
                                    pressedRowKey == "channels:${channel.id}" -> ChannelRowInteractionState.Pressed
                                    else -> ChannelRowInteractionState.Default
                                }
                                ChannelRow(
                                    name = channel.name,
                                    joined = channel.joined,
                                    hasDraft = channel.hasDraft,
                                    badge = channel.unreadCount.takeIf { it > 0 }?.toString(),
                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                    interactionState = channelInteractionState,
                                    onClick = if (sidebarInteractionsEnabled) ({ onChannelClick(channel.id) }) else null,
                                    onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                        onChannelLongPress(channel.id, anchor)
                                        activePinMenuConversationId = channel.id
                                        pinMenuAnchor = anchor
                                        pressedRowKey = null
                                    }) else null,
                                    onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "channels:${channel.id}" }) else null,
                                    onPressEnd = { if (pressedRowKey == "channels:${channel.id}") pressedRowKey = null }
                                )
                            }
                        }
                        if (displayedJointChannels.isNotEmpty()) {
                            item { Spacer(Modifier.height(SlockSpacing.Xs)) }
                            item {
                                SlockCollapsibleSectionHeader(
                                    label = labels.jointChannels,
                                    count = displayedJointChannels.size,
                                    expanded = jointChannelsExpanded,
                                    onExpandedChange = { if (sidebarInteractionsEnabled) jointChannelsExpanded = it },
                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding)
                                )
                            }
                            if (jointChannelsExpanded) {
                                items(displayedJointChannels.size, key = { index -> "joint-${displayedJointChannels[index].id}" }) { index ->
                                    val channel = displayedJointChannels[index]
                                    val jointInteractionState = when {
                                        sidebarSelection.conversationId == channel.id -> ChannelRowInteractionState.Selected
                                        pressedRowKey == "channels:${channel.id}" -> ChannelRowInteractionState.Pressed
                                        else -> ChannelRowInteractionState.Default
                                    }
                                    ChannelRow(
                                        name = channel.name,
                                        symbol = "git-branch",
                                        joined = channel.joined,
                                        hasDraft = channel.hasDraft,
                                        badge = channel.unreadCount.takeIf { it > 0 }?.toString(),
                                        modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                        interactionState = jointInteractionState,
                                        onClick = if (sidebarInteractionsEnabled) ({ onChannelClick(channel.id) }) else null,
                                        onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                            onChannelLongPress(channel.id, anchor)
                                            activePinMenuConversationId = channel.id
                                            pinMenuAnchor = anchor
                                            pressedRowKey = null
                                        }) else null,
                                        onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "channels:${channel.id}" }) else null,
                                        onPressEnd = { if (pressedRowKey == "channels:${channel.id}") pressedRowKey = null }
                                    )
                                }
                            }
                        }
                        item {
                            SlockCollapsibleSectionHeader(
                                label = labels.directMessages,
                                count = displayedDirectMessages.size,
                                expanded = directMessagesExpanded,
                                onExpandedChange = { if (sidebarInteractionsEnabled) directMessagesExpanded = it },
                                sortContentDescription = "Sort ${labels.directMessages}",
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
                            items(displayedDirectMessages.size, key = { index -> "dm-${displayedDirectMessages[index].id}" }) { index ->
                                val dm = displayedDirectMessages[index]
                                val dmInteractionState = when {
                                    sidebarSelection.conversationId == dm.id -> ChannelRowInteractionState.Selected
                                    pressedRowKey == "dms:${dm.id}" -> ChannelRowInteractionState.Pressed
                                    else -> ChannelRowInteractionState.Default
                                }
                                DirectMessageRow(
                                    name = dm.name,
                                    description = dm.description,
                                    avatarLabel = dm.avatarLabel,
                                    avatarWireValue = dm.avatarWireValue,
                                    gravatarUrl = dm.gravatarUrl,
                                    peerType = dm.peerType,
                                    presence = dm.presence,
                                    modifier = Modifier.padding(horizontal = HomeListHorizontalPadding),
                                    hasDraft = dm.hasDraft,
                                    badge = dm.unreadCount.takeIf { it > 0 }?.toString(),
                                    interactionState = dmInteractionState,
                                    onClick = if (sidebarInteractionsEnabled) ({ onChannelClick(dm.id) }) else null,
                                    onLongPress = if (sidebarInteractionsEnabled) ({ anchor ->
                                        onChannelLongPress(dm.id, anchor)
                                        activePinMenuConversationId = dm.id
                                        pinMenuAnchor = anchor
                                        pressedRowKey = null
                                    }) else null,
                                    onPress = if (sidebarInteractionsEnabled) ({ pressedRowKey = "dms:${dm.id}" }) else null,
                                    onPressEnd = { if (pressedRowKey == "dms:${dm.id}") pressedRowKey = null }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(effectiveBottomContentPadding)) }
                    }
                }
            }
            if (serverSwitcherExpanded) {
                HomeServerSwitcherDismissLayer(onServerSwitcherDismiss)
                ServerSwitcherPanel(servers, currentServerId, pendingServerId, labels, onServerSelected, onCreateServerClick, Modifier.padding(top = topSafeArea + SlockSize.PageHeaderHeight, start = SlockSpacing.Lg))
            }
            activeSortMenu?.let { section ->
                SlockBaseMenuOverlay(
                    anchor = sortMenuAnchor,
                    items = SlockHomeSortMenuItems,
                    selectedId = when (section) {
                        SlockHomeSortSection.Channels -> channelSortModeState.wireValue
                        SlockHomeSortSection.DirectMessages -> directMessageSortMode.wireValue
                        SlockHomeSortSection.Pinned -> pinnedSortModeState.wireValue
                    },
                    onDismiss = { activeSortMenu = null },
                    onSelect = { selected ->
                        val nextMode = SlockSidebarSortMode.fromWireValue(selected)
                        when (section) {
                            SlockHomeSortSection.Channels -> {
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
            val activePinId = activePinMenuConversationId
            val pinTargetIsPinned = activePinId != null && activePinId in pinnedIdSet
            val pinMenuItem = activePinId?.let { id ->
                val label = if (pinTargetIsPinned) labels.unpin else labels.pin
                val icon = if (pinTargetIsPinned) SlockLucideIconName.PinOff else SlockLucideIconName.Pin
                SlockBaseMenuItem(id = "toggle-pin", label = label, icon = icon)
            }
            if (activePinId != null && pinMenuItem != null) {
                SlockBaseMenuOverlay(
                    anchor = pinMenuAnchor,
                    items = listOf(pinMenuItem),
                    selectedId = "",
                    onDismiss = {
                        activePinMenuConversationId = null
                        onPinMenuDismiss()
                    },
                    onSelect = { _ ->
                        onPinToggle(activePinId)
                        activePinMenuConversationId = null
                    }
                )
            }
        }
        if (showBottomBar) {
            SlockAppBottomTabBarHost(
                items = listOf(
                    SlockBottomTabItemPayload("home", "HOME", SlockLucideIconName.House, selected = selectedTabKey == "home"),
                    SlockBottomTabItemPayload("tasks", "TASKS", SlockLucideIconName.SquareCheckBig, selected = selectedTabKey == "tasks"),
                    SlockBottomTabItemPayload("members", "MEMBERS", SlockLucideIconName.Users, selected = selectedTabKey == "members"),
                    SlockBottomTabItemPayload("settings", "SETTINGS", SlockLucideIconName.Settings, selected = selectedTabKey == "settings")
                ),
                onSelect = onTabSelected,
                modifier = Modifier.fillMaxWidth(),
                bottomSafeArea = bottomSafeArea
            )
        }
    }
}

private fun Dp.max(other: Dp): Dp =
    if (this >= other) this else other

internal fun sharedListBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceIn(
        minimumValue = SharedListDefaultBeyondBoundsItemCount,
        maximumValue = SharedListMaxBeyondBoundsItemCount
    )

internal fun homeListBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceIn(
        minimumValue = SharedListDefaultBeyondBoundsItemCount,
        maximumValue = HomeListMaxBeyondBoundsItemCount
    )

internal fun homeLazyItemCount(
    channelsCount: Int,
    jointCount: Int,
    directMessagesCount: Int,
    pinnedCount: Int = 0,
    channelsExpanded: Boolean,
    jointChannelsExpanded: Boolean,
    directMessagesExpanded: Boolean,
    pinnedExpanded: Boolean = false,
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
    val jointSectionItems = if (jointCount > 0) 2 else 0 // spacer + header
    val pinnedSectionItems = if (pinnedCount > 0) 2 else 0 // header + (rows below); rows counted conditionally
    return fixedItems +
        (if (contentRefreshEnabled) 1 else 0) +
        jointSectionItems +
        pinnedSectionItems +
        (if (channelsExpanded) channelsCount else 0) +
        (if (jointChannelsExpanded) jointCount else 0) +
        (if (directMessagesExpanded) directMessagesCount else 0) +
        (if (pinnedExpanded) pinnedCount else 0)
}

private enum class SlockHomeSortSection { Channels, DirectMessages, Pinned }

private val SlockHomeSortMenuItems =
    SlockSidebarSortMode.entries.map { mode ->
        SlockBaseMenuItem(id = mode.wireValue, label = mode.label)
    }

internal fun homeDmStatusPresence(presence: SlockPresence?): SlockPresence =
    presence ?: SlockPresence.Offline

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
        return if (channel.kind.equals("joint", ignoreCase = true)) {
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
    label: (SlockHomeConversationItem) -> String
): List<SlockHomeConversationItem> =
    when (mode) {
        SlockSidebarSortMode.Manual -> this
        SlockSidebarSortMode.Recent ->
            sortedWith(
                compareByDescending<SlockHomeConversationItem> {
                    parseSlockTimestampEpochMillisOrNull(it.lastMessageAt ?: it.createdAt) ?: Long.MIN_VALUE
                }.thenBy { label(it).trim().lowercase() }.thenBy { it.id }
            )
        SlockSidebarSortMode.Az ->
            sortedWith(compareBy<SlockHomeConversationItem> { label(it).trim().lowercase() }.thenBy { it.id })
    }

/**
 * Reconstruct the Pinned section from raw `pinnedChannelIds` and `pinnedOrder`
 * (server side `SettingsServerSidebarOrderSnapshot`).
 *
 * Manual mode: items in `pinnedOrder` (drop unknown ids), then any remaining
 * pinned ids not present in `pinnedOrder` appended in input order.
 * Recent mode: sorted by `lastMessageAt ?: createdAt` desc, then name.
 * A-Z mode: sorted by display name asc.
 *
 * Items must be present in either `channels` (regular or joint) or
 * `directMessages`; pinned ids that no longer exist are dropped.
 */
internal fun pinnedItemsForSidebar(
    channels: List<SlockHomeConversationItem>,
    directMessages: List<SlockHomeConversationItem>,
    pinnedIds: List<String>,
    pinnedOrder: List<String>,
    mode: SlockSidebarSortMode
): List<SlockHomeConversationItem> {
    if (pinnedIds.isEmpty()) return emptyList()
    val byId: Map<String, SlockHomeConversationItem> = buildMap {
        channels.forEach { put(it.id, it) }
        directMessages.forEach { put(it.id, it) }
    }
    val pinnedSet = pinnedIds.toSet()
    val existingPinned = pinnedIds.mapNotNull { byId[it] }
    return when (mode) {
        SlockSidebarSortMode.Manual -> {
            val ordered = LinkedHashMap<String, SlockHomeConversationItem>()
            pinnedOrder.forEach { id ->
                byId[id]?.takeIf { it.id in pinnedSet }?.let { ordered.put(it.id, it) }
            }
            existingPinned.forEach { item ->
                if (!ordered.containsKey(item.id)) {
                    ordered.put(item.id, item)
                }
            }
            ordered.values.toList()
        }
        SlockSidebarSortMode.Recent ->
            existingPinned.sortedWith(
                compareByDescending<SlockHomeConversationItem> {
                    parseSlockTimestampEpochMillisOrNull(it.lastMessageAt ?: it.createdAt) ?: Long.MIN_VALUE
                }.thenBy { it.name.trim().lowercase() }.thenBy { it.id }
            )
        SlockSidebarSortMode.Az ->
            existingPinned.sortedWith(
                compareBy<SlockHomeConversationItem> { it.name.trim().lowercase() }.thenBy { it.id }
            )
    }
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
    var jumpStep by remember { mutableStateOf(0) }
    LaunchedEffect(refreshing) {
        if (!refreshing) {
            jumpStep = 0
            return@LaunchedEffect
        }
        while (true) {
            jumpStep = (jumpStep + 1) % SlockRefreshLetters.length
            delay(120)
        }
    }
    val pullOffset = ((1f - pullProgress.coerceIn(0f, 1f)) * 8f).dp
    Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
        SlockJumpingLogoBadge(
            activeIndex = if (refreshing) jumpStep else -1,
            modifier = Modifier.offset(y = if (refreshing) 0.dp else pullOffset)
        )
    }
}

private const val SlockRefreshLetters = "RAFT"

@Composable
private fun SlockJumpingLogoBadge(activeIndex: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(end = 2.dp, bottom = 2.dp).rotate(-2f)) {
        Box(Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier = Modifier.background(SlockColor.TextBlack).border(SlockStroke.Default, SlockColor.BorderBlack).padding(horizontal = 14.dp, vertical = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SlockRefreshLetters.forEachIndexed { index, letter ->
                val letterOffset =
                    when {
                        activeIndex == index -> (-5).dp
                        activeIndex >= 0 && (activeIndex + SlockRefreshLetters.length - 1) % SlockRefreshLetters.length == index -> 2.dp
                        else -> 0.dp
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
                    tilted = true,
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
    textStyle: TextStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp)
) {
    SlockRotatedLogoBadge(
        text = text,
        modifier = modifier,
        showDropdown = showDropdown,
        unread = unread,
        textStyle = textStyle
    )
}

@Composable
private fun SlockRotatedLogoBadge(
    text: String,
    modifier: Modifier = Modifier,
    showDropdown: Boolean = false,
    unread: Boolean = false,
    textStyle: TextStyle = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp)
) {
    Box(modifier = modifier.padding(end = HomeSmallShadowOffset, bottom = HomeSmallShadowOffset).rotate(-2f)) {
        RectangleBadgeChrome(
            color = SlockColor.ShadowBlack,
            borderColor = Color.Transparent,
            modifier = Modifier.matchParentSize().offset(x = HomeSmallShadowOffset, y = HomeSmallShadowOffset)
        )
        RectangleBadgeChrome(
            color = SlockColor.TextBlack,
            borderColor = SlockColor.BorderBlack,
            modifier = Modifier.matchParentSize()
        )
        com.tencent.kuikly.compose.foundation.layout.Row(
            modifier =
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, color = SlockColor.BrandYellow, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            if (showDropdown) {
                Spacer(Modifier.width(4.dp))
                SlockLucideIcon(SlockLucideIconName.ChevronDown, Modifier.size(15.dp).rotate(2f), color = SlockColor.BrandYellowArgb)
            }
        }
        if (unread) ServerUnreadMarker(modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun RectangleBadgeChrome(
    color: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = SlockStroke.Default.toPx()
        drawRect(color = color, style = Fill)
        if (borderColor != Color.Transparent) {
            drawRect(color = borderColor, style = Stroke(width = strokeWidth))
        }
    }
}

@Composable
private fun SlantedBadgeChrome(
    color: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val slant = HomeTiltedBadgeSlant.toPx()
        val strokeWidth = SlockStroke.Default.toPx()
        val path =
            Path().apply {
                moveTo(slant, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width - slant, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(path = path, color = color, style = Fill)
        if (borderColor != Color.Transparent) {
            drawPath(path = path, color = borderColor, style = Stroke(width = strokeWidth))
        }
    }
}

@Composable
private fun ServerUnreadMarker(modifier: Modifier = Modifier) {
    SlockStatusIndicator(
        color = SlockColor.AccentPink,
        size = SlockStatusIndicatorTokens.ServerUnreadSize,
        modifier = modifier.offset(x = 4.dp, y = (-4).dp)
    )
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
                SlockLucideIcon(SlockLucideIconName.TriangleAlert, Modifier.size(16.dp))
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
            SlockLucideIcon(
                when (notification.kind) {
                    SlockHomeNotificationKind.Warning -> SlockLucideIconName.CircleArrowUp
                    SlockHomeNotificationKind.Info -> SlockLucideIconName.BellRing
                    SlockHomeNotificationKind.Error -> SlockLucideIconName.TriangleAlert
                },
                Modifier.size(16.dp)
            )
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
    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
        backgroundColor = color,
        minWidth = 72.dp,
        minHeight = 32.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Medium)
    )
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
                pending -> Text("...", style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(lineHeight = 14.sp), color = SlockColor.TextBlack, maxLines = 1, textAlign = TextAlign.Center)
                selected -> SlockLucideIcon(SlockLucideIconName.Check, Modifier.size(14.dp))
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
        SlockLucideIcon(SlockLucideIconName.Plus, Modifier.size(14.dp))
        Text(label, style = SlockType.ButtonBold, color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SidebarActivityRow(label: String, count: String?, selected: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    if (!selected) {
        SidebarActionRow(
            icon = SlockLucideIconName.Activity,
            label = label,
            modifier = modifier,
            badge = count,
            onClick = onClick
        )
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
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
                    SlockLucideIcon(SlockLucideIconName.Activity, Modifier.size(SidebarActionIconSize))
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
private fun SidebarActionRow(icon: SlockLucideIconName, label: String, modifier: Modifier = Modifier, selected: Boolean = false, badge: String? = null, trailingText: String? = null, onClick: (() -> Unit)? = null) {
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
private fun ChannelRow(name: String, modifier: Modifier = Modifier, symbol: String = "#", avatarLabel: String? = null, avatarUrl: String? = null, avatarWireValue: String? = avatarUrl, presence: SlockPresence? = null, showPresence: Boolean = true, badge: String? = null, hasDraft: Boolean = false, joined: Boolean = true, interactionState: ChannelRowInteractionState = ChannelRowInteractionState.Default, onClick: (() -> Unit)? = null, onLongPress: ((Offset) -> Unit)? = null, onPress: (() -> Unit)? = null, onPressEnd: (() -> Unit)? = null) {
    // #211 review (Cindy / @Android-Developer-2 01:27): hover is
    // detected locally so the visual layer can react without the shell
    // having to track per-row hover. Selected still wins: a currently
    // selected row keeps its pink highlight on hover.
    val hoverInteractionSource = remember { MutableInteractionSource() }
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
        ChannelRowInteractionState.Hovered -> SlockColor.EmptyStateGray
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Pressed -> SlockColor.EmptyStateGray
    }
    val borderColor = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Hovered, ChannelRowInteractionState.Pressed, ChannelRowInteractionState.Selected, ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    var rowAnchor by remember { mutableStateOf(Offset.Zero) }
    val hoverableModifier = Modifier.hoverable(hoverInteractionSource)
    val clickModifier = remember(onClick, onLongPress, onPress, onPressEnd) {
        if (onLongPress != null) {
            Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress?.invoke()
                        tryAwaitRelease()
                        onPressEnd?.invoke()
                    },
                    onTap = { onClick?.invoke() },
                    onLongPress = { onLongPress.invoke(rowAnchor) }
                )
            }
        } else if (onClick != null) {
            Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress?.invoke()
                        tryAwaitRelease()
                        onPressEnd?.invoke()
                    },
                    onTap = { onClick?.invoke() }
                )
            }
        } else {
            Modifier
        }
    }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    // Pin/Unpin menu anchor = row's bottom-left, so the
                    // menu lands 8dp below the row's bottom edge
                    // (SlockBaseMenuVerticalOffset). Top-left would put
                    // the menu inside the row's vertical bounds because
                    // the row is taller than the menu.
                    val pos = coordinates.positionInRoot()
                    val size = coordinates.size
                    rowAnchor = Offset(x = pos.x, y = pos.y + size.height)
                }
                .then(if (selected) Modifier.padding(end = ChannelRowShadowOffset, bottom = ChannelRowShadowOffset) else Modifier)
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
            Box(modifier = Modifier.width(ChannelSymbolWidth), contentAlignment = Alignment.Center) {
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
                } else {
                    Text(symbol, style = SlockType.Button, color = SlockColor.TextBlack.copy(alpha = if (joined) 1f else 0.46f), textAlign = TextAlign.Center)
                }
            }
            Text(name, style = SlockType.Button, color = SlockColor.TextBlack.copy(alpha = if (joined) 1f else 0.46f), modifier = Modifier.padding(start = SidebarActionLabelGap).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) Spacer(Modifier.width(SlockSpacing.Sm)) else if (hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
            if (hasDraft) SlockLucideIcon(SlockLucideIconName.Pencil, Modifier.size(14.dp), color = SlockColor.TextMutedGrayArgb)
            if (badge != null) {
                if (hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
                if (joined) CountPill(badge, SlockColor.AccentPink) else DimmedUnreadCount(badge)
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
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DirectMessageRow(name: String, description: String? = null, modifier: Modifier = Modifier, avatarLabel: String, avatarWireValue: String? = null, gravatarUrl: String? = null, peerType: String? = null, presence: SlockPresence? = null, badge: String? = null, hasDraft: Boolean = false, interactionState: ChannelRowInteractionState = ChannelRowInteractionState.Default, onClick: (() -> Unit)? = null, onLongPress: ((Offset) -> Unit)? = null, onPress: (() -> Unit)? = null, onPressEnd: (() -> Unit)? = null) {
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val isHovered by hoverInteractionSource.collectIsHoveredAsState()
    val selected = interactionState == ChannelRowInteractionState.Selected
    val pressed = interactionState == ChannelRowInteractionState.Pressed
    val unread = badge != null
    val isAgent = peerType?.equals("agent", ignoreCase = true) == true
    val descriptionText = description?.takeIf { it.isNotBlank() }
    val effectiveInteractionState = when {
        selected -> ChannelRowInteractionState.Selected
        pressed -> ChannelRowInteractionState.Pressed
        isHovered -> ChannelRowInteractionState.Hovered
        else -> interactionState
    }
    val rowBackground = when (effectiveInteractionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Selected -> SlockColor.AccentPink
        ChannelRowInteractionState.Hovered -> SlockColor.EmptyStateGray
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
        ChannelRowInteractionState.Pressed -> SlockColor.EmptyStateGray
    }
    val borderColor = when (interactionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
        ChannelRowInteractionState.Hovered, ChannelRowInteractionState.Pressed, ChannelRowInteractionState.Selected, ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    var rowAnchor by remember { mutableStateOf(Offset.Zero) }
    val hoverableModifier = Modifier.hoverable(hoverInteractionSource)
    val clickModifier = remember(onClick, onLongPress, onPress, onPressEnd) {
        if (onLongPress != null) {
            Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress?.invoke()
                        tryAwaitRelease()
                        onPressEnd?.invoke()
                    },
                    onTap = { onClick?.invoke() },
                    onLongPress = { onLongPress.invoke(rowAnchor) }
                )
            }
        } else if (onClick != null) {
            Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress?.invoke()
                        tryAwaitRelease()
                        onPressEnd?.invoke()
                    },
                    onTap = { onClick?.invoke() }
                )
            }
        } else {
            Modifier
        }
    }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    // Pin/Unpin menu anchor = row's bottom-left, so the
                    // menu lands 8dp below the row's bottom edge
                    // (SlockBaseMenuVerticalOffset). Top-left would put
                    // the menu inside the row's vertical bounds because
                    // the row is taller than the menu.
                    val pos = coordinates.positionInRoot()
                    val size = coordinates.size
                    rowAnchor = Offset(x = pos.x, y = pos.y + size.height)
                }
                .then(if (selected) Modifier.padding(end = ChannelRowShadowOffset, bottom = ChannelRowShadowOffset) else Modifier)
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
            Box(modifier = Modifier.width(ChannelSymbolWidth), contentAlignment = Alignment.Center) {
                SlockAvatar(avatarWireValue, avatarLabel, gravatarUrl = gravatarUrl)
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
                    color = SlockColor.TextBlack,
                    modifier =
                        if (descriptionText != null) {
                            Modifier.widthIn(max = 260.dp)
                        } else {
                            Modifier.weight(1f)
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                DirectMessageStatusDot(homeDmStatusPresence(presence))
            }
            if (hasDraft) {
                if (isAgent) Spacer(Modifier.width(SlockSpacing.Xs))
                SlockLucideIcon(SlockLucideIconName.Pencil, Modifier.size(14.dp), color = SlockColor.TextMutedGrayArgb)
            }
            if (badge != null) {
                if (isAgent || hasDraft) Spacer(Modifier.width(SlockSpacing.Xs))
                CountPill(badge, SlockColor.AccentPink)
            }
        }
    }
}

@Composable
private fun DirectMessageStatusDot(presence: SlockPresence) {
    Box(
        modifier =
            Modifier
                .size(DirectMessageStatusDotSize)
                .borderRadius(4.dp)
                .background(presence.homeRowColor)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
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
    borderWidth: Dp = SlockStroke.Thin,
    gravatarUrl: String? = null
) {
    val pixelSpec = remember(avatarWireValue) { avatarWireValue?.toPixelAvatarSpec() }
    when {
        pixelSpec != null -> PixelAvatar(pixelSpec, modifier, avatarSize, borderWidth)
        avatarWireValue?.startsWith("http://") == true || avatarWireValue?.startsWith("https://") == true -> Box(modifier = modifier.size(avatarSize).background(SlockColor.AccentLavender).border(borderWidth, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
            DefaultUserAvatarIcon(avatarSize)
            SlockAvatarHttpImage(
                source = avatarWireValue,
                fallbackSource = gravatarUrl,
                modifier = Modifier.fillMaxSize()
            )
        }
        else -> DefaultUserAvatar(modifier, avatarSize, borderWidth)
    }
}

@Composable
private fun SlockAvatarHttpImage(
    source: String,
    modifier: Modifier = Modifier,
    fallbackSource: String? = null
) {
    // #225 fix: HTTP avatar (e.g. peerAvatarUrl) may fail to load; if a
    // Gravatar URL is provided, retry with it before showing the user icon
    // underneath. Mirrors React AvatarSlot / GravatarAvatar chain.
    var primaryLoaded by remember(source) { mutableStateOf(false) }
    var primaryFailed by remember(source) { mutableStateOf(false) }
    val showFallback = primaryFailed && !fallbackSource.isNullOrBlank()
    val effectiveSource = if (showFallback) fallbackSource else source
    val painter =
        rememberAsyncImagePainter(
            model = effectiveSource,
            onLoading = { primaryLoaded = false },
            onSuccess = { primaryLoaded = true },
            onError = {
                if (!showFallback) primaryFailed = true
            }
        )
    if (primaryLoaded) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.FillBounds
        )
    }
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
    get() = when (this) { SlockPresence.Online -> SlockColor.AccentLime; SlockPresence.Offline -> SlockColor.EmptyStateGray; SlockPresence.Unknown -> SlockColor.BrandYellow }

@Composable
private fun PixelAvatar(
    spec: PixelAvatarSpec,
    modifier: Modifier = Modifier,
    avatarSize: Dp = ChannelAvatarSize,
    borderWidth: Dp = SlockStroke.Thin
) {
    Canvas(modifier = modifier.size(avatarSize).border(borderWidth, SlockColor.BorderBlack)) {
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
