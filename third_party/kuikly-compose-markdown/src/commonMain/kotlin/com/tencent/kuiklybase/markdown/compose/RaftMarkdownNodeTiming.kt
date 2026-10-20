package com.tencent.kuiklybase.markdown.compose

import androidx.compose.runtime.compositionLocalOf
import kotlin.time.TimeSource

/**
 * Raft #Perf task #20 t6-a: per-node composition timing for the markdown
 * render tree (diagnostics only; reviewed spec by MingQi/XiShi 2026-07-23).
 *
 * Everything here is OFF unless the host app installs a sink. The OFF
 * counterfactual is executable: with [RaftMarkdownNodeTrace.sink] == null no
 * clock is read, no type name is normalized, no string is built and nothing
 * is printed — call sites bail out before any of that work (the reviewed
 * "gate before first clock read" requirement).
 *
 * Naming discipline: durations measured here are Composable call durations —
 * composition / render-tree construction. They are NOT native text measure
 * and NOT actual draw.
 */
interface RaftMarkdownNodeTraceSink {
    /** One line per emitted event; the host decides the channel. */
    fun emit(line: String)
}

object RaftMarkdownNodeTrace {
    /**
     * Host-installed sink; null keeps every instrumentation site on the
     * untimed path. Installed/cleared by the app (e.g. keyed to its
     * recomposition-profiler toggle) — this vendor module never reaches back
     * into app code.
     */
    var sink: RaftMarkdownNodeTraceSink? = null

    /**
     * Overhead-A/B suppression (reviewed): true keeps the sink uninstalled
     * even with diagnostics enabled, so paired runs on one exact differ ONLY
     * in this instrumentation. Session-only by construction (plain field,
     * reset on process death), settable solely via the app's debug
     * diagnostics deep link — no release/production control surface.
     */
    var suppressedForOverheadTest: Boolean = false
}

/** Individual node details below this exclusive duration are summarized only. */
internal const val RaftNodeDetailThresholdUs: Long = 200L

/** Per-render cap on individual detail lines so logging cannot dominate. */
internal const val RaftNodeDetailCapPerRender: Int = 32

private var renderSeqCounter: Int = 0

private class NodeFrame(
    val typeKey: String,
    val depth: Int,
    val topIndex: Int,
    val startMark: kotlin.time.TimeMark,
) {
    var childTotalUs: Long = 0L
    // Reviewed paragraph sub-phases (mutually exclusive slices of this
    // node's exclusive time): annotate = AnnotatedString build,
    // textCall = MarkdownText composable call; the rest of exclusive time
    // reports as paragraph "other" (dispatch/model/spacer plumbing).
    var annotateUs: Long = 0L
    var textCallUs: Long = 0L
    var textCallStart: kotlin.time.TimeMark? = null
}

private class TypeSummary {
    var count: Int = 0
    var totalExclusiveUs: Long = 0L
    var maxExclusiveUs: Long = 0L
    var belowThresholdCount: Int = 0
    var totalAnnotateUs: Long = 0L
    var totalTextCallUs: Long = 0L
}

/**
 * Per-MarkdownSuccess-render timing state. Created only when the sink is
 * installed; composition of one markdown body runs on a single thread
 * (the Kuikly context thread), so plain fields suffice.
 */
