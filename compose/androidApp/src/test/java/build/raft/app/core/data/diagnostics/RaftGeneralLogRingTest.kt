package build.raft.app.core.data.diagnostics

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftGeneralLogRingTest {
    @Test
    fun relativeLogPathWithinRejectsPrefixConfusableAndSymlinkEscapes() {
        val parent = Files.createTempDirectory("raft-diagnostics-prefix-safety").toFile()
        val root = parent.resolve("root-a").apply { mkdirs() }
        val confusable = parent.resolve("root-ab/raft-diagnostics.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{}\n")
        }
        val outside = parent.resolve("outside/raft-diagnostics.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{}\n")
        }
        val linkedDirectory = root.resolve("launch-linked")
        Files.createSymbolicLink(linkedDirectory.toPath(), requireNotNull(outside.parentFile).toPath())

        assertEquals(null, RaftGeneralLogRing.relativeLogPathWithin(root, confusable))
        assertEquals(
            null,
            RaftGeneralLogRing.relativeLogPathWithin(root, linkedDirectory.resolve(outside.name)),
        )
    }

    @Test
    fun flushReturnsTrueAfterDroppedEntryAndStatusNoteReflectsCounts() {
        try {
            RaftGeneralLogRing.resetForTests()
            // Record before init() so append() increments droppedEntries without touching disk.
            RaftGeneralLogRing.record(
                SanitizedDiagnosticLog(
                    level = "info",
                    tag = "FlushTest",
                    message = "pre_init_log",
                    timestampMillis = 1_700_000_000_000L,
                    threadName = "test",
                    sequence = 1L,
                    droppedBeforeDispatch = 0L,
                )
            )
            assertTrue("flush must report queue drain rather than permanent poisoning", RaftGeneralLogRing.flush())
            assertTrue("dropped counter remains observable", RaftGeneralLogRing.droppedEntryCount() >= 1L)
        } finally {
            RaftGeneralLogRing.resetForTests()
        }

        val clean =
            appendDiagnosticsFlushStatusNote(
                summaryText = "Raft diagnostics",
                flushed = true,
                dispatchDropped = 0L,
                dispatchFailed = 0L,
                generalDropped = 0L,
                authDropped = 0L,
            )
        assertEquals("Raft diagnostics", clean)

        val annotated =
            appendDiagnosticsFlushStatusNote(
                summaryText = "Raft diagnostics",
                flushed = false,
                dispatchDropped = 2L,
                dispatchFailed = 1L,
                generalDropped = 3L,
                authDropped = 4L,
            )
        assertTrue(annotated.contains("flushed=false"))
        assertTrue(annotated.contains("dispatchDropped=2"))
        assertTrue(annotated.contains("dispatchFailed=1"))
        assertTrue(annotated.contains("generalDropped=3"))
        assertTrue(annotated.contains("authDropped=4"))
    }
}
