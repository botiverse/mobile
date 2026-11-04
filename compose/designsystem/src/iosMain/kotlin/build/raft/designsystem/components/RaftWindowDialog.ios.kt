package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun raftWindowDialogPlatformProperties(properties: RaftWindowDialogProperties): DialogProperties =
    DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
        // Full screen: the scrim covers the safe areas, the content pads for them itself.
        usePlatformInsets = false,
        useSoftwareKeyboardInset = !properties.contentHandlesKeyboard,
        // RaftWindowDialog draws RaftTheme.scrim.
        scrimColor = Color.Transparent,
        // Compose's own dialog transition (the whole window fading in from 20% while it grows from
        // 95% and drops 10dp, 200ms; 100ms back out) is not Raft motion: a centered overlay appears
        // and goes at once, a sheet or drawer slides itself (raft-design Motion).
        animateTransition = false,
    )

/**
 * Nothing to do: a CMP dialog on iOS is a layer in the host's UIWindow, whose
 * `overrideUserInterfaceStyle` (and so the status bar) already follows the theme
 * (RaftIosSceneDelegate; the status bar style through RaftCmpRoot's onSystemBarsResolved).
 */
@Composable
internal actual fun RaftDialogWindowPolicy(isDark: Boolean, keepsHostKeyboard: Boolean) = Unit

@Composable
internal actual fun RaftPrepareDialogWindow(isDark: Boolean) = Unit

@Composable
internal actual fun RaftPopupWindowPolicy(onReady: () -> Unit) = Unit

internal actual val RaftPopupDefersFocus: Boolean = false

internal actual val RaftPopupClipsDrawing: Boolean = false
