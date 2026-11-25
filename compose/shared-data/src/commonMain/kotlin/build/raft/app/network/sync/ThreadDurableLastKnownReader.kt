package build.raft.app.network.sync

import build.raft.app.RaftCoreInternalApi

import build.raft.app.canonical.message.MessageAggregateField
import kotlinx.coroutines.CancellationException

/** One restored V2 scope (top-level or, task #168 step 3, reply thread), ready to be projected as the thread screen's offline first frame. */
@RaftCoreInternalApi data class ThreadDurableLastKnownFrame(
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
 * - [Newest]: newest [limit] contiguous facts for an unanchored open. When a head segment exists, reads
 *   strictly inside that head segment up to `head.highSeq` (never splicing unproven loose realtime facts
 *   above head or disconnected lower islands below head, so normal opens never emit mid-timeline gaps;
 *   online refresh fetches and absorbs newer messages at the bottom, and explicit offline [Before] still
 *   crosses into lower stored islands). When no head segment exists, skips non-head islands and returns
 *   only the newest consecutive gap-free run of loose facts (or empty baseline).
 * - [Before]: newest [limit] facts strictly below [beforeSeq]. When [crossGaps] is `false` (online local
 *   paging), stays strictly inside the contiguous segment covering `beforeSeq - 1`; when `true` (offline),
 *   crosses into lower stored islands and derives boundary/internal gaps.
 * - [After]: symmetric mirror strictly above [afterSeq].
 * - [AroundMessageId]: gap-free window centered on [messageId] (`beforeCount` older + anchor +
 *   `afterCount` newer), resolving via `selectFactLocationById` so island-only scopes open locally
 *   without a head segment. Mirrors [Newest]: when the anchor sits inside a proven contiguous segment,
 *   reads strictly inside that segment; when the anchor is a loose fact, returns only the anchor and
 *   any strictly consecutive loose facts (`prevSeq == lowestSeq - 1`, `nextSeq == highestSeq + 1`),
 *   guaranteeing `gaps = emptyList()` on Frame 1 while preserving accurate `hasOlderStored` /
 *   `hasNewerStored` for explicit offline [Before] / [After] (`crossGaps = true`) island crossing.
 */
@RaftCoreInternalApi sealed interface ThreadDurableTimelineAnchor {
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
@RaftCoreInternalApi data class ThreadDurableTimelinePage(
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
@RaftCoreInternalApi interface ThreadDurableLastKnownReader {
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
     * Whether this reader implements the Phase 2d bounded timeline API ([readTimeline] /
     * [readReplyThreadTimeline]). Callers skip legacy full-scope fallbacks ([read] / [readBefore])
     * when `true`, so a deliberate `null` timeline answer (island-only scope, poisoned scope, or an
     * unproven empty head) never falls back to an unguarded full restore. Default `false` for
     * minimal test fakes that only implement [read].
     */
    val supportsTimelineReads: Boolean
        get() = false

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
@RaftCoreInternalApi data class ThreadDurableOlderPage(
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
@RaftCoreInternalApi fun ThreadDurableLastKnownFrame.windowEnvelopes(): List<CanonicalMessageEnvelopeV2> {
    val oldest = cursor.oldestLoadedSeq ?: return envelopes
    return envelopes.filter { it.fact.seq >= oldest }
}

/** Task v2: the scope kind of a task's own conversation (a flat, root-less window like a channel's). */
const val CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND: String = "task"

/**
 * The kinds a reply thread's parent may have (and a `thread` cursor stores): the four producer
 * top-level kinds. A task conversation is never a reply parent.
 */
@RaftCoreInternalApi val CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS: Set<String> = setOf("channel", "private", "dm", "joint")

/**
 * Top-level (flat window) scope kinds the V2 writer persists: the reply-parent kinds plus a task v2
 * conversation. Reply threads are the separate `thread` kind.
 */
@RaftCoreInternalApi val CANONICAL_MESSAGE_V2_TOP_LEVEL_SCOPE_KINDS: Set<String> =
    CANONICAL_MESSAGE_V2_REPLY_PARENT_SCOPE_KINDS + CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND

/** The one reply-thread scope kind (task #168 step 3); its cursor carries the parent scope and root. */
@RaftCoreInternalApi val CANONICAL_MESSAGE_V2_REPLY_THREAD_SCOPE_KINDS: Set<String> = setOf("thread")

@RaftCoreInternalApi class CanonicalMessageV2DurableLastKnownReader(
    private val store: CanonicalMessageV2Store,
    private val logger: (String) -> Unit = {},
    override val replyWindowCache: RaftThreadReplyWindowCache? = null,
) : ThreadDurableLastKnownReader {
    override val supportsTimelineReads: Boolean = true

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
            // Non-decoding pre-skip: avoid decoding poisoned payloads inside skipped segments (e.g. a context island above head).
            val nextSeq = store.highestFactSeqBefore(scope, beforeSeq = cursorSeq)
            if (nextSeq == null) {
                exhaustedStore = true
                break
            }
            if (oldestMinSeq != null && nextSeq < oldestMinSeq) {
                reachedLowerBound = true
                break
            }
            val nextMember = CanonicalMessageV2SegmentAlgebra.memberOf(segments, nextSeq)
            if (nextMember != null && shouldSkipSegment(nextMember)) {
                val jumpTo = minOf(nextSeq, nextMember.effectiveLowSeq)
                if (jumpTo >= cursorSeq) {
                    exhaustedStore = true
                    break
                }
                cursorSeq = jumpTo
                continue
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
            val seq = store.highestFactSeqBefore(scope, beforeSeq = cursorSeq) ?: return false
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
        return if (head != null) {
            readNewestHeadSegmentTimeline(scope, cursor, segments, head, limit)
        } else {
            readNewestLooseOnlyTimeline(scope, cursor, segments, limit)
        }
    }

    /**
     * Normal unanchored open when a head segment exists: read strictly inside [head] up to
     * `head.highSeq` so unproven loose realtime facts above head and disconnected lower islands
     * below head are never spliced into Frame 1 with mid-timeline gaps. Online refresh loads
     * newer messages at the bottom and absorbs loose facts into [head]; explicit offline
     * [ThreadDurableTimelineAnchor.Before] (`crossGaps = true`) still crosses into lower islands
     * because [ThreadDurableTimelinePage.hasOlderStored] reflects facts below [head].
     */
    private suspend fun readNewestHeadSegmentTimeline(
        scope: SyncScopeKey,
        cursor: PersistedCursor,
        segments: List<CanonicalMessageV2Segment>,
        head: CanonicalMessageV2Segment,
        limit: Int,
    ): ThreadDurableTimelinePage? {
        if (head.isEmptyBaseline) {
            return ThreadDurableTimelinePage(
                scope = scope,
                envelopes = emptyList(),
                gaps = emptyList(),
                cursor = cursor,
                segments = segments,
                hasOlderStored = false,
                hasNewerStored = false,
                reachesOldest = true,
                reachesHead = head.headAtVerify,
                insideProvenSegment = true,
                unknownFieldsByFactId = emptyMap(),
            )
        }
        val raw =
            if (head.highSeq == Long.MAX_VALUE) {
                val atMax = store.readFactsAfter(scope, afterSeq = Long.MAX_VALUE - 1L, limit = 1)
                if (atMax.envelopes.size >= limit) {
                    CanonicalMessageV2BoundedFactsPage(
                        envelopes = atMax.envelopes.take(limit),
                        hasMoreStored = atMax.hasMoreStored || store.hasFactBefore(scope, Long.MAX_VALUE),
                        unknownFieldsByFactId = atMax.unknownFieldsByFactId,
                    )
                } else {
                    val older = store.readFactsBefore(scope, beforeSeq = Long.MAX_VALUE, limit = limit - atMax.envelopes.size)
                    CanonicalMessageV2BoundedFactsPage(
                        envelopes = older.envelopes + atMax.envelopes,
                        hasMoreStored = older.hasMoreStored,
                        unknownFieldsByFactId = older.unknownFieldsByFactId + atMax.unknownFieldsByFactId,
                    )
                }
            } else {
                store.readFactsBefore(scope, beforeSeq = head.highSeq + 1L, limit = limit)
            }
        val envelopes = raw.envelopes.filter { it.fact.seq >= head.effectiveLowSeq }
        val keptIds = envelopes.mapTo(HashSet()) { it.fact.id }
        val hasOlderStored = raw.hasMoreStored || envelopes.size < raw.envelopes.size
        if (envelopes.isEmpty()) {
            // Re-verify cursor if a non-empty head yielded 0 facts (guards against torn read during concurrent clearScope).
            if (store.readCursor(scope) == null) return null
            // Never report an empty channel stand-in when a non-empty-baseline head has older facts or hasn't proven oldest.
            if (!head.reachesOldest || hasOlderStored) return null
        }
        val seqs = envelopes.map { it.fact.seq }
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = envelopes,
            gaps = emptyList(),
            cursor = cursor,
            segments = segments,
            hasOlderStored = hasOlderStored,
            hasNewerStored = false,
            reachesOldest =
                if (seqs.isEmpty()) {
                    head.reachesOldest && !hasOlderStored
                } else {
                    CanonicalMessageV2SegmentAlgebra.reachesOldestAt(seqs.first(), segments, hasOlderStored)
                },
            reachesHead =
                if (seqs.isEmpty()) {
                    head.headAtVerify
                } else {
                    CanonicalMessageV2SegmentAlgebra.reachesHeadAt(seqs.last(), segments, hasNewerStored = false)
                },
            insideProvenSegment = true,
            unknownFieldsByFactId = raw.unknownFieldsByFactId.filterKeys { it in keptIds },
        )
    }

    /**
     * Normal unanchored open when no head segment exists (`head == null`): skip all non-head
     * islands and return only the newest consecutive gap-free run of loose facts (`prevSeq ==
     * lowestSeq - 1`), guaranteeing zero mid-timeline gaps on Frame 1 while preserving
     * [ThreadDurableTimelinePage.hasOlderStored] for offline [ThreadDurableTimelineAnchor.Before].
     */
    private suspend fun readNewestLooseOnlyTimeline(
        scope: SyncScopeKey,
        cursor: PersistedCursor,
        segments: List<CanonicalMessageV2Segment>,
        limit: Int,
    ): ThreadDurableTimelinePage? {
        val collectedDesc = mutableListOf<CanonicalMessageEnvelopeV2>()
        val unknowns = mutableMapOf<String, Set<MessageAggregateField>>()
        val maxLoose =
            if (CanonicalMessageV2SegmentAlgebra.memberOf(segments, Long.MAX_VALUE) == null) {
                store.readFactsAfter(scope, afterSeq = Long.MAX_VALUE - 1L, limit = 1)
            } else {
                null
            }
        val firstAtMax = maxLoose?.envelopes?.singleOrNull()
        if (maxLoose != null && firstAtMax != null) {
            collectedDesc += firstAtMax
            maxLoose.unknownFieldsByFactId[firstAtMax.fact.id]?.let { unknowns[firstAtMax.fact.id] = it }
        } else {
            val newestLoose = readEligibleOlderSkippingSegments(
                scope = scope,
                segments = segments,
                initialBeforeSeq = Long.MAX_VALUE,
                limit = 1,
                oldestMinSeq = null,
                shouldSkipSegment = { true },
            )
            val newestEnvelope = newestLoose.envelopes.singleOrNull()
            if (newestEnvelope != null) {
                collectedDesc += newestEnvelope
                newestLoose.unknownFieldsByFactId[newestEnvelope.fact.id]?.let { unknowns[newestEnvelope.fact.id] = it }
            }
        }
        if (collectedDesc.isEmpty()) {
            val hasEmptyBaseline =
                segments.any { it.isEmptyBaseline } ||
                    (cursor.hasLoadedOldest && !store.hasFactBefore(scope, Long.MAX_VALUE) && !store.hasFactAfter(scope, Long.MAX_VALUE - 1L))
            if (!hasEmptyBaseline) return null
            return ThreadDurableTimelinePage(
                scope = scope,
                envelopes = emptyList(),
                gaps = emptyList(),
                cursor = cursor,
                segments = segments,
                hasOlderStored = false,
                hasNewerStored = false,
                reachesOldest = true,
                reachesHead = segments.any { it.isEmptyBaseline && it.headAtVerify } || (cursor.hasLoadedOldest && cursor.hasLoadedNewest),
                insideProvenSegment = false,
                unknownFieldsByFactId = emptyMap(),
            )
        }
        var lowestSeq = collectedDesc.last().fact.seq
        while (collectedDesc.size < limit && lowestSeq > Long.MIN_VALUE) {
            val prevSeq = store.highestFactSeqBefore(scope, beforeSeq = lowestSeq) ?: break
            if (prevSeq != lowestSeq - 1L) break
            if (CanonicalMessageV2SegmentAlgebra.memberOf(segments, prevSeq) != null) break
            val prevPage = store.readFactsBefore(scope, beforeSeq = lowestSeq, limit = 1)
            val prevEnvelope = prevPage.envelopes.singleOrNull() ?: break
            if (prevEnvelope.fact.seq != prevSeq) break
            collectedDesc += prevEnvelope
            prevPage.unknownFieldsByFactId[prevEnvelope.fact.id]?.let { unknowns[prevEnvelope.fact.id] = it }
            lowestSeq = prevSeq
        }
        val envelopes = collectedDesc.asReversed()
        val hasOlderStored = store.hasFactBefore(scope, envelopes.first().fact.seq)
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = envelopes,
            gaps = emptyList(),
            cursor = cursor,
            segments = segments,
            hasOlderStored = hasOlderStored,
            hasNewerStored = false,
            reachesOldest = false,
            reachesHead = false,
            insideProvenSegment = false,
            unknownFieldsByFactId = unknowns,
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
                store.hasFactAfter(scope, Long.MIN_VALUE) || store.hasFactBefore(scope, Long.MIN_VALUE + 1L)
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
        // #proj-mobile:#386 P0-1: offline gap-crossing only bridges rows belonging to proven segments,
        // never splicing individual unsegmented loose realtime facts row-by-row into N stacked gap rows.
        val segmented = readSegmentedFactsBefore(scope, segments, beforeSeq = anchor.beforeSeq, limit = anchor.limit)
        val seqs = segmented.envelopes.map { it.fact.seq }
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
                CanonicalMessageV2SegmentAlgebra.reachesOldestAt(seqs.first(), segments, segmented.hasMoreEligible)
            }
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = segmented.envelopes,
            gaps = gaps,
            cursor = cursor,
            segments = segments,
            hasOlderStored = segmented.hasMoreEligible,
            hasNewerStored = hasNewerStored,
            reachesOldest = reachesOldest,
            reachesHead = false,
            insideProvenSegment = provenSegment != null,
            unknownFieldsByFactId = segmented.unknownFieldsByFactId,
        )
    }

    private data class BoundedSegmentedFactsResult(
        val envelopes: List<CanonicalMessageEnvelopeV2>,
        val hasMoreEligible: Boolean,
        val unknownFieldsByFactId: Map<String, Set<MessageAggregateField>>,
    )

    private suspend fun readSegmentedFactsBefore(
        scope: SyncScopeKey,
        segments: List<CanonicalMessageV2Segment>,
        beforeSeq: Long,
        limit: Int,
    ): BoundedSegmentedFactsResult {
        if (beforeSeq == Long.MIN_VALUE || segments.isEmpty()) {
            return BoundedSegmentedFactsResult(emptyList(), hasMoreEligible = false, unknownFieldsByFactId = emptyMap())
        }
        val collectedDesc = mutableListOf<CanonicalMessageEnvelopeV2>()
        val unknowns = mutableMapOf<String, Set<MessageAggregateField>>()
        var hasMoreEligible = false
        for (segment in segments.asReversed()) {
            if (segment.isEmptyBaseline || segment.effectiveLowSeq >= beforeSeq) continue
            val segUpperSeq = minOf(segment.highSeq, beforeSeq - 1L)
            val highestInSeg = store.highestFactSeqBefore(scope, beforeSeq = segUpperSeq + 1L)
            if (highestInSeg == null || highestInSeg < segment.effectiveLowSeq) continue
            if (collectedDesc.size >= limit) {
                hasMoreEligible = true
                break
            }
            val remaining = limit - collectedDesc.size
            val span =
                if (segUpperSeq - segment.effectiveLowSeq >= remaining.toLong()) {
                    remaining
                } else {
                    (segUpperSeq - segment.effectiveLowSeq + 1L).toInt()
                }
            val raw = store.readFactsBefore(scope, beforeSeq = segUpperSeq + 1L, limit = span)
            val inSeg =
                raw.envelopes
                    .filter { it.fact.seq in segment.effectiveLowSeq..segUpperSeq }
                    .takeLast(remaining)
            if (inSeg.isEmpty()) continue
            for (index in inSeg.lastIndex downTo 0) {
                val envelope = inSeg[index]
                collectedDesc += envelope
                raw.unknownFieldsByFactId[envelope.fact.id]?.let { unknowns[envelope.fact.id] = it }
            }
            if (collectedDesc.size >= limit) {
                val lowestTaken = inSeg.first().fact.seq
                if (
                    lowestTaken > segment.effectiveLowSeq &&
                    store.highestFactSeqBefore(scope, beforeSeq = lowestTaken)?.let { it >= segment.effectiveLowSeq } == true
                ) {
                    hasMoreEligible = true
                    break
                }
            }
        }
        return BoundedSegmentedFactsResult(
            envelopes = collectedDesc.asReversed(),
            hasMoreEligible = hasMoreEligible,
            unknownFieldsByFactId = unknowns,
        )
    }

    private suspend fun readSegmentedFactsAfter(
        scope: SyncScopeKey,
        segments: List<CanonicalMessageV2Segment>,
        afterSeq: Long,
        limit: Int,
    ): BoundedSegmentedFactsResult {
        if (afterSeq == Long.MAX_VALUE || segments.isEmpty()) {
            return BoundedSegmentedFactsResult(emptyList(), hasMoreEligible = false, unknownFieldsByFactId = emptyMap())
        }
        val collectedAsc = mutableListOf<CanonicalMessageEnvelopeV2>()
        val unknowns = mutableMapOf<String, Set<MessageAggregateField>>()
        var hasMoreEligible = false
        for (segment in segments) {
            if (segment.isEmptyBaseline || segment.highSeq <= afterSeq) continue
            val segLowerSeq = maxOf(segment.effectiveLowSeq, afterSeq + 1L)
            val lowestInSeg = store.lowestFactSeqAfter(scope, afterSeq = segLowerSeq - 1L)
            if (lowestInSeg == null || lowestInSeg > segment.highSeq) continue
            if (collectedAsc.size >= limit) {
                hasMoreEligible = true
                break
            }
            val remaining = limit - collectedAsc.size
            val span =
                if (segment.highSeq - segLowerSeq >= remaining.toLong()) {
                    remaining
                } else {
                    (segment.highSeq - segLowerSeq + 1L).toInt()
                }
            val raw = store.readFactsAfter(scope, afterSeq = segLowerSeq - 1L, limit = span)
            val inSeg =
                raw.envelopes
                    .filter { it.fact.seq in segLowerSeq..segment.highSeq }
                    .take(remaining)
            if (inSeg.isEmpty()) continue
            for (envelope in inSeg) {
                collectedAsc += envelope
                raw.unknownFieldsByFactId[envelope.fact.id]?.let { unknowns[envelope.fact.id] = it }
            }
            if (collectedAsc.size >= limit) {
                val highestTaken = inSeg.last().fact.seq
                if (
                    highestTaken < segment.highSeq &&
                    store.lowestFactSeqAfter(scope, afterSeq = highestTaken)?.let { it <= segment.highSeq } == true
                ) {
                    hasMoreEligible = true
                    break
                }
            }
        }
        return BoundedSegmentedFactsResult(
            envelopes = collectedAsc,
            hasMoreEligible = hasMoreEligible,
            unknownFieldsByFactId = unknowns,
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
                store.hasFactBefore(scope, Long.MAX_VALUE) || store.hasFactAfter(scope, Long.MAX_VALUE - 1L)
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
        // #proj-mobile:#386 P0-1: offline gap-crossing only bridges rows belonging to proven segments.
        val segmented = readSegmentedFactsAfter(scope, segments, afterSeq = anchor.afterSeq, limit = anchor.limit)
        val seqs = segmented.envelopes.map { it.fact.seq }
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
                CanonicalMessageV2SegmentAlgebra.reachesHeadAt(seqs.last(), segments, segmented.hasMoreEligible)
            }
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = segmented.envelopes,
            gaps = gaps,
            cursor = cursor,
            segments = segments,
            hasOlderStored = hasOlderStored,
            hasNewerStored = segmented.hasMoreEligible,
            reachesOldest = false,
            reachesHead = reachesHead,
            insideProvenSegment = provenSegment != null,
            unknownFieldsByFactId = segmented.unknownFieldsByFactId,
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
        val anchorSegment = CanonicalMessageV2SegmentAlgebra.memberOf(segments, anchorSeq)
        return if (anchorSegment != null) {
            readAroundMessageIdInSegmentTimeline(scope, cursor, segments, anchorSegment, trimmedId, anchorSeq, anchor)
        } else {
            readAroundMessageIdLooseOnlyTimeline(scope, cursor, segments, trimmedId, anchorSeq, anchor)
        }
    }

    /**
     * Anchored open when the anchor sits inside a proven contiguous segment: read strictly inside
     * `[anchorSegment.effectiveLowSeq..anchorSegment.highSeq]` so disconnected loose facts and
     * other islands are never spliced into Frame 1 with mid-timeline gaps, while preserving
     * accurate [ThreadDurableTimelinePage.hasOlderStored] / [ThreadDurableTimelinePage.hasNewerStored]
     * for explicit offline [ThreadDurableTimelineAnchor.Before] / [ThreadDurableTimelineAnchor.After]
     * (`crossGaps = true`) island crossing.
     */
    private suspend fun readAroundMessageIdInSegmentTimeline(
        scope: SyncScopeKey,
        cursor: PersistedCursor,
        segments: List<CanonicalMessageV2Segment>,
        anchorSegment: CanonicalMessageV2Segment,
        trimmedId: String,
        anchorSeq: Long,
        anchor: ThreadDurableTimelineAnchor.AroundMessageId,
    ): ThreadDurableTimelinePage? {
        val effectiveBeforeCount = if (anchorSeq <= anchorSegment.effectiveLowSeq) 0 else anchor.beforeCount
        val effectiveAfterCount = if (anchorSeq >= anchorSegment.highSeq) 0 else anchor.afterCount
        val beforeAndAnchor =
            if (anchorSeq == Long.MAX_VALUE) {
                val anchorPage = store.readFactsAfter(scope, afterSeq = Long.MAX_VALUE - 1L, limit = 1)
                if (anchorPage.envelopes.none { it.fact.id == trimmedId }) return null
                if (effectiveBeforeCount > 0) {
                    val older = store.readFactsBefore(scope, beforeSeq = Long.MAX_VALUE, limit = effectiveBeforeCount)
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
                val page = store.readFactsBefore(scope, beforeSeq = anchorSeq + 1L, limit = effectiveBeforeCount + 1)
                if (page.envelopes.none { it.fact.id == trimmedId }) return null
                page
            }
        val after =
            if (anchorSeq == Long.MAX_VALUE) {
                CanonicalMessageV2BoundedFactsPage(envelopes = emptyList(), hasMoreStored = false)
            } else if (effectiveAfterCount > 0) {
                store.readFactsAfter(scope, afterSeq = anchorSeq, limit = effectiveAfterCount)
            } else {
                CanonicalMessageV2BoundedFactsPage(
                    envelopes = emptyList(),
                    hasMoreStored = store.hasFactAfter(scope, anchorSeq),
                )
            }
        val keptBeforeAndAnchor = beforeAndAnchor.envelopes.filter { it.fact.seq >= anchorSegment.effectiveLowSeq }
        if (keptBeforeAndAnchor.none { it.fact.id == trimmedId }) return null
        val keptAfter = after.envelopes.filter { it.fact.seq <= anchorSegment.highSeq }
        val envelopes = keptBeforeAndAnchor + keptAfter
        val keptIds = envelopes.mapTo(HashSet()) { it.fact.id }
        val seqs = envelopes.map { it.fact.seq }
        val hasOlderStored = beforeAndAnchor.hasMoreStored || keptBeforeAndAnchor.size < beforeAndAnchor.envelopes.size
        val hasNewerStored = after.hasMoreStored || keptAfter.size < after.envelopes.size
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = envelopes,
            gaps = emptyList(),
            cursor = cursor,
            segments = segments,
            hasOlderStored = hasOlderStored,
            hasNewerStored = hasNewerStored,
            reachesOldest = CanonicalMessageV2SegmentAlgebra.reachesOldestAt(seqs.first(), segments, hasOlderStored),
            reachesHead = CanonicalMessageV2SegmentAlgebra.reachesHeadAt(seqs.last(), segments, hasNewerStored),
            insideProvenSegment = true,
            unknownFieldsByFactId = (beforeAndAnchor.unknownFieldsByFactId + after.unknownFieldsByFactId)
                .filterKeys { it in keptIds },
        )
    }

    /**
     * Anchored open when the anchor is a loose fact (`anchorSegment == null`): return only the
     * anchor and any strictly consecutive loose facts (`prevSeq == lowestSeq - 1`, `nextSeq ==
     * highestSeq + 1`, stopping at any segment member or seq gap), guaranteeing zero mid-timeline
     * gaps on Frame 1 while preserving accurate [ThreadDurableTimelinePage.hasOlderStored] /
     * [ThreadDurableTimelinePage.hasNewerStored] for offline island crossing.
     */
    private suspend fun readAroundMessageIdLooseOnlyTimeline(
        scope: SyncScopeKey,
        cursor: PersistedCursor,
        segments: List<CanonicalMessageV2Segment>,
        trimmedId: String,
        anchorSeq: Long,
        anchor: ThreadDurableTimelineAnchor.AroundMessageId,
    ): ThreadDurableTimelinePage? {
        val anchorPage =
            if (anchorSeq == Long.MAX_VALUE) {
                store.readFactsAfter(scope, afterSeq = Long.MAX_VALUE - 1L, limit = 1)
            } else {
                store.readFactsBefore(scope, beforeSeq = anchorSeq + 1L, limit = 1)
            }
        val anchorEnvelope = anchorPage.envelopes.firstOrNull { it.fact.id == trimmedId } ?: return null
        val unknowns = mutableMapOf<String, Set<MessageAggregateField>>()
        anchorPage.unknownFieldsByFactId[anchorEnvelope.fact.id]?.let { unknowns[anchorEnvelope.fact.id] = it }

        val olderDesc = mutableListOf<CanonicalMessageEnvelopeV2>()
        var lowestSeq = anchorEnvelope.fact.seq
        while (olderDesc.size < anchor.beforeCount && lowestSeq > Long.MIN_VALUE) {
            val prevSeq = store.highestFactSeqBefore(scope, beforeSeq = lowestSeq) ?: break
            if (prevSeq != lowestSeq - 1L) break
            if (CanonicalMessageV2SegmentAlgebra.memberOf(segments, prevSeq) != null) break
            val prevPage = store.readFactsBefore(scope, beforeSeq = lowestSeq, limit = 1)
            val prevEnvelope = prevPage.envelopes.singleOrNull() ?: break
            if (prevEnvelope.fact.seq != prevSeq) break
            olderDesc += prevEnvelope
            prevPage.unknownFieldsByFactId[prevEnvelope.fact.id]?.let { unknowns[prevEnvelope.fact.id] = it }
            lowestSeq = prevSeq
        }

        val newerAsc = mutableListOf<CanonicalMessageEnvelopeV2>()
        var highestSeq = anchorEnvelope.fact.seq
        while (newerAsc.size < anchor.afterCount && highestSeq < Long.MAX_VALUE) {
            val nextSeq = store.lowestFactSeqAfter(scope, afterSeq = highestSeq) ?: break
            if (nextSeq != highestSeq + 1L) break
            if (CanonicalMessageV2SegmentAlgebra.memberOf(segments, nextSeq) != null) break
            val nextPage = store.readFactsAfter(scope, afterSeq = highestSeq, limit = 1)
            val nextEnvelope = nextPage.envelopes.singleOrNull() ?: break
            if (nextEnvelope.fact.seq != nextSeq) break
            newerAsc += nextEnvelope
            nextPage.unknownFieldsByFactId[nextEnvelope.fact.id]?.let { unknowns[nextEnvelope.fact.id] = it }
            highestSeq = nextSeq
        }

        val envelopes = olderDesc.asReversed() + anchorEnvelope + newerAsc
        val hasOlderStored = store.hasFactBefore(scope, envelopes.first().fact.seq)
        val hasNewerStored = store.hasFactAfter(scope, envelopes.last().fact.seq)
        return ThreadDurableTimelinePage(
            scope = scope,
            envelopes = envelopes,
            gaps = emptyList(),
            cursor = cursor,
            segments = segments,
            hasOlderStored = hasOlderStored,
            hasNewerStored = hasNewerStored,
            reachesOldest = false,
            reachesHead = false,
            insideProvenSegment = false,
            unknownFieldsByFactId = unknowns,
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
