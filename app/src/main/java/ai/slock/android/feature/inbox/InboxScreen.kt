package ai.slock.android.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.R
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockIconSquareButton
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

@Composable
fun InboxScreen(
    state: InboxUiState,
    onBack: () -> Unit,
    onOpenThread: (InboxItem) -> Unit,
    modifier: Modifier = Modifier,
    onFilterSelected: (InboxFilter) -> Unit = {},
    onMarkAllRead: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        when (val loadState = state.loadState) {
            ScreenLoadState.Loading -> InboxStateContent(
                title = stringResource(R.string.inbox_loading_title),
                detail = stringResource(R.string.inbox_loading_detail),
                onBack = onBack
            )
            is ScreenLoadState.Content -> InboxLoadedContent(
                state = state,
                content = loadState.data,
                onBack = onBack,
                onOpenThread = onOpenThread,
                onFilterSelected = onFilterSelected,
                onMarkAllRead = onMarkAllRead,
                onLoadMore = onLoadMore
            )
            is ScreenLoadState.Empty -> InboxStateContent(
                title = stringResource(R.string.inbox_empty_title),
                detail = loadState.reason.name,
                onBack = onBack
            )
            is ScreenLoadState.Error -> InboxErrorContent(
                error = loadState.error,
                onBack = onBack,
                onRetry = onRetry
            )
            is ScreenLoadState.NoPermission -> InboxStateContent(
                title = stringResource(R.string.inbox_no_permission_title),
                detail = loadState.blocker.requiredCapability ?: loadState.blocker.code,
                onBack = onBack
            )
        }
    }
}

@Composable
private fun InboxLoadedContent(
    state: InboxUiState,
    content: InboxContent,
    onBack: () -> Unit,
    onOpenThread: (InboxItem) -> Unit,
    onFilterSelected: (InboxFilter) -> Unit,
    onMarkAllRead: () -> Unit,
    onLoadMore: () -> Unit
) {
    val visibleItems = when (state.selectedFilter) {
        InboxFilter.All -> content.items
        InboxFilter.Unread -> content.items.filter { it.unreadCount > 0 }
    }

    InboxHeader(
        content = content,
        onBack = onBack
    )
    InboxFilterRow(
        selectedFilter = state.selectedFilter,
        onFilterSelected = onFilterSelected,
        onMarkAllRead = onMarkAllRead
    )
    if (visibleItems.isEmpty()) {
        InboxEmptyBody(
            title = if (state.selectedFilter == InboxFilter.Unread) {
                stringResource(R.string.inbox_no_unread_title)
            } else {
                stringResource(R.string.inbox_empty_title)
            },
            detail = if (state.selectedFilter == InboxFilter.Unread) {
                stringResource(R.string.inbox_no_unread_detail)
            } else {
                stringResource(R.string.inbox_empty_detail)
            }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            visibleItems.forEach { item ->
                InboxFlatItem(
                    item = item,
                    focused = item.key == state.focusedItemKey,
                    onClick = { onOpenThread(item) }
                )
            }
            if (content.hasMore) {
                SlockButton(
                    text = if (state.loadingMore) {
                        stringResource(R.string.inbox_loading_more)
                    } else {
                        stringResource(R.string.inbox_more_items_available)
                    },
                    onClick = onLoadMore,
                    enabled = !state.loadingMore,
                    loading = state.loadingMore,
                    variant = SlockButtonVariant.Outline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = SlockSpacing.Md)
                )
            }
        }
    }
}

@Composable
private fun InboxHeader(
    content: InboxContent,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(width = SlockStroke.Default, color = Color.Transparent)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockIconSquareButton(
            icon = SlockIcons.ArrowLeft,
            contentDescription = stringResource(R.string.inbox_back),
            onClick = onBack,
            modifier = Modifier.size(38.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.inbox_title),
                style = SlockType.PageTitle,
                color = SlockColor.TextBlack,
                maxLines = 1
            )
            Text(
                text = stringResource(
                    R.string.inbox_active_unread_stats,
                    content.totalCount,
                    content.totalUnreadCount
                ),
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SlockStroke.Default)
            .background(SlockColor.BorderBlack)
    )
}

