package build.raft.app

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/**
 * The open-trace session on a virtual clock and a fake list layout (ThreadOpenTrace.kt): milestones,
 * the stable / took-over / left / timeout ends, shift detection, cause attribution and the score.
 */
class ThreadOpenTraceSessionTest {
    @AfterTest
    fun forgetReopens() = ThreadOpenReopenMemory.clearForTest()

    /** Rows top to bottom, 100 px tall unless resized; density 2 (so 2 dp = 4 px). */
    private class Rows : ThreadOpenFrameRows {
        val keys = mutableListOf<Any>()
        val offsets = mutableListOf<Int>()
        val sizes = mutableListOf<Int>()
        override var totalItems = 0
        override var viewportWidth = 400
        override var viewportHeight = 800
        override var contentPadding = 0
        override val count: Int get() = keys.size

        override fun key(index: Int) = keys[index]

        override fun offset(index: Int) = offsets[index]

        override fun size(index: Int) = sizes[index]

        fun layout(vararg rows: Pair<String, Int>, firstOffset: Int = 0) {
            keys.clear(); offsets.clear(); sizes.clear()
            var y = firstOffset
            rows.forEach { (key, size) ->
                keys += key; offsets += y; sizes += size
                y += size
            }
            totalItems = maxOf(totalItems, rows.size)
        }
    }

    private class Clock(var now: Long = 1_000L)

    private fun session(clock: Clock, viewKey: String = "thread-a") =
        ThreadOpenTraceSession(
            surfaceKind = "channel",
            openKind = "latest",
            sampleRate = 0.1,
            recordCondition = "trace_sample",
            viewKey = viewKey,
            nowMs = { clock.now },
            nowEpochMs = { 1_794_391_200_000L + clock.now },
            random = Random(7),
        )

    private fun inputs() =
        ThreadOpenFrameInputs().apply {
            density = 2f
            status = "content"
            messageRows = 3
            backgroundRefreshing = true
        }

    private fun ThreadOpenTraceSession.frame(clock: Clock, rows: Rows, inputs: ThreadOpenFrameInputs, stepMs: Long = 16L) {
        clock.now += stepMs
        onFrame(rows, inputs)
    }

    @Test
    fun aCacheOpenThatTheNetworkReplacesIsStableAfterTheQuietWindowWithItsMilestones() {
        val clock = Clock()
        val rows = Rows().apply { layout("a" to 100, "b" to 100, "c" to 100) }
        val input = inputs()
        val trace = session(clock)
        trace.frame(clock, rows, input)
        assertEquals("memory", trace.cache)
        assertEquals(16L, trace.milestoneMs(ThreadOpenMilestone.CacheRowsShown))

        // The network answer lands and the bottom anchor settles; nothing moves.
        input.backgroundRefreshing = false
        input.positioned = true
        trace.frame(clock, rows, input)
        assertEquals("applied", trace.networkResult)
        assertEquals(32L, trace.milestoneMs(ThreadOpenMilestone.NetworkApplied))
        repeat(37) { trace.frame(clock, rows, input) }
        assertNull(trace.end, "592 ms quiet is not yet stable")
        trace.frame(clock, rows, input)
        assertEquals(ThreadOpenEnd.Stable, trace.end)
        assertEquals(trace.milestoneMs(ThreadOpenMilestone.Stable), trace.timeToStableMs)
        assertEquals(0, trace.shiftCount)
    }

    @Test
    fun shiftsAreAttributedToTheOwnerThatScrolledElseToWhatChanged() {
        val clock = Clock()
        val rows = Rows().apply { layout("a" to 100, "b" to 100, "c" to 100) }
        val input = inputs()
        val trace = session(clock)
        trace.frame(clock, rows, input)

        // Under 2 dp (3 px at density 2) is not a shift.
        rows.layout("a" to 100, "b" to 100, "c" to 100, firstOffset = -3)
        trace.frame(clock, rows, input)
        assertEquals(0, trace.shiftCount)

        // The initial bottom retry scrolls (its 3rd attempt): 50 dp.
        input.ownerScrollCount = 1
        input.ownerTag = ThreadViewportOwnerTag.InitialBottomRetry
        input.ownerAttempt = 3
        rows.layout("a" to 100, "b" to 100, "c" to 100, firstOffset = -103)
        trace.frame(clock, rows, input)

        // A row grows (a late markdown height): the rows under it move 30 dp.
        rows.layout("a" to 160, "b" to 100, "c" to 100, firstOffset = -103)
        trace.frame(clock, rows, input)
        trace.frame(clock, rows, input)

        // The keyboard shrinks the viewport and the list follows.
        rows.viewportHeight = 600
        rows.layout("a" to 160, "b" to 100, "c" to 100, firstOffset = -303)
        trace.frame(clock, rows, input)
        trace.frame(clock, rows, input)

        // The network answer replaces every row on screen.
        rows.layout("x" to 100, "y" to 100)
        trace.frame(clock, rows, input)

        assertEquals(
            listOf(
                ThreadViewportOwnerTag.InitialBottomRetry,
                ThreadOpenShiftCause.ContentRowHeight,
                ThreadOpenShiftCause.InsetViewport,
                ThreadOpenShiftCause.ContentReplace,
            ),
            (0 until trace.recordedShifts).map { trace.shiftCauseAt(it) },
        )
        assertEquals(3, trace.shiftAttemptAt(0))
        assertEquals(50f, trace.shiftDeltaDpAt(0))
        assertEquals(30f, trace.shiftDeltaDpAt(1))
        assertEquals(300f, trace.shiftDeltaDpAt(3), "a full replace counts one viewport height")
        assertEquals(4, trace.shiftCount)
        assertEquals(480f, trace.shiftSumDp)
        assertEquals(300f, trace.shiftMaxDp)
        assertEquals("cache_rows_shown", trace.shiftPhaseAt(0))
    }

