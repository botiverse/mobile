package build.raft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.motion.RaftMotion
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app shell's kept-alive tab stack with Android's fade through ([AppShellTabFadeThroughStack]):
 * a tab switch, animated or not, keeps every tab composed (scroll position, loaded data) and loads
 * nothing again; the fade keeps the outgoing tab on screen while it fades out, and reduced motion
 * switches at once. The fade's timing curve is AppShellTabFadeThroughFrameTest's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class AppShellTabFadeThroughTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Fixture {
        var selected by mutableStateOf("home")
        var retained by mutableStateOf(listOf("home"))
        val loads = mutableMapOf<String, Int>()
        val compositions = mutableMapOf<String, Int>()
        val listStates = mutableMapOf<String, LazyListState>()
        /** Whether each tab's layer is on screen (placed) as of its last composition. */
        val placed = mutableMapOf<String, Boolean>()

        fun select(tab: String) {
            if (tab !in retained) retained = retained + tab
            selected = tab
        }
    }

    private fun launch(fixture: Fixture, reducedMotion: Boolean): View {
        // Frames only when the test moves the clock: by default Robolectric's Choreographer
        // advances the clock itself on each frame, so a whole 300ms animation ran inside one idle.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = activities.launch()
        activity.setContent {
            AppShellTabFadeThroughStack(
                activeTab = fixture.selected,
                tabs = fixture.retained,
                fadeThrough = true,
                reducedMotion = reducedMotion,
            ) { tab, _, placed ->
                fixture.placed[tab] = placed
                remember { fixture.compositions[tab] = (fixture.compositions[tab] ?: 0) + 1 }
                val listState = rememberLazyListState()
                fixture.listStates[tab] = listState
                // A tab's first load, as a page store's would run when its page first composes.
                LaunchedEffect(Unit) {
                    fixture.loads[tab] = (fixture.loads[tab] ?: 0) + 1
                    if (tab == "home") listState.scrollToItem(30)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(TabColors.getValue(tab)),
                ) {
                    items(100) { Spacer(Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
        }
        idle(400)
        return assertNotNull(findComposeRoot(activity.window.decorView), "no compose root")
    }

    @After
    fun resumeChoreographer() {
        ShadowChoreographer.setPaused(false)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(15))
    }

    @Test
    fun fadeThroughKeepsEachTabsStateAndLoadsNothingAgain() {
        val fixture = Fixture()
        val root = launch(fixture, reducedMotion = false)
        val homeList = fixture.listStates.getValue("home")
        assertEquals(30, homeList.firstVisibleItemIndex, "home scrolled before the switch")

        fixture.select("tasks")
        idle(48)
        // Within the outgoing part of the fade (105ms) Home is still on screen, under Tasks.
        assertEquals(mapOf("home" to true, "tasks" to true), fixture.placed, "mid-fade: both tabs on screen")
        val midFade = centerPixel(root)
        assertTrue(
            Color.red(midFade) == 0xFF && Color.blue(midFade) in 1 until 0xFF && Color.green(midFade) > 0,
            "mid-fade: Home part faded over the window, Tasks not yet in: ${Integer.toHexString(midFade)}",
        )
        idle(RaftMotion.FadeThroughMillis.toLong() + 100)
        assertEquals(mapOf("home" to false, "tasks" to true), fixture.placed, "only Tasks once the fade ends")
        assertEquals(TasksArgb, centerPixel(root), "Tasks drawn at rest")

        fixture.select("home")
        idle(RaftMotion.FadeThroughMillis.toLong() + 100)
        assertEquals(mapOf("home" to true, "tasks" to false), fixture.placed, "only Home after switching back")
        assertEquals(HomeArgb, centerPixel(root), "Home drawn at rest")

        assertSame(homeList, fixture.listStates.getValue("home"), "Home kept its list state (not re-created)")
        assertEquals(30, homeList.firstVisibleItemIndex, "Home kept its scroll position")
        assertEquals(mapOf("home" to 1, "tasks" to 1), fixture.loads, "a tab switch loads nothing again")
        assertEquals(mapOf("home" to 1, "tasks" to 1), fixture.compositions, "no tab was re-created")
    }

    @Test
    fun reducedMotionSwitchesAtOnce() {
        val fixture = Fixture()
        val root = launch(fixture, reducedMotion = true)

        fixture.select("tasks")
        idle(20)
        assertEquals(mapOf("home" to false, "tasks" to true), fixture.placed, "reduced motion: Home gone in the first frame")
        assertEquals(TasksArgb, centerPixel(root), "reduced motion: Tasks at once")

        fixture.select("home")
        idle(20)
        assertEquals(mapOf("home" to true, "tasks" to false), fixture.placed, "reduced motion: Tasks gone in the first frame")
        assertEquals(HomeArgb, centerPixel(root), "reduced motion: Home at once")
        assertEquals(30, fixture.listStates.getValue("home").firstVisibleItemIndex, "Home kept its scroll position")
        assertEquals(mapOf("home" to 1, "tasks" to 1), fixture.loads, "a tab switch loads nothing again")
    }

    /** The window's center pixel right now (each tab fills itself with its own colour). */
    private fun centerPixel(root: View): Int {
        val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        root.draw(Canvas(bitmap))
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    private companion object {
        const val HomeArgb: Int = 0xFFFF0000.toInt()
        const val TasksArgb: Int = 0xFF0000FF.toInt()
        val TabColors = mapOf("home" to ComposeColor(HomeArgb), "tasks" to ComposeColor(TasksArgb))
    }

    private fun idle(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, 16L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
