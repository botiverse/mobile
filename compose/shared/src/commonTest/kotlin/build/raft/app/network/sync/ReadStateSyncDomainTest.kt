package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadStateSyncDomainTest {
    @Test
    fun reducerUsesVersionAsTheOnlyAcceptanceAuthority() {
        val current = ReadStateSyncState(readStateVersion = 4, maxReadSeq = 40)

        val stale =
            reduceReadStateSyncState(
                current,
                ReadStateSyncEvent(readStateVersion = 3, maxReadSeq = 90)
            )
        assertEquals(ReadStateSyncApplyResult.Stale, stale.result)
        assertEquals(current, stale.state)

        val rewind =
            reduceReadStateSyncState(
                current,
                ReadStateSyncEvent(readStateVersion = 5, maxReadSeq = 20)
            )
        assertEquals(ReadStateSyncApplyResult.Accepted, rewind.result)
        assertEquals(ReadStateSyncState(readStateVersion = 5, maxReadSeq = 20), rewind.state)

        val olderVersionAfterRegression =
            reduceReadStateSyncState(
                rewind.state,
                ReadStateSyncEvent(readStateVersion = 4, maxReadSeq = 90)
            )
        assertEquals(ReadStateSyncApplyResult.Stale, olderVersionAfterRegression.result)
        assertEquals(rewind.state, olderVersionAfterRegression.state)

        val equalWatermark =
            reduceReadStateSyncState(
                rewind.state,
                ReadStateSyncEvent(readStateVersion = 6, maxReadSeq = 20)
            )
        assertEquals(ReadStateSyncApplyResult.Accepted, equalWatermark.result)
        assertEquals(ReadStateSyncState(readStateVersion = 6, maxReadSeq = 20), equalWatermark.state)

        val advanced =
            reduceReadStateSyncState(
                equalWatermark.state,
                ReadStateSyncEvent(readStateVersion = 7, maxReadSeq = 60)
            )
        assertEquals(ReadStateSyncApplyResult.Accepted, advanced.result)
        assertEquals(ReadStateSyncState(readStateVersion = 7, maxReadSeq = 60), advanced.state)
    }
}
