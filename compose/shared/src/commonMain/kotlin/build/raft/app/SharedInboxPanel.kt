package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.SlockAnchoredMetaChip
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.ActivityUnreadSummaryProjection
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealInboxRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealReadReceiptRepository
import build.raft.app.data.NetworkSlockThreadApi
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.SharedActivitySenderProfile
import build.raft.app.data.SharedActivityState
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.ThreadConversation
import build.raft.app.model.threadIdentity
import build.raft.app.model.topLevelThreadId
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
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
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.text.withStyle
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val ActivitySelectedRowShadowOffset = 2.dp
private const val ActivityUpdateTrackingWindow = 20
private const val ActivityRetiredUpdateTrackingWindow = ActivityUpdateTrackingWindow * 5

@Composable
internal fun SharedHomeActivityScreen(
    bridge: BridgeModule,
    authStore: SlockAuthSessionStore,
    routeVisibleTick: Int = 0,
    onOpenMessage: (HomeMessageOpenRequest) -> Unit,
    onClose: (() -> Unit)? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    includeTopSafeArea: Boolean = true,
    timeContext: SlockTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope(),
    storeScope: SlockShellStoreScope = SlockShellStoreScope.Legacy,
    listState: LazyListState = rememberLazyListState(),
    activityTaskStatusBadgesEnabled: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val pager = LocalActivity.current.getPager() as Pager
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
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
    val selectedServerStore = remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val replyThreadPrewarmDataSource =
        remember(
            authStore,
            selectedServerStore,
            networkClient,
            conversationRepository,
            sharedStores.threadMessageStore,
            sharedStores.threadPayloadStore
        ) {
            SharedThreadDataSource(
                networkApi = NetworkSlockThreadApi(networkClient),
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = conversationRepository,
                messageStore = sharedStores.threadMessageStore,
                payloadStore = sharedStores.threadPayloadStore,
                threadPageSizeProvider = { serverId ->
                    sharedStores.serverFeatureFlags.snapshotFor(serverId).mobileThreadPageSize()
                },
                diagnosticsLogger = { message -> bridge.log("shared_activity_thread_prewarm $message") }
            )
        }
    val activityStore =
        remember(
            storeScope,
            authStore,
            repository,
            readReceiptRepository,
            conversationRepository,
            memberRepository,
            replyThreadPrewarmDataSource
        ) {
            sharedStores.getOrCreateHomeActivityStore(
                scope = storeScope,
                inboxRepository = repository,
                readReceiptRepository = readReceiptRepository,
                conversationRepository = conversationRepository,
                memberRepository = memberRepository,
                prewarmReplyThread = replyThreadPrewarmDataSource::prewarmReplyThread
            )
        }
    val state by activityStore.state.collectAsState()
    val activitySummaryServerId =
        remember(state.ownerServerId, state.snapshot?.server?.id?.value) {
            ServerId(state.ownerServerId ?: state.snapshot?.server?.id?.value.orEmpty())
        }
    val activitySummaryFlow =
        remember(sharedStores.activityUnreadSummaryStore, activitySummaryServerId) {
            sharedStores.activityUnreadSummaryStore.projectionFlow(activitySummaryServerId)
        }
    val activitySummary by activitySummaryFlow.collectAsState(
        sharedStores.activityUnreadSummaryStore.currentProjection(activitySummaryServerId)
    )
    val projectedState =
        remember(state, activitySummary) {
            projectActivityStateWithProcessSummary(state, activitySummary)
        }
    val sharedHomeStore = remember(sharedStores, storeScope) {
        sharedStores.sharedHomeStore(storeScope)
    }
    // t5/#419 A3: user-initiated opens resolve against the real conversation directory
    // (flow-based single source, #240) with the snapshot's real server id — the resolver is
    // the only exit; Pending is an explainable failure, never a guessed channel-shaped nav.
    val conversationDirectoryFlow =
        remember(sharedStores, state.snapshot?.server?.id) {
            state.snapshot?.server?.id
                ?.let { serverId -> sharedStores.conversationIdentityProvider(storeScope)?.observeServer(serverId) }
                ?: flowOf(SharedConversationDirectoryState())
        }
    val conversationDirectory by conversationDirectoryFlow.collectAsState(SharedConversationDirectoryState())
    val activityConversationDirectory = remember(state.snapshot) {
        state.snapshot?.toSharedConversationDirectoryState() ?: SharedConversationDirectoryState()
    }
    val openConversationDirectory = remember(activityConversationDirectory, conversationDirectory) {
        listOf(activityConversationDirectory, conversationDirectory).mergedSharedConversationDirectoryState()
    }
    val activeDraftRecords by sharedStores.composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    val conversationNotReadyMessage = stringResource(MR.strings.inbox_error_conversation_not_ready)
    LaunchedEffect(activityStore) {
        activityStore.dispatch(SharedActivityIntent.LoadInitial)
    }
    LaunchedEffect(sharedStores.activityUnreadSummaryStore, activitySummaryServerId) {
        if (activitySummaryServerId.value.isNotBlank()) {
            sharedStores.activityUnreadSummaryStore.ensure(
                activitySummaryServerId,
                reason = "activity_route_attach"
            )
        }
    }
    LaunchedEffect(sharedHomeStore, state.snapshot) {
        state.snapshot?.let { snapshot ->
            sharedHomeStore?.supplementConversationIdentity(snapshot)
        }
    }
    LaunchedEffect(routeVisibleTick) {
        if (activityPageDidAppearShouldRefresh(routeVisibleTick)) {
            activityStore.dispatch(SharedActivityIntent.Refresh)
        }
    }
    SharedActivityPanelContent(
        state = projectedState,
        onOpenItem = { item ->
            state.snapshot?.let { snapshot ->
                sharedHomeStore?.supplementConversationIdentity(snapshot)
            }
            when (
                val resolution =
                    item.toHomeMessageOpenRequest(
                        serverId = state.snapshot?.server?.id?.value.orEmpty(),
                        conversationDirectory = openConversationDirectory,
                        diagnosticsLogger = bridge::log
                    )
            ) {
                is ConversationOpenResolution.Resolved -> onOpenMessage(resolution.request)
                is ConversationOpenResolution.Pending -> {
                    bridge.logWarn(
                        "activity_open_request_pending reason=${resolution.reason} " +
                            "itemKey=${item.key} kind=${item.kind.name}"
                    )
                    bridge.toast(conversationNotReadyMessage)
                }
            }
        },
        onClose = onClose,
        onIntent = activityStore::dispatch,
        bottomContentPadding = bottomContentPadding,
        topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
        timeContext = timeContext,
        listState = listState,
        activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
        draftProjection = draftProjection,
        onTitleClick = onTitleClick,
        modifier = modifier
    )
}

