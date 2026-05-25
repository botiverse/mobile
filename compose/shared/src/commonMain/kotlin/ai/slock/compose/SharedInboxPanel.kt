package ai.slock.compose

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.InboxFilter
import ai.slock.compose.data.InboxItem
import ai.slock.compose.data.InboxSnapshot
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.RealInboxRepository
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.model.topLevelThreadId
import ai.slock.compose.network.SlockNetworkModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.CancellationException

@Composable
internal fun SharedHomeActivityScreen(
    bridge: BridgeModule,
    authStore: SlockAuthSessionStore,
    onOpenConversation: (ConversationSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    val pager = LocalActivity.current.getPager() as Pager
    val repository =
        remember(authStore, pager) {
            RealInboxRepository(
                sessionStore = authStore,
                networkClient =
                    KuiklySlockNetworkClient(
                        sessionStore = authStore,
                        networkModule = pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME),
                        warningLogger = { message -> bridge.log("kuikly_network $message") }
                    ),
                logger = { message -> bridge.log("shared_activity $message") },
                warningLogger = { message -> bridge.log("shared_activity_warning $message") }
            )
        }
    var selectedFilter by remember { mutableStateOf(InboxFilter.All) }
    var refreshNonce by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<SharedActivityPanelState>(SharedActivityPanelState.Loading) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(selectedFilter, refreshNonce) {
        val previous = (state as? SharedActivityPanelState.Content)?.snapshot
        if (previous == null) {
            state = SharedActivityPanelState.Loading
        } else {
            refreshing = true
        }
        try {
            val session = authStore.readSession()
            val serverId = session?.currentServerId
            val server = session?.servers?.firstOrNull { it.id == serverId }?.toServerSummary()
            if (session == null || serverId.isNullOrBlank() || server == null) {
                state = SharedActivityPanelState.Error("server_required")
                return@LaunchedEffect
            }
            state =
                when (val result = repository.loadInitial(ServerId(serverId), selectedFilter, limit = 30)) {
                    is DataResult.Content -> SharedActivityPanelState.Content(result.data)
                    is DataResult.Empty ->
                        SharedActivityPanelState.Content(
                            InboxSnapshot(server = server, items = emptyList(), totalCount = 0, totalUnreadCount = 0)
                        )
                    is DataResult.Error -> SharedActivityPanelState.Error(result.error.code)
                    is DataResult.NoPermission -> SharedActivityPanelState.Error(result.blocker.code)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            state = SharedActivityPanelState.Error("activity_network")
        } finally {
            refreshing = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
    ) {
        SharedActivityHeader(
            totalUnreadCount = (state as? SharedActivityPanelState.Content)?.snapshot?.totalUnreadCount ?: 0,
            refreshing = refreshing || state is SharedActivityPanelState.Loading,
            onRefresh = { refreshNonce += 1 }
        )
        SharedActivityFilterBar(
            selectedFilter = selectedFilter,
            onFilterSelected = { selectedFilter = it }
        )
        when (val current = state) {
            SharedActivityPanelState.Loading ->
                SharedActivityStateBody(
                    title = "Loading activity",
                    detail = "Fetching unread conversations from the selected server.",
                    loading = true
                )
            is SharedActivityPanelState.Error ->
                SharedActivityStateBody(
                    title = "Could not load activity",
                    detail = current.code,
                    actionLabel = "Retry",
                    onAction = { refreshNonce += 1 }
                )
            is SharedActivityPanelState.Content ->
                if (current.snapshot.items.isEmpty()) {
                    SharedActivityStateBody(
                        title = "Activity is empty",
                        detail = selectedFilter.emptyCopy()
                    )
                } else {
                    SharedActivityList(
                        snapshot = current.snapshot,
                        onOpenConversation = onOpenConversation
                    )
                }
        }
    }
}

private sealed interface SharedActivityPanelState {
    data object Loading : SharedActivityPanelState
    data class Error(val code: String) : SharedActivityPanelState
    data class Content(val snapshot: InboxSnapshot) : SharedActivityPanelState
}

@Composable
private fun SharedActivityHeader(
    totalUnreadCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    val topSafeArea = slockTopSafeAreaPadding()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(topSafeArea + SlockSize.PageHeaderHeight)
                .background(SlockColor.BrandYellow)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(start = 20.dp, top = topSafeArea, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(SlockSize.HeaderIcon)
                    .background(SlockColor.AccentPink)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon("activity", modifier = Modifier.size(18.dp), color = "#FF000000")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Activity",
                style = SlockType.PageTitle.withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (totalUnreadCount > 0) "$totalUnreadCount unread" else "All caught up",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        SharedActivitySmallButton(
            label = if (refreshing) "..." else "Refresh",
            iconName = "refresh-cw",
            onClick = onRefresh
        )
    }
}

@Composable
private fun SharedActivityFilterBar(
    selectedFilter: InboxFilter,
    onFilterSelected: (InboxFilter) -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InboxFilter.entries.forEach { filter ->
            SharedActivityFilterChip(
                label = filter.label,
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) }
            )
        }
    }
}

