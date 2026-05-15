package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import ai.slock.android.R
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

enum class SlockTaskStatus {
    Todo,
    InProgress,
    InReview,
    Done,
    Closed
}

data class SlockTaskCardData(
    val title: String,
    val source: String,
    val owner: String,
    val updatedAt: String,
    val status: SlockTaskStatus,
    val onClick: (() -> Unit)? = null,
    val onStatusSelected: ((SlockTaskStatus) -> Unit)? = null,
    val statusUpdating: Boolean = false,
    val changeStatusLabel: String = "Change status",
    val updatingLabel: String = "Updating"
)

@Composable
fun SlockTaskBoard(
    columns: Map<SlockTaskStatus, List<SlockTaskCardData>>,
    totalCounts: Map<SlockTaskStatus, Int> = emptyMap(),
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    copy: SlockTaskBoardCopy = SlockTaskBoardCopy.defaults(),
    columnRenderLimit: Int = SlockTaskBoardDefaults.ColumnRenderLimit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockTaskStatus.entries.forEach { status ->
            SlockTaskColumn(
                status = status,
                tasks = columns[status].orEmpty(),
                copy = copy,
                totalCount = totalCounts[status] ?: columns[status].orEmpty().size,
                renderLimit = columnRenderLimit,
                modifier = Modifier.width(260.dp)
            )
        }
    }
}

@Composable
fun SlockTaskColumn(
    status: SlockTaskStatus,
    tasks: List<SlockTaskCardData>,
    copy: SlockTaskBoardCopy = SlockTaskBoardCopy.defaults(),
    modifier: Modifier = Modifier,
    totalCount: Int = tasks.size,
    renderLimit: Int = SlockTaskBoardDefaults.ColumnRenderLimit
) {
    var collapsed by rememberSaveable(status) {
        mutableStateOf(status == SlockTaskStatus.Done || status == SlockTaskStatus.Closed)
    }
    val visibleTasks = if (renderLimit > 0) tasks.take(renderLimit) else tasks
    val hiddenCount = (totalCount - visibleTasks.size).coerceAtLeast(0)
    Column(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = { collapsed = !collapsed }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Icon(
                imageVector = if (collapsed) SlockIcons.ChevronRight else SlockIcons.ChevronDown,
                contentDescription = null,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(14.dp)
            )
            SlockStatusLabel(
                status = status,
                label = copy.statusLabel(status),
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = totalCount.toString(),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        if (collapsed) {
            Text(
                text = if (totalCount == 1) "1 task hidden" else "$totalCount tasks hidden",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else if (tasks.isEmpty()) {
            SlockEmptyState(
                title = copy.emptyColumnTitle(status),
                detail = copy.emptyColumnDetail
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
            ) {
                items(visibleTasks) { task ->
                    SlockTaskCard(task = task, statusLabel = copy.statusLabel(task.status))
                }
                if (hiddenCount > 0) {
                    item(key = "task-column-truncated-$status") {
                        Text(
                            text = "$hiddenCount more tasks hidden. Use List view or filters to narrow results.",
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray
                        )
                    }
                }
            }
        }
    }
}

object SlockTaskBoardDefaults {
    const val ColumnRenderLimit: Int = 80
}

@Composable
fun SlockTaskCard(
    task: SlockTaskCardData,
    statusLabel: String = task.status.displayLabel,
    modifier: Modifier = Modifier
) {
    var statusMenuExpanded by rememberSaveable(task.title, task.status) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .then(task.onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = task.title,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (task.source.isNotBlank()) {
            Text(
                text = task.source,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            Text(
                text = task.owner,
                style = SlockType.Caption,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = task.updatedAt,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            SlockStatusSelector(
                status = task.status,
                label = if (task.statusUpdating) task.updatingLabel else statusLabel,
                expanded = statusMenuExpanded,
                enabled = !task.statusUpdating && task.onStatusSelected != null,
                onExpandedChange = { statusMenuExpanded = it },
                onStatusSelected = { status ->
                    statusMenuExpanded = false
                    task.onStatusSelected?.invoke(status)
                }
            )
        }
    }
}

@Composable
fun SlockStatusSelector(
    status: SlockTaskStatus,
    label: String,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onStatusSelected: (SlockTaskStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        SlockStatusLabel(
            status = status,
            label = label,
            modifier =
                if (enabled) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClick = { onExpandedChange(!expanded) }
                    )
                } else {
                    Modifier
                }
        )
        if (expanded) {
            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true)
            ) {
                SlockMenuOverlay(
                    actions =
                        SlockTaskStatus.entries.map { option ->
                            SlockMenuOverlayAction(
                                label = option.displayLabel,
                                icon = SlockIcons.Check.takeIf { option == status },
                                onClick = {
                                    onExpandedChange(false)
                                    onStatusSelected(option)
                                }
                            )
                        },
                    width = 184.dp
                )
            }
        }
    }
}

@Composable
fun SlockStatusLabel(
    status: SlockTaskStatus,
    label: String = status.displayLabel,
    modifier: Modifier = Modifier
) {
    Text(
        text = label.uppercase(),
        style = SlockType.SectionLabel,
        color = SlockColor.TextBlack,
        modifier = modifier
            .background(status.color)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs)
    )
}

private val SlockTaskStatus.displayLabel: String
    get() = when (this) {
        SlockTaskStatus.Todo -> "TODO"
        SlockTaskStatus.InProgress -> "IN PROGRESS"
        SlockTaskStatus.InReview -> "IN REVIEW"
        SlockTaskStatus.Done -> "DONE"
        SlockTaskStatus.Closed -> "CLOSED"
    }

private val SlockTaskStatus.color: Color
    get() = when (this) {
        SlockTaskStatus.Todo -> SlockColor.AccentOrange
        SlockTaskStatus.InProgress -> SlockColor.AccentCyan
        SlockTaskStatus.InReview -> SlockColor.BrandYellow
        SlockTaskStatus.Done -> SlockColor.AccentLime
        SlockTaskStatus.Closed -> SlockColor.TextMutedGray
    }

data class SlockTaskBoardCopy(
    val emptyColumnDetail: String,
    val emptyColumnTitle: (SlockTaskStatus) -> String,
    val statusLabel: (SlockTaskStatus) -> String
) {
    companion object {
        @Composable
        fun defaults(): SlockTaskBoardCopy {
            val emptyColumnTitleFormat = stringResource(R.string.tasks_empty_column_title)
            val todo = stringResource(R.string.tasks_status_todo)
            val inProgress = stringResource(R.string.tasks_status_in_progress)
            val inReview = stringResource(R.string.tasks_status_in_review)
            val done = stringResource(R.string.tasks_status_done)
            val closed = stringResource(R.string.tasks_status_closed)

            return SlockTaskBoardCopy(
                emptyColumnDetail = stringResource(R.string.tasks_empty_column_detail),
                emptyColumnTitle = { status ->
                    emptyColumnTitleFormat.format(
                        when (status) {
                            SlockTaskStatus.Todo -> todo
                            SlockTaskStatus.InProgress -> inProgress
                            SlockTaskStatus.InReview -> inReview
                            SlockTaskStatus.Done -> done
                            SlockTaskStatus.Closed -> closed
                        }.lowercase()
                    )
                },
                statusLabel = { status ->
                    when (status) {
                        SlockTaskStatus.Todo -> todo
                        SlockTaskStatus.InProgress -> inProgress
                        SlockTaskStatus.InReview -> inReview
                        SlockTaskStatus.Done -> done
                        SlockTaskStatus.Closed -> closed
                    }
                }
            )
        }
    }
}
