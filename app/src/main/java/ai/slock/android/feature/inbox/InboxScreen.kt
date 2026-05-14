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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import ai.slock.android.feature.thread.toThreadTimestampLabel
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockIconSquareButton
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockLoadingState
import ai.slock.android.ui.components.SlockPullRefreshContainer
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
    onMarkItemRead: (InboxItem) -> Unit = {},
    onMarkAllRead: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val copy = InboxScreenCopy(
        title = stringResource(R.string.inbox_title),
        loadingTitle = stringResource(R.string.inbox_loading_title),
        loadingDetail = stringResource(R.string.inbox_loading_detail),
        emptyTitle = stringResource(R.string.inbox_empty_title),
        emptyDetail = stringResource(R.string.inbox_empty_detail),
        noPermissionTitle = stringResource(R.string.inbox_no_permission_title),
        errorTitle = stringResource(R.string.inbox_error_title)
    )
    InboxListScreen(
        state = state,
        copy = copy,
        showHeader = true,
        showFilters = true,
        onBack = onBack,
        onOpenThread = onOpenThread,
        modifier = modifier,
        onFilterSelected = onFilterSelected,
        onMarkItemRead = onMarkItemRead,
        onMarkAllRead = onMarkAllRead,
        onLoadMore = onLoadMore,
        refreshing = refreshing,
        onRefresh = onRefresh,
        onRetry = onRetry
    )
}

@Composable
fun SearchResultsListScreen(
    state: InboxUiState,
    onOpenThread: (InboxItem) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: () -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val copy = InboxScreenCopy(
        title = stringResource(R.string.home_search),
        loadingTitle = stringResource(R.string.search_loading_title),
        loadingDetail = stringResource(R.string.search_loading_detail),
        emptyTitle = stringResource(R.string.search_empty_title),
        emptyDetail = stringResource(R.string.search_empty_detail),
        noPermissionTitle = stringResource(R.string.inbox_no_permission_title),
        errorTitle = stringResource(R.string.search_error_title)
    )
    InboxListScreen(
        state = state,
        copy = copy,
        showHeader = false,
        showFilters = false,
        onBack = {},
        onOpenThread = onOpenThread,
        modifier = modifier,
        onLoadMore = onLoadMore,
        refreshing = refreshing,
        onRefresh = onRefresh,
        onRetry = onRetry
    )
}

@Composable
fun SavedScreen(
    state: InboxUiState,
    onBack: () -> Unit,
    onOpenThread: (InboxItem) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveSaved: (InboxItem) -> Unit = {},
    onLoadMore: () -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val copy = InboxScreenCopy(
        title = stringResource(R.string.saved_title),
        loadingTitle = stringResource(R.string.saved_loading_title),
        loadingDetail = stringResource(R.string.saved_loading_detail),
        emptyTitle = stringResource(R.string.saved_empty_title),
        emptyDetail = stringResource(R.string.saved_empty_detail),
        noPermissionTitle = stringResource(R.string.saved_no_permission_title),
        errorTitle = stringResource(R.string.saved_error_title)
    )
    InboxListScreen(
        state = state,
        copy = copy,
        showHeader = true,
        showFilters = false,
        onBack = onBack,
        onOpenThread = onOpenThread,
        modifier = modifier,
        onMarkItemRead = onRemoveSaved,
        onLoadMore = onLoadMore,
        refreshing = refreshing,
        onRefresh = onRefresh,
        onRetry = onRetry
    )
}

fun InboxItem.savedMessageIdOrNull(): MessageId? =
    latestActivityMessageId ?: lastMessageId ?: parentMessageId ?: firstUnreadMessageId

fun InboxUiState.removeSavedMessage(messageId: MessageId): InboxUiState =
    copy(
        loadState =
            (loadState as? ScreenLoadState.Content)?.let { contentState ->
                ScreenLoadState.Content(
                    contentState.data.copy(
                        items =
                            contentState.data.items.filterNot { item ->
                                item.savedMessageIdOrNull() == messageId
                            }
                    )
                )
            } ?: loadState
    )

