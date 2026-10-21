package build.raft.app.network.sync

/**
 * Lost-access eviction for the durable `sync-core.messages.v2` cache (data-layer plan, Tenny rule 2).
 *
 * When a background validation proves the account can no longer read a conversation (403 / 404), its
 * persisted window must not paint again on the next open. When the account is removed from a server
 * (or the server is deleted), that server's whole partition goes.
 *
 * Both operations go through [CanonicalMessageV2GenerationEngine], which serializes them with the
 * partition writer, bumps the commit generations (so an in-flight snapshot cannot re-commit the
 * evicted rows) and notifies the canary's invalidation / revocation observers.
 */
internal interface ThreadDurableAccessEvictor {
    /** Clears every baselined scope kind stored for [channelId] (and its dependent reply threads). */
    suspend fun evictConversation(partition: DurablePartitionKey, channelId: SyncScopeId): Int

    suspend fun evictServerPartition(partition: DurablePartitionKey)
}

internal class CanonicalMessageV2DurableAccessEvictor(
    private val engine: CanonicalMessageV2GenerationEngine,
    private val logger: (String) -> Unit = {},
) : ThreadDurableAccessEvictor {
    override suspend fun evictConversation(partition: DurablePartitionKey, channelId: SyncScopeId): Int {
        if (partition.consumerId != SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) return 0
        if (channelId.isBlank() || channelId != channelId.trim()) return 0
        val kinds = engine.baselinedScopeKinds(partition, channelId)
        kinds.forEach { kind ->
            engine.clearScope(SyncScopeKey(partition = partition, scopeId = channelId, scopeKind = kind))
        }
        logger(
            "durable_access_evict scope=conversation server=${partition.serverId} " +
                "channel=$channelId kinds=${kinds.joinToString(",")}"
        )
        return kinds.size
    }

    override suspend fun evictServerPartition(partition: DurablePartitionKey) {
        if (partition.consumerId != SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) return
        engine.clearPartition(partition)
        logger("durable_access_evict scope=server server=${partition.serverId}")
    }
}
