package ai.slock.compose

import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.SharedTasksRouteIntent
import ai.slock.compose.data.TasksContent
import ai.slock.compose.data.TasksPresentationIntent
import ai.slock.compose.data.TasksPresentationState
import ai.slock.compose.data.TasksUiState
import ai.slock.compose.data.TasksViewMode
import ai.slock.compose.data.boardColumns
import ai.slock.compose.data.reduce
import ai.slock.compose.data.tasksAssigneeDisplayName
import ai.slock.compose.data.tasksDisplayTitle
import ai.slock.compose.data.tasksSourceLabel
import ai.slock.compose.data.visibleContent
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
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
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
import com.tencent.kuikly.compose.foundation.lazy.items
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

@Composable
internal fun SharedTasksScreen(
    state: TasksUiState,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    SharedTasksPanel(
        state = state,
        onIntent = onIntent,
        modifier = modifier,
        showHeader = true,
        showChannelFilter = true
    )
}

@Composable
internal fun SharedTasksPanel(
    state: TasksUiState,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    showChannelFilter: Boolean = true,
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
        if (showHeader) {
            SharedTasksHeader(
                refreshing = state.refreshing,
                onRefresh = { onIntent(SharedTasksRouteIntent.Refresh) }
            )
        }
        when (val loadState = displayState) {
            ScreenLoadState.Loading ->
                SharedTasksStateBody(
                    title = "Loading tasks",
                    detail = "Fetching shared tasks from the selected server.",
                    loading = true
                )
            is ScreenLoadState.Content ->
                SharedTasksLoadedBody(
                    state = state,
                    content = loadState.data,
                    showChannelFilter = showChannelFilter,
                    onOpenTask = onOpenTask,
                    onIntent = onIntent
                )
            is ScreenLoadState.Empty ->
                SharedTasksStateBody(
                    title = "No tasks",
                    detail = loadState.reason.name
                )
            is ScreenLoadState.Error ->
                SharedTasksStateBody(
                    title = "Could not load tasks",
                    detail = loadState.error.code,
                    actionLabel = "Retry",
                    onAction = { onIntent(SharedTasksRouteIntent.Refresh) }
                )
            is ScreenLoadState.NoPermission ->
                SharedTasksStateBody(
                    title = "Tasks unavailable",
                    detail = loadState.blocker.requiredCapability ?: loadState.blocker.code
                )
        }
    }
}

@Composable
private fun SharedTasksHeader(
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SlockSize.PageHeaderHeight)
                .background(SlockColor.BrandYellow)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 20.dp, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Tasks",
                style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (refreshing) "Syncing channel tasks" else "Channel tasks",
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        SharedTasksFilterChip(
            label = if (refreshing) "Syncing" else "Refresh",
            iconName = "refresh-cw",
            active = refreshing,
            enabled = !refreshing,
            onClick = onRefresh
        )
    }
}

