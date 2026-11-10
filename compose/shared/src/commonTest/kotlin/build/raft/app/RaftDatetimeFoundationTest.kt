package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftDatetimeFoundationTest {
    @Test
    fun newZoneOwnerUsesFreshWholeMinutesAndFallsBackExplicitly() {
        var offset: Int? = -300
        val owner =
            raftTestDatetimeFoundationOwner(
                localOffsetMinutesAt = { offset },
            )

        assertEquals(-300, owner.localOffsetMinutesAt(epochMillis = 0L, legacyOffsetMinutes = 480))
        offset = 60
        assertEquals(60, owner.localOffsetMinutesAt(epochMillis = 1L, legacyOffsetMinutes = 480))
        offset = null
        assertEquals(480, owner.localOffsetMinutesAt(epochMillis = 2L, legacyOffsetMinutes = 480))
    }

    @Test
    fun knownDstBoundaryIsEvaluatedAtEachInstantInsteadOfCachingAnOffset() {
        // America/New_York switches from UTC-05:00 to UTC-04:00 at
        // 2026-03-08T07:00:00Z. The fake owns only the rules; the Mobile owner
        // contract under test is that each requested instant is re-evaluated.
        val beforeDst = 1_772_953_199_000L
        val afterDst = 1_772_953_201_000L
        val owner =
            raftTestDatetimeFoundationOwner(
                localOffsetMinutesAt = { epochMillis ->
                    if (epochMillis < afterDst) -300 else -240
                },
            )

        assertEquals(-300, owner.localOffsetMinutesAt(beforeDst, legacyOffsetMinutes = 0))
        assertEquals(-240, owner.localOffsetMinutesAt(afterDst, legacyOffsetMinutes = 0))
    }
}
