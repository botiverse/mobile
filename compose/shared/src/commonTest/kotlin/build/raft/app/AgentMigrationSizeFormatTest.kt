package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Web `formatMigrationSize` (botiverse/slock #14275 / #14277, agentMigrationRetry.behavior.test.tsx). */
class AgentMigrationSizeFormatTest {
    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    @Test
    fun sizesOnTheCardAreBinaryLikeTheSizeLimitCopy() {
        assertEquals("10 GiB", formatAgentMigrationSize(10 * gib))
        assertEquals("10.4 GiB", formatAgentMigrationSize(kotlin.math.round(10.4 * gib).toLong()))
        assertEquals("812 MiB", formatAgentMigrationSize(812 * mib))
        assertEquals("1.5 KiB", formatAgentMigrationSize(1536))
        assertEquals("12 B", formatAgentMigrationSize(12))
    }

    @Test
    fun countsAreGroupedByThousands() {
        assertEquals("48,210", agentMigrationCount(48_210))
        assertEquals("1,203", agentMigrationCount(1203))
        assertEquals("7", agentMigrationCount(7))
    }
}
