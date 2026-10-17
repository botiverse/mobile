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
        state.updateOrigin(Offset(390f, 0f))

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

        state.updateOrigin(Offset(390f, 0f))
        assertEquals(Offset(490f, 20f), pageRootPoint + state.origin, "must use the new origin")

        state.updateOrigin(Offset.Zero)
        assertEquals(Offset(100f, 20f), pageRootPoint + state.origin, "returning to zero must also apply")
    }

    @Test
    fun settingTheSameOriginTwiceIsStable() {
        val state = SlockChildPageOriginState()
        state.updateOrigin(Offset(240f, 12f))
        state.updateOrigin(Offset(240f, 12f))

        assertEquals(Offset(240f, 12f), state.origin)
    }

    @Test
    fun aNestedPageAddsItsParentOriginAndItsOwnPosition() {
        // AppShell tab page (parent) -> detail / thread column page (child). The child's
        // positionInRoot() is relative to the parent page, so the window offset is parent + own.
        val parent = SlockChildPageOriginState(origin = Offset(390f, 0f))
        val child = SlockChildPageOriginState(origin = Offset(60f, 0f))
        child.linkParentOrigin(parent)

        assertEquals(Offset(450f, 0f), child.windowOrigin)
    }

    @Test
    fun anOuterChangePropagatesToTheNestedPageWithoutRecomputingALocalSnapshot() {
        // The parent link is live, so resizing the OUTER pane must move an inner overlay too.
        val parent = SlockChildPageOriginState(origin = Offset(0f, 0f))
        val child = SlockChildPageOriginState(origin = Offset(60f, 0f))
        child.linkParentOrigin(parent)
        assertEquals(Offset(60f, 0f), child.windowOrigin)

        parent.updateOrigin(Offset(390f, 0f))
        assertEquals(Offset(450f, 0f), child.windowOrigin, "outer origin must propagate inward")
    }

    @Test
    fun aPageCannotBeItsOwnParent() {
        val state = SlockChildPageOriginState(origin = Offset(10f, 0f))
        state.linkParentOrigin(state)

        assertEquals(Offset(10f, 0f), state.windowOrigin, "self-parenting must not double-count or recurse")
    }

    @Test
    fun aTopLevelPageHasNoParentAndReportsItsOwnOrigin() {
        val state = SlockChildPageOriginState(origin = Offset(120f, 8f))

        assertEquals(Offset(120f, 8f), state.windowOrigin)
    }
}