@Composable
private fun InboxFilterRow(
    selectedFilter: InboxFilter,
    onFilterSelected: (InboxFilter) -> Unit,
    onMarkAllRead: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        InboxFilterButton(
            label = stringResource(R.string.inbox_filter_all),
            selected = selectedFilter == InboxFilter.All,
            onClick = { onFilterSelected(InboxFilter.All) }
        )
        InboxFilterButton(
            label = stringResource(R.string.inbox_filter_unread),
            selected = selectedFilter == InboxFilter.Unread,
            onClick = { onFilterSelected(InboxFilter.Unread) }
        )
        Spacer(modifier = Modifier.weight(1f))
        InboxMarkAllReadButton(onClick = onMarkAllRead)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SlockStroke.Default)
            .background(SlockColor.BorderBlack)
    )
}

@Composable
private fun InboxFilterButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shadow = SlockShadow.Small
    Row(
        modifier = Modifier
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .slockHardShadow(shadow)
            .background(
                if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite,
                RoundedCornerShape(SlockRadius.Xs)
            )
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Tab, onClick = onClick)
            .defaultMinSize(minHeight = 36.dp)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = label.uppercase(),
            style = SlockType.Button,
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun InboxMarkAllReadButton(onClick: () -> Unit) {
    val shadow = SlockShadow.Small
    Box(
        modifier = Modifier
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .slockHardShadow(shadow)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 36.dp)
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.inbox_mark_all_as_read).uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
private fun InboxFlatItem(
    item: InboxItem,
    focused: Boolean,
    onClick: () -> Unit
) {
    val unread = item.unreadCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (focused) SlockColor.SurfaceCream else SlockColor.SurfaceWhite,
                RoundedCornerShape(SlockRadius.Xs)
            )
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        InboxKindIcon(item = item, unread = unread)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                Text(
                    text = item.metaText,
                    style = SlockType.Caption.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = item.titleText,
                style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.previewText(),
                style = SlockType.Body.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            InboxItemFooter(item = item, unread = unread)
        }
        Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.TopEnd) {
            InboxItemCheckButton(onClick = onClick)
        }
    }
}

