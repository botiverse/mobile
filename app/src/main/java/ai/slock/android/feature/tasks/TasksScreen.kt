package ai.slock.android.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import ai.slock.android.R
import java.util.Locale
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockCheckbox
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockTaskBoard
import ai.slock.android.ui.components.SlockTaskCardData
import ai.slock.android.ui.components.SlockTaskStatus
import ai.slock.android.ui.components.SlockTitledPageHeader
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

@Composable
fun TasksScreen(
    state: TasksUiState,
    modifier: Modifier = Modifier,
    onFilterSelected: (TaskStatus?) -> Unit = {},
    onTaskClick: (TaskSummary) -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val copy = TasksScreenCopy.defaults()
    var lastVisibleContent by remember { mutableStateOf<TasksContent?>(null) }
    val currentContent = (state.loadState as? ScreenLoadState.Content)?.data
    if (currentContent != null) {
        lastVisibleContent = currentContent
    }
    val displayState = when {
        state.refreshing && lastVisibleContent != null -> ScreenLoadState.Content(lastVisibleContent!!)
        else -> state.loadState
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        SlockTitledPageHeader(title = copy.title)
        when (val loadState = displayState) {
            ScreenLoadState.Loading -> TasksStateBody(
                title = copy.loadingTitle,
                detail = copy.loadingDetail
            )
            is ScreenLoadState.Content -> TasksLoadedBody(
                state = state,
                content = loadState.data,
                copy = copy,
                onFilterSelected = onFilterSelected,
                onTaskClick = onTaskClick,
                refreshing = state.refreshing,
                onRefresh = onRetry
            )
            is ScreenLoadState.Empty -> TasksStateBody(
                title = copy.emptyTitle,
                detail = loadState.reason.name
            )
            is ScreenLoadState.Error -> TasksErrorBody(
                code = loadState.error.code,
                copy = copy,
                onRetry = onRetry
            )
            is ScreenLoadState.NoPermission -> TasksStateBody(
                title = copy.noPermissionTitle,
                detail = loadState.blocker.requiredCapability ?: loadState.blocker.code
            )
        }
    }
}

@Composable
private fun TasksLoadedBody(
    state: TasksUiState,
    content: TasksContent,
    copy: TasksScreenCopy,
    onFilterSelected: (TaskStatus?) -> Unit,
    onTaskClick: (TaskSummary) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    val channelOptions = content.tasks
        .mapNotNull { it.channelName }
        .distinct()
        .sorted()
    var selectedChannelNames by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var channelSelectorExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedChannels = selectedChannelNames.filter { it in channelOptions }.toSet()
    val channelScopedTasks = content.tasks.filter { task ->
        selectedChannels.isEmpty() || task.channelName in selectedChannels
    }
    val channelScopedContent = content.copy(
        summaryCounts = TaskStatus.entries.associateWith { status ->
            channelScopedTasks.count { it.status == status }
        },
        tasks = channelScopedTasks
    )
    val filteredTasks = channelScopedTasks.filter { task ->
        state.selectedFilter == null || task.status == state.selectedFilter
    }
    val filteredContent = channelScopedContent.copy(tasks = filteredTasks)
    var selectedMode by rememberSaveable { mutableStateOf(TasksViewMode.Board) }

    TasksRefreshableContent(
        refreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) { refreshOffset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = refreshOffset)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            TasksSummaryBar(content = channelScopedContent, copy = copy)
            state.navigationErrorCode?.let { code ->
                TasksNavigationNotice(
                    title = copy.navigationFailedTitle,
                    detail = copy.navigationErrorDetail(code)
                )
            }
            TasksToolbar(
                state = state,
                copy = copy,
                channelOptions = channelOptions,
                selectedChannelNames = selectedChannels,
                channelSelectorExpanded = channelSelectorExpanded,
                selectedMode = selectedMode,
                onFilterSelected = onFilterSelected,
                onChannelSelectorExpandedChange = { channelSelectorExpanded = it },
                onChannelToggled = { channelName ->
                    selectedChannelNames = if (channelName in selectedChannels) {
                        selectedChannels - channelName
                    } else {
                        selectedChannels + channelName
                    }
                },
                onClearChannels = { selectedChannelNames = emptySet() },
                onModeSelected = { selectedMode = it }
            )
            when (selectedMode) {
                TasksViewMode.Board -> TasksBoardSection(content = filteredContent, copy = copy, onTaskClick = onTaskClick)
                TasksViewMode.List -> TasksListSection(tasks = filteredTasks, copy = copy, onTaskClick = onTaskClick)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksRefreshableContent(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(Dp) -> Unit
) {
    val refreshState = rememberPullToRefreshState()
    Box(
        modifier = modifier.pullToRefresh(
            isRefreshing = refreshing,
            state = refreshState,
            enabled = true,
            threshold = 120.dp,
            onRefresh = {
                if (!refreshing) {
                    onRefresh()
                }
            }
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            val offset = if (refreshing) {
                SlockSpacing.Xs
            } else {
                SlockSpacing.Xs * refreshState.distanceFraction.coerceIn(0f, 1f)
            }
            content(offset)
        }
        if (refreshing || refreshState.distanceFraction > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SlockSpacing.Sm),
                contentAlignment = Alignment.TopCenter
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = SlockColor.TextBlack,
                    trackColor = SlockColor.BorderBlack.copy(alpha = 0.18f)
                )
            }
        }
    }
}