internal fun activityPageDidAppearShouldRefresh(routeVisibleTick: Int): Boolean =
    routeVisibleTick > 0

internal data class ActivityRefreshPresentation(
    val headerRefreshing: Boolean,
    val pullRefreshing: Boolean
)

internal fun activityRefreshPresentation(
    refreshing: Boolean,
    pullRefreshing: Boolean
): ActivityRefreshPresentation =
    ActivityRefreshPresentation(
        headerRefreshing = refreshing || pullRefreshing,
        pullRefreshing = pullRefreshing
    )

internal fun projectActivityStateWithProcessSummary(
    state: SharedActivityState,
    activitySummary: ActivityUnreadSummaryProjection
): SharedActivityState {
    val snapshot = state.snapshot
    if (
        snapshot == null ||
        state.selectedFilter != InboxFilter.All ||
        !activitySummary.hasValue
    ) {
        return state
    }
    return state.copy(
        snapshot =
            snapshot.copy(
                totalUnreadCount = activitySummary.totalUnreadCount,
                totalCount = activitySummary.totalCount
            )
    )
}

@Composable
internal fun SharedHomeActivityScreen(
    state: SharedActivityState,
    onIntent: (SharedActivityIntent) -> Unit,
    onOpenItem: (InboxItem) -> Unit,
    onClose: (() -> Unit)? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    includeTopSafeArea: Boolean = true,
    timeContext: SlockTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope(),
    listState: LazyListState = rememberLazyListState(),
    activityTaskStatusBadgesEnabled: Boolean = false,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    onTitleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    SharedActivityPanelContent(
        state = state,
        onOpenItem = onOpenItem,
        onClose = onClose,
        onIntent = onIntent,
        bottomContentPadding = bottomContentPadding,
        topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
        timeContext = timeContext,
        listState = listState,
        activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
        draftProjection = draftProjection,
        onTitleClick = onTitleClick,
        modifier = modifier
    )
}

@Composable
internal fun SharedHomeActivityPanel(
    snapshot: InboxSnapshot,
    selectedFilter: InboxFilter = InboxFilter.All,
    onOpenItem: (InboxItem) -> Unit = {},
    onClose: (() -> Unit)? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    includeTopSafeArea: Boolean = false,
    timeContext: SlockTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope(),
    listState: LazyListState = rememberLazyListState(),
    activityTaskStatusBadgesEnabled: Boolean = false,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    onTitleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // #192 trace scope extension: wrap the Inbox panel render. The Inbox is
    // recomposed on every state snapshot change; this marker tells us how much
    // wall-time the panel render itself costs on the UI thread (excluding
    // inner List measure which we'd add separately if this shows hot).
    traceMarkdownSection(SlockInboxTraceNames.PANEL_RENDER, snapshot.items.size) {
        SharedActivityPanelContent(
            state = SharedActivityState(selectedFilter = selectedFilter, snapshot = snapshot, loaded = true),
            onOpenItem = onOpenItem,
            onClose = onClose,
            onIntent = {},
            bottomContentPadding = bottomContentPadding,
            topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
            timeContext = timeContext,
            listState = listState,
            activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
            draftProjection = draftProjection,
            onTitleClick = onTitleClick,
            modifier = modifier
        )
    }
}