internal class RaftMarkdownRenderTiming(
    private val sink: RaftMarkdownNodeTraceSink,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    val renderSeq: Int = ++renderSeqCounter
    private val stack = ArrayDeque<NodeFrame>()
    private val summaries = LinkedHashMap<String, TypeSummary>()
    private var detailEmitted = 0
    private var detailDropped = 0
    private var exclusiveSumUs = 0L
    private var topLevelCounter = -1
    private var annotatedStringHits = 0
    private var annotatedStringRebuilds = 0

    /** AnnotatedString memo accounting (counts only — no content/labels/hashes
     * per the reviewed privacy vocabulary). A textCall drop without these
     * counters could not be attributed to cache hits vs workload drift. */
    fun recordAnnotatedStringHit() {
        annotatedStringHits++
    }

    fun recordAnnotatedStringRebuild() {
        annotatedStringRebuilds++
    }

    fun enterNode(rawTypeName: String): Boolean {
        val typeKey = raftMarkdownNodeTypeKey(rawTypeName)
        val depth = stack.size
        val topIndex = if (depth == 0) ++topLevelCounter else topLevelCounter
        stack.addLast(NodeFrame(typeKey, depth, topIndex, timeSource.markNow()))
        return true
    }

    /**
     * Measures the paragraph AnnotatedString build (non-composable block).
     * No-ops when no frame is open (defensive: never throws in production).
     */
    fun <T> measureParagraphAnnotate(block: () -> T): T {
        val frame = stack.lastOrNull() ?: return block()
        val mark = timeSource.markNow()
        val result = block()
        frame.annotateUs += mark.elapsedNow().inWholeMicroseconds
        return result
    }

    /** Sequential pair around the MarkdownText composable call (Compose
     * forbids try/finally around composable invocations — same contract as
     * enter/exitNode: an abandoned pass invalidates the render). */
    fun enterParagraphText() {
        stack.lastOrNull()?.let { it.textCallStart = timeSource.markNow() }
    }

    fun exitParagraphText() {
        stack.lastOrNull()?.let { frame ->
            frame.textCallStart?.let { start ->
                frame.textCallUs += start.elapsedNow().inWholeMicroseconds
                frame.textCallStart = null
            }
        }
    }

    fun exitNode() {
        val frame = stack.removeLastOrNull() ?: return
        val totalUs = frame.startMark.elapsedNow().inWholeMicroseconds
        stack.lastOrNull()?.let { parent -> parent.childTotalUs += totalUs }
        val exclusiveUs = (totalUs - frame.childTotalUs).coerceAtLeast(0L)
        exclusiveSumUs += exclusiveUs
        val summary = summaries.getOrPut(frame.typeKey) { TypeSummary() }
        summary.count++
        summary.totalExclusiveUs += exclusiveUs
        summary.totalAnnotateUs += frame.annotateUs
        summary.totalTextCallUs += frame.textCallUs
        if (exclusiveUs > summary.maxExclusiveUs) summary.maxExclusiveUs = exclusiveUs
        if (exclusiveUs >= RaftNodeDetailThresholdUs) {
            if (detailEmitted < RaftNodeDetailCapPerRender) {
                detailEmitted++
                val subPhases =
                    if (frame.annotateUs > 0L || frame.textCallUs > 0L) {
                        val otherUs =
                            (exclusiveUs - frame.annotateUs - frame.textCallUs).coerceAtLeast(0L)
                        " annotateUs=${frame.annotateUs} textCallUs=${frame.textCallUs} otherUs=$otherUs"
                    } else ""
                sink.emit(
                    "SlockMarkdownSpike phase=markdown.node.render renderSeq=$renderSeq " +
                        "type=${frame.typeKey} exclusiveUs=$exclusiveUs totalUs=$totalUs " +
                        "depth=${frame.depth} topIndex=${frame.topIndex}" + subPhases
                )
            } else {
                detailDropped++
            }
        } else {
            summary.belowThresholdCount++
        }
    }

    /**
     * Returns true when this render's attribution is VALID (no aborted
     * frames). Callers may use the verdict to forward per-render facts (e.g.
     * the row census render cost) strictly on the valid path — invalid
     * renders yield no data anywhere, per the no-false-data contract.
     */
    fun finishRender(renderNodesUs: Long): Boolean {
        // Reviewed no-false-data contract: frames left open by an abandoned
        // pass must NOT be synthesized into durations. If any frame is still
        // open, the whole render's attribution is discarded — only an
        // explicit invalid reconcile line is emitted so censoring is visible.
        if (stack.isNotEmpty()) {
            val abortedFrames = stack.size
            stack.clear()
            sink.emit(
                "SlockMarkdownSpike phase=markdown.node.reconcile renderSeq=$renderSeq " +
                    "invalid=1 abortedFrames=$abortedFrames renderNodesUs=$renderNodesUs"
            )
            return false
        }
        for ((typeKey, s) in summaries) {
            sink.emit(
                "SlockMarkdownSpike phase=markdown.node.summary renderSeq=$renderSeq " +
                    "type=$typeKey count=${s.count} totalExclusiveUs=${s.totalExclusiveUs} " +
                    "maxUs=${s.maxExclusiveUs} belowThreshold=${s.belowThresholdCount} " +
                    "totalAnnotateUs=${s.totalAnnotateUs} totalTextCallUs=${s.totalTextCallUs}"
            )
        }
        val remainderUs = (renderNodesUs - exclusiveSumUs)
        sink.emit(
            "SlockMarkdownSpike phase=markdown.node.reconcile renderSeq=$renderSeq " +
                "renderNodesUs=$renderNodesUs exclusiveSumUs=$exclusiveSumUs " +
                "remainderUs=$remainderUs detailDropped=$detailDropped abortedFrames=0 " +
                "annotatedStringHits=$annotatedStringHits annotatedStringRebuilds=$annotatedStringRebuilds"
        )
        return true
    }
}

/**
 * Fixed vocabulary for node types (reviewed privacy requirement: no message
 * ids, bodies or content hashes; unknown types collapse to "other" to keep
 * log cardinality bounded).
 */
