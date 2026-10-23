package build.raft.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

internal actual fun raftModalDialogProperties(): DialogProperties =
    DialogProperties(
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
        // Edge to edge like the host activity: pages pad for the system bars and the keyboard
        // themselves (slock*SafeAreaPadding / keyboardHeightChange), so DialogLayout must not.
        decorFitsSystemWindows = false,
    )

@Composable
internal actual fun RaftModalDialogWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            // Slock draws its own veil (RaftModalDismissBarrier); the theme's dim doubled it.
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // No dialog-owned bar backgrounds: they painted a gray strip over the navigation bar.
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            // Same bar icon appearance as the host activity (RaftCmpActivity.applySystemBarAppearance):
            // dark icons in the light theme, light icons in the dark theme. A fixed `true` turned the
            // status bar icons black over the dark theme whenever a menu or sheet opened.
            val host = view.context.findActivity()?.window
            val hostBars = host?.let { WindowCompat.getInsetsController(it, it.decorView) }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = hostBars?.isAppearanceLightStatusBars ?: true
                isAppearanceLightNavigationBars = hostBars?.isAppearanceLightNavigationBars ?: true
            }
        }
        onDispose {}
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
