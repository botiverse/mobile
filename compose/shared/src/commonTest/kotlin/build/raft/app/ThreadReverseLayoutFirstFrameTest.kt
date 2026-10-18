package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #193: with a reply-cache hit (#3083) the thread renders content on the FIRST frame, before the
 * reverse-layout gate has resolved. The list used to capture the direction once, keyed only on the
 * thread instance, so it froze the pre-gate placeholder "forward" and never picked up the real
 * answer -- latest item off the bottom, a large empty band, back-to-bottom pill floating mid-screen.
 *
 * These cover the decision itself.
 *
 * Why each would fail on the pre-fix code (`8f0e0e0f`):
 *  - there was no `decided` input at all: the list did `remember(threadInstanceKey) { requested }`
 *    and `rememberLazyListState { if (reverse) 0 else latest }`, so a cache-hit entry that composed
 *    before the gate resolved permanently produced `reverse = false, initialIndex = latestItemIndex`
 *    even after the gate later reported `true`. `aListBuiltBeforeTheDecisionMustBeRebuiltOnceTheGateArrives`
 *    asserts a false->true transition forces a rebuild; the old code had no rebuild path, so it
 *    returned false and this test fails.
 *  - `afterTheGateDecidesTheGateWins` asserts the decided gate value reaches the list. On the old
 *    code the value reaching the list was whatever was present at first composition, so with a
 *    cache-hit ordering that is `false` while the gate says `true` -- fails.
 */
class ThreadReverseLayoutFirstFrameTest {

    @Test
    fun beforeTheGateDecidesTheDirectionIsStillReported() {
        // Undecided must not be silently treated as "forward forever"; the value is provisional and
        // callers are required to re-evaluate. What we assert here is that it is passed through
        // as-is rather than being rewritten.
        assertFalse(threadReverseLayoutForList(requested = false, decided = false))
        assertTrue(threadReverseLayoutForList(requested = true, decided = false))
    }

    @Test
    fun afterTheGateDecidesTheGateWins() {
        // The regression: a cache-hit entry saw `requested = false` before the gate ran, and the list
        // locked it. Once the gate decides `true`, the list must use `true`.
        assertTrue(
            threadReverseLayoutForList(requested = true, decided = true),
            "a decided reverse gate must reach the list"
        )
        assertFalse(
            threadReverseLayoutForList(requested = false, decided = true),
            "a decided forward gate must stay forward"
        )
    }

    @Test
    fun aListBuiltBeforeTheDecisionMustBeRebuiltOnceTheGateArrives() {
        // This is the exact first-frame ordering from the log: content on frame one with
        // gateReady=false, gateReady=true ~48ms later.
        assertTrue(
            threadReverseLayoutNeedsRebuild(previousDecided = false, nextDecided = true),
            "gate arriving after the list was built must force a rebuild of the scroll state"
        )
    }

    @Test
    fun aListBuiltAfterTheDecisionMustNotBeRebuilt() {
        // Rebuilding a list the user may already have scrolled would re-map every index -- the
        // original comment on this code was right about that, so re-deciding is not a reason to
        // rebuild.
        assertFalse(threadReverseLayoutNeedsRebuild(previousDecided = true, nextDecided = true))
    }

    @Test
    fun theTwoHalvesOfTheFixAreIndependent() {
        // Regression guard for the near miss: flipping only the direction flag while leaving
        // `rememberLazyListState`'s initial index computed from the old orientation looks like a fix
        // and is not. Both the direction and the rebuild must move for the decided transition.
        val decidedArrived = threadReverseLayoutNeedsRebuild(previousDecided = false, nextDecided = true)
        val directionAfter = threadReverseLayoutForList(requested = true, decided = true)
        assertEquals(
            true,
            decidedArrived && directionAfter,
            "the decided transition must both rebuild the state and deliver the decided direction"
        )
    }
    @Test
    fun anExplicitUserChoiceIsDecidedWithoutWaitingForTheGate() {
        // threadReverseLayoutEffective returns `userChoice ?: followedDefault()`, so a user who
        // explicitly picked a layout never consults the gate. Treating them as "undecided" until the
        // gate lands would rebuild a list that was already correct.
        assertTrue(threadReverseLayoutIsDecided(userChoice = true, gateReady = false))
        assertTrue(threadReverseLayoutIsDecided(userChoice = false, gateReady = false))
    }

    @Test
    fun withNoUserChoiceTheGateDecides() {
        assertFalse(
            threadReverseLayoutIsDecided(userChoice = null, gateReady = false),
            "no user choice and no gate means the direction is still a placeholder"
        )
        assertTrue(threadReverseLayoutIsDecided(userChoice = null, gateReady = true))
    }

    @Test
    fun aUserChoiceNeverTriggersTheRebuild() {
        // The rebuild exists only to repair the pre-gate placeholder. A user choice is settled from
        // the first composition, so the false -> true transition never happens for those users.
        val alreadyDecided = threadReverseLayoutIsDecided(userChoice = true, gateReady = false)
        assertFalse(
            threadReverseLayoutNeedsRebuild(previousDecided = alreadyDecided, nextDecided = true),
            "an explicitly chosen layout must not rebuild when the gate arrives"
        )
    }

}
