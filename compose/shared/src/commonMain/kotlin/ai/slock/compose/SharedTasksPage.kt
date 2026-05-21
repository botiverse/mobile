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
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.items
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp

@Composable
internal fun SharedTasksScreen(
    state: TasksUiState,
    onIntent: (SharedTasksRouteIntent) -> Unit,
    modifier: Modifier = Modifier
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
        SharedTasksHeader(
            refreshing = state.refreshing,
            onRefresh = { onIntent(SharedTasksRouteIntent.Refresh) }
        )
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
                .heightIn(min = SlockSize.PageHeaderHeight)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .background(SlockColor.BrandYellow)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon("square-check-big", modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Tasks",
                style = SlockType.PageTitle,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Shared KMP task state",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        SharedTasksPillButton(
            label = if (refreshing) "Syncing" else "Refresh",
            selected = refreshing,
            enabled = !refreshing,
            onClick = onRefresh
        )
    }
}

@Composable
private fun SharedTasksLoadedBody(
    state: TasksUiState,
    content: TasksContent,
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
            showChannelFilter = true
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

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        item(key = "summary") {
            SharedTasksSummary(
                content = visibleContent.channelScopedContent,
                modifier = Modifier.padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
            )
        }
        state.navigationErrorCode?.let { code ->
            item(key = "nav-error") {
                SharedTasksNotice("Action unavailable", code, Modifier.padding(horizontal = SlockSpacing.Md))
            }
        }
        state.statusUpdateErrorCode?.let { code ->
            item(key = "status-error") {
                SharedTasksNotice("Could not update status", code, Modifier.padding(horizontal = SlockSpacing.Md))
            }
        }
        item(key = "filters") {
            SharedTasksToolbar(
                state = state,
                channels = visibleContent.channelOptions,
                selectedChannels = visibleContent.selectedChannelNames,
                selectedMode = presentationState.selectedMode,
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
                val columns = visibleContent.filteredContent.boardColumns(renderLimit = 8)
                items(columns.size, key = { index -> "board-${columns[index].status}" }) { index ->
                    val column = columns[index]
                    SharedTasksBoardColumn(
                        status = column.status,
                        totalCount = column.totalCount,
                        tasks = column.tasks,
                        updatingTaskId = state.statusUpdatingTaskId,
                        onStatusChange = { task, status -> onIntent(SharedTasksRouteIntent.UpdateStatus(task, status)) },
                        modifier = Modifier.padding(horizontal = SlockSpacing.Md)
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
                    items(visibleContent.filteredTasks.size, key = { index -> visibleContent.filteredTasks[index].id.value }) { index ->
                        val task = visibleContent.filteredTasks[index]
                        SharedTaskRow(
                            task = task,
                            updating = state.statusUpdatingTaskId == task.id.value,
                            onStatusChange = { status -> onIntent(SharedTasksRouteIntent.UpdateStatus(task, status)) },
                            modifier = Modifier.padding(horizontal = SlockSpacing.Md)
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

@Composable
private fun SharedTasksSummary(
    content: TasksContent,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = "${content.tasks.size} TASKS",
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        Text(
            text = TaskStatus.entries.joinToString(" · ") { "${it.label}: ${content.summaryCounts[it] ?: 0}" },
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SharedTasksToolbar(
    state: TasksUiState,
    channels: List<String>,
    selectedChannels: Set<String>,
    selectedMode: TasksViewMode,
    onFilterSelected: (TaskStatus?) -> Unit,
    onChannelToggled: (String) -> Unit,
    onClearChannels: () -> Unit,
    onModeSelected: (TasksViewMode) -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SharedTasksPillButton(
                label = "Board",
                selected = selectedMode == TasksViewMode.Board,
                onClick = { onModeSelected(TasksViewMode.Board) }
            )
            SharedTasksPillButton(
                label = "List",
                selected = selectedMode == TasksViewMode.List,
                onClick = { onModeSelected(TasksViewMode.List) }
            )
            Spacer(modifier = Modifier.weight(1f))
            SharedTasksPillButton(
                label = "Clear",
                selected = false,
                enabled = selectedChannels.isNotEmpty(),
                onClick = onClearChannels
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            SharedTasksPillButton("All", selected = state.selectedFilter == null, onClick = { onFilterSelected(null) })
            state.availableFilters.forEach { status ->
                SharedTasksPillButton(
                    label = status.label,
                    selected = state.selectedFilter == status,
                    onClick = { onFilterSelected(status) }
                )
            }
        }
        if (channels.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                Text(
                    text = "CHANNELS",
                    style = SlockType.SectionLabel,
                    color = SlockColor.TextMutedGray
                )
                channels.take(12).forEach { channel ->
                    SharedTasksChannelFilterRow(
                        channel = channel,
                        selected = channel in selectedChannels,
                        onClick = { onChannelToggled(channel) }
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
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
                .heightIn(min = 34.dp)
                .background(if (selected) SlockColor.AppBgLightBlue else SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, if (selected) SlockColor.BorderBlack else SlockColor.EmptyStateGray)
                .clickable(onClick = onClick)
                .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Text(text = "✓", style = SlockType.Caption.copy(fontWeight = FontWeight.Bold), color = SlockColor.TextBlack)
            }
        }
        Text(
            text = "#$channel",
            style = SlockType.Button,
            color = SlockColor.TextBlack,
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            SharedTasksStatusDot(status)
            Text(
                text = status.label.uppercase(),
                style = SlockType.SectionLabel,
                color = SlockColor.TextBlack
            )
            Text(
                text = totalCount.toString(),
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
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
                    onStatusChange = { nextStatus -> onStatusChange(task, nextStatus) }
                )
            }
        }
    }
}

@Composable
private fun SharedTaskRow(
    task: TaskSummary,
    updating: Boolean,
    onStatusChange: (TaskStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
                .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SharedTasksStatusDot(task.status)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = task.tasksDisplayTitle(),
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        listOfNotNull(
                            task.tasksSourceLabel(),
                            task.tasksAssigneeDisplayName() ?: "Unassigned",
                            task.updatedAtLabel.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
            TaskStatus.entries.forEach { status ->
                SharedTasksPillButton(
                    label = status.shortLabel,
                    selected = task.status == status,
                    enabled = !updating && task.status != status,
                    onClick = { onStatusChange(status) }
                )
            }
        }
        if (updating) {
            Text(
                text = "Updating status...",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
private fun SharedTasksStatusDot(status: TaskStatus) {
    Box(
        modifier =
            Modifier
                .padding(top = 3.dp)
                .size(10.dp)
                .background(status.color)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
    )
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
                .widthIn(min = 44.dp)
                .heightIn(min = 30.dp)
                .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, if (enabled) SlockColor.BorderBlack else SlockColor.EmptyStateGray)
                .clickable(enabled = enabled, onClick = onClick)
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
