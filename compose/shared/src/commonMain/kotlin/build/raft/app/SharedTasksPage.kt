package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.gesture.rememberRaftBackSwipeScroll
import androidx.compose.ui.draw.clip
import build.raft.app.components.raftCardSurface
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.RaftAnchorPlacement
import build.raft.app.components.RaftAnchoredMetaChip
import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.markdown.RaftTaskStatusPixel
import build.raft.app.markdown.RaftTaskStatusPixelStyle
import build.raft.app.markdown.raftTaskStatusColor
import build.raft.app.markdown.raftTaskStatusSoft
import build.raft.app.components.RaftAnchoredPickerTrigger
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftToolbarChip
import build.raft.app.data.KeyValueTasksViewPreferenceRepository
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationIntent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.TasksVisibleContent
import build.raft.app.data.TasksViewMode
import build.raft.app.data.hasCompletedTasksSnapshot
import build.raft.app.data.boardColumns
import build.raft.app.data.isCreatingTasksFor
import build.raft.app.data.reduce
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.tasksAssigneeDisplayName
import build.raft.app.data.tasksAvailableAssigneeFilterIds
import build.raft.app.data.tasksDisplayTitle
import build.raft.app.data.tasksIdentityFilterId
import build.raft.app.data.tasksSourceLabel
import build.raft.app.data.visibleContent
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftStorePullToRefreshBox
import build.raft.app.components.rememberRaftStorePullRefreshState
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.flow.distinctUntilChanged

private const val TasksListMaxBeyondBoundsItemCount = 4

@Composable
internal fun SharedTasksScreen(
    state: TasksUiState,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    scrollToTopRequest: Int = 0,
    modifier: Modifier = Modifier,
    onOpenTask: ((TaskSummary) -> Unit)? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (AppShellLazyListPosition) -> Unit = {},
    identityOptions: List<MemberProfile> = emptyList(),
    timeContext: RaftTimeContext? = null
) {
    SharedTasksPanel(
        state = state,
        onIntent = onIntent,
        scrollToTopRequest = scrollToTopRequest,
        modifier = modifier,
        showHeader = true,
        showChannelFilter = true,
        onOpenTask = onOpenTask,
        bottomContentPadding = bottomContentPadding,
        draftProjection = draftProjection,
        initialListPosition = initialListPosition,
        onListPositionSnapshot = onListPositionSnapshot,
        identityOptions = identityOptions,
        timeContext = timeContext
    )
}

@Composable
internal fun SharedTasksPanel(
    state: TasksUiState,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    scrollToTopRequest: Int = 0,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    showChannelFilter: Boolean = true,
    /** When non-null, render the New Task button and dispatch [SharedTasksRouteIntent.CreateTasks] against this channel.
     *  Server-mode (no implicit channel) leaves this null and the button is hidden. */
    createTasksChannelId: ChannelId? = null,
    onOpenTask: ((TaskSummary) -> Unit)? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (AppShellLazyListPosition) -> Unit = {},
    /** Fixture hook (same pattern as SharedHomeSearchPanel.initialOpenFilterId): when
     *  non-null, the loaded body starts from this presentation state instead of
     *  [TasksPresentationState.Default] + the persisted view-mode preference. The
     *  components.tasks.panel.states visual case starts in List mode with Done expanded,
     *  mirroring the React capture's "Show Done" click (task #330/#353). */
    initialPresentationOverride: TasksPresentationState? = null,
    identityOptions: List<MemberProfile> = emptyList(),
    timeContext: RaftTimeContext? = null
) {
    var lastContent by remember { mutableStateOf<TasksContent?>(null) }
    val currentContent = (state.loadState as? ScreenLoadState.Content)?.data
    if (currentContent != null) {
        lastContent = currentContent
    }
    val displayState =
        if (state.refreshing && lastContent != null) {
            ScreenLoadState.Content(lastContent!!)
        } else {
            state.loadState
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RaftColor.SurfaceWhite)
    ) {
        when (val loadState = displayState) {
            ScreenLoadState.Loading -> {
                if (showHeader) {
                    SharedTasksHeader(refreshing = state.refreshing)
                }
                SharedTasksStateBody(
                    title = stringResource(Res.string.common_loading),
                    detail = "",
                    loading = true
                )
            }
            is ScreenLoadState.Content ->
                SharedTasksLoadedBody(
                    state = state,
                    content = loadState.data,
                    showHeader = showHeader,
                    showChannelFilter = showChannelFilter,
                    createTasksChannelId = createTasksChannelId,
                    onOpenTask = onOpenTask,
                    onIntent = onIntent,
                    scrollToTopRequest = scrollToTopRequest,
                    bottomContentPadding = bottomContentPadding,
                    draftProjection = draftProjection,
                    initialListPosition = initialListPosition,
                    onListPositionSnapshot = onListPositionSnapshot,
                    initialPresentationOverride = initialPresentationOverride,
                    identityOptions = identityOptions,
                    timeContext = timeContext
                )
            is ScreenLoadState.Empty -> {
                val showEmptyToolbar = sharedTasksEmptyToolbarVisible(createTasksChannelId)
                if (showEmptyToolbar) {
                    // Reuse the loaded body instead of maintaining a second toolbar
                    // implementation: empty channel mode keeps the same live identity
                    // popovers, whole-chip hit targets, create dialog, and prune rules.
                    SharedTasksLoadedBody(
                        state = state,
                        content = TasksContent(summaryCounts = emptyMap(), tasks = emptyList()),
                        showHeader = showHeader,
                        showChannelFilter = showChannelFilter,
                        createTasksChannelId = createTasksChannelId,
                        onOpenTask = onOpenTask,
                        onIntent = onIntent,
                        scrollToTopRequest = scrollToTopRequest,
                        bottomContentPadding = bottomContentPadding,
                        draftProjection = draftProjection,
                        initialListPosition = initialListPosition,
                        onListPositionSnapshot = onListPositionSnapshot,
                        initialPresentationOverride =
                            initialPresentationOverride
                                ?: TasksPresentationState.Default.copy(selectedMode = TasksViewMode.List),
                        identityOptions = identityOptions,
                        timeContext = timeContext
                    )
                } else {
                    if (showHeader) {
                        SharedTasksHeader(refreshing = state.refreshing)
                    }
                    val emptyCopy =
                        localizedSharedTasksEmptyStateCopy(
                            hasAnyFilter = state.selectedFilter != null,
                            isChannelMode = false
                        )
                    SharedTasksStateBody(
                        title = emptyCopy.title,
                        detail = emptyCopy.description,
                        iconName = RaftLucideIconName.SquareCheckBig
                    )
                }
            }
            is ScreenLoadState.Error -> {
                if (showHeader) {
                    SharedTasksHeader(refreshing = state.refreshing)
                }
                SharedTasksStateBody(
                    title = stringResource(Res.string.tasks_error_load),
                    detail = loadState.error.code,
                    actionLabel = stringResource(Res.string.common_retry),
                    onAction = { onIntent(SharedTasksRouteIntent.Refresh) }
                )
            }
            is ScreenLoadState.NoPermission -> {
                if (showHeader) {
                    SharedTasksHeader(refreshing = state.refreshing)
                }
                SharedTasksStateBody(
                    title = stringResource(Res.string.tasks_unavailable),
                    detail = loadState.blocker.requiredCapability ?: loadState.blocker.code
                )
            }
        }
    }
}

@Composable
private fun SharedTasksHeader(
    subtitle: String? = null,
    refreshing: Boolean = false
) {
    RaftMainTabPageHeader(
        title = stringResource(Res.string.tasks_title),
        subtitle = subtitle ?: stringResource(Res.string.tasks_header_channel_tasks),
        refreshing = refreshing
    )
}

@Composable
private fun localizedTasksHeaderSubtitle(totalTasks: Int, visibleTasks: Int = totalTasks): String {
    val safeTotal = totalTasks.coerceAtLeast(0)
    val safeVisible = visibleTasks.coerceIn(0, safeTotal)
    return if (safeVisible != safeTotal) {
        stringResource(Res.string.tasks_header_channel_tasks_filtered, safeVisible, safeTotal)
    } else {
        stringResource(Res.string.tasks_header_channel_tasks_count, safeTotal)
    }
}

internal fun tasksHeaderSubtitle(totalTasks: Int, visibleTasks: Int = totalTasks): String {
    val safeTotal = totalTasks.coerceAtLeast(0)
    val safeVisible = visibleTasks.coerceIn(0, safeTotal)
    return if (safeVisible != safeTotal) {
        "$safeVisible of $safeTotal channel tasks"
    } else {
        "$safeTotal channel tasks"
    }
}

internal data class SharedTasksEmptyStateCopy(
    val title: String,
    val description: String
)

