package build.raft.app

import build.raft.app.data.MOBILE_THREAD_OPEN_TRACE_NAME
import build.raft.app.data.RaftWebEventRecord
import build.raft.app.data.RaftWebSpanRecord
import build.raft.app.model.raftEpochMillisUtcIso
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Open-path jitter tracing, phase 1 (#proj-mobile:#199): how a thread / channel open settles, and
 * every user-visible move of its message list until it does.
 *
 * One [ThreadOpenTraceSession] per sampled open (trace_sample flags, RaftTraceSampling.kt), observe-only: it reads
 * the list's layout once per frame and never scrolls. It ends when the open is stable (positioned,
 * the network answer applied, then [ThreadOpenStableQuietMs] without a shift), the user touches the
 * list, another open replaces it, the page leaves, or after [ThreadOpenTimeoutMs]. Then it writes
 * an `open_trace_summary` line and up to [ThreadOpenMaxShiftRecords] `open_trace_shift` lines to the
 * diagnostics log and uploads the `mobile.thread_open` span with its `mobile.thread_open.shift`
 * events (privacy mode permitting, RaftClientTraceEvents).
 *
 * A shift is a frame where a row on screen in both frames moved by at least [ThreadOpenShiftMinDp]
 * (or every row was replaced: one viewport height) while the
 * user was not touching the list (they would have ended the session). Its cause is the viewport
 * owner that scrolled in that frame ([ThreadViewportOwnerTag], noted at the list's viewport writers),
 * else what changed: [ThreadOpenShiftCause].
 */
internal const val ThreadOpenStableQuietMs = 600L
internal const val ThreadOpenTimeoutMs = 8_000L
internal const val ThreadOpenShiftMinDp = 2f
internal const val ThreadOpenMaxShiftRecords = 20

/** Rows compared frame to frame; a thread viewport shows far fewer. Fixed, so a frame allocates nothing. */
internal const val ThreadOpenMaxTrackedRows = 64

/** A reopen of the same view this soon after the last one ended is reported (`reopen_same_view_within_ms`). */
internal const val ThreadOpenReopenWindowMs = 10_000L

internal const val ThreadOpenShiftEventName = "mobile.thread_open.shift"

/**
 * Who issued a programmatic scroll of the thread list (the viewport writers in ThreadMessagesList,
 * RaftThreadImeLayout and the controller's command executor). Observe-only labels.
 */
internal object ThreadViewportOwnerTag {
    const val InitialBottomRetry = "initial_bottom_retry"
    const val FollowLatest = "follow_latest"
    const val FocusedReanchor = "focused_reanchor"
    const val OriginCorrection = "origin_correction"
    const val TailLayoutAnchor = "tail_layout_anchor"
    const val ImeCorrection = "ime_correction"
    const val OlderRestore = "older_restore"
    const val NewerSettle = "newer_settle"
    const val RenderedTail = "rendered_tail"
    const val JumpPill = "jump_pill"
    const val Top = "top"
    const val AnchorRestore = "anchor_restore"
    const val AdaptiveRestore = "adaptive_restore"
    const val LongMessageToggle = "long_message_toggle"

    /** The typing / working line came or went while the list rested on its end (ThreadMessagesList). */
    const val TypingLineEnd = "typing_line_end"

    /** The reading anchor kept the anchored row's top in place across an in-place row height change (ThreadReadingAnchorKeeper). */
    const val ReadingAnchor = "reading_anchor"
}

/** A shift's cause when no viewport owner scrolled in its frame. */
internal object ThreadOpenShiftCause {
    const val ContentInsert = "content_mutation:insert"
    const val ContentReplace = "content_mutation:replace"
    const val ContentRowHeight = "content_mutation:row_height"
    const val InsetViewport = "inset:viewport"
    const val InsetPadding = "inset:padding"
    const val WidthChange = "width_change"
    const val FontScale = "font_scale"
    const val Unknown = "unknown"
}

internal enum class ThreadOpenMilestone(val wire: String) {
    TransitionEnd("transition_end"),
    FirstFrame("first_frame"),
    CacheRowsShown("cache_rows_shown"),
    NetworkApplied("network_applied"),
    TargetPositioned("target_positioned"),
    Stable("stable"),
}

internal enum class ThreadOpenEnd(val wire: String) {
    Stable("stable"),
    UserTookOver("user_took_over"),
    Superseded("superseded"),
    Left("left"),
    Timeout("timeout"),
}

/** The list rows of one frame, read in place (no copies): LazyListLayoutInfo in production. */
internal interface ThreadOpenFrameRows {
    val count: Int
    val totalItems: Int
    val viewportWidth: Int
    val viewportHeight: Int
    val contentPadding: Int

    fun key(index: Int): Any

    fun offset(index: Int): Int

    fun size(index: Int): Int
}

/** Everything else a frame reads; one instance per recorder, refilled every frame. */
internal class ThreadOpenFrameInputs {
    var density: Float = 1f
    var userTouching: Boolean = false
    var pageLeaving: Boolean = false
    var pageInMotion: Boolean = false
    var positioned: Boolean = false
    var typeScaleKey: Int = 0
    var ownerScrollCount: Int = 0
    var ownerTag: String = ThreadOpenShiftCause.Unknown
    var ownerAttempt: Int = 0

    // The payload the list renders.
    var status: String = "loading"
    var messageRows: Int = 0
    var durableLastKnown: Boolean = false
    var backgroundRefreshing: Boolean = false
    var mainLoadFailed: Boolean = false
}

/** One process-wide monotonic clock for every session, so reopen gaps compare. */
internal object ThreadOpenClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}

