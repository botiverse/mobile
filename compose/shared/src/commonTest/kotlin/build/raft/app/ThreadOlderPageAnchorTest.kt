package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadOlderPageAnchorTest {
    @Test
    fun anchorRestoresWhenUserScrollGenerationDidNotChange() {
        assertTrue(
            shouldRestoreThreadOlderPageAnchor(
                capturedUserScrollGeneration = 3,
                currentUserScrollGeneration = 3
            )
        )
    }

    @Test
    fun anchorRestoresSkippedWhenUserScrolledDuringLoad() {
        assertFalse(
            shouldRestoreThreadOlderPageAnchor(
                capturedUserScrollGeneration = 3,
                currentUserScrollGeneration = 4
            )
        )
    }

    @Test
    fun anchorRestoresSkippedWhenUserScrolledBeforeLoadStarted() {
        assertFalse(
            shouldRestoreThreadOlderPageAnchor(
                capturedUserScrollGeneration = 0,
                currentUserScrollGeneration = 1
            )
        )
    }
}