internal fun sharedTasksEmptyStateCopy(
    hasAnyFilter: Boolean,
    isChannelMode: Boolean
): SharedTasksEmptyStateCopy =
    when {
        hasAnyFilter ->
            SharedTasksEmptyStateCopy(
                title = "No tasks match this filter",
                description = "Try a different combination or clear the current selection."
            )
        isChannelMode ->
            SharedTasksEmptyStateCopy(
                title = "No tasks yet",
                description = "Create one with the New Task button."
            )
        else ->
            SharedTasksEmptyStateCopy(
                title = "No tasks yet",
                description = "DM and agent task boards are intentionally excluded from this server-wide view."
            )
    }

@Composable
private fun localizedSharedTasksEmptyStateCopy(
    hasAnyFilter: Boolean,
    isChannelMode: Boolean
): SharedTasksEmptyStateCopy =
    when {
        hasAnyFilter ->
            SharedTasksEmptyStateCopy(
                title = stringResource(Res.string.tasks_empty_filtered),
                description = stringResource(Res.string.tasks_empty_filtered_detail)
            )
        isChannelMode ->
            SharedTasksEmptyStateCopy(
                title = stringResource(Res.string.tasks_empty),
                description = stringResource(Res.string.tasks_create_hint)
            )
        else ->
            SharedTasksEmptyStateCopy(
                title = stringResource(Res.string.tasks_empty),
                description = stringResource(Res.string.tasks_empty_server_detail)
            )
    }

internal fun sharedTasksEmptyToolbarVisible(createTasksChannelId: ChannelId?): Boolean =
    createTasksChannelId != null

private fun TasksVisibleContent.hasAnyPresentationFilter(): Boolean =
    selectedChannelNames.isNotEmpty() ||
        selectedCreatorIds.isNotEmpty() ||
        selectedAssigneeIds.isNotEmpty()

@Composable
private fun SharedTasksLoadedBody(
    state: TasksUiState,
    content: TasksContent,
    showHeader: Boolean,
    showChannelFilter: Boolean,
    createTasksChannelId: ChannelId?,
    onOpenTask: ((TaskSummary) -> Unit)?,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    scrollToTopRequest: Int,
    bottomContentPadding: Dp,
    draftProjection: SharedThreadComposerDraftProjection,
    initialListPosition: AppShellLazyListPosition,
    onListPositionSnapshot: (AppShellLazyListPosition) -> Unit,
    initialPresentationOverride: TasksPresentationState?,
    identityOptions: List<MemberProfile>,
    timeContext: RaftTimeContext?
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        SharedTasksLoadedBodyContent(
            state = state,
            content = content,
            showHeader = showHeader,
            showChannelFilter = showChannelFilter,
            createTasksChannelId = createTasksChannelId,
            onOpenTask = onOpenTask,
            onIntent = onIntent,
            scrollToTopRequest = scrollToTopRequest,
            defaultViewMode = defaultTasksViewMode(maxWidth),
            bottomContentPadding = bottomContentPadding,
            draftProjection = draftProjection,
            initialListPosition = initialListPosition,
            onListPositionSnapshot = onListPositionSnapshot,
            initialPresentationOverride = initialPresentationOverride,
            identityOptions = identityOptions,
            timeContext = timeContext
        )
    }
}

/**
 * Seed value for a filter-search caller-local editor at a session open, or null to
 * leave the editor untouched. The filter search inputs are the sole owners of their
 * [TextFieldValue] within a session; this is invoked only from a LaunchedEffect
 * keyed on the `openFilterId` session token, so a filter is reseeded from its
 * durable String only when its popover becomes the open session (SetOpenFilter
 * clears the non-active boxes). Returning null for every non-active filter is what
 * stops a stale or echoed durable String from re-injecting into a live editor and
 * clobbering its selection/composition. The seed always places the caret at the end
 * of the durable text; within the session the local editor owns the selection.
 */
internal fun raftFilterSearchSessionSeed(
    openFilterId: String?,
    thisFilterId: String,
    durableSearch: String
): TextFieldValue? =
    if (openFilterId == thisFilterId) {
        TextFieldValue(durableSearch, selection = TextRange(durableSearch.length))
    } else {
        null
    }

