package build.raft.app.data.realtime

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * task #354: pins the raw-ingest topic split — raw server realtime events
 * travel on a dedicated topic to the single canonical AppShell ingest so a
 * multi-MiB event is bridge-decoded once instead of once per open page.
 */
class RealtimeRawIngestTopicContractTest {

    @Test
    fun rawIngestTopicIsDistinctAndDerivedFromTheNotifyContract() {
        assertTrue(RaftRealtimeNotifyContract.RawIngestEventName.isNotBlank())
        assertTrue(
            RaftRealtimeNotifyContract.RawIngestEventName !=
                RaftRealtimeNotifyContract.EventName,
            "raw ingest must be a separate topic so pages never parse large raw events"
        )
        assertTrue(
            RaftRealtimeNotifyContract.RawIngestEventName
                .startsWith(RaftRealtimeNotifyContract.EventName),
            "derived name keeps the contract family greppable"
        )
    }
}