    @Test
    fun consecutiveFramesOfOneMoveAreOneShiftAndOnlyTwentyAreKept() {
        val clock = Clock()
        val rows = Rows().apply { layout("a" to 100, "b" to 100) }
        val input = inputs()
        val trace = session(clock)
        trace.frame(clock, rows, input)
        // An animated owner scroll over three frames: one shift of 30 dp.
        input.ownerTag = ThreadViewportOwnerTag.Top
        for (step in 1..3) {
            input.ownerScrollCount = step
            rows.layout("a" to 100, "b" to 100, firstOffset = -20 * step)
            trace.frame(clock, rows, input)
        }
        assertEquals(1, trace.shiftCount)
        assertEquals(30f, trace.shiftDeltaDpAt(0))

        // 30 separate jumps (a quiet frame between each): all counted, the first 20 kept.
        trace.frame(clock, rows, input)
        input.positioned = true
        var offset = -60
        repeat(30) {
            offset -= 10
            input.ownerScrollCount += 1
            rows.layout("a" to 100, "b" to 100, firstOffset = offset)
            trace.frame(clock, rows, input)
            trace.frame(clock, rows, input)
        }
        assertEquals(31, trace.shiftCount)
        assertEquals(ThreadOpenMaxShiftRecords, trace.recordedShifts)
        assertEquals(30, trace.shiftsAfterPositioned)
        val (span, events) = trace.traceRecords()
        assertEquals(ThreadOpenMaxShiftRecords, events.size)
        assertEquals(31, span.attrs.getValue("shift_count").jsonPrimitive.content.toInt())
    }

    @Test
    fun theUserTouchingThePageLeavingAndTheTimeoutEndTheSession() {
        val cases =
            listOf<Pair<(ThreadOpenFrameInputs) -> Unit, ThreadOpenEnd>>(
                { i: ThreadOpenFrameInputs -> i.userTouching = true } to ThreadOpenEnd.UserTookOver,
                { i: ThreadOpenFrameInputs -> i.pageLeaving = true } to ThreadOpenEnd.Left,
                { _: ThreadOpenFrameInputs -> } to ThreadOpenEnd.Timeout,
            )
        for ((change, expected) in cases) {
            val clock = Clock()
            val rows = Rows().apply { layout("a" to 100) }
            val input = inputs()
            val trace = session(clock)
            trace.frame(clock, rows, input)
            change(input)
            trace.frame(clock, rows, input, stepMs = ThreadOpenTimeoutMs)
            assertEquals(expected, trace.end, "$expected")
            // Ended: later frames change nothing.
            rows.layout("z" to 100)
            trace.frame(clock, rows, input)
            assertEquals(0, trace.shiftCount)
        }
    }

    @Test
    fun theUploadIsOneSpanWithItsShiftEventsAndAReopenOfTheSameViewIsReported() {
        val clock = Clock()
        val rows = Rows().apply { layout("a" to 100, "b" to 100) }
        val input = inputs()
        val first = session(clock)
        first.frame(clock, rows, input)
        input.ownerScrollCount = 1
        input.ownerTag = ThreadViewportOwnerTag.FollowLatest
        rows.layout("a" to 100, "b" to 100, firstOffset = -40)
        first.frame(clock, rows, input)
        first.finish(ThreadOpenEnd.Superseded)

        val (span, events) = first.traceRecords()
        assertEquals("mobile.thread_open", span.name)
        assertEquals("span", span.type)
        assertEquals(32, span.traceId.length)
        assertEquals(16, span.spanId.length)
        assertEquals("superseded", span.attrs.getValue("end").jsonPrimitive.content)
        assertEquals("trace_sample", span.attrs.getValue("record_condition").jsonPrimitive.content)
        assertEquals("0.1", span.attrs.getValue("sample_rate").jsonPrimitive.content)
        val shift = events.single()
        assertEquals("mobile.thread_open.shift", shift.name)
        assertEquals(span.traceId, shift.traceId)
        assertEquals(span.spanId, shift.spanId)
        assertEquals("follow_latest", shift.attrs.getValue("cause").jsonPrimitive.content)
        assertEquals("20.0", shift.attrs.getValue("delta_dp").jsonPrimitive.content)
        assertTrue(first.diagnosticsLines(uploaded = true).first().startsWith("open_trace_summary trace_id=${span.traceId} "))

        clock.now += 700L
        val reopened = session(clock)
        assertEquals(700L, reopened.reopenSameViewWithinMs)
        assertNull(session(clock, viewKey = "thread-b").reopenSameViewWithinMs)
    }
}
