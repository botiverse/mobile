package build.raft.app.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftHostInUseTest {
    @AfterTest
    fun reset() {
        RaftHostInUse.update(true)
        clearRaftHostVisibilityForTests()
    }

    @Test
    fun focusedWindowWithRecentInputIsInUse() {
        assertTrue(raftWindowInUse(focused = true, lastInputAtMillis = 1_000L, nowMillis = 1_000L))
        assertTrue(
            raftWindowInUse(
                focused = true,
                lastInputAtMillis = 1_000L,
                nowMillis = 1_000L + RaftHostInUseInputWindowMillis - 1L
            )
        )
    }

    @Test
    fun unfocusedWindowIsNotInUseEvenWithRecentInput() {
        assertFalse(raftWindowInUse(focused = false, lastInputAtMillis = 1_000L, nowMillis = 1_000L))
    }

    @Test
    fun focusedWindowIdleForThreeMinutesIsNotInUse() {
        assertFalse(
            raftWindowInUse(
                focused = true,
                lastInputAtMillis = 1_000L,
                nowMillis = 1_000L + RaftHostInUseInputWindowMillis
            )
        )
        assertFalse(raftWindowInUse(focused = true, lastInputAtMillis = null, nowMillis = 1_000L))
    }

    @Test
    fun hostShowsContentOnlyWhileVisibleAndInUse() {
        assertTrue(raftHostShowsContent("shell-a"))

        RaftHostInUse.update(false)
        assertFalse(raftHostShowsContent("shell-a"))

        RaftHostInUse.update(true)
        updateRaftHostVisibility("shell-a", visible = false)
        assertFalse(raftHostShowsContent("shell-a"))
        assertTrue(raftHostShowsContent("shell-b"))
    }
}
