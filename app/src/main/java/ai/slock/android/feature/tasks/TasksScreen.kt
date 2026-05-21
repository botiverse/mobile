package ai.slock.android.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import ai.slock.android.R
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.feature.thread.toThreadTimestampLabel
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockCheckbox
import ai.slock.android.ui.icons.LucideIcons
import ai.slock.android.ui.components.SlockInlineLoading
import ai.slock.android.ui.components.SlockLoadingState
import ai.slock.android.ui.components.SlockStatusSelector
import ai.slock.android.ui.components.SlockTaskBoard
import ai.slock.android.ui.components.SlockTaskBoardDefaults
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
import ai.slock.compose.data.TasksPresentationIntent
import ai.slock.compose.data.TasksPresentationState
import ai.slock.compose.data.TasksViewMode
import ai.slock.compose.data.reduce
import ai.slock.compose.data.visibleContent
import ai.slock.compose.data.TasksContent as SharedTasksContent
import ai.slock.compose.data.TasksVisibleContent as SharedTasksVisibleContent
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun TasksScreen(
    state: TasksUiState,
    modifier: Modifier = Modifier,
    onFilterSelected: (TaskStatus?) -> Unit = {},
    onTaskClick: (TaskSummary) -> Unit = {},
    onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {}
) {
    val copy = TasksScreenCopy.defaults()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        SlockTitledPageHeader(title = copy.title)
        TasksPanel(
            state = state,
            showChannelFilter = true,
            onFilterSelected = onFilterSelected,
            onTaskClick = onTaskClick,
            onTaskStatusChange = onTaskStatusChange,
            onRetry = onRetry
        )
    }
}

@Composable
fun TasksPanel(
    state: TasksUiState,
    modifier: Modifier = Modifier,
    showChannelFilter: Boolean = true,
    onFilterSelected: (TaskStatus?) -> Unit = {},
    onTaskClick: (TaskSummary) -> Unit = {},
    onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {}
) {
    val copy = TasksScreenCopy.defaults()
    var lastVisibleContent by remember { mutableStateOf<TasksContent?>(null) }
    var localSelectedFilter by rememberSaveable { mutableStateOf<TaskStatus?>(null) }
    LaunchedEffect(state.selectedFilter) {
        if (showChannelFilter) {
            localSelectedFilter = state.selectedFilter
        }
    }
    val currentContent = (state.loadState as? ScreenLoadState.Content)?.data
    if (currentContent != null) {
        lastVisibleContent = currentContent
    }
    val effectiveSelectedFilter =
        if (showChannelFilter) state.selectedFilter else localSelectedFilter
    val displayUiState = state.copy(selectedFilter = effectiveSelectedFilter)
    val displayState = when {
        state.refreshing && lastVisibleContent != null -> ScreenLoadState.Content(lastVisibleContent!!)
        else -> state.loadState
    }
    TasksPanel(
        state = displayUiState,
        copy = copy,
        lastVisibleContent = displayState,
        showChannelFilter = showChannelFilter,
        modifier = modifier,
        onFilterSelected = { filter ->
            if (showChannelFilter) {
                onFilterSelected(filter)
            } else {
                localSelectedFilter = filter
            }
        },
        onTaskClick = onTaskClick,
        onTaskStatusChange = onTaskStatusChange,
        onRetry = onRetry
    )
}

