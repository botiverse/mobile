package build.raft.app.data

import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2Store
import build.raft.app.network.sync.CanonicalMessageV2StoreWriteResult
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.RestoredCanonicalMessageV2Scope
import build.raft.app.network.sync.SyncScopeKey
import kotlinx.coroutines.CompletableDeferred

/**
 * Shared fakes for the V2 production binding tests (canary gateway, enter-snapshot loader, and the
 * real-SQLite round trip). The network fake answers only the two endpoints the snapshot fetcher
 * uses, for the channel ids it was told about; everything else is a 404.
 */
internal class RecordingNetworkClient(
    private val pageBody: String,
    private val channelIds: Set<String> = setOf("channel-1"),
    private val channelType: String = "channel",
) : SlockNetworkClient {
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
        val channelId = channelIds.firstOrNull { path == "channels/$it" || path == "messages/channel/$it" }
            ?: return HomeNetworkResponse(404, null)
        return if (path == "channels/$channelId") {
            HomeNetworkResponse(200, """{"id":"$channelId","type":"$channelType"}""")
        } else {
            pageGate?.await()
            onPageRequested?.invoke()
            HomeNetworkResponse(200, pageBody)
        }
    }
}

internal class RecordingStore : CanonicalMessageV2Store {
    val snapshots = mutableListOf<CanonicalMessageV2SnapshotFrame>()

    override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
        snapshots += frame
        return CanonicalMessageV2StoreWriteResult()
    }

    override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame) =
        CanonicalMessageV2StoreWriteResult()

    override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
        RestoredCanonicalMessageV2Scope(emptyList(), null)

    override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> = setOf(scope)

    override suspend fun clearPartition(partition: DurablePartitionKey) = Unit
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

/** One full-v6 top-level message page; `includeReplyCount = false` drops the authoritative reply count. */
internal fun canonicalV2TestPage(includeReplyCount: Boolean = true, channelId: String = "channel-1"): String {
    val replyCount = if (includeReplyCount) ",\"replyCount\":4" else ""
    return """{"messages":[{"id":"message-1","seq":7,"channelId":"$channelId","senderType":"user","senderId":"user-1","content":"hello","messageType":"chat","randomId":null,"threadId":null,"createdAt":"2026-07-17T00:00:00.000Z","senderName":"Alice","senderDescription":null,"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":[],"commentRef":null,"actionMetadata":null$replyCount}],"historyLimited":false}"""
}
