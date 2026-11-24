package build.raft.app

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadOpenTraceTest {
    private data class FakeRow(val key: Any, val offset: Int, val size: Int)

    private class FakeFrameRows(
        var rows: List<FakeRow>,
        override var totalItems: Int = rows.size,
        override var viewportWidth: Int = 400,
        override var viewportHeight: Int = 800,
        override var contentPadding: Int = 0,
    ) : ThreadOpenFrameRows {
        override val count: Int
            get() = rows.size

        override fun key(index: Int): Any = rows[index].key

        override fun offset(index: Int): Int = rows[index].offset

        override fun size(index: Int): Int = rows[index].size
    }

    @Test
    fun summaryAttrsAndDiagnosticsIncludeAppBuildAndLayoutMode() {
        var now = 0L
        val wide =
            ThreadOpenTraceSession(
                surfaceKind = "channel",
                openKind = "focused",
                sampleRate = 0.25,
                recordCondition = "trace_sample",
                viewKey = "view-wide",
                nowMs = { now },
                nowEpochMs = { 1_794_391_200_000L + now },
                random = Random(1),
                appBuild = " 130042 ",
                layoutMode = "wide",
            )
        wide.finish(ThreadOpenEnd.Stable, t = 120L)

        val attrs = wide.summaryAttrs()
        assertEquals("130042", attrs["app_build"])
        assertEquals("wide", attrs["layout_mode"])
        val summaryLine = wide.diagnosticsLines(uploaded = true).first()
        assertTrue("app_build=130042" in summaryLine, summaryLine)
        assertTrue("layout_mode=wide" in summaryLine, summaryLine)

        val (span, _) = wide.traceRecords()
        assertEquals("130042", span.attrs["app_build"]?.jsonPrimitive?.content)
        assertEquals("wide", span.attrs["layout_mode"]?.jsonPrimitive?.content)

        // Blank or absent values are omitted cleanly.
        val minimal =
            ThreadOpenTraceSession(
                surfaceKind = "dm",
                openKind = "latest",
                sampleRate = 1.0,
                recordCondition = "trace_sample",
                viewKey = "view-minimal",
                nowMs = { now },
                nowEpochMs = { 1_794_391_200_000L },
                random = Random(2),
                appBuild = "   ",
                layoutMode = null,
            )
        minimal.finish(ThreadOpenEnd.Left, t = 10L)
        assertFalse("app_build" in minimal.summaryAttrs())
        assertFalse("layout_mode" in minimal.summaryAttrs())
    }

    @Test
    fun shiftEventsEmitPaddingAndViewportDeltasEvenWhenAnotherCauseWins() {
        var now = 0L
        val session =
            ThreadOpenTraceSession(
                surfaceKind = "channel",
                openKind = "focused",
                sampleRate = 1.0,
                recordCondition = "trace_sample",
                viewKey = "view-same-frame-deltas",
                nowMs = { now },
                nowEpochMs = { 1_794_391_200_000L + now },
                random = Random(3),
                appBuild = "130042",
                layoutMode = "narrow",
            )
        val inputs =
            ThreadOpenFrameInputs().apply {
                density = 2f
                status = "content"
                messageRows = 2
                durableLastKnown = true
                backgroundRefreshing = true
                positioned = true
            }
        val frame1 =
            FakeFrameRows(
                rows = listOf(FakeRow("m1", offset = 0, size = 100), FakeRow("m2", offset = 100, size = 100)),
                viewportHeight = 1600,
                contentPadding = 80, // 40dp
            )
        session.onFrame(frame1, inputs)

        // Same-frame owner scroll + 40dp padding collapse + 20dp viewport resize + row height change.
        now = 16L
        inputs.ownerScrollCount = 1
        inputs.ownerTag = ThreadViewportOwnerTag.ReadingAnchor
        inputs.ownerAttempt = 2
        val frame2 =
            FakeFrameRows(
                rows = listOf(FakeRow("m1", offset = 80, size = 140), FakeRow("m2", offset = 220, size = 100)),
                viewportHeight = 1560, // -40px = -20dp
                contentPadding = 0, // -80px = -40dp
            )
        session.onFrame(frame2, inputs)

        // Second shift with unchanged padding & viewport height: deltas must be omitted.
        now = 48L
        val frame3 =
            FakeFrameRows(
                rows = listOf(FakeRow("m1", offset = 80, size = 140), FakeRow("m2", offset = 250, size = 100)),
                viewportHeight = 1560,
                contentPadding = 0,
            )
        session.onFrame(frame3, inputs)
        session.finish(ThreadOpenEnd.Left, t = 64L)

        assertEquals(2, session.recordedShifts)
        assertEquals(ThreadViewportOwnerTag.ReadingAnchor, session.shiftCauseAt(0))
        assertEquals(2, session.shiftAttemptAt(0))
        assertEquals(-40f, session.shiftPaddingDeltaDpAt(0))
        assertEquals(-20f, session.shiftViewportDeltaDpAt(0))

        val firstAttrs = session.shiftAttrs(0)
        assertEquals("reading_anchor", firstAttrs["cause"])
        assertEquals(2, firstAttrs["owner_attempt"])
        assertEquals(-40.0, firstAttrs["padding_delta_dp"])
        assertEquals(-20.0, firstAttrs["viewport_delta_dp"])

        val (_, events) = session.traceRecords()
        assertEquals(-40.0, events[0].attrs["padding_delta_dp"]?.jsonPrimitive?.double)
        assertEquals(-20.0, events[0].attrs["viewport_delta_dp"]?.jsonPrimitive?.double)
        assertEquals(2, events[0].attrs["owner_attempt"]?.jsonPrimitive?.int)

        val secondAttrs = session.shiftAttrs(1)
        assertFalse("padding_delta_dp" in secondAttrs)
        assertFalse("viewport_delta_dp" in secondAttrs)
    }

    @Test
    fun viewportOwnerTagsTrackPerTagOneBasedAttemptsAutomatically() {
        val viewport = ThreadListViewport(LazyListState(), reverse = true)

        // ReadingAnchor direct viewport owner increments 1, 2 independently of other tags.
        viewport.noteViewportOwner(ThreadViewportOwnerTag.ReadingAnchor)
        assertEquals(ThreadViewportOwnerTag.ReadingAnchor, viewport.activeScrollOwnerTag)
        assertEquals(1, viewport.activeScrollOwnerAttempt)

        viewport.noteViewportOwner(ThreadViewportOwnerTag.ReadingAnchor)
        assertEquals(2, viewport.activeScrollOwnerAttempt)

        // Dispatched FocusedReanchor commands increment 1, 2 without sharing ReadingAnchor's counter.
        viewport.noteDispatchOwner(ThreadViewportOwnerTag.FocusedReanchor)
        viewport.noteCommandOwner(ThreadViewportOwnerTag.AnchorRestore)
        assertEquals(ThreadViewportOwnerTag.FocusedReanchor, viewport.activeScrollOwnerTag)
        assertEquals(1, viewport.activeScrollOwnerAttempt)

        viewport.noteDispatchOwner(ThreadViewportOwnerTag.FocusedReanchor)
        viewport.noteCommandOwner(ThreadViewportOwnerTag.AnchorRestore)
        assertEquals(2, viewport.activeScrollOwnerAttempt)

        // Command without prior noteDispatchOwner uses defaultTag and assigns 1-based attempt.
        viewport.noteCommandOwner(ThreadViewportOwnerTag.AnchorRestore)
        assertEquals(ThreadViewportOwnerTag.AnchorRestore, viewport.activeScrollOwnerTag)
        assertEquals(1, viewport.activeScrollOwnerAttempt)

        // Raw viewport writes also assign 1-based attempts.
        viewport.noteRawViewportWrite(ThreadViewportOwnerTag.TailLayoutAnchor)
        assertEquals(ThreadViewportOwnerTag.TailLayoutAnchor, viewport.lastScrollOwnerTag)
        assertEquals(1, viewport.lastScrollOwnerAttempt)
        viewport.noteRawViewportWrite(ThreadViewportOwnerTag.TailLayoutAnchor)
        assertEquals(2, viewport.lastScrollOwnerAttempt)

        // Explicit positive attempt is honored and raises the counter floor for subsequent implicit calls.
        viewport.noteDispatchOwner(ThreadViewportOwnerTag.InitialBottomRetry, attempt = 3)
        viewport.noteCommandOwner(ThreadViewportOwnerTag.FollowLatest)
        assertEquals(ThreadViewportOwnerTag.InitialBottomRetry, viewport.activeScrollOwnerTag)
        assertEquals(3, viewport.activeScrollOwnerAttempt)
        viewport.noteDispatchOwner(ThreadViewportOwnerTag.InitialBottomRetry)
        viewport.noteCommandOwner(ThreadViewportOwnerTag.FollowLatest)
        assertEquals(4, viewport.activeScrollOwnerAttempt)
    }
}
