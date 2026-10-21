package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.RaftHostImeInsets
import java.time.Duration
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Hands 1af517cc: the keyboard came up in a DM and the composer stayed behind it. Robolectric
 * never dispatches an IME inset, so Compose's `WindowInsets.ime` stays 0 here: exactly the stale
 * state the device was in. The host report alone must lift the composer and hide the shell bar,
 * and an IME animation in flight must hand the geometry back to `WindowInsets.ime`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class RaftThreadComposerHostImeLiftTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val composerHeight = 48.dp

    @Test
    fun hostReportedKeyboardLiftsTheComposerWhenComposeInsetsAreStale() {
        val activity = activities.launch()
        val host = RaftHostImeInsets()
        var composerBottomPx = -1
        var rootHeightPx = -1
        var imeVisible: Boolean? = null
        activity.setContent {
            CompositionLocalProvider(LocalRaftHostImeInsets provides host) {
                val visible = raftImeVisible()
                SideEffect { imeVisible = visible }
                Column(
                    Modifier.fillMaxSize().onGloballyPositioned { rootHeightPx = it.size.height }
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f))
                    Box(
                        Modifier.fillMaxWidth().raftThreadComposerLiveKeyboardPadding(
                            bottomSafeArea = 0.dp,
                            keyboardInsetExcludesBottomSafeArea = true,
                        )
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(composerHeight).onGloballyPositioned {
                                composerBottomPx = (it.positionInRoot().y + it.size.height).roundToInt()
                            }
                        )
                    }
                }
            }
        }
        settle(activity)
        val density = activity.resources.displayMetrics.density
        fun restingGapPx(keyboardPx: Int): Int =
            (threadComposerBottomPadding(
                keyboardInsetDp = keyboardPx / density,
                bottomSafeArea = 0.dp,
                keyboardInsetExcludesBottomSafeArea = true,
            ).value * density).roundToInt()

        assertEquals(rootHeightPx - restingGapPx(0), composerBottomPx, "keyboard closed")
        assertEquals(false, imeVisible, "keyboard closed")

        // The keyboard opened with no animation reaching Compose: only the host knows.
        val keyboardPx = 1006
        host.imeBottomPx = keyboardPx
        settle(activity)
        assertEquals(rootHeightPx - restingGapPx(keyboardPx), composerBottomPx, "host-reported keyboard")
        assertEquals(true, imeVisible, "the shell bar steps aside for the host-reported keyboard")

        // While a platform IME animation runs, WindowInsets.ime (0 here) drives the frames.
        host.imeAnimationRunning = true
        settle(activity)
        assertEquals(rootHeightPx - restingGapPx(0), composerBottomPx, "animation in flight")

        host.imeAnimationRunning = false
        host.imeBottomPx = 0
        settle(activity)
        assertEquals(rootHeightPx - restingGapPx(0), composerBottomPx, "keyboard closed again")
        assertEquals(false, imeVisible, "keyboard closed again")
    }

    // Robolectric does not draw, and a layout-only invalidation is only flushed by a draw pass, so
    // run the pending measure/layout explicitly after the frames.
    private fun settle(activity: ComponentActivity) {
        Snapshot.sendApplyNotifications()
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        (findComposeRoot(activity.window.decorView) ?: error("no compose root")).measureAndLayoutForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
