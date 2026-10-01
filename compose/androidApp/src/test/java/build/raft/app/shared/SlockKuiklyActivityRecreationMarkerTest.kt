package build.raft.app.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #58: the recreate marker must only be injected when this attach
 * followed a real savedInstanceState AND was not disqualified by an
 * onNewIntent explicit navigation. The disqualifier travels inside the
 * saved-state bundle, so it survives the instance boundary — an instance
 * field alone would be lost when recreate() allocates the replacement.
 */
class SlockKuiklyActivityRecreationMarkerTest {

    /** Mirrors SlockKuiklyActivity's eligibility chain: onCreate latches
     * whether the attach had saved state and whether the bundle carried the
     * explicit-intent disqualifier; onSaveInstanceState writes the flag when
     * the dying instance was onNewIntent-triggered. */
    private class Harness {
        private var pendingExplicitIntentRecreate = false
        private var lastCreateHadSavedState = false

        fun onNewIntent() {
            pendingExplicitIntentRecreate = true
        }

        fun onSaveInstanceState(bundle: MutableMap<String, Boolean>) {
            if (pendingExplicitIntentRecreate) {
                bundle["slockExplicitIntentRecreated"] = true
            }
        }

        fun onCreate(bundle: MutableMap<String, Boolean>?) {
            lastCreateHadSavedState =
                bundle != null && bundle["slockExplicitIntentRecreated"] != true
        }

        fun consumeActivityRecreationEligibility(): Boolean {
            val wasSystemRecreated =
                lastCreateHadSavedState && !pendingExplicitIntentRecreate
            pendingExplicitIntentRecreate = false
            lastCreateHadSavedState = false
            return wasSystemRecreated
        }
    }

    @Test
    fun `system recreate injects marker once`() {
        val old = Harness()
        val bundle = mutableMapOf<String, Boolean>()
        old.onSaveInstanceState(bundle)

        val fresh = Harness()
        fresh.onCreate(bundle)
        assertTrue(fresh.consumeActivityRecreationEligibility())
        assertFalse(fresh.consumeActivityRecreationEligibility())
    }

    @Test
    fun `cold start never injects marker`() {
        val fresh = Harness()
        fresh.onCreate(null)
        assertFalse(fresh.consumeActivityRecreationEligibility())
    }

    @Test
    fun `explicit intent recreate carries disqualifier across the instance boundary`() {
        val old = Harness()
        old.onNewIntent()
        val bundle = mutableMapOf<String, Boolean>()
        old.onSaveInstanceState(bundle)

        // New instance sees saved state but the bundle flag disqualifies it —
        // this is the defect HuaTuo caught: a field alone would be lost.
        val fresh = Harness()
        fresh.onCreate(bundle)
        assertFalse(fresh.consumeActivityRecreationEligibility())
    }

    @Test
    fun `saved state without snapshot still walks launch seed`() {
        val fresh = Harness()
        fresh.onCreate(mutableMapOf())
        assertTrue(fresh.consumeActivityRecreationEligibility())
    }
}
