package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import kotlinx.serialization.Serializable

const val SLOCK_HOME_SMOKE_PAGE = "compose_home_smoke"

private val SidebarActionLabelGap = 10.dp
private val ChannelSymbolWidth = 22.dp
private val ChannelSymbolFontSize = 16.sp
private val ChannelAvatarSize = 20.dp
private val ChannelAvatarFontSize = 11.sp
private val ChannelRowHeight = 32.dp
private val ChannelRowHorizontalPadding = 8.dp
private val SectionChevronSize = 12.dp
private val MiniActionSize = 30.dp
private val MiniActionIconSize = 16.dp
private val CountPillMinWidth = 20.dp
private val CountPillHeight = 18.dp

@Serializable
internal data class SlockHomeSectionPayload(
    val key: String = "",
    val label: String = "",
    val expanded: Boolean = true,
    val items: List<SlockHomeRowPayload> = emptyList()
)

@Serializable
internal data class SlockHomeRowPayload(
    val key: String = "",
    val name: String = "",
    val symbol: String = "#",
    val avatarLabel: String? = null,
    val presence: String? = null,
    val badge: String? = null,
    val hasDraft: Boolean = false,
    val joined: Boolean = true,
    val selected: Boolean = false
)

@Page(SLOCK_HOME_SMOKE_PAGE, supportInLocal = true)
internal class ComposeHomeSmokePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockHomeSmokeContent() }
    }
}

