@file:OptIn(ExperimentalJsExport::class)

package build.raft.syncrules

/*
 * SPIKE web facade. Boundary rules:
 * - seq is a server-global 64-bit integer -> crossed as a decimal String (a JS number loses
 *   precision above 2^53; Kotlin Long has no stable TS type);
 * - verifiedAtMs is epoch milliseconds -> Double (exact below 2^53, ~285k years);
 * - collections are Arrays; the source enum is its wire string;
 * - an invalid input (bad range, unnormalized list, unknown source, non-decimal seq) throws a
 *   JS Error whose message names the rule (IllegalArgumentException extends Error in Kotlin/JS).
 */

/** One stored range claim: every message of the scope with seq in [lowSeq, highSeq] is stored. */
@JsExport
class SyncSegment(
    val lowSeq: String,
    val highSeq: String,
    val reachesOldest: Boolean,
    val headAtVerify: Boolean,
    val verifiedAtMs: Double,
    /** CanonicalMessageV2SegmentSource wire value: latest|older|newer|context|live|migrated|legacy. */
    val source: String,
)

/** One write's range claim before it is unioned in (no verification time yet). */
@JsExport
class SyncCoverage(
    val lowSeq: String,
    val highSeq: String,
    val reachesOldest: Boolean,
    val headAtVerify: Boolean,
    val source: String,
) {
    fun toSegment(verifiedAtMs: Double): SyncSegment =
        SyncSegment(lowSeq, highSeq, reachesOldest, headAtVerify, verifiedAtMs, source)
}

@JsExport
val SEGMENT_SOURCES: Array<String> = CanonicalMessageV2SegmentSource.entries.map { it.wire }.toTypedArray()

@JsExport
fun isNormalizedSegments(segments: Array<SyncSegment>): Boolean =
    CanonicalMessageV2SegmentAlgebra.isNormalized(segments.map { it.toKt() })

/** [segments] (normalized) with [incoming] unioned in. Throws on an unnormalized list. */
@JsExport
fun unionSegment(segments: Array<SyncSegment>, incoming: SyncSegment): Array<SyncSegment> =
    CanonicalMessageV2SegmentAlgebra.union(segments.map { it.toKt() }, incoming.toKt()).toJsArray()

@JsExport
fun headSegment(segments: Array<SyncSegment>): SyncSegment? =
    CanonicalMessageV2SegmentAlgebra.head(segments.map { it.toKt() })?.toJs()

/** The segment [seq] belongs to, or null = a loose fact. */
@JsExport
fun segmentOf(segments: Array<SyncSegment>, seq: String): SyncSegment? =
    CanonicalMessageV2SegmentAlgebra.memberOf(segments.map { it.toKt() }, seq.toSeq())?.toJs()

/** Retention deleted every fact at or below [cutSeq]. */
@JsExport
fun trimSegmentsBelow(segments: Array<SyncSegment>, cutSeq: String): Array<SyncSegment> =
    CanonicalMessageV2SegmentAlgebra.trimBelow(segments.map { it.toKt() }, cutSeq.toSeq()).toJsArray()

/**
 * The claim of a `GET messages/channel` page from its `messageWindow` JSON object as received
 * (any field may be absent; seq fields may be number or string), or null = proves nothing.
 */
@JsExport
fun coverageFromMessageWindow(
    messageWindow: dynamic,
    expectedServerId: String,
    expectedScopeId: String,
    historyLimited: Boolean,
    source: String,
): SyncCoverage? {
    if (messageWindow == null || jsTypeOf(messageWindow) != "object") return null
    val window = ChannelMessageWindow(
        schemaVersion = wireSeq(messageWindow.schemaVersion)?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt(),
        domain = messageWindow.domain as? String,
        serverId = messageWindow.serverId as? String,
        scopeId = messageWindow.scopeId as? String,
        coveredAfterSeq = wireSeq(messageWindow.coveredAfterSeq),
        coveredThroughSeq = wireSeq(messageWindow.coveredThroughSeq),
        completeThroughLatest = messageWindow.completeThroughLatest as? Boolean,
    )
    return window.toCoverage(expectedServerId, expectedScopeId, historyLimited, source.toSource())?.toJs()
}

/** The claim of a `messages/context` page from its row seqs; null = empty page. */
@JsExport
fun coverageFromContextPage(
    seqs: Array<String>,
    hasOlder: Boolean,
    hasNewer: Boolean,
    historyLimited: Boolean,
): SyncCoverage? =
    CanonicalMessageV2Coverage.fromContextPage(seqs.map { it.toSeq() }, hasOlder, hasNewer, historyLimited)?.toJs()

private fun String.toSeq(): Long = toLongOrNull() ?: throw IllegalArgumentException("seq \"$this\" is not a decimal integer")

private fun String.toSource(): CanonicalMessageV2SegmentSource =
    CanonicalMessageV2SegmentSource.fromWire(this) ?: throw IllegalArgumentException("unknown segment source \"$this\"")

/** A wire seq: a safe-integer JS number or a decimal string; anything else = absent. */
private fun wireSeq(value: dynamic): Long? = when (jsTypeOf(value)) {
    "number" -> (value as Double).let { if (it == kotlin.math.floor(it) && kotlin.math.abs(it) <= 9007199254740991.0) it.toLong() else null }
    "string" -> (value as String).toLongOrNull()
    else -> null
}

private fun SyncSegment.toKt() = CanonicalMessageV2Segment(
    lowSeq.toSeq(), highSeq.toSeq(), reachesOldest, headAtVerify, verifiedAtMs.toLong(), source.toSource(),
)

private fun CanonicalMessageV2Segment.toJs() = SyncSegment(
    lowSeq.toString(), highSeq.toString(), reachesOldest, headAtVerify, verifiedAtMs.toDouble(), source.wire,
)

private fun CanonicalMessageV2Coverage.toJs() =
    SyncCoverage(lowSeq.toString(), highSeq.toString(), reachesOldest, headAtVerify, source.wire)

private fun List<CanonicalMessageV2Segment>.toJsArray(): Array<SyncSegment> = map { it.toJs() }.toTypedArray()