@Composable
private fun SharedActivityPanelContent(
    state: SharedActivityState,
    onOpenItem: (InboxItem) -> Unit,
    onClose: (() -> Unit)?,
    onIntent: (SharedActivityIntent) -> Unit,
    bottomContentPadding: Dp,
    topSafeAreaPadding: Dp,
    timeContext: SlockTimeContext?,
    listState: LazyListState,
    activityTaskStatusBadgesEnabled: Boolean,
    draftProjection: SharedThreadComposerDraftProjection,
    onTitleClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val refreshPresentation = activityRefreshPresentation(state.refreshing, state.pullRefreshing)
    Column(
        modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
    ) {
        SharedActivityHeader(
            totalCount = state.totalCount,
            totalUnreadCount = state.totalUnreadCount,
            onClose = onClose,
            topSafeAreaPadding = topSafeAreaPadding,
            refreshing = refreshPresentation.headerRefreshing,
            onTitleClick = onTitleClick
        )
        SlockAppShellBodyTopBanner()
        SharedActivityFilterBar(
            selectedFilter = state.selectedFilter,
            totalUnreadCount = state.totalUnreadCount,
            onFilterSelected = { onIntent(SharedActivityIntent.SetFilter(it)) },
            onMarkAllRead = { onIntent(SharedActivityIntent.MarkAllRead) }
        )
        SharedActivityBodyContent(
            state = state,
            onOpenItem = onOpenItem,
            onIntent = onIntent,
            bottomContentPadding = bottomContentPadding,
            timeContext = timeContext,
            listState = listState,
            activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
            draftProjection = draftProjection,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

@Composable
private fun SharedActivityBodyContent(
    state: SharedActivityState,
    onOpenItem: (InboxItem) -> Unit,
    onIntent: (SharedActivityIntent) -> Unit,
    bottomContentPadding: Dp,
    timeContext: SlockTimeContext?,
    listState: LazyListState,
    activityTaskStatusBadgesEnabled: Boolean,
    draftProjection: SharedThreadComposerDraftProjection,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    Box(modifier = modifier) {
        when {
            snapshot == null && state.loading ->
                SharedActivityStateBody(
                    title = stringResource(MR.strings.common_loading),
                    detail = "",
                    loading = true,
                    bottomContentPadding = bottomContentPadding
                )
            snapshot == null && state.errorCode != null ->
                SharedActivityRefreshableStateBody(
                    title = stringResource(MR.strings.inbox_error_load_activity),
                    detail = state.errorCode,
                    actionLabel = if (state.refreshing || state.loading) "…" else stringResource(MR.strings.common_retry),
                    onAction = { onIntent(SharedActivityIntent.Refresh) },
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
                    bottomContentPadding = bottomContentPadding,
                    listState = listState
                )
            snapshot == null ->
                SharedActivityRefreshableStateBody(
                    title = stringResource(MR.strings.inbox_state_activity_empty),
                    detail = state.selectedFilter.localizedEmptyCopy(),
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
                            InboxFilter.Mentions -> stringResource(MR.strings.inbox_state_no_mentions)
                            InboxFilter.Unread -> stringResource(MR.strings.inbox_state_no_unread_chats)
                            InboxFilter.All -> stringResource(MR.strings.inbox_state_activity_empty)
                        },
                    detail = state.selectedFilter.localizedEmptyCopy(),
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
                    bottomContentPadding = bottomContentPadding,
                    listState = listState
                )
            else ->
                SharedActivityList(
                    snapshot = remember(snapshot, draftProjection) {
                        snapshot.withComposerDraftProjection(draftProjection)
                    },
                    selectedFilter = state.selectedFilter,
                    senderProfiles = state.senderProfiles,
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    loadingMore = state.loadingMore,
                    loadMoreErrorCode = state.loadMoreErrorCode,
                    onOpenItem = onOpenItem,
                    onIntent = onIntent,
                    bottomContentPadding = bottomContentPadding,
                    timeContext = timeContext,
                    listState = listState,
                    activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled
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
    refreshing: Boolean,
    onTitleClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth().height(SlockSize.PageHeaderHeight + topSafeAreaPadding).background(SlockColor.SurfaceWhite)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SlockSize.PageHeaderContentHeight + topSafeAreaPadding)
                    .padding(start = 20.dp, top = topSafeAreaPadding + 10.dp, end = 20.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onClose != null) {
                SlockBrutalIconButton(iconName = SlockLucideIconName.ArrowLeft, onClick = onClose, size = 32.dp, shadowOffset = 2.dp)
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(enabled = onTitleClick != null) { onTitleClick?.invoke() },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(MR.strings.home_nav_activity),
                    style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (totalUnreadCount > 0) {
                            stringResource(MR.strings.activity_header_active_unread_count, totalCount, totalUnreadCount)
                        } else {
                            stringResource(MR.strings.activity_header_active_count, totalCount)
                        },
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
                        SegmentedControlOption(value = filter.wireValue, label = filter.localizedLabel())
                    },
                onValueSelected = { value ->
                    InboxFilter.entries.firstOrNull { it.wireValue == value }?.let(onFilterSelected)
                },
                modifier = Modifier.weight(1f)
            )
            if (totalUnreadCount > 0) {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.inbox_action_mark_all_read),
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
    selectedFilter: InboxFilter,
    senderProfiles: Map<String, SharedActivitySenderProfile>,
    refreshing: Boolean,
    pullRefreshing: Boolean,
    loadingMore: Boolean,
    loadMoreErrorCode: String?,
    onOpenItem: (InboxItem) -> Unit,
    onIntent: (SharedActivityIntent) -> Unit,
    bottomContentPadding: Dp,
    timeContext: SlockTimeContext?,
    listState: LazyListState = rememberLazyListState(),
    activityTaskStatusBadgesEnabled: Boolean
) {
    var activeMenuItem by remember { mutableStateOf<InboxItem?>(null) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    var selectedItemKey by remember { mutableStateOf<String?>(null) }
    var updateTracking by remember(selectedFilter) { mutableStateOf(ActivityUpdateTrackingState(filter = selectedFilter)) }
    var revealRequest by remember(selectedFilter) { mutableStateOf(0) }
    val latestOnIntent by rememberUpdatedState(onIntent)
    val refreshPresentation = activityRefreshPresentation(refreshing, pullRefreshing)
    val pullToRefreshState = rememberPullToRefreshState(refreshPresentation.pullRefreshing)
    LaunchedEffect(snapshot.items) {
        val visibleItemKeys =
            listState.layoutInfo.visibleItemsInfo.mapNotNull { itemInfo ->
                itemInfo.key as? String
            }
        if (
            activityListShouldRecoverVisibleContent(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                visibleItemKeys = visibleItemKeys,
                currentItemKeys = snapshot.items.map(InboxItem::key)
            )
        ) {
            updateTracking = updateTracking.consumePending()
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(snapshot.items) {
        val selected = selectedItemKey
        if (selected != null && snapshot.items.none { it.key == selected }) {
            selectedItemKey = null
        }
    }
    LaunchedEffect(selectedFilter, snapshot.items) {
        // Realtime arrivals never take ownership of the Activity viewport, even
        // while it is parked at the top. Stable item keys retain the row under
        // the user's finger and the explicit "x new" action owns revealing the
        // new head. Filter changes establish a fresh baseline: All, Unread and
        // Mentions never classify one another's membership changes as arrivals.
        updateTracking = updateTracking.recordSnapshot(selectedFilter, snapshot.items)
    }
    LaunchedEffect(selectedFilter, revealRequest) {
        if (revealRequest > 0) {
            listState.animateScrollToTopExpressive()
        }
    }
    LaunchedEffect(listState, snapshot.server.id, snapshot.items) {
        val activityItemsByKey = snapshot.items.associateBy(InboxItem::key)
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { itemInfo ->
                    (itemInfo.key as? String)
                        ?.let(activityItemsByKey::get)
                        ?.toReplyThreadPrewarmMarker(snapshot.server.id.value)
                }
                .toSet()
        }
            .distinctUntilChanged()
            .collect { markers ->
                onIntent(SharedActivityIntent.PrewarmVisibleReplyThreads(markers))
            }
    }
    DisposableEffect(listState) {
        onDispose {
            latestOnIntent(SharedActivityIntent.DetachPrewarmViewport)
        }
    }
    val showPullToRefreshItem = slockPlatformSupportsKuiklyPullToRefreshItem()
    val showLoadMoreFooter = snapshot.hasMore || loadingMore || loadMoreErrorCode != null
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showPullToRefreshItem) {
                slockRaftPullToRefreshItem(
                    state = pullToRefreshState,
                    scrollState = listState,
                    onRefresh = { onIntent(SharedActivityIntent.PullRefresh) }
                )
            }
            items(snapshot.items.size, key = { index -> snapshot.items[index].key }) { index ->
                val item = snapshot.items[index]
                SharedActivityRow(
                    item = item,
                    senderProfiles = senderProfiles,
                    selected = selectedItemKey == item.key,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    timeContext = timeContext,
                    activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                    captureMenuAnchor = !listState.isScrollInProgress,
                    onOpen = {
                        selectedItemKey = item.key
                        onIntent(
                            SharedActivityIntent.PrewarmActivityTail(
                                activityReplyThreadPrewarmNextAfterClick(
                                    items = snapshot.items,
                                    clickedIndex = index,
                                    serverId = snapshot.server.id.value
                                )
                            )
                        )
                        item.activityOpenReadIntent()?.let(onIntent)
                        onOpenItem(item)
                    },
                    onDone = { onIntent(SharedActivityIntent.MarkDone(item)) },
                    onOpenMenu = { anchor ->
                        activeMenuItem = item
                        menuAnchor = anchor
                    }
                )
            }
            if (showLoadMoreFooter) {
                item(key = "activity_load_more") {
                    val shouldRequest = activityShouldRequestLoadMore(
                        hasMore = snapshot.hasMore,
                        refreshing = refreshing,
                        loadingMore = loadingMore,
                        loadMoreErrorCode = loadMoreErrorCode
                    )
                    // The footer is the tail sentinel. Entering composition is a stronger and
                    // more portable signal than observing LazyList layoutInfo: the latter did not
                    // emit on the affected Kuikly/Android build, leaving the user at the end of
                    // the first page with neither a request nor failure feedback (task #539).
                    // Keep page appends stable so one composed footer cannot cascade through every
                    // remaining page. A refresh is different: it replaces the appended tail with
                    // the first page while this keyed footer can remain composed. Re-key on the
                    // refresh lifecycle so its terminal transition can request the lost tail again.
                    LaunchedEffect(refreshing) {
                        if (shouldRequest) {
                            latestOnIntent(SharedActivityIntent.LoadMore)
                        }
                    }
                    when (activityLoadMoreFooterMode(loadingMore, loadMoreErrorCode)) {
                        ActivityLoadMoreFooterMode.Sentinel -> Spacer(Modifier.fillMaxWidth().height(1.dp))
                        ActivityLoadMoreFooterMode.Loading -> SharedActivityLoadingMoreRow()
                        ActivityLoadMoreFooterMode.Retry ->
                            SharedActivityLoadMoreRetryRow(onClick = { onIntent(SharedActivityIntent.LoadMore) })
                    }
                }
            }
            item { Spacer(Modifier.height(bottomContentPadding)) }
        }
        if (updateTracking.pendingCount > 0) {
            SlockBrutalActionButton(
                label = stringResource(
                    if (updateTracking.pendingCount == 1) MR.strings.activity_updates_one else MR.strings.activity_updates_many,
                    updateTracking.pendingCount
                ),
                onClick = {
                    // Retire exactly the snapshot represented by this button
                    // before scrolling. A later realtime arrival gets its own
                    // marker and remains actionable; animation completion never
                    // clears work that arrived after this click.
                    updateTracking = updateTracking.consumePending()
                    revealRequest += 1
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                minHeight = 30.dp,
                minWidth = 0.dp,
                shadowOffset = 2.dp,
                textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
            )
        }
        activeMenuItem?.let { item ->
            SlockBaseMenuOverlay(
                anchor = menuAnchor,
                items =
                    listOf(
                        SlockBaseMenuItem(
                            id = "toggle-read",
                            label =
                                if (item.unreadCount > 0) {
                                    stringResource(MR.strings.home_action_mark_as_read)
                                } else {
                                    stringResource(MR.strings.home_action_mark_as_unread)
                                },
                            icon = SlockLucideIconName.Mail
                        ),
                        SlockBaseMenuItem(id = "done", label = stringResource(MR.strings.common_done), icon = SlockLucideIconName.Check)
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

/**
 * Activity prewarm is click-progressive: consuming row N arms only the next
 * eligible reply Thread. Warming the full tail after navigation competes with
 * the opened Thread's foreground work, especially while confirming a send.
 */
internal fun activityReplyThreadPrewarmNextAfterClick(
    items: List<InboxItem>,
    clickedIndex: Int,
    serverId: String
): List<ActivityReplyThreadPrewarmMarker> {
    if (clickedIndex !in items.indices) return emptyList()
    return listOfNotNull(
        items
        .drop(clickedIndex + 1)
        .firstNotNullOfOrNull { item -> item.toReplyThreadPrewarmMarker(serverId) }
    )
}

internal fun InboxItem.activityOpenReadIntent(): SharedActivityIntent? {
    // The destination store acknowledges the stable channel/thread identity after it is loaded
    // and visible. Activity rows can reorder while navigation is in flight, so the list must not
    // infer or write read state from its transient presentation snapshot.
    return null
}

internal fun InboxItem.toReplyThreadPrewarmMarker(serverId: String): ActivityReplyThreadPrewarmMarker? {
    if (kind != InboxItemKind.Thread || unreadCount <= 0) return null
    val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedParentChannelId = parentChannelId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedParentMessageId = parentMessageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedThreadChannelId = threadChannelId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (normalizedThreadChannelId == normalizedParentChannelId) return null
    return ActivityReplyThreadPrewarmMarker(
        key =
            ActivityReplyThreadPrewarmKey(
                serverId = normalizedServerId,
                parentChannelId = normalizedParentChannelId,
                parentMessageId = normalizedParentMessageId,
                threadChannelId = normalizedThreadChannelId
            )
    )
}

@Composable
private fun SharedActivitySelectedRowSurface(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(end = ActivitySelectedRowShadowOffset, bottom = ActivitySelectedRowShadowOffset)
    ) {
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(x = ActivitySelectedRowShadowOffset, y = ActivitySelectedRowShadowOffset)
                    .background(SlockColor.ShadowBlack)
            )
        }
        content(
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(
                    SlockStroke.Default,
                    if (selected) SlockColor.BorderBlack else SlockColor.TextBlack.copy(alpha = 0.3f)
                )
        )
    }
}

@Composable
private fun SharedActivityRow(
    item: InboxItem,
    senderProfiles: Map<String, SharedActivitySenderProfile>,
    selected: Boolean,
    modifier: Modifier = Modifier,
    timeContext: SlockTimeContext?,
    activityTaskStatusBadgesEnabled: Boolean,
    captureMenuAnchor: Boolean = true,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onOpenMenu: (Offset) -> Unit
) {
    val openGestureKey = remember(item) { item.inboxItemOpenGestureKey() }
    val mutedPrimaryContent = item.activityPrimaryContentMuted()
    val threadFallback = stringResource(MR.strings.activity_fallback_thread_update)
    val actionFallback = stringResource(MR.strings.activity_fallback_action_required)
    val conversationFallback = stringResource(MR.strings.activity_fallback_conversation_update)
    SharedActivitySelectedRowSurface(selected = selected, modifier = modifier) { surfaceModifier ->
        Column(modifier = surfaceModifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .activityRowGestures(openGestureKey, captureMenuAnchor, onOpen, onOpenMenu)
                            .padding(start = 12.dp, top = 12.dp)
                ) {
                // Tag + timestamp share the top row — artin's ruling on #1039
                // (#wg-nightly-visual-fix:4670869d, 2026-07-17): react's
                // timestamp-beside-title placement steals width from the real
                // message content; the android layout is the desired product
                // behavior and react should adopt it (declared react-adopts-later).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Channel tag fills the row so the timestamp is pushed to the
                    // right edge (artin #1171, 2026-07-17): timestamp right-aligned
                    // on the tag row; the title and the rows below do not move.
                    Text(
                        text = item.channelLabel(),
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.52f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 10.dp)
                    )
                    Text(
                        text = item.activityTimeLabel(timeContext),
                        style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = SlockMonoFamily),
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
                        SlockLucideIcon(
                            SlockLucideIconName.MessageSquare,
                            modifier = Modifier.size(13.dp),
                            // react title icon is text-black/45 in EVERY state
                            // (#1039); only the title text dims when read.
                            color = "#73000000",
                            verticalOffsetDp = 2f
                        )
                    }
                    val primaryPreview = item.primaryActivityPreviewText(
                        senderProfiles = senderProfiles,
                        threadFallback = threadFallback,
                        actionFallback = actionFallback,
                        conversationFallback = conversationFallback
                    )
                    Text(
                        text = primaryPreview.toAnnotatedString(),
                        style =
                            if (item.unreadCount > 0) {
                                SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp)
                            } else {
                                SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp)
                            },
                        color = if (mutedPrimaryContent) SlockColor.TextBlack.copy(alpha = 0.5f) else SlockColor.TextBlack,
                        maxLines = if (item.kind == InboxItemKind.Thread) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                item.secondaryActivityPreviewText(
                    senderProfiles = senderProfiles,
                    threadFallback = threadFallback,
                    actionFallback = actionFallback,
                    conversationFallback = conversationFallback
                )?.let { secondary ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = secondary.toAnnotatedString(),
                        style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = if (item.unreadCount > 0) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.45f),
                        // react body is line-clamp-2 (#1039): the preview gets
                        // two lines before ellipsis, not one.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SharedActivityFooter(
                    item = item,
                    activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled
                )
                }
                Row(
                    modifier = Modifier.padding(top = 12.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SharedActivitySmallIconButton(
                        iconName = SlockLucideIconName.Check,
                        clickIdentity = item.activityDoneClickIdentity(),
                        onClick = onDone
                    )
                }
            }
            // This padding is visually part of the card, so keep it in the
            // row's open/long-press target instead of leaving a dead strip
            // below the content. The Done button above remains independent.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .activityRowGestures(openGestureKey, captureMenuAnchor, onOpen, onOpenMenu)
            )
        }
    }
}

