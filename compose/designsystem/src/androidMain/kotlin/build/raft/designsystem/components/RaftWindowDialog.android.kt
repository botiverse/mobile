package build.raft.designsystem.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
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
    // The host activity's bar icons, not a guess from isDark: the page sets them from what it
    // paints under the bars (a theme's frame can be dark in light mode: Space, Quest), and a
    // dialog window that holds focus decides the icons (#3618, artin 2026-10-28). Keyed on
    // isDark so an appearance change with the dialog open re-copies; isDark is the fallback
    // when there is no host activity.
    val hostWindow = LocalContext.current.raftActivity()?.window
    DisposableEffect(window, isDark, hostWindow) {
        if (hostWindow != null) {
            window.decorView.copyRaftSystemBarsAppearanceFrom(hostWindow)
        } else {
            window.applyRaftSystemBarsAppearance(isDark)
        }
        onDispose {}
    }
}

@Composable
internal actual fun RaftPopupWindowPolicy() {
    val popupView = LocalView.current
    val hostWindow = LocalContext.current.raftActivity()?.window ?: return
    // Every recomposition re-copies it: cheap, and it follows a host change while the menu is open.
    SideEffect { popupView.rootView.copyRaftSystemBarsAppearanceFrom(hostWindow) }
}

private fun Context.raftActivity(): Activity? {
    var context: Context? = this
    while (context != null) {
        if (context is Activity) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}

/** Gives the window [this] root view sits in the host's light/dark bar icons. */
@Suppress("DEPRECATION")
private fun View.copyRaftSystemBarsAppearanceFrom(host: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val hostAppearance = host.insetsController?.systemBarsAppearance ?: return
        val controller = windowInsetsController ?: return
        // Only on a change: setting it relayouts the window, which must not feed a recomposition loop.
        if ((controller.systemBarsAppearance and mask) != (hostAppearance and mask)) {
            controller.setSystemBarsAppearance(hostAppearance and mask, mask)
        }
    } else {
        val mask =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val hostFlags = host.decorView.systemUiVisibility and mask
        val next = (systemUiVisibility and mask.inv()) or hostFlags
        if (next != systemUiVisibility) systemUiVisibility = next
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
