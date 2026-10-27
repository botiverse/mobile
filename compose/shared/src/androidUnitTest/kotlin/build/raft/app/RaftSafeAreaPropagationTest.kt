package build.raft.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.designsystem.components.raftConsumeSafeAreaHorizontal
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration

/**
 * How the horizontal safe area reaches content: the page shell pads nothing (backgrounds run edge
 * to edge) and hands the inset down; `raftSafeAreaHorizontalPadding` pads it once per layout path,
 * so nested child pages, rows in a padded container and a modal never pad it twice; a side tab
 * bar's band joins the inset; physical edges stay physical in RTL. Bounds are in px at xxhdpi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h300dp-xxhdpi")
class RaftSafeAreaPropagationTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val bounds = mutableMapOf<String, Pair<Int, Int>>()
    private var rootWidth = 0
    private var density = 0f

    private fun px(dp: Int): Int = (dp * density).roundToInt()

    @Test
    fun pageShellPadsNothingAndContentPadsTheInsetOnce() {
        show(rightDp = 59) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(40.dp).track("background"))
                Box(Modifier.fillMaxWidth().height(40.dp).raftSafeAreaHorizontalPadding().track("content")) {
                    // Inside padded content: nothing left to pad.
                    Box(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().track("nested"))
                }
            }
        }
        assertEquals(0 to rootWidth, bounds.getValue("background"))
        assertEquals(0 to rootWidth - px(59), bounds.getValue("content"))
        assertEquals(0 to rootWidth - px(59), bounds.getValue("nested"))
    }

    @Test
    fun embeddedChildPageInsideAPaddedContainerDoesNotPadAgain() {
        show(rightDp = 59) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(60.dp).raftSafeAreaHorizontalPadding()) {
                    // An embedded child page: its own page shell, the same window.
                    RaftHorizontalSafeAreaShell(required = raftHorizontalSafeArea()) {
                        Box(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().track("child.padded"))
                    }
                }
                Box(Modifier.fillMaxWidth().height(60.dp)) {
                    RaftHorizontalSafeAreaShell(required = raftHorizontalSafeArea()) {
                        Box(Modifier.fillMaxWidth().track("child.background"))
                        Box(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().track("child.full"))
                    }
                }
            }
        }
        assertEquals(0 to rootWidth - px(59), bounds.getValue("child.padded"))
        // A full-width child page: its background reaches the edge, its content pads once.
        assertEquals(0 to rootWidth, bounds.getValue("child.background"))
        assertEquals(0 to rootWidth - px(59), bounds.getValue("child.full"))
    }

    @Test
    fun consumedSideIsNotPadded() {
        // The inner side of a split pane is away from the edge.
        show(leftDp = 59, rightDp = 59) {
            Box(Modifier.fillMaxWidth().raftConsumeSafeAreaHorizontal(WindowInsetsSides.Right)) {
                Box(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().track("pane"))
            }
        }
        assertEquals(px(59) to rootWidth, bounds.getValue("pane"))
    }

    @Test
    fun sideTabBarBandJoinsTheInset() {
        val bridge = RaftNativeTabBarBridge()
        bridge.publish(
            Any(),
            raftNativeTabBarState(
                appShellBottomTabs(selectedKey = "home", orderedTabKeys = listOf("home", "activity"), activityUnreadCount = 0, labelFor = { it }),
                visible = true,
            ),
        ) {}
        bridge.updateBand("left", 80.0)
        show(leftDp = 59, rightDp = 0, tabBar = bridge, pageShell = true) {
            Box(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().track("content"))
        }
        // The larger of the safe area (59) and the bar's band (80) on the left.
        assertEquals(px(80) to rootWidth, bounds.getValue("content"))
    }

    @Test
    fun physicalEdgesStayPhysicalInRtl() {
        show(rightDp = 59, layoutDirection = LayoutDirection.Rtl) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(40.dp).raftSafeAreaHorizontalPadding().track("all"))
                // End in RTL is the left edge: no inset there.
                Box(Modifier.fillMaxWidth().height(40.dp).raftSafeAreaHorizontalPadding(WindowInsetsSides.End).track("end"))
                Box(Modifier.fillMaxWidth().height(40.dp).raftSafeAreaHorizontalPadding(WindowInsetsSides.Start).track("start"))
            }
        }
        assertEquals(0 to rootWidth - px(59), bounds.getValue("all"))
        assertEquals(0 to rootWidth, bounds.getValue("end"))
        assertEquals(0 to rootWidth - px(59), bounds.getValue("start"))
    }

    @Test
    fun modalPadsItsWindowAndItsScrimBleeds() {
        show(rightDp = 59) {
            // What Modal does in its own window: pad the whole window inset, consume it.
            RaftHorizontalSafeAreaShell(required = LocalRaftWindowHorizontalSafeArea.current, windowRoot = true) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.raftBleedHorizontalSafeArea(LocalRaftHorizontalSafeAreaBleed.current).fillMaxSize().track("scrim"))
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp).raftSafeAreaHorizontalPadding().track("sheet"))
                }
            }
        }
        assertEquals(0 to rootWidth, bounds.getValue("scrim"))
        assertEquals(0 to rootWidth - px(59), bounds.getValue("sheet"))
    }

    private fun show(
        leftDp: Int = 0,
        rightDp: Int = 0,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        tabBar: RaftNativeTabBarBridge? = null,
        pageShell: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        val activity = activities.launch()
        val windowId = "propagation-${seq++}"
        activity.setContent {
            density = LocalDensity.current.density
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject("""{"slockWindowId":"$windowId"}""")) }
                }
            remember {
                RaftHostWindowInsets.publish(
                    windowId,
                    RaftHostWindowInsetValues(0f, 0f, statusBarHidden = true, leftPx = leftDp * density, rightPx = rightDp * density),
                )
            }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalLayoutDirection provides layoutDirection,
                LocalRaftNativeTabBarBridge provides tabBar,
            ) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { rootWidth = it.size.width }) {
                    if (pageShell) RaftPageHorizontalSafeAreaShell(content) else content()
                }
            }
        }
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }

    private fun Modifier.track(name: String): Modifier =
        onGloballyPositioned { coordinates ->
            val left = coordinates.positionInRoot().x.roundToInt()
            bounds[name] = left to left + coordinates.size.width
        }

    private companion object {
        var seq = 0
    }
}
