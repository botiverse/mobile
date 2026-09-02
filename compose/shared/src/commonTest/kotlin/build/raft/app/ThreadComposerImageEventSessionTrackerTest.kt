package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadComposerImageEventSessionTrackerTest {
    @Test
    fun closeReturnsOnlyPartialSessionsAndRejectsLateTracking() {
        val tracker = ThreadComposerImageEventSessionTracker()
        assertTrue(tracker.track("partial"))
        assertTrue(tracker.track("handoff"))
        assertTrue(tracker.markHandoffStarted("handoff"))
        val closed = tracker.close()
        assertEquals(setOf("partial"), closed.abortSessionIds)
        assertEquals(setOf("handoff"), closed.inFlightSessionIds)
        assertFalse(tracker.track("late"))
        assertFalse(tracker.owns("partial"))
    }

    @Test
    fun duplicateTrackIsIdempotentAndReleaseRemovesOwnership() {
        val tracker = ThreadComposerImageEventSessionTracker()
        assertTrue(tracker.track("session"))
        assertTrue(tracker.track(" session "))
        tracker.release(" session ")
        assertFalse(tracker.owns("session"))
        assertEquals(emptySet(), tracker.close().abortSessionIds)
    }
}
