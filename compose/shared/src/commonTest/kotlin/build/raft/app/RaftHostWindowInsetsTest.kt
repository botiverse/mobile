package build.raft.app

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task #482 round-3 review coverage: per-window host inset lifecycle —
 * isolation between windows, replace-with-zero/decrease, seed-vs-live
 * ordering, and the bottom host-excluded contract.
 */
class RaftHostWindowInsetsTest {

    // The registry is process-global; tests use unique window ids so they
    // never observe each other's state. The counter must be a companion
    // field: JUnit creates a fresh instance per test, so an instance-level
    // seq restarts at 0 and collides with entries published by earlier
    // cases in the same process.
    private fun freshWindow(): String = "test-window-${Companion.seq++}"

    companion object {
        private var seq = 0
    }

    @Test
    fun windowsAreIsolated() {
        val a = freshWindow()
        val b = freshWindow()
        RaftHostWindowInsets.publish(a, topPx = 48f, bottomPx = 96f, statusBarHidden = false)
        RaftHostWindowInsets.publish(b, topPx = 0f, bottomPx = 0f, statusBarHidden = true)
        assertEquals(RaftHostWindowInsetValues(48f, 96f, false), RaftHostWindowInsets.current(a))
        assertEquals(RaftHostWindowInsetValues(0f, 0f, true), RaftHostWindowInsets.current(b))
    }

    @Test
    fun liveEventReplacesIncludingZero() {
        val w = freshWindow()
        RaftHostWindowInsets.publish(w, topPx = 48f, bottomPx = 96f, statusBarHidden = false)
        // Taskbar hides: bottom must drop to a real zero, not keep 96.
        RaftHostWindowInsets.publish(w, topPx = 48f, bottomPx = 0f, statusBarHidden = false)
        assertEquals(RaftHostWindowInsetValues(48f, 0f, false), RaftHostWindowInsets.current(w))
        // And it can shrink without going to zero.
        RaftHostWindowInsets.publish(w, topPx = 48f, bottomPx = 24f, statusBarHidden = false)
        assertEquals(RaftHostWindowInsetValues(48f, 24f, false), RaftHostWindowInsets.current(w))
    }

    @Test
    fun seedPublishDoesNotOverwriteLiveEvent() {
        val w = freshWindow()
        // Live event lands first (post-attach dispatch).
        RaftHostWindowInsets.publish(w, topPx = 48f, bottomPx = 96f, statusBarHidden = false)
        // A stale attach-time params seed must not overwrite it.
        val staleParams = JSONObject(
            """{"slockWindowId":"$w","safeAreaTop":"0","safeAreaBottom":"0","slockStatusBarHidden":false}"""
        )
        RaftHostWindowInsets.seedFromPageDataIfPresent(staleParams)
        assertEquals(RaftHostWindowInsetValues(48f, 96f, false), RaftHostWindowInsets.current(w))
    }

    @Test
    fun seedWithoutInsetFieldsIsIgnored() {
        val w = freshWindow()
        // Child params that only carry the window id must not seed zeros.
        RaftHostWindowInsets.seedFromPageDataIfPresent(
            JSONObject("""{"slockWindowId":"$w"}""")
        )
        assertNull(RaftHostWindowInsets.current(w))
    }

    @Test
    fun seedFillsWhenNoLiveEventSeen() {
        val w = freshWindow()
        RaftHostWindowInsets.seedFromPageDataIfPresent(
            JSONObject(
                """{"slockWindowId":"$w","safeAreaTop":"48","navigationBarHeight":"96","slockStatusBarHidden":false}"""
            )
        )
        assertEquals(RaftHostWindowInsetValues(48f, 96f, false), RaftHostWindowInsets.current(w))
    }

    @Test
    fun bottomSafeAreaKeepsHostExcludedContract() {
        // Regression for round-3 finding 1: host already consumed the nav bar.
        assertEquals(
            0f,
            resolveRaftBottomSafeAreaDp(
                hostExcludesNavigationBar = true, nativeDp = 48f, hostDp = 48f
            )
        )
        assertEquals(
            24f,
            resolveRaftBottomSafeAreaDp(
                hostExcludesNavigationBar = false, nativeDp = 24f, hostDp = 0f
            )
        )
        assertEquals(
            48f,
            resolveRaftBottomSafeAreaDp(
                hostExcludesNavigationBar = false, nativeDp = 24f, hostDp = 48f
            )
        )
    }

    @Test
    fun blankWindowIdFallsToDefault() {
        RaftHostWindowInsets.publish("  ", topPx = 1f, bottomPx = 2f, statusBarHidden = null)
        assertTrue(RaftHostWindowInsets.current("host:default") != null)
    }

    @Test
    fun liveEventCarriesAllFourEdges() {
        val values =
            raftHostWindowInsetsFromEvent(
                JSONObject(
                    """{"statusBarHeightPx":"177.000","navigationBarHeightPx":"102.000",""" +
                        """"safeAreaLeftPx":"0.000","safeAreaRightPx":"0.000","statusBarHidden":false}"""
                )
            )
        assertEquals(RaftHostWindowInsetValues(177f, 102f, false, leftPx = 0f, rightPx = 0f), values)
    }