@Composable
private fun SharedActivityFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .background(if (selected) SlockColor.AccentCyan else SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Sm))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = if (selected) SlockType.ButtonBold else SlockType.Button,
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
private fun SharedActivityList(
    snapshot: InboxSnapshot,
    onOpenConversation: (ConversationSummary) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(snapshot.items.size, key = { index -> snapshot.items[index].key }) { index ->
            val item = snapshot.items[index]
            SharedActivityRow(
                item = item,
                modifier = Modifier.padding(horizontal = 16.dp),
                onClick = { onOpenConversation(item.toConversationSummary()) }
            )
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun SharedActivityRow(
    item: InboxItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceCream, RoundedCornerShape(SlockRadius.Sm))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable { onClick() }
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(38.dp)
                    .background(item.kind.iconColor())
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon(item.kind.iconName(), modifier = Modifier.size(17.dp), color = "#FF000000")
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.displayTitle(),
                    style = SlockType.ButtonBold,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    SharedActivityCountPill(item.unreadCount.toString())
                }
            }
            Text(
                text = item.previewText(),
                style = SlockType.Button,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.metaText(),
                style = SlockType.Caption,
                color = SlockColor.TextBlack.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SharedActivityCountPill(text: String) {
    Box(
        modifier =
            Modifier
                .widthIn(min = 28.dp)
                .background(SlockColor.AccentPink, RoundedCornerShape(SlockRadius.Sm))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 13.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
private fun SharedActivitySmallButton(
    label: String,
    iconName: String,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Sm))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(14.dp), color = "#FF000000")
        Text(label, style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack, maxLines = 1)
    }
}

@Composable
private fun SharedActivityStateBody(
    title: String,
    detail: String,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier =
                Modifier
                    .size(54.dp)
                    .background(if (loading) SlockColor.BrandYellow else SlockColor.EmptyStateGray)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon(if (loading) "refresh-cw" else "activity", modifier = Modifier.size(22.dp), color = "#FF000000")
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = SlockType.PageTitle, color = SlockColor.TextBlack, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(detail, style = SlockType.Button, color = SlockColor.TextMutedGray, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            SharedActivitySmallButton(label = actionLabel, iconName = "refresh-cw", onClick = onAction)
        }
    }
}

private val InboxFilter.label: String
    get() =
        when (this) {
            InboxFilter.All -> "All"
            InboxFilter.Unread -> "Unread"
            InboxFilter.Mentions -> "Mentions"
        }

private fun InboxFilter.emptyCopy(): String =
    when (this) {
        InboxFilter.All -> "No unread conversations or mentions yet."
        InboxFilter.Unread -> "No unread conversations."
        InboxFilter.Mentions -> "No mentions yet."
    }

private fun InboxItem.displayTitle(): String =
    when (kind) {
        InboxItemKind.Thread -> parentChannelName ?: channelName
        InboxItemKind.DirectMessage, InboxItemKind.Channel, InboxItemKind.System -> channelName
    }.ifBlank { channelId.value }

private fun InboxItem.previewText(): String =
    latestActivityPreview
        ?: lastMessagePreview
        ?: parentMessagePreview
        ?: if (kind == InboxItemKind.Thread) "Thread update" else "Conversation update"

private fun InboxItem.metaText(): String {
    val sender = latestActivitySenderName ?: lastMessageSenderName
    val at = latestActivityAt ?: lastMessageAt
    return listOfNotNull(sender, at).joinToString(" · ").ifBlank {
        when (kind) {
            InboxItemKind.Thread -> "Thread"
            InboxItemKind.DirectMessage -> "Direct message"
            InboxItemKind.Channel -> "Channel"
            InboxItemKind.System -> "System"
        }
    }
}

private fun InboxItemKind.iconName(): String =
    when (this) {
        InboxItemKind.Thread -> "message-square"
        InboxItemKind.DirectMessage -> "mail"
        InboxItemKind.Channel -> "hash"
        InboxItemKind.System -> "bell"
    }

private fun InboxItemKind.iconColor() =
    when (this) {
        InboxItemKind.Thread -> SlockColor.AccentLavender
        InboxItemKind.DirectMessage -> SlockColor.AccentCyan
        InboxItemKind.Channel -> SlockColor.BrandYellow
        InboxItemKind.System -> SlockColor.AccentOrange
    }

private fun InboxItem.toConversationSummary(): ConversationSummary {
    val streamChannelId = threadChannelId ?: channelId
    val parentId = parentMessageId
    val parentChannel = parentChannelId ?: if (kind == InboxItemKind.Thread) channelId else null
    val threadIdentity =
        if (kind == InboxItemKind.Thread && parentChannel != null && parentId != null) {
            threadIdentity(parentChannel, parentId)
        } else {
            null
        }
    return ConversationSummary(
        threadId = topLevelThreadId(streamChannelId),
        channelId = parentChannel ?: channelId,
        thread =
            if (kind == InboxItemKind.Thread && parentChannel != null && parentId != null) {
                ThreadConversation(
                    parentChannelId = parentChannel,
                    parentMessageId = parentId,
                    streamChannelId = streamChannelId
                )
            } else {
                null
            },
        kind =
            parentChannelKind
                ?: when (kind) {
                    InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
                    InboxItemKind.Thread -> ConversationKind.Thread
                    InboxItemKind.System -> ConversationKind.System
                    InboxItemKind.Channel -> ConversationKind.Channel
                },
        name = displayTitle(),
        subtitle = parentMessagePreview ?: lastMessagePreview ?: latestActivityPreview ?: threadIdentity?.value,
        unreadCount = unreadCount,
        unreadCountKnown = true,
        mentionCount = if (hasMention) 1 else 0,
        mentionCountKnown = hasMention,
        lastMessageId = latestActivityMessageId ?: lastMessageId,
        firstUnreadMessageId = firstUnreadMessageId,
        lastMessagePreview = latestActivityPreview ?: lastMessagePreview,
        lastMessageAt = latestActivityAt ?: lastMessageAt,
        lastMessageSenderName = latestActivitySenderName ?: lastMessageSenderName
    )
}
