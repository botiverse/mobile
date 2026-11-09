package build.raft.designsystem.components

import androidx.compose.ui.input.pointer.PointerIcon

internal actual fun raftSplitterPointerIcon(vertical: Boolean): PointerIcon =
    PointerIcon(
        if (vertical) android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW else android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
    )
