package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task #181 follow-up (Argus 2026-10-17): a Kuikly child page is its own page, so an anchored
 * overlay inside it resolves against the child page's root, not the window. The host provides its
 * origin and the anchor adds it at open time.
 *
 * These pin the two properties that make the fix safe: an un-offset page behaves exactly as before,
 * and the origin is read live so a resize / rotation / fold is picked up instead of using a stale
 * value.
 */
class SlockChildPageOriginTest {
    @Test
    fun aFullScreenPageKeepsTheOriginAtZeroSoBehaviourIsUnchanged() {
        // Full-screen / single-column hosts provide nothing; the conversion must be a no-op.
        val state = SlockChildPageOriginState()

        assertEquals(Offset.Zero, state.origin)
    }

    @Test
    fun theHostOriginIsWhatGetsAdded() {
        val state = SlockChildPageOriginState()
        state.setOrigin(Offset(390f, 0f))

        assertEquals(Offset(390f, 0f), state.origin)
    }

    @Test
    fun addingTheOriginConvertsPageRootCoordinatesIntoWindowCoordinates() {
        // Mirrors what resolve() does: localToRoot(...) + origin.
        val pageRootPoint = Offset(120f, 48f)
        val hostOrigin = Offset(390f, 0f)
        val state = SlockChildPageOriginState(origin = hostOrigin)

        assertEquals(Offset(510f, 48f), pageRootPoint + state.origin)
    }

    @Test
    fun aChangedOriginIsPickedUpOnTheNextReadInsteadOfAStaleCachedValue() {
        // Rotation or a pane resize moves the host. Because resolve() reads state.origin at open
        // time, the next menu opens in the right place -- a cached Offset would not.
        val state = SlockChildPageOriginState(origin = Offset(0f, 0f))
        val pageRootPoint = Offset(100f, 20f)
        assertEquals(Offset(100f, 20f), pageRootPoint + state.origin)

        state.setOrigin(Offset(390f, 0f))
        assertEquals(Offset(490f, 20f), pageRootPoint + state.origin, "must use the new origin")

        state.setOrigin(Offset.Zero)
        assertEquals(Offset(100f, 20f), pageRootPoint + state.origin, "returning to zero must also apply")
    }

    @Test
    fun settingTheSameOriginTwiceIsStable() {
        val state = SlockChildPageOriginState()
        state.setOrigin(Offset(240f, 12f))
        state.setOrigin(Offset(240f, 12f))

        assertEquals(Offset(240f, 12f), state.origin)
    }
}
