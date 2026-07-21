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
        val active = currentSession.writeLog("raft-diagnostics.jsonl", now - TimeUnit.HOURS.toMillis(1))
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
    fun logFilesAndArchiveNamesKeepOneReleaseLegacyCompatibility() {
        val dir = Files.createTempDirectory("raft-diagnostics-dual-read").toFile()
        val now = 1_800_000_000_000L
        val current = dir.writeLog("raft-diagnostics.jsonl", now)
        val legacy = dir.writeLog("slock-diagnostics.jsonl.1", now - 1)

        assertEquals(listOf(current, legacy), SlockGeneralLogRing.logFilesIn(dir, now))
        assertEquals("raft-diagnostics.jsonl.1", SlockGeneralLogRing.normalizeArchiveLogName(legacy.name))
        assertEquals("raft-diagnostics.jsonl", SlockGeneralLogRing.normalizeArchiveLogName("slock-diagnostics.log"))
    }

    @Test
    fun mergedRootsAlwaysReserveCapacityForCurrentRaftLogsBeforeLegacyLogs() {
        val dir = Files.createTempDirectory("raft-diagnostics-root-priority").toFile()
        val current = (1..3).map { dir.writeLog("raft-$it.jsonl", 100L + it) }
        val legacy = (1..3).map { dir.writeLog("legacy-$it.jsonl", 200L + it) }

        assertEquals(current + legacy.take(1), mergeDiagnosticsLogFiles(current, legacy, maxFiles = 4))
    }

    @Test
    fun equalMtimeSelectionUsesCanonicalPathTieBreak() {
        val dir = Files.createTempDirectory("raft-diagnostics-equal-mtime").toFile()
        val now = 1_800_000_000_000L
        val session = dir.resolve("launch-$now-current").apply { mkdirs() }
        val second = session.writeLog("raft-diagnostics.jsonl.2", now)
        val first = session.writeLog("raft-diagnostics.jsonl.1", now)

        assertEquals(listOf(first, second), SlockGeneralLogRing.logFilesIn(dir, now))
    }

    @Test
    fun equalMtimeCurrentAndLegacyRootsKeepCurrentCapacityAndStableCollisionSuffixes() {
        val parent = Files.createTempDirectory("raft-diagnostics-cross-root-equal-mtime").toFile()
        val currentRoot = parent.resolve("raft_diagnostics_logs").apply { mkdirs() }
        val legacyRoot = parent.resolve("slock_diagnostics_logs").apply { mkdirs() }
        val now = 1_800_000_000_000L
        val current = currentRoot.writeLog("raft-diagnostics.jsonl", now)
        val legacy = legacyRoot.writeLog("slock-diagnostics.jsonl", now)

        val merged = mergeDiagnosticsLogFiles(
            SlockGeneralLogRing.logFilesIn(currentRoot, now),
            SlockGeneralLogRing.logFilesIn(legacyRoot, now),
            maxFiles = 2,
        )
        assertEquals(listOf(current, legacy), merged)

        val seen = mutableSetOf<String>()
        val normalizedNames = merged.map { file ->
            uniqueDiagnosticsZipEntryName(
                "app_logs/${SlockGeneralLogRing.normalizeArchiveLogName(file.name)}",
                seen,
            )
        }
        assertEquals(
            listOf("app_logs/raft-diagnostics.jsonl", "app_logs/raft-diagnostics-2.jsonl"),
            normalizedNames,
        )
    }

    @Test
    fun normalizedLegacyEntriesUseStableSuffixesInsteadOfOverwritingCurrentBytes() {
        val seen = mutableSetOf<String>()
        val entry = "app_logs/launch-1/raft-diagnostics.jsonl"

        assertEquals(entry, uniqueDiagnosticsZipEntryName(entry, seen))
        assertEquals(
            "app_logs/launch-1/raft-diagnostics-2.jsonl",
            uniqueDiagnosticsZipEntryName(entry, seen),
        )
        assertEquals(
            "app_logs/launch-1/raft-diagnostics-3.jsonl",
            uniqueDiagnosticsZipEntryName(entry, seen),
        )
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
