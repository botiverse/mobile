package ai.slock.compose

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.components.SegmentedControl
import ai.slock.compose.components.SegmentedControlOption
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.data.InboxFilter
import ai.slock.compose.data.InboxItem
import ai.slock.compose.data.InboxSnapshot
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.RealConversationRepository
import ai.slock.compose.data.RealInboxRepository
import ai.slock.compose.data.RealMemberRepository
import ai.slock.compose.data.RealReadReceiptRepository
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.SharedActivityIntent
import ai.slock.compose.data.SharedActivitySenderProfile
import ai.slock.compose.data.SharedActivityState
import ai.slock.compose.data.SharedActivityStore
import ai.slock.compose.markdown.traceMarkdownSection
import ai.slock.compose.markdown.traceMarkdownSectionNonComposable
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.model.topLevelThreadId
import ai.slock.compose.network.SlockNetworkModule
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ExperimentalLayoutApi
import com.tencent.kuikly.compose.foundation.layout.FlowRow
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
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.pullToRefreshItem
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun SharedHomeActivityScreen(
    bridge: BridgeModule,
    authStore: SlockAuthSessionStore,
    onOpenMessage: (HomeMessageOpenRequest) -> Unit,
    onClose: (() -> Unit)? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    includeTopSafeArea: Boolean = true,
    timeContext: SlockTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope(),
    modifier: Modifier = Modifier
) {
    val pager = LocalActivity.current.getPager() as Pager
    val networkClient =
        remember(authStore, pager) {
            KuiklySlockNetworkClient(
                sessionStore = authStore,
                networkModule = pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME),
                warningLogger = { message -> bridge.log("kuikly_network $message") }
            )
        }
    val repository =
        remember(authStore, networkClient) {
            RealInboxRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("shared_activity $message") },
                warningLogger = { message -> bridge.log("shared_activity_warning $message") }
            )
        }
    val readReceiptRepository =
        remember(authStore, networkClient) {
            RealReadReceiptRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("shared_activity_read $message") },
                warningLogger = { message -> bridge.log("shared_activity_read_warning $message") }
            )
        }
    val conversationRepository =
        remember(authStore, networkClient) {
            RealConversationRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { message -> bridge.log("shared_activity_thread_warning $message") }
            )
        }
    val memberRepository =
        remember(authStore, networkClient) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("shared_activity_member $message") },
                warningLogger = { message -> bridge.log("shared_activity_member_warning $message") }
            )
        }
    val scope = rememberCoroutineScope()
    val activityStore =
        remember(authStore, repository, readReceiptRepository, conversationRepository, memberRepository, scope) {
            SharedActivityStore(
                authStore = authStore,
                inboxRepository = repository,
                readReceiptRepository = readReceiptRepository,
                conversationRepository = conversationRepository,
                memberRepository = memberRepository,
                scope = scope
            )
        }
    DisposableEffect(activityStore) {
        onDispose { activityStore.clear() }
    }
    val state by activityStore.state.collectAsState()
    LaunchedEffect(activityStore) {
        activityStore.dispatch(SharedActivityIntent.LoadInitial)
    }
    val listState = rememberLazyListState()

    SharedActivityPanelContent(
        state = state,
        onOpenMessage = onOpenMessage,
        onClose = onClose,
        onIntent = activityStore::dispatch,
        bottomContentPadding = bottomContentPadding,
        topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
        timeContext = timeContext,
        listState = listState,
        modifier = modifier
    )
}

@Composable
internal fun SharedHomeActivityScreen(
    state: SharedActivityState,
    onIntent: (SharedActivityIntent) -> Unit,
    onOpenMessage: (HomeMessageOpenRequest) -> Unit,
    onClose: (() -> Unit)? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    includeTopSafeArea: Boolean = true,
    timeContext: SlockTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope(),
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    SharedActivityPanelContent(
        state = state,
        onOpenMessage = onOpenMessage,
        onClose = onClose,
        onIntent = onIntent,
        bottomContentPadding = bottomContentPadding,
        topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
        timeContext = timeContext,
        listState = listState,
        modifier = modifier
    )
}

