package ai.slock.android.ui.components

import ai.slock.android.ui.icons.LucideIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import ai.slock.android.R
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSize
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

private val SidebarActionIconSize = 14.dp
private val SidebarActionIconBoxSize = 22.dp
private val SidebarActionRowHeight = 38.dp
private val SidebarRowHorizontalPadding = 8.dp
private val SidebarActionLabelGap = 10.dp
private val ChannelSymbolWidth = 22.dp
private val ChannelSymbolFontSize = 16.sp
private val ChannelAvatarSize = 20.dp
private val ChannelAvatarFontSize = 11.sp
private val ChannelRowHeight = 32.dp
private val HomeBottomTabListReserve = SlockSize.BottomBarHeight + SlockSpacing.Md
private val ChannelRowHorizontalPadding = 8.dp
private val SectionChevronSize = 12.dp
private val MiniActionSize = 30.dp
private val MiniActionIconSize = 16.dp
private val CountPillMinWidth = 20.dp
private val CountPillHeight = 18.dp

data class SlockServerSwitcherItem(
    val id: String,
    val name: String,
    val slug: String,
    val unreadCount: Int = 0
)

data class SlockHomeConversationItem(
    val id: String,
    val name: String,
    val unreadCount: Int = 0,
    val avatarLabel: String = name.take(2).uppercase(),
    val avatarUrl: String? = null,
    val avatar: AvatarReference? = AvatarReference.fromWireValue(avatarUrl),
    val presence: SlockPresence? = null,
    val hasDraft: Boolean = false,
    val joined: Boolean = true
)

data class SlockHomeShellLabels(
    val search: String,
    val inbox: String,
    val followedThreads: String,
    val saved: String,
    val channels: String,
    val directMessages: String,
    val addChannel: String,
    val noServersAvailable: String,
    val switchOrCreateServer: String
) {
    companion object {
        @Composable
        fun defaults(): SlockHomeShellLabels =
            SlockHomeShellLabels(
                search = stringResource(R.string.home_search),
                inbox = stringResource(R.string.home_inbox),
                followedThreads = stringResource(R.string.home_followed_threads),
                saved = stringResource(R.string.home_saved),
                channels = stringResource(R.string.home_channels).uppercase(),
                directMessages = stringResource(R.string.home_direct_messages).uppercase(),
                addChannel = stringResource(R.string.home_add_channel),
                noServersAvailable = stringResource(R.string.home_no_servers_available),
                switchOrCreateServer = stringResource(R.string.home_switch_or_create_server)
            )
    }
}

