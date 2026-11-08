package build.raft.app.shared

import android.app.ApplicationExitInfo
import build.raft.app.shared.RaftAnrReporter.AnrExit
import build.raft.app.shared.RaftAnrReporter.StampedBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftAnrReporterTest {
    private val now = 1_800_000_000_000L

    @Test
    fun stampRoundTripsTheHungBuild() {
        val stamp = RaftAnrReporter.encodeStamp("1.21.0", 12_100_006L)
        assertEquals(StampedBuild("1.21.0", 12_100_006L), RaftAnrReporter.decodeStamp(stamp))
    }

    @Test
    fun stampFitsTheProcessStateSummaryLimit() {
        val stamp = RaftAnrReporter.encodeStamp("1.21.0-" + "ü".repeat(200), 12_100_006L)
        assertTrue(stamp.size <= RaftAnrReporter.MAX_STAMP_BYTES)
        val decoded = RaftAnrReporter.decodeStamp(stamp)!!
        assertEquals(12_100_006L, decoded.versionCode)
        assertTrue(decoded.versionName.startsWith("1.21.0-ü"))
    }

    @Test
    fun exitsWithoutOurStampCarryNoBuild() {
        assertNull(RaftAnrReporter.decodeStamp(null))
        assertNull(RaftAnrReporter.decodeStamp(ByteArray(0)))
        assertNull(RaftAnrReporter.decodeStamp("someone else's summary".toByteArray()))
        assertNull(RaftAnrReporter.decodeStamp("raft-build:1\nnot-a-code\n1.21.0".toByteArray()))
        assertNull(RaftAnrReporter.decodeStamp("raft-build:1\n0\n1.21.0".toByteArray()))
        assertNull(RaftAnrReporter.decodeStamp("raft-build:1\n12100006\n  ".toByteArray()))
        assertNull(RaftAnrReporter.decodeStamp("raft-build:1\n12100006".toByteArray()))
    }

    @Test
    fun ticketVersionComesFromTheStampAndIsNeverInvented() {
        assertEquals(
            mapOf("version_name" to "1.20.0", "version_code" to 12_000_003L),
            RaftAnrReporter.versionExtras(RaftAnrReporter.decodeStamp(RaftAnrReporter.encodeStamp("1.20.0", 12_000_003L))),
        )
        // An exit from a build that predates the stamp: no version, not the uploading install's.
        assertEquals(emptyMap<String, Any>(), RaftAnrReporter.versionExtras(null))
    }

    @Test
    fun selectsAnrExitsNewerThanTheWatermarkOldestFirstAndCaps() {
        fun anr(pid: Int, ts: Long) = AnrExit(pid, ts, ApplicationExitInfo.REASON_ANR)
        val picked = RaftAnrReporter.selectPending(
            exits = listOf(
                anr(3, now - 1_000),
                AnrExit(4, now - 500, ApplicationExitInfo.REASON_CRASH),
                anr(2, now - 5_000),
                anr(1, now - 10_000),
                anr(3, now - 1_000),
            ),
            watermark = now - 10_000,
            now = now,
        )
        assertEquals(listOf(anr(2, now - 5_000), anr(3, now - 1_000)), picked)

        val capped = RaftAnrReporter.selectPending((1..10).map { anr(it, now - (100 - it) * 1_000L) }, 0L, now)
        assertEquals(listOf(8, 9, 10), capped.map { it.pid })

        val firstRun = RaftAnrReporter.selectPending(
            listOf(anr(1, now - RaftAnrReporter.FIRST_RUN_LOOKBACK_MS - 1), anr(2, now - 1)),
            watermark = null,
            now = now,
        )
        assertEquals(listOf(2), firstRun.map { it.pid })
    }

    @Test
    fun groupsOnTheFirstAppFrameOfTheMainThread() {
        val trace = """
            "Signal Catcher" daemon prio=10 tid=4 Runnable
              at java.lang.Object.wait(Native method)

            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1
              at java.lang.Thread.sleep(Native method)
              at build.raft.app.Foo.bar(Foo.kt:12)
              at android.os.Looper.loop(Looper.java:288)

        """.trimIndent()
        val frames = RaftAnrReporter.mainThreadFrames(trace)
        assertEquals("java.lang.Thread.sleep(Native method)", frames.first())
        val frame = RaftAnrReporter.groupingFrame(frames)
        assertEquals("build.raft.app.Foo.bar(Foo.kt:12)", frame)
        assertEquals("ANR: Input dispatching timed out\nat $frame", RaftAnrReporter.message(" Input dispatching timed out ", frame))
    }
}
