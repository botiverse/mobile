package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import ai.slock.android.R
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

private val SidebarActionIconSize = 20.dp
private val SidebarActionRowHeight = 46.dp
private val SidebarRowHorizontalPadding = 12.dp
private val SidebarActionLabelGap = 16.dp
private val ChannelSymbolWidth = 24.dp
private val ChannelSymbolFontSize = 18.sp
private val ChannelAvatarSize = 24.dp
private val ChannelAvatarFontSize = 11.sp
private val ChannelRowHeight = 42.dp
private val ChannelRowHorizontalPadding = SidebarRowHorizontalPadding
private val SectionChevronSize = 14.dp
private val MiniActionSize = 36.dp
private val MiniActionIconSize = 18.dp
private val CountPillMinHeight = 22.dp

data class SlockServerSwitcherItem(
    val id: String,
    val name: String,
    val slug: String
)

data class SlockHomeConversationItem(
    val id: String,
    val name: String,
    val unreadCount: Int = 0,
    val avatarLabel: String = name.take(2).uppercase()
)

data class SlockHomeShellLabels(
    val search: String,
    val inbox: String,
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

@Composable
fun SlockStructuralHomeShell(
    modifier: Modifier = Modifier,
    currentServerName: String = "Slock Android",
    currentServerId: String = "slock-android",
    servers: List<SlockServerSwitcherItem> = emptyList(),
    channels: List<SlockHomeConversationItem> = defaultChannelItems,
    directMessages: List<SlockHomeConversationItem> = emptyList(),
    selectedChannelId: String? = "android-dev",
    inboxUnreadCount: Int = 5,
    savedUnreadCount: Int = 0,
    serverSwitcherExpanded: Boolean = false,
    contentRefreshing: Boolean = false,
    contentRefreshEnabled: Boolean = true,
    selectedTabKey: String = "home",
    showBottomBar: Boolean = true,
    outerPadding: Dp = SlockSpacing.Md,
    onContentRefresh: () -> Unit = {},
    onServerBadgeClick: () -> Unit = {},
    onServerSelected: (String) -> Unit = {},
    onCreateServerClick: () -> Unit = {},
    onTabSelected: (String) -> Unit = {},
    onInboxClick: () -> Unit = {},
    onSavedClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onChannelClick: (String) -> Unit = {}
) {
    val labels = SlockHomeShellLabels.defaults()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.AppBgLightBlue)
            .padding(outerPadding)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
        ) {
            HomeWorkspaceHeader(
                serverName = currentServerName,
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(y = it)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = SlockSpacing.Lg, vertical = SlockSpacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                ) {
                    SidebarActionRow(
                        icon = Icons.Outlined.Search,
                        label = labels.search,
                        onClick = onSearchClick
                    )
                    SidebarActionRow(
                        icon = Icons.Outlined.Person,
                        label = labels.inbox,
                        badge = inboxUnreadCount.takeIf { it > 0 }?.toString(),
                        onClick = onInboxClick
                    )
                    SidebarActionRow(
                        icon = Icons.Outlined.Home,
                        label = labels.saved,
                        badge = savedUnreadCount.takeIf { it > 0 }?.toString(),
                        onClick = onSavedClick
                    )
                    Spacer(modifier = Modifier.height(SlockSpacing.Xs))
                    SectionBlock(
                        label = labels.channels,
                        count = channels.size,
                        action = {
                            MiniSquareIcon(
                                icon = Icons.Outlined.Add,
                                contentDescription = labels.addChannel,
                                onClick = onCreateServerClick
                            )
                        }
                    ) {
                        channels.forEach { channel ->
                            ChannelRow(
                                name = channel.name,
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
                    SectionBlock(
                        label = labels.directMessages,
                        count = directMessages.size
                    ) {
                        directMessages.forEach { directMessage ->
                            ChannelRow(
                                name = directMessage.name,
                                avatarLabel = directMessage.avatarLabel,
                                badge = directMessage.unreadCount.takeIf { it > 0 }?.toString(),
                                onClick = { onChannelClick(directMessage.id) }
                            )
                        }
                    }
                }
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
        }
        if (showBottomBar) {
            SlockBottomTabBar(
                items = listOf(
                    SlockBottomTabItem("home", "HOME", Icons.Outlined.Home),
                    SlockBottomTabItem("tasks", "TASKS", SlockIcons.ListTodo),
                    SlockBottomTabItem("members", "MEMBERS", Icons.Outlined.Person),
                    SlockBottomTabItem("settings", "SETTINGS", Icons.Outlined.Settings)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeRefreshableContent(
    refreshing: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(Dp) -> Unit
) {
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (refreshEnabled && !refreshing) {
                onRefresh()
            }
        },
        state = refreshState,
        modifier = modifier,
        indicator = {
            HomeSlockRefreshIndicator(
                refreshing = refreshing,
                progress = refreshState.distanceFraction,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    ) {
        val offset = if (refreshing) {
            SlockSpacing.Xl
        } else {
            SlockSpacing.Xl * refreshState.distanceFraction.coerceIn(0f, 1f)
        }
        content(offset)
    }
}

@Composable
private fun HomeSlockRefreshIndicator(
    refreshing: Boolean,
    progress: Float,
    modifier: Modifier = Modifier
) {
    if (!refreshing && progress <= 0.01f) return
    val transition = rememberInfiniteTransition(label = "home-refresh-logo")
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "home-refresh-logo-pulse"
    )
    val bounce = if (refreshing) pulse.value else progress.coerceIn(0f, 1f)
    val scale = if (refreshing) {
        1f + (0.06f * pulse.value)
    } else {
        0.92f + (0.08f * progress.coerceIn(0f, 1f))
    }
    Box(
        modifier = modifier
            .offset(y = SlockSpacing.Xs * bounce)
            .padding(top = SlockSpacing.Sm),
        contentAlignment = Alignment.Center
    ) {
        SlockLogoBadge(
            text = "SLOCK",
            variant = SlockLogoBadgeVariant.Compact,
            modifier = Modifier
                .offset(y = -(SlockSpacing.Xs * bounce))
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        )
    }
}

@Composable
private fun HomeWorkspaceHeader(
    serverName: String,
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
        }
    }
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
    }
}

@Composable
private fun SidebarActionRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
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
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(SidebarActionIconSize)
        )
        Text(
            text = label,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier
                .padding(start = SidebarActionLabelGap)
                .weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badge != null) {
            CountPill(text = badge, color = SlockColor.AccentPink)
        }
    }
}