@Composable
private fun InboxListScreen(
    state: InboxUiState,
    copy: InboxScreenCopy,
    showHeader: Boolean,
    showFilters: Boolean,
    onBack: () -> Unit,
    onOpenThread: (InboxItem) -> Unit,
    modifier: Modifier = Modifier,
    onFilterSelected: (InboxFilter) -> Unit = {},
    onMarkItemRead: (InboxItem) -> Unit = {},
    onMarkAllRead: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        when (val loadState = state.loadState) {
            ScreenLoadState.Loading -> InboxStateContent(
                copy = copy,
                state = state,
                showHeader = showHeader,
                onBack = onBack
            ) {
                InboxLoadingBody(overlay = state.previousContent != null)
            }
            is ScreenLoadState.Content -> InboxLoadedContent(
                state = state,
                copy = copy,
                content = loadState.data,
                showHeader = showHeader,
                showFilters = showFilters,
                onBack = onBack,
                onOpenThread = onOpenThread,
                onFilterSelected = onFilterSelected,
                onMarkItemRead = onMarkItemRead,
                onMarkAllRead = onMarkAllRead,
                onLoadMore = onLoadMore,
                refreshing = refreshing,
                onRefresh = onRefresh
            )
            is ScreenLoadState.Empty -> InboxStateContent(
                copy = copy,
                state = state,
                showHeader = showHeader,
                onBack = onBack
            ) {
                InboxEmptyBody(title = copy.emptyTitle, detail = copy.emptyDetail)
            }
            is ScreenLoadState.Error -> InboxErrorContent(
                copy = copy,
                state = state,
                error = loadState.error,
                showHeader = showHeader,
                onBack = onBack,
                onRetry = onRetry
            )
            is ScreenLoadState.NoPermission -> InboxStateContent(
                copy = copy,
                state = state,
                showHeader = showHeader,
                onBack = onBack
            ) {
                InboxEmptyBody(
                    title = copy.noPermissionTitle,
                    detail = loadState.blocker.requiredCapability ?: loadState.blocker.code
                )
            }
        }
    }
}

@Composable
fun FollowedThreadsScreen(
    state: InboxUiState,
    onBack: () -> Unit,
    onOpenThread: (InboxItem) -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val copy = InboxScreenCopy(
        title = stringResource(R.string.followed_threads_title),
        loadingTitle = stringResource(R.string.followed_threads_loading_title),
        loadingDetail = stringResource(R.string.followed_threads_loading_detail),
        emptyTitle = stringResource(R.string.followed_threads_empty_title),
        emptyDetail = stringResource(R.string.followed_threads_empty_detail),
        noPermissionTitle = stringResource(R.string.followed_threads_no_permission_title),
        errorTitle = stringResource(R.string.followed_threads_error_title)
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        when (val loadState = state.loadState) {
            ScreenLoadState.Loading -> InboxStateContent(
                copy = copy,
                state = state,
                onBack = onBack
            ) {
                InboxLoadingBody(overlay = state.previousContent != null)
            }
            is ScreenLoadState.Content -> FollowedThreadsLoadedContent(
                state = state,
                content = loadState.data,
                onBack = onBack,
                onOpenThread = onOpenThread,
                refreshing = refreshing,
                onRefresh = onRefresh
            )
            is ScreenLoadState.Empty -> InboxStateContent(
                copy = copy,
                state = state,
                onBack = onBack
            ) {
                InboxEmptyBody(title = copy.emptyTitle, detail = copy.emptyDetail)
            }
            is ScreenLoadState.Error -> InboxErrorContent(
                copy = copy,
                state = state,
                error = loadState.error,
                onBack = onBack,
                onRetry = onRetry
            )
            is ScreenLoadState.NoPermission -> InboxStateContent(
                copy = copy,
                state = state,
                onBack = onBack
            ) {
                InboxEmptyBody(
                    title = copy.noPermissionTitle,
                    detail = loadState.blocker.requiredCapability ?: loadState.blocker.code
                )
            }
        }
    }
}

