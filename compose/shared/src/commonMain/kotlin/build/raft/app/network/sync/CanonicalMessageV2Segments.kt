package build.raft.app.network.sync

/**
 * Message storage design 2026-10-27 (Phase 1): where a stored V2 range came from. [wire] is the
 * `messageSegmentEntity.source` value. [Legacy] marks the window the pre-segment writers
 * (applySnapshot / applyIncremental) still own; [Migrated] a window the v10 migration copied.
 */
enum class CanonicalMessageV2SegmentSource(val wire: String) {
    Latest("latest"),
    Older("older"),
    Newer("newer"),
    Context("context"),
    Live("live"),
    Migrated("migrated"),
    Legacy("legacy"),
    ;

    companion object {
        fun fromWire(wire: String): CanonicalMessageV2SegmentSource? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A claim that every message of the scope with a seq in `[lowSeq, highSeq]` is stored. Seq is server-global
 * and sparse, so two seqs never prove the messages between them; only a claim does.
 *
 * - [reachesOldest]: nothing exists below the range either. Such a segment covers every seq from 0 for
 *   joining and membership (see [effectiveLowSeq]). `[0, 0]` + [reachesOldest] = a known-empty scope.
 * - [headAtVerify]: [highSeq] was the scope's newest message when the claim was verified.
 */
data class CanonicalMessageV2Segment(
    val lowSeq: Long,
    val highSeq: Long,
    val reachesOldest: Boolean,
    val headAtVerify: Boolean,
    val verifiedAtMs: Long,
    val source: CanonicalMessageV2SegmentSource,
) {
    init {
        require(lowSeq >= 0L && lowSeq <= highSeq) { "segment [$lowSeq, $highSeq] is not a range" }
    }

    val effectiveLowSeq: Long get() = if (reachesOldest) 0L else lowSeq

    operator fun contains(seq: Long): Boolean = seq in effectiveLowSeq..highSeq

    /** `[0, 0]` + reachesOldest: the scope had no message at all (empty baseline). */
    val isEmptyBaseline: Boolean get() = reachesOldest && lowSeq == 0L && highSeq == 0L
}

/**
 * One write's range claim, before it is unioned into the scope's segments. Built from a server page's
 * `messageWindow` ([fromMessageWindow]), a context page's edges ([fromContextPage]) or a proven realtime run.
 */
data class CanonicalMessageV2Coverage(
    val lowSeq: Long,
    val highSeq: Long,
    val reachesOldest: Boolean,
    val headAtVerify: Boolean,
    val source: CanonicalMessageV2SegmentSource,
) {
    init {
        require(lowSeq >= 0L && lowSeq <= highSeq) { "coverage [$lowSeq, $highSeq] is not a range" }
    }

    fun toSegment(verifiedAtMs: Long): CanonicalMessageV2Segment =
        CanonicalMessageV2Segment(lowSeq, highSeq, reachesOldest, headAtVerify, verifiedAtMs, source)

    /**
     * Phase 2b: this claim without [seqs] (rows the page carried but the writer could not store): the pieces
     * between them. Only the piece starting at [lowSeq] keeps [reachesOldest], only the piece ending at
     * [highSeq] keeps [headAtVerify]; an excluded edge drops that flag. Seqs outside the range are ignored.
     */
    fun excluding(seqs: Set<Long>): List<CanonicalMessageV2Coverage> {
        val cuts = seqs.filter { it in lowSeq..highSeq }.sorted()
        if (cuts.isEmpty()) return listOf(this)
        val pieces = mutableListOf<CanonicalMessageV2Coverage>()
        var start = lowSeq
        for (cut in cuts) {
            if (start <= cut - 1) {
                pieces += CanonicalMessageV2Coverage(start, cut - 1, reachesOldest && start == lowSeq, headAtVerify = false, source)
            }
            start = cut + 1
        }
        if (start <= highSeq) {
            pieces += CanonicalMessageV2Coverage(start, highSeq, reachesOldest && start == lowSeq, headAtVerify, source)
        }
        return pieces
    }

    companion object {
        /**
         * The claim of a `GET messages/channel` page from its server `messageWindow` (server
         * messageService.listMessagesWithCoverage, same repeatable-read snapshot as the rows):
         * - `coveredAfterSeq` is the channel's newest seq below the page (0 = none), so the page proves
         *   `[coveredAfterSeq + 1, coveredThroughSeq]`;
         * - it reaches the oldest only when nothing is below it AND no plan history cutoff hid older rows;
         * - `completeThroughLatest` = the page reached the channel's newest message.
         * An empty page that is not the whole channel proves nothing (`null`).
         */
        fun fromMessageWindow(
            coveredAfterSeq: Long,
            coveredThroughSeq: Long,
            completeThroughLatest: Boolean,
            historyLimited: Boolean,
            source: CanonicalMessageV2SegmentSource,
        ): CanonicalMessageV2Coverage? {
            if (coveredAfterSeq < 0L || coveredThroughSeq < 0L) return null
            val reachesOldest = coveredAfterSeq == 0L && !historyLimited
            val low = coveredAfterSeq + 1L
            return when {
                low <= coveredThroughSeq ->
                    CanonicalMessageV2Coverage(low, coveredThroughSeq, reachesOldest, completeThroughLatest, source)
                // No row: only a whole empty channel says anything.
                reachesOldest && coveredThroughSeq == 0L ->
                    CanonicalMessageV2Coverage(0L, 0L, reachesOldest = true, headAtVerify = completeThroughLatest, source)
                else -> null
            }
        }

        /**
         * A `messages/context` page has no `messageWindow`; its `hasOlder` / `hasNewer` are data-derived,
         * so it proves `[hasOlder ? min : open-oldest, max]`, at the head iff `!hasNewer`. Empty = nothing.
         */
        fun fromContextPage(
            seqs: List<Long>,
            hasOlder: Boolean,
            hasNewer: Boolean,
            historyLimited: Boolean,
        ): CanonicalMessageV2Coverage? {
            val min = seqs.minOrNull() ?: return null
            val max = seqs.max()
            return CanonicalMessageV2Coverage(
                lowSeq = min,
                highSeq = max,
                reachesOldest = !hasOlder && !historyLimited,
                headAtVerify = !hasNewer,
                source = CanonicalMessageV2SegmentSource.Context,
            )
        }
    }
}

/**
 * Pure segment algebra (design §2, I3). Every function takes and returns NORMALIZED lists: sorted by
 * lowSeq, no two segments overlapping or touching (h1 + 1 == l2 touches; h1 + 2 == l2 leaves a one-seq gap).
 */
object CanonicalMessageV2SegmentAlgebra {
    /** Whether [a] and [b] overlap or touch, so their union is one range. */
    fun joins(a: CanonicalMessageV2Segment, b: CanonicalMessageV2Segment): Boolean =
        b.effectiveLowSeq <= a.highSeq + 1L && a.effectiveLowSeq <= b.highSeq + 1L

    fun isNormalized(segments: List<CanonicalMessageV2Segment>): Boolean =
        segments.zipWithNext().all { (a, b) -> a.lowSeq < b.lowSeq && !joins(a, b) }

    /** [segments] (normalized) with [incoming] unioned in: every segment it joins merges into one. */
    fun union(
        segments: List<CanonicalMessageV2Segment>,
        incoming: CanonicalMessageV2Segment,
    ): List<CanonicalMessageV2Segment> {
        require(isNormalized(segments)) { "segments are not normalized" }
        var merged = incoming
        val kept = mutableListOf<CanonicalMessageV2Segment>()
        // A merge can only widen [merged], so one pass after sorting by effective low is not enough when a
        // reachesOldest segment widens downward; loop until nothing else joins.
        var pending = segments
        while (true) {
            val (joined, apart) = pending.partition { joins(it, merged) }
            if (joined.isEmpty()) {
                kept += apart
                break
            }
            merged = joined.fold(merged, ::merge)
            pending = apart
        }
        return (kept + merged).sortedBy { it.lowSeq }
    }

    /** The union of two joining segments. */
    fun merge(a: CanonicalMessageV2Segment, b: CanonicalMessageV2Segment): CanonicalMessageV2Segment {
        require(joins(a, b)) { "segments [${a.lowSeq}, ${a.highSeq}] and [${b.lowSeq}, ${b.highSeq}] do not join" }
        val high = maxOf(a.highSeq, b.highSeq)
        val atHigh = listOf(a, b).filter { it.highSeq == high }
        val newest = listOf(a, b).maxWith(compareBy<CanonicalMessageV2Segment> { it.verifiedAtMs }.thenBy { it.highSeq })
        return CanonicalMessageV2Segment(
            lowSeq = minOf(a.lowSeq, b.lowSeq),
            highSeq = high,
            reachesOldest = a.reachesOldest || b.reachesOldest,
            headAtVerify = atHigh.any { it.headAtVerify },
            verifiedAtMs = maxOf(a.verifiedAtMs, b.verifiedAtMs),
            source = newest.source,
        )
    }

    /** The head segment: the one reaching the highest seq (the legacy window mirrors it). */
    fun head(segments: List<CanonicalMessageV2Segment>): CanonicalMessageV2Segment? = segments.maxByOrNull { it.highSeq }

    /** The segment [seq] belongs to (I4), or `null` = a loose fact. */
    fun memberOf(segments: List<CanonicalMessageV2Segment>, seq: Long): CanonicalMessageV2Segment? =
        segments.firstOrNull { seq in it }

    /**
     * Retention deleted every fact at or below [cutSeq]: no claim may reach there any more. A segment
     * entirely at or below goes; one reaching below starts above [cutSeq] and no longer reaches the oldest.
     */
    fun trimBelow(segments: List<CanonicalMessageV2Segment>, cutSeq: Long): List<CanonicalMessageV2Segment> =
        segments.mapNotNull { segment ->
            when {
                segment.highSeq <= cutSeq -> null
                segment.effectiveLowSeq <= cutSeq ->
                    segment.copy(lowSeq = maxOf(segment.lowSeq, cutSeq + 1L), reachesOldest = false)
                else -> segment
            }
        }
}