@Composable
private fun SectionBlock(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    action: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        SidebarSectionHeader(
            label = label,
            count = count,
            action = action
        )
        content()
    }
}

@Composable
private fun SidebarSectionHeader(
    label: String,
    count: Int,
    action: (@Composable BoxScope.() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = SlockIcons.ChevronDown,
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
        if (action != null) {
            Box(content = action)
        }
    }
}

@Composable
private fun ChannelRow(
    name: String,
    modifier: Modifier = Modifier,
    symbol: String = "#",
    avatarLabel: String? = null,
    badge: String? = null,
    interactionState: ChannelRowInteractionState = ChannelRowInteractionState.Default,
    onClick: (() -> Unit)? = null
) {
    val rowBackground = when (interactionState) {
        ChannelRowInteractionState.Default -> Color.Transparent
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
                    ChannelAvatar(label = avatarLabel)
                } else {
                    Text(
                        text = symbol,
                        style = SlockType.Body.copy(fontWeight = FontWeight.Normal, fontSize = ChannelSymbolFontSize),
                        color = SlockColor.TextBlack.copy(alpha = 0.62f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Text(
                text = name,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                modifier = Modifier
                    .padding(start = SidebarActionLabelGap)
                    .weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (badge != null) {
                Spacer(modifier = Modifier.width(SlockSpacing.Sm))
                CountPill(text = badge, color = SlockColor.AccentPink)
            }
        }
    }
}

@Composable
private fun ChannelAvatar(label: String) {
    Box(
        modifier = Modifier
            .size(ChannelAvatarSize)
            .background(SlockColor.AccentLavender)
            .border(SlockStroke.Thin, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.take(2).uppercase(),
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, fontSize = ChannelAvatarFontSize),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
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
                badge = "2",
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
    Text(
        text = text,
        style = SlockType.Caption,
        color = SlockColor.SurfaceWhite,
        modifier = Modifier
            .background(color)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .heightIn(min = CountPillMinHeight)
            .padding(horizontal = SlockSpacing.Sm, vertical = 1.dp)
    )
}

@Composable
private fun MiniSquareIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .size(MiniActionSize)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
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
                taskLabel = "task #5",
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
