package build.raft.app

import build.raft.app.settings.RaftFeedbackDiagnosticsBudget
import build.raft.app.settings.RaftFeedbackLogFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftFeedbackDiagnosticsBudgetTest {
    private val kib = 1024L
    private val mib = 1024L * kib

    private fun shard(launch: String, index: Int, size: Long, modified: Long) =
        RaftFeedbackLogFile(
            path = "$launch/raft-diagnostics.jsonl${if (index == 0) "" else ".$index"}",
            launchId = launch,
            sizeBytes = size,
            modifiedMillis = modified,
        )

    @Test
    fun newestLaunchFirstWithinOneMiBAndTheLaunchBeforeKeepsItsTail() {
        // The sample report's shape: 1 MiB shards, four launches, 31 shards / 29 MB.
        val files =
            (1..4).flatMap { launch ->
                (0 until 8).map { index -> shard("launch-$launch", index, mib, launch * 100L - index) }
            }
        val picks = RaftFeedbackDiagnosticsBudget.select(files)

        assertTrue(picks.sumOf { it.includedBytes } <= RaftFeedbackDiagnosticsBudget.DefaultBudgetBytes)
        // The current launch's active shard (as a 768 KiB tail), then the end of the launch before.
        assertEquals(listOf("launch-4/raft-diagnostics.jsonl", "launch-3/raft-diagnostics.jsonl"), picks.map { it.file.path })
        assertEquals(768 * kib, picks[0].includedBytes)
        assertEquals(256 * kib, picks[1].includedBytes)
        assertTrue(picks.all { it.truncated })
    }

    @Test
    fun rotationOrderAndLaunchIdWinOverUnreliableMtimes() {
        // Restored / unzipped files: every mtime equal or reversed.
        val files =
            listOf(
                shard("launch-1794189741790-1b9db220", 3, 600 * kib, 5),
                shard("launch-1794189741790-1b9db220", 0, 300 * kib, 1),
                shard("launch-1794164235381-d13cbf62", 0, 200 * kib, 9),
            )
        val picks = RaftFeedbackDiagnosticsBudget.select(files)
        assertEquals(
            listOf(
                "launch-1794189741790-1b9db220/raft-diagnostics.jsonl",
                "launch-1794189741790-1b9db220/raft-diagnostics.jsonl.3",
                "launch-1794164235381-d13cbf62/raft-diagnostics.jsonl",
            ),
            picks.map { it.file.path }
        )
        assertEquals(listOf(false, true, false), picks.map { it.truncated })
    }
}