@Composable
internal fun SharedHomeActivityPanel(
    snapshot: InboxSnapshot,
    selectedFilter: InboxFilter = InboxFilter.All,
    onOpenMessage: (HomeMessageOpenRequest) -> Unit = {},
    onClose: (() -> Unit)? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    includeTopSafeArea: Boolean = false,
    timeContext: SlockTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope(),
    modifier: Modifier = Modifier
) {
    // #192 trace scope extension: wrap the Inbox panel render. The Inbox is
    // recomposed on every state snapshot change; this marker tells us how much
    // wall-time the panel render itself costs on the UI thread (excluding
    // inner List measure which we'd add separately if this shows hot).
    val listState = rememberLazyListState()
    traceMarkdownSection(SlockInboxTraceNames.PANEL_RENDER, snapshot.items.size) {
        SharedActivityPanelContent(
            state = SharedActivityState(selectedFilter = selectedFilter, snapshot = snapshot, loaded = true),
            onOpenMessage = onOpenMessage,
            onClose = onClose,
            onIntent = {},
            bottomContentPadding = bottomContentPadding,
            topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
            timeContext = timeContext,
            listState = listState,
            modifier = modifier
        )
    }
}

@Composable
private fun SharedActivityPanelContent(
    state: SharedActivityState,
    onOpenMessage: (HomeMessageOpenRequest) -> Unit,
    onClose: (() -> Unit)?,
    onIntent: (SharedActivityIntent) -> Unit,
    bottomContentPadding: Dp,
    topSafeAreaPadding: Dp,
    timeContext: SlockTimeContext?,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
    ) {
        SharedActivityHeader(
            totalCount = state.totalCount,
            totalUnreadCount = state.totalUnreadCount,
            onClose = onClose,
            topSafeAreaPadding = topSafeAreaPadding,
            refreshing = state.refreshing && !state.pullRefreshing
        )
        SharedActivityFilterBar(
            selectedFilter = state.selectedFilter,
            totalUnreadCount = state.totalUnreadCount,
            onFilterSelected = { onIntent(SharedActivityIntent.SetFilter(it)) },
            onMarkAllRead = { onIntent(SharedActivityIntent.MarkAllRead) }
        )
        val snapshot = state.snapshot
        when {
            snapshot == null && state.loading ->
                SharedActivityStateBody(
                    title = "Loading Inbox...",
                    detail = "",
                    loading = true,
                    bottomContentPadding = bottomContentPadding
                )
            snapshot == null && state.errorCode != null ->
                SharedActivityRefreshableStateBody(
                    title = "Could not load activity",
                    detail = state.errorCode,
                    actionLabel = if (state.refreshing || state.loading) "..." else "Retry",
                    onAction = { onIntent(SharedActivityIntent.Refresh) },
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
                    bottomContentPadding = bottomContentPadding,
                    listState = listState
                )
            snapshot == null ->
                SharedActivityRefreshableStateBody(
                    title = "Activity is empty",
                    detail = state.selectedFilter.emptyCopy(),
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
                    bottomContentPadding = bottomContentPadding,
                    listState = listState
                )
            snapshot.items.isEmpty() ->
                SharedActivityRefreshableStateBody(
                    title =
                        when (state.selectedFilter) {
                            InboxFilter.Mentions -> "No mentions yet"
                            InboxFilter.Unread -> "No unread chats"
                            InboxFilter.All -> "Activity is empty"
                        },
                    detail = state.selectedFilter.emptyCopy(),
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
                    bottomContentPadding = bottomContentPadding,
                    listState = listState
                )
            else ->
                SharedActivityList(
                    snapshot = snapshot,
                    senderProfiles = state.senderProfiles,
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    loadingMore = state.loadingMore,
                    loadMoreErrorCode = state.loadMoreErrorCode,
                    onOpenMessage = onOpenMessage,
                    onIntent = onIntent,
                    bottomContentPadding = bottomContentPadding,
                    timeContext = timeContext,
                    listState = listState
                )
        }
    }
}