@Composable
private fun FollowedThreadsLoadedContent(
    state: InboxUiState,
    content: InboxContent,
    onBack: () -> Unit,
    onOpenThread: (InboxItem) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    FollowedThreadsHeader(content = content, onBack = onBack)
    SlockPullRefreshContainer(
        refreshing = refreshing,
        onRefresh = onRefresh,
        threshold = rememberInboxPullRefreshThreshold(),
        modifier = Modifier.fillMaxSize()
    ) { offset ->
        if (content.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().offset(y = offset)) {
                InboxEmptyBody(
                    title = stringResource(R.string.followed_threads_empty_title),
                    detail = stringResource(R.string.followed_threads_empty_detail)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = offset)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                content.items.forEach { item ->
                    InboxFlatItem(
                        item = item,
                        focused = item.key == state.focusedItemKey,
                        onClick = { onOpenThread(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowedThreadsHeader(
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
                text = stringResource(R.string.followed_threads_title),
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
private fun InboxLoadedContent(
    state: InboxUiState,
    copy: InboxScreenCopy,
    content: InboxContent,
    showHeader: Boolean,
    showFilters: Boolean,
    onBack: () -> Unit,
    onOpenThread: (InboxItem) -> Unit,
    onFilterSelected: (InboxFilter) -> Unit,
    onMarkItemRead: (InboxItem) -> Unit,
    onMarkAllRead: () -> Unit,
    onLoadMore: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    val visibleItems = when (state.selectedFilter) {
        InboxFilter.All -> content.items
        InboxFilter.Unread -> content.items.filter { it.unreadCount > 0 }
        InboxFilter.Mentions -> content.items.filter { it.hasUnreadMention }
    }

    if (showHeader) {
        InboxHeader(
            title = copy.title,
            content = content,
            onBack = onBack
        )
    }
    if (showFilters) {
        InboxFilterRow(
            selectedFilter = state.selectedFilter,
            onFilterSelected = onFilterSelected,
            onMarkAllRead = onMarkAllRead
        )
    }
    SlockPullRefreshContainer(
        refreshing = refreshing,
        onRefresh = onRefresh,
        threshold = rememberInboxPullRefreshThreshold(),
        modifier = Modifier.fillMaxSize()
    ) { offset ->
        if (visibleItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().offset(y = offset)) {
                InboxEmptyBody(
                    title =
                            when {
                                showFilters && state.selectedFilter == InboxFilter.Unread ->
                                        stringResource(R.string.inbox_no_unread_title)
                                showFilters && state.selectedFilter == InboxFilter.Mentions ->
                                        stringResource(R.string.inbox_no_mentions_title)
                                else -> copy.emptyTitle
                            },
                    detail =
                            when {
                                showFilters && state.selectedFilter == InboxFilter.Unread ->
                                        stringResource(R.string.inbox_no_unread_detail)
                                showFilters && state.selectedFilter == InboxFilter.Mentions ->
                                        stringResource(R.string.inbox_no_mentions_detail)
                                else -> copy.emptyDetail
                            }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = offset)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                visibleItems.forEach { item ->
                    InboxFlatItem(
                        item = item,
                        focused = item.key == state.focusedItemKey,
                        onClick = { onOpenThread(item) },
                        onMarkRead = { onMarkItemRead(item) }
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
}

@Composable
private fun rememberInboxPullRefreshThreshold() =
    with(LocalConfiguration.current) {
        if (screenHeightDp <= InboxShortHeightPullRefreshMaxHeightDp) {
            InboxLandscapePullRefreshThreshold
        } else {
            InboxPortraitPullRefreshThreshold
        }
    }

private val InboxPortraitPullRefreshThreshold = 180.dp
private val InboxLandscapePullRefreshThreshold = 96.dp
private const val InboxShortHeightPullRefreshMaxHeightDp = 540

@Composable
private fun InboxHeader(
    title: String,
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
                text = title,
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
        InboxFilterButton(
            label = stringResource(R.string.inbox_filter_mentions),
            selected = selectedFilter == InboxFilter.Mentions,
            onClick = { onFilterSelected(InboxFilter.Mentions) }
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
    onClick: () -> Unit,
    onMarkRead: () -> Unit = onClick
) {
    val unread = item.unreadCount > 0
    val metadataTimestamp = item.metadataTimestamp()?.toThreadTimestampLabel()
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
                    text = item.channelLabelText,
                    style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
                if (!metadataTimestamp.isNullOrBlank()) {
                    Text(
                        text = metadataTimestamp,
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            InboxItemActivityBody(item = item)
            if (unread || item.replyCount > 0) {
                InboxItemFooter(
                    item = item,
                    unread = unread,
                    onOpenThread = onClick
                )
            }
        }
        Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.TopEnd) {
            InboxItemCheckButton(onClick = onMarkRead)
        }
    }
}

@Composable
private fun InboxItemActivityBody(item: InboxItem) {
    when (val activity = item.activityPreview()) {
        is InboxActivityPreview.NewMessage -> InboxNewMessageLine(activity = activity)
        is InboxActivityPreview.Reply -> InboxReplyActivityLine(activity = activity)
        is InboxActivityPreview.System -> InboxSystemMessageLine(activity = activity)
    }
}

@Composable
private fun InboxNewMessageLine(activity: InboxActivityPreview.NewMessage) {
    Text(
        text = activity.body,
        style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
        color = SlockColor.TextBlack,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun InboxReplyActivityLine(activity: InboxActivityPreview.Reply) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = activity.parentBody,
            style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = SlockIcons.MessageSquare,
                contentDescription = null,
                tint = SlockColor.TextMutedGray,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(12.dp)
            )
            Text(
                text = activity.sender?.let { sender -> "$sender: ${activity.replyBody}" }
                    ?: activity.replyBody,
                style = SlockType.Caption,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun InboxSystemMessageLine(activity: InboxActivityPreview.System) {
    Text(
        text = activity.body,
        style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
        color = SlockColor.TextBlack,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InboxItemFooter(
    item: InboxItem,
    unread: Boolean,
    onOpenThread: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        if (unread) {
            InboxBadge(text = stringResource(R.string.inbox_badge_new), color = SlockColor.AccentPink)
        }
        if (item.hasUnreadMention) {
            InboxBadge(text = stringResource(R.string.inbox_badge_you), color = SlockColor.BrandYellow)
        }
        item.unreadCount.takeIf { it > 0 }?.let {
            InboxBadge(text = stringResource(R.string.inbox_unread_count, it), color = SlockColor.BrandYellow)
        }
        item.replyCount.takeIf { it > 0 }?.let {
            InboxReplyChip(
                text = stringResource(R.string.inbox_reply_count, it),
                onClick = onOpenThread
            )
        }
    }
}

private sealed interface InboxActivityPreview {
    data class NewMessage(val body: String) : InboxActivityPreview
    data class Reply(val parentBody: String, val sender: String?, val replyBody: String) :
            InboxActivityPreview
    data class System(val body: String) : InboxActivityPreview
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
            contentDescription = stringResource(R.string.inbox_mark_item_as_read),
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
            imageVector =
                    if (item.kind == InboxItemKind.System) SlockIcons.BellRing
                    else SlockIcons.MessageSquare,
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
private fun InboxReplyChip(
    text: String,
    onClick: () -> Unit
) {
    val shadow = SlockShadow.Small
    Row(
        modifier = Modifier
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .slockHardShadow(shadow)
            .background(SlockColor.AppBgLightBlue, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 30.dp)
            .padding(horizontal = SlockSpacing.Sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = SlockIcons.MessageSquare,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = text,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val InboxItem.hasUnreadMention: Boolean
    get() = hasMention && unreadCount > 0

@Composable
private fun InboxStateContent(
    copy: InboxScreenCopy,
    state: InboxUiState,
    showHeader: Boolean = true,
    onBack: () -> Unit,
    body: @Composable () -> Unit
) {
    if (showHeader) {
        InboxHeader(
            title = copy.title,
            content = state.previousContent ?: copy.placeholderContent,
            onBack = onBack
        )
    }
    body()
}

@Composable
private fun InboxErrorContent(
    copy: InboxScreenCopy,
    state: InboxUiState,
    error: ScreenError,
    title: String? = null,
    showHeader: Boolean = true,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    if (showHeader) {
        InboxHeader(
            title = copy.title,
            content = state.previousContent ?: copy.placeholderContent,
            onBack = onBack
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SlockSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockErrorState(
            title = title ?: copy.errorTitle,
            detail = stringResource(R.string.inbox_error_detail, error.code)
        )
        InboxMarkAllReadButton(onClick = onRetry)
    }
}

@Composable
private fun InboxLoadingBody(overlay: Boolean) {
    SlockLoadingState(
        title =
            if (overlay) {
                stringResource(R.string.inbox_refreshing_body)
            } else {
                stringResource(R.string.inbox_loading_body)
            }
    )
}

private val InboxScreenCopy.placeholderContent: InboxContent
    get() =
        InboxContent(
            server = ServerSummary(ServerId("state"), "Slock Android"),
            items = emptyList(),
            totalCount = 0,
            totalUnreadCount = 0
        )

private data class InboxScreenCopy(
    val title: String,
    val loadingTitle: String,
    val loadingDetail: String,
    val emptyTitle: String,
    val emptyDetail: String,
    val noPermissionTitle: String,
    val errorTitle: String
)

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

private val InboxItem.channelLabelText: String
    get() = when (kind) {
        InboxItemKind.DirectMessage -> channelName
        InboxItemKind.Thread -> "#${parentChannelName ?: channelName}"
        InboxItemKind.Channel -> "#$channelName"
        InboxItemKind.System -> "#$channelName"
    }

@Composable
private fun InboxItem.activityPreview(): InboxActivityPreview {
    val fallback = stringResource(R.string.inbox_no_preview)
    if (kind == InboxItemKind.System) {
        return InboxActivityPreview.System(
            body =
                (latestActivityPreview ?: lastMessagePreview ?: parentMessagePreview)
                    .toInboxPreviewTextOrNull("ui_system") ?: fallback
        )
    }
    val parent = parentMessagePreview.toInboxPreviewTextOrNull("ui_parent")
    val reply = latestActivityPreview.toInboxPreviewTextOrNull("ui_reply")
    val sender = latestActivitySenderName ?: lastMessageSenderName
    if (kind == InboxItemKind.Thread && parent != null && reply != null && reply != parent) {
        return InboxActivityPreview.Reply(
            parentBody = parent,
            sender = sender,
            replyBody = reply
        )
    }
    return InboxActivityPreview.NewMessage(
        body =
            (lastMessagePreview ?: latestActivityPreview ?: parentMessagePreview)
                .toInboxPreviewTextOrNull("ui_body") ?: fallback
    )
}

private fun String?.toInboxPreviewTextOrNull(source: String): String? =
    this?.let { raw ->
        raw.sanitizeInboxPreviewText()
    }?.takeIf { it.isNotBlank() }

private fun String.sanitizeInboxPreviewText(): String =
    replace("☑", "✅")
        .replace("✔", "✅")
        .replace("✓", "✅")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun InboxItem.metadataTimestamp(): String? =
    latestActivityAt ?: lastMessageAt

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
        lastMessageSenderName = "Product-UX-Designer-with-a-very-long-display-name-that-should-ellipsis",
        lastMessageAt = "18:13",
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