internal fun InboxItem.activityPrimaryContentMuted(): Boolean =
    kind != InboxItemKind.System && unreadCount == 0

internal fun InboxItem.activityDoneClickIdentity(): String = "activity-done:$key"

@Composable
private fun Modifier.activityRowGestures(
    gestureKey: String,
    captureMenuAnchor: Boolean,
    onClick: () -> Unit,
    onOpenMenu: (Offset) -> Unit
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnOpenMenu by rememberUpdatedState(onOpenMenu)
    val rootAnchor = remember(gestureKey) { SlockLiveRootAnchorRef() }
    return this
        .then(
            if (captureMenuAnchor) {
                Modifier.slockCaptureLiveRootAnchor(rootAnchor)
            } else {
                Modifier
            }
        )
        .pointerInput(gestureKey) {
            detectTapGestures(
                onTap = { currentOnClick() },
                onLongPress = { localOffset ->
                    rootAnchor.resolve(localOffset)?.let(currentOnOpenMenu)
                }
            )
        }
}

internal fun InboxItem.inboxItemOpenGestureKey(): String =
    // A stable remember-key for the row's gesture handlers. Derived from the row's own
    // identity fields — building an open request here would run the resolver per
    // composition for a value that only needs stability, not routing correctness.
    listOf(
        key,
        kind.name,
        channelId.value,
        threadChannelId?.value.orEmpty(),
        parentChannelId?.value.orEmpty(),
        parentMessageId?.value.orEmpty(),
        resolveInboxAnchorMessageId()?.value.orEmpty(),
        unreadCount.toString()
    ).joinToString("|")

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SharedActivityFooter(
    item: InboxItem,
    activityTaskStatusBadgesEnabled: Boolean
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item.activityFooterSlots(activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled).forEach { slot ->
            when (slot) {
                SharedActivityFooterSlot.Replies ->
                    Text(
                        text = stringResource(
                            if (item.replyCount == 1) MR.strings.activity_replies_one else MR.strings.activity_replies_many,
                            item.replyCount
                        ),
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.42f),
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                SharedActivityFooterSlot.Task -> SharedActivityTaskBadge(
                    item,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                SharedActivityFooterSlot.Mention ->
                    SharedActivityCountPill(
                        stringResource(MR.strings.activity_mention_you),
                        iconName = SlockLucideIconName.AtSign,
                        backgroundColor = SlockColor.BrandYellow,
                        textColor = SlockColor.TextBlack,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                SharedActivityFooterSlot.New ->
                    SharedActivityCountPill(
                        stringResource(MR.strings.activity_new_count, item.unreadCount),
                        backgroundColor = SlockColor.AccentPink,
                        textColor = SlockColor.SurfaceWhite,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                SharedActivityFooterSlot.Draft ->
                    SlockLucideIcon(
                        SlockLucideIconName.Pencil,
                        modifier = Modifier.size(12.dp).align(Alignment.CenterVertically),
                        color = SlockColor.TextMutedGrayArgb
                    )
            }
        }
    }
}

@Composable
private fun SharedActivityTaskBadge(
    item: InboxItem,
    modifier: Modifier = Modifier
) {
    val statusMeta = item.taskStatus?.sharedTaskStatusChipMeta() ?: return
    SlockAnchoredMetaChip(
        label = item.activityTaskBadgeText(),
        iconName = statusMeta.icon,
        iconSize = statusMeta.iconSize,
        color = statusMeta.color,
        modifier = modifier.widthIn(max = 260.dp),
        onClick = null
    )
}

internal fun InboxItem.activityTaskBadgeText(): String =
    "#${taskNumber}" + taskClaimedByName
        ?.takeIf { it.isNotBlank() }
        ?.let { " @$it" }
        .orEmpty()

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
                .padding(horizontal = 6.dp, vertical = 1.dp),
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
    clickIdentity: Any? = null,
    onClick: () -> Unit
) {
    SlockBrutalIconButton(
        iconName = iconName,
        modifier = modifier,
        clickIdentity = clickIdentity,
        onClick = onClick,
        size = 28.dp,
        shadowOffset = 2.dp
    )
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
            text = stringResource(MR.strings.inbox_state_loading_more),
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
            text = stringResource(MR.strings.inbox_error_load_more),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.padding(end = 8.dp)
        )
        SlockBrutalActionButton(
            label = stringResource(MR.strings.common_retry),
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
    val refreshPresentation = activityRefreshPresentation(refreshing, pullRefreshing)
    val pullToRefreshState = rememberPullToRefreshState(refreshPresentation.pullRefreshing)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (slockPlatformSupportsKuiklyPullToRefreshItem()) {
            slockRaftPullToRefreshItem(
                state = pullToRefreshState,
                scrollState = listState,
                onRefresh = onRefresh
            )
        }
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

@Composable
private fun InboxFilter.localizedLabel(): String =
    stringResource(
        when (this) {
            InboxFilter.All -> MR.strings.activity_filter_all
            InboxFilter.Unread -> MR.strings.activity_filter_unread
            InboxFilter.Mentions -> MR.strings.activity_filter_mentions
        }
    )

private fun InboxFilter.emptyCopy(): String =
    when (this) {
        InboxFilter.All -> "No unread conversations or mentions yet."
        InboxFilter.Unread -> "No unread conversations."
        InboxFilter.Mentions -> "No mentions yet."
    }

@Composable
private fun InboxFilter.localizedEmptyCopy(): String =
    stringResource(
        when (this) {
            InboxFilter.All -> MR.strings.inbox_empty_all_detail
            InboxFilter.Unread -> MR.strings.inbox_empty_unread_detail
            InboxFilter.Mentions -> MR.strings.inbox_empty_mentions_detail
        }
    )

private fun InboxItem.displayTitle(): String =
    when (kind) {
        InboxItemKind.Thread -> parentChannelName ?: channelName
        InboxItemKind.DirectMessage, InboxItemKind.Channel, InboxItemKind.MentionAction, InboxItemKind.System -> channelName
    }.ifBlank { channelId.value }

private fun InboxItem.channelLabel(): String =
    when (kind) {
        InboxItemKind.DirectMessage -> "@${channelName.ifBlank { channelId.value }}"
        InboxItemKind.Thread -> "#${(parentChannelName ?: channelName).ifBlank { channelId.value }}"
        InboxItemKind.MentionAction ->
            if (channelKind == ConversationKind.DirectMessage) {
                "@${channelName.ifBlank { channelId.value }}"
            } else {
                "#${channelName.ifBlank { channelId.value }}"
            }
        InboxItemKind.Channel, InboxItemKind.System -> "#${channelName.ifBlank { channelId.value }}"
    }

private fun InboxItem.senderDisplayName(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): String? =
    if (kind == InboxItemKind.Thread) {
        latestActivitySenderId
            ?.let(senderProfiles::get)
            ?.displayName
            ?: latestActivitySenderName
            ?: lastMessageSenderId
                ?.let(senderProfiles::get)
                ?.displayName
            ?: lastMessageSenderName
    } else {
        lastMessageSenderId
            ?.let(senderProfiles::get)
            ?.displayName
            ?: lastMessageSenderName
            ?: latestActivitySenderId
                ?.let(senderProfiles::get)
                ?.displayName
            ?: latestActivitySenderName
    }

internal fun InboxItem.activityTimeLabel(timeContext: SlockTimeContext?): String =
    timeContext?.formatTimestampLabel(latestActivityAt ?: lastMessageAt)
        ?: latestActivityAt
        ?: lastMessageAt
        ?: ""

private fun InboxItem.previewText(
    threadFallback: String,
    actionFallback: String,
    conversationFallback: String
): String =
    when (kind) {
        InboxItemKind.Thread -> parentMessagePreview ?: latestActivityPreview ?: lastMessagePreview ?: threadFallback
        InboxItemKind.MentionAction -> lastMessagePreview ?: latestActivityPreview ?: actionFallback
        else -> lastMessagePreview ?: latestActivityPreview ?: conversationFallback
    }

internal fun InboxItem.primaryPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): String =
    primaryActivityPreviewText(senderProfiles).fullText

internal fun InboxItem.secondaryPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): String? =
    secondaryActivityPreviewText(senderProfiles)?.fullText

