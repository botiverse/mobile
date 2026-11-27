package build.raft.app

import build.raft.app.settings.desktopModeOffCleanupDecision
import kotlin.test.Test
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
}