internal fun raftMarkdownNodeTypeKey(typeName: String): String = when (typeName) {
    "TEXT" -> "text"
    "EOL" -> "eol"
    "CODE_FENCE" -> "code_fence"
    "CODE_BLOCK" -> "code_block"
    "ATX_1", "ATX_2", "ATX_3", "ATX_4", "ATX_5", "ATX_6" -> "heading"
    "SETEXT_1", "SETEXT_2" -> "setext_heading"
    "BLOCK_QUOTE" -> "block_quote"
    "PARAGRAPH" -> "paragraph"
    "ORDERED_LIST" -> "ordered_list"
    "UNORDERED_LIST" -> "unordered_list"
    "IMAGE" -> "image"
    "LINK_DEFINITION" -> "link_definition"
    "HORIZONTAL_RULE" -> "horizontal_rule"
    "TABLE" -> "table"
    else -> "other"
}

/**
 * Per-render timing handle for the current MarkdownSuccess execution; null
 * whenever the sink is not installed (the zero-cost OFF path).
 *
 * Exception safety note (reviewed): Compose forbids try/finally around
 * composable invocations, so enter/exit pairs are sequential. An abandoned
 * composition pass therefore emits nothing at all (no exit -> no summary ->
 * no reconcile line) — aborted passes yield NO data rather than wrong data,
 * and partially closed stacks that still reach finishRender are drained and
 * reported via abortedFrames.
 */
internal val LocalRaftMarkdownRenderTiming =
    compositionLocalOf<RaftMarkdownRenderTiming?> { null }

/**
 * Creation seam (reviewed OFF-counterfactual requirement): the ONLY place a
 * render timing is constructed. With sink == null it returns null before any
 * clock/normalizer/string work — tests inject counting doubles to prove the
 * OFF path performs zero instrumentation calls and the ON path the expected
 * number.
 */
internal fun raftMarkdownRenderTimingOrNull(
    sink: RaftMarkdownNodeTraceSink? = RaftMarkdownNodeTrace.sink,
    timeSource: TimeSource = TimeSource.Monotonic,
): RaftMarkdownRenderTiming? = sink?.let { RaftMarkdownRenderTiming(it, timeSource) }

/**
 * Sentinel for "the host declared NO annotation revision". Compared by
 * IDENTITY (===) so no legitimate revision value — including null, empty
 * lists, or any structurally-equal object — can collide with it.
 */
object RaftMarkdownAnnotationRevisionUnspecified

/**
 * Host-provided revision for dynamic annotation inputs (e.g. tag / channel
 * label resolution) that affect the built AnnotatedString but are not part of
 * the (content, node, style, settings) memo keys.
 *
 * Contract: AnnotatedString memoization is OPT-IN. With the default
 * (identity-)sentinel the paragraph/text builds keep the legacy semantics —
 * rebuilt every recomposition — because a MarkdownAnnotator is a behavior
 * callback whose output can change behind a stable identity; memoizing it
 * without a revision would silently freeze such output. A host that provides
 * a revision value promises it changes (structural equality) whenever ANY
 * dynamic annotation input changes; the value itself should be cheap,
 * structurally comparable data (e.g. the resolved label snapshot list) and
 * must never be bumped per-recomposition (permanent cache miss).
 */
val LocalRaftMarkdownAnnotationRevision =
    compositionLocalOf<Any?> { RaftMarkdownAnnotationRevisionUnspecified }

/**
 * Slock (task #800): whether MarkdownParagraph fills the available width.
 * Default true preserves the legacy layout for every existing surface; the
 * Thread page provides false for right-aligned own messages so short
 * paragraphs wrap to their text and hug the End edge.
 */
val LocalRaftMarkdownParagraphFillWidth = compositionLocalOf { true }

/**
 * Single decision point for AnnotatedString memoization, shared by
 * MarkdownParagraph and MarkdownText and locked by contract teeth:
 * null = memo DISABLED (legacy rebuild-every-recomposition semantics);
 * non-null = the exact remember keys to use. Only the identity sentinel
 * disables — null is a valid host revision meaning "static annotations".
 */
internal fun raftMarkdownAnnotatedStringMemoKeys(
    content: Any?,
    node: Any?,
    style: Any?,
    annotatorSettings: Any?,
    revision: Any?,
): Array<Any?>? =
    if (revision === RaftMarkdownAnnotationRevisionUnspecified) {
        null
    } else {
        arrayOf(content, node, style, annotatorSettings, revision)
    }

/**
 * Per-row render-cost sink for the host's plain-candidate census (task #20
 * Option B P1 v2, reviewed). MarkdownSuccess forwards renderNodesUs here
 * STRICTLY on the valid finishRender path — invalid/aborted renders forward
 * nothing (no-false-data). The host provides a per-row token that buffers the
 * cost as a pending value and only commits it to the per-open collector when
 * the composition itself commits (RememberObserver.onRemembered), so an
 * abandoned pass leaves no trace. Default null = census off = zero work.
 */
interface RaftMarkdownRowRenderCostSink {
    fun recordValidRender(renderNodesUs: Long)
}

val LocalRaftMarkdownRowRenderCostSink =
    compositionLocalOf<RaftMarkdownRowRenderCostSink?> { null }