@Composable
private fun SharedTasksLoadedBody(
    state: TasksUiState,
    content: TasksContent,
    showChannelFilter: Boolean,
    onOpenTask: ((TaskSummary) -> Unit)?,
    onIntent: (SharedTasksRouteIntent) -> Unit
) {
    var presentationState by remember { mutableStateOf(TasksPresentationState()) }
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
    LaunchedEffect(visibleContent.channelOptions) {
        val next =
            presentationState.reduce(
                TasksPresentationIntent.PruneUnavailableChannels(visibleContent.channelOptions.toSet())
            )
        if (next != presentationState) {
            presentationState = next
        }
    }
    val boardColumns = visibleContent.filteredContent.boardColumns(renderLimit = 8)
    val listColumns = visibleContent.filteredContent.boardColumns(renderLimit = 0).filter { it.totalCount > 0 }
    val lazyItemCount =
        sharedTasksLazyItemCount(
            mode = presentationState.selectedMode,
            filteredTasksCount = visibleContent.filteredTasks.size,
            boardColumnCount = boardColumns.size,
            hasNavigationError = state.navigationErrorCode != null,
            hasStatusUpdateError = state.statusUpdateErrorCode != null
        )

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        beyondBoundsItemCount = sharedListBeyondBoundsItemCount(lazyItemCount)
    ) {
        state.navigationErrorCode?.let { code ->
            item(key = "nav-error") {
                SharedTasksNotice("Action unavailable", code, modifier = Modifier.padding(SlockSpacing.Md))
            }
        }
        state.statusUpdateErrorCode?.let { code ->
            item(key = "status-error") {
                SharedTasksNotice("Could not update status", code, modifier = Modifier.padding(SlockSpacing.Md))
            }
        }
        item(key = "filters") {
            SharedTasksToolbar(
                state = state,
                summaryCounts = visibleContent.channelScopedContent.summaryCounts,
                totalTasks = content.tasks.size,
                visibleTasks = visibleContent.filteredTasks.size,
                channels = visibleContent.channelOptions,
                selectedChannels = visibleContent.selectedChannelNames,
                selectedMode = presentationState.selectedMode,
                showChannelFilter = showChannelFilter,
                onFilterSelected = { status -> onIntent(SharedTasksRouteIntent.SelectFilter(status)) },
                onChannelToggled = { channel ->
                    presentationState = presentationState.reduce(TasksPresentationIntent.ToggleChannel(channel))
                },
                onClearChannels = {
                    presentationState = presentationState.reduce(TasksPresentationIntent.ClearChannels)
                },
                onModeSelected = { mode ->
                    presentationState = presentationState.reduce(TasksPresentationIntent.SelectMode(mode))
                }
            )
        }
        when (presentationState.selectedMode) {
            TasksViewMode.Board -> {
                items(boardColumns.size, key = { index -> "board-${boardColumns[index].status}" }) { index ->
                    val column = boardColumns[index]
                    SharedTasksBoardColumn(
                        status = column.status,
                        totalCount = column.totalCount,
                        tasks = column.tasks,
                        updatingTaskId = state.statusUpdatingTaskId,
                        onStatusChange = { task, status -> onIntent(SharedTasksRouteIntent.UpdateStatus(task, status)) },
                        onOpenTask = onOpenTask,
                        modifier = Modifier.padding(horizontal = SlockSpacing.Md, vertical = if (index == 0) SlockSpacing.Md else 0.dp).padding(bottom = SlockSpacing.Md)
                    )
                }
            }
            TasksViewMode.List -> {
                if (visibleContent.filteredTasks.isEmpty()) {
                    item(key = "list-empty") {
                        SharedTasksStateBody(
                            title = "No matching tasks",
                            detail = "Try another status or channel filter."
                        )
                    }
                } else {
                    items(listColumns.size, key = { index -> "list-section-${listColumns[index].status}" }) { index ->
                        val column = listColumns[index]
                        SharedTasksListSection(
                            status = column.status,
                            tasks = column.tasks,
                            updatingTaskId = state.statusUpdatingTaskId,
                            onStatusChange = { task, status -> onIntent(SharedTasksRouteIntent.UpdateStatus(task, status)) },
                            onOpenTask = onOpenTask,
                            modifier = Modifier.padding(horizontal = SlockSpacing.Md, vertical = if (index == 0) SlockSpacing.Md else 0.dp).padding(bottom = SlockSpacing.Md)
                        )
                    }
                }
            }
        }
        item(key = "bottom-space") {
            Spacer(modifier = Modifier.height(SlockSpacing.Md))
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
    val fixedItems =
        1 + // filters
            1 // bottom spacer
    val errorItems = (if (hasNavigationError) 1 else 0) + (if (hasStatusUpdateError) 1 else 0)
    val bodyItems =
        when (mode) {
            TasksViewMode.Board -> boardColumnCount
            TasksViewMode.List -> filteredTasksCount.takeIf { it > 0 } ?: 1
        }
    return fixedItems + errorItems + bodyItems
}

@Composable
private fun SharedTasksToolbar(
    state: TasksUiState,
    summaryCounts: Map<TaskStatus, Int>,
    totalTasks: Int,
    visibleTasks: Int,
    channels: List<String>,
    selectedChannels: Set<String>,
    selectedMode: TasksViewMode,
    showChannelFilter: Boolean,
    onFilterSelected: (TaskStatus?) -> Unit,
    onChannelToggled: (String) -> Unit,
    onClearChannels: () -> Unit,
    onModeSelected: (TasksViewMode) -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = SlockSpacing.Md, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = "$visibleTasks${if (visibleTasks != totalTasks) " of $totalTasks" else ""} channel tasks",
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SharedTasksSegmentedControl(
                selectedMode = selectedMode,
                onModeSelected = onModeSelected
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
            SharedTasksFilterChip("All", iconName = "list-filter", active = state.selectedFilter == null, onClick = { onFilterSelected(null) })
            state.availableFilters.take(3).forEach { status ->
                SharedTasksFilterChip(
                    label = status.shortLabel,
                    active = state.selectedFilter == status,
                    onClick = { onFilterSelected(status) },
                    count = summaryCounts[status]
                )
            }
        }
        state.availableFilters.drop(3).takeIf { it.isNotEmpty() }?.let { remainingFilters ->
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
                remainingFilters.forEach { status ->
                    SharedTasksFilterChip(
                        label = status.shortLabel,
                        active = state.selectedFilter == status,
                        onClick = { onFilterSelected(status) },
                        count = summaryCounts[status]
                    )
                }
            }
        }
        if (showChannelFilter && channels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
                SharedTasksFilterChip(
                    label = "Channel",
                    iconName = "hash",
                    active = selectedChannels.isNotEmpty(),
                    count = selectedChannels.size.takeIf { it > 0 },
                    onClick = {
                        if (selectedChannels.isEmpty()) {
                            channels.firstOrNull()?.let(onChannelToggled)
                        } else {
                            onClearChannels()
                        }
                    }
                )
                channels.take(3).forEach { channel ->
                    SharedTasksChannelFilterRow(
                        channel = channel,
                        selected = channel in selectedChannels,
                        onClick = { onChannelToggled(channel) }
                    )
                }
                if (selectedChannels.isNotEmpty()) {
                    SharedTasksFilterChip("Clear all", active = false, onClick = onClearChannels)
                }
            }
        }
    }
}

