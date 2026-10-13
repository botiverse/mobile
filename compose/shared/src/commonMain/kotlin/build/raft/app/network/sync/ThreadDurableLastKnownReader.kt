package build.raft.app.network.sync

/** One restored top-level V2 scope, ready to be projected as the thread screen's offline first frame. */
internal data class ThreadDurableLastKnownFrame(
    val scope: SyncScopeKey,
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val cursor: PersistedCursor,
)

/**
 * Read entry of the offline cache (task #1123, Phase 2-1): the last known timeline of one channel
 * as persisted by the `sync-core.messages.v2` consumer, keyed by the caller's durable partition.
 *
 * The reader is deliberately read-only and identity-strict. It never widens what the writer selected,
 * never touches the baseline coordinator, and answers `null` (no frame) for every non-positive case:
 * server flag off, no committed baseline for the channel, an ambiguous scope kind, or a poisoned
 * scope. Callers must treat `null` as "render the ordinary loading shell", never as an error.
 *
 * A committed baseline with no facts is a frame with empty [ThreadDurableLastKnownFrame.envelopes]
 * (task #1137): the channel was empty when last seen, which is an answer, not a miss.
 */
internal interface ThreadDurableLastKnownReader {
    suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame?

    /**
     * Offline paging (task #168 step 2b): the newest [limit] facts of the stored window that are
     * older than [beforeSeq], in ascending seq order. Same gates as [read] (it is built on it: flag,
     * baseline, scope kind, poison), so `null` means "no frame". The store restores a whole scope in
     * one query (at most the retention cap of 500 facts), so filtering the restored frame IS the
     * `before` query; there is no cheaper SQL path to add.
     */
    suspend fun readBefore(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        beforeSeq: Long,
        limit: Int,
    ): ThreadDurableOlderPage? {
        require(limit > 0)
        val frame = read(partition, channelId) ?: return null
        val older = frame.windowEnvelopes().filter { it.fact.seq < beforeSeq }.sortedBy { it.fact.seq }
        val page = older.takeLast(limit)
        return ThreadDurableOlderPage(
            scope = frame.scope,
            envelopes = page,
            hasOlderStored = older.size > page.size,
            hasLoadedOldest = frame.cursor.hasLoadedOldest,
        )
    }
}

/**
 * One offline older page read from the stored window. [hasOlderStored]: the window holds still-older
 * facts below [envelopes]; [hasLoadedOldest]: the stored cursor says the window reaches the
 * channel's first message.
 */
internal data class ThreadDurableOlderPage(
    val scope: SyncScopeKey,
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val hasOlderStored: Boolean,
    val hasLoadedOldest: Boolean,
)

/**
 * The facts inside the stored window (`seq >= cursor.oldestLoadedSeq`). Facts below it can only be
 * thread roots kept alive by a reply scope after retention trimmed the window; they are not
 * contiguous with it, so offline paging never serves them.
 */
internal fun ThreadDurableLastKnownFrame.windowEnvelopes(): List<CanonicalMessageEnvelopeV2> {
    val oldest = cursor.oldestLoadedSeq ?: return envelopes
    return envelopes.filter { it.fact.seq >= oldest }
}

/** Top-level scope kinds the V2 writer persists. Reply threads are not readable here (no writer yet). */
internal val CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS: Set<String> = setOf("channel", "private", "dm", "joint")

internal class CanonicalMessageV2DurableLastKnownReader(
    private val store: CanonicalMessageV2Store,
    private val isEnabledForServer: (serverId: String) -> Boolean,
    private val logger: (String) -> Unit = {},
) : ThreadDurableLastKnownReader {
    override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        if (channelId.isBlank() || channelId != channelId.trim()) return null
        if (!isEnabledForServer(partition.serverId)) return null
        val kinds = store.baselinedScopeKinds(partition, channelId)
            .filter { it in CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS }
        val scopeKind = when (kinds.size) {
            0 -> return null
            1 -> kinds.single()
            else -> {
                logger(
                    "durable_last_known outcome=ambiguous_scope_kind server=${partition.serverId} " +
                        "scope=$channelId kinds=${kinds.joinToString(",")}",
                )
                return null
            }
        }
        val scope = SyncScopeKey(partition = partition, scopeId = channelId, scopeKind = scopeKind)
        val restored = try {
            store.restore(scope)
        } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
            logger(
                "durable_last_known outcome=poisoned server=${partition.serverId} scope=$channelId " +
                    "fact=${poisoned.factId.orEmpty()}",
            )
            return null
        }
        val cursor = restored.cursor ?: return null
        return ThreadDurableLastKnownFrame(scope = scope, envelopes = restored.envelopes, cursor = cursor)
    }
}