@Composable
private fun SharedTasksLoadedBodyContent(
    state: TasksUiState,
    content: TasksContent,
    showHeader: Boolean,
    showChannelFilter: Boolean,
    createTasksChannelId: ChannelId?,
    onOpenTask: ((TaskSummary) -> Unit)?,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    scrollToTopRequest: Int,
    defaultViewMode: TasksViewMode,
    bottomContentPadding: Dp,
    draftProjection: SharedThreadComposerDraftProjection,
    initialListPosition: AppShellLazyListPosition,
    onListPositionSnapshot: (AppShellLazyListPosition) -> Unit,
    initialPresentationOverride: TasksPresentationState?,
    identityOptions: List<MemberProfile>,
    timeContext: RaftTimeContext?
) {
    val pager = LocalActivity.current.getPager() as Pager
    val viewModeRepository =
        remember(pager) {
            KeyValueTasksViewPreferenceRepository(raftSharedPreferenceStore())
        }
    val initialPresentationState =
        remember(viewModeRepository, defaultViewMode) {
            initialPresentationOverride
                ?: TasksPresentationState.Default.copy(
                    selectedMode = viewModeRepository.getTasksViewModeSync(defaultViewMode)
                )
        }
    var presentationState by remember(viewModeRepository) { mutableStateOf(initialPresentationState) }
    val sortedContent = remember(content) {
        content.copy(tasks = content.tasks.sortedByTaskDisplayOrder())
    }
    val visibleContent = remember(sortedContent, state.selectedFilter, presentationState, identityOptions) {
        sortedContent.visibleContent(
            selectedFilter = state.selectedFilter,
            presentationState = presentationState,
            showChannelFilter = showChannelFilter,
            identityOptions = identityOptions
        )
    }
    val availableChannelIds = remember(visibleContent.channelOptions) {
        visibleContent.channelOptions.toSet()
    }
    val availableCreatorIds = remember(visibleContent.creatorOptions) {
        visibleContent.creatorOptions.map { it.tasksIdentityFilterId() }.toSet()
    }
    val availableAssigneeIds = remember(visibleContent.assigneeOptions) {
        tasksAvailableAssigneeFilterIds(visibleContent.assigneeOptions)
    }
    // Drop stale chip selections when the underlying option pool changes (e.g. a server
    // refresh removed a channel that was checked in the popover).
    LaunchedEffect(availableChannelIds) {
        val next =
            presentationState.reduce(
                TasksPresentationIntent.PruneUnavailableChannels(availableChannelIds)
            )
        if (next != presentationState) presentationState = next
    }
    LaunchedEffect(availableCreatorIds) {
        val next =
            presentationState.reduce(
                TasksPresentationIntent.PruneUnavailableCreators(availableCreatorIds)
            )
        if (next != presentationState) presentationState = next
    }
    LaunchedEffect(availableAssigneeIds) {
        val next =
            presentationState.reduce(
                TasksPresentationIntent.PruneUnavailableAssignees(availableAssigneeIds)
            )
        if (next != presentationState) presentationState = next
    }

    // boardColumns always covers every TaskStatus so the user can see empty columns
    // (you can't drop a task into a column that doesn't exist). renderLimit = 0 means
    // "include all tasks per column" — pagination would be a separate feature.
    val boardColumns = remember(visibleContent.filteredContent) {
        visibleContent.filteredContent.boardColumns(renderLimit = 0)
    }
    // For list mode we hide totally-empty status groups; otherwise the screen looks
    // like a wall of "No X tasks" headers.
    val listColumns = remember(boardColumns) { boardColumns.filter { it.totalCount > 0 } }
    val collapsedStatuses = presentationState.collapsedStatuses

    // The status chip provides a root-space BottomEnd anchor. Keep that exact trigger
    // coordinate after a LazyColumn scroll; reconstructing it from item layout offsets
    // moves the menu away from the chip and can leave the menu under the touch barrier.
    var statusMenuTask by remember { mutableStateOf<TaskSummary?>(null) }
    var statusMenuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var assigneeMenuTask by remember { mutableStateOf<TaskSummary?>(null) }
    var assigneeMenuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var assigneeMenuSearch by remember { mutableStateOf("") }
    val listModeListState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialListPosition.firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = initialListPosition.firstVisibleItemScrollOffset
        )
    val listScrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (
            listScrollToTopRequestFence.consume(scrollToTopRequest) &&
                presentationState.selectedMode == TasksViewMode.List
        ) {
            listModeListState.animateScrollToTopExpressive()
        }
    }
    val latestOnListPositionSnapshot by rememberUpdatedState(onListPositionSnapshot)
    DisposableEffect(listModeListState) {
        onDispose { latestOnListPositionSnapshot(listModeListState.toAppShellLazyListPosition()) }
    }

    // Pagination sentinel (task #345): when the list-mode viewport scrolls near
    // the end and another page is available, ask the host for it. Keyed on the
    // list state and the current page's total/loading flags so it re-arms after
    // each page lands; `shouldRequestNextTasksPage` is pure and unit-tested.
    LaunchedEffect(listModeListState, listColumns, state.hasMore, state.loadingMore) {
        val totalItems = listColumns.sumOf { if (it.status in collapsedStatuses) 0 else it.tasks.size }
        snapshotFlow { listModeListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (
                    shouldRequestNextTasksPage(
                        totalItems = totalItems,
                        lastVisibleIndex = lastVisibleIndex,
                        hasMore = state.hasMore,
                        loadingMore = state.loadingMore
                    )
                ) {
                    onIntent(SharedTasksRouteIntent.LoadMore)
                }
            }
    }

    // Filter popover anchors. Resolved from each chip's live coordinates when opened.
    // Caller-local editing snapshots for the filter search inputs. The durable
    // filter String in presentationState is a downstream projection; the
    // caller-local TextFieldValue is the SOLE mounted editor owner for a filter
    // session, so CJK composition and cursor/selection are never disturbed by the
    // reducer. Typing projects only `.text` back into the store.
    var channelSearchValue by remember {
        mutableStateOf(
            TextFieldValue(presentationState.channelSearch, selection = TextRange(presentationState.channelSearch.length))
        )
    }
    var creatorSearchValue by remember {
        mutableStateOf(
            TextFieldValue(presentationState.creatorSearch, selection = TextRange(presentationState.creatorSearch.length))
        )
    }
    var assigneeSearchValue by remember {
        mutableStateOf(
            TextFieldValue(presentationState.assigneeSearch, selection = TextRange(presentationState.assigneeSearch.length))
        )
    }
    // Seed the caller-local editor exactly once per filter session, keyed on the
    // explicit session token `openFilterId`. SetOpenFilter opens/swaps a popover
    // and clears the non-active search boxes, so a (re)opened box must start from
    // the durable String rather than stale local text. Keying on openFilterId —
    // never on the durable String — means normal typing, reducer projection echo,
    // out-of-order/stale durable Strings and same-session recompositions never
    // write back local selection/composition. The only other local mutations are
    // the field's own edits (typing / its X clear) via onSearchChange. Non-active
    // filters are reseeded when they next become the open session.
    LaunchedEffect(presentationState.openFilterId) {
        val openId = presentationState.openFilterId
        raftFilterSearchSessionSeed(openId, "channel", presentationState.channelSearch)?.let { channelSearchValue = it }
        raftFilterSearchSessionSeed(openId, "creator", presentationState.creatorSearch)?.let { creatorSearchValue = it }
        raftFilterSearchSessionSeed(openId, "assignee", presentationState.assigneeSearch)?.let { assigneeSearchValue = it }
    }
    var createDialogOpen by remember { mutableStateOf(false) }
    var createSubmitted by remember { mutableStateOf(false) }
    var createFailureVisible by remember { mutableStateOf(false) }
    var createBaselineAttempt by remember { mutableStateOf(state.createAttempt) }
    val createLoading =
        createSubmitted || createTasksChannelId?.let { state.isCreatingTasksFor(it) } == true
    LaunchedEffect(
        createDialogOpen,
        createSubmitted,
        createBaselineAttempt,
        state.createTerminalAttempt,
        state.createErrorCode
    ) {
        val terminalAttempt = state.createTerminalAttempt ?: return@LaunchedEffect
        if (!createDialogOpen || !createSubmitted || terminalAttempt <= createBaselineAttempt) {
            return@LaunchedEffect
        }
        if (state.createErrorCode == null) {
            createDialogOpen = false
            createFailureVisible = false
        } else {
            createFailureVisible = true
        }
        createSubmitted = false
    }
    val channelFilterOptions = remember(visibleContent.channelOptions) {
        visibleContent.channelOptions.map { name -> RaftFilterOption(id = name, label = name, icon = RaftLucideIconName.Hash) }
    }
    val creatorFilterOptions = remember(visibleContent.creatorOptions) {
        visibleContent.creatorOptions.map { RaftFilterOption(id = it.tasksIdentityFilterId(), label = it.displayName, icon = RaftLucideIconName.CircleUser) }
    }
    val unassignedAssigneeLabel = stringResource(Res.string.tasks_assignee_unassigned)
    val assigneeFilterOptions = remember(visibleContent.assigneeOptions, unassignedAssigneeLabel) {
        tasksAssigneeFilterOptions(visibleContent.assigneeOptions, unassignedAssigneeLabel)
    }
    RaftBackHandler(
        enabled = createDialogOpen || statusMenuTask != null || assigneeMenuTask != null || presentationState.openFilterId != null
    ) {
        when {
            createDialogOpen && !createLoading -> createDialogOpen = false
            createDialogOpen -> Unit
            statusMenuTask != null -> statusMenuTask = null
            assigneeMenuTask != null -> {
                assigneeMenuTask = null
                assigneeMenuSearch = ""
            }
            presentationState.openFilterId != null ->
                presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null))
        }
    }

    // artin task #22: a pulled refresh shows the pull indicator only, not the header spinner too.
    val tasksPullState = rememberRaftStorePullRefreshState()
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        if (showHeader) {
            SharedTasksHeader(
                subtitle =
                    localizedTasksHeaderSubtitle(
                        totalTasks = content.tasks.size,
                        visibleTasks = visibleContent.filteredTasks.size
                    ),
                refreshing = tasksPullState.headerRefreshing(state.refreshing)
            )
        }
        Box(modifier = Modifier.weight(1f).background(RaftColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                state.navigationErrorCode?.let { code ->
                    SharedTasksNotice(stringResource(Res.string.tasks_error_action_unavailable), code, modifier = Modifier.padding(RaftAppSpacing.Md))
                }
                state.statusUpdateErrorCode?.let { code ->
                    SharedTasksNotice(stringResource(Res.string.tasks_error_update_status), code, modifier = Modifier.padding(RaftAppSpacing.Md))
                }
                SharedTasksToolbar(
                    channelOptions = visibleContent.channelOptions,
                    selectedChannels = visibleContent.selectedChannelNames,
                    selectedCreatorIds = visibleContent.selectedCreatorIds,
                    selectedAssigneeIds = visibleContent.selectedAssigneeIds,
                    selectedMode = presentationState.selectedMode,
                    showChannelFilter = showChannelFilter,
                    openFilterId = presentationState.openFilterId,
                    showCreateButton = createTasksChannelId != null,
                    onModeSelected = { mode ->
                        viewModeRepository.selectTasksViewModeSync(mode)
                        presentationState = presentationState.reduce(TasksPresentationIntent.SelectMode(mode))
                    },
                    onChannelChipClick = { _ ->
                        presentationState =
                            presentationState.reduce(
                                TasksPresentationIntent.SetOpenFilter(
                                    if (presentationState.openFilterId == "channel") null else "channel"
                                )
                            )
                    },
                    onCreatorChipClick = { _ ->
                        presentationState =
                            presentationState.reduce(
                                TasksPresentationIntent.SetOpenFilter(
                                    if (presentationState.openFilterId == "creator") null else "creator"
                                )
                            )
                    },
                    onAssigneeChipClick = { _ ->
                        presentationState =
                            presentationState.reduce(
                                TasksPresentationIntent.SetOpenFilter(
                                    if (presentationState.openFilterId == "assignee") null else "assignee"
                                )
                            )
                    },
                    onClearAll = {
                        presentationState = presentationState.reduce(TasksPresentationIntent.ClearAllFilters)
                    },
                    onCreateClick = {
                        createBaselineAttempt = state.createAttempt
                        createSubmitted = false
                        createFailureVisible = false
                        createDialogOpen = true
                    }
                )
                // Task #363: the per-status Board seeds each column's first page
                // (its rows AND cursor) once it becomes the active view. List mode
                // never dispatches this, so the global walk issues no extra
                // requests and the #345 path stays byte-identical.
                LaunchedEffect(presentationState.selectedMode, state.hasCompletedTasksSnapshot()) {
                    if (presentationState.selectedMode == TasksViewMode.Board &&
                        state.hasCompletedTasksSnapshot()
                    ) {
                        onIntent(SharedTasksRouteIntent.EnterBoard)
                    }
                }
                // Pull-to-refresh over both views (board columns scroll vertically inside the
                // horizontal board, so their overscroll reaches this box through the LazyRow).
                RaftStorePullToRefreshBox(
                    refreshing = state.refreshing,
                    onRefresh = { onIntent(SharedTasksRouteIntent.Refresh) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    pullState = tasksPullState,
                ) {
                when (presentationState.selectedMode) {
                    TasksViewMode.Board -> {
                        // Horizontal board: each TaskStatus is a fixed-width column the user can
                        // scroll between left/right. Mirrors the React `overflow-x-auto` panel.
                        val backSwipeRowState = rememberLazyListState()
                        val backSwipeRow = rememberRaftBackSwipeScroll(backSwipeRowState)
                        LazyRow(
                            state = backSwipeRowState,
                            userScrollEnabled = backSwipeRow.userScrollEnabled,
                            // The board's columns are cards: they keep out of the safe area.
                            modifier = Modifier.then(backSwipeRow.modifier).fillMaxSize().raftSafeAreaHorizontalPadding(),
                            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
                            contentPadding = PaddingValues(RaftAppSpacing.Md)
                        ) {
                            items(boardColumns.size, key = { i -> "board-col-${boardColumns[i].status}" }) { index ->
                                val column = boardColumns[index]
                                SharedTasksBoardColumn(
                                    status = column.status,
                                    totalCount = column.totalCount,
                                    tasks = column.tasks,
                                    collapsed = column.status in collapsedStatuses,
                                    onToggleCollapsed = {
                                        presentationState =
                                            presentationState.reduce(
                                                TasksPresentationIntent.ToggleStatusCollapsed(column.status)
                                            )
                                    },
                                    onOpenTask = onOpenTask,
                                    timeContext = timeContext,
                                    onStatusBadgeClick = { task, anchor ->
                                        statusMenuTask = task
                                        statusMenuAnchor = anchor
                                    },
                                    onAssigneeBadgeClick = { task, anchor ->
                                        assigneeMenuTask = task
                                        assigneeMenuAnchor = anchor
                                    },
                                    onLongPressTask = { task, anchor ->
                                        statusMenuTask = task
                                        statusMenuAnchor = anchor
                                    },
                                    onReachedEnd = { columnStatus ->
                                        // task #363: per-status pagination is the
                                        // ONLY walk for a Board column. A column
                                        // advances its own cursor (single-flight
                                        // per status); an exhausted column is a
                                        // no-op. It never falls back to the global
                                        // cursor — that would consume another
                                        // column's page (@LoveArk). Every column
                                        // is seeded, including empty ones, so a
                                        // missing entry means "not seeded yet"
                                        // (no-op), never "use the global walk".
                                        val page = state.statusPages[columnStatus]
                                        if (page != null && page.hasMore && !page.loadingMore) {
                                            onIntent(SharedTasksRouteIntent.LoadMoreStatus(columnStatus))
                                        }
                                    },
                                    bottomContentPadding = bottomContentPadding,
                                    draftProjection = draftProjection,
                                    scrollToTopRequest = scrollToTopRequest,
                                )
                            }
                        }
                    }
                    TasksViewMode.List -> {
                        if (visibleContent.filteredTasks.isEmpty()) {
                            val emptyCopy =
                                localizedSharedTasksEmptyStateCopy(
                                    hasAnyFilter = state.selectedFilter != null || visibleContent.hasAnyPresentationFilter(),
                                    isChannelMode = createTasksChannelId != null
                                )
                            SharedTasksStateBody(
                                title = emptyCopy.title,
                                detail = emptyCopy.description,
                                iconName = RaftLucideIconName.SquareCheckBig
                            )
                        } else {
                            LazyColumn(
                                state = listModeListState,
                                // Task cards on the canvas: the list keeps out of the safe area.
                                modifier = Modifier.fillMaxSize().raftSafeAreaHorizontalPadding(),
                                contentPadding =
                                    PaddingValues(
                                        start = RaftAppSpacing.Md,
                                        top = RaftAppSpacing.Md,
                                        end = RaftAppSpacing.Md,
                                        bottom = bottomContentPadding
                                    ),
                            ) {
                                listColumns.forEachIndexed { columnIndex, column ->
                                    item(key = "list-header-${column.status.name}") {
                                        // web TasksPanel list: sections `stack-y-6`, each section
                                        // `stack-y-2.5` (header, then cards 10dp apart).
                                        BoardSectionHeader(
                                            modifier =
                                                Modifier.padding(
                                                    top = if (columnIndex == 0) 0.dp else SharedTasksListSectionGap
                                                ),
                                            status = column.status,
                                            count = column.totalCount,
                                            collapsed = column.status in collapsedStatuses,
                                            onClick = {
                                                presentationState =
                                                    presentationState.reduce(
                                                        TasksPresentationIntent.ToggleStatusCollapsed(column.status)
                                                    )
                                            }
                                        )
                                    }
                                    if (column.status !in collapsedStatuses) {
                                        items(
                                            column.tasks.size,
                                            key = { index -> "list-card-${column.status.name}-${column.tasks[index].id.value}" }
                                        ) { index ->
                                            val task = column.tasks[index]
                                            SharedTaskCard(
                                                modifier = Modifier.padding(top = SharedTasksListItemGap),
                                                task = task,
                                                hasDraft = draftProjection.hasDraftForTask(task),
                                                captureLongPressAnchor = !listModeListState.isScrollInProgress,
                                                timeContext = timeContext,
                                                onClick = { onOpenTask?.invoke(task) },
                                                onStatusBadgeClick = { anchor ->
                                                    statusMenuTask = task
                                                    statusMenuAnchor = anchor
                                                },
                                                onAssigneeBadgeClick = { anchor ->
                                                    assigneeMenuTask = task
                                                    assigneeMenuAnchor = anchor
                                                },
                                                onLongPress = { anchor ->
                                                    statusMenuTask = task
                                                    statusMenuAnchor = anchor
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }

        // --- Overlays. Rendered in z-order: popovers, then status menu, then create dialog.
        // Each overlay short-circuits on null state so unmounting tears down the gesture
        // capture overlay too (otherwise the dim layer would swallow taps).
        when (presentationState.openFilterId) {
            "channel" ->
                RaftFilterSheet(
                    title = stringResource(Res.string.tasks_filter_channel),
                    searchPlaceholder = stringResource(Res.string.tasks_search_channels),
                    searchText = channelSearchValue.text,
                    onSearchChange = { channelSearchValue = TextFieldValue(it); presentationState = presentationState.reduce(TasksPresentationIntent.SetChannelSearch(it)) },
                    options = channelFilterOptions,
                    selectedIds = visibleContent.selectedChannelNames,
                    onToggle = { id -> presentationState = presentationState.reduce(TasksPresentationIntent.ToggleChannel(id)) },
                    onClear = { presentationState = presentationState.reduce(TasksPresentationIntent.ClearChannels) },
                    onDismiss = { presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null)) }
                )
            "creator" ->
                RaftFilterSheet(
                    title = stringResource(Res.string.tasks_filter_creator),
                    searchPlaceholder = stringResource(Res.string.tasks_search_creators),
                    searchText = creatorSearchValue.text,
                    onSearchChange = { creatorSearchValue = TextFieldValue(it); presentationState = presentationState.reduce(TasksPresentationIntent.SetCreatorSearch(it)) },
                    options = creatorFilterOptions,
                    selectedIds = visibleContent.selectedCreatorIds,
                    onToggle = { id -> presentationState = presentationState.reduce(TasksPresentationIntent.ToggleCreator(id)) },
                    onClear = { presentationState = presentationState.reduce(TasksPresentationIntent.ClearCreators) },
                    onDismiss = { presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null)) }
                )
            "assignee" ->
                RaftFilterSheet(
                    title = stringResource(Res.string.tasks_filter_assignee),
                    searchPlaceholder = stringResource(Res.string.tasks_search_assignees),
                    searchText = assigneeSearchValue.text,
                    onSearchChange = { assigneeSearchValue = TextFieldValue(it); presentationState = presentationState.reduce(TasksPresentationIntent.SetAssigneeSearch(it)) },
                    options = assigneeFilterOptions,
                    selectedIds = visibleContent.selectedAssigneeIds,
                    onToggle = { id -> presentationState = presentationState.reduce(TasksPresentationIntent.ToggleAssignee(id)) },
                    onClear = { presentationState = presentationState.reduce(TasksPresentationIntent.ClearAssignees) },
                    onDismiss = { presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null)) }
                )
            else -> Unit
        }

        // Read-only tasks (e.g. historical joint tasks) never open mutation menus, like web; nor does the
        // stored offline stand-in before the network confirmed it.
        statusMenuTask?.takeIf { it.readOnlyReason == null && !state.standIn }?.let { task ->
            val updating = state.statusUpdatingTaskId == task.id.value
            val resolvedStatusAnchor =
                statusMenuAnchor?.takeIf { it.belongsTo(tasksStatusMenuOwnerIdentity(task.id.value)) }
                    ?: return@let
            // artin msg=2736fb22: task-tab status click keeps the OLD anchored
            // menu interaction (Todo / In progress / …) — no modal.
            RaftTaskStatusMenuOverlay(
                anchor = resolvedStatusAnchor,
                items = TaskStatus.values().map { status ->
                    RaftBaseMenuItem(id = status.name, label = status.localizedLabel())
                },
                selectedId = task.status.name,
                onDismiss = { statusMenuTask = null },
                onSelect = { selectedId ->
                    statusMenuTask = null
                    val next = TaskStatus.values().firstOrNull { it.name == selectedId }
                    if (!updating && next != null && next != task.status) {
                        onIntent(SharedTasksRouteIntent.UpdateStatus(task, next))
                    }
                }
            )
        }

        // Assignee editor (task v2 port, task #244). Per the web ruling the assignee
        // picker reuses the searchable filter sheet (stdrc msg=4c357cb1) — humans
        // first, then agents, "unassigned" sentinel always first and never filtered
        // away. Matching is on the visible display name, mirroring TaskProperties.tsx.
        assigneeMenuTask?.takeIf { it.readOnlyReason == null && !state.standIn }?.let { task ->
            // The picker opens for the task whose assignee chip was tapped.
            assigneeMenuAnchor?.takeIf { it.belongsTo(tasksStatusMenuOwnerIdentity(task.id.value)) }
                ?: return@let
            val unassignedLabel = stringResource(Res.string.tasks_assignee_unassigned)
            val needle = assigneeMenuSearch.trim().lowercase()
            val people =
                visibleContent.assigneeOptions
                    .filter { it.kind != MemberKind.System }
                    .filter { needle.isEmpty() || it.displayName.lowercase().contains(needle) }
                    .sortedWith(compareByDescending<MemberProfile> { it.kind == MemberKind.Human }.thenBy { it.displayName.lowercase() })
            val selectedId = task.assignee?.tasksIdentityFilterId() ?: TasksUnassignedAssigneeFilterId
            RaftFilterSheet(
                title = stringResource(Res.string.tasks_filter_assignee),
                searchPlaceholder = stringResource(Res.string.tasks_search_assignees),
                searchText = assigneeMenuSearch,
                onSearchChange = { assigneeMenuSearch = it },
                options =
                    listOf(
                        RaftFilterOption(
                            id = TasksUnassignedAssigneeFilterId,
                            label = unassignedLabel
                        )
                    ) +
                        people.map { member ->
                            RaftFilterOption(
                                id = member.tasksIdentityFilterId(),
                                label = member.displayName
                            )
                        },
                selectedIds = setOf(selectedId),
                onToggle = { id ->
                    assigneeMenuTask = null
                    assigneeMenuSearch = ""
                    if (id != selectedId) {
                        val member = people.firstOrNull { it.tasksIdentityFilterId() == id }
                        onIntent(SharedTasksRouteIntent.AssignTask(task, member))
                    }
                },
                onClear = { assigneeMenuSearch = "" },
                onDismiss = {
                    assigneeMenuTask = null
                    assigneeMenuSearch = ""
                },
                multiSelect = false,
                showClear = false
            )
        }

        if (createDialogOpen && createTasksChannelId != null && !state.standIn) {
            TasksCreateDialog(
                loading = createLoading,
                errorCode = state.createErrorCode.takeIf { createFailureVisible && !createLoading },
                onDismiss = {
                    if (!createLoading) createDialogOpen = false
                },
                onSubmit = { titles ->
                    if (!createLoading) {
                        createFailureVisible = false
                        createSubmitted = true
                        onIntent(SharedTasksRouteIntent.CreateTasks(createTasksChannelId, titles))
                    }
                }
            )
        }
        }
    }
}

internal fun tasksAssigneeFilterOptions(
    assignees: List<MemberProfile>,
    unassignedLabel: String
): List<RaftFilterOption> =
    listOf(
        RaftFilterOption(
            id = TasksUnassignedAssigneeFilterId,
            label = unassignedLabel,
            icon = RaftLucideIconName.User
        )
    ) + assignees.map {
        RaftFilterOption(
            id = it.tasksIdentityFilterId(),
            label = it.displayName,
            icon = RaftLucideIconName.User
        )
    }

internal fun sharedTasksLazyItemCount(
    mode: TasksViewMode,
    filteredTasksCount: Int,
    boardColumnCount: Int,
    hasNavigationError: Boolean,
    hasStatusUpdateError: Boolean
): Int {
    // Retained for tests. The body no longer uses a single LazyColumn but this pure
    // helper still answers the question "how many top-level items would the loaded
    // body render?" given the same inputs as before.
    val fixedItems = 1 + 1 // toolbar + bottom spacer
    val errorItems = (if (hasNavigationError) 1 else 0) + (if (hasStatusUpdateError) 1 else 0)
    val bodyItems =
        when (mode) {
            TasksViewMode.Board -> boardColumnCount
            TasksViewMode.List -> filteredTasksCount.takeIf { it > 0 } ?: 1
        }
    return fixedItems + errorItems + bodyItems
}

internal fun sharedTasksBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceAtLeast(0).coerceAtMost(TasksListMaxBeyondBoundsItemCount)

/**
 * How close (in items) to the end the list must scroll before the next page is
 * requested (task #345). Small enough to feel seamless, large enough that a
 * fast flick still triggers before the user hits the bottom.
 */
internal const val SharedTasksLoadMoreThreshold = 5

/**
 * Whether scrolling to [lastVisibleIndex] over [totalItems] items should request
 * the next page. Pure so it can be unit-tested: requests when a page is
 * available, not already loading, and the visible window has reached the
 * threshold. A single-item/short list always qualifies (index 0 over <threshold
 * items).
 */
internal fun shouldRequestNextTasksPage(
    totalItems: Int,
    lastVisibleIndex: Int,
    hasMore: Boolean,
    loadingMore: Boolean,
): Boolean {
    if (!hasMore || loadingMore || totalItems <= 0) return false
    return totalItems - 1 - lastVisibleIndex < SharedTasksLoadMoreThreshold
}

private fun defaultTasksViewMode(maxWidth: Dp): TasksViewMode =
    if (maxWidth < 768.dp) TasksViewMode.List else TasksViewMode.Board

@Composable
private fun SharedTasksToolbar(
    channelOptions: List<String>,
    selectedChannels: Set<String>,
    selectedCreatorIds: Set<String>,
    selectedAssigneeIds: Set<String>,
    selectedMode: TasksViewMode,
    showChannelFilter: Boolean,
    openFilterId: String?,
    showCreateButton: Boolean,
    onModeSelected: (TasksViewMode) -> Unit,
    onChannelChipClick: (RaftPopoverAnchor) -> Unit,
    onCreatorChipClick: (RaftPopoverAnchor) -> Unit,
    onAssigneeChipClick: (RaftPopoverAnchor) -> Unit,
    onClearAll: () -> Unit,
    onCreateClick: () -> Unit
) {
    val anyFilterActive = selectedChannels.isNotEmpty() || selectedCreatorIds.isNotEmpty() || selectedAssigneeIds.isNotEmpty()
    val channelLabel = stringResource(Res.string.tasks_chip_channel)
    val creatorLabel = stringResource(Res.string.tasks_chip_creator)
    val assigneeLabel = stringResource(Res.string.tasks_chip_assignee)
    val channelChip: @Composable () -> Unit = {
        RaftAnchoredPickerTrigger(
            label = if (selectedChannels.isEmpty()) channelLabel else "$channelLabel · ${selectedChannels.size}",
            anchorIdentity = "tasks_filter:channel",
            iconName = RaftLucideIconName.Hash,
            hasValue = openFilterId == "channel" || selectedChannels.isNotEmpty(),
            enabled = channelOptions.isNotEmpty(),
            onClick = onChannelChipClick
        )
    }
    val creatorChip: @Composable () -> Unit = {
        RaftAnchoredPickerTrigger(
            label = if (selectedCreatorIds.isEmpty()) creatorLabel else "$creatorLabel · ${selectedCreatorIds.size}",
            anchorIdentity = "tasks_filter:creator",
            iconName = RaftLucideIconName.CircleUser,
            hasValue = openFilterId == "creator" || selectedCreatorIds.isNotEmpty(),
            // Web leaves identity filters interactive while their server directory is
            // loading; the popover can open empty and populate without changing chips.
            enabled = true,
            onClick = onCreatorChipClick
        )
    }
    val assigneeChip: @Composable () -> Unit = {
        RaftAnchoredPickerTrigger(
            label = if (selectedAssigneeIds.isEmpty()) assigneeLabel else "$assigneeLabel · ${selectedAssigneeIds.size}",
            anchorIdentity = "tasks_filter:assignee",
            iconName = RaftLucideIconName.User,
            hasValue = openFilterId == "assignee" || selectedAssigneeIds.isNotEmpty(),
            // Web always exposes the localized Unassigned sentinel, even when the
            // currently loaded task set contains no member identities.
            enabled = true,
            onClick = onAssigneeChipClick
        )
    }
    val clearAllChip: @Composable () -> Unit = {
        RaftToolbarChip(
            label = stringResource(Res.string.tasks_action_clear_all),
            iconName = RaftLucideIconName.X,
            uppercase = false,
            active = false,
            onClick = onClearAll
        )
    }
    val newTaskButton: @Composable () -> Unit = {
        RaftButton(
            text = stringResource(Res.string.tasks_action_new_task),
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Plus) },
            onClick = onCreateClick,
            variant = RaftButtonVariant.Primary
        )
    }
    val viewSwitch: @Composable () -> Unit = {
        SharedTasksSegmentedControl(
            selectedMode = selectedMode,
            onModeSelected = onModeSelected
        )
    }
    val filterControls =
        buildList {
            if (showChannelFilter) add("channel")
            add("creator")
            add("assignee")
            if (anyFilterActive) add("clear")
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.SurfaceWhite)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftSafeAreaHorizontalPadding()
                    .padding(horizontal = RaftAppSpacing.Md, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
        ) {
            val backSwipeRowState = rememberLazyListState()
            val backSwipeRow = rememberRaftBackSwipeScroll(backSwipeRowState)
            LazyRow(
                state = backSwipeRowState,
                userScrollEnabled = backSwipeRow.userScrollEnabled,
                modifier = Modifier.then(backSwipeRow.modifier).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
            ) {
                items(filterControls.size, key = { index -> "tasks-filter-${filterControls[index]}" }) { index ->
                    when (filterControls[index]) {
                        "channel" -> channelChip()
                        "creator" -> creatorChip()
                        "assignee" -> assigneeChip()
                        "clear" -> clearAllChip()
                    }
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                tasksToolbarActionRows(maxWidth = maxWidth, showCreateButton = showCreateButton).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            if (row.size == 1 && row.first() == TasksToolbarActionControl.ViewSwitch) {
                                Arrangement.End
                            } else {
                                Arrangement.spacedBy(RaftAppSpacing.Sm)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        row.forEachIndexed { index, control ->
                            if (control == TasksToolbarActionControl.ViewSwitch && index > 0) {
                                Spacer(Modifier.weight(1f))
                            }
                            when (control) {
                                TasksToolbarActionControl.NewTask -> newTaskButton()
                                TasksToolbarActionControl.ViewSwitch -> viewSwitch()
                            }
                        }
                    }
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftAppStroke.Thin)
                    .background(RaftColor.BorderSeam)
        )
    }
}

internal enum class TasksToolbarActionControl {
    NewTask,
    ViewSwitch
}

internal fun tasksToolbarActionRows(
    maxWidth: Dp,
    showCreateButton: Boolean
): List<List<TasksToolbarActionControl>> =
    if (!showCreateButton) {
        listOf(listOf(TasksToolbarActionControl.ViewSwitch))
    } else if (maxWidth >= 480.dp) {
        listOf(listOf(TasksToolbarActionControl.NewTask, TasksToolbarActionControl.ViewSwitch))
    } else {
        listOf(
            listOf(TasksToolbarActionControl.NewTask),
            listOf(TasksToolbarActionControl.ViewSwitch)
        )
    }

/** Vertical board column. Header is always visible (so collapsed columns can re-expand). */
@Composable
private fun SharedTasksBoardColumn(
    status: TaskStatus,
    totalCount: Int,
    tasks: List<TaskSummary>,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onOpenTask: ((TaskSummary) -> Unit)?,
    timeContext: RaftTimeContext?,
    onStatusBadgeClick: (TaskSummary, RaftPopoverAnchor) -> Unit,
    onAssigneeBadgeClick: (TaskSummary, RaftPopoverAnchor) -> Unit,
    onLongPressTask: (TaskSummary, RaftPopoverAnchor) -> Unit,
    bottomContentPadding: Dp,
    draftProjection: SharedThreadComposerDraftProjection,
    scrollToTopRequest: Int,
    onReachedEnd: (TaskStatus) -> Unit,
) {
    val columnListState = rememberLazyListState()
    val scrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequestFence.consume(scrollToTopRequest) && !collapsed) {
            columnListState.animateScrollToTopExpressive()
        }
    }
    // Board pagination sentinel (#345): the board has no single list state, so
    // each column reports when its own viewport reaches the end. The host
    // guards with the shared single-flight predicate, so only one request is in
    // flight regardless of how many columns fire.
    LaunchedEffect(columnListState, tasks.size, collapsed) {
        if (collapsed) return@LaunchedEffect
        snapshotFlow { columnListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex >= 0 && lastVisibleIndex >= tasks.lastIndex - SharedTasksLoadMoreThreshold) {
                    onReachedEnd(status)
                }
            }
    }
    // fillMaxHeight pins each board column to the LazyRow's height, giving the inner
    // LazyColumn a bounded constraint to scroll within. Without this, the inner list
    // tries to take infinite height and silently drops scrollability.
    Column(
        modifier =
            Modifier
                .width(320.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(RaftAppRadius.Container))
                .background(RaftColor.SurfaceWhite)
                .border(
                    RaftAppStroke.Default,
                    RaftColor.BorderBlack.copy(alpha = 0.2f),
                    RoundedCornerShape(RaftAppRadius.Container),
                )
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BoardSectionHeader(
            status = status,
            count = totalCount,
            collapsed = collapsed,
            onClick = onToggleCollapsed
        )
        if (!collapsed) {
            if (tasks.isEmpty()) {
                Text(
                    text = localizedTasksEmptyStatus(status),
                    style = RaftType.Caption,
                    color = RaftColor.TextMutedGray
                )
            } else {
                // Nested LazyColumn inside the outer LazyRow lets each column scroll its
                // own cards independently — mirrors the React board's per-column overflow.
                LazyColumn(
                    state = columnListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomContentPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(tasks.size, key = { i -> "board-task-${tasks[i].id.value}" }) { index ->
                        val task = tasks[index]
                        SharedTaskCard(
                            task = task,
                            hasDraft = draftProjection.hasDraftForTask(task),
                            captureLongPressAnchor = !columnListState.isScrollInProgress,
                            timeContext = timeContext,
                            onClick = { onOpenTask?.invoke(task) },
                            onStatusBadgeClick = { anchor -> onStatusBadgeClick(task, anchor) },
                            onAssigneeBadgeClick = { anchor -> onAssigneeBadgeClick(task, anchor) },
                            onLongPress = { anchor -> onLongPressTask(task, anchor) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun localizedTasksEmptyStatus(status: TaskStatus): String =
    stringResource(
        when (status) {
            TaskStatus.Todo -> Res.string.tasks_empty_todo
            TaskStatus.InProgress -> Res.string.tasks_empty_in_progress
            TaskStatus.InReview -> Res.string.tasks_empty_in_review
            TaskStatus.Done -> Res.string.tasks_empty_done
            TaskStatus.Closed -> Res.string.tasks_empty_closed
        }
    )

/**
 * raft-ui TaskSectionTrigger (web TasksPanel `TaskSection`): a 32dp row, `px-2 gap-2`, the
 * status pixel, the label in footnote bold hint ink and the count in mono-meta hint ink. The
 * whole row toggles the section; web's chevron is not drawn on the phone.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun BoardSectionHeader(
    status: TaskStatus,
    count: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = RaftAppThemeState.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .height(32.dp)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaftTaskStatusPixel(
            style = RaftTaskStatusPixelStyle(fill = status.raftTaskStatusColor(colors), stroke = RaftUiPalette.stickerInk)
        )
        Text(
            text = status.localizedLabel(),
            style = RaftType.SectionLabel,
            color = RaftColor.ForegroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = count.toString(),
            style = SharedTaskMonoMetaStyle,
            color = RaftColor.ForegroundHint,
            maxLines = 1
        )
    }
}

/**
 * raft-ui TaskCard (web `task/TaskCard.tsx`): card surface, mono-meta source + number,
 * body-semibold title, callout-muted description, and the status chip in the foot. Android
 * keeps its declared extras (platformDivergences.json): the relative timestamp at the top
 * right and the assignee chip after the status chip.
 */
@Composable
private fun SharedTaskCard(
    modifier: Modifier = Modifier,
    task: TaskSummary,
    hasDraft: Boolean,
    captureLongPressAnchor: Boolean = true,
    timeContext: RaftTimeContext? = null,
    onClick: () -> Unit,
    onStatusBadgeClick: (RaftPopoverAnchor) -> Unit,
    onAssigneeBadgeClick: (RaftPopoverAnchor) -> Unit = {},
    onLongPress: (RaftPopoverAnchor) -> Unit
) {
    // Keep a live card coordinate so long-press conversion remains correct after scrolling.
    val rootAnchor =
        remember(task.id.value) { RaftLiveRootAnchorRef(identity = tasksStatusMenuOwnerIdentity(task.id.value)) }
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "tasks-card",
            semanticIdentity = task.id.value,
            callbacks =
                SharedTaskCardGestureCallbacks(
                    onClick = onClick,
                    onLongPress = onLongPress,
                    rootAnchor = rootAnchor
                )
        )
    val description = task.description?.takeIf { it.isNotBlank() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (captureLongPressAnchor) {
                    Modifier.raftCaptureLiveRootAnchor(rootAnchor)
                } else {
                    Modifier
                }
            )
    ) {
        // raft-ui card: container radius, card layer, 1dp hairline; no ink frame or offset shadow.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RaftAppRadius.Container))
                .raftCardSurface()
                .raftGesturePointerInput(gestureOwner) { owner ->
                    var gesture: RaftGestureSession<SharedTaskCardGestureCallbacks>? = null
                    // tap = open task; long-press = open status menu. Wrapping both in the
                    // same pointer input keeps gesture semantics consistent and avoids
                    // clickable() swallowing the long-press.
                    detectTapGestures(
                        onPress = {
                            gesture = owner.beginGesture()
                            tryAwaitRelease()
                        },
                        onTap = {
                            (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onClick()
                        },
                        onLongPress = { localOffset ->
                            (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                                callbacks.rootAnchor
                                    .resolve(RaftPopoverLocalPoint.Exact(localOffset))
                                    ?.let(callbacks.onLongPress)
                            }
                        }
                    )
                }
                // web card: 1dp hairline + `p-3`; the meta row sits 4dp lower (measured
                // 17dp from the card's top edge to the meta line box).
                .padding(start = 13.dp, end = 13.dp, top = 17.dp, bottom = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                task.tasksSourceLabel()?.takeIf { it.isNotBlank() }?.let { source ->
                    Text(
                        text = source,
                        style = SharedTaskMonoMetaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                task.taskNumber?.let { taskNumber ->
                    Text(
                        text = "#$taskNumber",
                        style = SharedTaskMonoMetaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1
                    )
                }
                if (hasDraft) {
                    RaftLucideIcon(
                        RaftLucideIconName.Pencil,
                        modifier = Modifier.size(13.dp),
                        color = RaftColor.TextMutedGrayArgb
                    )
                }
                Spacer(Modifier.weight(1f))
                task.updatedAtLabel.takeIf { it.isNotBlank() }?.let { updatedAt ->
                    RaftRelativeTimestampText(
                        value = updatedAt,
                        timeContext = timeContext,
                        style = SharedTaskMonoMetaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = task.tasksMeaningfulDisplayTitle(),
                // `text-body` 16/24 at 600 (task titles).
                style = RaftType.ListItem,
                color = RaftColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            description?.let { desc ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = RaftType.Button.withRaftFontWeight(FontWeight.Normal),
                    color = RaftColor.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // web foot: `mt-2` + the chip's `mt-0.5`.
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status first, at the card's leading edge like web's TaskCard foot; the menu
                // opens from the chip's bottom-left (`dropdownAlign="left"`).
                RaftTaskStatusChip(
                    status = task.status,
                    onClick = onStatusBadgeClick,
                    clickIdentity = tasksStatusChipClickIdentity(task.id.value, task.status),
                    anchorOwnerIdentity = tasksStatusMenuOwnerIdentity(task.id.value)
                )
                // Android-only assignee chip (declared divergence). Task #251: it
                // shrink-wraps its label like Web's InlineBadgeEditor; long display names
                // are capped and ellipsized.
                val assigneeDisplayName = task.tasksAssigneeDisplayName()
                RaftAnchoredMetaChip(
                    label = assigneeDisplayName?.let { "@$it" }
                        ?: stringResource(Res.string.tasks_assignee_unassigned),
                    iconName = null,
                    color = RaftColor.SurfaceWhite,
                    onClick = onAssigneeBadgeClick,
                    clickIdentity = tasksAssigneeChipClickIdentity(task.id.value),
                    anchorOwnerIdentity = tasksStatusMenuOwnerIdentity(task.id.value),
                    modifier = Modifier.widthIn(max = SharedTaskCardAssigneeChipMaxWidth),
                    trailingIconName = RaftLucideIconName.Pencil,
                    minHeight = 22.dp,
                    horizontalPadding = 6.dp,
                    verticalPadding = 0.dp,
                    labelFontSize = 12.sp,
                    labelLineHeight = 16.sp,
                    labelFontWeight = FontWeight.SemiBold,
                    borderWidth = RaftAppStroke.Thin,
                    anchorPlacement = RaftAnchorPlacement.BottomEnd
                )
                task.taskCardReplyCount()?.let { count ->
                    Spacer(Modifier.weight(1f))
                    SharedTaskReplyCount(count)
                }
            }
        }
    }
}

/**
 * Task v2: the size of the task's own conversation (web TaskChipList `ReplyCount`): only a task with
 * its own conversation and at least one reply; a pre-v2 task talks in its host message's thread.
 */
internal fun TaskSummary.taskCardReplyCount(): Int? =
    conversationReplyCount.takeIf { conversationChannelId != null && it > 0 }

/** web `ReplyCount`: a 12dp `message-square` then the count in `text-mono-meta` `foreground-hint`. */
@Composable
private fun SharedTaskReplyCount(count: Int) {
    val description = stringResource(Res.string.tasks_reply_count_description, count)
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftLucideIcon(RaftLucideIconName.MessageSquare, Modifier.size(12.dp), color = raftArgbHex(RaftColor.ForegroundHint))
        Text(text = count.toString(), style = SharedTaskMonoMetaStyle, color = RaftColor.ForegroundHint, maxLines = 1)
    }
}

/**
 * raft-ui TaskStatusBadge as an editable trigger (web InlineBadgeEditor with `badgeStatus`):
 * a 22dp soft chip in the status `-soft` wash, `px-1.5`, the 9dp status pixel, the label in
 * caption semibold foreground ink and a 12dp chevron. Tapping hands the chip's bottom-left
 * anchor to [onClick] so the status menu drops below it, left-aligned.
 */
@Composable
internal fun RaftTaskStatusChip(
    status: TaskStatus,
    onClick: ((RaftPopoverAnchor) -> Unit)?,
    modifier: Modifier = Modifier,
    clickIdentity: Any? = null,
    anchorOwnerIdentity: Any? = null
) {
    val colors = RaftAppThemeState.colors
    RaftAnchoredMetaChip(
        label = status.localizedLabel(),
        iconName = null,
        color = status.raftTaskStatusSoft(colors),
        onClick = onClick,
        clickIdentity = clickIdentity,
        anchorOwnerIdentity = anchorOwnerIdentity,
        modifier = modifier,
        trailingIconName = RaftLucideIconName.ChevronDown,
        trailingIconSize = 12.dp,
        trailingIconColorArgb = RaftColor.TextBlackArgb,
        borderWidth = 0.dp,
        minHeight = 22.dp,
        horizontalPadding = 6.dp,
        verticalPadding = 0.dp,
        labelFontSize = 12.sp,
        labelLineHeight = 16.sp,
        labelFontWeight = FontWeight.SemiBold,
        contentSpacing = 4.dp,
        leadingContent = {
            RaftTaskStatusPixel(
                style = RaftTaskStatusPixelStyle(fill = status.raftTaskStatusColor(colors), stroke = RaftUiPalette.stickerInk),
                modifier = Modifier.padding(end = 1.dp)
            )
        },
        anchorPlacement = RaftAnchorPlacement.BottomStart
    )
}

/** `text-mono-meta` 12/16 Space Mono: card meta, timestamps and section counts. */
private val SharedTaskMonoMetaStyle: androidx.compose.ui.text.TextStyle
    get() = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)

private val SharedTasksListSectionGap = 24.dp
private val SharedTasksListItemGap = 10.dp

private data class SharedTaskCardGestureCallbacks(
    val onClick: () -> Unit,
    val onLongPress: (RaftPopoverAnchor) -> Unit,
    val rootAnchor: RaftLiveRootAnchorRef
)

internal fun tasksStatusChipClickIdentity(taskId: String, status: TaskStatus): String =
    "tasks-status:$taskId:${status.name}"

internal fun tasksAssigneeChipClickIdentity(taskId: String): String =
    "tasks-assignee:$taskId"

internal fun tasksStatusMenuOwnerIdentity(taskId: String): String = "task:$taskId"

@Composable
private fun SharedTasksSegmentedControl(
    selectedMode: TasksViewMode,
    onModeSelected: (TasksViewMode) -> Unit
) {
    SegmentedControl(
        selectedValue = selectedMode.name,
        options =
            listOf(
                SegmentedControlOption(value = TasksViewMode.Board.name, label = stringResource(Res.string.tasks_view_board), iconName = RaftLucideIconName.Columns3),
                SegmentedControlOption(value = TasksViewMode.List.name, label = stringResource(Res.string.tasks_view_list), iconName = RaftLucideIconName.LayoutList)
            ),
        onValueSelected = { value ->
            when (value) {
                TasksViewMode.Board.name -> onModeSelected(TasksViewMode.Board)
                TasksViewMode.List.name -> onModeSelected(TasksViewMode.List)
            }
        }
    )
}

@Composable
private fun SharedTasksNotice(
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1064
                .background(RaftColor.AccentRed.copy(alpha = 0.12f), RoundedCornerShape(RaftAppRadius.Container))
                .border(RaftAppStroke.Default, RaftColor.BorderBlack, RoundedCornerShape(RaftAppRadius.Container))
                .padding(RaftAppSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = title, style = RaftType.ButtonBold, color = RaftColor.TextBlack)
        Text(text = detail, style = RaftType.Caption, color = RaftColor.AccentRed)
    }
}

@Composable
private fun SharedTasksStateBody(
    title: String,
    detail: String?,
    loading: Boolean = false,
    iconName: RaftLucideIconName = RaftLucideIconName.SquareCheckBig,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    if (loading) {
        RaftCenteredLoading(message = title)
        return
    }
    RaftEmptyState(
        title = title,
        detail = detail,
        iconName = iconName,
        actionLabel = actionLabel,
        onAction = onAction
    )
}

@Composable
internal fun TaskStatus.localizedLabel(): String =
    stringResource(
        when (this) {
            TaskStatus.Todo -> Res.string.tasks_status_todo
            TaskStatus.InProgress -> Res.string.tasks_status_in_progress
            TaskStatus.InReview -> Res.string.tasks_status_in_review
            TaskStatus.Done -> Res.string.tasks_status_done
            TaskStatus.Closed -> Res.string.tasks_status_closed
        }
    )

private val SharedTaskCardAssigneeChipMaxWidth = 160.dp

private val TaskStatus.iconName: RaftLucideIconName
    get() =
        when (this) {
            TaskStatus.Todo -> RaftLucideIconName.Circle
            TaskStatus.InProgress -> RaftLucideIconName.Play
            TaskStatus.InReview -> RaftLucideIconName.Eye
            // Registry only ships "circle-check-big"; the smaller variant is not bundled.
            TaskStatus.Done -> RaftLucideIconName.CircleCheckBig
            TaskStatus.Closed -> RaftLucideIconName.Ban
        }

private fun TaskStatus.nextStatus(): TaskStatus =
    when (this) {
        TaskStatus.Todo -> TaskStatus.InProgress
        TaskStatus.InProgress -> TaskStatus.InReview
        TaskStatus.InReview -> TaskStatus.Done
        TaskStatus.Done -> TaskStatus.Closed
        TaskStatus.Closed -> TaskStatus.Todo
    }

internal fun TaskSummary.tasksMeaningfulDisplayTitle(): String {
    val raw = tasksDisplayTitle()
    val numberLabel = taskNumber?.let { "#$it" } ?: return raw
    return raw.removePrefix(numberLabel).trim().takeIf { it.isNotBlank() } ?: raw
}

internal data class TaskDisplayOrderKey(
    val updatedAt: Long,
    val taskNumber: Int,
    val normalizedTitle: String
)

internal fun TaskSummary.taskDisplayOrderKey(): TaskDisplayOrderKey =
    TaskDisplayOrderKey(
        updatedAt = updatedAtLabel.toTaskSortValue(),
        taskNumber = taskNumber ?: Int.MIN_VALUE,
        normalizedTitle = title.lowercase()
    )

internal fun List<TaskSummary>.sortedByTaskDisplayOrder(
    keyOf: (TaskSummary) -> TaskDisplayOrderKey = TaskSummary::taskDisplayOrderKey
): List<TaskSummary> =
    map { task -> task to keyOf(task) }
        .sortedWith(
            compareByDescending<Pair<TaskSummary, TaskDisplayOrderKey>> { it.second.updatedAt }
                .thenByDescending { it.second.taskNumber }
                .thenBy { it.second.normalizedTitle }
        )
        .map { it.first }

private fun String.toTaskSortValue(): Long {
    val trimmed = trim()
    if (trimmed.isBlank()) return Long.MIN_VALUE
    if (trimmed.all { it.isDigit() }) {
        val value = trimmed.toLongOrNull() ?: return Long.MIN_VALUE
        return when (trimmed.length) {
            10 -> value * 1_000L
            13 -> value
            else -> value
        }
    }
    return Long.MIN_VALUE
}


@Composable
private fun TasksStatusDialog(
    task: TaskSummary,
    updating: Boolean,
    errorCode: String?,
    onDismiss: () -> Unit,
    onSelect: (TaskStatus) -> Unit
) {
    RaftUnifiedDialog(
        title = stringResource(Res.string.tasks_label_status),
        onDismiss = onDismiss,
        dismissEnabled = !updating,
        contentSpacing = RaftAppSpacing.Sm
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
        ) {
            item {
                Text(
                    text = task.tasksMeaningfulDisplayTitle(),
                    modifier = Modifier.raftCopyable(task.tasksMeaningfulDisplayTitle()),
                    style = RaftType.ButtonBold,
                    color = RaftColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item {
                Text(
                    text = stringResource(Res.string.tasks_current_status, task.status.localizedLabel()),
                    style = RaftType.Caption,
                    color = RaftColor.TextBlack.copy(alpha = 0.65f)
                )
            }
            items(TaskStatus.values().size) { idx ->
                val status = TaskStatus.values()[idx]
                val selected = status == task.status
                RaftModalFullWidthActionButton(
                    label = status.localizedLabel(),
                    icon = if (selected) RaftLucideIconName.Check else RaftLucideIconName.Circle,
                    color = if (selected) RaftColor.BrandYellow else RaftColor.SurfaceWhite,
                    enabled = !updating && !selected,
                    onClick = { onSelect(status) }
                )
            }
            errorCode?.takeIf { it.isNotBlank() }?.let { code ->
                item {
                    Text(
                        text = "${stringResource(Res.string.tasks_error_update_status)}: $code",
                        style = RaftType.Caption,
                        color = RaftColor.AccentRed
                    )
                }
            }
        }
    }
}

@Composable
private fun TasksCreateDialog(
    loading: Boolean,
    errorCode: String?,
    onDismiss: () -> Unit,
    onSubmit: (List<String>) -> Unit
) {
    var titles by remember { mutableStateOf(listOf("")) }
    val anyNonBlank = titles.any { it.isNotBlank() }
    RaftUnifiedDialog(
        title = stringResource(Res.string.tasks_label_new_tasks),
        onDismiss = onDismiss,
        dismissEnabled = !loading,
        keyboardAvoidanceEnabled = true,
        minWidth = 280.dp,
        maxWidth = 360.dp,
        contentPadding = RaftAppSpacing.Md,
        contentSpacing = RaftAppSpacing.Sm
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
        ) {
            item {
                Text(
                    text = stringResource(Res.string.tasks_create_multiple_hint),
                    style = RaftType.Caption,
                    color = RaftColor.TextMutedGray
                )
            }
            items(titles.size) { index ->
                val value = titles[index]
                TextField(
                    value = value,
                    onValueChange = { next ->
                        titles = titles.toMutableList().also { it[index] = next }
                    },
                    placeholder = stringResource(Res.string.tasks_label_title),
                    placeholderColor = RaftColor.TextMutedGray,
                    enabled = !loading,
                    autoFocus = index == 0,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .background(RaftColor.AppBgLightBlue.copy(alpha = 0.6f), RoundedCornerShape(RaftAppRadius.Control))
                        .border(RaftAppStroke.Thin, RaftColor.BorderBlack, RoundedCornerShape(RaftAppRadius.Control))
                        .padding(horizontal = RaftAppSpacing.Sm, vertical = 6.dp)
                )
            }
            item {
                RaftToolbarChip(
                    label = stringResource(Res.string.tasks_action_add_another),
                    iconName = RaftLucideIconName.Plus,
                    active = false,
                    enabled = !loading,
                    onClick = { titles = titles + "" }
                )
            }
            errorCode?.takeIf { it.isNotBlank() }?.let { code ->
                item {
                    Text(
                        text = code,
                        style = RaftType.Caption,
                        color = RaftColor.AccentRed,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                enabled = !loading,
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg
            )
            RaftButton(
                text = if (loading) {
                        stringResource(Res.string.tasks_create_action_creating)
                    } else {
                        stringResource(Res.string.tasks_create_action)
                    },
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Check) },
                enabled = anyNonBlank && !loading,
                onClick = { onSubmit(titles) },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg
            )
        }
    }
}
