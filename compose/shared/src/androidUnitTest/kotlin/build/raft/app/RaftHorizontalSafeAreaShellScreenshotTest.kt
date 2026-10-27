package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The page shell's horizontal safe area with asymmetric host insets (start 0, end 59dp: an iPhone
 * in landscape with the Dynamic Island on the right, or the iPhone Duo inner display's right-edge
 * status bar). A page-like layout (top bar, content with an embedded child page, composer) sits
 * inside [RaftPageHorizontalSafeAreaShell]: nothing but the canvas (grey) is drawn in the 59dp
 * strip, the embedded child shell does not pad again, and a modal scrim still runs edge to edge.
 * The layout bounds are asserted too, so the guarantee does not rest on the golden alone.
 * Reports land in `build/reports/shell-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w420dp-h240dp-xxhdpi")
class RaftHorizontalSafeAreaShellScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val bounds = mutableMapOf<String, Pair<Float, Float>>()
    private var rootWidthPx = 0
    private var densityValue = 0f

    @Test
    fun asymmetricInsetsKeepContentOutOfTheEndStrip() {
        capture("shell.safearea.end59", scrim = false)
        val endInsetPx = (59 * densityValue).roundToInt()
        for (part in listOf("topBar", "content", "child", "composer")) {
            val (left, right) = bounds.getValue(part)
            assertEquals(0f, left, "$part starts at the left edge (start inset 0)")
            assertEquals((rootWidthPx - endInsetPx).toFloat(), right, "$part ends before the 59dp end inset")
        }
    }

    @Test
    fun modalScrimRunsUnderTheInset() {
        capture("shell.safearea.end59.scrim", scrim = true)
        val (left, right) = bounds.getValue("scrim")
        assertEquals(0f, left)
        assertEquals(rootWidthPx.toFloat(), right, "the scrim covers the padded strip")
    }

    @Test
    fun hostWithoutHorizontalInsetsPadsNothing() {
        capture("shell.safearea.none", scrim = false, endInsetDp = 0)
        for (part in listOf("topBar", "content", "composer")) {
            assertEquals(0f to rootWidthPx.toFloat(), bounds.getValue(part), part)
        }
    }

    private fun capture(name: String, scrim: Boolean, endInsetDp: Int = 59) =
        RaftRobolectricScreenshot.capture(
            name,
            RaftAppAppearance.Light,
            reportDir = "build/reports/shell-screenshots",
            activities = activities,
        ) {
            val windowId = remember { "screenshot-shell-$name" }
            val density = LocalDensity.current.density
            densityValue = density
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject("""{"slockWindowId":"$windowId"}""")) }
                }
            remember {
                // What the iOS host publishes for this page (pixels): top 0 (status bar hidden in
                // landscape), bottom 21dp home indicator, left 0, right [endInsetDp].
                RaftHostWindowInsets.publish(
                    windowId,
                    RaftHostWindowInsetValues(
                        topPx = 0f,
                        bottomPx = 21 * density,
                        statusBarHidden = true,
                        leftPx = 0f,
                        rightPx = endInsetDp * density,
                    ),
                )
            }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF9E9E9E)).onGloballyPositioned {
                        rootWidthPx = it.size.width
                    }
                ) {
                    RaftPageHorizontalSafeAreaShell {
                        PageLike(scrim)
                    }
                }
            }
        }

    @Composable
    private fun PageLike(scrim: Boolean) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF1565C0)).track("topBar"))
                Box(Modifier.fillMaxWidth().weight(1f).background(Color.White).track("content")) {
                    // An embedded child page: its own shell must not pad the inset again.
                    RaftHorizontalSafeAreaShell(required = raftHorizontalSafeArea()) {
                        Box(Modifier.fillMaxSize().padding(vertical = 12.dp).background(Color(0xFF2E7D32)).track("child"))
                    }
                }
                Box(Modifier.fillMaxWidth().height(48.dp).background(Color(0xFFEF6C00)).track("composer"))
            }
            if (scrim) {
                // Drawn for the golden; the probe beside it measures the same bleed.
                RaftModalDismissBarrier(overlayAlpha = 0.65f, onDismiss = {})
                Box(
                    Modifier.raftBleedHorizontalSafeArea(LocalRaftHorizontalSafeAreaBleed.current)
                        .fillMaxSize()
                        .track("scrim")
                )
            }
        }
    }

    private fun Modifier.track(name: String): Modifier =
        onGloballyPositioned { coordinates ->
            val left = coordinates.positionInRoot().x
            bounds[name] = left to left + coordinates.size.width
        }
}
