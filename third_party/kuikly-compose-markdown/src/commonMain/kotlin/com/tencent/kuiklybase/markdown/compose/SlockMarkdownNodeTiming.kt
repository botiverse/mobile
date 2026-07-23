package com.tencent.kuiklybase.markdown.compose

import androidx.compose.runtime.compositionLocalOf
import kotlin.time.TimeSource

/**
 * Raft #Perf task #20 t6-a: per-node composition timing for the markdown
 * render tree (diagnostics only; reviewed spec by MingQi/XiShi 2026-07-23).
 *
 * Everything here is OFF unless the host app installs a sink. The OFF
 * counterfactual is executable: with [SlockMarkdownNodeTrace.sink] == null no
 * clock is read, no type name is normalized, no string is built and nothing
 * is printed — call sites bail out before any of that work (the reviewed
 * "gate before first clock read" requirement).
 *
 * Naming discipline: durations measured here are Composable call durations —
 * composition / render-tree construction. They are NOT native text measure
 * and NOT actual draw.
 */
interface SlockMarkdownNodeTraceSink {
    /** One line per emitted event; the host decides the channel. */
    fun emit(line: String)
}

object SlockMarkdownNodeTrace {
    /**
     * Host-installed sink; null keeps every instrumentation site on the
     * untimed path. Installed/cleared by the app (e.g. keyed to its
     * recomposition-profiler toggle) — this vendor module never reaches back
     * into app code.
     */
    var sink: SlockMarkdownNodeTraceSink? = null
}

/** Individual node details below this exclusive duration are summarized only. */
internal const val SlockNodeDetailThresholdUs: Long = 200L

/** Per-render cap on individual detail lines so logging cannot dominate. */
internal const val SlockNodeDetailCapPerRender: Int = 32

private var renderSeqCounter: Int = 0

private class NodeFrame(
    val typeKey: String,
    val depth: Int,
    val topIndex: Int,
    val startMark: kotlin.time.TimeMark,
) {
    var childTotalUs: Long = 0L
}

private class TypeSummary {
    var count: Int = 0
    var totalExclusiveUs: Long = 0L
    var maxExclusiveUs: Long = 0L
    var belowThresholdCount: Int = 0
}

/**
 * Per-MarkdownSuccess-render timing state. Created only when the sink is
 * installed; composition of one markdown body runs on a single thread
 * (the Kuikly context thread), so plain fields suffice.
 */
internal class SlockMarkdownRenderTiming(
    private val sink: SlockMarkdownNodeTraceSink,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    val renderSeq: Int = ++renderSeqCounter
    private val stack = ArrayDeque<NodeFrame>()
    private val summaries = LinkedHashMap<String, TypeSummary>()
    private var detailEmitted = 0
    private var detailDropped = 0
    private var exclusiveSumUs = 0L
    private var topLevelCounter = -1

    fun enterNode(typeKey: String): Boolean {
        val depth = stack.size
        val topIndex = if (depth == 0) ++topLevelCounter else topLevelCounter
        stack.addLast(NodeFrame(typeKey, depth, topIndex, timeSource.markNow()))
        return true
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
        if (exclusiveUs > summary.maxExclusiveUs) summary.maxExclusiveUs = exclusiveUs
        if (exclusiveUs >= SlockNodeDetailThresholdUs) {
            if (detailEmitted < SlockNodeDetailCapPerRender) {
                detailEmitted++
                sink.emit(
                    "SlockMarkdownSpike phase=markdown.node.render renderSeq=$renderSeq " +
                        "type=${frame.typeKey} exclusiveUs=$exclusiveUs totalUs=$totalUs " +
                        "depth=${frame.depth} topIndex=${frame.topIndex}"
                )
            } else {
                detailDropped++
            }
        } else {
            summary.belowThresholdCount++
        }
    }

    fun finishRender(renderNodesUs: Long) {
        // Defensive: a composition abort can leave frames; close them so the
        // reconciliation line reflects reality instead of silently dropping.
        var abortedFrames = 0
        while (stack.isNotEmpty()) {
            abortedFrames++
            exitNode()
        }
        for ((typeKey, s) in summaries) {
            sink.emit(
                "SlockMarkdownSpike phase=markdown.node.summary renderSeq=$renderSeq " +
                    "type=$typeKey count=${s.count} totalExclusiveUs=${s.totalExclusiveUs} " +
                    "maxUs=${s.maxExclusiveUs} belowThreshold=${s.belowThresholdCount}"
            )
        }
        val remainderUs = (renderNodesUs - exclusiveSumUs)
        sink.emit(
            "SlockMarkdownSpike phase=markdown.node.reconcile renderSeq=$renderSeq " +
                "renderNodesUs=$renderNodesUs exclusiveSumUs=$exclusiveSumUs " +
                "remainderUs=$remainderUs detailDropped=$detailDropped " +
                "abortedFrames=$abortedFrames"
        )
    }
}

/**
 * Fixed vocabulary for node types (reviewed privacy requirement: no message
 * ids, bodies or content hashes; unknown types collapse to "other" to keep
 * log cardinality bounded).
 */
internal fun slockMarkdownNodeTypeKey(typeName: String): String = when (typeName) {
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
internal val LocalSlockMarkdownRenderTiming =
    compositionLocalOf<SlockMarkdownRenderTiming?> { null }