@Composable
private fun SharedActivityHeader(
    totalCount: Int,
    totalUnreadCount: Int,
    onClose: (() -> Unit)?,
    topSafeAreaPadding: Dp,
    refreshing: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth().height(62.dp + topSafeAreaPadding).background(SlockColor.SurfaceWhite)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(60.dp + topSafeAreaPadding)
                    .padding(start = 20.dp, top = topSafeAreaPadding + 10.dp, end = 20.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onClose != null) {
                SlockBrutalIconButton(iconName = SlockLucideIconName.ArrowLeft, onClick = onClose, size = 32.dp, shadowOffset = 2.dp)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Activity",
                    style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$totalCount active" + if (totalUnreadCount > 0) " · $totalUnreadCount unread" else "",
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.52f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (refreshing) {
                SlockHeaderRefreshIndicator()
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
private fun SharedActivityFilterBar(
    selectedFilter: InboxFilter,
    totalUnreadCount: Int,
    onFilterSelected: (InboxFilter) -> Unit,
    onMarkAllRead: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().height(54.dp).background(SlockColor.SurfaceWhite)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SegmentedControl(
                selectedValue = selectedFilter.wireValue,
                options =
                    InboxFilter.entries.map { filter ->
                        SegmentedControlOption(value = filter.wireValue, label = filter.label)
                    },
                onValueSelected = { value ->
                    InboxFilter.entries.firstOrNull { it.wireValue == value }?.let(onFilterSelected)
                },
                modifier = Modifier.weight(1f)
            )
            if (totalUnreadCount > 0) {
                SlockBrutalActionButton(
                    label = "Mark all read",
                    minHeight = 28.dp,
                    minWidth = 0.dp,
                    shadowOffset = 2.dp,
                    backgroundColor = SlockColor.SurfaceWhite,
                    textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    onClick = onMarkAllRead
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
private fun SharedActivityList(
    snapshot: InboxSnapshot,
    senderProfiles: Map<String, SharedActivitySenderProfile>,
    refreshing: Boolean,
    pullRefreshing: Boolean,
    loadingMore: Boolean,
    loadMoreErrorCode: String?,
    onOpenMessage: (HomeMessageOpenRequest) -> Unit,
    onIntent: (SharedActivityIntent) -> Unit,
    bottomContentPadding: Dp,
    timeContext: SlockTimeContext?,
    listState: LazyListState = rememberLazyListState()
) {
    var activeMenuItem by remember { mutableStateOf<InboxItem?>(null) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    val pullToRefreshState = rememberPullToRefreshState(pullRefreshing)
    LaunchedEffect(snapshot.items.size, snapshot.hasMore, loadingMore) {
        snapshotFlow { listState.isActivityLoadMoreThreshold(snapshot.items.size) }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad && snapshot.hasMore && !loadingMore) {
                    onIntent(SharedActivityIntent.LoadMore)
                }
            }
    }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            pullToRefreshItem(
                state = pullToRefreshState,
                scrollState = listState,
                onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
                content = { pullProgress, isRefreshing, _ ->
                    SlockRaftPullRefreshIndicator(
                        pullProgress = pullProgress,
                        isRefreshing = isRefreshing
                    )
                }
            )
            items(snapshot.items.size, key = { index -> snapshot.items[index].key }) { index ->
                val item = snapshot.items[index]
                SharedActivityRow(
                    item = item,
                    senderProfiles = senderProfiles,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    timeContext = timeContext,
                    onOpen = {
                        if (item.unreadCount > 0) {
                            onIntent(SharedActivityIntent.MarkRead(item))
                        }
                        onOpenMessage(item.toHomeMessageOpenRequest())
                    },
                    onDone = { onIntent(SharedActivityIntent.MarkDone(item)) },
                    onOpenMenu = { anchor ->
                        activeMenuItem = item
                        menuAnchor = anchor
                    }
                )
            }
            when {
                loadingMore -> {
                    item(key = "activity_load_more") {
                        SharedActivityLoadingMoreRow()
                    }
                }
                snapshot.hasMore && loadMoreErrorCode != null -> {
                    item(key = "activity_load_more_error") {
                        SharedActivityLoadMoreRetryRow(onClick = { onIntent(SharedActivityIntent.LoadMore) })
                    }
                }
            }
            item { Spacer(Modifier.height(bottomContentPadding)) }
        }
        activeMenuItem?.let { item ->
            SlockBaseMenuOverlay(
                anchor = menuAnchor,
                items =
                    listOf(
                        SlockBaseMenuItem(
                            id = "toggle-read",
                            label = if (item.unreadCount > 0) "Mark as Read" else "Mark as Unread",
                            icon = SlockLucideIconName.Mail
                        ),
                        SlockBaseMenuItem(id = "done", label = "Done", icon = SlockLucideIconName.Check)
                    ),
                selectedId = "",
                uppercaseLabels = false,
                onDismiss = { activeMenuItem = null },
                onSelect = { id ->
                    when (id) {
                        "toggle-read" ->
                            if (item.unreadCount > 0) {
                                onIntent(SharedActivityIntent.MarkRead(item))
                            } else {
                                onIntent(SharedActivityIntent.MarkUnread(item))
                            }
                        "done" -> onIntent(SharedActivityIntent.MarkDone(item))
                    }
                    activeMenuItem = null
                }
            )
        }
    }
}

@Composable
private fun SharedActivityRow(
    item: InboxItem,
    senderProfiles: Map<String, SharedActivitySenderProfile>,
    modifier: Modifier = Modifier,
    timeContext: SlockTimeContext?,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onOpenMenu: (Offset) -> Unit
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.TextBlack.copy(alpha = 0.3f))
                .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).activityRowGestures(item.key, onOpen, onOpenMenu)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = item.channelLabel(),
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.52f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.activityTimeLabel(timeContext),
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = com.tencent.kuikly.compose.ui.text.font.FontFamily.Monospace),
                    color = SlockColor.TextBlack.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (item.kind == InboxItemKind.Thread) {
                    SlockLucideIcon(SlockLucideIconName.MessageSquare, modifier = Modifier.size(13.dp), color = "#FFFF4B9B")
                }
                Text(
                    text = item.primaryPreviewText(senderProfiles),
                    style =
                        if (item.unreadCount > 0) {
                            SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp)
                        } else {
                            SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp)
                        },
                    color = if (item.kind == InboxItemKind.Thread && item.unreadCount == 0) SlockColor.TextBlack.copy(alpha = 0.5f) else SlockColor.TextBlack,
                    maxLines = if (item.kind == InboxItemKind.Thread) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            item.secondaryPreviewText(senderProfiles)?.let { secondary ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = secondary,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = if (item.unreadCount > 0) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SharedActivityFooter(item)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SharedActivitySmallIconButton(iconName = SlockLucideIconName.Check, onClick = onDone)
        }
    }
}

