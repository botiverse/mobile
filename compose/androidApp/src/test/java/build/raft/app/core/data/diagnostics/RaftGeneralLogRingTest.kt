package build.raft.app.core.data.diagnostics

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftGeneralLogRingTest {
    @Test
    fun logFilesIncludesRecentHistoryAndSkipsOldFiles() {
        val dir = Files.createTempDirectory("slock-diagnostics-history").toFile()
        val now = 1_800_000_000_000L
        val currentSession = dir.resolve("launch-1800000000000-current").apply { mkdirs() }
        val previousSession = dir.resolve("launch-1799990000000-previous").apply { mkdirs() }
        val active = currentSession.writeLog("raft-diagnostics.jsonl", now - TimeUnit.HOURS.toMillis(1))
        val rotated = previousSession.writeLog("raft-diagnostics.jsonl.1", now - TimeUnit.HOURS.toMillis(12))
        val flat = dir.writeLog("raft-diagnostics.jsonl.2", now - TimeUnit.HOURS.toMillis(36))
        val old = dir.writeLog("raft-diagnostics.jsonl.3", now - TimeUnit.DAYS.toMillis(3))
        dir.writeLog("unrelated.jsonl", now)

        val files = RaftGeneralLogRing.logFilesIn(dir, now)

        assertEquals(listOf(active, rotated, flat), files)
        assertFalse(files.any { it.name == old.name })
    }

    @Test
    fun logFilesCapsExportFileCount() {
        val dir = Files.createTempDirectory("slock-diagnostics-history-cap").toFile()
        val now = 1_800_000_000_000L
        repeat(7) { sessionIndex ->
            val session = dir.resolve("launch-${now - sessionIndex}-$sessionIndex").apply { mkdirs() }
            repeat(6) { fileIndex ->
                session.writeLog("raft-diagnostics.jsonl.$fileIndex", now - sessionIndex * 100 - fileIndex)
            }
        }

        val files = RaftGeneralLogRing.logFilesIn(dir, now)

        assertEquals(30, files.size)
        assertTrue(files.first().lastModified() >= files.last().lastModified())
        assertEquals(5, files.mapNotNull { it.parentFile?.name }.distinct().size)
    }

    @Test
    fun logFilesFallsBackToCurrentFlatFilesWhenNoLaunchSessionsExist() {
        val dir = Files.createTempDirectory("raft-diagnostics-flat").toFile()
        val now = 1_800_000_000_000L
        val active = dir.writeLog("raft-diagnostics.jsonl", now - 1)
        val rotated = dir.writeLog("raft-diagnostics.jsonl.1", now - 2)

        assertEquals(listOf(active, rotated), RaftGeneralLogRing.logFilesIn(dir, now))
    }

    @Test
    fun equalMtimeSelectionUsesCanonicalPathTieBreak() {
        val dir = Files.createTempDirectory("raft-diagnostics-equal-mtime").toFile()
        val now = 1_800_000_000_000L
        val session = dir.resolve("launch-$now-current").apply { mkdirs() }
        val second = session.writeLog("raft-diagnostics.jsonl.2", now)
        val first = session.writeLog("raft-diagnostics.jsonl.1", now)

        assertEquals(listOf(first, second), RaftGeneralLogRing.logFilesIn(dir, now))
    }

    @Test
    fun duplicateCurrentEntriesUseStableSuffixesInsteadOfOverwritingBytes() {
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
        val dir = Files.createTempDirectory("raft-diagnostics-relative").toFile()
        val file = dir.resolve("launch-1800000000000-a/raft-diagnostics.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{}\n")
        }

        assertEquals(
            "launch-1800000000000-a/raft-diagnostics.jsonl",
            RaftGeneralLogRing.relativeLogPathWithin(dir, file)
        )
    }

    @Test
    fun relativeLogPathWithinRejectsSiblingRootsInsteadOfEmittingDotDot() {
        val parent = Files.createTempDirectory("raft-diagnostics-path-safety").toFile()
        val currentRoot = parent.resolve("raft_diagnostics_logs").apply { mkdirs() }
        val outsideFile = parent.resolve("outside_logs/launch-outside/raft-diagnostics.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("{}\n")
        }

        assertEquals(null, RaftGeneralLogRing.relativeLogPathWithin(currentRoot, outsideFile))
        assertFalse(RaftGeneralLogRing.relativeLogPathWithin(currentRoot, outsideFile).orEmpty().contains(".."))
    }

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
    fun zipEntryGuardRejectsTraversalAbsoluteBackslashEmptyAndOldBrandSegments() {
        listOf(
            "app_logs/../raft-diagnostics.jsonl",
            "/app_logs/raft-diagnostics.jsonl",
            "app_logs\\raft-diagnostics.jsonl",
            "app_logs//raft-diagnostics.jsonl",
            "app_logs/./raft-diagnostics.jsonl",
            "app_logs/slock_diagnostics_logs/raft-diagnostics.jsonl",
        ).forEach { unsafe ->
            assertTrue(runCatching { requireSafeDiagnosticsZipEntryName(unsafe) }.isFailure)
        }
        assertEquals(
            "app_logs/launch-1/raft-diagnostics.jsonl",
            requireSafeDiagnosticsZipEntryName("app_logs/launch-1/raft-diagnostics.jsonl"),
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
