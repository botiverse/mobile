package build.raft.app.data

import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2Store
import build.raft.app.network.sync.CanonicalMessageV2StoreWriteResult
import build.raft.app.network.sync.CanonicalMessageV2ScopePoisoned
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.RestoredCanonicalMessageV2Scope
import build.raft.app.network.sync.SyncScopeKey
import kotlinx.coroutines.CompletableDeferred

/**
 * Shared fakes for the V2 production binding tests (canary gateway, enter-snapshot loader, and the
 * real-SQLite round trip). The network fake answers only the three endpoints the snapshot fetcher
 * uses, for the channel ids it was told about; everything else is a 404.
 */
internal class RecordingNetworkClient(
    private val pageBody: String,
    private val channelIds: Set<String> = setOf("channel-1"),
    private val channelType: String = "channel",
    /** Body for `channels/{id}/threads`; `null` makes that endpoint fail (500). */
    private val threadSummariesBody: String? = "{}",
    /** Task v2: the `parentChannelId` a `type: "task"` channel detail names (omitted when null). */
    private val parentChannelId: String? = null,
) : RaftNetworkClient {
    val paths = mutableListOf<String>()

    /** When set, the messages page answer waits for this gate (in-flight dedupe tests). */
    var pageGate: CompletableDeferred<Unit>? = null

    /** Runs right before the messages page answer is produced (identity / partition change mid-fetch). */
    var onPageRequested: (suspend () -> Unit)? = null

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: build.raft.app.model.ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        paths += path
        val channelId = channelIds.firstOrNull {
            path == "channels/$it" || path == "messages/channel/$it" || path == "channels/$it/threads"
        } ?: return HomeNetworkResponse(404, null)
        return when (path) {
            "channels/$channelId" ->
                HomeNetworkResponse(
                    200,
                    parentChannelId
                        ?.let { parent -> """{"id":"$channelId","type":"$channelType","parentChannelId":"$parent"}""" }
                        ?: """{"id":"$channelId","type":"$channelType"}""",
                )
            "channels/$channelId/threads" ->
                threadSummariesBody?.let { HomeNetworkResponse(200, it) } ?: HomeNetworkResponse(500, null)
            else -> {
                pageGate?.await()
                onPageRequested?.invoke()
                HomeNetworkResponse(200, pageBody)
            }
        }
    }
}

internal class RecordingStore : CanonicalMessageV2Store {
    val snapshots = mutableListOf<CanonicalMessageV2SnapshotFrame>()
    var snapshotFailure: CanonicalMessageV2ScopePoisoned? = null

    override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
        snapshotFailure?.let { throw it }
        snapshots += frame
        return CanonicalMessageV2StoreWriteResult()
    }

    val incrementals = mutableListOf<CanonicalMessageV2IncrementalFrame>()

    override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
        incrementals += frame
        return CanonicalMessageV2StoreWriteResult()
    }

    override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
        RestoredCanonicalMessageV2Scope(emptyList(), null)

    override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> = setOf(scope)

    override suspend fun clearPartition(partition: DurablePartitionKey) = Unit
}

/**
 * Task #168 step 3: what the enter snapshot does around its commit, without a network: register the pending
 * baseline, commit [frame] inside the scope writer, then hand it to the canary (or abandon it).
 */
internal suspend fun commitEnterBaselineForTest(
    canary: build.raft.app.network.sync.CanonicalMessageV2ProductionCanary,
    engine: build.raft.app.network.sync.CanonicalMessageV2GenerationEngine,
    frame: CanonicalMessageV2SnapshotFrame,
    nonMember: Boolean = false,
): build.raft.app.network.sync.CanonicalMessageV2CommitResult {
    // A member channel on a connection whose rooms:joined arrived (task #168 step 3 gate).
    val epoch = canary.currentTransportEpochOf(frame.scope.partition)
        ?: 1L.also { canary.noteTransportEpoch(frame.scope.partition, it) }
    canary.noteRoomsJoined(frame.scope.partition, epoch)
    val baseline = requireNotNull(canary.beginEnterBaseline(frame.scope.partition, frame.scope.scopeId, nonMember))
    return engine.withScopeWriter(frame.scope) {
        engine.applySnapshot(frame).also { result ->
            if (result == build.raft.app.network.sync.CanonicalMessageV2CommitResult.Committed) {
                canary.onEnterCommitted(baseline, frame)
            } else {
                canary.abandonEnterBaseline(baseline)
            }
        }
    }
}

internal fun canonicalV2TestGrant() = RealtimeGrantRecord(
    slotKey = RealtimeSlotKey("scope-1", "server-1"),
    principalId = "user-1",
    environmentId = "env-test",
    authGeneration = 1L,
    connectionFamilyId = "family-1",
    connectionEpochId = "epoch-1",
)

internal fun canonicalV2TestSession() = StoredAuthSession(
    user = StoredAuthUser(id = "user-1", email = "alice@example.com"),
    accessToken = "access",
    refreshToken = "refresh",
    selectedServerId = "server-1",
    servers = listOf(StoredAuthServer(id = "server-1", name = "Server", slug = "server")),
)

/**
 * One full-v6 top-level message page in the real server shape: nodes never carry
 * `replyCount`/`threadSummary` (the server emits reply counts only via `channels/{id}/threads`).
 */
internal fun canonicalV2TestPage(channelId: String = "channel-1"): String =
    """{"messages":[{"id":"message-1","seq":7,"channelId":"$channelId","senderType":"user","senderId":"user-1","content":"hello","messageType":"chat","randomId":null,"threadId":null,"createdAt":"2026-07-17T00:00:00.000Z","senderName":"Alice","senderDescription":null,"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":[],"commentRef":null,"actionMetadata":null}],"historyLimited":false}"""

/** Same page, but the single message is a thread parent (threadId anchors the thread channel). */
internal fun canonicalV2TestThreadParentPage(channelId: String = "channel-1"): String =
    """{"messages":[{"id":"message-1","seq":7,"channelId":"$channelId","senderType":"user","senderId":"user-1","content":"hello","messageType":"chat","randomId":null,"threadId":"thread-9","createdAt":"2026-07-17T00:00:00.000Z","senderName":"Alice","senderDescription":null,"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":[],"commentRef":null,"actionMetadata":null}],"historyLimited":false}"""

/** A page whose node is missing one of the seven required full-v6 keys. */
internal fun canonicalV2TestThinPage(channelId: String = "channel-1"): String =
    """{"messages":[{"id":"message-1","seq":7,"channelId":"$channelId","senderType":"user","senderId":"user-1","content":"hello","messageType":"chat","randomId":null,"threadId":null,"createdAt":"2026-07-17T00:00:00.000Z","senderName":"Alice","senderDescription":null,"senderMembershipStatus":null,"mentions":[],"attachments":[],"commentRef":null,"actionMetadata":null}],"historyLimited":false}"""

/** `channels/{id}/threads` response body; keyed by parent message id, as the server emits. */
internal fun canonicalV2TestThreadSummaries(replyCount: Int = 4): String =
    """{"message-1":{"threadChannelId":"thread-9","replyCount":$replyCount,"unreadCount":0,"participantIds":[]}}"""
