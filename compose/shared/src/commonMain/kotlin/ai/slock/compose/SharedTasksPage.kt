package ai.slock.compose

import ai.slock.compose.components.SegmentedControl
import ai.slock.compose.components.SegmentedControlOption
import ai.slock.compose.components.SlockAnchoredMetaChip
import ai.slock.compose.components.SlockAnchoredToolbarChip
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockToolbarChip
import ai.slock.compose.data.KeyValueTasksViewPreferenceRepository
import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.SharedTasksRouteIntent
import ai.slock.compose.data.TasksContent
import ai.slock.compose.data.TasksPresentationIntent
import ai.slock.compose.data.TasksPresentationState
import ai.slock.compose.data.TasksUiState
import ai.slock.compose.data.TasksVisibleContent
import ai.slock.compose.data.TasksViewMode
import ai.slock.compose.data.kuiklySharedPreferenceStore
import ai.slock.compose.data.boardColumns
import ai.slock.compose.data.reduce
import ai.slock.compose.data.tasksAssigneeDisplayName
import ai.slock.compose.data.tasksDisplayTitle
import ai.slock.compose.data.tasksIdentityFilterId
import ai.slock.compose.data.tasksSourceLabel
import ai.slock.compose.data.visibleContent
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.pager.Pager

@Composable
internal fun SharedTasksScreen(
    state: TasksUiState,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenTask: ((TaskSummary) -> Unit)? = null
) {
    SharedTasksPanel(
        state = state,
        onIntent = onIntent,
        modifier = modifier,
        showHeader = true,
        showChannelFilter = true,
        onOpenTask = onOpenTask
    )
}

@Composable
internal fun SharedTasksPanel(
    state: TasksUiState,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    showChannelFilter: Boolean = true,
    /** When non-null, render the New Task button and dispatch [SharedTasksRouteIntent.CreateTasks] against this channel.
     *  Server-mode (no implicit channel) leaves this null and the button is hidden. */
    createTasksChannelId: ChannelId? = null,
    onOpenTask: ((TaskSummary) -> Unit)? = null
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
            .background(SlockColor.SurfaceWhite)
    ) {
        when (val loadState = displayState) {
            ScreenLoadState.Loading -> {
                if (showHeader) {
                    SharedTasksHeader()
                }
                SharedTasksStateBody(
                    title = "Loading...",
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
                    onIntent = onIntent
                )
            is ScreenLoadState.Empty -> {
                if (showHeader) {
                    SharedTasksHeader()
                }
                val emptyCopy =
                    sharedTasksEmptyStateCopy(
                        hasAnyFilter = state.selectedFilter != null,
                        isChannelMode = createTasksChannelId != null
                    )
                SharedTasksStateBody(
                    title = emptyCopy.title,
                    detail = emptyCopy.description,
                    iconName = SlockLucideIconName.SquareCheckBig
                )
            }
            is ScreenLoadState.Error -> {
                if (showHeader) {
                    SharedTasksHeader()
                }
                SharedTasksStateBody(
                    title = "Could not load tasks",
                    detail = loadState.error.code,
                    actionLabel = "Retry",
                    onAction = { onIntent(SharedTasksRouteIntent.Refresh) }
                )
            }
            is ScreenLoadState.NoPermission -> {
                if (showHeader) {
                    SharedTasksHeader()
                }
                SharedTasksStateBody(
                    title = "Tasks unavailable",
                    detail = loadState.blocker.requiredCapability ?: loadState.blocker.code
                )
            }
        }
    }
}

