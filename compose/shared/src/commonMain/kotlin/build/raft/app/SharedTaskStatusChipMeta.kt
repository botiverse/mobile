package build.raft.app

import build.raft.app.model.TaskStatus
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class SharedTaskStatusChipMeta(
    val status: TaskStatus,
    val key: String,
    val label: String,
    val icon: SlockLucideIconName,
    val color: Color,
    val foregroundColor: Color = SlockColor.TextBlack,
    val iconSize: Dp = 10.dp
)

internal val SharedTaskStatusChipMetas: List<SharedTaskStatusChipMeta> =
    listOf(
        SharedTaskStatusChipMeta(TaskStatus.Todo, "Todo", "Todo", SlockLucideIconName.Circle, SlockColor.AccentOrange),
        SharedTaskStatusChipMeta(TaskStatus.InProgress, "InProgress", "In progress", SlockLucideIconName.Play, SlockColor.AccentCyan),
        SharedTaskStatusChipMeta(TaskStatus.InReview, "InReview", "In review", SlockLucideIconName.Eye, SlockColor.AccentLavender),
        SharedTaskStatusChipMeta(TaskStatus.Done, "Done", "Done", SlockLucideIconName.CircleCheckBig, SlockColor.AccentLime),
        SharedTaskStatusChipMeta(TaskStatus.Closed, "Closed", "Closed", SlockLucideIconName.Ban, SlockColor.AccentStone)
    )

internal fun TaskStatus.sharedTaskStatusChipMeta(): SharedTaskStatusChipMeta =
    SharedTaskStatusChipMetas.first { it.status == this }

internal fun String?.sharedTaskStatusChipMetaOrDefault(default: TaskStatus = TaskStatus.Todo): SharedTaskStatusChipMeta {
    val normalized = normalizedTaskStatusToken()
    return SharedTaskStatusChipMetas.firstOrNull { meta ->
        meta.key.normalizedTaskStatusToken() == normalized ||
            meta.label.normalizedTaskStatusToken() == normalized
    } ?: default.sharedTaskStatusChipMeta()
}

private fun String?.normalizedTaskStatusToken(): String =
    this?.trim()?.lowercase().orEmpty().replace("-", "").replace("_", "").replace(" ", "")
