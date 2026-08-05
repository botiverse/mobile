package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * task #990: pins the two properties the trace exists to guarantee.
 *
 * Disabled must be genuinely inert — not merely quiet. The earlier probe looked
 * behaviour-neutral in a diff while writing product state at a new call time,
 * so "off means nothing runs" is asserted rather than assumed.
 *
 * Enabled must carry every common key on every record, because the failure it
 * replaces was a layer that emitted with no keys at all — invisible until
 * someone read the source line by line.
 */
class ThreadImeTraceTest {

    private val context =
        ThreadTraceContext(
            threadInstance = "thread-1",
            imeCycle = 7L,
            layoutGeneration = 3L,
            targetMessageId = "message-a"
        )

    @Test
    fun disabledTraceEvaluatesNothingAndEmitsNothing() {
        val lines = mutableListOf<String>()
        var bodyEvaluated = 0
        val trace = ThreadImeTrace(context) { lines += it }

        trace.event(
            producer = ThreadTraceProducer.ImeTailPixelProbe,
            function = "samplePixelPhase",
            name = "thread_ime_tail_pixel_probe"
        ) {
            // stands in for the PixelCopy: a GPU readback must not happen when
            // tracing is off, so the body must not be evaluated at all
            bodyEvaluated += 1
            "whitePermille=1000"
        }

        if (!ThreadImeTraceEnabled) {
            assertEquals(0, bodyEvaluated, "the event body must not be evaluated when tracing is disabled")
            assertEquals(emptyList(), lines, "a disabled trace must emit nothing")
        }
    }

    @Test
    fun everyRecordCarriesTheCommonKeys() {
        val lines = mutableListOf<String>()
        val trace = ThreadImeTrace(context) { lines += it }

        trace.emitRendered(
            producer = ThreadTraceProducer.ThreadLayoutProbe,
            function = "threadLazyWindowStats",
            name = "thread_lazy_window",
            frame = ThreadTraceFrame(frameSequence = 11L, frameTimeNanos = 999L),
            command = ThreadTraceCommand.Id(epoch = 4L, sequence = 5L),
            rendered = "gapPx=120"
        )

        if (ThreadImeTraceEnabled) {
            val line = lines.single()
            listOf(
                "producer=ThreadLayoutProbe",
                "function=threadLazyWindowStats",
                "threadInstance=thread-1",
                "imeCycle=7",
                "layoutGeneration=3",
                "targetMessageId=message-a",
                "commandId=4:5",
                "frameSequence=11",
                "frameTimeNanos=999",
                "gapPx=120"
            ).forEach { assertTrue(line.contains(it), "record must carry $it — got: $line") }
        }
    }

    @Test
    fun absentCommandRendersExplicitlyRatherThanEmpty() {
        assertEquals("none", ThreadTraceCommand.None.render())
        assertEquals("4:5", ThreadTraceCommand.Id(4L, 5L).render())
    }

    @Test
    fun baselineStatusSeparatesEveryWayItCanBeUnusable() {
        // The bug this replaces: layoutGeneration was stored and printed but
        // never compared, so a baseline from another layout reported Ok.
        assertEquals(
            ThreadPreShrinkBaselineStatus.StaleLayout,
            status(cachedLayoutGeneration = 2L, currentLayoutGeneration = 3L)
        )
        assertEquals(
            ThreadPreShrinkBaselineStatus.StaleTarget,
            status(cachedTargetMessageId = "other")
        )
        assertEquals(
            ThreadPreShrinkBaselineStatus.StaleThread,
            status(cachedThreadInstance = "thread-2")
        )
        assertEquals(
            ThreadPreShrinkBaselineStatus.StaleViewport,
            status(cachedViewportEndPx = 800)
        )
        assertEquals(
            ThreadPreShrinkBaselineStatus.Incomplete,
            status(cachedComplete = false)
        )
        assertEquals(ThreadPreShrinkBaselineStatus.Missing, status(cachedThreadInstance = null))
        assertEquals(ThreadPreShrinkBaselineStatus.Ok, status())
    }

    private fun status(
        cachedThreadInstance: String? = "thread-1",
        cachedTargetMessageId: String? = "message-a",
        cachedLayoutGeneration: Long? = 3L,
        cachedViewportStartPx: Int? = 0,
        cachedViewportEndPx: Int? = 1000,
        cachedComplete: Boolean = true,
        currentLayoutGeneration: Long = 3L
    ): ThreadPreShrinkBaselineStatus =
        threadPreShrinkBaselineStatus(
            cachedThreadInstance = cachedThreadInstance,
            cachedTargetMessageId = cachedTargetMessageId,
            cachedLayoutGeneration = cachedLayoutGeneration,
            cachedViewportStartPx = cachedViewportStartPx,
            cachedViewportEndPx = cachedViewportEndPx,
            cachedComplete = cachedComplete,
            currentThreadInstance = "thread-1",
            currentTargetMessageId = "message-a",
            currentLayoutGeneration = currentLayoutGeneration,
            currentViewportStartPx = 0,
            currentViewportEndPx = 1000
        )
}
