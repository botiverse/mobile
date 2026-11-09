package build.raft.app

import android.os.Looper
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalView
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
 * artin (dm fff6fafe, 2026-10-28): opening a menu or a dialog changed the status bar icons. A dialog
 * window that holds focus decides the bar icons; since #3623 it guessed them from the mode instead of
 * copying the host, which is wrong on a frame that is dark in light mode (Space, Quest). This fails on
 * the old code.
 *
 * The menu half (RaftPopupWindowPolicy) has no test here: Robolectric does not model which window drives
 * the bar icons, a popup test passed on the old code too, and an open RaftDropdownMenu hung the
 * Robolectric run (60s watchdog). It is verified on a device.
 *
 * artin (dm 302181ad, 2026-11-01): the dialog's window is shown before its content composes, so a
 * copy made from the content leaves the focused window a frame of the default icons. The second
 * test reads the window as its content first composes, before any effect has run: it already has
 * to ask for the host's icons (RaftPrepareDialogWindow, set before show() as Material3 does).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RaftWindowDialogSystemBarsTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

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

    @Suppress("DEPRECATION")
    @Test
    fun aDialogWindowAsksForTheHostsIconsBeforeItsContentRuns() {
        var firstComposition: Int? = null
        var dialogRoot: View? = null
        val activity = activities.launch()
        // The page asked for DARK icons; the activity theme's default (no windowLightStatusBar) is
        // light icons, which is what a window created without the host's appearance shows.
        activity.window.decorView.systemUiVisibility =
            activity.window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        activity.setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftWindowDialog(onDismissRequest = {}) {
                    val root = LocalView.current.rootView
                    // In composition, ahead of every effect: what the window asked for when shown.
                    if (firstComposition == null) firstComposition = root.systemUiVisibility
                    dialogRoot = root
                }
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
        val root = assertNotNull(dialogRoot, "the dialog window")
        assertTrue(root !== activity.window.decorView, "the dialog is its own window")
        val shown = assertNotNull(firstComposition, "the dialog content composed")
        assertTrue(
            shown and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0,
            "the dialog window already asks for the host's dark icons when it is shown, before its content runs"
        )
    }
}
