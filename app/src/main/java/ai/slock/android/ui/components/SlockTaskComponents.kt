package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
    modifier: Modifier = Modifier,
    copy: SlockTaskBoardCopy = SlockTaskBoardCopy.defaults()
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockTaskStatus.entries.forEach { status ->
            SlockTaskColumn(
                status = status,
                tasks = columns[status].orEmpty(),
                copy = copy,
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            SlockStatusLabel(status = status, label = copy.statusLabel(status))
            Text(
                text = tasks.size.toString(),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        if (tasks.isEmpty()) {
            SlockEmptyState(
                title = copy.emptyColumnTitle(status),
                detail = copy.emptyColumnDetail
            )
        } else {
            tasks.forEach { task ->
                SlockTaskCard(task = task, statusLabel = copy.statusLabel(task.status))
            }
        }
    }
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
        Box {
            SlockStatusLabel(
                status = task.status,
                label = if (task.statusUpdating) task.updatingLabel else statusLabel,
                modifier =
                    task.onStatusSelected?.let {
                        Modifier.clickable(
                            enabled = !task.statusUpdating,
                            role = Role.Button,
                            onClick = { statusMenuExpanded = !statusMenuExpanded }
                        )
                    } ?: Modifier
            )
            if (statusMenuExpanded) {
                Popup(
                    alignment = Alignment.TopStart,
                    onDismissRequest = { statusMenuExpanded = false },
                    properties = PopupProperties(focusable = true)
                ) {
                    Column(
                        modifier =
                            Modifier
                                .width(180.dp)
                                .padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                                )
                                .slockHardShadow(SlockShadow.Small)
                                .background(SlockColor.SurfaceWhite)
                                .border(SlockStroke.Default, SlockColor.BorderBlack)
                    ) {
                        SlockTaskStatus.entries.forEach { status ->
                            SlockTaskStatusDropdownRow(
                                status = status,
                                label = status.displayLabel,
                                selected = status == task.status,
                                onClick = {
                                    statusMenuExpanded = false
                                    task.onStatusSelected?.invoke(status)
                                }
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = task.title,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = task.source,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
    }
}

@Composable
private fun SlockTaskStatusDropdownRow(
    status: SlockTaskStatus,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) status.color else SlockColor.SurfaceWhite)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Icon(
            imageVector = SlockIcons.ChevronRight,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.width(12.dp)
        )
        Text(
            text = label,
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
        style = SlockType.Caption,
        color = SlockColor.TextBlack,
        modifier = modifier
            .background(status.color)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Sm, vertical = 2.dp)
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