internal data class ActivityPreviewText(
    val senderName: String?,
    val body: String
) {
    val fullText: String =
        if (senderName.isNullOrBlank()) {
            body
        } else {
            "$senderName: $body"
        }

    fun toAnnotatedString(): AnnotatedString =
        buildAnnotatedString {
            if (!senderName.isNullOrBlank()) {
                withStyle(slockSpanStyleForWeight(FontWeight.Bold)) {
                    append(senderName)
                    append(": ")
                }
            }
            append(body)
        }
}

internal fun InboxItem.primaryActivityPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    threadFallback: String = "Thread update",
    actionFallback: String = "Action required",
    conversationFallback: String = "Conversation update"
): ActivityPreviewText =
    ActivityPreviewText(
        senderName = if (kind == InboxItemKind.Thread || kind == InboxItemKind.MentionAction) null else senderDisplayName(senderProfiles),
        body = previewText(threadFallback, actionFallback, conversationFallback)
    )

internal fun InboxItem.secondaryActivityPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    threadFallback: String = "Thread update",
    actionFallback: String = "Action required",
    conversationFallback: String = "Conversation update"
): ActivityPreviewText? =
    latestActivityPreview
        ?.takeIf {
            kind == InboxItemKind.Thread && replyCount > 0 &&
                it != previewText(threadFallback, actionFallback, conversationFallback)
        }
        ?.let { preview ->
            ActivityPreviewText(senderName = senderDisplayName(senderProfiles), body = preview)
        }

