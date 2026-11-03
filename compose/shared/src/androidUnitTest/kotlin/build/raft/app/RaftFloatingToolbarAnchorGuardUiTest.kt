package build.raft.app

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin 2026-11-03: cancelling a selection in the composer flashed the floating text toolbar at
 * the top-left corner of the screen (with "Paste / Select all") before it went away. A plain
 * BasicTextField with Compose's own platform toolbar (no Raft code around it): a long-press opens
 * the floating bar, a tap collapses the selection. Every anchor the platform is given for the bar
 * is recorded between Compose's callback and the window, without and with the guard that
 * RaftTraceFrameLayout installs over every page.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
// Real text layout, so a long-press lands on a word.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [RaftFloatingToolbarAnchorGuardUiTest.NoSurfaceMagnifier::class])
class RaftFloatingToolbarAnchorGuardUiTest {
    /** Robolectric's Magnifier has no Surface and throws when a long-press dismisses it. */
    @Implements(android.widget.Magnifier::class)
    class NoSurfaceMagnifier {
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) {}

        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) {}

        @Implementation fun update() {}

        @Implementation fun dismiss() {}
    }

    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun withoutTheGuardComposeAnchorsTheClosingBarAtTheWindowOrigin() {
        val seen = selectThenCollapse(guarded = false)
        assertTrue(seen.anchors.firstOrNull()?.isAnchor() == true, "the bar opens at the text: ${seen.anchors}")
        assertTrue(
            seen.anchors.any { !it.isAnchor() },
            "Compose reports a 0x0 anchor while the bar is still open: ${seen.anchors}",
        )
    }

    @Test
    fun theGuardKeepsTheClosingBarWhereItWas() {
        val seen = selectThenCollapse(guarded = true)
        assertTrue(seen.anchors.isNotEmpty())
        assertTrue(seen.anchors.all { it.isAnchor() }, "never a 0x0 anchor: ${seen.anchors}")
        assertEquals(seen.anchors.first(), seen.anchors.last(), "the bar closes where it was")
        assertTrue(seen.closed, "the bar still closes")
    }

    @Test
    fun onlyFloatingCallback2sAreWrapped() {
        val plain = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        assertTrue(RaftFloatingToolbarAnchorGuard.wrap(plain, ActionMode.TYPE_FLOATING) === plain)
        val floating = RecordingCallback(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) {}
        }, Seen())
        assertTrue(RaftFloatingToolbarAnchorGuard.wrap(floating, ActionMode.TYPE_PRIMARY) === floating)
        assertTrue(RaftFloatingToolbarAnchorGuard.wrap(floating, ActionMode.TYPE_FLOATING) is RaftFloatingToolbarAnchorGuard)
    }

    private fun selectThenCollapse(guarded: Boolean): Seen {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).addOrUpdateActivity(
            android.content.pm.ActivityInfo().apply {
                name = ComponentActivity::class.java.name
                packageName = context.packageName
            }
        )
        val seen = Seen()
        showSelectThenCollapse(guarded, seen)
        return seen
    }

    private fun showSelectThenCollapse(guarded: Boolean, seen: Seen) {
        val fieldState = TextFieldState("hello world test")
        runAndroidComposeUiTest<ComponentActivity> {
            runOnUiThread {
                val host = RecordingHost(activity!!, guarded, seen)
                host.addView(
                    ComposeView(activity!!).apply {
                        setContent {
                            Column(Modifier.padding(top = 200.dp, start = 40.dp)) {
                                BasicTextField(
                                    state = fieldState,
                                    modifier = Modifier.testTag(FIELD).fillMaxWidth(),
                                )
                            }
                        }
                    },
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                activity!!.setContentView(host)
            }
            waitForIdle()
            onNodeWithTag(FIELD).performTouchInput { longClick(Offset(width * 0.4f, centerY)) }
            waitForIdle()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertTrue(seen.started, "the long-press opens the floating text toolbar")
            // Cancel it the way artin did: tap the empty end of the field.
            onNodeWithTag(FIELD).performTouchInput { click(Offset(width - 2f, centerY)) }
            waitForIdle()
            mainClock.advanceTimeBy(100)
            waitForIdle()
        }
    }

    private class Seen {
        var started = false
        var closed = false

        /** Every anchor the platform was given for the bar, in order. */
        val anchors = mutableListOf<Rect>()
    }

    /** The window side: records what the platform reads from the (guarded) callback. */
    private class RecordingHost(context: Context, private val guarded: Boolean, private val seen: Seen) : FrameLayout(context) {
        override fun startActionModeForChild(originalView: View?, callback: ActionMode.Callback?, type: Int): ActionMode? {
            val inner = if (guarded && callback != null) RaftFloatingToolbarAnchorGuard.wrap(callback, type) else callback
            if (type == ActionMode.TYPE_FLOATING && inner is ActionMode.Callback2) seen.started = true
            return super.startActionModeForChild(originalView, inner?.let { RecordingCallback(it, seen) }, type)
        }
    }

    private class RecordingCallback(private val delegate: ActionMode.Callback, private val seen: Seen) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = delegate.onCreateActionMode(mode, menu)

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = delegate.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = delegate.onActionItemClicked(mode, item)

        override fun onDestroyActionMode(mode: ActionMode) {
            seen.closed = true
            delegate.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(mode: ActionMode, view: View?, outRect: Rect) {
            (delegate as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect)
            seen.anchors += Rect(outRect)
        }
    }

    private companion object {
        const val FIELD = "field"

        fun Rect.isAnchor() = RaftFloatingToolbarAnchorGuard.isAnchor(this)
    }
}