@Composable
private fun SharedTasksChannelFilterRow(
    channel: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp)
                .background(if (selected) SlockColor.AccentCyan else SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, if (selected) SlockColor.BorderBlack else SlockColor.BorderBlack.copy(alpha = 0.3f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SlockLucideIcon("hash", modifier = Modifier.size(14.dp), color = "#FF000000")
        Text(
            text = channel,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = if (selected) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SharedTasksBoardColumn(
    status: TaskStatus,
    totalCount: Int,
    tasks: List<TaskSummary>,
    updatingTaskId: String?,
    onStatusChange: (TaskSummary, TaskStatus) -> Unit,
    onOpenTask: ((TaskSummary) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.2f))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm), modifier = Modifier.fillMaxWidth()) {
            SlockLucideIcon("chevron-down", modifier = Modifier.size(14.dp))
            SharedTasksStatusLabel(status)
            Text(
                text = totalCount.toString(),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        if (tasks.isEmpty()) {
            Text(
                text = "No ${status.label.lowercase()} tasks",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        } else {
            tasks.forEach { task ->
                SharedTaskRow(
                    task = task,
                    updating = updatingTaskId == task.id.value,
                    onOpenTask = onOpenTask?.let { opener -> { opener(task) } },
                    onStatusChange = { nextStatus -> onStatusChange(task, nextStatus) }
                )
            }
        }
    }
}

@Composable
private fun SharedTasksListSection(
    status: TaskStatus,
    tasks: List<TaskSummary>,
    updatingTaskId: String?,
    onStatusChange: (TaskSummary, TaskStatus) -> Unit,
    onOpenTask: ((TaskSummary) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            SharedTasksStatusLabel(status)
            Text(
                text = tasks.size.toString(),
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
                color = SlockColor.TextBlack.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f)
            )
            SlockLucideIcon("chevron-down", modifier = Modifier.size(14.dp), color = "#80000000")
        }
        tasks.forEach { task ->
            SharedTaskRow(
                task = task,
                updating = updatingTaskId == task.id.value,
                onOpenTask = onOpenTask?.let { opener -> { opener(task) } },
                onStatusChange = { nextStatus -> onStatusChange(task, nextStatus) }
            )
        }
    }
}

