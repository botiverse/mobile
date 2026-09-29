package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopModeOffCleanupTest {
    @Test
    fun onToOffTransitionClearsTransientSurfacesOnce() {
        // Armed (ON) → OFF: cleanup runs and disarms.
        val (shouldCleanup, nextArmed) =
            desktopModeOffCleanupDecision(
                desktopModeOnLargeScreen = false,
                cleanupArmed = true,
            )
        assertTrue(shouldCleanup)
        assertFalse(nextArmed)
    }

    @Test
    fun coldStartOffDoesNotCleanup() {
        // Cold start begins OFF and unarmed: nothing to clear, stays disarmed.
        val (shouldCleanup, nextArmed) =
            desktopModeOffCleanupDecision(
                desktopModeOnLargeScreen = false,
                cleanupArmed = false,
            )
        assertFalse(shouldCleanup)
        assertFalse(nextArmed)
    }

    @Test
    fun offThenUserReopensDoesNotRecleanup() {
        // After the edge cleanup fired, a subsequent OFF tick must not wipe a
        // surface the user reopened — the predicate is edge-triggered, not level.
        val (_, armedAfterOff) =
            desktopModeOffCleanupDecision(
                desktopModeOnLargeScreen = false,
                cleanupArmed = true,
            )
        val (shouldCleanupAgain, nextArmed) =
            desktopModeOffCleanupDecision(
                desktopModeOnLargeScreen = false,
                cleanupArmed = armedAfterOff,
            )
        assertFalse(shouldCleanupAgain)
        assertFalse(nextArmed)
    }

    @Test
    fun onTickRearmsWithoutCleaning() {
        // OFF→ON re-arms the latch and never cleans on the ON side.
        val (shouldCleanup, nextArmed) =
            desktopModeOffCleanupDecision(
                desktopModeOnLargeScreen = true,
                cleanupArmed = false,
            )
        assertFalse(shouldCleanup)
        assertTrue(nextArmed)
    }

    @Test
    fun onToOffToOnToOffCleansEachEdge() {
        // Each true→false edge cleans once; ON re-arms so a second OFF cleans again.
        var armed = true
        val results =
            listOf(false, true, false).map { flag ->
                val (cleanup, next) =
                    desktopModeOffCleanupDecision(
                        desktopModeOnLargeScreen = flag,
                        cleanupArmed = armed,
                    )
                armed = next
                cleanup
            }
        assertEquals(listOf(true, false, true), results)
    }
}
