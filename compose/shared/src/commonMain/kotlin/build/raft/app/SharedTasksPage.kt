package build.raft.app

import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.SlockAnchorPlacement
import build.raft.app.components.SlockAnchoredMetaChip
import build.raft.app.components.SlockAnchoredToolbarChip
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockToolbarChip
import build.raft.app.data.KeyValueTasksViewPreferenceRepository
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationIntent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksVisibleContent
import build.raft.app.data.TasksViewMode
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.data.boardColumns
import build.raft.app.data.reduce
import build.raft.app.data.tasksAssigneeDisplayName
import build.raft.app.data.tasksDisplayTitle
import build.raft.app.data.tasksIdentityFilterId
import build.raft.app.data.tasksSourceLabel
import build.raft.app.data.visibleContent
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberProfile
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxWithConstraints
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
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
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.items
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.core.pager.Pager

private const val TasksListMaxBeyondBoundsItemCount = 4

@Composable
internal fun SharedTasksScreen(
    state: TasksUiState,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    scrollToTopRequest: Int = 0,
    modifier: Modifier = Modifier,
    onOpenTask: ((TaskSummary) -> Unit)? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (AppShellLazyListPosition) -> Unit = {},
    timeContext: SlockTimeContext? = null
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
    bottomContentPadding: Dp = SlockSpacing.Lg,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (AppShellLazyListPosition) -> Unit = {},
    /** Fixture hook (same pattern as SharedHomeSearchPanel.initialOpenFilterId): when
     *  non-null, the loaded body starts from this presentation state instead of
     *  [TasksPresentationState.Default] + the persisted view-mode preference. The
     *  components.tasks.panel.states visual case starts in List mode with Done expanded,
     *  mirroring the React capture's "Show Done" click (task #330/#353). */
    initialPresentationOverride: TasksPresentationState? = null,
    timeContext: SlockTimeContext? = null
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

    // #no-task-new-btn: when the task list is empty (ScreenLoadState.Empty),
    // the user still needs the New Task button and filter toolbar visible.
    var emptyCreateDialogOpen by remember { mutableStateOf(false) }
    var emptySelectedMode by remember { mutableStateOf(TasksViewMode.List) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        when (val loadState = displayState) {
            ScreenLoadState.Loading -> {
                if (showHeader) {
                    SharedTasksHeader(refreshing = state.refreshing)
                }
                SharedTasksStateBody(
                    title = stringResource(MR.strings.common_loading),
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
                    timeContext = timeContext
                )
            is ScreenLoadState.Empty -> {
                if (showHeader) {
                    SharedTasksHeader(refreshing = state.refreshing)
                }
                val showEmptyToolbar = sharedTasksEmptyToolbarVisible(createTasksChannelId)
                val emptyCopy =
                    localizedSharedTasksEmptyStateCopy(
                        hasAnyFilter = state.selectedFilter != null,
                        isChannelMode = createTasksChannelId != null
                    )
                if (showEmptyToolbar) {
                    // #no-task-new-btn: channel empty state still needs New Task.
                    // Server-wide empty has no create action and no task rows, so
                    // skip the disabled filter/view toolbar on the tab-switch path.
                    SharedTasksToolbar(
                        channelOptions = emptyList(),
                        selectedChannels = emptySet(),
                        creatorOptions = emptyList(),
                        selectedCreatorIds = emptySet(),
                        assigneeOptions = emptyList(),
                        selectedAssigneeIds = emptySet(),
                        selectedMode = emptySelectedMode,
                        showChannelFilter = showChannelFilter,
                        openFilterId = null,
                        showCreateButton = true,
                        onModeSelected = { mode -> emptySelectedMode = mode },
                        onChannelChipClick = { },
                        onCreatorChipClick = { },
                        onAssigneeChipClick = { },
                        onClearAll = { },
                        onCreateClick = { emptyCreateDialogOpen = true }
                    )
                }
                SharedTasksStateBody(
                    title = emptyCopy.title,
                    detail = emptyCopy.description,
                    iconName = SlockLucideIconName.SquareCheckBig
                )
                if (emptyCreateDialogOpen && createTasksChannelId != null) {
                    TasksCreateDialog(
                        refreshing = state.refreshing,
                        onDismiss = { emptyCreateDialogOpen = false },
                        onSubmit = { titles ->
                            onIntent(SharedTasksRouteIntent.CreateTasks(createTasksChannelId, titles))
                            emptyCreateDialogOpen = false
                        }
                    )
                }
            }
            is ScreenLoadState.Error -> {
                if (showHeader) {
                    SharedTasksHeader(refreshing = state.refreshing)
                }
                SharedTasksStateBody(
                    title = stringResource(MR.strings.tasks_error_load),
                    detail = loadState.error.code,
                    actionLabel = stringResource(MR.strings.common_retry),
                    onAction = { onIntent(SharedTasksRouteIntent.Refresh) }
                )
            }
            is ScreenLoadState.NoPermission -> {
                if (showHeader) {
                    SharedTasksHeader(refreshing = state.refreshing)
                }
                SharedTasksStateBody(
                    title = stringResource(MR.strings.tasks_unavailable),
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
    SlockMainTabPageHeader(
        surface = SlockMainTabHeaderSurface.Tasks,
        title = stringResource(MR.strings.tasks_title),
        subtitle = subtitle ?: stringResource(MR.strings.tasks_header_channel_tasks),
        refreshing = refreshing
    )
}

@Composable
private fun localizedTasksHeaderSubtitle(totalTasks: Int, visibleTasks: Int = totalTasks): String {
    val safeTotal = totalTasks.coerceAtLeast(0)
    val safeVisible = visibleTasks.coerceIn(0, safeTotal)
    return if (safeVisible != safeTotal) {
        stringResource(MR.strings.tasks_header_channel_tasks_filtered, safeVisible, safeTotal)
    } else {
        stringResource(MR.strings.tasks_header_channel_tasks_count, safeTotal)
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
                title = stringResource(MR.strings.tasks_empty_filtered),
                description = stringResource(MR.strings.tasks_empty_filtered_detail)
            )
        isChannelMode ->
            SharedTasksEmptyStateCopy(
                title = stringResource(MR.strings.tasks_empty),
                description = stringResource(MR.strings.tasks_create_hint)
            )
        else ->
            SharedTasksEmptyStateCopy(
                title = stringResource(MR.strings.tasks_empty),
                description = stringResource(MR.strings.tasks_empty_server_detail)
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
    timeContext: SlockTimeContext?
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
            timeContext = timeContext
        )
    }
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
    timeContext: SlockTimeContext?
) {
    val pager = LocalActivity.current.getPager() as Pager
    val viewModeRepository =
        remember(pager) {
            KeyValueTasksViewPreferenceRepository(slockSharedPreferenceStore())
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
    val visibleContent = remember(sortedContent, state.selectedFilter, presentationState) {
        sortedContent.visibleContent(
            selectedFilter = state.selectedFilter,
            presentationState = presentationState,
            showChannelFilter = showChannelFilter
        )
    }
    val availableChannelIds = remember(visibleContent.channelOptions) {
        visibleContent.channelOptions.toSet()
    }
    val availableCreatorIds = remember(visibleContent.creatorOptions) {
        visibleContent.creatorOptions.map { it.tasksIdentityFilterId() }.toSet()
    }
    val availableAssigneeIds = remember(visibleContent.assigneeOptions) {
        visibleContent.assigneeOptions.map { it.id.value }.toSet()
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
    var statusMenuAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
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

    // Filter popover anchors. Resolved from each chip's live coordinates when opened.
    var channelAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    var creatorAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    var assigneeAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    var createDialogOpen by remember { mutableStateOf(false) }
    val channelFilterOptions = remember(visibleContent.channelOptions) {
        visibleContent.channelOptions.map { name -> SlockFilterOption(id = name, label = name, icon = SlockLucideIconName.Hash) }
    }
    val creatorFilterOptions = remember(visibleContent.creatorOptions) {
        visibleContent.creatorOptions.map { SlockFilterOption(id = it.tasksIdentityFilterId(), label = it.displayName, icon = SlockLucideIconName.CircleUser) }
    }
    val assigneeFilterOptions = remember(visibleContent.assigneeOptions) {
        visibleContent.assigneeOptions.map { SlockFilterOption(id = it.id.value, label = it.displayName, icon = SlockLucideIconName.User) }
    }
    SlockBackHandler(
        enabled = createDialogOpen || statusMenuTask != null || presentationState.openFilterId != null
    ) {
        when {
            createDialogOpen -> createDialogOpen = false
            statusMenuTask != null -> statusMenuTask = null
            presentationState.openFilterId != null ->
                presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null))
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        if (showHeader) {
            SharedTasksHeader(
                subtitle =
                    localizedTasksHeaderSubtitle(
                        totalTasks = content.tasks.size,
                        visibleTasks = visibleContent.filteredTasks.size
                    ),
                refreshing = state.refreshing
            )
        }
        Box(modifier = Modifier.weight(1f).background(SlockColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                state.navigationErrorCode?.let { code ->
                    SharedTasksNotice(stringResource(MR.strings.tasks_error_action_unavailable), code, modifier = Modifier.padding(SlockSpacing.Md))
                }
                state.statusUpdateErrorCode?.let { code ->
                    SharedTasksNotice(stringResource(MR.strings.tasks_error_update_status), code, modifier = Modifier.padding(SlockSpacing.Md))
                }
                SharedTasksToolbar(
                    channelOptions = visibleContent.channelOptions,
                    selectedChannels = visibleContent.selectedChannelNames,
                    creatorOptions = visibleContent.creatorOptions,
                    selectedCreatorIds = visibleContent.selectedCreatorIds,
                    assigneeOptions = visibleContent.assigneeOptions,
                    selectedAssigneeIds = visibleContent.selectedAssigneeIds,
                    selectedMode = presentationState.selectedMode,
                    showChannelFilter = showChannelFilter,
                    openFilterId = presentationState.openFilterId,
                    showCreateButton = createTasksChannelId != null,
                    onModeSelected = { mode ->
                        viewModeRepository.selectTasksViewModeSync(mode)
                        presentationState = presentationState.reduce(TasksPresentationIntent.SelectMode(mode))
                    },
                    onChannelChipClick = { anchor ->
                        channelAnchor = anchor
                        presentationState =
                            presentationState.reduce(
                                TasksPresentationIntent.SetOpenFilter(
                                    if (presentationState.openFilterId == "channel") null else "channel"
                                )
                            )
                    },
                    onCreatorChipClick = { anchor ->
                        creatorAnchor = anchor
                        presentationState =
                            presentationState.reduce(
                                TasksPresentationIntent.SetOpenFilter(
                                    if (presentationState.openFilterId == "creator") null else "creator"
                                )
                            )
                    },
                    onAssigneeChipClick = { anchor ->
                        assigneeAnchor = anchor
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
                    onCreateClick = { createDialogOpen = true }
                )
                when (presentationState.selectedMode) {
                    TasksViewMode.Board -> {
                        // Horizontal board: each TaskStatus is a fixed-width column the user can
                        // scroll between left/right. Mirrors the React `overflow-x-auto` panel.
                        LazyRow(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
                            contentPadding = PaddingValues(SlockSpacing.Md)
                        ) {
                            items(boardColumns.size, key = { i -> "board-col-${boardColumns[i].status}" }) { index ->
                                val column = boardColumns[index]
                                SharedTasksBoardColumn(
                                    status = column.status,
                                    totalCount = column.totalCount,
                                    tasks = column.tasks,
                                    collapsed = column.status in collapsedStatuses,
                                    updatingTaskId = state.statusUpdatingTaskId,
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
                                    onLongPressTask = { task, anchor ->
                                        statusMenuTask = task
                                        statusMenuAnchor = anchor
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
                                sharedTasksEmptyStateCopy(
                                    hasAnyFilter = state.selectedFilter != null || visibleContent.hasAnyPresentationFilter(),
                                    isChannelMode = createTasksChannelId != null
                                )
                            SharedTasksStateBody(
                                title = emptyCopy.title,
                                detail = emptyCopy.description,
                                iconName = SlockLucideIconName.SquareCheckBig
                            )
                        } else {
                            LazyColumn(
                                state = listModeListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding =
                                    PaddingValues(
                                        start = SlockSpacing.Md,
                                        top = SlockSpacing.Md,
                                        end = SlockSpacing.Md,
                                        bottom = bottomContentPadding
                                    ),
                                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
                                beyondBoundsItemCount = sharedTasksBeyondBoundsItemCount(visibleContent.filteredTasks.size)
                            ) {
                                listColumns.forEach { column ->
                                    item(key = "list-header-${column.status.name}") {
                                        BoardSectionHeader(
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
                                                task = task,
                                                updating = state.statusUpdatingTaskId == task.id.value,
                                                hasDraft = draftProjection.hasDraftForTask(task),
                                                captureLongPressAnchor = !listModeListState.isScrollInProgress,
                                                timeContext = timeContext,
                                                onClick = { onOpenTask?.invoke(task) },
                                                onStatusBadgeClick = { anchor ->
                                                    statusMenuTask = task
                                                    statusMenuAnchor = anchor
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

        // --- Overlays. Rendered in z-order: popovers, then status menu, then create dialog.
        // Each overlay short-circuits on null state so unmounting tears down the gesture
        // capture overlay too (otherwise the dim layer would swallow taps).
        when (presentationState.openFilterId) {
            "channel" ->
                channelAnchor?.let { anchor -> SlockFilterPopover(
                    anchor = anchor,
                    title = stringResource(MR.strings.tasks_filter_channel),
                    searchPlaceholder = stringResource(MR.strings.tasks_search_channels),
                    searchText = presentationState.channelSearch,
                    onSearchChange = { presentationState = presentationState.reduce(TasksPresentationIntent.SetChannelSearch(it)) },
                    options = channelFilterOptions,
                    selectedIds = visibleContent.selectedChannelNames,
                    onToggle = { id -> presentationState = presentationState.reduce(TasksPresentationIntent.ToggleChannel(id)) },
                    onClear = { presentationState = presentationState.reduce(TasksPresentationIntent.ClearChannels) },
                    onDismiss = { presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null)) }
                ) }
            "creator" ->
                creatorAnchor?.let { anchor -> SlockFilterPopover(
                    anchor = anchor,
                    title = stringResource(MR.strings.tasks_filter_creator),
                    searchPlaceholder = stringResource(MR.strings.tasks_search_creators),
                    searchText = presentationState.creatorSearch,
                    onSearchChange = { presentationState = presentationState.reduce(TasksPresentationIntent.SetCreatorSearch(it)) },
                    options = creatorFilterOptions,
                    selectedIds = visibleContent.selectedCreatorIds,
                    onToggle = { id -> presentationState = presentationState.reduce(TasksPresentationIntent.ToggleCreator(id)) },
                    onClear = { presentationState = presentationState.reduce(TasksPresentationIntent.ClearCreators) },
                    onDismiss = { presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null)) }
                ) }
            "assignee" ->
                assigneeAnchor?.let { anchor -> SlockFilterPopover(
                    anchor = anchor,
                    title = stringResource(MR.strings.tasks_filter_assignee),
                    searchPlaceholder = stringResource(MR.strings.tasks_search_assignees),
                    searchText = presentationState.assigneeSearch,
                    onSearchChange = { presentationState = presentationState.reduce(TasksPresentationIntent.SetAssigneeSearch(it)) },
                    options = assigneeFilterOptions,
                    selectedIds = visibleContent.selectedAssigneeIds,
                    onToggle = { id -> presentationState = presentationState.reduce(TasksPresentationIntent.ToggleAssignee(id)) },
                    onClear = { presentationState = presentationState.reduce(TasksPresentationIntent.ClearAssignees) },
                    onDismiss = { presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null)) }
                ) }
            else -> Unit
        }

        statusMenuTask?.let { task ->
            val resolvedStatusAnchor =
                statusMenuAnchor?.takeIf { it.belongsTo(tasksStatusMenuOwnerIdentity(task.id.value)) }
                    ?: return@let
            SlockTaskStatusMenuOverlay(
                anchor = resolvedStatusAnchor,
                items = TaskStatus.values().map { next ->
                    SlockBaseMenuItem(id = next.name, label = next.localizedLabel())
                },
                selectedId = task.status.name,
                onDismiss = { statusMenuTask = null },
                onSelect = { id ->
                    val next = TaskStatus.values().firstOrNull { it.name == id }
                    statusMenuTask = null
                    if (next != null && next != task.status) {
                        onIntent(SharedTasksRouteIntent.UpdateStatus(task, next))
                    }
                }
            )
        }

        if (createDialogOpen && createTasksChannelId != null) {
            TasksCreateDialog(
                refreshing = state.refreshing,
                onDismiss = { createDialogOpen = false },
                onSubmit = { titles ->
                    onIntent(SharedTasksRouteIntent.CreateTasks(createTasksChannelId, titles))
                    createDialogOpen = false
                }
            )
        }
        }
    }
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

private fun defaultTasksViewMode(maxWidth: Dp): TasksViewMode =
    if (maxWidth < 768.dp) TasksViewMode.List else TasksViewMode.Board

@Composable
private fun SharedTasksToolbar(
    channelOptions: List<String>,
    selectedChannels: Set<String>,
    creatorOptions: List<MemberProfile>,
    selectedCreatorIds: Set<String>,
    assigneeOptions: List<MemberProfile>,
    selectedAssigneeIds: Set<String>,
    selectedMode: TasksViewMode,
    showChannelFilter: Boolean,
    openFilterId: String?,
    showCreateButton: Boolean,
    onModeSelected: (TasksViewMode) -> Unit,
    onChannelChipClick: (SlockPopoverAnchor) -> Unit,
    onCreatorChipClick: (SlockPopoverAnchor) -> Unit,
    onAssigneeChipClick: (SlockPopoverAnchor) -> Unit,
    onClearAll: () -> Unit,
    onCreateClick: () -> Unit
) {
    val anyFilterActive = selectedChannels.isNotEmpty() || selectedCreatorIds.isNotEmpty() || selectedAssigneeIds.isNotEmpty()
    val channelLabel = stringResource(MR.strings.tasks_chip_channel)
    val creatorLabel = stringResource(MR.strings.tasks_chip_creator)
    val assigneeLabel = stringResource(MR.strings.tasks_chip_assignee)
    val channelChip: @Composable () -> Unit = {
        SlockAnchoredToolbarChip(
            label = if (selectedChannels.isEmpty()) channelLabel else "$channelLabel · ${selectedChannels.size}",
            anchorIdentity = "tasks_filter:channel",
            iconName = SlockLucideIconName.Hash,
            // react filter chips are Title case (no uppercase transform) — task #373.
            uppercase = false,
            active = openFilterId == "channel" || selectedChannels.isNotEmpty(),
            enabled = channelOptions.isNotEmpty(),
            onClick = onChannelChipClick
        )
    }
    val creatorChip: @Composable () -> Unit = {
        SlockAnchoredToolbarChip(
            label = if (selectedCreatorIds.isEmpty()) creatorLabel else "$creatorLabel · ${selectedCreatorIds.size}",
            anchorIdentity = "tasks_filter:creator",
            iconName = SlockLucideIconName.CircleUser,
            uppercase = false,
            active = openFilterId == "creator" || selectedCreatorIds.isNotEmpty(),
            enabled = creatorOptions.isNotEmpty(),
            onClick = onCreatorChipClick
        )
    }
    val assigneeChip: @Composable () -> Unit = {
        SlockAnchoredToolbarChip(
            label = if (selectedAssigneeIds.isEmpty()) assigneeLabel else "$assigneeLabel · ${selectedAssigneeIds.size}",
            anchorIdentity = "tasks_filter:assignee",
            iconName = SlockLucideIconName.User,
            uppercase = false,
            active = openFilterId == "assignee" || selectedAssigneeIds.isNotEmpty(),
            enabled = assigneeOptions.isNotEmpty(),
            onClick = onAssigneeChipClick
        )
    }
    val clearAllChip: @Composable () -> Unit = {
        SlockToolbarChip(
            label = stringResource(MR.strings.tasks_action_clear_all),
            iconName = SlockLucideIconName.X,
            uppercase = false,
            active = false,
            onClick = onClearAll
        )
    }
    val newTaskButton: @Composable () -> Unit = {
        SlockBrutalActionButton(
            label = stringResource(MR.strings.tasks_action_new_task),
            leadingIcon = SlockLucideIconName.Plus,
            backgroundColor = SlockColor.AccentPink,
            minWidth = 0.dp,
            minHeight = 30.dp,
            shadowOffset = 2.dp,
            textStyle = SlockType.TabLabel.copy(fontSize = 11.sp, lineHeight = 14.sp),
            onClick = onCreateClick
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
                .background(SlockColor.SurfaceWhite)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SlockSpacing.Md, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
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
                                Arrangement.spacedBy(SlockSpacing.Sm)
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
                    .height(SlockStroke.Default)
                    .background(SlockColor.BorderBlack)
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
    updatingTaskId: String?,
    onToggleCollapsed: () -> Unit,
    onOpenTask: ((TaskSummary) -> Unit)?,
    timeContext: SlockTimeContext?,
    onStatusBadgeClick: (TaskSummary, SlockPopoverAnchor) -> Unit,
    onLongPressTask: (TaskSummary, SlockPopoverAnchor) -> Unit,
    bottomContentPadding: Dp,
    draftProjection: SharedThreadComposerDraftProjection,
    scrollToTopRequest: Int,
) {
    val columnListState = rememberLazyListState()
    val scrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequestFence.consume(scrollToTopRequest) && !collapsed) {
            columnListState.animateScrollToTopExpressive()
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
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.2f))
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
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            } else {
                // Nested LazyColumn inside the outer LazyRow lets each column scroll its
                // own cards independently — mirrors the React board's per-column overflow.
                LazyColumn(
                    state = columnListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomContentPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    beyondBoundsItemCount = sharedTasksBeyondBoundsItemCount(tasks.size)
                ) {
                    items(tasks.size, key = { i -> "board-task-${tasks[i].id.value}" }) { index ->
                        val task = tasks[index]
                        SharedTaskCard(
                            task = task,
                            updating = updatingTaskId == task.id.value,
                            hasDraft = draftProjection.hasDraftForTask(task),
                            captureLongPressAnchor = !columnListState.isScrollInProgress,
                            timeContext = timeContext,
                            onClick = { onOpenTask?.invoke(task) },
                            onStatusBadgeClick = { anchor -> onStatusBadgeClick(task, anchor) },
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
            TaskStatus.Todo -> MR.strings.tasks_empty_todo
            TaskStatus.InProgress -> MR.strings.tasks_empty_in_progress
            TaskStatus.InReview -> MR.strings.tasks_empty_in_review
            TaskStatus.Done -> MR.strings.tasks_empty_done
            TaskStatus.Closed -> MR.strings.tasks_empty_closed
        }
    )

@Composable
private fun BoardSectionHeader(
    status: TaskStatus,
    count: Int,
    collapsed: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SharedTasksStatusLabel(status)
        Text(
            text = count.toString(),
            style = SlockType.Caption.copy(fontFamily = SlockMonoFamily),
            color = SlockColor.TextBlack.copy(alpha = 0.5f),
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        SlockLucideIcon(
            "chevron-down",
            modifier = Modifier.size(14.dp).rotate(if (collapsed) -90f else 0f),
            color = "#FF000000"
        )
    }
}

/** React's TaskCard equivalent: brutalist shadow, source label, title, status badge. */
@Composable
private fun SharedTaskCard(
    task: TaskSummary,
    updating: Boolean,
    hasDraft: Boolean,
    captureLongPressAnchor: Boolean = true,
    timeContext: SlockTimeContext? = null,
    onClick: () -> Unit,
    onStatusBadgeClick: (SlockPopoverAnchor) -> Unit,
    onLongPress: (SlockPopoverAnchor) -> Unit
) {
    // Keep a live card coordinate so long-press conversion remains correct after scrolling.
    val rootAnchor =
        remember(task.id.value) { SlockLiveRootAnchorRef(identity = tasksStatusMenuOwnerIdentity(task.id.value)) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val description = task.description?.takeIf { it.isNotBlank() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .then(
                if (captureLongPressAnchor) {
                    Modifier.slockCaptureLiveRootAnchor(rootAnchor)
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 3.dp, y = 3.dp)
                .background(SlockColor.ShadowBlack)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .pointerInput(task.id.value, updating) {
                    // tap = open task; long-press = open status menu. Wrapping both in the
                    // same pointerInput keeps gesture semantics consistent and avoids
                    // clickable() swallowing the long-press.
                    detectTapGestures(
                        onTap = { if (!updating) currentOnClick() },
                        onLongPress = { localOffset ->
                            if (!updating) {
                                rootAnchor.resolve(SlockPopoverLocalPoint.Exact(localOffset))?.let(currentOnLongPress)
                            }
                        }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                task.tasksSourceLabel()?.takeIf { it.isNotBlank() }?.let { source ->
                    Text(
                        text = source,
                        style = SlockType.TabLabel.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                task.taskNumber?.let { taskNumber ->
                    Text(
                        text = "#$taskNumber",
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.35f),
                        maxLines = 1
                    )
                }
                if (hasDraft) {
                    SlockLucideIcon(
                        SlockLucideIconName.Pencil,
                        modifier = Modifier.size(13.dp),
                        color = SlockColor.TextMutedGrayArgb
                    )
                }
                Spacer(Modifier.weight(1f))
                task.updatedAtLabel.takeIf { it.isNotBlank() }?.let { updatedAt ->
                    Text(
                        text = timeContext?.formatTimestampLabel(updatedAt) ?: updatedAt,
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.35f),
                        maxLines = 1
                    )
                }
            }
            Text(
                text = task.tasksMeaningfulDisplayTitle(),
                style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            description?.let { desc ->
                Text(
                    text = desc,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val assigneeDisplayName = task.tasksAssigneeDisplayName()
                Text(
                    text = assigneeDisplayName?.let { "@$it" }
                        ?: stringResource(MR.strings.tasks_assignee_unassigned),
                    style = SlockType.Caption
                        .withSlockFontWeight(FontWeight.Bold)
                        .copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Use the same anchored Button(onClick2) model as thread meta chips so the
                // status pill reliably wins the tap instead of the parent card gesture.
                // Sentence-case label + trailing pencil mirror React's editable status
                // badge (InlineBadgeEditor with uppercase={false}).
                SlockAnchoredMetaChip(
                    label = if (updating) {
                        stringResource(MR.strings.tasks_state_updating)
                    } else {
                        task.status.localizedLabel()
                    },
                    iconName = null,
                    color = task.status.color,
                    onClick = if (updating) null else onStatusBadgeClick,
                    clickIdentity = tasksStatusChipClickIdentity(task.id.value, task.status),
                    anchorOwnerIdentity = tasksStatusMenuOwnerIdentity(task.id.value),
                    modifier = Modifier.widthIn(min = SharedTaskCardStatusChipMinWidth),
                    trailingIconName = SlockLucideIconName.Pencil.takeIf { !updating },
                    // react's card status badge (InlineBadgeEditor, text-xs/leading-4 +
                    // py-0.5 + border-2) measures a 24dp box / 20dp fill; 22dp min + 14sp
                    // line rendered ~22dp box / 18dp fill. 24dp min + 16sp line matches (#373).
                    minHeight = 24.dp,
                    horizontalPadding = 8.dp,
                    labelFontSize = 12.sp,
                    labelLineHeight = 16.sp,
                    borderWidth = SlockStroke.Default,
                    anchorPlacement = SlockAnchorPlacement.BottomEnd
                )
            }
        }
    }
}

internal fun tasksStatusChipClickIdentity(taskId: String, status: TaskStatus): String =
    "tasks-status:$taskId:${status.name}"

internal fun tasksStatusMenuOwnerIdentity(taskId: String): String = "task:$taskId"

@Composable
private fun SharedTasksStatusLabel(
    status: TaskStatus,
    label: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = Modifier
            .then(modifier)
            .background(status.color)
            // React's section-header status pill uses a 1px border; android's 2dp
            // read as "too black / heavier" vs react's thinner (lighter-looking)
            // line. Same black color, just match the 1px stroke (task #384).
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = (label ?: status.localizedLabel()).uppercase(),
            style = SlockType.TabLabel,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SharedTasksPillButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .padding(end = if (selected) 2.dp else 0.dp, bottom = if (selected) 2.dp else 0.dp)
                .widthIn(min = 44.dp)
                .heightIn(min = 30.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .matchParentSize()
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                    .border(SlockStroke.Default, if (enabled) SlockColor.BorderBlack else SlockColor.EmptyStateGray, RoundedCornerShape(SlockRadius.Xs))
                    .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label.uppercase(),
                    style = SlockType.SectionLabel,
                    color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SharedTasksSegmentedControl(
    selectedMode: TasksViewMode,
    onModeSelected: (TasksViewMode) -> Unit
) {
    SegmentedControl(
        selectedValue = selectedMode.name,
        options =
            listOf(
                SegmentedControlOption(value = TasksViewMode.Board.name, label = stringResource(MR.strings.tasks_view_board), iconName = SlockLucideIconName.Columns3),
                SegmentedControlOption(value = TasksViewMode.List.name, label = stringResource(MR.strings.tasks_view_list), iconName = SlockLucideIconName.LayoutList)
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
                .background(SlockColor.AccentRed.copy(alpha = 0.12f))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = title, style = SlockType.ButtonBold, color = SlockColor.TextBlack)
        Text(text = detail, style = SlockType.Caption, color = SlockColor.AccentRed)
    }
}

@Composable
private fun SharedTasksStateBody(
    title: String,
    detail: String?,
    loading: Boolean = false,
    iconName: SlockLucideIconName = SlockLucideIconName.SquareCheckBig,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    if (loading) {
        SlockCenteredLoading(message = title)
        return
    }
    SlockEmptyState(
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
            TaskStatus.Todo -> MR.strings.tasks_status_todo
            TaskStatus.InProgress -> MR.strings.tasks_status_in_progress
            TaskStatus.InReview -> MR.strings.tasks_status_in_review
            TaskStatus.Done -> MR.strings.tasks_status_done
            TaskStatus.Closed -> MR.strings.tasks_status_closed
        }
    )

private val TaskStatus.color: com.tencent.kuikly.compose.ui.graphics.Color
    get() =
        when (this) {
            TaskStatus.Todo -> SlockColor.AccentOrange
            TaskStatus.InProgress -> SlockColor.AccentCyan
            TaskStatus.InReview -> SlockColor.AccentLavender
            TaskStatus.Done -> SlockColor.AccentLime
            TaskStatus.Closed -> SlockColor.EmptyStateGray
        }

private val SharedTaskCardStatusChipMinWidth = 78.dp

private val TaskStatus.iconName: SlockLucideIconName
    get() =
        when (this) {
            TaskStatus.Todo -> SlockLucideIconName.Circle
            TaskStatus.InProgress -> SlockLucideIconName.Play
            TaskStatus.InReview -> SlockLucideIconName.Eye
            // Registry only ships "circle-check-big"; the smaller variant is not bundled.
            TaskStatus.Done -> SlockLucideIconName.CircleCheckBig
            TaskStatus.Closed -> SlockLucideIconName.Ban
        }

private fun TaskStatus.nextStatus(): TaskStatus =
    when (this) {
        TaskStatus.Todo -> TaskStatus.InProgress
        TaskStatus.InProgress -> TaskStatus.InReview
        TaskStatus.InReview -> TaskStatus.Done
        TaskStatus.Done -> TaskStatus.Closed
        TaskStatus.Closed -> TaskStatus.Todo
    }

private fun TaskSummary.tasksMeaningfulDisplayTitle(): String {
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
private fun TasksCreateDialog(
    refreshing: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (List<String>) -> Unit
) {
    var titles by remember { mutableStateOf(listOf("")) }
    val anyNonBlank = titles.any { it.isNotBlank() }
    Modal(onWillDismiss = { onDismiss() }) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            SlockModalDismissBarrier(
                overlayAlpha = 0.4f,
                onDismiss = onDismiss
            )
            Column(
                modifier = Modifier
                    .padding(SlockSpacing.Md)
                    .widthIn(min = 280.dp, max = 360.dp)
                    .dismissInputFocusOnUnconsumedPointerDown()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .slockFloatingContentClickTarget()
                    .padding(SlockSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Text(text = stringResource(MR.strings.tasks_label_new_tasks), style = SlockType.PageTitle, color = SlockColor.TextBlack)
                Text(
                    text = "Add one or more task titles. Empty rows are ignored.",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
                titles.forEachIndexed { index, value ->
                    TextField(
                        value = value,
                        onValueChange = { next ->
                            titles = titles.toMutableList().also { it[index] = next }
                        },
                        placeholder = stringResource(MR.strings.tasks_label_title),
                        placeholderColor = SlockColor.TextMutedGray,
                        autoFocus = index == 0,
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .background(SlockColor.AppBgLightBlue.copy(alpha = 0.6f))
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(horizontal = SlockSpacing.Sm, vertical = 6.dp)
                    )
                }
                SlockToolbarChip(
                    label = stringResource(MR.strings.tasks_action_add_another),
                    iconName = SlockLucideIconName.Plus,
                    active = false,
                    onClick = { titles = titles + "" }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    SlockToolbarChip(
                        label = stringResource(MR.strings.common_cancel),
                        iconName = null,
                        active = false,
                        onClick = onDismiss
                    )
                    SlockToolbarChip(
                        label = if (refreshing) "Creating\u2026" else "Create",
                        iconName = SlockLucideIconName.Check,
                        active = true,
                        enabled = anyNonBlank && !refreshing,
                        onClick = { onSubmit(titles) }
                    )
                }
            }
        }
    }
}
