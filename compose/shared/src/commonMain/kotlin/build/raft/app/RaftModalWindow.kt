package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * Window setup for [Modal], which CMP renders as a platform `Dialog` window.
 *
 * Kuikly's Modal was a full-screen in-window overlay: no window dim (`scrimColor` was transparent
 * because Slock draws its own [RaftModalDismissBarrier]), drawn edge to edge under the system bars,
 * and the keyboard handled by the page through `keyboardHeightChange`. The platform defaults of a
 * CMP Dialog differ on every point (Android: dimmed window fitted between the system bars with its
 * own navigation-bar background and IME padding; iOS: default scrim, safe-area and keyboard
 * insets), which double-dimmed every modal and left a strip under bottom sheets.
 */
internal expect fun raftModalDialogProperties(): DialogProperties

/** Applies the window-level part of the setup above from inside the Dialog content. */
@Composable
internal expect fun RaftModalDialogWindowEffect()
