package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Per-frame ingest evidence line (`sync_core_t0_frame ...`) added for the dual-surface
 * subscription audit (task #13): the line must carry messageId + seq + applied watermark +
 * scope/partition identifiers — and no message content — so a same-id redelivery can be
 * told apart from a second application.
 */
class SyncCoreMessageShadowFoldLaneFrameLogTest {
    private class FakeFlagsApi(private val enabled: Boolean) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"evaluations":[{"key":"$SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY","enabled":$enabled}]}""",
            )
    }

    private fun flags(): SharedServerFeatureFlags =
        SharedServerFeatureFlags(FakeFlagsApi(enabled = true)).also { flags ->
            runBlocking { flags.prefetch(ServerId("s1")) }
        }

    private val partition =
        DurablePartitionKey("s1", "p1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test")

    private fun carry(id: String, seq: Long): CanonicalMessageCarry =
        CanonicalMessageCarry(
            row = CanonicalMessageRow(
                id = id,
                channelId = "c1",
                seq = seq,
                senderType = "user",
                senderId = "u1",
                content = "body-$id",
                messageType = "chat",
                createdAt = "2026-09-29T00:00:00Z",
                senderName = "Alice",
            ),
            presentAggregates = emptySet(),
        )

    private class NoopAdapter : FoldPersistenceAdapter {
        override suspend fun applyFrame(frame: FoldPersistFrame) {}
        override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
            RestoredFoldState(facts = emptyList(), cursor = null)
    }

    private fun lane(logs: MutableList<String>): SyncCoreMessageShadowFoldLane =
        SyncCoreMessageShadowFoldLane(
            featureFlags = flags(),
            adapter = NoopAdapter(),
            scope = CoroutineScope(SupervisorJob()),
            logger = logs::add,
        )

    @Test
    fun foldEmitsEvidenceLineWithIdSeqAndAppliedWatermark() {
        val logs = mutableListOf<String>()
        lane(logs).foldAndPersist(partition, carry("m-42", 7L), updated = false, epoch = null)

        val line = logs.firstOrNull { it.startsWith("sync_core_t0_frame ") }
        assertTrue(line != null, "expected a sync_core_t0_frame line, got $logs")
        assertTrue(line!!.contains("id=m-42"), line)
        assertTrue(line.contains("seq=7"), line)
        assertTrue(line.contains("applied="), line)
        assertTrue(line.contains("updated=false"), line)
        assertTrue(line.contains("scope=c1"), line)
        assertTrue(line.contains("partition=s1:$SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID"), line)
        assertTrue(!line.contains("body-"), "log must not carry message content: $line")
    }

    @Test
    fun redeliveryFoldsToSameWatermarkWithoutGrowingRows() {
        val logs = mutableListOf<String>()
        val lane = lane(logs)
        lane.foldAndPersist(partition, carry("m-42", 7L), updated = false, epoch = null)
        lane.foldAndPersist(partition, carry("m-42", 7L), updated = false, epoch = null)

        val frames = logs.filter { it.startsWith("sync_core_t0_frame ") }
        assertTrue(frames.size == 2, "expected two ingest lines, got $frames")
        assertTrue(frames.all { it.contains("id=m-42") && it.contains("seq=7") }, frames.toString())
    }
}