internal enum class SharedActivityFooterSlot {
    Replies,
    Task,
    Mention,
    New,
    Draft
}

internal fun InboxItem.activityFooterSlots(
    activityTaskStatusBadgesEnabled: Boolean = true
): List<SharedActivityFooterSlot> {
    val slots = mutableListOf<SharedActivityFooterSlot>()
    if (kind == InboxItemKind.Thread) {
        slots += SharedActivityFooterSlot.Replies
    }
    if (activityTaskStatusBadgesEnabled && taskNumber != null && taskStatus != null) {
        slots += SharedActivityFooterSlot.Task
    }
    if (hasMention && unreadCount > 0) {
        slots += SharedActivityFooterSlot.Mention
    }
    if (unreadCount > 0) {
        slots += SharedActivityFooterSlot.New
    }
    if (hasDraft) {
        slots += SharedActivityFooterSlot.Draft
    }
    return slots
}

internal fun activityShouldRequestLoadMore(
    hasMore: Boolean,
    refreshing: Boolean,
    loadingMore: Boolean,
    loadMoreErrorCode: String?
): Boolean = hasMore && !refreshing && !loadingMore && loadMoreErrorCode == null

internal enum class ActivityLoadMoreFooterMode {
    Sentinel,
    Loading,
    Retry
}

