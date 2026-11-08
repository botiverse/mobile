package build.raft.app.shared

import build.raft.app.shared.RaftBuildStamp.StampedBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftBuildStampTest {
    @Test
    fun stampRoundTripsTheHungBuild() {
        val stamp = RaftBuildStamp.encodeStamp("1.21.0", 12_100_006L)
        assertEquals(StampedBuild("1.21.0", 12_100_006L), RaftBuildStamp.decodeStamp(stamp))
    }

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

    @Test
    fun exitsWithoutOurStampCarryNoBuild() {
        assertNull(RaftBuildStamp.decodeStamp(null))
        assertNull(RaftBuildStamp.decodeStamp(ByteArray(0)))
        assertNull(RaftBuildStamp.decodeStamp("someone else's summary".toByteArray()))
        assertNull(RaftBuildStamp.decodeStamp("raft-build:1\nnot-a-code\n1.21.0".toByteArray()))
        assertNull(RaftBuildStamp.decodeStamp("raft-build:1\n0\n1.21.0".toByteArray()))
        assertNull(RaftBuildStamp.decodeStamp("raft-build:1\n12100006\n  ".toByteArray()))
        assertNull(RaftBuildStamp.decodeStamp("raft-build:1\n12100006".toByteArray()))
    }

    @Test
    fun ticketVersionComesFromTheStampAndIsNeverInvented() {
        assertEquals(
            mapOf("version_name" to "1.20.0", "version_code" to 12_000_003L),
            RaftBuildStamp.versionExtras(RaftBuildStamp.decodeStamp(RaftBuildStamp.encodeStamp("1.20.0", 12_000_003L))),
        )
        // An exit from a build that predates the stamp: no version, not the uploading install's.
        assertEquals(emptyMap<String, Any>(), RaftBuildStamp.versionExtras(null))
    }
}
