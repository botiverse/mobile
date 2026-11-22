package build.raft.app.network.sync

import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.coroutines.CancellationException

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
 * Message storage design 2026-10-27 (Phase 2d): bounded timeline read anchor.
 *
 * - [Newest]: newest [limit] facts of the scope (head + loose facts above head, crossing into lower islands
 *   with gaps, never hijacked by non-head context islands above head).
 * - [Before]: newest [limit] facts strictly below [beforeSeq]. When [crossGaps] is `false` (online local
 *   paging), stays strictly inside the contiguous segment covering `beforeSeq - 1`; when `true` (offline),
 *   crosses into lower stored islands and derives boundary/internal gaps.
 * - [After]: symmetric mirror strictly above [afterSeq].
 * - [AroundMessageId]: window centered on [messageId] (`beforeCount` older + anchor + `afterCount` newer),
 *   resolving via `selectFactLocationById` so island-only scopes open locally without a head segment.
 */
internal sealed interface ThreadDurableTimelineAnchor {
    data class Newest(
        val limit: Int,
    ) : ThreadDurableTimelineAnchor {
        init {
            require(limit > 0) { "limit must be positive" }
        }
    }

    data class Before(
        val beforeSeq: Long,
        val limit: Int,
        val crossGaps: Boolean = true,
    ) : ThreadDurableTimelineAnchor {
        init {
            require(limit > 0) { "limit must be positive" }
        }
    }

    data class After(
        val afterSeq: Long,
        val limit: Int,
        val crossGaps: Boolean = true,
    ) : ThreadDurableTimelineAnchor {
        init {
            require(limit > 0) { "limit must be positive" }
        }
    }

    data class AroundMessageId(
        val messageId: String,
        val beforeCount: Int,
        val afterCount: Int,
    ) : ThreadDurableTimelineAnchor {
        init {
            require(beforeCount in 0 until Int.MAX_VALUE) { "beforeCount must be in 0 until Int.MAX_VALUE" }
            require(afterCount >= 0) { "afterCount must be non-negative" }
        }
    }
}

/**
 * Message storage design 2026-10-27 (Phase 2d): one bounded SQLite timeline read result with derived
 * (never persisted) [gaps] and exact proven boundary flags.
 */
internal data class ThreadDurableTimelinePage(
    val scope: SyncScopeKey,
    val envelopes: List<CanonicalMessageEnvelopeV2>,
    val gaps: List<CanonicalMessageV2TimelineGap>,
    val cursor: PersistedCursor,
    val segments: List<CanonicalMessageV2Segment>,
    val hasOlderStored: Boolean,
    val hasNewerStored: Boolean,
    val reachesOldest: Boolean,
    val reachesHead: Boolean,
    /**
     * For [ThreadDurableTimelineAnchor.Before] / [ThreadDurableTimelineAnchor.After]: whether the queried
     * adjacent seq (`beforeSeq - 1` / `afterSeq + 1`) lies inside a proven contiguous segment; for
     * [ThreadDurableTimelineAnchor.Newest], whether the newest returned fact lies inside the head segment;
     * for [ThreadDurableTimelineAnchor.AroundMessageId], whether the anchor fact lies inside a segment.
     */
    val insideProvenSegment: Boolean = false,
    val unknownFieldsByFactId: Map<String, Set<MessageAggregateField>> = emptyMap(),
)

