package build.raft.app

import build.raft.app.model.TaskStatus
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class SharedTaskStatusChipMeta(
    val status: TaskStatus,
    val key: String,
    val label: String,
    val icon: RaftLucideIconName,
    val color: Color,
    val foregroundColor: Color = RaftColor.StickerInk,
    val iconSize: Dp = 10.dp
)

internal val SharedTaskStatusChipMetas: List<SharedTaskStatusChipMeta> =
    listOf(
        SharedTaskStatusChipMeta(TaskStatus.Todo, "Todo", "Todo", RaftLucideIconName.Circle, RaftColor.AccentOrange),
        SharedTaskStatusChipMeta(TaskStatus.InProgress, "InProgress", "In progress", RaftLucideIconName.Play, RaftColor.AccentCyan),
        SharedTaskStatusChipMeta(TaskStatus.InReview, "InReview", "In review", RaftLucideIconName.Eye, RaftColor.AccentLavender),
        SharedTaskStatusChipMeta(TaskStatus.Done, "Done", "Done", RaftLucideIconName.CircleCheckBig, RaftColor.AccentLime),
        SharedTaskStatusChipMeta(TaskStatus.Closed, "Closed", "Closed", RaftLucideIconName.Ban, RaftColor.AccentStone)
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
