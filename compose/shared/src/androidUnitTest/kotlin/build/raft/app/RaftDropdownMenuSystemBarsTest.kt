package build.raft.app

import android.os.Looper
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalView
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * artin (dm fff6fafe, 2026-10-28): opening the Activity filter menu turned the status bar icons
 * white on the yellow frame. A focusable popup is its own window on Android and, while it holds
 * focus, the bar icons follow its appearance; it must carry the host's light-bar flags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RaftDropdownMenuSystemBarsTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34])
    fun anOpenMenuKeepsTheHostsDarkStatusBarIcons() {
        var popupRoot: View? = null
        val activity = activities.launch()
        val lightStatusBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        // The page asked for dark icons (Raft Yellow frame), through the API 30+ controller.
        assertNotNull(activity.window.insetsController).setSystemBarsAppearance(lightStatusBars, lightStatusBars)
        activity.setContent {
            RaftTheme {
                RaftDropdownMenu(expanded = true, onDismissRequest = {}) {
                    popupRoot = LocalView.current.rootView
                }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
        val root = assertNotNull(popupRoot, "the menu's popup window")
        assertTrue(root !== activity.window.decorView, "the menu is its own window")
        val popupAppearance = assertNotNull(root.windowInsetsController, "the popup window's controller").systemBarsAppearance
        assertTrue(
            popupAppearance and lightStatusBars != 0,
            "the popup window keeps the host's dark status bar icons (appearance=$popupAppearance)"
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun aDialogCopiesTheHostsIconsInsteadOfGuessingFromTheMode() {
        var dialogRoot: View? = null
        val activity = activities.launch()
        // Light mode on a dark frame (Space, Quest): the page asked for LIGHT icons, so no flag.
        activity.window.decorView.systemUiVisibility =
            activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        activity.setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftWindowDialog(onDismissRequest = {}) {
                    dialogRoot = LocalView.current.rootView
                }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
        val root = assertNotNull(dialogRoot, "the dialog window")
        assertTrue(root !== activity.window.decorView, "the dialog is its own window")
        assertTrue(
            root.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR == 0,
            "the dialog keeps the host's light icons on a dark frame, light mode or not"
        )
    }
}
