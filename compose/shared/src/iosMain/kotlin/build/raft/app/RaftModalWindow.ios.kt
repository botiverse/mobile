package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun raftModalDialogProperties(): DialogProperties =
    DialogProperties(
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
        // Kuikly's Modal was full screen: pages pad for the safe area and the keyboard themselves.
        usePlatformInsets = false,
        useSoftwareKeyboardInset = false,
        // Kuikly passed a transparent scrim; Slock draws its own SlockModalDismissBarrier.
        scrimColor = Color.Transparent,
    )

@Composable
internal actual fun RaftModalDialogWindowEffect() = Unit
