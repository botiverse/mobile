package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RaftSearchTabHandoffTest {
    private val pop = RaftSearchTabHandoffRole.PopToShell
    private val shell = RaftSearchTabHandoffRole.Shell

    @Test
    fun savedThenDmThenSearchAllPopsEveryPageBackToTheShell() {
        // shell -> Saved -> DM -> in-channel search -> Search all
        val handoff = RaftSearchTabHandoffState(TestTimeSource())
        assertTrue(handoff.request("  figma  ", shellAlive = true))
        // The search page closed itself; the DM page reappears and closes.
        assertEquals(RaftSearchTabHandoffAction.ClosePage, handoff.onPageAppeared(pop, isInitialAppearance = false))
        // Saved (not a thread page) reappears and closes too, so the user does not land there.
        assertEquals(RaftSearchTabHandoffAction.ClosePage, handoff.onPageAppeared(pop, isInitialAppearance = false))
        // The shell reappears and takes the query exactly once.
        assertEquals(RaftSearchTabHandoffAction.Consume, handoff.onPageAppeared(shell, isInitialAppearance = false))
        assertEquals("figma", handoff.consume()?.query)
        assertNull(handoff.consume())
        // Later the shell reappearing, or another page, does nothing.
        assertEquals(RaftSearchTabHandoffAction.None, handoff.onPageAppeared(shell, isInitialAppearance = false))
        assertEquals(RaftSearchTabHandoffAction.None, handoff.onPageAppeared(pop, isInitialAppearance = false))
    }

    @Test
    fun aNewlyOpenedPageNeverClosesItself() {
        val handoff = RaftSearchTabHandoffState(TestTimeSource())
        handoff.request("q", shellAlive = true)
        assertEquals(RaftSearchTabHandoffAction.None, handoff.onPageAppeared(pop, isInitialAppearance = true))
        assertEquals(RaftSearchTabHandoffAction.None, handoff.onPageAppeared(RaftSearchTabHandoffRole.Ignore, isInitialAppearance = false))
    }

    @Test
    fun noStaleRequestAfterFiveSeconds() {
        val time = TestTimeSource()
        val handoff = RaftSearchTabHandoffState(time, maxAgeMillis = 5_000L)
        handoff.request("q", shellAlive = true)
        time += 6.seconds
        assertFalse(handoff.hasPending)
        assertEquals(RaftSearchTabHandoffAction.None, handoff.onPageAppeared(pop, isInitialAppearance = false))
        assertEquals(RaftSearchTabHandoffAction.None, handoff.onPageAppeared(shell, isInitialAppearance = false))
        assertNull(handoff.consume())
    }

    @Test
    fun withoutALiveShellThereIsNoHandoff() {
        val handoff = RaftSearchTabHandoffState(TestTimeSource())
        assertFalse(handoff.request("q", shellAlive = false))
        assertEquals(RaftSearchTabHandoffAction.None, handoff.onPageAppeared(pop, isInitialAppearance = false))
    }

    @Test
    fun eachRequestHasANewNonce() {
        val handoff = RaftSearchTabHandoffState(TestTimeSource())
        handoff.request("a", shellAlive = true)
        val first = handoff.consume()!!
        handoff.request("a", shellAlive = true)
        assertTrue(handoff.consume()!!.nonce > first.nonce)
    }

    @Test
    fun theWideRailShowsTheQueryInItsInlineSearch() {
        assertEquals(AppShellSearchHandoffTarget.RailInlineSearch, appShellSearchHandoffTarget(navRailActive = true))
        assertEquals(AppShellSearchHandoffTarget.SearchTab, appShellSearchHandoffTarget(navRailActive = false))
    }

    @Test
    fun theOpenedRowIsMarkedOnlyWhenThePageShowsAgain() {
        assertNull(homeSearchReturnHighlightKey(openedKey = "row", pageVisible = false))
        assertEquals("row", homeSearchReturnHighlightKey(openedKey = "row", pageVisible = true))
        assertNull(homeSearchReturnHighlightKey(openedKey = null, pageVisible = true))
    }
}
