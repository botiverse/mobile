package build.raft.designsystem.components

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

internal actual fun raftSplitterPointerIcon(vertical: Boolean): PointerIcon =
    PointerIcon(Cursor(if (vertical) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR))