@Composable
private fun TasksNavigationNotice(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.AccentRed.copy(alpha = 0.12f))
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = SlockType.Caption,
            color = SlockColor.AccentRed,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TasksSummaryBar(content: TasksContent, copy: TasksScreenCopy) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = copy.tasksCount(content.tasks.size).uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        Text(
            text = content.summaryLabel(copy),
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TasksToolbar(
    state: TasksUiState,
    copy: TasksScreenCopy,
    channelOptions: List<String>,
    selectedChannelNames: Set<String>,
    channelSelectorExpanded: Boolean,
    selectedMode: TasksViewMode,
    onFilterSelected: (TaskStatus?) -> Unit,
    onChannelSelectorExpandedChange: (Boolean) -> Unit,
    onChannelToggled: (String) -> Unit,
    onClearChannels: () -> Unit,
    onModeSelected: (TasksViewMode) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        TasksFilterBar(
            channels = channelOptions,
            selectedChannelNames = selectedChannelNames,
            channelSelectorExpanded = channelSelectorExpanded,
            selectedMode = selectedMode,
            copy = copy,
            onChannelSelectorExpandedChange = onChannelSelectorExpandedChange,
            onChannelToggled = onChannelToggled,
            onClearChannels = onClearChannels,
            onModeSelected = onModeSelected
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            TasksFilterButton(
                label = copy.filterAll,
                selected = state.selectedFilter == null,
                onClick = { onFilterSelected(null) }
            )
            state.availableFilters.forEach { status ->
                TasksFilterButton(
                    label = copy.statusLabel(status),
                    selected = state.selectedFilter == status,
                    onClick = { onFilterSelected(status) }
                )
            }
        }
    }
}

@Composable
private fun TasksFilterBar(
    channels: List<String>,
    selectedChannelNames: Set<String>,
    channelSelectorExpanded: Boolean,
    selectedMode: TasksViewMode,
    copy: TasksScreenCopy,
    onChannelSelectorExpandedChange: (Boolean) -> Unit,
    onChannelToggled: (String) -> Unit,
    onClearChannels: () -> Unit,
    onModeSelected: (TasksViewMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            TasksChannelSelector(
                channels = channels,
                selectedChannelNames = selectedChannelNames,
                expanded = channelSelectorExpanded,
                copy = copy,
                onExpandedChange = onChannelSelectorExpandedChange,
                onChannelToggled = onChannelToggled,
                onClearChannels = onClearChannels,
                modifier = Modifier.weight(1f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TasksModeButton(
                    label = copy.board,
                    icon = SlockIcons.Columns3,
                    selected = selectedMode == TasksViewMode.Board,
                    onClick = { onModeSelected(TasksViewMode.Board) }
                )
                TasksModeButton(
                    label = copy.list,
                    icon = SlockIcons.LayoutList,
                    selected = selectedMode == TasksViewMode.List,
                    onClick = { onModeSelected(TasksViewMode.List) }
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
}

@Composable
private fun TasksChannelSelector(
    channels: List<String>,
    selectedChannelNames: Set<String>,
    expanded: Boolean,
    copy: TasksScreenCopy,
    onExpandedChange: (Boolean) -> Unit,
    onChannelToggled: (String) -> Unit,
    onClearChannels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .zIndex(if (expanded) 2f else 0f)
    ) {
        TasksChannelSelectorButton(
            label = copy.channelTriggerLabel(selectedChannelNames),
            copy = copy,
            expanded = expanded,
            onClick = { onExpandedChange(!expanded) }
        )
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, 40),
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true)
            ) {
                TasksChannelDropdown(
                    channels = channels,
                    selectedChannelNames = selectedChannelNames,
                    copy = copy,
                    onChannelToggled = onChannelToggled,
                    onClearChannels = onClearChannels
                )
            }
        }
    }
}

