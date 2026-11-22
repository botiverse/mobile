package build.raft.app.shared

import build.raft.app.shared.RaftBuildStamp.StampedBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftBuildStampTest {
    @Test
    fun stampKeepsTheRaftBuildWireFormat() {
        // The Hands SDK (0.13.1+) and exits stamped by older builds depend on this exact layout.
        assertEquals("raft-build:1\n12100006\n1.21.0", RaftBuildStamp.encodeStamp(" 1.21.0 ", 12_100_006L).toString(Charsets.UTF_8))
    }

    @Test
    fun stampFitsTheProcessStateSummaryLimit() {
        val stamp = RaftBuildStamp.encodeStamp("1.21.0-" + "ü".repeat(200), 12_100_006L)
        assertTrue(stamp.size <= RaftBuildStamp.MAX_STAMP_BYTES)
        val decoded = RaftBuildStamp.decodeStamp(stamp)!!
        assertEquals(12_100_006L, decoded.versionCode)
        assertTrue(decoded.versionName.startsWith("1.21.0-ü"))
    }
}
