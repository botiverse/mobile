package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SlockFeatureFlagsApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncCoreMessageDomainStoreTest {
    @Test
    fun flagOffKeepsGlobalProjectionLiveWithZeroSqliteEgressAndMatchesFlagOnMemory() = runBlocking {
        val offAdapter = RecordingAdapter()
        val onAdapter = RecordingAdapter()
        val offScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val onScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val off = SyncCoreMessageShadowFoldLane(flags(false), offAdapter, offScope, logger = {})
        val on = SyncCoreMessageShadowFoldLane(flags(true), onAdapter, onScope, logger = {})

        val baseline = canonical("m-10", 10).row
        listOf(off, on).forEach { lane ->
            val token = lane.beginAuthoritativeRebaseline("server-1", "user-1", "channel-1", epoch = "epoch-1")!!
            lane.completeAuthoritativeRebaseline(
                token = token,
                memberId = "user-1",
                receiverKind = MessageReceiverKind.User,
                rows = listOf(baseline),
                coveredAfterSeq = 9,
                coveredFromSeq = 10,
                coveredThroughSeq = 10,
                remoteHighWaterSeq = 10,
                hasGap = false,
                hasNewer = false,
            )
            lane.foldAndPersist(
                resolvedServerId = "server-1",
                memberId = "user-1",
                decoded = canonical("m-11", 11),
                updated = false,
                epoch = "epoch-1",
            )
        }

        val checkpoint = MessageReadStateCheckpoint(readStateVersion = 2, maxReadSeq = 10)
        val offProjection = off.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", "epoch-1", checkpoint)
        val onProjection = on.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", "epoch-1", checkpoint)

        assertEquals(onProjection, offProjection, "rollout flag must not change in-memory state or projection")
        assertTrue(offProjection.complete)
        assertEquals(1, offProjection.unreadCount)
        assertEquals(0, offAdapter.appliedFrames)
        assertEquals(1, onAdapter.appliedFrames)
        offScope.cancel()
        onScope.cancel()
    }

    @Test
    fun projectionUsesIndependentOwnerEpochAndFailedGenerationDropsOldCoverage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lane = SyncCoreMessageShadowFoldLane(flags(false), RecordingAdapter(), scope, logger = {})
        val baseline = canonical("m-10", 10).row
        val first = lane.beginAuthoritativeRebaseline("server-1", "user-1", "channel-1", epoch = "epoch-a")!!
        lane.completeAuthoritativeRebaseline(
            token = first,
            memberId = "user-1",
            receiverKind = MessageReceiverKind.User,
            rows = listOf(baseline),
            coveredAfterSeq = 9,
            coveredFromSeq = 10,
            coveredThroughSeq = 10,
            remoteHighWaterSeq = 10,
            hasGap = false,
            hasNewer = false,
        )
        val checkpoint = MessageReadStateCheckpoint(readStateVersion = 1, maxReadSeq = 10)

        assertTrue(lane.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", "epoch-a", checkpoint).complete)
        assertFalse(lane.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", "epoch-b", checkpoint).complete)

        val failed = lane.beginAuthoritativeRebaseline("server-1", "user-1", "channel-1", epoch = "epoch-a")!!
        lane.invalidateAuthoritativeRebaseline(failed)

        assertFalse(lane.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", "epoch-a", checkpoint).complete)

        val restored = lane.beginAuthoritativeRebaseline("server-1", "user-1", "channel-1", epoch = "epoch-a")!!
        lane.completeAuthoritativeRebaseline(
            token = restored,
            memberId = "user-1",
            receiverKind = MessageReceiverKind.User,
            rows = listOf(baseline),
            coveredAfterSeq = 9,
            coveredFromSeq = 10,
            coveredThroughSeq = 10,
            remoteHighWaterSeq = 10,
            hasGap = false,
            hasNewer = false,
        )
        lane.invalidateScopeCoverage("server-1", "user-1", "channel-1")

        assertFalse(lane.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", "epoch-a", checkpoint).complete)
        scope.cancel()
    }

    @Test
    fun principalSwitchIsolatesFramesAndRejectsLateSnapshotAcrossOwnerGenerations() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lane = SyncCoreMessageShadowFoldLane(flags(false), RecordingAdapter(), scope, logger = {})
        val aBaseline = canonical("a-10", 10).row
        val aToken = lane.beginAuthoritativeRebaseline("server-1", "user-a", "channel-1", epoch = "epoch-1")!!
        assertIs<SyncIngestOutcome.Applied>(
            lane.completeAuthoritativeRebaseline(
                token = aToken,
                memberId = "user-a",
                receiverKind = MessageReceiverKind.User,
                rows = listOf(aBaseline),
                coveredAfterSeq = 9,
                coveredFromSeq = 10,
                coveredThroughSeq = 10,
                remoteHighWaterSeq = 10,
                hasGap = false,
                hasNewer = false,
            )
        )
        val pendingA = lane.beginAuthoritativeRebaseline("server-1", "user-a", "channel-1", epoch = "epoch-1")!!

        lane.foldAndPersist(
            resolvedServerId = "server-1",
            memberId = "user-b",
            decoded = canonical("b-11", 11),
            updated = false,
            epoch = "epoch-1",
        )

        assertIs<SyncIngestOutcome.DuplicateDropped>(
            lane.completeAuthoritativeRebaseline(
                token = pendingA,
                memberId = "user-a",
                receiverKind = MessageReceiverKind.User,
                rows = listOf(aBaseline, canonical("a-12", 12).row),
                coveredAfterSeq = 9,
                coveredFromSeq = 10,
                coveredThroughSeq = 12,
                remoteHighWaterSeq = 12,
                hasGap = false,
                hasNewer = false,
            )
        )
        assertFalse(
            lane.projectReadState(
                "server-1",
                "user-b",
                MessageReceiverKind.User,
                "channel-1",
                "epoch-1",
                MessageReadStateCheckpoint(readStateVersion = 1, maxReadSeq = 10),
            ).complete
        )

        val returnedA = lane.beginAuthoritativeRebaseline("server-1", "user-a", "channel-1", epoch = "epoch-1")!!
        lane.completeAuthoritativeRebaseline(
            token = returnedA,
            memberId = "user-a",
            receiverKind = MessageReceiverKind.User,
            rows = listOf(aBaseline),
            coveredAfterSeq = 9,
            coveredFromSeq = 10,
            coveredThroughSeq = 10,
            remoteHighWaterSeq = 10,
            hasGap = false,
            hasNewer = false,
        )
        val projection =
            lane.projectReadState(
                "server-1",
                "user-a",
                MessageReceiverKind.User,
                "channel-1",
                "epoch-1",
                MessageReadStateCheckpoint(readStateVersion = 2, maxReadSeq = 9),
            )
        assertTrue(projection.complete)
        assertEquals(1, projection.unreadCount, "B frame and late A snapshot must not replay into returned A")
        scope.cancel()
    }

    private fun canonical(id: String, seq: Long): CanonicalMessageCarry =
        requireNotNull(
            decodeCanonicalMessageWire(
                buildJsonObject {
                    put("id", id)
                    put("seq", seq)
                    put("channelId", "channel-1")
                    put("senderType", "user")
                    put("senderId", "user-2")
                    put("senderName", "User 2")
                    put("content", "message $seq")
                    put("messageType", "chat")
                    put("randomId", JsonNull)
                    put("threadId", JsonNull)
                    put("createdAt", "2026-07-14T00:00:00Z")
                },
                ProductionFace.MessageNew,
            )
        )

    private fun flags(enabled: Boolean): SharedServerFeatureFlags =
        SharedServerFeatureFlags(
            api =
                object : SlockFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                        HomeNetworkResponse(
                            code = 200,
                            bodyText =
                                """{"evaluations":[{"key":"$SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY","enabled":$enabled}]}""",
                        )
                }
        ).also { runBlocking { it.prefetch(ServerId("server-1")) } }

    private class RecordingAdapter : FoldPersistenceAdapter {
        var appliedFrames: Int = 0

        override suspend fun applyFrame(frame: FoldPersistFrame) {
            appliedFrames += 1
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
            RestoredFoldState(facts = emptyList(), cursor = null)
    }
}