@Composable
private fun Modifier.activityRowGestures(
    itemKey: String,
    onClick: () -> Unit,
    onOpenMenu: (Offset) -> Unit
): Modifier {
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { rootPos = it.positionInRoot() }
        .pointerInput(itemKey) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = { localOffset ->
                    onOpenMenu(Offset(rootPos.x + localOffset.x, rootPos.y + localOffset.y))
                }
            )
        }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SharedActivityFooter(item: InboxItem) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (item.taskNumber != null && item.taskStatus != null) {
            SharedActivityTaskBadge(item)
        }
        if (item.kind == InboxItemKind.Thread) {
            Text(
                text = "${item.replyCount} ${if (item.replyCount == 1) "reply" else "replies"}",
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = SlockColor.TextBlack.copy(alpha = 0.42f),
                maxLines = 1
            )
        }
        if (item.hasMention && item.unreadCount > 0) {
            SharedActivityCountPill("you", iconName = SlockLucideIconName.AtSign, backgroundColor = SlockColor.BrandYellow, textColor = SlockColor.TextBlack)
        }
        if (item.unreadCount > 0) {
            SharedActivityCountPill("${item.unreadCount} new", backgroundColor = SlockColor.AccentPink, textColor = SlockColor.SurfaceWhite)
        }
    }
}

