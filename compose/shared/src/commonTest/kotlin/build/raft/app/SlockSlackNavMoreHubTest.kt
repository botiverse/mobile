package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #386/#388: the system back key must be consumed by More sub-pages (returning to the
 * hub) instead of falling through to the root shell handler, which closes the whole page.
 */
class SlockSlackNavMoreHubTest {

    @Test
    fun systemBackIsConsumedBySubPagesOnly() {
        // The hub itself keeps the root shell back behavior (page-level back).
        assertFalse(slockSlackNavMoreShouldConsumeSystemBack(SlockSlackNavMoreDestination.Hub))
        // Every demoted sub-destination intercepts back and returns to the hub.
        assertTrue(slockSlackNavMoreShouldConsumeSystemBack(SlockSlackNavMoreDestination.Tasks))
        assertTrue(slockSlackNavMoreShouldConsumeSystemBack(SlockSlackNavMoreDestination.Members))
        assertTrue(slockSlackNavMoreShouldConsumeSystemBack(SlockSlackNavMoreDestination.Settings))
    }
}
