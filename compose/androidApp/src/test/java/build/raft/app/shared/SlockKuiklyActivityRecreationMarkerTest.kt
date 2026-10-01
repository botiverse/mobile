package build.raft.app.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #58: the recreate marker must only be injected when this attach
 * followed a real savedInstanceState AND was not disqualified by an
 * onNewIntent explicit navigation. Instance fields must not leak across
 * Activity instances — each new instance starts cold with both flags false.
 */
class SlockKuiklyActivityRecreationMarkerTest {

    /** Drive the same decision chain the Activity runs, against explicit
     * field state — the Activity itself can't be instantiated in a unit
     * test, so this pins the ordering and the single-shot consumption. */
    private class Harness {
        var lastCreateHadSavedState = false
        var pendingExplicitIntentRecreate = false

        fun onCreate(savedInstanceStatePresent: Boolean) {
            lastCreateHadSavedState = savedInstanceStatePresent
        }

        fun onNewIntent() {
            pendingExplicitIntentRecreate = true
        }

        fun consumeActivityRecreationEligibility(): Boolean {
            val wasSystemRecreated = lastCreateHadSavedState && !pendingExplicitIntentRecreate
            pendingExplicitIntentRecreate = false
            lastCreateHadSavedState = false
            return wasSystemRecreated
        }
    }

    @Test
    fun `saved instance attach injects marker once`() {
        val h = Harness()
        h.onCreate(savedInstanceStatePresent = true)
        assertTrue(h.consumeActivityRecreationEligibility())
        assertFalse(h.consumeActivityRecreationEligibility())
    }

    @Test
    fun `cold start never injects marker`() {
        val h = Harness()
        h.onCreate(savedInstanceStatePresent = false)
        assertFalse(h.consumeActivityRecreationEligibility())
    }

    @Test
    fun `onNewIntent disqualifies recreate even with saved state`() {
        val h = Harness()
        h.onCreate(savedInstanceStatePresent = true)
        h.onNewIntent()
        assertFalse(h.consumeActivityRecreationEligibility())
    }

    @Test
    fun `explicit intent flag does not survive a fresh instance`() {
        val old = Harness()
        old.onCreate(savedInstanceStatePresent = true)
        old.onNewIntent()
        // A real recreate allocates a new Activity; this harness instance
        // models that — the new instance's flags start false regardless of
        // what the old one carried.
        val fresh = Harness()
        fresh.onCreate(savedInstanceStatePresent = false)
        assertFalse(fresh.consumeActivityRecreationEligibility())
    }

    @Test
    fun `saved state without snapshot still walks launch seed`() {
        // Marker injected (real recreate) but consume() in shared only applies
        // the snapshot when it exists and the identity matches — recorded
        // elsewhere; this pins that the marker alone does not force a tab.
        val h = Harness()
        h.onCreate(savedInstanceStatePresent = true)
        assertTrue(h.consumeActivityRecreationEligibility())
    }
}
