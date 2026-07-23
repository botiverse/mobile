package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.compose.SlockMarkdownNodeTraceSink
import com.tencent.kuiklybase.markdown.compose.SlockMarkdownRenderTiming
import com.tencent.kuiklybase.markdown.compose.slockMarkdownRenderTimingOrNull
import com.tencent.kuiklybase.markdown.compose.SlockNodeDetailCapPerRender
import com.tencent.kuiklybase.markdown.compose.slockMarkdownNodeTypeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/**
 * Raft #Perf task #20 t6-a synthetic matrix (reviewed spec): exclusive-time
 * math without parent/child double counting, threshold/summary behavior,
 * per-render detail cap, aborted-frame accounting, reconciliation closure,
 * privacy of emitted lines, and the fixed type vocabulary. Deterministic
 * virtual time via TestTimeSource per the AGENTS.md unit-test rule.
 */
@OptIn(ExperimentalTime::class)
class SlockMarkdownNodeTimingTest {
    private class RecordingSink : SlockMarkdownNodeTraceSink {
        val lines = mutableListOf<String>()
        override fun emit(line: String) {
            lines.add(line)
        }
    }

    private fun field(line: String, key: String): String? =
        Regex("$key=(\\S+)").find(line)?.groupValues?.get(1)

    @Test
    fun exclusiveTimeExcludesChildTotalWithoutDoubleCounting() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)

        timing.enterNode("PARAGRAPH")
        clock += 1_000.microseconds
        timing.enterNode("TEXT")
        clock += 3_000.microseconds
        timing.exitNode()
        clock += 2_000.microseconds
        timing.exitNode()
        timing.finishRender(renderNodesUs = 6_000)

        val details = sink.lines.filter { "phase=markdown.node.render" in it }
        assertEquals(2, details.size)
        val child = details.first { field(it, "type") == "text" }
        assertEquals("3000", field(child, "exclusiveUs"))
        assertEquals("3000", field(child, "totalUs"))
        assertEquals("1", field(child, "depth"))
        val parent = details.first { field(it, "type") == "paragraph" }
        assertEquals("6000", field(parent, "totalUs"))
        assertEquals("3000", field(parent, "exclusiveUs"))
        assertEquals("0", field(parent, "depth"))
        val reconcile = sink.lines.single { "phase=markdown.node.reconcile" in it }
        assertEquals("6000", field(reconcile, "exclusiveSumUs"))
        assertEquals("0", field(reconcile, "remainderUs"))
        assertEquals("0", field(reconcile, "abortedFrames"))
    }

    @Test
    fun belowThresholdNodesAreSummarizedNotDetailed() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)

        repeat(3) {
            timing.enterNode("EOL")
            clock += 50.microseconds
            timing.exitNode()
        }
        timing.finishRender(renderNodesUs = 150)

        assertTrue(sink.lines.none { "phase=markdown.node.render" in it })
        val summary = sink.lines.single { "phase=markdown.node.summary" in it }
        assertEquals("eol", field(summary, "type"))
        assertEquals("3", field(summary, "count"))
        assertEquals("150", field(summary, "totalExclusiveUs"))
        assertEquals("50", field(summary, "maxUs"))
        assertEquals("3", field(summary, "belowThreshold"))
    }

    @Test
    fun detailCapDropsAreCountedInReconcile() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)

        repeat(SlockNodeDetailCapPerRender + 5) {
            timing.enterNode("PARAGRAPH")
            clock += 1_000.microseconds
            timing.exitNode()
        }
        timing.finishRender(renderNodesUs = (SlockNodeDetailCapPerRender + 5) * 1_000L)

        val details = sink.lines.count { "phase=markdown.node.render" in it }
        assertEquals(SlockNodeDetailCapPerRender, details)
        val reconcile = sink.lines.single { "phase=markdown.node.reconcile" in it }
        assertEquals("5", field(reconcile, "detailDropped"))
        assertEquals("0", field(reconcile, "remainderUs"))
    }

    @Test
    fun abortedRenderDiscardsAllAttributionAndMarksInvalid() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)

        // One properly closed node, then two frames left open (abandoned pass).
        timing.enterNode("PARAGRAPH")
        clock += 1_000.microseconds
        timing.exitNode()
        timing.enterNode("BLOCK_QUOTE")
        clock += 1_000.microseconds
        timing.enterNode("PARAGRAPH")
        clock += 500.microseconds
        timing.finishRender(renderNodesUs = 2_500)

        // No-false-data contract: no summaries, no detail beyond the already
        // emitted closed-node line, and an explicit invalid reconcile only.
        assertTrue(sink.lines.none { "phase=markdown.node.summary" in it })
        val reconcile = sink.lines.single { "phase=markdown.node.reconcile" in it }
        assertEquals("1", field(reconcile, "invalid"))
        assertEquals("2", field(reconcile, "abortedFrames"))
        assertEquals(null, field(reconcile, "exclusiveSumUs"))
    }

    @Test
    fun remainderCapturesUnattributedRenderTime() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)

        timing.enterNode("PARAGRAPH")
        clock += 1_000.microseconds
        timing.exitNode()
        timing.finishRender(renderNodesUs = 4_000)

        val reconcile = sink.lines.single { "phase=markdown.node.reconcile" in it }
        assertEquals("1000", field(reconcile, "exclusiveSumUs"))
        assertEquals("3000", field(reconcile, "remainderUs"))
    }

    @Test
    fun emittedLinesCarryOnlyTheAllowedFieldVocabulary() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)
        timing.enterNode("PARAGRAPH")
        clock += 1_000.microseconds
        timing.exitNode()
        timing.finishRender(renderNodesUs = 1_000)

        val allowedKeys = setOf(
            "phase", "renderSeq", "type", "exclusiveUs", "totalUs", "depth",
            "topIndex", "count", "totalExclusiveUs", "maxUs", "belowThreshold",
            "renderNodesUs", "exclusiveSumUs", "remainderUs", "detailDropped",
            "abortedFrames", "invalid", "annotateUs", "textCallUs", "otherUs",
            "totalAnnotateUs", "totalTextCallUs",
            // PR-2 memo accounting: pure counts, no content/labels/hashes.
            "annotatedStringHits", "annotatedStringRebuilds"
        )
        for (line in sink.lines) {
            assertTrue(line.startsWith("SlockMarkdownSpike "), line)
            for (token in line.removePrefix("SlockMarkdownSpike ").split(" ")) {
                val key = token.substringBefore("=")
                assertTrue(key in allowedKeys, "unexpected field '$key' in: $line")
            }
        }
    }

    @Test
    fun annotatedStringCountersLandInTheValidReconcileLine() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)
        timing.enterNode("PARAGRAPH")
        clock += 1_000.microseconds
        timing.recordAnnotatedStringRebuild()
        timing.exitNode()
        timing.enterNode("PARAGRAPH")
        clock += 1_000.microseconds
        timing.recordAnnotatedStringHit()
        timing.recordAnnotatedStringHit()
        timing.exitNode()
        timing.finishRender(renderNodesUs = 2_000)

        val reconcile = sink.lines.single { "phase=markdown.node.reconcile" in it }
        assertEquals("2", field(reconcile, "annotatedStringHits"))
        assertEquals("1", field(reconcile, "annotatedStringRebuilds"))
    }

    @Test
    fun abortedRenderDiscardsAnnotatedStringCountersWithEverythingElse() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)
        timing.enterNode("PARAGRAPH")
        timing.recordAnnotatedStringRebuild()
        timing.recordAnnotatedStringHit()
        // No exitNode: abandoned pass -> only the invalid reconcile line, and
        // it must not carry memo counters (no-false-data contract).
        timing.finishRender(renderNodesUs = 1_000)

        val reconcile = sink.lines.single { "phase=markdown.node.reconcile" in it }
        assertEquals("1", field(reconcile, "invalid"))
        assertEquals(null, field(reconcile, "annotatedStringHits"))
        assertEquals(null, field(reconcile, "annotatedStringRebuilds"))
    }

    @Test
    fun nodeTypeVocabularyIsClosedAndUnknownCollapsesToOther() {
        assertEquals("paragraph", slockMarkdownNodeTypeKey("PARAGRAPH"))
        assertEquals("heading", slockMarkdownNodeTypeKey("ATX_3"))
        assertEquals("setext_heading", slockMarkdownNodeTypeKey("SETEXT_2"))
        assertEquals("table", slockMarkdownNodeTypeKey("TABLE"))
        assertEquals("other", slockMarkdownNodeTypeKey("SOME_CUSTOM_NODE"))
        assertEquals("other", slockMarkdownNodeTypeKey("MESSAGE_BODY_LEAK"))
    }

    private class CountingTimeSource(private val delegate: TestTimeSource) : kotlin.time.TimeSource {
        var markNowCalls = 0
        override fun markNow(): kotlin.time.TimeMark {
            markNowCalls++
            return delegate.markNow()
        }
    }

    @Test
    fun offCounterfactualCreatesNothingAndReadsNoClock() {
        val clock = CountingTimeSource(TestTimeSource())
        val timing = slockMarkdownRenderTimingOrNull(sink = null, timeSource = clock)
        assertNull(timing)
        assertEquals(0, clock.markNowCalls)
    }

    @Test
    fun onPathClockUsageMatchesEnteredNodeCount() {
        val sink = RecordingSink()
        val clock = CountingTimeSource(TestTimeSource())
        val timing = slockMarkdownRenderTimingOrNull(sink = sink, timeSource = clock)
        assertNotNull(timing)
        repeat(3) {
            timing.enterNode("PARAGRAPH")
            timing.exitNode()
        }
        assertEquals(3, clock.markNowCalls)
        timing.finishRender(renderNodesUs = 0)
    }

    @Test
    fun sinkSelectorInstallsOnEnableAndClearsOnDisable() {
        assertSame(SlockMarkdownNodeSpikeSink, slockMarkdownNodeSinkFor(true, suppressedForOverheadTest = false))
        assertNull(slockMarkdownNodeSinkFor(false, suppressedForOverheadTest = false))
    }

    @Test
    fun overheadSuppressionKeepsSinkUninstalledEvenWhenEnabled() {
        assertNull(slockMarkdownNodeSinkFor(true, suppressedForOverheadTest = true))
        assertNull(slockMarkdownNodeSinkFor(false, suppressedForOverheadTest = true))
    }

    @Test
    fun paragraphSubPhasesAreMutuallyExclusiveSlicesOfExclusiveTime() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)

        timing.enterNode("PARAGRAPH")
        clock += 500.microseconds                         // dispatch/pre work
        timing.measureParagraphAnnotate { clock += 2_000.microseconds }
        timing.enterParagraphText()
        clock += 3_000.microseconds
        timing.exitParagraphText()
        clock += 500.microseconds                         // post work
        timing.exitNode()
        timing.finishRender(renderNodesUs = 6_000)

        val detail = sink.lines.single { "phase=markdown.node.render" in it }
        assertEquals("6000", field(detail, "exclusiveUs"))
        assertEquals("2000", field(detail, "annotateUs"))
        assertEquals("3000", field(detail, "textCallUs"))
        assertEquals("1000", field(detail, "otherUs"))    // exclusive - annotate - textCall
        val summary = sink.lines.single { "phase=markdown.node.summary" in it }
        assertEquals("2000", field(summary, "totalAnnotateUs"))
        assertEquals("3000", field(summary, "totalTextCallUs"))
    }
}
