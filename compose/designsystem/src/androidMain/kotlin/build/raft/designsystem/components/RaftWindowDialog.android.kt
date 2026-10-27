package build.raft.designsystem.components

import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

internal actual fun raftWindowDialogPlatformProperties(properties: RaftWindowDialogProperties): DialogProperties =
    DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
        // Edge to edge like the host activity: the content pads for the system bars and the
        // keyboard itself (WindowInsets.safeDrawing), so DialogLayout must not.
        decorFitsSystemWindows = false,
    )

@Composable
internal actual fun RaftDialogWindowPolicy(isDark: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window) {
        // RaftWindowDialog draws RaftTheme.scrim; the theme's dim would double it.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.applyRaftTransparentSystemBars()
        onDispose {}
    }
    // Keyed on isDark: an appearance change with the dialog open re-applies it.
    DisposableEffect(window, isDark) {
        window.applyRaftSystemBarsAppearance(isDark)
        onDispose {}
    }
}

/**
 * Transparent status and navigation bars without the platform's contrast scrims, for the host
 * activity and every [RaftWindowDialog] window. Before API 26 the navigation bar icons cannot
 * turn dark, so the navigation bar keeps a 40% black wash there to keep its white icons legible.
 */
@Suppress("DEPRECATION")
fun Window.applyRaftTransparentSystemBars() {
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Color.TRANSPARENT else 0x66000000
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
}

/**
 * The system bar icons for a window drawn in a theme that [isDark] describes
 * ([raftSystemBarsAppearance]): the host activity and every [RaftWindowDialog] window.
 */
fun Window.applyRaftSystemBarsAppearance(isDark: Boolean) {
    applyRaftSystemBarsAppearance(raftSystemBarsAppearance(isDark))
}

/** The system bar icons [appearance] asks for (the host activity: from what its page paints there). */
fun Window.applyRaftSystemBarsAppearance(appearance: RaftSystemBarsAppearance) {
    WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = appearance.lightStatusBars
        isAppearanceLightNavigationBars = appearance.lightNavigationBars
    }
}
