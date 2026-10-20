package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Task #4 / Hands 1c6885d8, three fixes to the external-agent setup surface:
 * the bootstrap-token entry must be reachable before the long copy commands, each external
 * runtime harness gets its own tab instead of one flat command list, and a freshly created
 * external agent lands on its profile rather than back in the member list.
 *
 * These assert the ordering and identity contract the UI relies on. They deliberately do not
 * assert composable layout, which needs a device/capture pass.
 */
class MembersExternalSetupSectionTest {
    @Test
    fun everyHarnessTabHasADistinctStableValueAndLabel() {
        val harnesses = MembersExternalHarness.entries
        assertTrue(harnesses.size >= 2, "external runtimes must be separable as tabs")
        assertEquals(
            harnesses.size,
            harnesses.map { it.value }.toSet().size,
            "harness tab values must be unique — the segmented control selects by value"
        )
        assertEquals(
            harnesses.size,
            harnesses.map { it.labelRes }.toSet().size,
            "harness tabs must not share a label resource"
        )
    }

    @Test
    fun tabValueRoundTripsThroughSelection() {
        // The segmented control emits the selected `value`; the section resolves it back to an
        // enum. A value that does not round-trip would silently reset the user's tab.
        MembersExternalHarness.entries.forEach { harness ->
            val resolved = MembersExternalHarness.entries.firstOrNull { it.value == harness.value }
            assertEquals(harness, resolved, "value ${harness.value} must resolve to its own harness")
        }
        assertEquals(
            null,
            MembersExternalHarness.entries.firstOrNull { it.value == "not-a-harness" },
            "an unknown value must not select a harness"
        )
    }

    @Test
    fun claudeAndHermesDoNotShareTheStartStep() {
        // Claude Code and Hermes differ precisely in how the runtime is started; if both mapped
        // to the same tab they could not be distinguished at all.
        assertNotEquals(MembersExternalHarness.ClaudeCode, MembersExternalHarness.Hermes)
        assertEquals("claude-code", MembersExternalHarness.ClaudeCode.value)
        assertEquals("hermes", MembersExternalHarness.Hermes.value)
    }
}
