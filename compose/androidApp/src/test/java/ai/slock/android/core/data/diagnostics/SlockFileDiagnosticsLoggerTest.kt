package ai.slock.android.core.data.diagnostics

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockFileDiagnosticsLoggerTest {
    @Test
    fun logFilesIncludesRecentHistoryAndSkipsOldFiles() {
        val dir = Files.createTempDirectory("slock-diagnostics-history").toFile()
        val now = 1_800_000_000_000L
        val active = dir.writeLog("slock-diagnostics.jsonl", now - TimeUnit.HOURS.toMillis(1))
        val rotated = dir.writeLog("slock-diagnostics.jsonl.1", now - TimeUnit.HOURS.toMillis(12))
        val legacy = dir.writeLog("slock-diagnostics.log", now - TimeUnit.HOURS.toMillis(36))
        val old = dir.writeLog("slock-diagnostics.jsonl.2", now - TimeUnit.DAYS.toMillis(3))
        dir.writeLog("unrelated.jsonl", now)

        val files = SlockFileDiagnosticsLogger.logFilesIn(dir, now)

        assertEquals(listOf(active.name, rotated.name, legacy.name), files.map { it.name })
        assertFalse(files.any { it.name == old.name })
    }

    @Test
    fun logFilesCapsExportFileCount() {
        val dir = Files.createTempDirectory("slock-diagnostics-history-cap").toFile()
        val now = 1_800_000_000_000L
        repeat(16) { index ->
            dir.writeLog("slock-diagnostics.jsonl.$index", now - index)
        }

        val files = SlockFileDiagnosticsLogger.logFilesIn(dir, now)

        assertEquals(12, files.size)
        assertTrue(files.first().lastModified() >= files.last().lastModified())
    }

    private fun java.io.File.writeLog(name: String, modifiedAt: Long): java.io.File =
        resolve(name).apply {
            writeText("""{"message":"$name"}""" + "\n")
            setLastModified(modifiedAt)
        }
}