    @Test
    fun landscapeLeftAndRightKeepTheInsetOnItsPhysicalEdge() {
        // Dynamic Island iPhone in landscape (status bar hidden): 59pt at 3x on one side.
        val islandLeft =
            raftHostWindowInsetsFromEvent(
                JSONObject(
                    """{"statusBarHeightPx":"0","navigationBarHeightPx":"63","safeAreaLeftPx":"177",""" +
                        """"safeAreaRightPx":"0","statusBarHidden":true}"""
                )
            )
        assertEquals(RaftHorizontalSafeArea(left = 59.dp, right = 0.dp), resolveRaftHorizontalSafeArea(islandLeft, 3f))
        val islandRight =
            raftHostWindowInsetsFromEvent(
                JSONObject(
                    """{"statusBarHeightPx":"0","navigationBarHeightPx":"63","safeAreaLeftPx":"0",""" +
                        """"safeAreaRightPx":"177","statusBarHidden":true}"""
                )
            )
        val right = resolveRaftHorizontalSafeArea(islandRight, 3f)
        assertEquals(RaftHorizontalSafeArea(left = 0.dp, right = 59.dp), right)
        // Start/end follow the layout direction; the physical edge does not move.
        assertEquals(0.dp, right.start(LayoutDirection.Ltr))
        assertEquals(59.dp, right.end(LayoutDirection.Ltr))
        assertEquals(59.dp, right.start(LayoutDirection.Rtl))
        assertEquals(0.dp, right.end(LayoutDirection.Rtl))
    }

    @Test
    fun rightEdgeStatusBarKeepsTopSmallAndRightLarge() {
        // iPhone Duo inner display: the status bar is a strip along the right edge. The host sends
        // the safe area only, so the top is the small top inset, not the strip's frame height.
        val w = freshWindow()
        RaftHostWindowInsets.publish(
            w,
            raftHostWindowInsetsFromEvent(
                JSONObject(
                    """{"statusBarHeightPx":"36","navigationBarHeightPx":"60","safeAreaLeftPx":"0",""" +
                        """"safeAreaRightPx":"162","statusBarHidden":false}"""
                )
            ),
        )
        val values = RaftHostWindowInsets.current(w)
        assertEquals(36f, values?.topPx)
        assertEquals(RaftHorizontalSafeArea(left = 0.dp, right = 54.dp), resolveRaftHorizontalSafeArea(values, 3f))
    }

    @Test
    fun hostWithoutHorizontalFieldsResolvesToZero() {
        // The Android host's payload: top and bottom only. Its pages must stay unchanged.
        val android =
            raftHostWindowInsetsFromEvent(
                JSONObject("""{"statusBarHeightPx":"96","navigationBarHeightPx":"48","statusBarHidden":false}""")
            )
        assertEquals(RaftHostWindowInsetValues(96f, 48f, false), android)
        assertEquals(RaftHorizontalSafeArea.Zero, resolveRaftHorizontalSafeArea(android, 2.625f))
        assertEquals(RaftHorizontalSafeArea.Zero, resolveRaftHorizontalSafeArea(null, 3f))
    }

    @Test
    fun invalidHorizontalValuesReadAsZero() {
        val values =
            raftHostWindowInsetsFromEvent(
                JSONObject("""{"safeAreaLeftPx":"-12","safeAreaRightPx":"NaN"}""")
            )
        assertEquals(0f, values.leftPx)
        assertEquals(0f, values.rightPx)
    }

    @Test
    fun visualZeroSafeAreaDropsHorizontalInsets() {
        val values = RaftHostWindowInsetValues(0f, 0f, null, leftPx = 177f, rightPx = 177f)
        assertEquals(RaftHorizontalSafeArea.Zero, resolveRaftHorizontalSafeArea(values, 3f, visualZeroSafeArea = true))
    }

    @Test
    fun seedCarriesHorizontalEdges() {
        val w = freshWindow()
        RaftHostWindowInsets.seedFromPageDataIfPresent(
            JSONObject(
                """{"slockWindowId":"$w","safeAreaTop":"36","safeAreaBottom":"60","safeAreaLeft":"0","safeAreaRight":"162"}"""
            )
        )
        assertEquals(RaftHostWindowInsetValues(36f, 60f, null, leftPx = 0f, rightPx = 162f), RaftHostWindowInsets.current(w))
    }

    @Test
    fun removeForgetsTheWindowAndItsLiveMark() {
        val w = freshWindow()
        val other = freshWindow()
        RaftHostWindowInsets.publish(w, topPx = 48f, bottomPx = 96f, statusBarHidden = false)
        RaftHostWindowInsets.publish(other, topPx = 12f, bottomPx = 0f, statusBarHidden = false)
        RaftHostWindowInsets.remove(w)
        assertNull(RaftHostWindowInsets.current(w))
        // Other windows are untouched.
        assertEquals(RaftHostWindowInsetValues(12f, 0f, false), RaftHostWindowInsets.current(other))
        // The live mark went too: a later seed for a reused id fills again.
        RaftHostWindowInsets.seedFromPageDataIfPresent(
            JSONObject("""{"slockWindowId":"$w","safeAreaTop":"10","safeAreaBottom":"20"}""")
        )
        assertEquals(RaftHostWindowInsetValues(10f, 20f, null), RaftHostWindowInsets.current(w))
        // Removing an unknown id is a no-op.
        RaftHostWindowInsets.remove("never-published-window")
        assertNull(RaftHostWindowInsets.current("never-published-window"))
    }

    @Test
    fun nestedShellsPadOnlyWhatIsLeft() {
        val window = RaftHorizontalSafeArea(left = 0.dp, right = 59.dp)
        // The page shell pads the whole inset; an embedded child page inside it pads nothing more.
        assertEquals(window, window.remainingAfter(RaftHorizontalSafeArea.Zero))
        assertEquals(RaftHorizontalSafeArea.Zero, window.remainingAfter(window))
        // A side tab bar band wider than the inset only adds the difference.
        val withBar = window.union(RaftHorizontalSafeArea(right = 100.dp))
        assertEquals(RaftHorizontalSafeArea(right = 41.dp), withBar.remainingAfter(window))
    }
}
