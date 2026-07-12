package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadStateSyncDomainTest {
    @Test
    fun reducerSeparatesVersionStalenessFromWatermarkRegression() {
        val current = ReadStateSyncState(readStateVersion = 4, maxReadSeq = 40)

        val stale =
            reduceReadStateSyncState(
                current,
                ReadStateSyncEvent(readStateVersion = 3, maxReadSeq = 90)
            )
        assertEquals(ReadStateSyncApplyResult.Stale, stale.result)
        assertEquals(current, stale.state)

        val regressed =
            reduceReadStateSyncState(
                current,
                ReadStateSyncEvent(readStateVersion = 5, maxReadSeq = 20)
            )
        assertEquals(ReadStateSyncApplyResult.Regressed, regressed.result)
        assertEquals(ReadStateSyncState(readStateVersion = 5, maxReadSeq = 40), regressed.state)

        val olderVersionAfterRegression =
            reduceReadStateSyncState(
                regressed.state,
                ReadStateSyncEvent(readStateVersion = 4, maxReadSeq = 90)
            )
        assertEquals(ReadStateSyncApplyResult.Stale, olderVersionAfterRegression.result)
        assertEquals(regressed.state, olderVersionAfterRegression.state)

        val equalWatermark =
            reduceReadStateSyncState(
                regressed.state,
                ReadStateSyncEvent(readStateVersion = 6, maxReadSeq = 40)
            )
        assertEquals(ReadStateSyncApplyResult.Advanced, equalWatermark.result)
        assertEquals(ReadStateSyncState(readStateVersion = 6, maxReadSeq = 40), equalWatermark.state)

        val advanced =
            reduceReadStateSyncState(
                equalWatermark.state,
                ReadStateSyncEvent(readStateVersion = 7, maxReadSeq = 60)
            )
        assertEquals(ReadStateSyncApplyResult.Advanced, advanced.result)
        assertEquals(ReadStateSyncState(readStateVersion = 7, maxReadSeq = 60), advanced.state)
    }

    @Test
    fun syncCoreUsesVersionAsSparseFrameWatermarkAndKeepsMaxReadSeqMonotonic() {
        val core = createSyncCore(SyncCoreConfig(domains = listOf(readStateSyncDomain())))

        assertEquals(
            SyncIngestOutcome.MaxAdvanced("server-1:channel-1", 2),
            core.ingestFrame(
                READ_STATE_SYNC_DOMAIN,
                SyncFrame(
                    scopeId = "server-1:channel-1",
                    seq = 2,
                    event = ReadStateSyncEvent(readStateVersion = 2, maxReadSeq = 20)
                )
            )
        )
        core.ingestFrame(
            READ_STATE_SYNC_DOMAIN,
            SyncFrame(
                scopeId = "server-1:channel-1",
                seq = 3,
                event = ReadStateSyncEvent(readStateVersion = 3, maxReadSeq = 10)
            )
        )

        assertEquals(
            ReadStateSyncState(readStateVersion = 3, maxReadSeq = 20),
            core.state(READ_STATE_SYNC_DOMAIN, "server-1:channel-1")
        )
    }
}
