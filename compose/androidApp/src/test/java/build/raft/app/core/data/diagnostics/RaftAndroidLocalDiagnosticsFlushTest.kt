package build.raft.app.core.data.diagnostics

import android.app.Application
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end regression tests for Android feedback archive generation after historical dropped
 * log entries (`f4a05254` / `48cefb2a`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftAndroidLocalDiagnosticsFlushTest {
    private val archivesToClean = mutableListOf<File>()

    @Before
    fun setUp() {
        RaftGeneralLogRing.resetForTests()
        RaftAuthCriticalJournal.resetForTests()
    }

    @After
    fun tearDown() {
        archivesToClean.forEach { file -> runCatching { file.delete() } }
        archivesToClean.clear()
        RaftGeneralLogRing.resetForTests()
        RaftAuthCriticalJournal.resetForTests()
    }

    @Test
    fun prepareArchiveAndRecentExportSucceedAfterHistoricalDroppedEntries() {
        val app = RuntimeEnvironment.getApplication()
        val preInitEntry =
            SanitizedDiagnosticLog(
                level = "warn",
                tag = "SlockAuth",
                message = "refresh_failed status=401 pre_init_drop_marker",
                timestampMillis = System.currentTimeMillis(),
                threadName = "test",
                sequence = 1L,
                droppedBeforeDispatch = 0L,
            )

        // Simulate logs recorded before init() completed (incrementing droppedEntries on both sinks).
        RaftGeneralLogRing.record(preInitEntry)
        RaftAuthCriticalJournal.record(preInitEntry, "refresh_failed")
        assertTrue("general ring flush must stay healthy after dropped entry", RaftGeneralLogRing.flush())
        assertTrue("auth journal flush must stay healthy after dropped entry", RaftAuthCriticalJournal.flush())
        assertTrue(RaftGeneralLogRing.droppedEntryCount() >= 1L)
        assertTrue(RaftAuthCriticalJournal.droppedEntryCount() >= 1L)

        // Now initialize storage and record a real diagnostic entry.
        RaftGeneralLogRing.init(app)
        RaftAuthCriticalJournal.init(app)
        RaftGeneralLogRing.record(
            preInitEntry.copy(
                tag = "SlockDiagnostics",
                message = "post_init_recoverable_log_line",
                sequence = 2L,
            )
        )
        assertTrue(RaftGeneralLogRing.flush())
        assertTrue(RaftAuthCriticalJournal.flush())

        val archive =
            RaftAndroidLocalDiagnostics.prepareDiagnosticsFeedbackArchive(
                context = app,
                summaryText = "Raft diagnostics\n\nEnvironment\nApp: Raft",
            )
        archivesToClean += archive
        assertTrue("feedback zip must exist and be non-empty", archive.isFile && archive.length() > 0L)

        ZipFile(archive).use { zip ->
            val summaryEntry = requireNotNull(zip.getEntry("raft-diagnostics-summary.txt")) {
                "summary entry missing from archive"
            }
            val summaryText =
                zip.getInputStream(summaryEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            assertTrue(summaryText.contains("Diagnostics pipeline note: flushed=true"))
            assertTrue(summaryText.contains("generalDropped="))
            assertTrue(summaryText.contains("authDropped="))

            val recentMemoryEntry = requireNotNull(zip.getEntry("app_logs/recent-memory.jsonl")) {
                "recent-memory.jsonl missing from archive"
            }
            val recentMemoryText =
                zip.getInputStream(recentMemoryEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            assertTrue(recentMemoryText.contains("post_init_recoverable_log_line"))
        }

        val recentText = RaftAndroidLocalDiagnostics.exportRecentDiagnosticsText(app, maxChars = 16_000)
        assertFalse(recentText.contains("Diagnostics snapshot unavailable"))
        assertTrue(recentText.contains("post_init_recoverable_log_line"))
    }
}
