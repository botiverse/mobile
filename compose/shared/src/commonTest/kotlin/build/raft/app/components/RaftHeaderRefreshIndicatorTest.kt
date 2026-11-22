package build.raft.app.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** artin task #22: during a user pull the pull indicator is the only refresh chrome. */
class RaftHeaderRefreshIndicatorTest {
    @Test
    fun headerSpinnerShowsOnlyForRefreshesTheUserDidNotPull() {
        assertTrue(raftHeaderRefreshIndicatorVisible(refreshing = true, pullRefreshing = false))
        assertFalse(raftHeaderRefreshIndicatorVisible(refreshing = true, pullRefreshing = true))
        assertFalse(raftHeaderRefreshIndicatorVisible(refreshing = false, pullRefreshing = true))
        assertFalse(raftHeaderRefreshIndicatorVisible(refreshing = false, pullRefreshing = false))
    }
}
