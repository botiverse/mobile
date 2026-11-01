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
 * Nothing to do: the desktop target only renders components for the screenshot tests.
 */
@Composable
internal actual fun RaftDialogWindowPolicy(isDark: Boolean) = Unit

@Composable
internal actual fun RaftPrepareDialogWindow(isDark: Boolean) = Unit

@Composable
internal actual fun RaftPopupWindowPolicy(onReady: () -> Unit) = Unit

internal actual val RaftPopupDefersFocus: Boolean = false

internal actual val RaftPopupClipsDrawing: Boolean = false