private enum class ChannelRowInteractionState {
    Default,
    Selected,
    Hovered,
    Focused
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SlockStructuralComponentsPreview() {
    SlockTheme {
        SlockStructuralHomeShell(servers = defaultServerSwitcherItems)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 120)
@Composable
private fun SlockBottomTabBarFramePreview() {
    SlockTheme {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .padding(vertical = SlockSpacing.Xl)
        ) {
            SlockBottomTabBar(
                items =
                    listOf(
                        SlockBottomTabItem("home", "HOME", LucideIcons.House),
                        SlockBottomTabItem("tasks", "TASKS", LucideIcons.SquareCheckBig),
                        SlockBottomTabItem("members", "MEMBERS", LucideIcons.Users),
                        SlockBottomTabItem("settings", "SETTINGS", LucideIcons.Settings)
                    ),
                selectedKey = "tasks",
                onSelected = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

data class SlockHomeNotificationItem(
    val id: String,
    val title: String,
    val body: String,
    val actionLabel: String = "View",
    val targetId: String? = null,
    val kind: SlockHomeNotificationKind = SlockHomeNotificationKind.Warning
)

enum class SlockHomeNotificationKind {
    Warning,
    Info,
    Error
}

@Composable
fun SlockStructuralHomeShell(
    modifier: Modifier = Modifier,
    currentServerName: String = "Slock Android",
    currentServerId: String = "slock-android",
    servers: List<SlockServerSwitcherItem> = emptyList(),
    channels: List<SlockHomeConversationItem> = defaultChannelItems,
    directMessages: List<SlockHomeConversationItem> = emptyList(),
    selectedChannelId: String? = "android-dev",
    serverUnreadCount: Int = 5,
    inboxUnreadCount: Int = 0,
    savedCount: Int = 0,
    notifications: List<SlockHomeNotificationItem> = emptyList(),
    serverSwitcherExpanded: Boolean = false,
    contentRefreshing: Boolean = false,
    contentRefreshEnabled: Boolean = true,
    selectedTabKey: String = "home",
    showBottomBar: Boolean = true,
    bottomContentPadding: Dp = if (showBottomBar) HomeBottomTabListReserve else SlockSpacing.Lg,
    outerPadding: Dp = SlockSpacing.Md,
    onContentRefresh: () -> Unit = {},
    onServerBadgeClick: () -> Unit = {},
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
    val labels = SlockHomeShellLabels.defaults()
    var channelsExpanded by rememberSaveable { mutableStateOf(true) }
    var directMessagesExpanded by rememberSaveable { mutableStateOf(true) }
    var channelsReversed by rememberSaveable { mutableStateOf(false) }
    var directMessagesReversed by rememberSaveable { mutableStateOf(false) }
    val displayedChannels = if (channelsReversed) channels.asReversed() else channels
    val displayedDirectMessages =
        if (directMessagesReversed) directMessages.asReversed() else directMessages
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.AppBgLightBlue)
            .padding(outerPadding)
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HomeWorkspaceHeader(
                    serverName = currentServerName,
                    serverUnreadCount = serverUnreadCount,
                    onServerBadgeClick = onServerBadgeClick
                )
                HomeRefreshableContent(
                    refreshing = contentRefreshing,
                    refreshEnabled = contentRefreshEnabled,
                    onRefresh = onContentRefresh,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(y = it),
                        contentPadding =
                            PaddingValues(
                                start = SlockSpacing.Md,
                                top = SlockSpacing.Md,
                                end = SlockSpacing.Md,
                                bottom = bottomContentPadding
                            ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item(key = "action-search") {
                            SidebarActionRow(
                                icon = LucideIcons.Search,
                                label = labels.search,
                                onClick = onSearchClick
                            )
                        }
                        item(key = "action-inbox") {
                            SidebarActionRow(
                                icon = LucideIcons.Inbox,
                                label = labels.inbox,
                                badge = inboxUnreadCount.takeIf { it > 0 }?.toString(),
                                onClick = onInboxClick
                            )
                        }
                        item(key = "action-saved") {
                            SidebarActionRow(
                                icon = LucideIcons.Bookmark,
                                label = labels.saved,
                                trailingText = savedCount.takeIf { it > 0 }?.toString(),
                                onClick = onSavedClick
                            )
                        }
                        item(key = "action-spacer") {
                            Spacer(modifier = Modifier.height(SlockSpacing.Xs))
                        }
                        item(key = "channels-header") {
                            SidebarSectionHeader(
                                label = labels.channels,
                                count = channels.size,
                                expanded = channelsExpanded,
                                onExpandedChange = { channelsExpanded = it },
                                onSortClick = { channelsReversed = !channelsReversed },
                                action = {
                                    HeaderIconAction(
                                        icon = LucideIcons.Plus,
                                        contentDescription = labels.addChannel,
                                        onClick = onCreateChannelClick
                                    )
                                }
                            )
                        }
                        if (channelsExpanded) {
                            items(
                                items = displayedChannels,
                                key = { channel -> "channel-${channel.id}" }
                            ) { channel ->
                                ChannelRow(
                                    name = channel.name,
                                    joined = channel.joined,
                                    hasDraft = channel.hasDraft,
                                    badge = channel.unreadCount.takeIf { it > 0 }?.toString(),
                                    interactionState = if (channel.id == selectedChannelId) {
                                        ChannelRowInteractionState.Selected
                                    } else {
                                        ChannelRowInteractionState.Default
                                    },
                                    onClick = { onChannelClick(channel.id) }
                                )
                            }
                        }
                        item(key = "direct-messages-header") {
                            SidebarSectionHeader(
                                label = labels.directMessages,
                                count = directMessages.size,
                                expanded = directMessagesExpanded,
                                onExpandedChange = { directMessagesExpanded = it },
                                onSortClick = { directMessagesReversed = !directMessagesReversed },
                                action = null
                            )
                        }
                        if (directMessagesExpanded) {
                            items(
                                items = displayedDirectMessages,
                                key = { directMessage -> "direct-message-${directMessage.id}" }
                            ) { directMessage ->
                                ChannelRow(
                                    name = directMessage.name,
                                    avatarLabel = directMessage.avatarLabel,
                                    avatarUrl = directMessage.avatarUrl,
                                    avatar = directMessage.avatar,
                                    presence = directMessage.presence,
                                    hasDraft = directMessage.hasDraft,
                                    badge = directMessage.unreadCount.takeIf { it > 0 }?.toString(),
                                    onClick = { onChannelClick(directMessage.id) }
                                )
                            }
                        }
                    }
                }
            }
            HomeNotificationCenter(
                notifications = notifications,
                onView = onNotificationView,
                onDismiss = onNotificationDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = SlockSpacing.Lg, end = SlockSpacing.Lg)
                        .zIndex(3f)
            )
            if (serverSwitcherExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onServerBadgeClick
                        )
                )
                ServerSwitcherPanel(
                    servers = servers,
                    currentServerId = currentServerId,
                    labels = labels,
                    onServerSelected = onServerSelected,
                    onCreateServerClick = onCreateServerClick,
                    modifier = Modifier
                        .padding(start = SlockSpacing.Lg, top = SlockSpacing.Xs)
                        .zIndex(1f)
                )
            }
        }
        if (showBottomBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = SlockSpacing.Xs)
            ) {
                SlockBottomTabBar(
                    items = listOf(
                        SlockBottomTabItem("home", "HOME", LucideIcons.House),
                        SlockBottomTabItem("tasks", "TASKS", LucideIcons.SquareCheckBig),
                        SlockBottomTabItem("members", "MEMBERS", LucideIcons.Users),
                        SlockBottomTabItem("settings", "SETTINGS", LucideIcons.Settings)
                    ),
                    selectedKey = selectedTabKey,
                    onSelected = onTabSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = -SlockStroke.Default)
                )
            }
        }
    }
}

