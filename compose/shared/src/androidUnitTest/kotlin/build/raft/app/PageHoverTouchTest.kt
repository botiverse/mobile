package build.raft.app

import android.graphics.Bitmap
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.dp
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A finger never hovers (raft-ui `@media (hover: hover)`): on the message row, the Activity row,
 * the rail's server tile and a markdown link, a touch that the platform reports as hovering and
 * then taps leaves the frame exactly as at rest, while a mouse at the same point changes it (so
 * the check can fail). Pixels of the whole surface, not one spot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h200dp-xxhdpi")
class PageHoverTouchTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun aFingerLeavesNoHoverOnAMessageRow() = check("message-row", at = Offset(0.95f, 0.5f)) { HoverMessageCard(it) }

    @Test
    fun aFingerLeavesNoHoverOnAnActivityRow() =
        check("activity-row", at = Offset(0.5f, 0.5f)) { modifier -> Box(modifier) { HoverActivityRowContent() } }

    // No tap here: the tile's press holds for raft-ui's 120ms minimum on the wall clock, which
    // Robolectric's paused looper does not advance; the press is not a hover anyway.
    @Test
    fun aFingerLeavesNoHoverOnTheServerTile() =
        check("server-tile", at = Offset(0.5f, 0.5f), tap = false) { modifier -> Box(Modifier.padding(12.dp)) { Box(modifier) { HoverServerTileContent() } } }

    @Test
    fun aFingerLeavesNoHoverOnAMarkdownLink() =
        check("markdown-link", at = Offset(0.15f, 0.2f), tap = false) { HoverMarkdown(it) }

    /**
     * Renders [content] at rest, after a finger hovers (and taps, with [tap]) at [at] of it, and
     * after a mouse hovers there; the finger's frame must match rest, the mouse's must not.
     */
    private fun check(name: String, at: Offset, tap: Boolean = true, content: @Composable (Modifier) -> Unit) {
        val rest = render(content, at) { _, _ -> }
        val finger =
            render(content, at) { view, point ->
                hoverPointer(view, point, MotionEvent.TOOL_TYPE_FINGER)
                if (tap) tapFinger(view, point)
            }
        val mouse = render(content, at) { view, point -> hoverPointer(view, point, MotionEvent.TOOL_TYPE_MOUSE) }
        assertTrue(rest.sameAs(finger), "a finger left a hover behind")
        assertTrue(!rest.sameAs(mouse), "a mouse showed no hover (the check can't tell)")
    }

    private fun render(content: @Composable (Modifier) -> Unit, at: Offset, interact: (View, Offset) -> Unit): Bitmap {
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        var point = Offset.Zero
        activity.setContent {
            PageHoverHost(RaftAppAppearance.Light) {
                content(
                    Modifier.onGloballyPositioned { coordinates ->
                        val box = coordinates.boundsInRoot()
                        point = Offset(box.left + box.width * at.x, box.top + box.height * at.y)
                    }
                )
            }
        }
        settle()
        val view = findComposeRoot(activity.window.decorView)
        assertNotNull(view, "no compose root")
        interact(view, point)
        settle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun settle() {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