/**
 * Read entry of the offline cache (task #1123, Phase 2-1 / Phase 2d): the last known timeline of one channel
 * as persisted by the `sync-core.messages.v2` consumer, keyed by the caller's durable partition.
 *
 * Legacy entries ([read], [readReplyThread], [readBefore], [readReplyThreadBefore]) are strictly read-only
 * and identity-strict: they never mutate `lastUserAccessAtMs` or touch the baseline coordinator, and answer
 * `null` for every non-positive case (no committed baseline, ambiguous scope kind, or poisoned scope).
 * Phase 2d bounded entries ([readTimeline], [readReplyThreadTimeline]) read at most the requested window
 * via SQLite `LIMIT`, derive timeline gaps from stored segments, and record user access via [touchUserAccess]
 * (either inline when `touchUserAccess = true` or deferred off-Main by the caller when `touchUserAccess = false`).
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
     * older than [beforeSeq], in ascending seq order. Same gates as [read] (baseline, scope kind,
     * poison, strictly read-only), so `null` means "no frame".
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
     * Phase 2d: bounded timeline read for a top-level scope, returning stored facts + derived gaps
     * without scanning the whole scope. Set [touchUserAccess] to `false` on UI-thread opens when the
     * caller will invoke [touchUserAccess] asynchronously off-Main after provenance/surface validation.
     * Default returns `null` for minimal test fakes.
     */
    suspend fun readTimeline(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        anchor: ThreadDurableTimelineAnchor,
        touchUserAccess: Boolean = true,
    ): ThreadDurableTimelinePage? = null

    /**
     * Phase 2d: bounded timeline read for a reply thread scope, enforcing [replyParent] identity.
     * Default returns `null` for minimal test fakes.
     */
    suspend fun readReplyThreadTimeline(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        anchor: ThreadDurableTimelineAnchor,
        touchUserAccess: Boolean = true,
    ): ThreadDurableTimelinePage? = null

    /** Phase 2d: locate stored copies of [messageId] inside [partition] without scanning scopes. */
    suspend fun locateMessage(
        partition: DurablePartitionKey,
        messageId: String,
    ): List<CanonicalMessageV2FactLocation> = emptyList()

    /**
     * Phase 2d: best-effort user-access touch for [scope] (and its parent when [scope] is a reply thread).
     * Intended for callers that pass `touchUserAccess = false` on Main-thread first-frame reads and dispatch
     * the access write asynchronously off-Main after provenance/surface checks pass.
     */
    suspend fun touchUserAccess(scope: SyncScopeKey) {}
}

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

/** Task v2: the scope kind of a task's own conversation (a flat, root-less window like a channel's). */
const val CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND: String = "task"

/**
 * The kinds a reply thread's parent may have (and a `thread` cursor stores): the four producer
 * top-level kinds. A task conversation is never a reply parent.
 */
internal val CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS: Set<String> = setOf("channel", "private", "dm", "joint")

/**
 * Top-level (flat window) scope kinds the V2 writer persists: the reply-parent kinds plus a task v2
 * conversation. Reply threads are the separate `thread` kind.
 */
internal val CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS: Set<String> =
    CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS + CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND

/** The one reply-thread scope kind (task #168 step 3); its cursor carries the parent scope and root. */
internal val CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS: Set<String> = setOf("thread")

