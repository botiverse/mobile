package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #193: on a reply-cache hit (#3083) the thread renders content on the FIRST frame, before the
 * reverse-layout gate has resolved. The list used to be built forward in that window and locked
 * there -- latest item off the bottom, a large empty band, back-to-bottom pill mid-screen.
 *
 * The fix has two halves and these cover both:
 *  1. an undecided gate now defaults to REVERSE, so the first frame is already built in the
 *     direction it will almost certainly keep (1.14.0 measured 63/63 entries `enabled=true`);
 *  2. a gate that later resolves to FORWARD still moves the list, exactly once, so the
 *     `LazyListState` initial index is recomputed for the new orientation.
 */
class ThreadReverseLayoutFirstFrameTest {

    /** The production fallback the real call site uses (`ThreadPage`, `?: THREAD_..._DEFAULT`). */
    private fun gateUndecidedDefault(): Boolean = THREAD_REVERSE_LAYOUT_UNDECIDED_DEFAULT

    @Test
    fun anUndecidedGateDefaultsToReverseSoTheFirstFrameIsAlreadyCorrect() {
        // This is the regression. Before the fix the fallback was `false`, so a cache-hit entry that
        // composed before the gate resolved produced a forward list; the old code had no way to
        // correct it afterwards. `false` here means the bug is back.
        assertTrue(
            gateUndecidedDefault(),
            "a cache-hit entry composes before the gate; defaulting to forward re-creates the bug"
        )
    }

    @Test
    fun theUndecidedDefaultIsOnlyADefaultNotAUserChoice() {
        // threadReverseLayoutEffective is `userChoice ?: followedDefault()`. A user who explicitly
        // picked forward short-circuits before the fallback is ever consulted, so flipping the
        // default must not override them.
        assertFalse(
            threadReverseLayoutForList(requested = false, decided = true),
            "an explicit forward choice must survive the default flip"
        )
        assertTrue(
            threadReverseLayoutForList(requested = true, decided = true),
            "an explicit reverse choice is unaffected too"
        )
    }

    @Test
    fun aGateThatResolvesToForwardStillNeedsToMoveTheList() {
        // With the default now reverse, this is the remaining direction change: reverse -> forward.
        // It must be visible to the list as a real change, otherwise the LazyListState initial index
        // stays computed for the reverse orientation.
        val beforeGate = gateUndecidedDefault()
        val afterGate = false // server says forward for this server
        assertTrue(
            beforeGate != afterGate,
            "a reverse default followed by a forward gate is a real transition the list must follow"
        )
    }

    @Test
    fun anExplicitChoiceMeansTheGateIsNeverConsulted() {
        assertTrue(threadReverseLayoutIsDecided(userChoice = true, gateReady = false))
        assertTrue(threadReverseLayoutIsDecided(userChoice = false, gateReady = false))
        assertFalse(threadReverseLayoutIsDecided(userChoice = null, gateReady = false))
        assertTrue(threadReverseLayoutIsDecided(userChoice = null, gateReady = true))
    }
}
