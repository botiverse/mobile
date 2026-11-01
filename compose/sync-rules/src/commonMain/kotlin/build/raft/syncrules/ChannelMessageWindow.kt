package build.raft.syncrules

/*
 * SPIKE copy of :shared build.raft.app.data.ChannelMessageWindow (toCoverage only) and the
 * PersistedCursor legacy-window mirror from CanonicalMessageV2Generation.kt. The kotlinx
 * @Serializable annotation and the lenient JSON decode stay in :shared: decoding is the
 * platform's job (web already has the JSON object), the rule is what is shared.
 */

/** A `GET messages/channel` page's server `messageWindow`; every field optional (drifted server). */
data class ChannelMessageWindow(
    val schemaVersion: Int? = null,
    val domain: String? = null,
    val serverId: String? = null,
    val receiverKind: String? = null,
    val receiverId: String? = null,
    val scopeId: String? = null,
    val coveredAfterSeq: Long? = null,
    val coveredFromSeq: Long? = null,
    val coveredThroughSeq: Long? = null,
    val remoteHighWaterSeq: Long? = null,
    val hasGap: Boolean? = null,
    val hasNewer: Boolean? = null,
    val completeThroughLatest: Boolean? = null,
) {
    /**
     * The range this page proves for the scope being written, or `null`: an unknown schema or domain, a
     * window naming another server or channel (or none), a missing bound, or an empty page that is not the
     * whole channel. `null` only withholds the claim; the page's facts are still stored.
     */
    fun toCoverage(
        expectedServerId: String,
        expectedScopeId: String,
        historyLimited: Boolean,
        source: CanonicalMessageV2SegmentSource,
    ): CanonicalMessageV2Coverage? {
        if (schemaVersion != CHANNEL_MESSAGE_WINDOW_SCHEMA_VERSION) return null
        if (domain != CHANNEL_MESSAGE_WINDOW_DOMAIN) return null
        if (serverId != expectedServerId || scopeId != expectedScopeId) return null
        return CanonicalMessageV2Coverage.fromMessageWindow(
            coveredAfterSeq = coveredAfterSeq ?: return null,
            coveredThroughSeq = coveredThroughSeq ?: return null,
            completeThroughLatest = completeThroughLatest ?: return null,
            historyLimited = historyLimited,
            source = source,
        )
    }
}

const val CHANNEL_MESSAGE_WINDOW_SCHEMA_VERSION: Int = 1
const val CHANNEL_MESSAGE_WINDOW_DOMAIN: String = "receiver_visible_messages_v1"

/** The durable per-scope cursor (subset of :shared PersistedCursor the legacy-window mirror reads). */
data class PersistedCursor(
    val latestSeq: Long?,
    val oldestLoadedSeq: Long?,
    val newestLoadedSeq: Long?,
    val hasLoadedOldest: Boolean = false,
    val hasLoadedNewest: Boolean = true,
    val epoch: String? = null,
)

/**
 * The legacy window of a cursor as a segment: `[oldest, newest]`, or `[0, 0]` for an empty baseline
 * (both edges NULL, oldest reached). `null` = the cursor claims no window.
 */
fun PersistedCursor.legacyWindowSegment(
    verifiedAtMs: Long,
    source: CanonicalMessageV2SegmentSource = CanonicalMessageV2SegmentSource.Legacy,
): CanonicalMessageV2Segment? {
    val oldest = oldestLoadedSeq
    val newest = newestLoadedSeq
    return when {
        oldest != null && newest != null && oldest in 0L..newest ->
            CanonicalMessageV2Segment(oldest, newest, hasLoadedOldest, hasLoadedNewest, verifiedAtMs, source)
        oldest == null && newest == null && hasLoadedOldest ->
            CanonicalMessageV2Segment(0L, 0L, reachesOldest = true, headAtVerify = hasLoadedNewest, verifiedAtMs, source)
        else -> null
    }
}

/** This cursor with its window columns mirroring [head] (`null` = no window); edges are stored-fact seqs. */
fun PersistedCursor.mirroring(head: CanonicalMessageV2Segment?, headFactBounds: Pair<Long, Long>?): PersistedCursor = when {
    head == null -> copy(oldestLoadedSeq = null, newestLoadedSeq = null, hasLoadedOldest = false, hasLoadedNewest = false)
    else -> copy(
        oldestLoadedSeq = headFactBounds?.first,
        newestLoadedSeq = headFactBounds?.second,
        hasLoadedOldest = head.reachesOldest,
        hasLoadedNewest = head.headAtVerify,
    )
}