@Composable
private fun SharedActivityTaskBadge(item: InboxItem) {
    val background =
        when (item.taskStatus) {
            ai.slock.compose.model.TaskStatus.Todo -> SlockColor.AccentOrange
            ai.slock.compose.model.TaskStatus.InProgress -> SlockColor.AccentCyan
            ai.slock.compose.model.TaskStatus.InReview -> SlockColor.AccentLavender
            ai.slock.compose.model.TaskStatus.Done -> SlockColor.AccentLime
            ai.slock.compose.model.TaskStatus.Closed -> SlockColor.AccentRed
            null -> SlockColor.SurfaceWhite
        }
    SharedActivityCountPill(
        text = "#${item.taskNumber}" + item.taskClaimedByName?.let { "\n@$it" }.orEmpty(),
        iconName = item.taskStatus?.activityTaskIconName(),
        backgroundColor = background,
        textColor = SlockColor.TextBlack,
        modifier = item.taskClaimedByName?.let { Modifier.width(82.dp) } ?: Modifier
    )
}

@Composable
private fun SharedActivityCountPill(
    text: String,
    iconName: SlockLucideIconName? = null,
    backgroundColor: com.tencent.kuikly.compose.ui.graphics.Color = SlockColor.AccentPink,
    textColor: com.tencent.kuikly.compose.ui.graphics.Color = SlockColor.TextBlack,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(SlockRadius.Sm)
    Box(
        modifier =
            modifier
                .widthIn(min = 22.dp)
                .background(backgroundColor, pillShape)
                .border(SlockStroke.Thin, SlockColor.BorderBlack, pillShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (iconName != null) 4.dp else 0.dp)
        ) {
            iconName?.let { name ->
                SlockLucideIcon(name, modifier = Modifier.size(10.dp), color = "#FF000000")
            }
            Text(
                text = text,
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = textColor,
                maxLines = if (text.contains('\n')) 4 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SharedActivitySmallIconButton(
    iconName: SlockLucideIconName,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SlockBrutalIconButton(iconName = iconName, modifier = modifier, onClick = onClick, size = 28.dp, shadowOffset = 2.dp)
}

@Composable
private fun SharedActivitySmallButton(
    label: String,
    iconName: SlockLucideIconName,
    onClick: () -> Unit
) {
    SlockBrutalActionButton(
        label = label,
        leadingIcon = iconName,
        minHeight = 30.dp,
        minWidth = 0.dp,
        shadowOffset = 2.dp,
        backgroundColor = SlockColor.SurfaceWhite,
        textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp),
        onClick = onClick
    )
}

@Composable
private fun SharedActivityLoadingMoreRow() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading more...",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun SharedActivityLoadMoreRetryRow(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Could not load more.",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.padding(end = 8.dp)
        )
        SlockBrutalActionButton(
            label = "Retry",
            minHeight = 30.dp,
            minWidth = 0.dp,
            shadowOffset = 2.dp,
            backgroundColor = SlockColor.SurfaceWhite,
            textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp),
            onClick = onClick
        )
    }
}

@Composable
private fun SharedActivityRefreshableStateBody(
    title: String,
    detail: String,
    actionLabel: String? = null,
    refreshing: Boolean,
    pullRefreshing: Boolean,
    onRefresh: () -> Unit,
    bottomContentPadding: Dp,
    listState: LazyListState,
    onAction: () -> Unit = {}
) {
    val pullToRefreshState = rememberPullToRefreshState(pullRefreshing)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        pullToRefreshItem(
            state = pullToRefreshState,
            scrollState = listState,
            onRefresh = onRefresh,
            content = { pullProgress, isRefreshing, _ ->
                SlockRaftPullRefreshIndicator(
                    pullProgress = pullProgress,
                    isRefreshing = isRefreshing
                )
            }
        )
        item(key = "activity_state") {
            SharedActivityStateBody(
                title = title,
                detail = detail,
                actionLabel = actionLabel,
                bottomContentPadding = bottomContentPadding,
                onAction = onAction,
                modifier = Modifier.fillParentMaxSize()
            )
        }
    }
}