@Composable
private fun TasksPanel(
    state: TasksUiState,
    copy: TasksScreenCopy,
    lastVisibleContent: ScreenLoadState<TasksContent>,
    showChannelFilter: Boolean,
    modifier: Modifier = Modifier,
    onFilterSelected: (TaskStatus?) -> Unit,
    onTaskClick: (TaskSummary) -> Unit,
    onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
    onRetry: () -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        when (val loadState = lastVisibleContent) {
            ScreenLoadState.Loading -> TasksStateBody(
                title = copy.loadingTitle,
                detail = copy.loadingDetail,
                loading = true
            )
            is ScreenLoadState.Content -> TasksLoadedBody(
                state = state,
                content = loadState.data,
                copy = copy,
                onFilterSelected = onFilterSelected,
                onTaskClick = onTaskClick,
                onTaskStatusChange = onTaskStatusChange,
                showChannelFilter = showChannelFilter,
                refreshing = state.refreshing
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
    onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
    showChannelFilter: Boolean,
    refreshing: Boolean
) {
    var presentationState by remember { mutableStateOf(TasksPresentationState()) }
    val sortedContent = remember(content) {
        content.copy(tasks = content.tasks.sortedByRecentTaskTime())
    }
    val visibleContent = remember(sortedContent, state.selectedFilter, presentationState, showChannelFilter) {
        sortedContent.toShared().visibleContent(
            selectedFilter = state.selectedFilter,
            presentationState = presentationState,
            showChannelFilter = showChannelFilter
        ).toAndroid()
    }
    LaunchedEffect(visibleContent.channelOptions) {
        val nextState =
            presentationState.reduce(
                TasksPresentationIntent.PruneUnavailableChannels(visibleContent.channelOptions.toSet())
            )
        if (nextState != presentationState) {
            presentationState = nextState
        }
    }
    var renderedMode by remember { mutableStateOf(presentationState.selectedMode) }
    LaunchedEffect(presentationState.selectedMode) {
        if (renderedMode != presentationState.selectedMode) {
            delay(80)
            renderedMode = presentationState.selectedMode
        }
    }
    val modeSwitching = renderedMode != presentationState.selectedMode
    val listFilterScrollState = rememberScrollState()
    val boardScrollState = rememberScrollState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite),
        contentPadding = PaddingValues(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        item(key = "summary") {
            TasksSummaryBar(content = visibleContent.channelScopedContent, copy = copy)
        }
        state.navigationErrorCode?.let { code ->
            item(key = "navigation-error") {
                TasksNavigationNotice(
                    title = copy.navigationFailedTitle,
                    detail = copy.navigationErrorDetail(code)
                )
            }
        }
        state.statusUpdateErrorCode?.let { code ->
            item(key = "status-error") {
                TasksNavigationNotice(
                    title = copy.statusUpdateFailedTitle,
                    detail = copy.statusUpdateErrorDetail(code)
                )
            }
        }
        item(key = "toolbar") {
            TasksToolbar(
                state = state,
                copy = copy,
                filterScrollState = listFilterScrollState,
                channelOptions = visibleContent.channelOptions,
                selectedChannelNames = visibleContent.selectedChannelNames,
                channelSelectorExpanded = presentationState.channelSelectorExpanded,
                selectedMode = presentationState.selectedMode,
                showChannelFilter = showChannelFilter,
                onFilterSelected = onFilterSelected,
                onChannelSelectorExpandedChange = { expanded ->
                    presentationState =
                        presentationState.reduce(TasksPresentationIntent.SetChannelSelectorExpanded(expanded))
                },
                onChannelToggled = { channelName ->
                    presentationState =
                        presentationState.reduce(TasksPresentationIntent.ToggleChannel(channelName))
                },
                onClearChannels = {
                    presentationState = presentationState.reduce(TasksPresentationIntent.ClearChannels)
                },
                onModeSelected = { mode ->
                    presentationState = presentationState.reduce(TasksPresentationIntent.SelectMode(mode))
                }
            )
        }
        if (refreshing || modeSwitching) {
            item(key = "loading-section") {
                TasksContentLoadingSection(mode = presentationState.selectedMode, copy = copy)
            }
        } else {
            when (renderedMode) {
                TasksViewMode.Board ->
                    item(key = "board-section") {
                        TasksBoardSection(
                            content = visibleContent.filteredContent,
                            copy = copy,
                            boardScrollState = boardScrollState,
                            onTaskClick = onTaskClick,
                            onTaskStatusChange = onTaskStatusChange,
                            updatingTaskId = state.statusUpdatingTaskId
                        )
                    }
                TasksViewMode.List ->
                    tasksListSection(
                        tasks = visibleContent.filteredTasks,
                        copy = copy,
                        onTaskClick = onTaskClick,
                        onTaskStatusChange = onTaskStatusChange,
                        updatingTaskId = state.statusUpdatingTaskId
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
    filterScrollState: ScrollState,
    channelOptions: List<String>,
    selectedChannelNames: Set<String>,
    channelSelectorExpanded: Boolean,
    selectedMode: TasksViewMode,
    showChannelFilter: Boolean,
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
            showChannelFilter = showChannelFilter,
            copy = copy,
            onChannelSelectorExpandedChange = onChannelSelectorExpandedChange,
            onChannelToggled = onChannelToggled,
            onClearChannels = onClearChannels,
            onModeSelected = onModeSelected
        )
        if (selectedMode == TasksViewMode.List) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(filterScrollState),
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
}

@Composable
private fun TasksFilterBar(
    channels: List<String>,
    selectedChannelNames: Set<String>,
    channelSelectorExpanded: Boolean,
    selectedMode: TasksViewMode,
    showChannelFilter: Boolean,
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
            if (showChannelFilter) {
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
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TasksModeButton(
                    label = copy.board,
                    icon = LucideIcons.Columns3,
                    selected = selectedMode == TasksViewMode.Board,
                    onClick = { onModeSelected(TasksViewMode.Board) }
                )
                TasksModeButton(
                    label = copy.list,
                    icon = LucideIcons.LayoutList,
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
            imageVector = LucideIcons.Hash,
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
            imageVector = LucideIcons.ChevronDown,
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

@Composable
private fun TasksCompactAction(
    label: String,
    icon: ImageVector
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
    boardScrollState: ScrollState,
    onTaskClick: (TaskSummary) -> Unit,
    onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
    updatingTaskId: String?
) {
    val currentOnTaskClick by rememberUpdatedState(onTaskClick)
    val currentOnTaskStatusChange by rememberUpdatedState(onTaskStatusChange)
    val boardData = remember(content.tasks, copy, updatingTaskId) {
        content.toTaskBoardData(
            copy = copy,
            renderLimit = SlockTaskBoardDefaults.ColumnRenderLimit,
            onTaskClick = { task -> currentOnTaskClick(task) },
            onTaskStatusChange = { task, status -> currentOnTaskStatusChange(task, status) },
            updatingTaskId = updatingTaskId
        )
    }
    SlockTaskBoard(
        columns = boardData.columns,
        totalCounts = boardData.totalCounts,
        scrollState = boardScrollState
    )
}

@Composable
private fun TasksContentLoadingSection(
    mode: TasksViewMode,
    copy: TasksScreenCopy
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        TasksSectionHeader(
            title = when (mode) {
                TasksViewMode.Board -> copy.board
                TasksViewMode.List -> copy.list
            },
            detail = copy.loadingDetail
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.EmptyStateGray),
            contentAlignment = Alignment.Center
        ) {
            SlockInlineLoading(text = copy.loadingDetail)
        }
    }
}

private fun LazyListScope.tasksListSection(
    tasks: List<TaskSummary>,
    copy: TasksScreenCopy,
    onTaskClick: (TaskSummary) -> Unit,
    onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
    updatingTaskId: String?
) {
    item(key = "list-header") {
        TasksSectionHeader(title = copy.list, detail = copy.visibleCount(tasks.size))
    }
    if (tasks.isEmpty()) {
        item(key = "list-empty") {
            SlockEmptyState(
                title = copy.noMatchingTitle,
                detail = copy.noMatchingDetail
            )
        }
    } else {
        items(
            items = tasks,
            key = { task -> task.id.value }
        ) { task ->
            TasksListRow(
                task = task,
                copy = copy,
                updating = updatingTaskId == task.id.value,
                onClick = { onTaskClick(task) },
                onStatusChange = { status -> onTaskStatusChange(task, status) }
            )
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
    updating: Boolean,
    onClick: () -> Unit,
    onStatusChange: (TaskStatus) -> Unit
) {
    var statusSelectorExpanded by rememberSaveable(task.id.value) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.Top,
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
                text = task.tasksDisplayTitle(),
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    task.channelName?.let { "#$it" },
                    task.tasksAssigneeDisplayName() ?: copy.unassigned,
                    task.updatedAtLabel.toThreadTimestampLabel()
                ).joinToString(" · "),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Icon(
                imageVector = LucideIcons.ChevronRight,
                contentDescription = null,
                tint = SlockColor.TextMutedGray,
                modifier = Modifier.size(16.dp)
            )
            SlockStatusSelector(
                status = task.status.toSlockTaskStatus(),
                label =
                    if (updating) {
                        copy.statusUpdating
                    } else {
                        copy.statusLabel(task.status)
                    },
                expanded = statusSelectorExpanded,
                enabled = !updating,
                onExpandedChange = { statusSelectorExpanded = it },
                onStatusSelected = { status ->
                    statusSelectorExpanded = false
                    onStatusChange(status.toTaskStatus())
                }
            )
        }
    }
}

@Composable
private fun TasksStateBody(title: String, detail: String, loading: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        if (loading) {
            SlockLoadingState(title = title)
        } else {
            SlockEmptyState(title = title, detail = detail)
        }
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
            TasksCompactAction(label = copy.retry, icon = LucideIcons.RefreshCw)
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

private data class TasksBoardData(
    val columns: Map<SlockTaskStatus, List<SlockTaskCardData>>,
    val totalCounts: Map<SlockTaskStatus, Int>
)

private data class TasksVisibleContent(
    val channelOptions: List<String>,
    val selectedChannelNames: Set<String>,
    val channelScopedContent: TasksContent,
    val filteredContent: TasksContent,
    val filteredTasks: List<TaskSummary>
)

private fun TasksContent.toShared(): SharedTasksContent =
    SharedTasksContent(
        summaryCounts = summaryCounts,
        tasks = tasks
    )

private fun SharedTasksVisibleContent.toAndroid(): TasksVisibleContent =
    TasksVisibleContent(
        channelOptions = channelOptions,
        selectedChannelNames = selectedChannelNames,
        channelScopedContent = channelScopedContent.toAndroid(),
        filteredContent = filteredContent.toAndroid(),
        filteredTasks = filteredTasks
    )

private fun SharedTasksContent.toAndroid(): TasksContent =
    TasksContent(
        summaryCounts = summaryCounts,
        tasks = tasks
    )

private fun TasksContent.toTaskBoardData(
    copy: TasksScreenCopy,
    renderLimit: Int,
    onTaskClick: (TaskSummary) -> Unit,
    onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
    updatingTaskId: String?
): TasksBoardData {
    val tasksByStatus = tasks.groupBy { it.status.toSlockTaskStatus() }
    val totalCounts = SlockTaskStatus.entries.associateWith { status ->
        tasksByStatus[status].orEmpty().size
    }
    val columns = SlockTaskStatus.entries.associateWith { status ->
        val statusTasks = tasksByStatus[status].orEmpty()
        val visibleTasks = if (renderLimit > 0) statusTasks.take(renderLimit) else statusTasks
        visibleTasks.map { task ->
            task.toTaskCardData(copy, onTaskClick, onTaskStatusChange, updatingTaskId)
        }
    }
    return TasksBoardData(columns = columns, totalCounts = totalCounts)
}

private fun List<TaskSummary>.sortedByRecentTaskTime(): List<TaskSummary> =
    sortedWith(
        compareByDescending<TaskSummary> { it.updatedAtLabel.toTaskSortEpochMillis() }
            .thenByDescending { it.taskNumber ?: Int.MIN_VALUE }
            .thenBy { it.title.lowercase(Locale.ROOT) }
    )

private fun String.toTaskSortEpochMillis(): Long {
    val trimmed = trim()
    if (trimmed.isEmpty()) return Long.MIN_VALUE
    if (trimmed.all { it.isDigit() }) {
        val value = trimmed.toLongOrNull() ?: return Long.MIN_VALUE
        return when (trimmed.length) {
            10 -> value * 1_000L
            13 -> value
            else -> Long.MIN_VALUE
        }
    }
    val isoLikeTrimmed = trimmed.replace(' ', 'T')
    return parseTaskSortTimeOrNull { Instant.parse(trimmed).toEpochMilli() }
        ?: parseTaskSortTimeOrNull { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        ?: parseTaskSortTimeOrNull { ZonedDateTime.parse(trimmed).toInstant().toEpochMilli() }
        ?: parseTaskSortTimeOrNull {
            LocalDateTime.parse(trimmed).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        ?: parseTaskSortTimeOrNull { Instant.parse(isoLikeTrimmed).toEpochMilli() }
        ?: parseTaskSortTimeOrNull { OffsetDateTime.parse(isoLikeTrimmed).toInstant().toEpochMilli() }
        ?: parseTaskSortTimeOrNull { ZonedDateTime.parse(isoLikeTrimmed).toInstant().toEpochMilli() }
        ?: parseTaskSortTimeOrNull {
            LocalDateTime.parse(isoLikeTrimmed).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        ?: Long.MIN_VALUE
}

private inline fun parseTaskSortTimeOrNull(block: () -> Long): Long? =
    try {
        block()
    } catch (_: Exception) {
        null
    }

private fun TaskSummary.toTaskCardData(
    copy: TasksScreenCopy,
    onTaskClick: (TaskSummary) -> Unit,
    onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
    updatingTaskId: String?
): SlockTaskCardData {
    return SlockTaskCardData(
        title = tasksDisplayTitle(),
        source = tasksSourceLabel().orEmpty(),
        owner = tasksAssigneeDisplayName() ?: copy.unassigned,
        updatedAt = updatedAtLabel.toThreadTimestampLabel(),
        status = status.toSlockTaskStatus(),
        onClick = { onTaskClick(this) },
        onStatusSelected = { status -> onTaskStatusChange(this, status.toTaskStatus()) },
        statusUpdating = updatingTaskId == id.value,
        changeStatusLabel = copy.changeStatus,
        updatingLabel = copy.statusUpdating
    )
}

internal fun TaskSummary.tasksSourceLabel(): String? =
    channelName?.takeIf { it.isNotBlank() }?.let { "#$it" }

internal fun TaskSummary.tasksDisplayTitle(): String {
    val numberLabel = taskNumber?.let { "#$it" }
    val meaningfulTitle = title.tasksMeaningfulTitle(taskNumber)
    return when {
        numberLabel != null && meaningfulTitle != null -> "$numberLabel $meaningfulTitle"
        numberLabel != null -> "Task $numberLabel"
        meaningfulTitle != null -> meaningfulTitle
        else -> "Task"
    }
}

internal fun TaskSummary.tasksAssigneeDisplayName(): String? =
    assignee?.displayName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { it.isUuidLike() }
        ?.takeUnless { assignee?.id?.value == it }

private fun String.tasksMeaningfulTitle(taskNumber: Int?): String? {
    val trimmed = trim()
    if (trimmed.isBlank()) return null
    val number = taskNumber ?: return trimmed
    val withoutNumberPrefix =
        trimmed.replaceFirst(
            Regex("""^\s*(?:task\s*)?#$number(?:\s*[-:：,，]\s*|\s+)""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
    if (withoutNumberPrefix.isNotBlank() && withoutNumberPrefix != trimmed) return withoutNumberPrefix
    val placeholders =
        setOf(
            "#$number",
            number.toString(),
            "task #$number",
            "task $number"
        )
    return trimmed.takeUnless { it.lowercase(Locale.ROOT) in placeholders }
}

private fun String.isUuidLike(): Boolean =
    uuidLikeRegex.matches(trim())

private val uuidLikeRegex =
    Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

private fun TaskStatus.toSlockTaskStatus(): SlockTaskStatus {
    return when (this) {
        TaskStatus.Todo -> SlockTaskStatus.Todo
        TaskStatus.InProgress -> SlockTaskStatus.InProgress
        TaskStatus.InReview -> SlockTaskStatus.InReview
        TaskStatus.Done -> SlockTaskStatus.Done
        TaskStatus.Closed -> SlockTaskStatus.Closed
    }
}

private fun SlockTaskStatus.toTaskStatus(): TaskStatus {
    return when (this) {
        SlockTaskStatus.Todo -> TaskStatus.Todo
        SlockTaskStatus.InProgress -> TaskStatus.InProgress
        SlockTaskStatus.InReview -> TaskStatus.InReview
        SlockTaskStatus.Done -> TaskStatus.Done
        SlockTaskStatus.Closed -> TaskStatus.Closed
    }
}

private val SlockTaskStatus.statusColor
    get() = when (this) {
        SlockTaskStatus.Todo -> SlockColor.AccentOrange
        SlockTaskStatus.InProgress -> SlockColor.AccentCyan
        SlockTaskStatus.InReview -> SlockColor.BrandYellow
        SlockTaskStatus.Done -> SlockColor.AccentLime
        SlockTaskStatus.Closed -> SlockColor.TextMutedGray
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
    val channelFilterLabel: String,
    val allChannels: String,
    val clearAll: String,
    val noChannels: String,
    val expandChannels: String,
    val collapseChannels: String,
    val navigationFailedTitle: String,
    val statusUpdateFailedTitle: String,
    val changeStatus: String,
    val statusUpdating: String,
    val selectedChannelsCount: (Int) -> String,
    val tasksCount: (Int) -> String,
    val visibleCount: (Int) -> String,
    val errorDetail: (String) -> String,
    val navigationErrorDetail: (String) -> String,
    val statusUpdateErrorDetail: (String) -> String,
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
            val closed = stringResource(R.string.tasks_status_closed)

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
                channelFilterLabel = stringResource(R.string.tasks_channel_filter_label),
                allChannels = stringResource(R.string.tasks_all_channels),
                clearAll = stringResource(R.string.tasks_clear_all_channels),
                noChannels = stringResource(R.string.tasks_no_channels),
                expandChannels = stringResource(R.string.tasks_expand_channels),
                collapseChannels = stringResource(R.string.tasks_collapse_channels),
                navigationFailedTitle = "Cannot open task",
                statusUpdateFailedTitle = "Cannot update status",
                changeStatus = "Change status",
                statusUpdating = "Updating",
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
                statusUpdateErrorDetail = { code -> "Task status could not be updated: $code" },
                statusLabel = { status ->
                    when (status) {
                        TaskStatus.Todo -> todo
                        TaskStatus.InProgress -> inProgress
                        TaskStatus.InReview -> inReview
                        TaskStatus.Done -> done
                        TaskStatus.Closed -> closed
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