/** The last few ended opens (view key hash, end time), in memory only: never uploaded or logged. */
internal object ThreadOpenReopenMemory {
    private const val Slots = 4
    private val keys = IntArray(Slots)
    private val ends = LongArray(Slots) { Long.MIN_VALUE }
    private var next = 0

    fun reopenGapMs(viewKey: String, nowMs: Long): Long? {
        val hash = viewKey.hashCode()
        var best: Long? = null
        for (i in 0 until Slots) {
            if (ends[i] == Long.MIN_VALUE || keys[i] != hash) continue
            val gap = nowMs - ends[i]
            if (gap in 0..ThreadOpenReopenWindowMs && (best == null || gap < best)) best = gap
        }
        return best
    }

    fun ended(viewKey: String, nowMs: Long) {
        keys[next] = viewKey.hashCode()
        ends[next] = nowMs
        next = (next + 1) % Slots
    }

    internal fun clearForTest() {
        ends.fill(Long.MIN_VALUE)
        next = 0
    }
}

internal class ThreadOpenTraceSession(
    /** `channel` / `dm` / `thread` (RaftThreadSurfaceKind). */
    val surfaceKind: String,
    /** `focused` (aimed at a message) or `latest`. */
    val openKind: String,
    val sampleRate: Double,
    /** The flag-resolution rule that admitted it: `trace_sample` (a flag decided) or `trace_sample_default`. */
    val recordCondition: String,
    private val viewKey: String,
    private val nowMs: () -> Long = ThreadOpenClock::nowMs,
    private val nowEpochMs: () -> Long,
    random: Random = Random,
    val appBuild: String? = null,
    val layoutMode: String? = null,
) {
    val traceId: String = randomTraceHex(16, random)
    val spanId: String = randomTraceHex(8, random)
    private val startMs = nowMs()
    private val startEpochMs = nowEpochMs()
    val reopenSameViewWithinMs: Long? = ThreadOpenReopenMemory.reopenGapMs(viewKey, startMs)

    private val milestones = LongArray(ThreadOpenMilestone.entries.size) { -1L }
    var end: ThreadOpenEnd? = null
        private set
    private var endMs = -1L
    private var phase = "open"
    var cache: String = ""
        private set
    var networkResult: String = "none"
        private set
    private var sawRefresh = false
    private var sawInMotion = false

    // Frame-to-frame rows (double buffer, swapped each frame).
    private var prevKeys = arrayOfNulls<Any>(ThreadOpenMaxTrackedRows)
    private var prevOffsets = IntArray(ThreadOpenMaxTrackedRows)
    private var prevSizes = IntArray(ThreadOpenMaxTrackedRows)
    private var prevCount = -1
    private var curKeys = arrayOfNulls<Any>(ThreadOpenMaxTrackedRows)
    private var curOffsets = IntArray(ThreadOpenMaxTrackedRows)
    private var curSizes = IntArray(ThreadOpenMaxTrackedRows)
    private var prevTotal = -1
    private var prevWidth = -1
    private var prevHeight = -1
    private var prevPadding = -1
    private var prevTypeScale = 0
    private var prevOwnerScrolls = -1
    private var previousFrameShift = false
    private var lastShiftMs = -1L

    // Score.
    var shiftCount = 0
        private set
    var shiftSumDp = 0f
        private set
    var shiftMaxDp = 0f
        private set
    private var shiftViewportHeights = 0f
    var shiftsAfterPositioned = 0
        private set

    // The first [ThreadOpenMaxShiftRecords] shifts.
    private val shiftT = IntArray(ThreadOpenMaxShiftRecords)
    private val shiftDelta = FloatArray(ThreadOpenMaxShiftRecords)
    private val shiftPhase = arrayOfNulls<String>(ThreadOpenMaxShiftRecords)
    private val shiftRowsResized = IntArray(ThreadOpenMaxShiftRecords)
    private val shiftMaxRowDelta = FloatArray(ThreadOpenMaxShiftRecords)
    private val shiftPaddingDelta = FloatArray(ThreadOpenMaxShiftRecords)
    private val shiftViewportDelta = FloatArray(ThreadOpenMaxShiftRecords)
    private val shiftCause = arrayOfNulls<String>(ThreadOpenMaxShiftRecords)
    private val shiftAttempt = IntArray(ThreadOpenMaxShiftRecords)
    private var lastCause: String? = null

    val ended: Boolean
        get() = end != null

    private var reported = false

    /** Writes the lines and uploads (set by the page that owns the session; ThreadOpenTraceRecorder.kt). */
    var reporter: ((ThreadOpenTraceSession) -> Unit)? = null

    /** Ends at [reason] if still open, then reports (once). */
    fun finishAndReport(reason: ThreadOpenEnd) {
        finish(reason)
        reporter?.invoke(this)
    }

    /** True once, for the one caller that reports this session (log lines + upload). */
    fun claimReport(): Boolean {
        if (end == null || reported) return false
        reported = true
        return true
    }

    fun milestoneMs(milestone: ThreadOpenMilestone): Long? = milestones[milestone.ordinal].takeIf { it >= 0 }

    private fun mark(milestone: ThreadOpenMilestone, t: Long) {
        if (milestones[milestone.ordinal] >= 0) return
        milestones[milestone.ordinal] = t
        phase = milestone.wire
    }

    /** One frame. Allocates nothing; a no-op once ended. */
    fun onFrame(rows: ThreadOpenFrameRows, inputs: ThreadOpenFrameInputs) {
        if (end != null) return
        val t = nowMs() - startMs
        if (inputs.pageLeaving) return finish(ThreadOpenEnd.Left, t)
        if (inputs.userTouching) return finish(ThreadOpenEnd.UserTookOver, t)
        mark(ThreadOpenMilestone.FirstFrame, t)
        if (inputs.pageInMotion) sawInMotion = true else if (sawInMotion) mark(ThreadOpenMilestone.TransitionEnd, t)
        observeContent(inputs, t)
        if (inputs.positioned) mark(ThreadOpenMilestone.TargetPositioned, t)
        observeRows(rows, inputs, t)
        val positionedAt = milestones[ThreadOpenMilestone.TargetPositioned.ordinal]
        val networkAt = milestones[ThreadOpenMilestone.NetworkApplied.ordinal]
        if (positionedAt >= 0 && networkAt >= 0) {
            val quietSince = max(max(positionedAt, networkAt), lastShiftMs)
            if (t - quietSince >= ThreadOpenStableQuietMs) {
                mark(ThreadOpenMilestone.Stable, t)
                return finish(ThreadOpenEnd.Stable, t)
            }
        }
        if (t >= ThreadOpenTimeoutMs) finish(ThreadOpenEnd.Timeout, t)
    }

    /** Cache first frame and network answer, from the payload the list renders. */
    private fun observeContent(inputs: ThreadOpenFrameInputs, t: Long) {
        val loading = inputs.status == "loading"
        if (cache.isEmpty() && inputs.messageRows > 0) {
            cache =
                when {
                    inputs.durableLastKnown -> "durable"
                    loading -> "seed"
                    sawRefresh && !inputs.backgroundRefreshing -> "none"
                    else -> "memory"
                }
            if (cache != "none") mark(ThreadOpenMilestone.CacheRowsShown, t)
        }
        if (inputs.backgroundRefreshing || loading) sawRefresh = true
        if (networkResult == "none" && !inputs.backgroundRefreshing && !loading && (sawRefresh || inputs.mainLoadFailed)) {
            networkResult = if (inputs.mainLoadFailed) "failed" else "applied"
            mark(ThreadOpenMilestone.NetworkApplied, t)
        }
    }

    private fun observeRows(rows: ThreadOpenFrameRows, inputs: ThreadOpenFrameInputs, t: Long) {
        val count = minOf(rows.count, ThreadOpenMaxTrackedRows)
        for (i in 0 until count) {
            curKeys[i] = rows.key(i)
            curOffsets[i] = rows.offset(i)
            curSizes[i] = rows.size(i)
        }
        val total = rows.totalItems
        val width = rows.viewportWidth
        val height = rows.viewportHeight
        val padding = rows.contentPadding
        if (prevCount > 0 && count > 0) {
            // The largest move of a row on screen in both frames (CLS-like: a row growing pushes
            // its neighbours; a scroll moves them all).
            var deltaPx = 0
            var common = false
            var rowsResized = 0
            var maxRowDeltaPx = 0
            for (i in 0 until count) {
                val p = indexOfPrevious(curKeys[i])
                if (p < 0) continue
                common = true
                val moved = abs(curOffsets[i] - prevOffsets[p])
                if (moved > deltaPx) deltaPx = moved
                val sizeDelta = abs(curSizes[i] - prevSizes[p])
                if (sizeDelta > 0) {
                    rowsResized += 1
                    if (sizeDelta > maxRowDeltaPx) maxRowDeltaPx = sizeDelta
                }
            }
            val replaced = !common
            val movedPx = if (replaced) height else deltaPx
            val density = if (inputs.density > 0f) inputs.density else 1f
            val movedDp = movedPx / density
            if (movedDp >= ThreadOpenShiftMinDp) {
                val ownerScrolled = prevOwnerScrolls >= 0 && inputs.ownerScrollCount != prevOwnerScrolls
                val cause =
                    when {
                        ownerScrolled -> inputs.ownerTag
                        inputs.typeScaleKey != prevTypeScale -> ThreadOpenShiftCause.FontScale
                        width != prevWidth -> ThreadOpenShiftCause.WidthChange
                        height != prevHeight -> ThreadOpenShiftCause.InsetViewport
                        padding != prevPadding -> ThreadOpenShiftCause.InsetPadding
                        replaced -> ThreadOpenShiftCause.ContentReplace
                        total != prevTotal -> ThreadOpenShiftCause.ContentInsert
                        rowsResized > 0 -> ThreadOpenShiftCause.ContentRowHeight
                        else -> ThreadOpenShiftCause.Unknown
                    }
                val paddingDeltaDp = if (prevPadding >= 0 && padding != prevPadding) (padding - prevPadding) / density else 0f
                val viewportDeltaDp = if (prevHeight >= 0 && height != prevHeight) (height - prevHeight) / density else 0f
                recordShift(
                    t = t,
                    movedDp = movedDp,
                    rowsResized = rowsResized,
                    maxRowDeltaDp = maxRowDeltaPx / density,
                    paddingDeltaDp = paddingDeltaDp,
                    viewportDeltaDp = viewportDeltaDp,
                    viewportHeight = height,
                    movedPx = movedPx,
                    cause = cause,
                    attempt = if (ownerScrolled) inputs.ownerAttempt else 0,
                )
            } else {
                previousFrameShift = false
            }
        }
        // Swap buffers: this frame becomes the previous one.
        val k = prevKeys; prevKeys = curKeys; curKeys = k
        val o = prevOffsets; prevOffsets = curOffsets; curOffsets = o
        val s = prevSizes; prevSizes = curSizes; curSizes = s
        for (i in count until ThreadOpenMaxTrackedRows) {
            if (prevKeys[i] == null) break
            prevKeys[i] = null
        }
        prevCount = count
        prevTotal = total
        prevWidth = width
        prevHeight = height
        prevPadding = padding
        prevTypeScale = inputs.typeScaleKey
        prevOwnerScrolls = inputs.ownerScrollCount
    }

    private fun indexOfPrevious(key: Any?): Int {
        for (j in 0 until prevCount) if (prevKeys[j] == key) return j
        return -1
    }

    /**
     * Consecutive shifting frames with one cause (an animated scroll, a row growing over a few
     * frames) are one shift: what the eye sees as one move.
     */
    private fun recordShift(
        t: Long,
        movedDp: Float,
        rowsResized: Int,
        maxRowDeltaDp: Float,
        paddingDeltaDp: Float,
        viewportDeltaDp: Float,
        viewportHeight: Int,
        movedPx: Int,
        cause: String,
        attempt: Int,
    ) {
        lastShiftMs = t
        if (viewportHeight > 0) shiftViewportHeights += movedPx.toFloat() / viewportHeight
        shiftSumDp += movedDp
        val merge = previousFrameShift && lastCause == cause
        previousFrameShift = true
        if (merge) {
            val slot = shiftCount - 1
            if (slot < ThreadOpenMaxShiftRecords) {
                shiftDelta[slot] += movedDp
                shiftRowsResized[slot] += rowsResized
                if (maxRowDeltaDp > shiftMaxRowDelta[slot]) shiftMaxRowDelta[slot] = maxRowDeltaDp
                shiftPaddingDelta[slot] += paddingDeltaDp
                shiftViewportDelta[slot] += viewportDeltaDp
                if (shiftDelta[slot] > shiftMaxDp) shiftMaxDp = shiftDelta[slot]
            } else if (movedDp > shiftMaxDp) {
                shiftMaxDp = movedDp
            }
            return
        }
        lastCause = cause
        val slot = shiftCount
        shiftCount += 1
        if (milestones[ThreadOpenMilestone.TargetPositioned.ordinal] >= 0) shiftsAfterPositioned += 1
        if (movedDp > shiftMaxDp) shiftMaxDp = movedDp
        if (slot >= ThreadOpenMaxShiftRecords) return
        shiftT[slot] = t.toInt()
        shiftDelta[slot] = movedDp
        shiftPhase[slot] = phase
        shiftRowsResized[slot] = rowsResized
        shiftMaxRowDelta[slot] = maxRowDeltaDp
        shiftPaddingDelta[slot] = paddingDeltaDp
        shiftViewportDelta[slot] = viewportDeltaDp
        shiftCause[slot] = cause
        shiftAttempt[slot] = attempt
    }

    /** Ends the session (once). [ThreadOpenEnd.Superseded] / [ThreadOpenEnd.Left] come from the page. */
    fun finish(reason: ThreadOpenEnd, t: Long = nowMs() - startMs) {
        if (end != null) return
        end = reason
        endMs = t
        ThreadOpenReopenMemory.ended(viewKey, startMs + t)
    }

    val recordedShifts: Int
        get() = minOf(shiftCount, ThreadOpenMaxShiftRecords)

    fun shiftCauseAt(index: Int): String? = shiftCause.getOrNull(index)?.takeIf { index < recordedShifts }

    fun shiftDeltaDpAt(index: Int): Float = shiftDelta[index]

    fun shiftPhaseAt(index: Int): String? = shiftPhase[index]

    fun shiftAttemptAt(index: Int): Int = shiftAttempt[index]

    fun shiftPaddingDeltaDpAt(index: Int): Float = shiftPaddingDelta[index]

    fun shiftViewportDeltaDpAt(index: Int): Float = shiftViewportDelta[index]

    val timeToStableMs: Long?
        get() = milestoneMs(ThreadOpenMilestone.Stable)

    val durationMs: Long
        get() = endMs

    private fun Float.oneDecimal(): Double = (this * 10f).roundToInt() / 10.0

    /** Span attributes (also the summary line's fields). Ids: only the random trace id. */
    fun summaryAttrs(): Map<String, Any> =
        buildMap {
            put("surface_kind", surfaceKind)
            put("open_kind", openKind)
            appBuild?.trim()?.takeIf { it.isNotEmpty() }?.let { put("app_build", it) }
            layoutMode?.trim()?.takeIf { it.isNotEmpty() }?.let { put("layout_mode", it) }
            put("end", end?.wire ?: "open")
            put("duration_ms", endMs)
            put("cache", cache.ifEmpty { "none" })
            put("network_result", networkResult)
            ThreadOpenMilestone.entries.forEach { milestone -> milestoneMs(milestone)?.let { put("${milestone.wire}_ms", it) } }
            put("shift_count", shiftCount)
            put("shift_sum_dp", shiftSumDp.oneDecimal())
            put("shift_max_dp", shiftMaxDp.oneDecimal())
            put("shift_vh", ((shiftViewportHeights * 100f).roundToInt() / 100.0))
            put("shifts_after_positioned", shiftsAfterPositioned)
            put("shifts_recorded", recordedShifts)
            timeToStableMs?.let { put("time_to_stable_ms", it) }
            reopenSameViewWithinMs?.let { put("reopen_same_view_within_ms", it) }
            put("sample_rate", sampleRate)
            put("record_condition", recordCondition)
        }

    fun shiftAttrs(index: Int): Map<String, Any> =
        buildMap {
            put("seq", index + 1)
            put("t_ms", shiftT[index])
            put("delta_dp", shiftDelta[index].oneDecimal())
            put("phase", shiftPhase[index] ?: "open")
            put("cause", shiftCause[index] ?: ThreadOpenShiftCause.Unknown)
            if (shiftAttempt[index] > 0) put("owner_attempt", shiftAttempt[index])
            put("rows_resized", shiftRowsResized[index])
            put("max_row_delta_dp", shiftMaxRowDelta[index].oneDecimal())
            if (shiftPaddingDelta[index] != 0f) put("padding_delta_dp", shiftPaddingDelta[index].oneDecimal())
            if (shiftViewportDelta[index] != 0f) put("viewport_delta_dp", shiftViewportDelta[index].oneDecimal())
        }

    /** Diagnostics lines (kept in every build, RaftDiagnosticsVolume `open_trace_`). */
    fun diagnosticsLines(uploaded: Boolean): List<String> =
        buildList {
            add(
                "open_trace_summary trace_id=$traceId " +
                    summaryAttrs().entries.joinToString(" ") { (k, v) -> "$k=$v" } +
                    " uploaded=$uploaded",
            )
            for (i in 0 until recordedShifts) {
                add("open_trace_shift trace_id=$traceId " + shiftAttrs(i).entries.joinToString(" ") { (k, v) -> "$k=$v" })
            }
        }

    /** The `mobile.thread_open` span and its shift events (web-trace records). */
    fun traceRecords(): Pair<RaftWebSpanRecord, List<RaftWebEventRecord>> {
        val span =
            RaftWebSpanRecord(
                traceId = traceId,
                spanId = spanId,
                name = MOBILE_THREAD_OPEN_TRACE_NAME,
                startTime = raftEpochMillisUtcIso(startEpochMs),
                endTime = raftEpochMillisUtcIso(startEpochMs + max(endMs, 0L)),
                attrs = summaryAttrs().toJsonObject(),
            )
        val events =
            (0 until recordedShifts).map { i ->
                RaftWebEventRecord(
                    name = ThreadOpenShiftEventName,
                    time = raftEpochMillisUtcIso(startEpochMs + shiftT[i]),
                    traceId = traceId,
                    spanId = spanId,
                    attrs = shiftAttrs(i).toJsonObject(),
                )
            }
        return span to events
    }
}

private fun Map<String, Any>.toJsonObject(): JsonObject =
    JsonObject(
        mapValues { (_, value) ->
            when (value) {
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        },
    )

/** The owner a controller command stands for when its dispatcher did not name itself. */
internal fun LazyViewportCommandTarget.openTraceOwnerTag(): String =
    when (this) {
        is LazyViewportCommandTarget.Top -> ThreadViewportOwnerTag.Top
        LazyViewportCommandTarget.Latest -> ThreadViewportOwnerTag.FollowLatest
        is LazyViewportCommandTarget.Anchor ->
            when (placement) {
                LazyViewportAnchorPlacement.ExactOffset -> ThreadViewportOwnerTag.AnchorRestore
                LazyViewportAnchorPlacement.Focused -> ThreadViewportOwnerTag.FocusedReanchor
                LazyViewportAnchorPlacement.RenderedBottom -> ThreadViewportOwnerTag.RenderedTail
                LazyViewportAnchorPlacement.FocusedThreadReplies -> ThreadViewportOwnerTag.JumpPill
            }
    }
