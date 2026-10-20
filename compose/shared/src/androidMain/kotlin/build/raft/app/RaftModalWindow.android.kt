package build.raft.app

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
            // Lay out into the display cutout like the host activity (RaftCmpActivity
            // setupImmersiveMode): under the default mode a dialog window is letterboxed out of
            // the cutout band whenever the status bar is hidden, so the modal veil stopped short
            // of the top edge and the page showed through above it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                val attrs = window.attributes
                if (attrs.layoutInDisplayCutoutMode != cutoutMode) {
                    attrs.layoutInDisplayCutoutMode = cutoutMode
                    window.attributes = attrs
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            // Same light bar appearance as the host activity (RaftCmpActivity.applyLightSystemBarsAppearance).
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
        onDispose {}
    }
}