@Composable
private fun HomeRefreshableContent(
    refreshing: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(Dp) -> Unit
) {
    SlockPullRefreshContainer(
        refreshing = refreshing,
        enabled = refreshEnabled,
        onRefresh = onRefresh,
        modifier = modifier
    ) { offset ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            content(offset)
        }
    }
}

@Composable
private fun HomeWorkspaceHeader(
    serverName: String,
    serverUnreadCount: Int,
    onServerBadgeClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.BrandYellow)
            .drawBehind {
                val stroke = SlockStroke.Default.toPx()
                drawLine(
                    color = SlockColor.BorderBlack,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height - stroke / 2f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke
                )
            }
            .padding(horizontal = SlockSpacing.Lg, vertical = SlockSpacing.Md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.clickable(onClick = onServerBadgeClick)) {
            SlockLogoBadge(
                text = serverName,
                variant = SlockLogoBadgeVariant.Large,
                showDropdown = true
            )
            if (serverUnreadCount > 0) {
                ServerUnreadMarker(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun ServerUnreadMarker(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .offset(x = 3.dp, y = (-3).dp)
            .size(9.dp)
            .drawBehind {
                drawRect(SlockColor.AccentRed)
                drawRect(
                    color = SlockColor.BorderBlack,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = SlockStroke.Thin.toPx()
                    )
                )
            }
    )
}

@Composable
private fun HomeNotificationCenter(
    notifications: List<SlockHomeNotificationItem>,
    onView: (SlockHomeNotificationItem) -> Unit,
    onDismiss: (SlockHomeNotificationItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (notifications.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val activeKind = notifications.first().kind
    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
                    .slockHardShadow(SlockShadow.Small)
                    .size(36.dp)
                    .background(activeKind.notificationColor())
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = LucideIcons.TriangleAlert,
                contentDescription = "Notification center (${notifications.size} active)",
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(18.dp)
            )
        }
        ServerUnreadMarker(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(1f)
        )
        if (expanded) {
            HomeNotificationPopover(
                notifications = notifications,
                onView = {
                    expanded = false
                    onView(it)
                },
                onDismiss = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = 44.dp)
            )
        }
    }
}