@Composable
private fun SharedTasksHeader(subtitle: String = "Channel tasks") {
    SlockTopPageHeader(
        title = "Tasks",
        subtitle = subtitle,
        iconName = SlockLucideIconName.SquareCheckBig
    )
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
    onIntent: (SharedTasksRouteIntent) -> Unit
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
            defaultViewMode = defaultTasksViewMode(maxWidth)
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
    defaultViewMode: TasksViewMode
) {
    val pager = LocalActivity.current.getPager() as Pager
    val viewModeRepository =
        remember(pager) {
            KeyValueTasksViewPreferenceRepository(kuiklySharedPreferenceStore(pager))
        }
    val initialPresentationState =
        remember(viewModeRepository, defaultViewMode) {
            TasksPresentationState.Default.copy(
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
    // Drop stale chip selections when the underlying option pool changes (e.g. a server
    // refresh removed a channel that was checked in the popover).
    LaunchedEffect(visibleContent.channelOptions) {
        val next =
            presentationState.reduce(
                TasksPresentationIntent.PruneUnavailableChannels(visibleContent.channelOptions.toSet())
            )
        if (next != presentationState) presentationState = next
    }
    LaunchedEffect(visibleContent.creatorOptions) {
        val next =
            presentationState.reduce(
                TasksPresentationIntent.PruneUnavailableCreators(
                    visibleContent.creatorOptions.map { it.tasksIdentityFilterId() }.toSet()
                )
            )
        if (next != presentationState) presentationState = next
    }
    LaunchedEffect(visibleContent.assigneeOptions) {
        val next =
            presentationState.reduce(
                TasksPresentationIntent.PruneUnavailableAssignees(
                    visibleContent.assigneeOptions.map { it.id.value }.toSet()
                )
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
    val listRows = remember(listColumns, collapsedStatuses) {
        listColumns.flatMap { column ->
            buildList {
                add(SharedTasksListRow.Header(column.status, column.totalCount, column.status in collapsedStatuses))
                if (column.status !in collapsedStatuses) {
                    column.tasks.forEach { task ->
                        add(SharedTasksListRow.Card(column.status, task))
                    }
                }
            }
        }
    }

    // Per-card status menu coordinates. We open the single-select menu when the user
    // long-presses a card; storing the anchor in screen-root space lets the overlay
    // place itself near the press point without forcing a recomposition just to track
    // a state change in the trigger composable.
    var statusMenuTask by remember { mutableStateOf<TaskSummary?>(null) }
    var statusMenuAnchor by remember { mutableStateOf(Offset.Zero) }

    // Filter popover anchors. Captured via onGloballyPositioned on the chip
    // so the popover floats just below it.
    var channelAnchor by remember { mutableStateOf(Offset.Zero) }
    var creatorAnchor by remember { mutableStateOf(Offset.Zero) }
    var assigneeAnchor by remember { mutableStateOf(Offset.Zero) }
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
                    tasksHeaderSubtitle(
                        totalTasks = content.tasks.size,
                        visibleTasks = visibleContent.filteredTasks.size
                    )
            )
        }
        Box(modifier = Modifier.weight(1f).background(SlockColor.SurfaceWhite)) {
            Column(modifier = Modifier.fillMaxSize()) {
                state.navigationErrorCode?.let { code ->
                    SharedTasksNotice("Action unavailable", code, modifier = Modifier.padding(SlockSpacing.Md))
                }
                state.statusUpdateErrorCode?.let { code ->
                    SharedTasksNotice("Could not update status", code, modifier = Modifier.padding(SlockSpacing.Md))
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
                                    onStatusBadgeClick = { task, anchor ->
                                        statusMenuTask = task
                                        statusMenuAnchor = anchor
                                    },
                                    onLongPressTask = { task, anchor ->
                                        statusMenuTask = task
                                        statusMenuAnchor = anchor
                                    }
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
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(SlockSpacing.Md),
                                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
                            ) {
                                items(listRows.size, key = { i -> listRows[i].key }) { index ->
                                    when (val row = listRows[index]) {
                                        is SharedTasksListRow.Header ->
                                            BoardSectionHeader(
                                                status = row.status,
                                                count = row.count,
                                                collapsed = row.collapsed,
                                                onClick = {
                                                    presentationState =
                                                        presentationState.reduce(
                                                            TasksPresentationIntent.ToggleStatusCollapsed(row.status)
                                                        )
                                                }
                                            )
                                        is SharedTasksListRow.Card ->
                                            SharedTaskCard(
                                                task = row.task,
                                                updating = state.statusUpdatingTaskId == row.task.id.value,
                                                onClick = { onOpenTask?.invoke(row.task) },
                                                onStatusBadgeClick = { anchor ->
                                                    statusMenuTask = row.task
                                                    statusMenuAnchor = anchor
                                                },
                                                onLongPress = { anchor ->
                                                    statusMenuTask = row.task
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

        // --- Overlays. Rendered in z-order: popovers, then status menu, then create dialog.
        // Each overlay short-circuits on null state so unmounting tears down the gesture
        // capture overlay too (otherwise the dim layer would swallow taps).
        when (presentationState.openFilterId) {
            "channel" ->
                SlockFilterPopover(
                    anchor = channelAnchor,
                    title = "Filter by channel",
                    searchPlaceholder = "Search channels",
                    searchText = presentationState.channelSearch,
                    onSearchChange = { presentationState = presentationState.reduce(TasksPresentationIntent.SetChannelSearch(it)) },
                    options = channelFilterOptions,
                    selectedIds = visibleContent.selectedChannelNames,
                    onToggle = { id -> presentationState = presentationState.reduce(TasksPresentationIntent.ToggleChannel(id)) },
                    onClear = { presentationState = presentationState.reduce(TasksPresentationIntent.ClearChannels) },
                    onDismiss = { presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null)) }
                )
            "creator" ->
                SlockFilterPopover(
                    anchor = creatorAnchor,
                    title = "Filter by creator",
                    searchPlaceholder = "Search creators",
                    searchText = presentationState.creatorSearch,
                    onSearchChange = { presentationState = presentationState.reduce(TasksPresentationIntent.SetCreatorSearch(it)) },
                    options = creatorFilterOptions,
                    selectedIds = visibleContent.selectedCreatorIds,
                    onToggle = { id -> presentationState = presentationState.reduce(TasksPresentationIntent.ToggleCreator(id)) },
                    onClear = { presentationState = presentationState.reduce(TasksPresentationIntent.ClearCreators) },
                    onDismiss = { presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null)) }
                )
            "assignee" ->
                SlockFilterPopover(
                    anchor = assigneeAnchor,
                    title = "Filter by assignee",
                    searchPlaceholder = "Search assignees",
                    searchText = presentationState.assigneeSearch,
                    onSearchChange = { presentationState = presentationState.reduce(TasksPresentationIntent.SetAssigneeSearch(it)) },
                    options = assigneeFilterOptions,
                    selectedIds = visibleContent.selectedAssigneeIds,
                    onToggle = { id -> presentationState = presentationState.reduce(TasksPresentationIntent.ToggleAssignee(id)) },
                    onClear = { presentationState = presentationState.reduce(TasksPresentationIntent.ClearAssignees) },
                    onDismiss = { presentationState = presentationState.reduce(TasksPresentationIntent.SetOpenFilter(null)) }
                )
            else -> Unit
        }

        statusMenuTask?.let { task ->
            SlockBaseMenuOverlay(
                anchor = statusMenuAnchor,
                items = TaskStatus.values().map { next ->
                    SlockBaseMenuItem(id = next.name, label = next.label)
                },
                selectedId = task.status.name,
                uppercaseLabels = false,
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

private fun defaultTasksViewMode(maxWidth: Dp): TasksViewMode =
    if (maxWidth < 768.dp) TasksViewMode.List else TasksViewMode.Board

private sealed interface SharedTasksListRow {
    val key: String

    data class Header(
        val status: TaskStatus,
        val count: Int,
        val collapsed: Boolean
    ) : SharedTasksListRow {
        override val key: String = "list-header-${status.name}"
    }

    data class Card(
        val status: TaskStatus,
        val task: TaskSummary
    ) : SharedTasksListRow {
        override val key: String = "list-card-${status.name}-${task.id.value}"
    }
}

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
    onChannelChipClick: (Offset) -> Unit,
    onCreatorChipClick: (Offset) -> Unit,
    onAssigneeChipClick: (Offset) -> Unit,
    onClearAll: () -> Unit,
    onCreateClick: () -> Unit
) {
    val anyFilterActive = selectedChannels.isNotEmpty() || selectedCreatorIds.isNotEmpty() || selectedAssigneeIds.isNotEmpty()
    val channelChip: @Composable () -> Unit = {
        SlockAnchoredToolbarChip(
            label = if (selectedChannels.isEmpty()) "Channel" else "Channel · ${selectedChannels.size}",
            iconName = SlockLucideIconName.Hash,
            active = openFilterId == "channel" || selectedChannels.isNotEmpty(),
            enabled = channelOptions.isNotEmpty(),
            borderColor = SlockColor.EmptyStateGray,
            onClick = onChannelChipClick
        )
    }
    val creatorChip: @Composable () -> Unit = {
        SlockAnchoredToolbarChip(
            label = if (selectedCreatorIds.isEmpty()) "Creator" else "Creator · ${selectedCreatorIds.size}",
            iconName = SlockLucideIconName.CircleUser,
            active = openFilterId == "creator" || selectedCreatorIds.isNotEmpty(),
            enabled = creatorOptions.isNotEmpty(),
            borderColor = SlockColor.EmptyStateGray,
            onClick = onCreatorChipClick
        )
    }
    val assigneeChip: @Composable () -> Unit = {
        SlockAnchoredToolbarChip(
            label = if (selectedAssigneeIds.isEmpty()) "Assignee" else "Assignee · ${selectedAssigneeIds.size}",
            iconName = SlockLucideIconName.User,
            active = openFilterId == "assignee" || selectedAssigneeIds.isNotEmpty(),
            enabled = assigneeOptions.isNotEmpty(),
            borderColor = SlockColor.EmptyStateGray,
            onClick = onAssigneeChipClick
        )
    }
    val clearAllChip: @Composable () -> Unit = {
        SlockToolbarChip(
            label = "Clear all",
            iconName = SlockLucideIconName.X,
            active = false,
            onClick = onClearAll
        )
    }
    val newTaskButton: @Composable () -> Unit = {
        SlockBrutalActionButton(
            label = "NEW TASK",
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
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val rows =
                    tasksToolbarRows(
                        maxWidth = maxWidth,
                        showChannelFilter = showChannelFilter,
                        showClearAll = anyFilterActive,
                        showCreateButton = showCreateButton
                    )
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            if (row.size == 1 && row.first() == TasksToolbarControl.ViewSwitch) {
                                Arrangement.End
                            } else {
                                Arrangement.spacedBy(SlockSpacing.Sm)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        row.forEachIndexed { index, control ->
                            if (control == TasksToolbarControl.ViewSwitch && index > 0) {
                                Spacer(Modifier.weight(1f))
                            }
                            when (control) {
                                TasksToolbarControl.ChannelFilter -> channelChip()
                                TasksToolbarControl.CreatorFilter -> creatorChip()
                                TasksToolbarControl.AssigneeFilter -> assigneeChip()
                                TasksToolbarControl.ClearAll -> clearAllChip()
                                TasksToolbarControl.NewTask -> newTaskButton()
                                TasksToolbarControl.ViewSwitch -> viewSwitch()
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

internal enum class TasksToolbarControl {
    ChannelFilter,
    CreatorFilter,
    AssigneeFilter,
    ClearAll,
    NewTask,
    ViewSwitch
}

internal fun tasksToolbarRows(
    maxWidth: Dp,
    showChannelFilter: Boolean,
    showClearAll: Boolean,
    showCreateButton: Boolean
): List<List<TasksToolbarControl>> {
    val items =
        buildList {
            if (showChannelFilter) add(TasksToolbarControl.ChannelFilter)
            add(TasksToolbarControl.CreatorFilter)
            add(TasksToolbarControl.AssigneeFilter)
            if (showClearAll) add(TasksToolbarControl.ClearAll)
            if (showCreateButton) add(TasksToolbarControl.NewTask)
            add(TasksToolbarControl.ViewSwitch)
        }
    val availableWidth = if (maxWidth < 0.dp) 0.dp else maxWidth
    val rows = mutableListOf<List<TasksToolbarControl>>()
    var currentRow = mutableListOf<TasksToolbarControl>()
    var currentWidth = 0.dp
    items.forEach { item ->
        val nextWidth =
            if (currentRow.isEmpty()) {
                item.estimatedTasksToolbarWidth
            } else {
                currentWidth + SlockSpacing.Sm + item.estimatedTasksToolbarWidth
            }
        if (currentRow.isNotEmpty() && nextWidth > availableWidth) {
            rows += currentRow.toList()
            currentRow = mutableListOf()
            currentWidth = 0.dp
        }
        currentRow += item
        currentWidth =
            if (currentWidth == 0.dp) {
                item.estimatedTasksToolbarWidth
            } else {
                currentWidth + SlockSpacing.Sm + item.estimatedTasksToolbarWidth
            }
    }
    if (currentRow.isNotEmpty()) {
        rows += currentRow.toList()
    }
    return rows
}

private val TasksToolbarControl.estimatedTasksToolbarWidth: Dp
    get() =
        when (this) {
            TasksToolbarControl.ChannelFilter -> 96.dp
            TasksToolbarControl.CreatorFilter -> 94.dp
            TasksToolbarControl.AssigneeFilter -> 100.dp
            TasksToolbarControl.ClearAll -> 96.dp
            TasksToolbarControl.NewTask -> 104.dp
            TasksToolbarControl.ViewSwitch -> 132.dp
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
    onStatusBadgeClick: (TaskSummary, Offset) -> Unit,
    onLongPressTask: (TaskSummary, Offset) -> Unit
) {
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
                    text = "No ${status.label.lowercase()} tasks",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            } else {
                // Nested LazyColumn inside the outer LazyRow lets each column scroll its
                // own cards independently — mirrors the React board's per-column overflow.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tasks.size, key = { i -> "board-task-${tasks[i].id.value}" }) { index ->
                        val task = tasks[index]
                        SharedTaskCard(
                            task = task,
                            updating = updatingTaskId == task.id.value,
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
        // Rotate chevron to encode collapse direction; mirrors the React `rotate-[-90deg]`.
        SlockLucideIcon(
            "chevron-down",
            modifier = Modifier.size(14.dp).rotate(if (collapsed) -90f else 0f),
            color = "#FF000000"
        )
        SharedTasksStatusLabel(status)
        Text(
            text = count.toString(),
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
            color = SlockColor.TextBlack.copy(alpha = 0.5f),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/** React's TaskCard equivalent: brutalist shadow, source label, title, status badge. */
@Composable
private fun SharedTaskCard(
    task: TaskSummary,
    updating: Boolean,
    onClick: () -> Unit,
    onStatusBadgeClick: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit
) {
    // Capture the card root so a long-press offset reported in card-local coords can be
    // converted to root coords for the overlay menu anchor.
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    val description: String? = null // TaskSummary doesn't carry a description today; placeholder for forward-compat.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .onGloballyPositioned { rootPos = it.positionInRoot() }
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
                        onTap = { if (!updating) onClick() },
                        onLongPress = { localOffset ->
                            if (!updating) onLongPress(Offset(rootPos.x + localOffset.x, rootPos.y + localOffset.y))
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
                        style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.35f),
                        maxLines = 1
                    )
                }
                Spacer(Modifier.weight(1f))
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
                Text(
                    text = task.tasksAssigneeDisplayName() ?: "Unassigned",
                    style = SlockType.Caption,
                    color = SlockColor.TextBlack.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Use the same anchored Button(onClick2) model as thread meta chips so the
                // status pill reliably wins the tap instead of the parent card gesture.
                SlockAnchoredMetaChip(
                    label = if (updating) "Updating" else task.status.label.uppercase(),
                    iconName = task.status.iconName,
                    color = task.status.color,
                    onClick = if (updating) null else onStatusBadgeClick
                )
            }
        }
    }
}

@Composable
private fun SharedTasksStatusLabel(
    status: TaskStatus,
    label: String = status.label,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = Modifier
            .then(modifier)
            .background(status.color)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SlockLucideIcon(status.iconName, modifier = Modifier.size(10.dp), color = "#FF000000")
        Text(
            text = label.uppercase(),
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
                SegmentedControlOption(value = TasksViewMode.Board.name, label = "Board", iconName = SlockLucideIconName.Columns3),
                SegmentedControlOption(value = TasksViewMode.List.name, label = "List", iconName = SlockLucideIconName.LayoutList)
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

private val TaskStatus.label: String
    get() =
        when (this) {
            TaskStatus.Todo -> "Todo"
            TaskStatus.InProgress -> "In Progress"
            TaskStatus.InReview -> "In Review"
            TaskStatus.Done -> "Done"
            TaskStatus.Closed -> "Closed"
        }

private val TaskStatus.shortLabel: String
    get() =
        when (this) {
            TaskStatus.Todo -> "Todo"
            TaskStatus.InProgress -> "Doing"
            TaskStatus.InReview -> "Review"
            TaskStatus.Done -> "Done"
            TaskStatus.Closed -> "Closed"
        }

private val TaskStatus.color: com.tencent.kuikly.compose.ui.graphics.Color
    get() =
        when (this) {
            TaskStatus.Todo -> SlockColor.AccentOrange
            TaskStatus.InProgress -> SlockColor.AccentCyan
            TaskStatus.InReview -> SlockColor.AccentLavender
            TaskStatus.Done -> SlockColor.AccentLime
            TaskStatus.Closed -> SlockColor.EmptyStateGray
        }

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

private fun List<TaskSummary>.sortedByTaskDisplayOrder(): List<TaskSummary> =
    sortedWith(
        compareByDescending<TaskSummary> { it.updatedAtLabel.toTaskSortValue() }
            .thenByDescending { it.taskNumber ?: Int.MIN_VALUE }
            .thenBy { it.title.lowercase() }
    )

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
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.ShadowBlack.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .padding(SlockSpacing.Md)
                    .widthIn(min = 280.dp, max = 360.dp)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable(enabled = false, onClick = {})
                    .padding(SlockSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Text(text = "New tasks", style = SlockType.PageTitle, color = SlockColor.TextBlack)
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
                        placeholder = "Task title",
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
                    label = "Add another",
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
                        label = "Cancel",
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
