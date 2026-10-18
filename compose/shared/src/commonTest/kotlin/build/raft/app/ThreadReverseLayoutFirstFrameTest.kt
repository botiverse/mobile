package build.raft.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #193 (option C). The blank thread on 1.14.1 build 1: a warm entry rendered content on the
 * first frame while the reverse-layout gate was still unresolved, the old fallback was `false`,
 * and the list locked forward for the whole open (8f0e0e0f, `thread_reverse_layout
 * enabled=false gateReady=false`). Each case below fails against that fallback.
 */
class ThreadReverseLayoutFirstFrameTest {
    @AfterTest
    fun clear() {
        ThreadReverseLayoutLastDecision.clearForTest()
    }

    @Test
    fun undecidedGateOpensReverse() {
        assertTrue(THREAD_REVERSE_LAYOUT_UNDECIDED_DEFAULT)
        assertTrue(threadReverseLayoutFirstFrame(userChoice = null, gateResolved = null, lastResolvedForServer = null))
    }

    @Test
    fun explicitUserChoiceWinsOverEverything() {
        assertFalse(threadReverseLayoutFirstFrame(userChoice = false, gateResolved = true, lastResolvedForServer = true))
        assertTrue(threadReverseLayoutFirstFrame(userChoice = true, gateResolved = false, lastResolvedForServer = false))
    }

    @Test
    fun gateAlreadyResolvedAtFirstFrameIsUsed() {
        assertFalse(threadReverseLayoutFirstFrame(userChoice = null, gateResolved = false, lastResolvedForServer = true))
    }

    @Test
    fun serverResolvedForwardAppliesFromTheNextOpenNotTheCurrentOne() {
        // First open of the server: gate unresolved at the first frame -> reverse, locked.
        val firstOpen = threadReverseLayoutFirstFrame(
            userChoice = null,
            gateResolved = null,
            lastResolvedForServer = ThreadReverseLayoutLastDecision.get("srv-1")
        )
        assertTrue(firstOpen)
        // The gate later resolves forward for this server; only the per-server value changes.
        ThreadReverseLayoutLastDecision.record("srv-1", false)
        // Next open of the same server, gate again late: opens forward from its first frame.
        val nextOpen = threadReverseLayoutFirstFrame(
            userChoice = null,
            gateResolved = null,
            lastResolvedForServer = ThreadReverseLayoutLastDecision.get("srv-1")
        )
        assertFalse(nextOpen)
        // Another server is unaffected.
        assertNull(ThreadReverseLayoutLastDecision.get("srv-2"))
    }

    @Test
    fun blankServerIdIsIgnored() {
        ThreadReverseLayoutLastDecision.record("", false)
        assertNull(ThreadReverseLayoutLastDecision.get(""))
        assertNull(ThreadReverseLayoutLastDecision.get(null))
    }
}
