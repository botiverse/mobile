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
    )

/**
 * Nothing to do: a CMP dialog on iOS is a layer in the host's UIWindow, whose
 * `overrideUserInterfaceStyle` (and so the status bar) already follows the theme
 * (RaftIosSceneDelegate; the status bar style through RaftCmpRoot's onSystemBarsResolved).
 */
@Composable
internal actual fun RaftDialogWindowPolicy(isDark: Boolean) = Unit

@Composable
internal actual fun RaftPopupWindowPolicy() = Unit
