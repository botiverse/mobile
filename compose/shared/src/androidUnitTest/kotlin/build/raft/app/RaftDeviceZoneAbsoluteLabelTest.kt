package build.raft.app

import build.raft.app.data.RaftDatetimeFoundationOwner
import build.raft.app.data.RaftEpochMillisSource
import build.raft.app.data.RaftLocalOffsetMinutesSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** artin 11-05: with no time context a parseable timestamp reads as local time, never "unknown time". */
class RaftDeviceZoneAbsoluteLabelTest {
    // 2026-10-22T02:10:00Z, device at UTC+8 (10:10 local).
    private val now = 1_792_635_000_000L
    private val zone =
        RaftDatetimeFoundationOwner(
            kuiklyBaseClock = RaftEpochMillisSource { now },
            kuiklyBaseOffset = RaftLocalOffsetMinutesSource { 8 * 60 },
        )

    @Test
    fun sameDayShowsTheClock() {
        assertEquals("13:10", raftDeviceZoneAbsoluteLabel("2026-10-22T05:10:00.000Z", now, zone))
    }

    @Test
    fun anotherDayShowsTheDate() {
        assertEquals("10-23 12:10", raftDeviceZoneAbsoluteLabel("2026-10-23T04:10:00.000Z", now, zone))
    }

    @Test
    fun notATimestampStaysNull() {
        assertNull(raftDeviceZoneAbsoluteLabel("soon", now, zone))
    }
}
