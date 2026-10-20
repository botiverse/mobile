package build.raft.app

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
        assertEquals(Triple(48f, 96f, false), RaftHostWindowInsets.current(a))
        assertEquals(Triple(0f, 0f, true), RaftHostWindowInsets.current(b))
    }

    @Test
    fun liveEventReplacesIncludingZero() {
        val w = freshWindow()
        RaftHostWindowInsets.publish(w, topPx = 48f, bottomPx = 96f, statusBarHidden = false)
        // Taskbar hides: bottom must drop to a real zero, not keep 96.
        RaftHostWindowInsets.publish(w, topPx = 48f, bottomPx = 0f, statusBarHidden = false)
        assertEquals(Triple(48f, 0f, false), RaftHostWindowInsets.current(w))
        // And it can shrink without going to zero.
        RaftHostWindowInsets.publish(w, topPx = 48f, bottomPx = 24f, statusBarHidden = false)
        assertEquals(Triple(48f, 24f, false), RaftHostWindowInsets.current(w))
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
        assertEquals(Triple(48f, 96f, false), RaftHostWindowInsets.current(w))
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
        assertEquals(Triple(48f, 96f, false), RaftHostWindowInsets.current(w))
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
}