@Composable
private fun SharedActivityStateBody(
    title: String,
    detail: String,
    loading: Boolean = false,
    actionLabel: String? = null,
    bottomContentPadding: Dp,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (loading) {
        SlockCenteredLoading(message = title)
        return
    }
    SlockEmptyState(
        title = title,
        detail = detail,
        iconName = SlockLucideIconName.Activity,
        actionLabel = actionLabel,
        onAction = onAction.takeIf { actionLabel != null },
        modifier = modifier.padding(bottom = bottomContentPadding)
    )
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

private fun InboxItem.channelLabel(): String =
    when (kind) {
        InboxItemKind.DirectMessage -> "@${channelName.ifBlank { channelId.value }}"
        InboxItemKind.Thread -> "#${(parentChannelName ?: channelName).ifBlank { channelId.value }}"
        InboxItemKind.Channel, InboxItemKind.System -> "#${channelName.ifBlank { channelId.value }}"
    }

private fun InboxItem.senderDisplayName(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): String? =
    if (kind == InboxItemKind.Thread) {
        latestActivitySenderId
            ?.let(senderProfiles::get)
            ?.displayName
            ?: lastMessageSenderId
                ?.let(senderProfiles::get)
                ?.displayName
    } else {
        lastMessageSenderId
            ?.let(senderProfiles::get)
            ?.displayName
            ?: latestActivitySenderId
                ?.let(senderProfiles::get)
                ?.displayName
    }

private fun InboxItem.senderAvatarWireValue(): String? =
    when {
        latestActivitySenderKind != null && latestActivitySenderId != null ->
            avatarWireValueForAvatarSlot(
                kind = latestActivitySenderKind,
                id = latestActivitySenderId,
                avatarWireValue = latestActivitySenderAvatarWireValue
            )
        lastMessageSenderKind != null && lastMessageSenderId != null ->
            avatarWireValueForAvatarSlot(
                kind = lastMessageSenderKind,
                id = lastMessageSenderId,
                avatarWireValue = lastMessageSenderAvatarWireValue
            )
        else -> latestActivitySenderAvatarWireValue ?: lastMessageSenderAvatarWireValue
    }

internal fun InboxItem.activityTimeLabel(timeContext: SlockTimeContext?): String =
    timeContext?.formatTimestampLabel(latestActivityAt ?: lastMessageAt)
        ?: latestActivityAt
        ?: lastMessageAt
        ?: ""

private fun InboxItem.previewText(): String =
    if (kind == InboxItemKind.Thread) {
        parentMessagePreview ?: latestActivityPreview ?: lastMessagePreview ?: "Thread update"
    } else {
        lastMessagePreview ?: latestActivityPreview ?: "Conversation update"
    }

internal fun InboxItem.primaryPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): String =
    if (kind == InboxItemKind.Thread) {
        previewText()
    } else {
        senderDisplayName(senderProfiles)?.let { "$it: ${previewText()}" } ?: previewText()
    }

internal fun InboxItem.secondaryPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): String? =
    latestActivityPreview
        ?.takeIf { kind == InboxItemKind.Thread && replyCount > 0 && it != previewText() }
        ?.let { preview ->
            senderDisplayName(senderProfiles)?.let { "$it: $preview" } ?: preview
        }

private fun ai.slock.compose.model.TaskStatus.activityTaskIconName(): SlockLucideIconName =
    when (this) {
        ai.slock.compose.model.TaskStatus.Todo -> SlockLucideIconName.Circle
        ai.slock.compose.model.TaskStatus.InProgress -> SlockLucideIconName.Play
        ai.slock.compose.model.TaskStatus.InReview -> SlockLucideIconName.Eye
        ai.slock.compose.model.TaskStatus.Done -> SlockLucideIconName.CircleCheckBig
        ai.slock.compose.model.TaskStatus.Closed -> SlockLucideIconName.Ban
    }

private fun LazyListState.isActivityLoadMoreThreshold(lastActivityRowIndex: Int): Boolean {
    if (lastActivityRowIndex < 1) return false
    val visibleLastIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return visibleLastIndex >= lastActivityRowIndex - 3
}