@Composable
private fun SharedTaskRow(
    task: TaskSummary,
    updating: Boolean,
    onOpenTask: (() -> Unit)? = null,
    onStatusChange: (TaskStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 3.dp, bottom = 3.dp)
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
                    .clickable { onOpenTask?.invoke() ?: onStatusChange(task.status.nextStatus()) }
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
                            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                            color = SlockColor.TextBlack.copy(alpha = 0.6f),
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
                }
                Text(
                    text = task.tasksMeaningfulDisplayTitle(),
                    style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.tasksAssigneeDisplayName() ?: "Unassigned",
                        style = SlockType.Caption,
                        color = SlockColor.TextBlack.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    task.updatedAtLabel.takeIf { it.isNotBlank() }?.let { updatedAt ->
                        Text(
                            text = updatedAt,
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                    SharedTasksStatusLabel(
                        status = task.status,
                        label = if (updating) "Updating" else task.status.label,
                        modifier = Modifier.clickable(enabled = !updating) { onStatusChange(task.status.nextStatus()) }
                    )
                }
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
            style = SlockType.SectionLabel.copy(fontSize = 10.sp, lineHeight = 12.sp),
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
                .heightIn(min = 30.dp)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        }
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

@Composable
private fun SharedTasksFilterChip(
    label: String,
    iconName: String? = null,
    active: Boolean,
    enabled: Boolean = true,
    count: Int? = null,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .heightIn(min = 30.dp)
                .background(if (active) SlockColor.AccentCyan else SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, if (enabled) SlockColor.BorderBlack else SlockColor.EmptyStateGray)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        iconName?.let {
            SlockLucideIcon(it, modifier = Modifier.size(14.dp), color = "#FF000000")
        }
        Text(
            text = label.uppercase(),
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp),
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        count?.let {
            Text(
                text = it.toString(),
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 10.sp),
                color = SlockColor.TextBlack,
                modifier =
                    Modifier
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                        .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SharedTasksSegmentedControl(
    selectedMode: TasksViewMode,
    onModeSelected: (TasksViewMode) -> Unit
) {
    Row(
        modifier = Modifier.border(SlockStroke.Default, SlockColor.BorderBlack),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SharedTasksSegmentButton(
            label = "Board",
            iconName = "columns-3",
            selected = selectedMode == TasksViewMode.Board,
            onClick = { onModeSelected(TasksViewMode.Board) }
        )
        Box(Modifier.width(SlockStroke.Default).height(30.dp).background(SlockColor.BorderBlack))
        SharedTasksSegmentButton(
            label = "List",
            iconName = "list",
            selected = selectedMode == TasksViewMode.List,
            onClick = { onModeSelected(TasksViewMode.List) }
        )
    }
}

@Composable
private fun SharedTasksSegmentButton(
    label: String,
    iconName: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .heightIn(min = 30.dp)
                .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = SlockSpacing.Sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(12.dp), color = "#FF000000")
        Text(
            text = label.uppercase(),
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
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
    detail: String,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(if (loading) SlockColor.AppBgLightBlue else SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(SlockSpacing.Md)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                Text(text = title, style = SlockType.PageTitle, color = SlockColor.TextBlack)
                Text(text = detail, style = SlockType.Body, color = SlockColor.TextMutedGray)
            }
        }
        if (actionLabel != null && onAction != null) {
            SharedTasksPillButton(label = actionLabel, selected = false, onClick = onAction)
        }
    }
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

private val TaskStatus.iconName: String
    get() =
        when (this) {
            TaskStatus.Todo -> "circle"
            TaskStatus.InProgress -> "play"
            TaskStatus.InReview -> "eye"
            TaskStatus.Done -> "circle-check"
            TaskStatus.Closed -> "ban"
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