@Composable
private fun HomeNotificationPopover(
    notifications: List<SlockHomeNotificationItem>,
    onView: (SlockHomeNotificationItem) -> Unit,
    onDismiss: (SlockHomeNotificationItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .padding(end = SlockShadow.Default.offsetX, bottom = SlockShadow.Default.offsetY)
                .slockHardShadow(SlockShadow.Default)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .drawBehind {
                        val stroke = SlockStroke.Default.toPx()
                        drawLine(
                            color = SlockColor.BorderBlack,
                            start = Offset(0f, size.height - stroke / 2f),
                            end = Offset(size.width, size.height - stroke / 2f),
                            strokeWidth = stroke
                        )
                    }
                    .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NOTIFICATIONS",
                style = SlockType.SectionLabel,
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
            Text(
                text = "${notifications.size} ${if (notifications.size == 1) "item" else "items"}",
                style = SlockType.Button,
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
        }
        notifications.forEachIndexed { index, notification ->
            if (index > 0) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(SlockStroke.Default)
                            .background(SlockColor.BorderBlack)
                )
            }
            HomeNotificationRow(
                notification = notification,
                onView = { onView(notification) },
                onDismiss = { onDismiss(notification) }
            )
        }
    }
}

@Composable
private fun HomeNotificationRow(
    notification: SlockHomeNotificationItem,
    onView: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(SlockSpacing.Md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .background(notification.kind.notificationColor())
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector =
                    when (notification.kind) {
                        SlockHomeNotificationKind.Warning -> LucideIcons.CircleArrowUp
                        SlockHomeNotificationKind.Info -> LucideIcons.BellRing
                        SlockHomeNotificationKind.Error -> LucideIcons.TriangleAlert
                    },
                contentDescription = null,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(16.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = notification.title,
                style = SlockType.Button,
                color = SlockColor.TextBlack,
                overflow = TextOverflow.Ellipsis,
                maxLines = 3
            )
            Text(
                text = notification.body,
                style = SlockType.Button,
                color = SlockColor.TextMutedGray,
                overflow = TextOverflow.Ellipsis,
                maxLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                HomeNotificationActionButton(
                    text = notification.actionLabel,
                    color = SlockColor.AccentPink,
                    onClick = onView
                )
                HomeNotificationActionButton(
                    text = "Dismiss",
                    color = SlockColor.SurfaceWhite,
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun HomeNotificationActionButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    Text(
        text = text,
        style = SlockType.Caption.copy(fontWeight = FontWeight.Medium),
        color = SlockColor.TextBlack,
        modifier =
            Modifier
                .background(color)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(onClick = onClick)
                .padding(horizontal = SlockSpacing.Sm, vertical = 6.dp),
        maxLines = 1
    )
}

private fun SlockHomeNotificationKind.notificationColor(): Color =
    when (this) {
        SlockHomeNotificationKind.Warning -> SlockColor.AccentOrange
        SlockHomeNotificationKind.Info -> SlockColor.BrandYellow
        SlockHomeNotificationKind.Error -> SlockColor.AccentRed
    }

@Composable
private fun ServerSwitcherPanel(
    servers: List<SlockServerSwitcherItem>,
    currentServerId: String,
    labels: SlockHomeShellLabels,
    onServerSelected: (String) -> Unit,
    onCreateServerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 276.dp, max = 328.dp)
            .padding(end = SlockShadow.Default.offsetX, bottom = SlockShadow.Default.offsetY)
            .slockHardShadow(SlockShadow.Default)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        if (servers.isEmpty()) {
            Text(
                text = labels.noServersAvailable,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(SlockSpacing.Md)
            )
        } else {
            servers.forEach { server ->
                ServerSwitcherRow(
                    server = server,
                    selected = server.id == currentServerId,
                    onClick = { onServerSelected(server.id) }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(SlockColor.SurfaceWhite)
                .clickable(onClick = onCreateServerClick)
                .padding(horizontal = SlockSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = "+",
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack
            )
            Text(
                text = labels.switchOrCreateServer,
                style = SlockType.Button,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ServerSwitcherRow(
    server: SlockServerSwitcherItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(SlockColor.SurfaceWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = if (selected) "✓" else "",
            style = SlockType.Button,
            color = SlockColor.TextBlack,
            modifier = Modifier.width(22.dp),
            textAlign = TextAlign.Center
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = SlockType.Button,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "/${server.slug}",
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (server.unreadCount > 0) {
            CountPill(text = server.unreadCount.toString(), color = SlockColor.AccentPink)
        }
    }
}

@Composable
private fun SidebarActionRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    trailingText: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SidebarActionRowHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = SidebarRowHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(SidebarActionIconBoxSize),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(SidebarActionIconSize)
            )
        }
        Text(
            text = label,
            style = SlockType.Button,
            color = SlockColor.TextBlack,
            modifier = Modifier
                .padding(start = SidebarActionLabelGap)
                .weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badge != null) {
            CountPill(text = badge, color = SlockColor.AccentPink)
        } else if (trailingText != null) {
            Text(
                text = trailingText,
                style = SlockType.Caption,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SectionBlock(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortClick: () -> Unit,
    action: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        SidebarSectionHeader(
            label = label,
            count = count,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            onSortClick = onSortClick,
            action = action
        )
        if (expanded) {
            content()
        }
    }
}

@Composable
private fun SidebarSectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortClick: () -> Unit,
    action: (@Composable BoxScope.() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clickable { onExpandedChange(!expanded) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (expanded) LucideIcons.ChevronDown else LucideIcons.ChevronRight,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(SectionChevronSize)
        )
        Text(
            text = label,
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(start = SlockSpacing.Xs)
        )
        Text(
            text = count.toString(),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            modifier = Modifier.padding(start = SlockSpacing.Xs)
        )
        Spacer(modifier = Modifier.weight(1f))
        HeaderIconAction(
            icon = LucideIcons.ArrowUpDown,
            contentDescription = "Sort $label",
            onClick = onSortClick
        )
        if (action != null) {
            Box(content = action)
        }
    }
}

@Composable
private fun HeaderIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(MiniActionSize)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(MiniActionIconSize)
        )
    }
}

@Composable
private fun ChannelRow(
    name: String,
    modifier: Modifier = Modifier,
    symbol: String = "#",
    avatarLabel: String? = null,
    avatarUrl: String? = null,
    avatar: AvatarReference? = AvatarReference.fromWireValue(avatarUrl),
    presence: SlockPresence? = null,
    badge: String? = null,
    hasDraft: Boolean = false,
    joined: Boolean = true,
    interactionState: ChannelRowInteractionState = ChannelRowInteractionState.Default,
    onClick: (() -> Unit)? = null
) {
    val rowBackground = when (interactionState) {
        ChannelRowInteractionState.Default -> if (joined) Color.Transparent else SlockColor.EmptyStateGray.copy(alpha = 0.36f)
        ChannelRowInteractionState.Selected -> SlockColor.BrandYellow
        ChannelRowInteractionState.Hovered -> SlockColor.EmptyStateGray
        ChannelRowInteractionState.Focused -> SlockColor.SurfaceWhite
    }
    val borderColor = when (interactionState) {
        ChannelRowInteractionState.Default,
        ChannelRowInteractionState.Hovered -> Color.Transparent
        ChannelRowInteractionState.Selected,
        ChannelRowInteractionState.Focused -> SlockColor.BorderBlack
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ChannelRowHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChannelRowHeight)
                .background(rowBackground)
                .border(SlockStroke.Default, borderColor)
                .padding(
                    start = ChannelRowHorizontalPadding,
                    end = SlockSpacing.Sm
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(ChannelSymbolWidth),
                contentAlignment = Alignment.Center
            ) {
                if (avatarLabel != null) {
                    ChannelAvatar(avatarUrl = avatarUrl, avatar = avatar, label = avatarLabel, presence = presence)
                } else {
                    Text(
                        text = symbol,
                        style = SlockType.Body.copy(fontWeight = FontWeight.Normal, fontSize = ChannelSymbolFontSize),
                        color = SlockColor.TextBlack.copy(alpha = if (joined) 0.62f else 0.32f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Text(
                text = name,
                style = SlockType.Button,
                color = SlockColor.TextBlack.copy(alpha = if (joined) 1f else 0.46f),
                modifier = Modifier
                    .padding(start = SidebarActionLabelGap)
                    .weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (badge != null) {
                Spacer(modifier = Modifier.width(SlockSpacing.Sm))
            } else if (hasDraft) {
                Spacer(modifier = Modifier.width(SlockSpacing.Xs))
            }
            if (hasDraft) {
                Icon(
                    imageVector = LucideIcons.Pencil,
                    contentDescription = "Unsent draft",
                    tint = SlockColor.TextMutedGray,
                    modifier = Modifier.size(14.dp)
                )
            }
            if (badge != null) {
                if (hasDraft) {
                    Spacer(modifier = Modifier.width(SlockSpacing.Xs))
                }
                CountPill(text = badge, color = SlockColor.AccentPink)
            }
        }
    }
}

@Composable
private fun ChannelAvatar(
    avatarUrl: String?,
    avatar: AvatarReference? = AvatarReference.fromWireValue(avatarUrl),
    label: String,
    presence: SlockPresence? = null
) {
    Box(modifier = Modifier.size(ChannelAvatarSize)) {
        SlockAvatar(
            avatarUrl = avatarUrl,
            avatar = avatar,
            label = label,
            size = ChannelAvatarSize,
            fontSize = ChannelAvatarFontSize
        )
        if (presence != null) {
            PresenceDot(
                presence = presence,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
private fun PresenceDot(
    presence: SlockPresence,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(presence.homeRowColor)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
    )
}

private val SlockPresence.homeRowColor: Color
    get() = when (this) {
        SlockPresence.Online -> SlockColor.AccentLime
        SlockPresence.Offline -> SlockColor.EmptyStateGray
        SlockPresence.Unknown -> SlockColor.BrandYellow
    }

@Composable
private fun PixelAvatar(spec: PixelAvatarSpec) {
    Canvas(
        modifier = Modifier
            .size(ChannelAvatarSize)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
    ) {
        val cellSize = size.width / PixelAvatarGridSize
        drawRect(spec.background)
        spec.grid.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, key ->
                val color = PixelAvatarPalette[key] ?: Color.Transparent
                if (color != Color.Transparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(columnIndex * cellSize, rowIndex * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun InitialsAvatar(label: String) {
    Box(
        modifier = Modifier
            .size(ChannelAvatarSize)
            .background(SlockColor.AccentLavender)
            .border(SlockStroke.Thin, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.take(2).uppercase(),
            style = SlockType.Caption.copy(fontWeight = FontWeight.Medium, fontSize = ChannelAvatarFontSize),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

private data class PixelAvatarSpec(
    val background: Color,
    val grid: List<String>
)

private const val PixelAvatarGridSize = 8f
private const val PixelAvatarProtocolPrefix = "pixel:"
private const val PixelAvatarRandomPrefix = "random:"
private const val PixelAvatarDefaultKey = "robot"

private val PixelAvatarPalette = mapOf(
    '_' to Color.Transparent,
    'K' to Color(0xFF141111),
    'W' to Color(0xFFFFFFFF),
    'P' to Color(0xFFFE7DA8),
    'O' to Color(0xFFF8A16F),
    'Y' to Color(0xFFFFD440),
    'L' to Color(0xFFA9D877),
    'C' to Color(0xFF27CCF3),
    'V' to Color(0xFFBBAFE6),
    'B' to Color(0xFF576574),
    'R' to Color(0xFFF97264),
    'G' to Color(0xFF9DCAAA)
)

private val PixelAvatarFixedSpecs = mapOf(
    "robot" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('C'),
        grid = listOf(
            "_KKKKKK_",
            "KCKWWKCK",
            "KWKWWKWK",
            "KKKKKKKK",
            "_KWKKWK_",
            "_KKKKKK_",
            "_KK__KK_",
            "_KK__KK_"
        )
    ),
    "cat" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('Y'),
        grid = listOf(
            "K______K",
            "KK____KK",
            "KYKYYKYK",
            "KYYYYYYK",
            "KYKYYKYK",
            "_KYYYYK_",
            "__KYYK__",
            "___KK___"
        )
    ),
    "ghost" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('V'),
        grid = listOf(
            "__KKKK__",
            "_KWWWWK_",
            "KWKWWKWK",
            "KWWWWWWK",
            "KWWWWWWK",
            "KWWKKWWK",
            "KWKWWKWK",
            "K_K__K_K"
        )
    ),
    "skull" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('O'),
        grid = listOf(
            "_KKKKKK_",
            "KWWWWWWK",
            "KWKWWKWK",
            "KWWWWWWK",
            "_KWWWWK_",
            "_KWKWKK_",
            "__KKKK__",
            "___KK___"
        )
    ),
    "alien" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('L'),
        grid = listOf(
            "_KKKKKK_",
            "KGGGGGGK",
            "KGKKKKGK",
            "KGGGGGGK",
            "_KGGGGK_",
            "__KGGK__",
            "__KGGK__",
            "_KK__KK_"
        )
    ),
    "heart" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('P'),
        grid = listOf(
            "________",
            "_KK__KK_",
            "KRRKKRRK",
            "KRRRRRRK",
            "KRRRRRRK",
            "_KRRRRK_",
            "__KRRK__",
            "___KK___"
        )
    ),
    "star" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('Y'),
        grid = listOf(
            "___KK___",
            "___KK___",
            "KKKKKKKK",
            "_KYYYYK_",
            "__KYYK__",
            "_KYKKYK_",
            "KYK__KYK",
            "KK____KK"
        )
    ),
    "flame" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('O'),
        grid = listOf(
            "___K____",
            "__KRK___",
            "__KRKK__",
            "_KORRRK_",
            "_KOORRK_",
            "KYOOORK_",
            "KYYOOK__",
            "_KKKK___"
        )
    ),
    "diamond" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('C'),
        grid = listOf(
            "___KK___",
            "__KCCK__",
            "_KCWCCK_",
            "KCWCCCCK",
            "KCCCCCCK",
            "_KCCCCK_",
            "__KCCK__",
            "___KK___"
        )
    ),
    "mushroom" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('P'),
        grid = listOf(
            "__KKKK__",
            "_KRWWRK_",
            "KRRWWRRK",
            "KRRRRRRK",
            "_KKKKKK_",
            "__KWWK__",
            "__KWWK__",
            "_KKKKKK_"
        )
    ),
    "eye" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('V'),
        grid = listOf(
            "________",
            "__KKKK__",
            "_KWWWWK_",
            "KWWKKWWK",
            "KWWKKWWK",
            "_KWWWWK_",
            "__KKKK__",
            "________"
        )
    ),
    "crown" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('Y'),
        grid = listOf(
            "________",
            "_K_KK_K_",
            "_KKKKKK_",
            "_KYYYYK_",
            "_KYYYYK_",
            "KKKKKKKK",
            "KYYYYYYK",
            "KKKKKKKK"
        )
    ),
    "cloud" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('C'),
        grid = listOf(
            "________",
            "__KK____",
            "_KWWKK__",
            "KWWWWWK_",
            "KWWWWWWK",
            "KWWWWWWK",
            "_KKKKKK_",
            "________"
        )
    ),
    "sun" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('R'),
        grid = listOf(
            "_K_KK_K_",
            "K_KYYK_K",
            "_KYYYYK_",
            "KYYYYYYK",
            "KYYYYYYK",
            "_KYYYYK_",
            "K_KYYK_K",
            "_K_KK_K_"
        )
    ),
    "bell" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('B'),
        grid = listOf(
            "___KK___",
            "__KYYK__",
            "_KYYYYK_",
            "_KYYYYK_",
            "_KYYYYK_",
            "KYYYYYYK",
            "KKKKKKKK",
            "___KK___"
        )
    ),
    "tree" to PixelAvatarSpec(
        background = PixelAvatarPalette.getValue('L'),
        grid = listOf(
            "___KK___",
            "__KGGK__",
            "_KGGGGK_",
            "KGGGGGGK",
            "KGGGGGGK",
            "_KKGGKK_",
            "__KOOK__",
            "___KK___"
        )
    )
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

private fun String.toPixelAvatarSpec(): PixelAvatarSpec? {
    val pixelKey = removePrefixOrNull(PixelAvatarProtocolPrefix)
    if (pixelKey != null) {
        return PixelAvatarFixedSpecs[pixelKey] ?: PixelAvatarFixedSpecs.getValue(PixelAvatarDefaultKey)
    }
    val randomSeed = removePrefixOrNull(PixelAvatarRandomPrefix)
    if (randomSeed != null) {
        return randomPixelAvatar(randomSeed)
    }
    return null
}

private fun String.removePrefixOrNull(prefix: String): String? =
    takeIf { it.startsWith(prefix) }?.substring(prefix.length)

private fun randomPixelAvatar(seed: String): PixelAvatarSpec {
    val random = PixelAvatarRandom(pixelHash(seed))
    val (background, fill) = PixelAvatarRandomOptions[(random.next() * PixelAvatarRandomOptions.size).toInt()]
    val grid = List(8) {
        val left = List(4) { if (random.next() < 0.45) fill else '_' }
        (left + left.asReversed()).joinToString(separator = "")
    }
    return PixelAvatarSpec(background = background, grid = grid)
}

private fun pixelHash(value: String): Int {
    var hash = 0
    value.forEach { char ->
        hash = (hash shl 5) - hash + char.code
    }
    return hash
}

private class PixelAvatarRandom(seed: Int) {
    private var value = seed

    fun next(): Double {
        value += 1831565813
        var mixed = (value xor (value ushr 15)) * (1 or value)
        mixed = (mixed + ((mixed xor (mixed ushr 7)) * (61 or mixed))) xor mixed
        val unsigned = (mixed xor (mixed ushr 14)).toLong() and 0xffffffffL
        return unsigned.toDouble() / 4294967296.0
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ChannelRowStatesPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(SlockSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            ChannelRow(
                name = "all",
                onClick = {}
            )
            ChannelRow(
                name = "android-dev",
                interactionState = ChannelRowInteractionState.Selected,
                onClick = {}
            )
            ChannelRow(
                name = "onboarding-artin",
                badge = "8",
                onClick = {}
            )
            ChannelRow(
                name = "onboarding-artin",
                badge = "8",
                interactionState = ChannelRowInteractionState.Selected,
                onClick = {}
            )
            ChannelRow(
                name = "selected-channel-with-long-mixed-中文-and-english-name",
                interactionState = ChannelRowInteractionState.Selected,
                onClick = {}
            )
            ChannelRow(
                name = "product-hover",
                interactionState = ChannelRowInteractionState.Hovered,
                onClick = {}
            )
            ChannelRow(
                name = "very-long-channel-name-with-mixed-中文-and-english",
                interactionState = ChannelRowInteractionState.Focused,
                onClick = {}
            )
            ChannelRow(
                name = "artin",
                avatarLabel = "AR",
                avatarUrl = "pixel:diamond",
                badge = "2",
                onClick = {}
            )
            ChannelRow(
                name = "random-seed",
                avatarLabel = "RS",
                avatarUrl = "random:random-seed",
                onClick = {}
            )
        }
    }
}

@Composable
private fun CountPill(
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .background(color)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .height(CountPillHeight)
            .widthIn(min = CountPillMinWidth)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = SlockType.Caption.copy(lineHeight = 14.sp),
            color = SlockColor.SurfaceWhite,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

private val defaultServerSwitcherItems = listOf(
    SlockServerSwitcherItem(
        id = "slock-android",
        name = "Slock Android",
        slug = "slock-android"
    ),
    SlockServerSwitcherItem(
        id = "product-lab",
        name = "Product Lab",
        slug = "product-lab"
    ),
    SlockServerSwitcherItem(
        id = "long-sample-server",
        name = "Very Long Server Name For Layout Coverage",
        slug = "very-long-server-slug-for-preview"
    )
)

private val defaultChannelItems = listOf(
    SlockHomeConversationItem(id = "all", name = "all"),
    SlockHomeConversationItem(id = "onboarding-artin", name = "onboarding-artin", unreadCount = 8),
    SlockHomeConversationItem(id = "product", name = "product"),
    SlockHomeConversationItem(id = "android-dev", name = "android-dev", unreadCount = 3),
    SlockHomeConversationItem(id = "design", name = "design"),
    SlockHomeConversationItem(id = "qa", name = "qa")
)

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockStructuralStatePreview() {
    SlockTheme {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            SlockPageHeader(
                title = "Tasks",
                subtitle = "6 open across #android-dev"
            )
            SlockMessageItem(
                authorName = "Android-Developer",
                role = "agent",
                time = "13:26",
                body = "Long mixed text wraps here: 组件 preview should include English and 中文内容 without overflowing.",
                attachmentLabel = "1 file",
                state = SlockMessageState.Pending
            )
            SlockComposer(
                value = "可以开始 mock data",
                onValueChange = {},
                onSend = {},
                imageIcon = Icons.Outlined.Home,
                attachmentIcon = Icons.Outlined.Person
            )
        }
    }
}