internal fun InboxItem.toConversationSummary(): ConversationSummary {
    val streamChannelId = resolveInboxStreamChannelId()
    val parentId = parentMessageId
    val parentChannel = parentChannelId
    val stableThreadIdentity =
        if (kind == InboxItemKind.Thread && parentChannel != null && parentId != null) {
            threadIdentity(parentChannel, parentId)
        } else {
            null
        }
    return ConversationSummary(
        threadId = stableThreadIdentity ?: topLevelThreadId(channelId),
        channelId = if (kind == InboxItemKind.Thread) parentChannel else channelId,
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
            when (kind) {
                InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
                InboxItemKind.Thread -> ConversationKind.Thread
                InboxItemKind.System -> ConversationKind.System
                InboxItemKind.Channel -> parentChannelKind ?: ConversationKind.Channel
            },
        name = displayTitle(),
        subtitle = parentMessagePreview ?: lastMessagePreview ?: latestActivityPreview ?: stableThreadIdentity?.value,
        unreadCount = unreadCount,
        unreadCountKnown = true,
        mentionCount = if (hasMention) 1 else 0,
        mentionCountKnown = hasMention,
        lastMessageId = latestActivityMessageId ?: lastMessageId,
        firstUnreadMessageId = firstUnreadMessageId,
        lastMessagePreview = latestActivityPreview ?: lastMessagePreview,
        lastMessageAt = latestActivityAt ?: lastMessageAt,
        lastMessageSenderName = latestActivitySenderName ?: lastMessageSenderName,
        originMessagePreview = parentMessagePreview,
        originMessageSenderId = parentMessageSenderId,
        originMessageSenderKind = parentMessageSenderKind,
        originMessageExpected = kind == InboxItemKind.Thread && parentId != null
    )
}

internal data class HomeMessageOpenRequest(
    val conversation: ConversationSummary,
    val anchorMessageId: String?
)

/**
 * Per-kind stream channel id resolver. Replaces the prior silent fallback
 * `if (kind == Thread) threadChannelId else threadChannelId ?: channelId` (反例 1 in #280 audit).
 *
 * - Thread items resolve to their explicit `threadChannelId` (v0.2 contract requires the
 *   thread stream id to be distinct from `channelId`).
 * - Non-thread items resolve to `null`. The downstream `ThreadConversation` field is only
 *   populated for Thread items, so this is the safe default.
 *
 * Future sealed `InboxItem.Thread` (Phase 3 #3 (B)) will encode this in the type system:
 * `threadChannelId` becomes a required non-null constructor parameter on the Thread subclass.
 */
internal fun InboxItem.resolveInboxStreamChannelId(): ChannelId? =
    when (kind) {
        InboxItemKind.Thread -> threadChannelId
        InboxItemKind.Channel, InboxItemKind.DirectMessage, InboxItemKind.System -> null
    }

/**
 * 3-level anchor chain used by [InboxItem.toHomeMessageOpenRequest] and Saved store anchor
 * resolution. Thread items must NOT fall back to `parentMessageId` — that field is the parent
 * channel origin, not a thread reply id; mixing it in would silently degrade the reply-stream
 * anchor (regression covered by `appShellActivityThreadItemShowsLatestReplyAsAnchorWhenUnreadIsEmpty`
 * and `SharedInboxPanelTest.inboxPanelThreadItemAnchor3LevelChain`).
 *
 * Replaces the prior 4-level chain `firstUnreadMessageId ?: latestActivityMessageId
 * ?: lastMessageId ?: parentMessageId` (反例 11 in #280 audit).
 */
internal fun InboxItem.resolveInboxAnchorMessageId(): MessageId? =
    firstUnreadMessageId ?: latestActivityMessageId ?: lastMessageId

internal fun InboxItem.toHomeMessageOpenRequest(): HomeMessageOpenRequest =
    HomeMessageOpenRequest(
        conversation = toConversationSummary(),
        anchorMessageId = resolveInboxAnchorMessageId()?.value?.takeIf { it.isNotBlank() }
    )
