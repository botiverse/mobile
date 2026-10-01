package build.raft.app

import build.raft.app.data.InboxFilter
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * #2224 review blocker 1: the Activity title tap used to scroll a caller-owned
 * LazyListState that no list was attached to — silently dead, invisible to
 * types. The fix removes the caller-owned state entirely; the panel scrolls
 * [activityScrollToTopTarget] of its own per-page states. This test pins the
 * one part of that wiring that can still silently drift: the mapping must
 * return the IDENTICAL object the selected filter's page renders with (both
 * sides index into the same [InboxFilter.entries]-aligned list).
 */
class ActivityScrollToTopTargetTest {
    @Test
    fun `title tap targets the exact state object of the selected filters page`() {
        // Stand-ins for the three page LazyListStates; identity is the assertion.
        val pageStates = listOf(Any(), Any(), Any())
        for (filter in InboxFilter.entries) {
            val pageIndexThePagerRenders = InboxFilter.entries.indexOf(filter)
            assertSame(
                pageStates[pageIndexThePagerRenders],
                activityScrollToTopTarget(filter, pageStates),
                "filter=$filter must scroll the same state its page renders with",
            )
        }
    }
}
