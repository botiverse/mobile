package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadStateSyncDomainTest {
    @Test
    fun web4622EqualVerdictVectorV1MatchesEveryStep() {
        data class Step(
            val serverId: String,
            val scopeId: String,
            val version: Long,
            val maxReadSeq: Long,
            val expected: ReadStateSyncApplyResult,
            val expectedState: ReadStateSyncState
        )

        val states = mutableMapOf<String, ReadStateSyncState>()
        fun run(step: Step) {
            val key = "${step.serverId}:${step.scopeId}"
            val transition =
                reduceReadStateSyncState(
                    states[key] ?: ReadStateSyncState(),
                    ReadStateSyncEvent(step.version, step.maxReadSeq)
                )
            assertEquals(step.expected, transition.result, key)
            assertEquals(step.expectedState, transition.state, key)
            states[key] = transition.state
        }

        run(Step("A", "X", 5, 100, ReadStateSyncApplyResult.Advanced, ReadStateSyncState(5, 100)))
        run(Step("B", "X", 3, 50, ReadStateSyncApplyResult.Advanced, ReadStateSyncState(3, 50)))

        states.clear()
        run(Step("A", "X", 5, 100, ReadStateSyncApplyResult.Advanced, ReadStateSyncState(5, 100)))
        run(Step("A", "X", 7, 80, ReadStateSyncApplyResult.Regressed, ReadStateSyncState(7, 100)))
        run(Step("A", "X", 6, 200, ReadStateSyncApplyResult.Stale, ReadStateSyncState(7, 100)))

        states.clear()
        run(Step("A", "X", 5, 100, ReadStateSyncApplyResult.Advanced, ReadStateSyncState(5, 100)))
        run(Step("A", "X", 7, 100, ReadStateSyncApplyResult.Advanced, ReadStateSyncState(7, 100)))

        states.clear()
        run(Step("A", "X", 5, 100, ReadStateSyncApplyResult.Advanced, ReadStateSyncState(5, 100)))
        run(Step("A", "X", 3, 50, ReadStateSyncApplyResult.Stale, ReadStateSyncState(5, 100)))
        run(Step("A", "Y", 2, 30, ReadStateSyncApplyResult.Advanced, ReadStateSyncState(2, 30)))
    }

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