@Composable
private fun TasksChannelSelectorButton(
    label: String,
    copy: TasksScreenCopy,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val shadow = SlockShadow.Small
    Row(
        modifier = Modifier
            .widthIn(min = 152.dp, max = 260.dp)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(
                SlockStroke.Default,
                SlockColor.BorderBlack.copy(alpha = 0.30f),
                RoundedCornerShape(SlockRadius.Xs)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Icon(
            imageVector = SlockIcons.Hash,
            contentDescription = null,
            tint = SlockColor.TextBlack.copy(alpha = 0.70f),
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = SlockType.SectionLabel.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack.copy(alpha = 0.70f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Icon(
            imageVector = SlockIcons.ChevronDown,
            contentDescription = if (expanded) copy.collapseChannels else copy.expandChannels,
            tint = SlockColor.TextBlack.copy(alpha = 0.70f),
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun TasksChannelDropdown(
    channels: List<String>,
    selectedChannelNames: Set<String>,
    copy: TasksScreenCopy,
    onChannelToggled: (String) -> Unit,
    onClearChannels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 260.dp, max = 328.dp)
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        TasksChannelDropdownHeader(
            clearLabel = copy.clearAll,
            enabled = selectedChannelNames.isNotEmpty(),
            onClear = onClearChannels
        )
        if (channels.isEmpty()) {
            Text(
                text = copy.noChannels,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(SlockSpacing.Md)
            )
        } else {
            channels.forEach { channelName ->
                TasksChannelDropdownRow(
                    label = channelName,
                    selected = channelName in selectedChannelNames,
                    onClick = { onChannelToggled(channelName) }
                )
            }
        }
    }
}

@Composable
private fun TasksChannelDropdownHeader(
    clearLabel: String,
    enabled: Boolean,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(SlockColor.SurfaceWhite)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClear)
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = clearLabel.uppercase(),
            style = SlockType.SectionLabel,
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
            maxLines = 1
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SlockStroke.Thin)
            .background(SlockColor.EmptyStateGray)
    )
}

@Composable
private fun TasksChannelDropdownRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (selected) SlockColor.AppBgLightBlue else SlockColor.SurfaceWhite)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        SlockCheckbox(checked = selected, size = 18.dp)
        Text(
            text = "#",
            style = SlockType.SectionLabel,
            color = SlockColor.TextMutedGray,
            modifier = Modifier.width(18.dp),
            textAlign = TextAlign.Center
        )
        Text(
            text = label,
            style = SlockType.Button,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TasksFilterButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shadow = SlockShadow.Small
    Box(
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
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.uppercase(),
            style = SlockType.Button,
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
private fun TasksModeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shadow = SlockShadow.Small
    Row(
        modifier = Modifier
            .then(
                if (selected) {
                    Modifier
                        .padding(end = shadow.offsetX, bottom = shadow.offsetY)
                        .slockHardShadow(shadow)
                } else {
                    Modifier
                }
            )
            .background(
                if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite,
                RoundedCornerShape(SlockRadius.Xs)
            )
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Tab, onClick = onClick)
            .height(28.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = label.uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

private enum class TasksViewMode {
    Board,
    List
}

@Composable
private fun TasksCompactAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val shadow = SlockShadow.Small
    Row(
        modifier = Modifier
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .slockHardShadow(shadow)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .defaultMinSize(minHeight = 34.dp)
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label.uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
private fun TasksBoardSection(
    content: TasksContent,
    copy: TasksScreenCopy,
    onTaskClick: (TaskSummary) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        TasksSectionHeader(title = copy.board, detail = copy.boardDetail)
        SlockTaskBoard(columns = content.toTaskColumns(copy, onTaskClick))
    }
}

@Composable
private fun TasksListSection(
    tasks: List<TaskSummary>,
    copy: TasksScreenCopy,
    onTaskClick: (TaskSummary) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        TasksSectionHeader(title = copy.list, detail = copy.visibleCount(tasks.size))
        if (tasks.isEmpty()) {
            SlockEmptyState(
                title = copy.noMatchingTitle,
                detail = copy.noMatchingDetail
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                tasks.forEach { task ->
                    TasksListRow(task = task, copy = copy, onClick = { onTaskClick(task) })
                }
            }
        }
    }
}

@Composable
private fun TasksSectionHeader(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(text = title.uppercase(), style = SlockType.SectionLabel, color = SlockColor.TextBlack)
        Text(
            text = detail,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TasksListRow(
    task: TaskSummary,
    copy: TasksScreenCopy,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(task.status.toSlockTaskStatus().statusColor, RoundedCornerShape(10.dp))
                .border(SlockStroke.Thin, SlockColor.BorderBlack, RoundedCornerShape(10.dp))
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (task.taskNumber != null) "task #${task.taskNumber} ${task.title}" else task.title,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    task.channelName?.let { "#$it" },
                    task.assignee?.displayName ?: copy.unassigned,
                    task.updatedAtLabel
                ).joinToString(" · "),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = copy.statusLabel(task.status).uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack,
            modifier = Modifier
                .background(task.status.toSlockTaskStatus().statusColor)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = SlockSpacing.Sm, vertical = 2.dp)
        )
        Icon(
            imageVector = SlockIcons.ChevronRight,
            contentDescription = null,
            tint = SlockColor.TextMutedGray,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun TasksStateBody(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockEmptyState(title = title, detail = detail)
    }
}

@Composable
private fun TasksErrorBody(code: String, copy: TasksScreenCopy, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockErrorState(title = copy.failedTitle, detail = copy.errorDetail(code))
        Box(modifier = Modifier.clickable(role = Role.Button, onClick = onRetry)) {
            TasksCompactAction(label = copy.retry, icon = SlockIcons.RefreshCw)
        }
    }
}

private fun TasksContent.summaryLabel(copy: TasksScreenCopy): String =
    TaskStatus.entries.joinToString(" · ") { status ->
        "${copy.statusLabel(status)}: ${summaryCounts[status] ?: tasks.count { it.status == status }}"
    }

private fun TasksScreenCopy.channelTriggerLabel(selectedChannelNames: Set<String>): String =
    when (selectedChannelNames.size) {
        0 -> channelFilterLabel
        1 -> "#${selectedChannelNames.first()}"
        else -> selectedChannelsCount(selectedChannelNames.size)
    }

private fun TasksContent.toTaskColumns(
    copy: TasksScreenCopy,
    onTaskClick: (TaskSummary) -> Unit
): Map<SlockTaskStatus, List<SlockTaskCardData>> {
    return tasks
        .map { it.toTaskCardData(copy, onTaskClick) }
        .groupBy { it.status }
}

private fun TaskSummary.toTaskCardData(
    copy: TasksScreenCopy,
    onTaskClick: (TaskSummary) -> Unit
): SlockTaskCardData {
    return SlockTaskCardData(
        title = if (taskNumber != null) "task #$taskNumber $title" else title,
        source = channelName?.let { "#$it" } ?: copy.sampleState,
        owner = assignee?.displayName ?: copy.unassigned,
        updatedAt = updatedAtLabel,
        status = status.toSlockTaskStatus(),
        onClick = { onTaskClick(this) }
    )
}

private fun TaskStatus.toSlockTaskStatus(): SlockTaskStatus {
    return when (this) {
        TaskStatus.Todo -> SlockTaskStatus.Todo
        TaskStatus.InProgress -> SlockTaskStatus.InProgress
        TaskStatus.InReview -> SlockTaskStatus.InReview
        TaskStatus.Done -> SlockTaskStatus.Done
    }
}

private val SlockTaskStatus.statusColor
    get() = when (this) {
        SlockTaskStatus.Todo -> SlockColor.AccentOrange
        SlockTaskStatus.InProgress -> SlockColor.AccentCyan
        SlockTaskStatus.InReview -> SlockColor.BrandYellow
        SlockTaskStatus.Done -> SlockColor.AccentLime
    }

private data class TasksScreenCopy(
    val title: String,
    val loadingTitle: String,
    val loadingDetail: String,
    val emptyTitle: String,
    val noPermissionTitle: String,
    val filterAll: String,
    val board: String,
    val list: String,
    val boardDetail: String,
    val noMatchingTitle: String,
    val noMatchingDetail: String,
    val failedTitle: String,
    val retry: String,
    val unassigned: String,
    val sampleState: String,
    val channelFilterLabel: String,
    val allChannels: String,
    val clearAll: String,
    val noChannels: String,
    val expandChannels: String,
    val collapseChannels: String,
    val navigationFailedTitle: String,
    val selectedChannelsCount: (Int) -> String,
    val tasksCount: (Int) -> String,
    val visibleCount: (Int) -> String,
    val errorDetail: (String) -> String,
    val navigationErrorDetail: (String) -> String,
    val statusLabel: (TaskStatus) -> String
) {
    companion object {
        @Composable
        fun defaults(): TasksScreenCopy {
            val tasksCountFormat = stringResource(R.string.tasks_count)
            val visibleCountFormat = stringResource(R.string.tasks_visible_count)
            val errorDetailFormat = stringResource(R.string.tasks_error_detail)
            val selectedChannelsFormat = stringResource(R.string.tasks_selected_channels_count)
            val todo = stringResource(R.string.tasks_status_todo)
            val inProgress = stringResource(R.string.tasks_status_in_progress)
            val inReview = stringResource(R.string.tasks_status_in_review)
            val done = stringResource(R.string.tasks_status_done)

            return TasksScreenCopy(
                title = stringResource(R.string.tasks_title),
                loadingTitle = stringResource(R.string.tasks_loading_title),
                loadingDetail = stringResource(R.string.tasks_loading_detail),
                emptyTitle = stringResource(R.string.tasks_empty_title),
                noPermissionTitle = stringResource(R.string.tasks_no_permission_title),
                filterAll = stringResource(R.string.tasks_filter_all),
                board = stringResource(R.string.tasks_board),
                list = stringResource(R.string.tasks_list),
                boardDetail = stringResource(R.string.tasks_board_detail),
                noMatchingTitle = stringResource(R.string.tasks_no_matching_title),
                noMatchingDetail = stringResource(R.string.tasks_no_matching_detail),
                failedTitle = stringResource(R.string.tasks_failed_title),
                retry = stringResource(R.string.tasks_retry),
                unassigned = stringResource(R.string.tasks_unassigned),
                sampleState = stringResource(R.string.tasks_sample_state),
                channelFilterLabel = stringResource(R.string.tasks_channel_filter_label),
                allChannels = stringResource(R.string.tasks_all_channels),
                clearAll = stringResource(R.string.tasks_clear_all_channels),
                noChannels = stringResource(R.string.tasks_no_channels),
                expandChannels = stringResource(R.string.tasks_expand_channels),
                collapseChannels = stringResource(R.string.tasks_collapse_channels),
                navigationFailedTitle = "Cannot open task",
                selectedChannelsCount = { count ->
                    String.format(Locale.getDefault(), selectedChannelsFormat, count)
                },
                tasksCount = { count -> String.format(Locale.getDefault(), tasksCountFormat, count) },
                visibleCount = { count -> String.format(Locale.getDefault(), visibleCountFormat, count) },
                errorDetail = { code -> String.format(Locale.getDefault(), errorDetailFormat, code) },
                navigationErrorDetail = { code ->
                    if (code == "missing_context") {
                        "This task does not include a channel, thread, or message context."
                    } else {
                        "Task context could not be loaded: $code"
                    }
                },
                statusLabel = { status ->
                    when (status) {
                        TaskStatus.Todo -> todo
                        TaskStatus.InProgress -> inProgress
                        TaskStatus.InReview -> inReview
                        TaskStatus.Done -> done
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 920)
@Composable
private fun TasksScreenPreview() {
    SlockTheme {
        TasksScreen(state = SlockStateSamples.tasksContent)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun TasksScreenStatesPreview() {
    SlockTheme {
        Column {
            SlockStateSamples.tasksBranches.forEach { branch ->
                Box(modifier = Modifier.height(180.dp)) {
                    TasksScreen(state = branch)
                }
            }
        }
    }
}
