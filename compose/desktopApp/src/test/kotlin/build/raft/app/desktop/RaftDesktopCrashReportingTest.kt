package build.raft.app.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import build.raft.app.RaftDesktopFileLog
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Crash recording (artin 11-03: the app froze on opening a reply and quit, leaving nothing): an
 * uncaught throwable on any thread and a Compose window failure reach the log file with their stack
 * before the previous handler runs, and a session that never shut down is reported at next start.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RaftDesktopCrashReportingTest {
    private lateinit var originalHandler: Thread.UncaughtExceptionHandler

    @BeforeTest fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler() ?: Thread.UncaughtExceptionHandler { _, _ -> }
        RaftDesktopFileLog.install(logDir)
    }

    @AfterTest fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    private fun logText(): String {
        RaftDesktopFileLog.flush()
        return File(RaftDesktopFileLog.directory!!, "raft.log").readText()
    }

    @Test fun `an uncaught throwable is logged with its stack, then the previous handler runs`() {
        val seen = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> synchronized(seen) { seen += t } }
        RaftDesktopCrashReporting.installUncaughtHandler()
        // Installing twice does not stack a second wrapper.
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        RaftDesktopCrashReporting.installUncaughtHandler()
        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())

        val boom = IllegalStateException("thread boom marker-7731")
        val thread = Thread({ throw boom }, "raft-test-crasher")
        thread.start()
        thread.join()

        assertEquals(listOf<Throwable>(boom), synchronized(seen) { seen.toList() })
        val text = logText()
        assertTrue(text.contains("crash source=uncaught thread=raft-test-crasher type=java.lang.IllegalStateException"), text)
        assertTrue(text.contains("java.lang.IllegalStateException: thread boom marker-7731"), text)
        assertTrue(text.contains("\tat build.raft.app.desktop.RaftDesktopCrashReportingTest"), text)
    }

    @Test fun `an out-of-memory error also logs the heap`() {
        RaftDesktopCrashReporting.logFatal("uncaught", Thread.currentThread(), OutOfMemoryError("Java heap space marker-5512"))
        val text = logText()
        assertTrue(text.contains("out_of_memory heapUsedMb="), text)
        assertTrue(text.contains("java.lang.OutOfMemoryError: Java heap space marker-5512"), text)
    }

    @Test fun `a compose window failure is logged, then handed to the previous window handler once`() {
        val handled = mutableListOf<Throwable>()
        val handler = RaftDesktopCrashReporting.wrapWindowExceptionHandler(WindowExceptionHandler { handled += it })
        val failure = IllegalArgumentException("composition marker-9043")
        handler.onException(failure)
        assertEquals(listOf<Throwable>(failure), handled)
        // The same throwable reaching the uncaught handler afterwards is not logged twice.
        RaftDesktopCrashReporting.logFatal("uncaught", Thread.currentThread(), failure)
        val text = logText()
        assertEquals(1, Regex("crash source=compose_window .*IllegalArgumentException").findAll(text).count(), text)
        assertEquals(0, Regex("crash source=uncaught .*IllegalArgumentException").findAll(text).count(), text)
        // No previous handler: rethrown after logging (Compose's own behaviour without a handler).
        val bare = RaftDesktopCrashReporting.wrapWindowExceptionHandler(null)
        assertFailsWith<IllegalStateException> { bare.onException(IllegalStateException("bare")) }
    }

    @Test fun `a session that never shut down is reported at the next start`() {
        val dir = Files.createTempDirectory("raft-session").toFile()
        try {
            assertNull(RaftDesktopCrashReporting.checkPreviousSession(dir, now = 1_000L))
            // The marker is still there: this "session" did not shut down.
            val previous = RaftDesktopCrashReporting.checkPreviousSession(dir, now = 2_000L)
            assertNotNull(previous)
            assertTrue(previous.startsWith("startedAtMillis=1000 "), previous)
            assertTrue(logText().contains("previous_session_unclean_exit startedAtMillis=1000"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        val logDir: File by lazy { Files.createTempDirectory("raft-crash-log").toFile().apply { deleteOnExit() } }
    }

    @Test
    fun aLateEventOnADisposedSceneIsRecoverable() {
        assertTrue(RaftDesktopCrashReporting.isRecoverableDisposedSceneError(IllegalStateException("RootNodeOwner is already disposed")))
        assertTrue(
            RaftDesktopCrashReporting.isRecoverableDisposedSceneError(
                RuntimeException("wrapped", IllegalStateException("RootNodeOwner is already disposed"))
            )
        )
        assertFalse(RaftDesktopCrashReporting.isRecoverableDisposedSceneError(IllegalStateException("something else")))
        assertFalse(RaftDesktopCrashReporting.isRecoverableDisposedSceneError(OutOfMemoryError()))
    }
}