internal class CanonicalMessageV2DurableLastKnownReader(
    private val store: CanonicalMessageV2Store,
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

    override suspend fun readBefore(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        beforeSeq: Long,
        limit: Int,
    ): ThreadDurableOlderPage? {
        require(limit > 0)
        return readWindowOlderPage(
            partition = partition,
            scopeId = channelId,
            allowedKinds = CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS,
            replyParent = null,
            beforeSeq = beforeSeq,
            limit = limit,
        )
    }

    override suspend fun readReplyThreadBefore(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        beforeSeq: Long,
        limit: Int,
    ): ThreadDurableOlderPage? {
        require(limit > 0)
        return readWindowOlderPage(
            partition = partition,
            scopeId = threadChannelId,
            allowedKinds = CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS,
            replyParent = replyParent,
            beforeSeq = beforeSeq,
            limit = limit,
        )
    }

    override suspend fun readTimeline(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        anchor: ThreadDurableTimelineAnchor,
        touchUserAccess: Boolean,
    ): ThreadDurableTimelinePage? =
        readScopeTimeline(
            partition = partition,
            scopeId = channelId,
            allowedKinds = CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS,
            replyParent = null,
            anchor = anchor,
            touchUserAccess = touchUserAccess,
        )

    override suspend fun readReplyThreadTimeline(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        anchor: ThreadDurableTimelineAnchor,
        touchUserAccess: Boolean,
    ): ThreadDurableTimelinePage? =
        readScopeTimeline(
            partition = partition,
            scopeId = threadChannelId,
            allowedKinds = CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS,
            replyParent = replyParent,
            anchor = anchor,
            touchUserAccess = touchUserAccess,
        )

    override suspend fun locateMessage(
        partition: DurablePartitionKey,
        messageId: String,
    ): List<CanonicalMessageV2FactLocation> {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        if (messageId.isBlank() || messageId != messageId.trim()) return emptyList()
        return store.locateFact(partition, messageId)
    }

    override suspend fun touchUserAccess(scope: SyncScopeKey) {
        touchUserAccessBestEffort(scope)
    }

    private suspend fun resolveScope(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        allowedKinds: Set<String>,
    ): SyncScopeKey? {
        require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        if (scopeId.isBlank() || scopeId != scopeId.trim()) return null
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
        return SyncScopeKey(partition = partition, scopeId = scopeId, scopeKind = scopeKind)
    }

    private fun cursorMatchesReplyParent(
        scope: SyncScopeKey,
        cursor: PersistedCursor,
        replyParent: ThreadDurableReplyParent?,
    ): Boolean {
        if (replyParent == null) return true
        if (
            cursor.parentScopeId != replyParent.parentChannelId ||
            cursor.rootMessageId != replyParent.rootMessageId
        ) {
            logger(
                "durable_last_known outcome=parent_mismatch server=${scope.serverId} " +
                    "scope=${scope.scopeId} scopeKind=${scope.scopeKind}",
            )
            return false
        }
        return true
    }

    private suspend fun touchUserAccessBestEffort(scope: SyncScopeKey) {
        try {
            store.touchUserAccess(scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logger(
                "durable_last_known outcome=touch_access_failed server=${scope.serverId} " +
                    "scope=${scope.scopeId} scopeKind=${scope.scopeKind} error=${t::class.simpleName}",
            )
        }
    }

    private data class BoundedEligibleOlderResult(
        val envelopes: List<CanonicalMessageEnvelopeV2>,
        val hasOlderEligible: Boolean,
        val unknownFieldsByFactId: Map<String, Set<MessageAggregateField>>,
    )

    /**
     * Bounded downward scan stepping past skipped segments by jumping straight to `segment.effectiveLowSeq`.
     * Bounded to at most `segments.size + 1` SQLite queries even when large non-head context islands sit above head.
     */
    private suspend fun readEligibleOlderSkippingSegments(
        scope: SyncScopeKey,
        segments: List<CanonicalMessageV2Segment>,
        initialBeforeSeq: Long,
        limit: Int,
        oldestMinSeq: Long?,
        shouldSkipSegment: (CanonicalMessageV2Segment) -> Boolean,
    ): BoundedEligibleOlderResult {
        val collectedDesc = mutableListOf<CanonicalMessageEnvelopeV2>()
        val unknowns = mutableMapOf<String, Set<MessageAggregateField>>()
        var cursorSeq = initialBeforeSeq
        var reachedLowerBound = false
        var exhaustedStore = false

        while (collectedDesc.size < limit) {
            if (oldestMinSeq != null && cursorSeq <= oldestMinSeq) {
                reachedLowerBound = true
                break
            }
            val remaining = limit - collectedDesc.size
            val raw = store.readFactsBefore(scope, beforeSeq = cursorSeq, limit = remaining)
            if (raw.envelopes.isEmpty()) {
                exhaustedStore = true
                break
            }
            var jumpedPastSegment = false
            var lowestConsumedSeq = cursorSeq
            for (index in raw.envelopes.lastIndex downTo 0) {
                val envelope = raw.envelopes[index]
                val seq = envelope.fact.seq
                if (oldestMinSeq != null && seq < oldestMinSeq) {
                    reachedLowerBound = true
                    break
                }
                val member = CanonicalMessageV2SegmentAlgebra.memberOf(segments, seq)
                if (member != null && shouldSkipSegment(member)) {
                    val jumpTo = minOf(seq, member.effectiveLowSeq)
                    if (jumpTo >= cursorSeq) {
                        exhaustedStore = true
                    } else {
                        cursorSeq = jumpTo
                        jumpedPastSegment = true
                    }
                    break
                }
                collectedDesc += envelope
                raw.unknownFieldsByFactId[envelope.fact.id]?.let { unknowns[envelope.fact.id] = it }
                lowestConsumedSeq = seq
                if (collectedDesc.size >= limit) break
            }
            if (reachedLowerBound || exhaustedStore) break
            if (jumpedPastSegment) continue
            if (!raw.hasMoreStored) {
                exhaustedStore = true
                break
            }
            if (lowestConsumedSeq >= cursorSeq) break
            cursorSeq = lowestConsumedSeq
        }

        val envelopes = collectedDesc.asReversed()
        val hasOlderEligible =
            if (reachedLowerBound || exhaustedStore) {
                false
            } else {
                val probeStart = envelopes.firstOrNull()?.fact?.seq ?: cursorSeq
                hasEligibleFactBefore(
                    scope = scope,
                    segments = segments,
                    initialBeforeSeq = probeStart,
                    oldestMinSeq = oldestMinSeq,
                    shouldSkipSegment = shouldSkipSegment,
                )
            }
        return BoundedEligibleOlderResult(
            envelopes = envelopes,
            hasOlderEligible = hasOlderEligible,
            unknownFieldsByFactId = unknowns,
        )
    }

    private suspend fun hasEligibleFactBefore(
        scope: SyncScopeKey,
        segments: List<CanonicalMessageV2Segment>,
        initialBeforeSeq: Long,
        oldestMinSeq: Long?,
        shouldSkipSegment: (CanonicalMessageV2Segment) -> Boolean,
    ): Boolean {
        var cursorSeq = initialBeforeSeq
        while (true) {
            if (oldestMinSeq != null && cursorSeq <= oldestMinSeq) return false
            val probe = store.readFactsBefore(scope, beforeSeq = cursorSeq, limit = 1)
            val row = probe.envelopes.firstOrNull() ?: return false
            val seq = row.fact.seq
            if (oldestMinSeq != null && seq < oldestMinSeq) return false
            val member = CanonicalMessageV2SegmentAlgebra.memberOf(segments, seq)
            if (member != null && shouldSkipSegment(member)) {
                val jumpTo = minOf(seq, member.effectiveLowSeq)
                if (jumpTo >= cursorSeq) return false
                cursorSeq = jumpTo
                continue
            }
            return true
        }
    }

    private suspend fun readWindowOlderPage(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        allowedKinds: Set<String>,
        replyParent: ThreadDurableReplyParent?,
        beforeSeq: Long,
        limit: Int,
    ): ThreadDurableOlderPage? {
        val scope = resolveScope(partition, scopeId, allowedKinds) ?: return null
        return try {
            val cursor = store.readCursor(scope) ?: return null
            if (!cursorMatchesReplyParent(scope, cursor, replyParent)) return null
            val segments = store.readSegments(scope)
            val head = CanonicalMessageV2SegmentAlgebra.head(segments)
            // Keep non-head islands invisible to the legacy readBefore wrapper until PR 2d-2 switches callers to readTimeline.
            val shouldSkip: (CanonicalMessageV2Segment) -> Boolean = { segment -> segment != head }
            val oldestWindowSeq = cursor.oldestLoadedSeq
            val result = readEligibleOlderSkippingSegments(
                scope = scope,
                segments = segments,
                initialBeforeSeq = beforeSeq,
                limit = limit,
                oldestMinSeq = oldestWindowSeq,
                shouldSkipSegment = shouldSkip,
            )
            // Island-only scope (head == null, !hasLoadedOldest) is invisible unless at least one loose fact exists in scope.
            if (head == null && result.envelopes.isEmpty() && !cursor.hasLoadedOldest) {
                val hasAnyLooseFact = hasEligibleFactBefore(
                    scope = scope,
                    segments = segments,
                    initialBeforeSeq = Long.MAX_VALUE,
                    oldestMinSeq = null,
                    shouldSkipSegment = { true },
                )
                if (!hasAnyLooseFact) return null
            }
            ThreadDurableOlderPage(
                scope = scope,
                envelopes = result.envelopes,
                hasOlderStored = result.hasOlderEligible,
                hasLoadedOldest = cursor.hasLoadedOldest,
            )
        } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
            logger(
                "durable_last_known outcome=poisoned server=${partition.serverId} scope=$scopeId " +
                    "scopeKind=${scope.scopeKind} ${poisoned.diagnosticsLabel()}",
            )
            null
        }
    }

    private suspend fun readScopeTimeline(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        allowedKinds: Set<String>,
        replyParent: ThreadDurableReplyParent?,
        anchor: ThreadDurableTimelineAnchor,
        touchUserAccess: Boolean,
    ): ThreadDurableTimelinePage? {
        val scope = resolveScope(partition, scopeId, allowedKinds) ?: return null
        return try {
            val cursor = store.readCursor(scope) ?: return null
            if (!cursorMatchesReplyParent(scope, cursor, replyParent)) return null
            val segments = store.readSegments(scope)
            val page = when (anchor) {
                is ThreadDurableTimelineAnchor.Newest -> readNewestTimeline(scope, cursor, segments, anchor.limit)
                is ThreadDurableTimelineAnchor.Before -> readBeforeTimeline(scope, cursor, segments, anchor)
                is ThreadDurableTimelineAnchor.After -> readAfterTimeline(scope, cursor, segments, anchor)
                is ThreadDurableTimelineAnchor.AroundMessageId ->
                    readAroundMessageIdTimeline(partition, scope, cursor, segments, anchor)
            } ?: return null
            if (touchUserAccess) {
                touchUserAccessBestEffort(scope)
            }
            page
        } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
            logger(
                "durable_last_known outcome=poisoned server=${partition.serverId} scope=$scopeId " +
                    "scopeKind=${scope.scopeKind} ${poisoned.diagnosticsLabel()}",
            )
            null
        }
    }

    private suspend fun readNewestTimeline(
        scope: SyncScopeKey,
        cursor: PersistedCursor,
        segments: List<CanonicalMessageV2Segment>,
        limit: Int,
    ): ThreadDurableTimelinePage? {
        val head = CanonicalMessageV2SegmentAlgebra.head(segments)
        // Phase 2c / Design §5: a non-head context island is never the head, even when its seqs sit above head.
        // Skip context islands strictly above head (or all islands when head == null) while allowing lower islands
        // below head to be reached with derived gaps.
        val shouldSkip: (CanonicalMessageV2Segment) -> Boolean =
            if (head != null) {
                { segment -> segment != head && !segment.source.rootsHead && segment.effectiveLowSeq > head.highSeq }
            } else {
                { true }
            }
        val result = readEligibleOlderSkippingSegments(
            scope = scope,
            segments = segments,
            initialBeforeSeq = Long.MAX_VALUE,
            limit = limit,
            oldestMinSeq = null,
            shouldSkipSegment = shouldSkip,
        )
        val envelopes = result.envelopes
        if (head == null) {
            val hasEmptyBaseline = segments.any { it.isEmptyBaseline } || (cursor.hasLoadedOldest && envelopes.isEmpty() && !store.hasFactBefore(scope, Long.MAX_VALUE))
            if (envelopes.isEmpty() && !hasEmptyBaseline) return null
        } else if (envelopes.isEmpty() && !head.isEmptyBaseline) {
            // Re-verify cursor if a non-empty head yielded 0 facts (guards against torn read during concurrent clearScope).
            if (store.readCursor(scope) == null) return null
        }
        val hasOlderStored = result.hasOlderEligible
        val seqs = envelopes.map { it.fact.seq }
        val insideProvenSegment =
            if (head == null) {
                false
            } else if (seqs.isEmpty()) {
                head.isEmptyBaseline
            } else {
                CanonicalMessageV2SegmentAlgebra.memberOf(segments, seqs.last()) == head
            }
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = envelopes,
            gaps = CanonicalMessageV2SegmentAlgebra.deriveGaps(seqs, segments),
            cursor = cursor,
            segments = segments,
            hasOlderStored = hasOlderStored,
            hasNewerStored = false,
            reachesOldest =
                if (envelopes.isEmpty()) {
                    cursor.hasLoadedOldest || segments.any { it.isEmptyBaseline }
                } else {
                    CanonicalMessageV2SegmentAlgebra.reachesOldestAt(seqs.first(), segments, hasOlderStored)
                },
            reachesHead =
                if (envelopes.isEmpty()) {
                    segments.any { it.isEmptyBaseline && it.headAtVerify } || (cursor.hasLoadedOldest && cursor.hasLoadedNewest)
                } else {
                    CanonicalMessageV2SegmentAlgebra.reachesHeadAt(seqs.last(), segments, hasNewerStored = false)
                },
            insideProvenSegment = insideProvenSegment,
            unknownFieldsByFactId = result.unknownFieldsByFactId,
        )
    }

    private suspend fun readBeforeTimeline(
        scope: SyncScopeKey,
        cursor: PersistedCursor,
        segments: List<CanonicalMessageV2Segment>,
        anchor: ThreadDurableTimelineAnchor.Before,
    ): ThreadDurableTimelinePage {
        val provenSegment = CanonicalMessageV2SegmentAlgebra.provenSegmentBefore(segments, anchor.beforeSeq)
        val hasNewerStored =
            if (anchor.beforeSeq == Long.MIN_VALUE) {
                false
            } else {
                store.hasFactAfter(scope, anchor.beforeSeq - 1L)
            }
        if (!anchor.crossGaps) {
            if (provenSegment == null) {
                val hasOlderStored = store.hasFactBefore(scope, anchor.beforeSeq)
                return ThreadDurableTimelinePage(
                    scope = scope,
                    envelopes = emptyList(),
                    gaps = emptyList(),
                    cursor = cursor,
                    segments = segments,
                    hasOlderStored = hasOlderStored,
                    hasNewerStored = hasNewerStored,
                    reachesOldest = false,
                    reachesHead = false,
                    insideProvenSegment = false,
                )
            }
            val raw = store.readFactsBefore(scope, beforeSeq = anchor.beforeSeq, limit = anchor.limit)
            val inSegment = raw.envelopes.filter { it.fact.seq >= provenSegment.effectiveLowSeq }
            val keptIds = inSegment.mapTo(HashSet()) { it.fact.id }
            val hasOlderStored = raw.hasMoreStored || inSegment.size < raw.envelopes.size
            val reachesOldest =
                if (inSegment.isEmpty()) {
                    provenSegment.reachesOldest && !hasOlderStored
                } else {
                    CanonicalMessageV2SegmentAlgebra.reachesOldestAt(inSegment.first().fact.seq, segments, hasOlderStored)
                }
            return ThreadDurableTimelinePage(
                scope = scope,
                envelopes = inSegment,
                gaps = emptyList(),
                cursor = cursor,
                segments = segments,
                hasOlderStored = hasOlderStored,
                hasNewerStored = hasNewerStored,
                reachesOldest = reachesOldest,
                reachesHead = false,
                insideProvenSegment = true,
                unknownFieldsByFactId = raw.unknownFieldsByFactId.filterKeys { it in keptIds },
            )
        }
        val raw = store.readFactsBefore(scope, beforeSeq = anchor.beforeSeq, limit = anchor.limit)
        val seqs = raw.envelopes.map { it.fact.seq }
        // Include anchor.beforeSeq as upper boundary so a jump from upper segment into a lower island emits the gap between them.
        val gaps =
            if (seqs.isEmpty()) {
                emptyList()
            } else {
                CanonicalMessageV2SegmentAlgebra.deriveGaps(seqs + anchor.beforeSeq, segments)
            }
        val reachesOldest =
            if (seqs.isEmpty()) {
                provenSegment?.reachesOldest == true ||
                    CanonicalMessageV2SegmentAlgebra.memberOf(segments, anchor.beforeSeq)?.reachesOldest == true
            } else {
                CanonicalMessageV2SegmentAlgebra.reachesOldestAt(seqs.first(), segments, raw.hasMoreStored)
            }
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = raw.envelopes,
            gaps = gaps,
            cursor = cursor,
            segments = segments,
            hasOlderStored = raw.hasMoreStored,
            hasNewerStored = hasNewerStored,
            reachesOldest = reachesOldest,
            reachesHead = false,
            insideProvenSegment = provenSegment != null,
            unknownFieldsByFactId = raw.unknownFieldsByFactId,
        )
    }

    private suspend fun readAfterTimeline(
        scope: SyncScopeKey,
        cursor: PersistedCursor,
        segments: List<CanonicalMessageV2Segment>,
        anchor: ThreadDurableTimelineAnchor.After,
    ): ThreadDurableTimelinePage {
        val provenSegment = CanonicalMessageV2SegmentAlgebra.provenSegmentAfter(segments, anchor.afterSeq)
        val hasOlderStored =
            if (anchor.afterSeq == Long.MAX_VALUE) {
                false
            } else {
                store.hasFactBefore(scope, anchor.afterSeq + 1L)
            }
        if (!anchor.crossGaps) {
            if (provenSegment == null) {
                val hasNewerStored = store.hasFactAfter(scope, anchor.afterSeq)
                return ThreadDurableTimelinePage(
                    scope = scope,
                    envelopes = emptyList(),
                    gaps = emptyList(),
                    cursor = cursor,
                    segments = segments,
                    hasOlderStored = hasOlderStored,
                    hasNewerStored = hasNewerStored,
                    reachesOldest = false,
                    reachesHead = false,
                    insideProvenSegment = false,
                )
            }
            val raw = store.readFactsAfter(scope, afterSeq = anchor.afterSeq, limit = anchor.limit)
            val inSegment = raw.envelopes.filter { it.fact.seq <= provenSegment.highSeq }
            val keptIds = inSegment.mapTo(HashSet()) { it.fact.id }
            val hasNewerStored = raw.hasMoreStored || inSegment.size < raw.envelopes.size
            val reachesHead =
                if (inSegment.isEmpty()) {
                    provenSegment.headAtVerify && !hasNewerStored
                } else {
                    CanonicalMessageV2SegmentAlgebra.reachesHeadAt(inSegment.last().fact.seq, segments, hasNewerStored)
                }
            return ThreadDurableTimelinePage(
                scope = scope,
                envelopes = inSegment,
                gaps = emptyList(),
                cursor = cursor,
                segments = segments,
                hasOlderStored = hasOlderStored,
                hasNewerStored = hasNewerStored,
                reachesOldest = false,
                reachesHead = reachesHead,
                insideProvenSegment = true,
                unknownFieldsByFactId = raw.unknownFieldsByFactId.filterKeys { it in keptIds },
            )
        }
        val raw = store.readFactsAfter(scope, afterSeq = anchor.afterSeq, limit = anchor.limit)
        val seqs = raw.envelopes.map { it.fact.seq }
        val gaps =
            if (seqs.isEmpty()) {
                emptyList()
            } else {
                CanonicalMessageV2SegmentAlgebra.deriveGaps(listOf(anchor.afterSeq) + seqs, segments)
            }
        val reachesHead =
            if (seqs.isEmpty()) {
                provenSegment?.headAtVerify == true ||
                    CanonicalMessageV2SegmentAlgebra.memberOf(segments, anchor.afterSeq)?.headAtVerify == true
            } else {
                CanonicalMessageV2SegmentAlgebra.reachesHeadAt(seqs.last(), segments, raw.hasMoreStored)
            }
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = raw.envelopes,
            gaps = gaps,
            cursor = cursor,
            segments = segments,
            hasOlderStored = hasOlderStored,
            hasNewerStored = raw.hasMoreStored,
            reachesOldest = false,
            reachesHead = reachesHead,
            insideProvenSegment = provenSegment != null,
            unknownFieldsByFactId = raw.unknownFieldsByFactId,
        )
    }

    private suspend fun readAroundMessageIdTimeline(
        partition: DurablePartitionKey,
        scope: SyncScopeKey,
        cursor: PersistedCursor,
        segments: List<CanonicalMessageV2Segment>,
        anchor: ThreadDurableTimelineAnchor.AroundMessageId,
    ): ThreadDurableTimelinePage? {
        val trimmedId = anchor.messageId.trim()
        if (trimmedId.isEmpty() || trimmedId != anchor.messageId) return null
        val location = store.locateFact(partition, trimmedId)
            .firstOrNull { it.scopeKind == scope.scopeKind && it.scopeId == scope.scopeId }
            ?: return null
        val anchorSeq = location.seq
        val beforeAndAnchor =
            if (anchorSeq == Long.MAX_VALUE) {
                val anchorPage = store.readFactsAfter(scope, afterSeq = Long.MAX_VALUE - 1L, limit = 1)
                if (anchorPage.envelopes.none { it.fact.id == trimmedId }) return null
                if (anchor.beforeCount > 0) {
                    val older = store.readFactsBefore(scope, beforeSeq = Long.MAX_VALUE, limit = anchor.beforeCount)
                    CanonicalMessageV2BoundedFactsPage(
                        envelopes = older.envelopes + anchorPage.envelopes,
                        hasMoreStored = older.hasMoreStored,
                        unknownFieldsByFactId = older.unknownFieldsByFactId + anchorPage.unknownFieldsByFactId,
                    )
                } else {
                    CanonicalMessageV2BoundedFactsPage(
                        envelopes = anchorPage.envelopes,
                        hasMoreStored = store.hasFactBefore(scope, Long.MAX_VALUE),
                        unknownFieldsByFactId = anchorPage.unknownFieldsByFactId,
                    )
                }
            } else {
                val page = store.readFactsBefore(scope, beforeSeq = anchorSeq + 1L, limit = anchor.beforeCount + 1)
                if (page.envelopes.none { it.fact.id == trimmedId }) return null
                page
            }
        val after =
            if (anchorSeq == Long.MAX_VALUE) {
                CanonicalMessageV2BoundedFactsPage(envelopes = emptyList(), hasMoreStored = false)
            } else if (anchor.afterCount > 0) {
                store.readFactsAfter(scope, afterSeq = anchorSeq, limit = anchor.afterCount)
            } else {
                CanonicalMessageV2BoundedFactsPage(
                    envelopes = emptyList(),
                    hasMoreStored = store.hasFactAfter(scope, anchorSeq),
                )
            }
        val envelopes = beforeAndAnchor.envelopes + after.envelopes
        val seqs = envelopes.map { it.fact.seq }
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = envelopes,
            gaps = CanonicalMessageV2SegmentAlgebra.deriveGaps(seqs, segments),
            cursor = cursor,
            segments = segments,
            hasOlderStored = beforeAndAnchor.hasMoreStored,
            hasNewerStored = after.hasMoreStored,
            reachesOldest = CanonicalMessageV2SegmentAlgebra.reachesOldestAt(seqs.first(), segments, beforeAndAnchor.hasMoreStored),
            reachesHead = CanonicalMessageV2SegmentAlgebra.reachesHeadAt(seqs.last(), segments, after.hasMoreStored),
            insideProvenSegment = CanonicalMessageV2SegmentAlgebra.memberOf(segments, anchorSeq) != null,
            unknownFieldsByFactId = beforeAndAnchor.unknownFieldsByFactId + after.unknownFieldsByFactId,
        )
    }

    private suspend fun readScope(
        partition: DurablePartitionKey,
        scopeId: SyncScopeId,
        allowedKinds: Set<String>,
    ): ThreadDurableLastKnownFrame? {
        val scope = resolveScope(partition, scopeId, allowedKinds) ?: return null
        val (restored, segments) = try {
            store.restore(scope) to store.readSegments(scope)
        } catch (poisoned: CanonicalMessageV2ScopePoisoned) {
            logger(
                "durable_last_known outcome=poisoned server=${partition.serverId} scope=$scopeId " +
                    "scopeKind=${scope.scopeKind} ${poisoned.diagnosticsLabel()}",
            )
            return null
        }
        val cursor = restored.cursor ?: return null
        // Message storage Phase 2a: older pages now stay on disk as islands below the head. Until the read path
        // shows gaps (Phase 2d PR 2 switches SharedThreadDataSource to readTimeline) only the head range and the
        // facts in no range are read via this legacy entry, so an island is not rendered next to the head.
        val head = CanonicalMessageV2SegmentAlgebra.head(segments)
        val envelopes = restored.envelopes.filter { envelope ->
            val member = CanonicalMessageV2SegmentAlgebra.memberOf(segments, envelope.fact.seq)
            member == null || member == head
        }
        // Phase 2c: islands only (a link or overview write into a never-opened channel) is no window: not an empty
        // channel and not a screen, so the offline result stays what it was until readTimeline is wired.
        if (head == null && envelopes.isEmpty() && !cursor.hasLoadedOldest) return null
        val readIds = envelopes.mapTo(HashSet()) { it.fact.id }
        return ThreadDurableLastKnownFrame(
            scope = scope,
            envelopes = envelopes,
            cursor = cursor,
            unknownFieldsByFactId = restored.unknownFieldsByFactId.filterKeys { it in readIds },
        )
    }
}
