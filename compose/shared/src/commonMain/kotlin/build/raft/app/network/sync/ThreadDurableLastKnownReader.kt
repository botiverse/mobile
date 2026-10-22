package build.raft.app.network.sync

import build.raft.app.canonical.message.MessageAggregateField

/** One restored V2 scope (top-level or, task #168 step 3, reply thread), ready to be projected as the thread screen's offline first frame. */
internal data class ThreadDurableLastKnownFrame(
    val scope: SyncScopeKey,
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val cursor: PersistedCursor,
    /**
     * Task #14a: the partial facts' unknown aggregates (absent id = all known). Their envelopes hold
     * placeholders (no description, no reactions), which is how the stand-in renders them; never a value
     * to compare against or write back.
     */
    val unknownFieldsByFactId: Map<String, Set<MessageAggregateField>> = emptyMap(),
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
    /**
     * Last known reply-preview windows (Hands dd79da45), read beside the frame so the first frame
     * of a cold-started screen carries its replies blocks. Null: no durable reply windows.
     */
    val replyWindowCache: RaftThreadReplyWindowCache?
        get() = null

    suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame?

    /**
     * Task #168 step 3: the last known replies of the reply thread [threadChannelId]. Same gates as
     * [read], but only a `thread` scope qualifies, and only when its stored cursor names exactly
     * [replyParent] (parent channel + root message). Default: no reply-thread frames.
     */
    suspend fun readReplyThread(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
    ): ThreadDurableLastKnownFrame? = null

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
        return read(partition, channelId)?.olderPage(beforeSeq, limit)
    }

    /** [readBefore] for a reply thread; built on [readReplyThread], so it has the same gates. */
    suspend fun readReplyThreadBefore(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        beforeSeq: Long,
        limit: Int,
    ): ThreadDurableOlderPage? {
        require(limit > 0)
        return readReplyThread(partition, threadChannelId, replyParent)?.olderPage(beforeSeq, limit)
    }

    /**
     * Task #14 part C: offline NEWER paging of an anchored stand-in that is not the window's tail — the oldest
     * [limit] facts of the stored window newer than [afterSeq], ascending. Built on [read], same gates.
     */
    suspend fun readAfter(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        afterSeq: Long,
        limit: Int,
    ): ThreadDurableNewerPage? {
        require(limit > 0)
        return read(partition, channelId)?.newerPage(afterSeq, limit)
    }

    /** [readAfter] for a reply thread; built on [readReplyThread], so it has the same gates. */
    suspend fun readReplyThreadAfter(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        afterSeq: Long,
        limit: Int,
    ): ThreadDurableNewerPage? {
        require(limit > 0)
        return readReplyThread(partition, threadChannelId, replyParent)?.newerPage(afterSeq, limit)
    }
}

private fun ThreadDurableLastKnownFrame.newerPage(afterSeq: Long, limit: Int): ThreadDurableNewerPage {
    val newer = windowEnvelopes().filter { it.fact.seq > afterSeq }.sortedBy { it.fact.seq }
    val page = newer.take(limit)
    return ThreadDurableNewerPage(
        scope = scope,
        envelopes = page,
        hasNewerStored = newer.size > page.size,
        hasLoadedNewest = cursor.hasLoadedNewest,
    )
}

/**
 * One offline newer page read from the stored window (task #14 part C). [hasNewerStored]: the window holds
 * still-newer facts above [envelopes]; [hasLoadedNewest]: the stored cursor says the window reaches the
 * conversation's newest message.
 */
internal data class ThreadDurableNewerPage(
    val scope: SyncScopeKey,
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val hasNewerStored: Boolean,
    val hasLoadedNewest: Boolean,
)

private fun ThreadDurableLastKnownFrame.olderPage(beforeSeq: Long, limit: Int): ThreadDurableOlderPage {
    val older = windowEnvelopes().filter { it.fact.seq < beforeSeq }.sortedBy { it.fact.seq }
    val page = older.takeLast(limit)
    return ThreadDurableOlderPage(
        scope = scope,
        envelopes = page,
        hasOlderStored = older.size > page.size,
        hasLoadedOldest = cursor.hasLoadedOldest,
    )
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

/** Top-level scope kinds the V2 writer persists. Reply threads are the separate `thread` kind. */
internal val CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS: Set<String> = setOf("channel", "private", "dm", "joint")

/** The one reply-thread scope kind (task #168 step 3); its cursor carries the parent scope and root. */
internal val CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS: Set<String> = setOf("thread")

internal class CanonicalMessageV2DurableLastKnownReader(
    private val store: CanonicalMessageV2Store,
    private val isEnabledForServer: (serverId: String) -> Boolean,
    private val logger: (String) -> Unit = {},
    override val replyWindowCache: RaftThreadReplyWindowCache? = null,
) : ThreadDurableLastKnownReader {
    override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? =
        readScope(partition, channelId, CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS)

    override suspend fun readReplyThread(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
    ): ThreadDurableLastKnownFrame? {
        val frame = readScope(partition, threadChannelId, CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS) ?: return null
        if (
            frame.cursor.parentScopeId != replyParent.parentChannelId ||
            frame.cursor.rootMessageId != replyParent.rootMessageId
        ) {
            logger(
                "durable_last_known outcome=parent_mismatch server=${partition.serverId} " +
                    "scope=$threadChannelId scopeKind=${frame.scope.scopeKind}",
            )
            return null
        }
        return frame
    }

    private suspend fun readScope(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        allowedKinds: Set<String>,
    ): ThreadDurableLastKnownFrame? {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        if (scopeId.isBlank() || scopeId != scopeId.trim()) return null
        if (!isEnabledForServer(partition.serverId)) return null
        val kinds = store.baselinedScopeKinds(partition, scopeId).filter { it in allowedKinds }
        val scopeKind = when (kinds.size) {
            0 -> return null
            1 -> kinds.single()
            else -> {
                logger(
                    "durable_last_known outcome=ambiguous_scope_kind server=${partition.serverId} " +
                        "scope=$scopeId kinds=${kinds.joinToString(",")}",
                )
                return null
            }
        }
        val scope = SyncScopeKey(partition = partition, scopeId = scopeId, scopeKind = scopeKind)
        val restored = try {
            store.restore(scope)
        } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
            logger(
                "durable_last_known outcome=poisoned server=${partition.serverId} scope=$scopeId " +
                    "scopeKind=$scopeKind fact=${poisoned.factId.orEmpty()}",
            )
            return null
        }
        val cursor = restored.cursor ?: return null
        return ThreadDurableLastKnownFrame(
            scope = scope,
            envelopes = restored.envelopes,
            cursor = cursor,
            unknownFieldsByFactId = restored.unknownFieldsByFactId,
        )
    }
}
