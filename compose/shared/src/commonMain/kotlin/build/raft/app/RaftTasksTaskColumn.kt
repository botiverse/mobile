package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconToggleButton
import build.raft.designsystem.theme.RaftTheme

/*
 * The Tasks page's task beside its list on the wide layout (web TasksRoute, tasksPageOpen): the
 * list is the row's main pane, the task a column right of it at the detail column's width (web
 * `COLUMN_BOUNDS.detail`: 400, content at least 320, remembered), resizable on its left seam and
 * non-modal, so the list stays in use and another row replaces the task. Its head (the task
 * page's own, [LocalRaftTaskColumnHead]) carries Expand, which fills the row with it (#189 rule
 * 5, the thread's Focus mode), and Close. Where the row can't keep the list's 360 beside the
 * task's 320 the task takes the row, as on the web's phone, and Close (or Back) is the way back
 * to the list. Phones keep the pushed page: this layout is only used when a task is open in the
 * shell's wide detail.
 */

/** Whether the task column can stand beside the list in [roomDp] (the list's 360 + the column's 320). */
internal fun raftTasksTaskColumnBeside(roomDp: Float): Boolean =
    roomDp >= RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP + RaftPaneGridColumns.Detail.minWidthDp

/**
 * Which task each detail the task column opened showed, by [RaftAdaptiveDetailIdentity.key], so
 * Back / Forward through the wide history ([RaftWideNavHistory]) marks the card of the task it
 * reopens. Keeps the [cap] most recent.
 */
@Immutable
internal class RaftTasksColumnTaskIds(
    private val byDetailKey: Map<String, String> = emptyMap(),
    private val cap: Int = RaftWideNavHistoryCap,
) {
    /** [taskId] opened in the column as the detail [detailKey]. */
    fun opened(detailKey: String, taskId: String): RaftTasksColumnTaskIds {
        val next = (byDetailKey - detailKey) + (detailKey to taskId)
        val kept = if (next.size > cap) next.entries.drop(next.size - cap).associate { it.key to it.value } else next
        return RaftTasksColumnTaskIds(kept, cap)
    }

    /**
     * The task to mark once the history reopened [detailKey] in the column: its task, or [current]
     * for a detail the column reached by navigating inside it (a channel from the task's thread).
     */
    fun restored(detailKey: String, current: String?): String? = byDetailKey[detailKey] ?: current
}

/**
 * The task column's head actions, given to the page in the column (web TaskFocusContext + the
 * slot's close): [onToggleExpand] is null where the column already has the row to itself.
 */
@Immutable
internal class RaftTaskColumnHead(
    val expanded: Boolean,
    val onToggleExpand: (() -> Unit)?,
    val onClose: () -> Unit,
)

/** Set inside the Tasks page's task column only; the conversation head there adds [RaftTaskColumnHeadActions]. */
internal val LocalRaftTaskColumnHead = compositionLocalOf<RaftTaskColumnHead?> { null }

internal object RaftTasksTaskColumnTags {
    const val List = "tasks_task_column_list"
    const val Column = "tasks_task_column"
    const val Expand = "tasks_task_column_expand"
    const val Close = "tasks_task_column_close"
}

/**
 * Expand (pressed: Restore) and Close, the head's last two actions (raft-design panel.md: a
 * window control, after ⋯, Close last; web ExpandToggleAction and the task head's ×).
 */
@Composable
internal fun RaftTaskColumnHeadActions(head: RaftTaskColumnHead) {
    head.onToggleExpand?.let { toggle ->
        val label =
            stringResource(if (head.expanded) Res.string.tasks_task_column_restore else Res.string.tasks_task_column_expand)
        RaftIconToggleButton(
            checked = head.expanded,
            onCheckedChange = { toggle() },
            tooltip = label,
            modifier = Modifier.testTag(RaftTasksTaskColumnTags.Expand),
        ) {
            RaftContentLucideIcon(
                if (head.expanded) RaftLucideIconName.Minimize2 else RaftLucideIconName.Maximize2,
                size = RaftTheme.controls.barIconSize,
            )
        }
    }
    RaftIconButton(
        onClick = head.onClose,
        tooltip = stringResource(Res.string.tasks_task_column_close),
        modifier = Modifier.testTag(RaftTasksTaskColumnTags.Close),
        variant = RaftButtonVariant.Ghost,
        size = RaftIconButtonSize.Lg,
    ) {
        RaftContentLucideIcon(RaftLucideIconName.X, size = RaftTheme.controls.barIconSize)
    }
}

/**
 * The Tasks page ([list]) with the task open beside it ([task], while [open]). [expanded] is the
 * column's Expand; [onBack] is Back once the column is not expanded (the shell unwinds navigation
 * made inside the column, then closes it). Back is the column's while it is open and [active]:
 * Expand first, as the web's Escape (rowPlanStore) before the task's own.
 */
@Composable
internal fun RaftTasksTaskColumnLayout(
    open: Boolean,
    expanded: Boolean,
    active: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    task: @Composable () -> Unit,
    list: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val beside = raftTasksTaskColumnBeside(maxWidth.value)
        val expandedShown = open && beside && expanded
        RaftBackHandler(
            enabled = active && open,
            label = "tasks_task_column",
            // Beside the list the column is page state, as the Members split's detail: no swipe block.
            // Taking the row (no room beside the list) it stands as a pushed page over the list: the
            // host's page swipe must not pop the shell under it; Back returns to the list.
            blocksBackSwipe = !beside,
        ) {
            if (expandedShown) onToggleExpand() else onBack()
        }
        val head =
            RaftTaskColumnHead(
                expanded = expandedShown,
                onToggleExpand = onToggleExpand.takeIf { beside },
                onClose = onClose,
            )
        val currentHead by rememberUpdatedState(head)
        val currentTask by rememberUpdatedState(task)
        val resizeLabel = stringResource(Res.string.tasks_task_column_resize)
        // Kept across recompositions so the page in it only recomposes for what it reads.
        val column =
            remember(resizeLabel) {
                RaftPaneGridColumn(
                    bounds = RaftPaneGridColumns.Detail,
                    resizeLabel = resizeLabel,
                    cells =
                        listOf(
                            RaftPaneGridCell(id = "task") {
                                CompositionLocalProvider(LocalRaftTaskColumnHead provides currentHead) {
                                    Box(Modifier.fillMaxSize().testTag(RaftTasksTaskColumnTags.Column)) { currentTask() }
                                }
                            }
                        ),
                )
            }
        RaftPaneGrid(
            columns = if (open) listOf(column) else emptyList(),
            modifier = Modifier.fillMaxSize(),
            // Expanded, or no room beside the list: the task takes the row; the list stays composed.
            fillColumn = RaftPaneGridColumns.Detail.id.takeIf { open && (expandedShown || !beside) },
        ) {
            Box(Modifier.fillMaxSize().testTag(RaftTasksTaskColumnTags.List)) { list() }
        }
    }
}
