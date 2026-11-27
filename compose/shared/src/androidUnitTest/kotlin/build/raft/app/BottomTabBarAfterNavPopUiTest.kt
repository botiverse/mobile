package build.raft.app

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.RaftHostImeInsets
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import build.raft.app.thread.THREAD_PAGE
import build.raft.app.thread.key
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Regression checks for Hands ab79443f (#proj-mobile:#361):
 * 1. Bottom tab bar remains clickable after nav push/pop and IME hide/show while covered.
 * 2. AnimatedVisibility wrapping RaftAppBottomTabBarHost carries RaftAppBottomTabBarHostZIndex so
 *    higher-zIndex siblings in RaftAppShellSurface cannot cover or intercept bottom-bar taps.
 * 3. Taps landing in the 60dp bar's top 7dp or bottom 8dp padding select their tab without
 *    double-firing when tapping inside the 45dp item, while taps in the gesture safe area below
 *    60dp stay blocked by raftTouchBlocker.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class BottomTabBarAfterNavPopUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun bottomTabBarClickableAfterImeToggleWhileCoveredAndPop() = runRobolectricComposeUiTest {
        val stack = RaftPageStack()
        val hostIme = RaftHostImeInsets()
        var selectedTab by mutableStateOf("activity")
        val tappedTabs = mutableListOf<String>()
        var dispatcher: OnBackPressedDispatcher? = null
        var rootView: android.view.View? = null

        setContent {
            val context = LocalContext.current
            dispatcher = (context as? ComponentActivity)?.onBackPressedDispatcher
            rootView = LocalView.current
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftHostImeInsets provides hostIme,
            ) {
                RaftDesignSystemTheme {
                    RaftMobilePageStackDisplay(stack) { page ->
                        if (page.args.pageName == RAFT_APP_SHELL_PAGE) {
                            RaftAppShellSurface(
                                shellScope = RaftAppShellScope(
                                    topSafeArea = 44.dp,
                                    bottomSafeArea = 24.dp,
                                    bottomTabBarContentInset = 92.dp,
                                    bottomTabBarOverlaysContent = false,
                                ),
                                bottomTabs = listOf(
                                    RaftBottomTabItemPayload(key = "home", label = "Home", selected = selectedTab == "home"),
                                    RaftBottomTabItemPayload(key = "activity", label = "Activity", selected = selectedTab == "activity"),
                                    RaftBottomTabItemPayload(key = "search", label = "Search", selected = selectedTab == "search"),
                                ),
                                onBottomTabSelected = { key ->
                                    tappedTabs += key
                                    selectedTab = key
                                },
                                showBottomTabs = true,
                            ) {
                                Box(Modifier.fillMaxSize())
                            }
                        } else {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        waitForIdle()

        fun dispatchTap(x: Float, y: Float) {
            val view = checkNotNull(rootView)
            val now = SystemClock.uptimeMillis()
            runOnIdle {
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
                view.dispatchTouchEvent(down)
                view.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }
            waitForIdle()
        }

        fun tapTabAt(index: Int, yFraction: Float = 0.5f) {
            val tabs = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
            val b = tabs[index].boundsInRoot
            dispatchTap((b.left + b.right) / 2f, b.top + (b.bottom - b.top) * yFraction)
        }

        // Scenario 1: push -> IME open -> IME close while covered -> pop -> MotionEvent tap
        runOnIdle { stack.open(THREAD_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        runOnIdle {
            hostIme.imeBottomPx = 1060
            hostIme.imeAnimationRunning = false
        }
        mainClock.advanceTimeBy(300)
        waitForIdle()

        runOnIdle {
            hostIme.imeBottomPx = 0
            hostIme.imeAnimationRunning = false
        }
        mainClock.advanceTimeBy(500)
        waitForIdle()

        runOnIdle { stack.back() }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        tapTabAt(0)
        assertEquals(listOf("home"), tappedTabs, "Scenario 1: tap after IME toggle while covered + pop")

        // Scenario 2: push -> IME open -> pop WHILE IME open -> IME close after pop -> MotionEvent tap
        runOnIdle { stack.open(THREAD_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        runOnIdle { hostIme.imeBottomPx = 1060 }
        mainClock.advanceTimeBy(100)
        waitForIdle()

        runOnIdle { stack.back() }
        mainClock.advanceTimeBy(100)
        runOnIdle { hostIme.imeBottomPx = 0 }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        tapTabAt(1)
        assertEquals(listOf("home", "activity"), tappedTabs, "Scenario 2: tap after pop with IME closing mid-pop")

        // Scenario 3: push -> IME toggle -> predictive back -> MotionEvent tap
        runOnIdle { stack.open(THREAD_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        runOnIdle { hostIme.imeBottomPx = 1060 }
        waitForIdle()
        runOnIdle { hostIme.imeBottomPx = 0 }
        waitForIdle()
        runOnIdle {
            dispatcher!!.dispatchOnBackStarted(BackEventCompat(10f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher!!.dispatchOnBackProgressed(BackEventCompat(250f, 500f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher!!.onBackPressed()
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()

        tapTabAt(2)
        assertEquals(listOf("home", "activity", "search"), tappedTabs, "Scenario 3: tap after IME toggle + predictive back")
    }

    @Test
    fun bottomTabBarSitsAboveHigherZIndexOverlayAndExpandsVerticalPaddingTapArea() = runRobolectricComposeUiTest {
        var selectedTab by mutableStateOf("activity")
        val tappedTabs = mutableListOf<String>()
        var overlaySwallows = 0
        var rootView: android.view.View? = null
        var densityPx = 1f

        setContent {
            rootView = LocalView.current
            densityPx = LocalDensity.current.density
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    RaftAppShellSurface(
                        shellScope = RaftAppShellScope(
                            topSafeArea = 44.dp,
                            bottomSafeArea = 24.dp,
                            bottomTabBarContentInset = 92.dp,
                            bottomTabBarOverlaysContent = false,
                        ),
                        bottomTabs = listOf(
                            RaftBottomTabItemPayload(key = "home", label = "Home", selected = selectedTab == "home"),
                            RaftBottomTabItemPayload(key = "activity", label = "Activity", selected = selectedTab == "activity"),
                            RaftBottomTabItemPayload(key = "search", label = "Search", selected = selectedTab == "search"),
                        ),
                        onBottomTabSelected = { key ->
                            tappedTabs += key
                            selectedTab = key
                        },
                        showBottomTabs = true,
                        // Simulate a sibling overlay with zIndex > 0f; AnimatedVisibility must carry
                        // RaftAppBottomTabBarHostZIndex so the tab bar still wins hit-testing.
                        shellOverlay = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .zIndex(10f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { overlaySwallows += 1 },
                                    )
                            )
                        },
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        waitForIdle()

        fun dispatchTap(x: Float, y: Float) {
            val view = checkNotNull(rootView)
            val now = SystemClock.uptimeMillis()
            runOnIdle {
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
                view.dispatchTouchEvent(down)
                view.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }
            waitForIdle()
        }

        val tabs = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
        assertEquals(3, tabs.size)
        val homeBounds = tabs[0].boundsInRoot
        val searchBounds = tabs[2].boundsInRoot
        val homeCenterX = (homeBounds.left + homeBounds.right) / 2f
        val searchCenterX = (searchBounds.left + searchBounds.right) / 2f

        // 1. Center of the 45dp tab item fires exactly once (no double-fire, not stolen by zIndex=10 overlay).
        dispatchTap(homeCenterX, (homeBounds.top + homeBounds.bottom) / 2f)
        assertEquals(listOf("home"), tappedTabs, "Center tap should select tab exactly once")
        assertEquals(0, overlaySwallows, "Higher-zIndex shellOverlay sibling must not steal bottom-bar tap")

        // 2. Top 7dp bar padding (3dp above the 45dp tab top) selects the tab instead of hitting raftTouchBlocker.
        dispatchTap(searchCenterX, searchBounds.top - 3f * densityPx)
        assertEquals(listOf("home", "search"), tappedTabs, "Top bar padding tap should select tab")

        // 3. Bottom 8dp bar padding (4dp below the 45dp tab label) selects the tab instead of hitting raftTouchBlocker.
        dispatchTap(homeCenterX, homeBounds.bottom + 4f * densityPx)
        assertEquals(listOf("home", "search", "home"), tappedTabs, "Bottom 8dp bar padding tap should select tab")

        // 4. System gesture safe-area strip below the 60dp bar (16dp below the 45dp tab bottom) stays blocked.
        dispatchTap(searchCenterX, searchBounds.bottom + 16f * densityPx)
        assertEquals(listOf("home", "search", "home"), tappedTabs, "Gesture safe-area tap must stay blocked")
        assertEquals(0, overlaySwallows, "Gesture safe-area tap must not fall through to sibling overlay")
    }
}
