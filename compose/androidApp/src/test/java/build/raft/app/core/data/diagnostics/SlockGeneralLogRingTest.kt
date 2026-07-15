package build.raft.app.core.data.diagnostics

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockGeneralLogRingTest {
    @Test
    fun logFilesIncludesRecentHistoryAndSkipsOldFiles() {
        val dir = Files.createTempDirectory("slock-diagnostics-history").toFile()
        val now = 1_800_000_000_000L
        val currentSession = dir.resolve("launch-1800000000000-current").apply { mkdirs() }
        val previousSession = dir.resolve("launch-1799990000000-previous").apply { mkdirs() }
        val active = currentSession.writeLog("slock-diagnostics.jsonl", now - TimeUnit.HOURS.toMillis(1))
        val rotated = previousSession.writeLog("slock-diagnostics.jsonl.1", now - TimeUnit.HOURS.toMillis(12))
        val legacy = dir.writeLog("slock-diagnostics.log", now - TimeUnit.HOURS.toMillis(36))
        val old = dir.writeLog("slock-diagnostics.jsonl.2", now - TimeUnit.DAYS.toMillis(3))
        dir.writeLog("unrelated.jsonl", now)

        val files = SlockGeneralLogRing.logFilesIn(dir, now)

        assertEquals(listOf(active, rotated, legacy), files)
        assertFalse(files.any { it.name == old.name })
    }

    @Test
    fun logFilesCapsExportFileCount() {
        val dir = Files.createTempDirectory("slock-diagnostics-history-cap").toFile()
        val now = 1_800_000_000_000L
        repeat(7) { sessionIndex ->
            val session = dir.resolve("launch-${now - sessionIndex}-$sessionIndex").apply { mkdirs() }
            repeat(6) { fileIndex ->
                session.writeLog("slock-diagnostics.jsonl.$fileIndex", now - sessionIndex * 100 - fileIndex)
            }
        }

        val files = SlockGeneralLogRing.logFilesIn(dir, now)

        assertEquals(30, files.size)
        assertTrue(files.first().lastModified() >= files.last().lastModified())
        assertEquals(5, files.mapNotNull { it.parentFile?.name }.distinct().size)
    }

    @Test
    fun logFilesFallsBackToLegacyFlatFilesWhenNoLaunchSessionsExist() {
        val dir = Files.createTempDirectory("slock-diagnostics-legacy").toFile()
        val now = 1_800_000_000_000L
        val active = dir.writeLog("slock-diagnostics.jsonl", now - 1)
        val legacy = dir.writeLog("slock-diagnostics.log", now - 2)

        assertEquals(listOf(active, legacy), SlockGeneralLogRing.logFilesIn(dir, now))
    }

    @Test
    fun relativeLogPathKeepsLaunchIdentity() {
        val dir = Files.createTempDirectory("slock-diagnostics-relative").toFile()
        val file = dir.resolve("launch-1800000000000-a/slock-diagnostics.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{}\n")
        }

        assertEquals(
            "launch-1800000000000-a/slock-diagnostics.jsonl",
            SlockGeneralLogRing.relativeLogPath(dir, file)
        )
    }

    @Test
    fun retentionLabelStatesCapacityAndAgeAsSeparateBounds() {
        assertEquals(
            "Files: 30 (latest 5 launches, up to 30 MiB, max age 2 days)",
            diagnosticsRetentionLabel(30)
        )
    }

    private fun java.io.File.writeLog(name: String, modifiedAt: Long): java.io.File =
        resolve(name).apply {
            writeText("""{"message":"$name"}""" + "\n")
            setLastModified(modifiedAt)
        }
}