internal fun activityLoadMoreFooterMode(
    loadingMore: Boolean,
    loadMoreErrorCode: String?
): ActivityLoadMoreFooterMode =
    when {
        loadMoreErrorCode != null -> ActivityLoadMoreFooterMode.Retry
        loadingMore -> ActivityLoadMoreFooterMode.Loading
        else -> ActivityLoadMoreFooterMode.Sentinel
    }

internal fun activityListShouldRecoverVisibleContent(
    firstVisibleItemIndex: Int,
    visibleItemKeys: List<String>,
    currentItemKeys: List<String>
): Boolean {
    if (firstVisibleItemIndex <= 0 || currentItemKeys.isEmpty()) return false
    val currentKeys = currentItemKeys.toSet()
    return visibleItemKeys.none(currentKeys::contains)
}

internal data class ActivityUpdateMarker(
    val filter: InboxFilter,
    val itemKey: String,
    val latestActivityMessageId: String?
)

internal data class ActivityPendingUpdateLedger(
    val pendingMarkers: Set<ActivityUpdateMarker> = emptySet(),
    val retiredMarkers: Set<ActivityUpdateMarker> = emptySet()
) {
    val count: Int
        get() = pendingMarkers.size

    fun record(markers: Set<ActivityUpdateMarker>): ActivityPendingUpdateLedger {
        val genuinelyPending = markers - retiredMarkers
        if (genuinelyPending.isEmpty()) return this
        return copy(pendingMarkers = pendingMarkers + genuinelyPending)
    }

    fun consumePending(): ActivityPendingUpdateLedger {
        if (pendingMarkers.isEmpty()) return this
        val allRetired = (retiredMarkers + pendingMarkers).toList()
        val retainedFrom = (allRetired.size - ActivityRetiredUpdateTrackingWindow).coerceAtLeast(0)
        val retired =
            allRetired
                .subList(retainedFrom, allRetired.size)
                .toSet()
        return copy(pendingMarkers = emptySet(), retiredMarkers = retired)
    }
}

internal data class ActivityUpdateTrackingState(
    val filter: InboxFilter,
    val previousItems: List<InboxItem> = emptyList(),
    val pendingUpdates: ActivityPendingUpdateLedger = ActivityPendingUpdateLedger()
) {
    val pendingCount: Int
        get() = pendingUpdates.count

    fun recordSnapshot(
        nextFilter: InboxFilter,
        currentItems: List<InboxItem>
    ): ActivityUpdateTrackingState {
        val currentWindow = currentItems.take(ActivityUpdateTrackingWindow)
        if (filter != nextFilter) {
            return ActivityUpdateTrackingState(
                filter = nextFilter,
                previousItems = currentWindow
            )
        }
        val update =
            activityUpdatePresentation(
                filter = nextFilter,
                previousItems = previousItems,
                currentItems = currentItems
            )
        return copy(
            previousItems = currentWindow,
            pendingUpdates = pendingUpdates.record(update.pendingMarkers)
        )
    }

    fun consumePending(): ActivityUpdateTrackingState =
        copy(pendingUpdates = pendingUpdates.consumePending())
}

