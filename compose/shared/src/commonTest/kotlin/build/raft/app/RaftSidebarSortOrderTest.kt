package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Home sort sheet keeps the person's order when the section's rows are re-emitted. */
class RaftSidebarSortOrderTest {
    @Test
    fun reconcileKeepsUserOrderDropsLeftRowsAndAppendsNewRows() {
        val cases =
            listOf(
                // same rows, new objects (a realtime tick): the sheet order survives as is
                Triple(listOf("c", "a", "b"), listOf("a", "b", "c"), listOf("c", "a", "b")),
                // a row was unpinned elsewhere while the sheet was open
                Triple(listOf("c", "a", "b"), listOf("a", "c"), listOf("c", "a")),
                // a row was pinned elsewhere: it joins at the end
                Triple(listOf("c", "a"), listOf("a", "d", "c"), listOf("c", "a", "d")),
            )
        cases.forEach { (userOrder, itemIds, expected) ->
            assertEquals(expected, reconcileSidebarSortOrder(userOrder, itemIds), "user=$userOrder items=$itemIds")
        }
    }
}
