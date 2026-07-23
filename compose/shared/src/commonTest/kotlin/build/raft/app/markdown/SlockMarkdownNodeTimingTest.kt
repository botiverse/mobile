package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.compose.SlockMarkdownNodeTraceSink
import com.tencent.kuiklybase.markdown.compose.SlockMarkdownRenderTiming
import com.tencent.kuiklybase.markdown.compose.SlockNodeDetailCapPerRender
import com.tencent.kuiklybase.markdown.compose.slockMarkdownNodeTypeKey
import kotlin.test.Test
import kotlin.test.assertEquals
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

        timing.enterNode("paragraph")
        clock += 1_000.microseconds
        timing.enterNode("text")
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
            timing.enterNode("eol")
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
            timing.enterNode("paragraph")
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
    fun abortedFramesAreDrainedAndReported() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)

        timing.enterNode("block_quote")
        clock += 1_000.microseconds
        timing.enterNode("paragraph")
        clock += 500.microseconds
        // No exits: simulates a pass that still reaches finishRender.
        timing.finishRender(renderNodesUs = 1_500)

        val reconcile = sink.lines.single { "phase=markdown.node.reconcile" in it }
        assertEquals("2", field(reconcile, "abortedFrames"))
        // Both frames were closed: their exclusive time is accounted for.
        assertEquals("1500", field(reconcile, "exclusiveSumUs"))
    }

    @Test
    fun remainderCapturesUnattributedRenderTime() {
        val sink = RecordingSink()
        val clock = TestTimeSource()
        val timing = SlockMarkdownRenderTiming(sink, clock)

        timing.enterNode("paragraph")
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
        timing.enterNode("paragraph")
        clock += 1_000.microseconds
        timing.exitNode()
        timing.finishRender(renderNodesUs = 1_000)

        val allowedKeys = setOf(
            "phase", "renderSeq", "type", "exclusiveUs", "totalUs", "depth",
            "topIndex", "count", "totalExclusiveUs", "maxUs", "belowThreshold",
            "renderNodesUs", "exclusiveSumUs", "remainderUs", "detailDropped",
            "abortedFrames"
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
    fun nodeTypeVocabularyIsClosedAndUnknownCollapsesToOther() {
        assertEquals("paragraph", slockMarkdownNodeTypeKey("PARAGRAPH"))
        assertEquals("heading", slockMarkdownNodeTypeKey("ATX_3"))
        assertEquals("setext_heading", slockMarkdownNodeTypeKey("SETEXT_2"))
        assertEquals("table", slockMarkdownNodeTypeKey("TABLE"))
        assertEquals("other", slockMarkdownNodeTypeKey("SOME_CUSTOM_NODE"))
        assertEquals("other", slockMarkdownNodeTypeKey("MESSAGE_BODY_LEAK"))
    }
}