internal data class ActivityUpdatePresentation(
    val pendingMarkers: Set<ActivityUpdateMarker>
) {
    val pendingIncrement: Int
        get() = pendingMarkers.size
}

internal fun activityUpdatePresentation(
    filter: InboxFilter = InboxFilter.All,
    previousItems: List<InboxItem>,
    currentItems: List<InboxItem>
): ActivityUpdatePresentation {
    // A data arrival is not a viewport-replacement intent. Always accumulate
    // genuine head changes behind the explicit reveal action; do not silently
    // scroll just because the current viewport happens to be near the top.
    val pendingMarkers =
        activityPendingUpdateMarkers(
            filter = filter,
            previousItems = previousItems,
            currentItems = currentItems,
            nearTop = false
        )
    return ActivityUpdatePresentation(pendingMarkers = pendingMarkers)
}

// "↑ x new" counts ONLY genuinely new activity, never the user's own actions.
// Two things qualify (task #554 follow-up — the old whole-signature diff
// counted mark-read/unread/done mutations as "new updates"):
//  1. fresh rows that sorted in above every previously known row (the feed is
//     newest-first, so real arrivals enter at the top; rows revealed from
//     below the tracked window sit under a known key and don't count);
//  2. known conversations whose latest activity MESSAGE changed — read-state
//     and task-status mutations never touch latestActivityMessageId.
internal fun activityPendingUpdateIncrement(
    previousItems: List<InboxItem>,
    currentItems: List<InboxItem>,
    nearTop: Boolean,
    filter: InboxFilter = InboxFilter.All
): Int = activityPendingUpdateMarkers(filter, previousItems, currentItems, nearTop).size

internal fun activityPendingUpdateMarkers(
    filter: InboxFilter,
    previousItems: List<InboxItem>,
    currentItems: List<InboxItem>,
    nearTop: Boolean
): Set<ActivityUpdateMarker> {
    if (nearTop || previousItems.isEmpty()) return emptySet()
    val previousByKey = previousItems.associateBy { it.key }
    val currentWindow = currentItems.take(ActivityUpdateTrackingWindow)
    if (currentWindow.isEmpty()) return emptySet()
    val freshTopRows = currentWindow.takeWhile { it.key !in previousByKey }
    val advancedKnownRows =
        currentWindow.filter { current ->
            val previous = previousByKey[current.key] ?: return@filter false
            val currentLatest = current.latestActivityMessageId?.value
            currentLatest != null && currentLatest != previous.latestActivityMessageId?.value
        }
    return (freshTopRows + advancedKnownRows)
        .mapTo(linkedSetOf()) { item ->
            ActivityUpdateMarker(
                filter = filter,
                itemKey = item.key,
                latestActivityMessageId = item.latestActivityMessageId?.value
            )
        }
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
                    streamChannelId = streamChannelId,
                    parentSurfaceKind = parentChannelKind.threadParentSurfaceKindOrNull(),
                    parentChannelKind = parentChannelKind.threadChannelKindWireValue()
                )
            } else {
                null
            },
        kind =
            launchConversationKind(),
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
        lastMessageSenderName = latestActivitySenderName ?: lastMessageSenderName
    )
}

internal data class ThreadLaunchOriginFallback(
    val parentChannelId: String?,
    val parentMessageId: String,
    val preview: String?,
    val senderId: String?,
    val senderKind: MemberKind?,
    val expected: Boolean = true
)

internal data class HomeMessageOpenRequest(
    val conversationIdentity: ThreadLaunchConversationIdentity,
    val anchorMessageId: String?,
    val originFallback: ThreadLaunchOriginFallback? = null
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
        InboxItemKind.Channel, InboxItemKind.DirectMessage, InboxItemKind.MentionAction, InboxItemKind.System -> null
    }

/**
 * Open anchor used by Activity, Search, and Saved rows. This mirrors React's row-open
 * contract: `firstUnreadMessageId` is only an anchor while the row is actually unread.
 * Thread rows focus reply ids (`firstUnreadMessageId` / `latestActivityMessageId`) and
 * must not fall back to `lastMessageId` or `parentMessageId`, both of which can describe
 * the parent/origin rather than a thread reply.
 */
internal fun InboxItem.resolveInboxAnchorMessageId(): MessageId? =
    when (kind) {
        InboxItemKind.Thread ->
            if (unreadCount > 0) {
                firstUnreadMessageId ?: latestActivityMessageId
            } else {
                latestActivityMessageId
            }
        InboxItemKind.MentionAction -> lastMessageId
        InboxItemKind.DirectMessage,
        InboxItemKind.Channel,
        InboxItemKind.System ->
            if (unreadCount > 0) {
                firstUnreadMessageId ?: lastMessageId
            } else {
                lastMessageId
            }
    }

/**
 * User-initiated row open (Activity/Search/Saved). The resolver is the ONLY exit — the
 * caller must supply the real server id and conversation directory, and must handle
 * [ConversationOpenResolution.Pending] as an explainable failure (loud log + toast, no
 * navigation). The previous Pending fallback rebuilt an identity directly, bypassing the
 * resolver's parent-kind qualification and misrouting null-parent-kind threads onto the
 * channel surface (#415, t5/#419 A3/A4); it is intentionally gone.
 */
internal fun InboxItem.toHomeMessageOpenRequest(
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState,
    diagnosticsLogger: (String) -> Unit = {}
): ConversationOpenResolution =
    resolveOpenRequest(
        serverId = serverId,
        sourceMessageIdentity = toConversationOpenSourceMessageIdentity(),
        conversationDirectory = conversationDirectory,
        diagnosticsLogger = diagnosticsLogger
    )

private fun InboxItem.launchConversationKind(): ConversationKind =
    when (kind) {
        InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
        InboxItemKind.Thread -> ConversationKind.Thread
        // Product default: user-initiated MentionAction opens the target as a channel,
        // this is not a parser fallback for unknown route kind strings.
        InboxItemKind.MentionAction -> channelKind ?: ConversationKind.Channel
        InboxItemKind.System -> ConversationKind.System
        InboxItemKind.Channel -> parentChannelKind ?: ConversationKind.Channel
    }
