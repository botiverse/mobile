package build.raft.designsystem.components

import androidx.compose.ui.input.pointer.PointerIcon

// iPadOS has no resize pointer shape for Compose to show; the pointer stays the default.
internal actual fun raftSplitterPointerIcon(vertical: Boolean): PointerIcon = PointerIcon.Default
