package build.raft.designsystem

import build.raft.designsystem.components.raftMobileNavServerPopoverDestinationColumns
import kotlin.test.Test
import kotlin.test.assertEquals

/** raft-design #1037: up to 4 pages keep one row; more than 4 take 3 columns. */
class RaftMobileNavServerDestinationsTest {
    @Test
    fun upToFourPagesKeepOneRow() {
        (1..4).forEach { assertEquals(it, raftMobileNavServerPopoverDestinationColumns(it)) }
    }

    @Test
    fun moreThanFourPagesTakeThreeColumns() {
        (5..9).forEach { assertEquals(3, raftMobileNavServerPopoverDestinationColumns(it)) }
    }
}