@Composable
private fun InboxItemFooter(item: InboxItem, unread: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        if (unread) {
            InboxBadge(text = stringResource(R.string.inbox_badge_new), color = SlockColor.AccentPink)
        }
        item.unreadCount.takeIf { it > 0 }?.let {
            InboxBadge(text = stringResource(R.string.inbox_unread_count, it), color = SlockColor.BrandYellow)
        }
        item.replyCount.takeIf { it > 0 }?.let {
            InboxBadge(text = stringResource(R.string.inbox_reply_count, it), color = SlockColor.SurfaceWhite)
        }
        Text(
            text = item.activityText(),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun InboxItemCheckButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shadow = SlockShadow.Small
    Box(
        modifier = modifier
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .slockHardShadow(shadow)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Button, onClick = onClick)
            .size(34.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = SlockIcons.Check,
            contentDescription = stringResource(R.string.inbox_open_item),
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun InboxKindIcon(
    item: InboxItem,
    unread: Boolean
) {
    Box(
        modifier = Modifier
            .padding(top = 1.dp)
            .size(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (item.kind == InboxItemKind.Thread) SlockIcons.MessageSquare else SlockIcons.MessageSquare,
            contentDescription = null,
            tint = if (unread) SlockColor.AccentPink else SlockColor.TextMutedGray,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun InboxBadge(
    text: String,
    color: Color
) {
    Text(
        text = text.uppercase(),
        style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
        color = SlockColor.TextBlack,
        modifier = Modifier
            .background(color)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Xs, vertical = 1.dp),
        maxLines = 1
    )
}

@Composable
private fun InboxStateContent(
    title: String,
    detail: String,
    onBack: () -> Unit
) {
    InboxHeader(
        content = InboxContent(
            server = ServerSummary(ServerId("state"), "Slock Android"),
            items = emptyList(),
            totalCount = 0,
            totalUnreadCount = 0
        ),
        onBack = onBack
    )
    InboxEmptyBody(title = title, detail = detail)
}

@Composable
private fun InboxErrorContent(
    error: ScreenError,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    InboxHeader(
        content = InboxContent(
            server = ServerSummary(ServerId("error"), "Slock Android"),
            items = emptyList(),
            totalCount = 0,
            totalUnreadCount = 0
        ),
        onBack = onBack
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SlockSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockErrorState(
            title = stringResource(R.string.inbox_error_title),
            detail = stringResource(R.string.inbox_error_detail, error.code)
        )
        InboxMarkAllReadButton(onClick = onRetry)
    }
}

@Composable
private fun InboxEmptyBody(
    title: String,
    detail: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SlockSpacing.Lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SlockEmptyState(title = title, detail = detail)
    }
}

private val InboxItem.titleText: String
    get() = when (kind) {
        InboxItemKind.Thread -> parentChannelName ?: channelName
        InboxItemKind.DirectMessage -> channelName
        InboxItemKind.Channel -> "#$channelName"
    }

private val InboxItem.metaText: String
    get() = listOfNotNull(
        "#${parentChannelName ?: channelName}",
        latestActivityAt ?: lastMessageAt
    ).joinToString(" · ")

@Composable
private fun InboxItem.previewText(): String =
    latestActivityPreview
        ?: lastMessagePreview
        ?: parentMessagePreview
        ?: stringResource(R.string.inbox_no_preview)

@Composable
private fun InboxItem.activityText(): String =
    listOfNotNull(
        latestActivitySenderName ?: lastMessageSenderName,
        taskNumber?.let { "task #$it" },
        taskStatus?.name
    ).joinToString(" · ").ifBlank { stringResource(R.string.inbox_read_item) }

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun InboxScreenPreview() {
    SlockTheme {
        InboxScreen(
            state = SlockStateSamples.inboxContent,
            onBack = {},
            onOpenThread = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun InboxScreenStatesPreview() {
    val baseContent = (SlockStateSamples.inboxContent.loadState as ScreenLoadState.Content).data
    val readItem = baseContent.items.first().copy(
        key = "channel:read-item",
        unreadCount = 0,
        channelName = "resolved-updates-with-a-very-long-name-that-should-ellipsis",
        lastMessagePreview = "This read inbox item proves text truncates cleanly without pushing the chevron."
    )
    SlockTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.AppBgLightBlue),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            InboxScreen(
                state = InboxUiState(
                    loadState = ScreenLoadState.Content(
                        baseContent.copy(items = listOf(readItem), totalCount = 24, totalUnreadCount = 1)
                    ),
                    selectedFilter = InboxFilter.Unread
                ),
                onBack = {},
                onOpenThread = {},
                modifier = Modifier.weight(1f)
            )
            InboxScreen(
                state = InboxUiState(
                    loadState = ScreenLoadState.NoPermission(
                        PermissionBlocker("inbox_denied", "View inbox")
                    )
                ),
                onBack = {},
                onOpenThread = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 520)
@Composable
private fun InboxScreenErrorPreview() {
    SlockTheme {
        InboxScreen(
            state = InboxUiState(loadState = ScreenLoadState.Error(ScreenError("inbox_failed"))),
            onBack = {},
            onOpenThread = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 520)
@Composable
private fun InboxScreenEmptyPreview() {
    SlockTheme {
        InboxScreen(
            state = InboxUiState(loadState = ScreenLoadState.Empty(EmptyReason.NoInboxItems)),
            onBack = {},
            onOpenThread = {}
        )
    }
}
