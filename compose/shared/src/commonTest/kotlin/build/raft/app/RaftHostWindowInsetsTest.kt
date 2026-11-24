package build.raft.app

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.designsystem.components.RaftSafeAreaInsets
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun hostWithoutHorizontalFieldsResolvesToZero() {
        // Hosts or fixtures that only publish vertical bars keep horizontal safe areas at zero.
        val verticalOnly =
            raftHostWindowInsetsFromEvent(
                JSONObject("""{"statusBarHeightPx":"96","navigationBarHeightPx":"48","statusBarHidden":false}""")
            )
        assertEquals(RaftHostWindowInsetValues(96f, 48f, false), verticalOnly)
        assertEquals(RaftHorizontalSafeArea.Zero, resolveRaftHorizontalSafeArea(verticalOnly, 2.625f))
        assertEquals(RaftHorizontalSafeArea.Zero, resolveRaftHorizontalSafeArea(null, 3f))
    }

    @Test
    fun androidLandscapeSeedLiveUpdateAndPortraitRotationClearHorizontalSafeAreas() {
        val w = freshWindow()
        // Pushed Activity first-frame seed in landscape (punch-hole left 108px, 3-button nav right 126px at 3x).
        RaftHostWindowInsets.seedFromPageDataIfPresent(
            JSONObject(
                """{"slockWindowId":"$w","safeAreaTop":"72","safeAreaLeft":"108","safeAreaRight":"126","slockStatusBarHidden":false}"""
            )
        )
        assertEquals(
            RaftHorizontalSafeArea(left = 36.dp, right = 42.dp),
            resolveRaftHorizontalSafeArea(RaftHostWindowInsets.current(w), density = 3f),
        )
        // Rotate 90 -> 270: live event swaps physical edges.
        RaftHostWindowInsets.publish(
            w,
            raftHostWindowInsetsFromEvent(
                JSONObject(
                    """{"statusBarHeightPx":"72","navigationBarHeightPx":"0","safeAreaLeftPx":"126",""" +
                        """"safeAreaRightPx":"108","statusBarHidden":false}"""
                )
            ),
        )
        assertEquals(
            RaftHorizontalSafeArea(left = 42.dp, right = 36.dp),
            resolveRaftHorizontalSafeArea(RaftHostWindowInsets.current(w), density = 3f),
        )
        // Rotate back to portrait: live event sends 0/0 and clears stale landscape insets.
        RaftHostWindowInsets.publish(
            w,
            raftHostWindowInsetsFromEvent(
                JSONObject(
                    """{"statusBarHeightPx":"96","navigationBarHeightPx":"63","safeAreaLeftPx":"0",""" +
                        """"safeAreaRightPx":"0","statusBarHidden":false}"""
                )
            ),
        )
        assertEquals(
            RaftHorizontalSafeArea.Zero,
            resolveRaftHorizontalSafeArea(RaftHostWindowInsets.current(w), density = 3f),
        )
    }

    @Test
    fun coldStartPageDataWithoutInsetKeysDoesNotSeedZeroSnapshot() {
        val w = freshWindow()
        RaftHostWindowInsets.seedFromPageDataIfPresent(
            JSONObject("""{"slockWindowId":"$w","slockStatusBarHidden":false}""")
        )
        assertEquals(null, RaftHostWindowInsets.current(w))
    }

    @Test
    fun sideTabBarBandJoinsTheWindowInsetPerEdge() {
        val window = RaftHorizontalSafeArea(left = 59.dp, right = 0.dp)
        // A left tab bar wider than the inset wins on its edge; the other edge keeps the inset.
        val withBar = window.union(RaftHorizontalSafeArea(left = 80.dp))
        assertEquals(RaftHorizontalSafeArea(left = 80.dp, right = 0.dp), withBar)
        // What the page shell hands to the design system (physical edges, never negative).
        assertEquals(RaftSafeAreaInsets(left = 80.dp, right = 0.dp), withBar.toDesignSystemInsets())
        assertEquals(RaftSafeAreaInsets.Zero, RaftHorizontalSafeArea(left = (-4).dp).toDesignSystemInsets())
    }
}
