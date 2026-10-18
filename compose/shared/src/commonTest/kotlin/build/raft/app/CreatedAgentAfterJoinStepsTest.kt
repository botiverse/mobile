package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #5 / Hands 906abab9. #3132 made creating an external agent open its profile, but on
 * build 2 (VC11401002) it did nothing. The cause was ordering, not logic: `onJoined` clears
 * `createAgentName`, which drops the dialog from composition and cancels the dialog's own
 * coroutine scope, so the `onCreated` call queued behind it never ran.
 *
 * The existing #3132 tests could not catch that — they asserted static enum properties and
 * never touched the callback contract. These pin the sequence itself.
 */
class CreatedAgentAfterJoinStepsTest {
    @Test
    fun externalAgentOpensTheProfileBeforeTheDialogIsDismissed() {
        val steps =
            createdAgentAfterJoinSteps(
                mode = MembersCreateAgentMode.External,
                isExternalAgent = true
            )

        assertEquals(
            listOf(CreatedAgentAfterJoinStep.OpenProfile, CreatedAgentAfterJoinStep.DismissDialog),
            steps,
            "navigation must precede dismissal; the dismissal cancels the scope the navigation uses"
        )
        assertTrue(
            steps.indexOf(CreatedAgentAfterJoinStep.OpenProfile) <
                steps.indexOf(CreatedAgentAfterJoinStep.DismissDialog),
            "opening the profile after dismissal is exactly the #3132 regression"
        )
    }

    @Test
    fun managedAgentOnlyDismisses() {
        assertEquals(
            listOf(CreatedAgentAfterJoinStep.DismissDialog),
            createdAgentAfterJoinSteps(
                mode = MembersCreateAgentMode.Managed,
                isExternalAgent = false
            ),
            "managed create keeps its long-standing close-and-refresh behaviour"
        )
    }

    @Test
    fun managedModeNeverNavigatesEvenIfTheFlagSaysExternal() {
        // The mode gate is the authority: a Managed creation must not navigate regardless of
        // what the caller passes for agent kind.
        val steps =
            createdAgentAfterJoinSteps(
                mode = MembersCreateAgentMode.Managed,
                isExternalAgent = true
            )

        assertFalse(
            steps.contains(CreatedAgentAfterJoinStep.OpenProfile),
            "mode decides navigation, not the kind flag"
        )
        assertEquals(listOf(CreatedAgentAfterJoinStep.DismissDialog), steps)
    }

    @Test
    fun everyExternalCreationNavigatesBeforeDismissing() {
        // Guards against the ordering being reintroduced anywhere in the external path.
        MembersCreateAgentMode.entries.forEach { mode ->
            val steps = createdAgentAfterJoinSteps(mode, isExternalAgent = true)
            if (mode == MembersCreateAgentMode.External) {
                assertEquals(CreatedAgentAfterJoinStep.OpenProfile, steps.first())
            } else {
                assertEquals(CreatedAgentAfterJoinStep.DismissDialog, steps.first())
            }
        }
    }
}