@Composable
internal fun SlockSharedHomeContent(
    serverName: String,
    serverUnreadCount: Int,
    sections: List<SlockHomeSectionPayload>,
    onServerBadgeClick: () -> Unit,
    onSectionExpandedChange: (String, Boolean) -> Unit,
    onSectionSortClick: (String) -> Unit,
    onAddChannelClick: () -> Unit,
    onRowClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockSharedHomeWorkspaceHeader(
            serverName = serverName,
            serverUnreadCount = serverUnreadCount,
            onServerBadgeClick = onServerBadgeClick
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sections.forEach { section ->
                item(section.key) {
                    SlockSharedHomeSection(
                        label = section.label,
                        count = section.items.size,
                        expanded = section.expanded,
                        onExpandedChange = { onSectionExpandedChange(section.key, it) },
                        onSortClick = { onSectionSortClick(section.key) },
                        action = if (section.key == "channels") {
                            {
                                SlockHomeHeaderIconAction(
                                    iconName = "plus",
                                    contentDescription = "Add ${section.label}",
                                    onClick = onAddChannelClick
                                )
                            }
                        } else {
                            null
                        }
                    ) {
                        section.items.forEach { row ->
                            SlockSharedHomeChannelRow(
                                name = row.name,
                                symbol = row.symbol,
                                avatarLabel = row.avatarLabel,
                                presence = row.presence,
                                badge = row.badge,
                                hasDraft = row.hasDraft,
                                joined = row.joined,
                                selected = row.selected,
                                onClick = { onRowClick(row.key) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlockSharedHomeWorkspaceHeader(
    serverName: String,
    serverUnreadCount: Int,
    onServerBadgeClick: () -> Unit
) {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight.coerceAtLeast(0f)
    Column(modifier = Modifier.fillMaxWidth().background(SlockColor.BrandYellow)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SlockSpacing.Lg,
                    top = (statusBarHeight + 16f).dp,
                    end = SlockSpacing.Lg,
                    bottom = SlockSpacing.Md
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.clickable(onClick = onServerBadgeClick)) {
                SlockSharedLogoBadge(
                    text = serverName,
                    showDropdown = true
                )
                if (serverUnreadCount > 0) {
                    SlockSharedServerUnreadMarker(modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
private fun SlockSharedLogoBadge(
    text: String,
    showDropdown: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(end = 3.dp, bottom = 3.dp)
            .background(SlockColor.TextBlack)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = 16.dp, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = SlockColor.BrandYellow,
            style = SlockType.Button.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
            modifier = Modifier.widthIn(max = 260.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showDropdown) {
            Spacer(modifier = Modifier.width(SlockSpacing.Xs))
            SlockLucideIcon(name = "chevron-down", color = "#FFFFD440", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SlockSharedServerUnreadMarker(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .offset(x = 3.dp, y = (-3).dp)
            .size(9.dp)
            .background(SlockColor.AccentRed)
            .border(1.dp, SlockColor.BorderBlack)
    )
}

@Composable
private fun SlockSharedHomeSection(
    label: String,
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortClick: () -> Unit,
    action: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        SlockSharedSidebarSectionHeader(
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
private fun SlockSharedSidebarSectionHeader(
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
        SlockLucideIcon(
            name = if (expanded) "chevron-down" else "chevron-right",
            color = "#FF000000",
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
        SlockHomeHeaderIconAction(
            iconName = "arrow-up-down",
            contentDescription = "Sort $label",
            onClick = onSortClick
        )
        if (action != null) {
            Box(content = action)
        }
    }
}

@Composable
private fun SlockHomeHeaderIconAction(
    iconName: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(MiniActionSize)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(
            name = iconName,
            color = "#FF000000",
            modifier = Modifier.size(MiniActionIconSize)
        )
    }
}

@Composable
private fun SlockSharedHomeChannelRow(
    name: String,
    symbol: String,
    avatarLabel: String?,
    presence: String?,
    badge: String?,
    hasDraft: Boolean,
    joined: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val rowBackground = when {
        selected -> SlockColor.BrandYellow
        joined -> Color.Transparent
        else -> SlockColor.EmptyStateGray.copy(alpha = 0.36f)
    }
    val borderColor = if (selected) SlockColor.BorderBlack else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChannelRowHeight)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChannelRowHeight)
                .background(rowBackground)
                .border(SlockStroke.Default, borderColor)
                .padding(start = ChannelRowHorizontalPadding, end = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(ChannelSymbolWidth),
                contentAlignment = Alignment.Center
            ) {
                if (avatarLabel != null) {
                    SlockSharedHomeAvatar(label = avatarLabel, presence = presence)
                } else {
                    Text(
                        text = symbol,
                        style = SlockType.Body.copy(fontWeight = FontWeight.Normal, fontSize = ChannelSymbolFontSize),
                        color = if (joined) SlockColor.TextBlack.copy(alpha = 0.62f) else SlockColor.TextBlack.copy(alpha = 0.32f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Text(
                text = name,
                style = SlockType.Button,
                color = if (joined) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.46f),
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
                SlockLucideIcon(name = "pencil", color = "#FF737373", modifier = Modifier.size(14.dp))
            }
            if (badge != null) {
                if (hasDraft) {
                    Spacer(modifier = Modifier.width(SlockSpacing.Xs))
                }
                SlockHomeCountPill(text = badge, color = SlockColor.AccentPink)
            }
        }
    }
}

@Composable
private fun SlockSharedHomeAvatar(
    label: String,
    presence: String?
) {
    Box(modifier = Modifier.size(ChannelAvatarSize)) {
        Box(
            modifier = Modifier
                .size(ChannelAvatarSize)
                .background(SlockColor.AccentCyan)
                .border(1.dp, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.take(2).uppercase(),
                style = SlockType.Caption.copy(fontSize = ChannelAvatarFontSize, lineHeight = 12.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        presence?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(8.dp)
                    .background(it.homePresenceColor())
                    .border(1.dp, SlockColor.BorderBlack)
            )
        }
    }
}

@Composable
private fun SlockHomeCountPill(
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .height(CountPillHeight)
            .widthIn(min = CountPillMinWidth)
            .background(color)
            .border(1.dp, SlockColor.BorderBlack)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

private fun String.homePresenceColor(): Color =
    when (lowercase()) {
        "online" -> Color(0xFFB8F773)
        "offline" -> SlockColor.EmptyStateGray
        else -> SlockColor.BrandYellow
    }

@Composable
internal fun SlockHomeSmokeContent() {
    var channelsExpanded by remember { mutableStateOf(true) }
    var directMessagesExpanded by remember { mutableStateOf(true) }
    val sections = listOf(
        SlockHomeSectionPayload(
            key = "channels",
            label = "CHANNELS",
            expanded = channelsExpanded,
            items = listOf(
                SlockHomeRowPayload(key = "home", name = "首页专修", selected = true, badge = "9"),
                SlockHomeRowPayload(key = "markdown", name = "markdown专修", badge = "3"),
                SlockHomeRowPayload(key = "feedback", name = "反馈", joined = false)
            )
        ),
        SlockHomeSectionPayload(
            key = "direct_messages",
            label = "DIRECT MESSAGES",
            expanded = directMessagesExpanded,
            items = listOf(
                SlockHomeRowPayload(key = "agent", name = "Android-App-Integrator", avatarLabel = "AI", presence = "online", hasDraft = true),
                SlockHomeRowPayload(key = "kmp", name = "KMP-专家", avatarLabel = "KM", presence = "unknown")
            )
        )
    )
    SlockSharedHomeContent(
        serverName = "slock",
        serverUnreadCount = 1,
        sections = sections,
        onServerBadgeClick = {},
        onSectionExpandedChange = { key, expanded ->
            when (key) {
                "channels" -> channelsExpanded = expanded
                "direct_messages" -> directMessagesExpanded = expanded
            }
        },
        onSectionSortClick = {},
        onAddChannelClick = {},
        onRowClick = {}
    )
}
