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

    @Test
    fun syncCoreKeepsWireVersionAuthorityIndependentFromFrameSequence() {
        val core = createSyncCore(SyncCoreConfig(domains = listOf(readStateSyncDomain())))

        assertEquals(
            SyncIngestOutcome.MaxAdvanced("server-1:channel-1", 2uL),
            core.ingestFrame(
                READ_STATE_SYNC_DOMAIN,
                SyncFrame(
                    scopeId = "server-1:channel-1",
                    seq = 2uL,
                    event = ReadStateSyncEvent(readStateVersion = 2, maxReadSeq = 20)
                )
            )
        )
        core.ingestFrame(
            READ_STATE_SYNC_DOMAIN,
            SyncFrame(
                scopeId = "server-1:channel-1",
                seq = 99uL,
                event = ReadStateSyncEvent(readStateVersion = 3, maxReadSeq = 10)
            )
        )

        assertEquals(
            ReadStateSyncState(readStateVersion = 3, maxReadSeq = 10),
            core.state(READ_STATE_SYNC_DOMAIN, "server-1:channel-1")
        )
    }
}

private const val READ_STATE_SYNC_DOMAIN = "read_state"

private fun readStateSyncDomain(): SyncDomainConfig =
    SyncDomainConfig(
        name = READ_STATE_SYNC_DOMAIN,
        density = SyncDensity.Sparse,
        initialState = { ReadStateSyncState() },
        fold = { state, event, _ ->
            val current = state as? ReadStateSyncState ?: ReadStateSyncState()
            val update = event as? ReadStateSyncEvent ?: return@SyncDomainConfig current
            reduceReadStateSyncState(current, update).state
        },
        fromSnapshot = { snapshot -> snapshot.state as? ReadStateSyncState ?: ReadStateSyncState() }
    )
