package build.raft.app.core.data.diagnostics

import android.app.Application
import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftDiagnosticsAsyncInitTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RaftGeneralLogRing.resetForTests()
        RaftAuthCriticalJournal.resetForTests()
        RaftGeneralLogRing.logRoot(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        RaftGeneralLogRing.resetForTests()
        RaftAuthCriticalJournal.resetForTests()
        RaftGeneralLogRing.logRoot(context).deleteRecursively()
    }

    @Test
    fun generalLogRingInitRunsOnWriterThreadAndOrdersEarlyWritesAndPrune() {
        val root = RaftGeneralLogRing.logRoot(context).apply { mkdirs() }
        repeat(6) { index ->
            val oldSession = File(root, "launch-170000000000$index-old$index").apply { mkdirs() }
            File(oldSession, "raft-diagnostics.jsonl").writeText("""{"message":"old-$index"}""" + "\n")
        }
        val callingThread = Thread.currentThread().name
        val initThread = AtomicReference<String?>(null)
        RaftGeneralLogRing.onInitTaskExecutedForTests = { threadName -> initThread.set(threadName) }

        RaftGeneralLogRing.init(context)
        RaftGeneralLogRing.record(
            SanitizedDiagnosticLog(
                level = "info",
                tag = "ColdStartTest",
                message = "early_line_after_init",
                timestampMillis = System.currentTimeMillis(),
                threadName = callingThread,
                sequence = 1L,
                droppedBeforeDispatch = 0L,
            )
        )

        assertTrue(RaftGeneralLogRing.flush())
        assertEquals("SlockDiagnosticsWriter", initThread.get())
        assertNotEquals(callingThread, initThread.get())
        val remainingSessions =
            root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("launch-") }
        assertTrue("expected old sessions to be pruned to <= 5, got ${remainingSessions.size}", remainingSessions.size <= 5)
        assertTrue(RaftGeneralLogRing.hasFiles(context))
        assertTrue(RaftGeneralLogRing.exportRecentText(context).contains("early_line_after_init"))

        RaftGeneralLogRing.clearFiles(context)
        assertFalse(RaftGeneralLogRing.hasFiles(context))
    }

    @Test
    fun authCriticalJournalInitRunsOnWriterThreadAndOrdersEarlyRecords() {
        val callingThread = Thread.currentThread().name
        val initThread = AtomicReference<String?>(null)
        RaftAuthCriticalJournal.onInitTaskExecutedForTests = { threadName -> initThread.set(threadName) }

        RaftAuthCriticalJournal.init(context)
        RaftAuthCriticalJournal.record(
            SanitizedDiagnosticLog(
                level = "warn",
                tag = "SlockAuth",
                message = "refresh_failed status=401 source=cold_start_test",
                timestampMillis = System.currentTimeMillis(),
                threadName = callingThread,
                sequence = 2L,
                droppedBeforeDispatch = 0L,
            ),
            event = "refresh_failed",
        )

        val entries = RaftAuthCriticalJournal.recentEntries(context)
        assertTrue(RaftAuthCriticalJournal.flush())
        assertEquals("SlockAuthJournalWriter", initThread.get())
        assertNotEquals(callingThread, initThread.get())
        assertEquals(1, entries.size)
        assertEquals("refresh_failed", entries.single().event)
        assertTrue(entries.single().message.contains("source=cold_start_test"))

        RaftAuthCriticalJournal.clearFiles(context)
        assertFalse(RaftAuthCriticalJournal.hasFiles(context))
    }
}
