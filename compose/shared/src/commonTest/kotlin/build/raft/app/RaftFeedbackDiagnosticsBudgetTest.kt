package build.raft.app

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
    fun fitsEverythingUnderTheBudget() {
        val files = listOf(shard("launch-2", 0, 200 * kib, 20), shard("launch-1", 0, 100 * kib, 10))
        val picks = RaftFeedbackDiagnosticsBudget.select(files)
        assertEquals(listOf("launch-2/raft-diagnostics.jsonl", "launch-1/raft-diagnostics.jsonl"), picks.map { it.file.path })
        assertTrue(picks.none { it.truncated })
    }

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
    fun newestLaunchFillsTheBudgetNewestShardFirstWhenAlone() {
        val files = (0 until 5).map { index -> shard("launch-9", index, 300 * kib, 100L - index) }
        val picks = RaftFeedbackDiagnosticsBudget.select(files)
        assertEquals(
            listOf("launch-9/raft-diagnostics.jsonl", "launch-9/raft-diagnostics.jsonl.1", "launch-9/raft-diagnostics.jsonl.2", "launch-9/raft-diagnostics.jsonl.3"),
            picks.map { it.file.path }
        )
        assertEquals(listOf(false, false, false, true), picks.map { it.truncated })
        assertEquals(mib, picks.sumOf { it.includedBytes })
    }

    @Test
    fun smallLaunchesLeaveRoomForEarlierShards() {
        val files =
            listOf(
                shard("launch-3", 0, 100 * kib, 300),
                shard("launch-2", 0, 100 * kib, 200),
                shard("launch-2", 1, 400 * kib, 199),
                shard("launch-1", 0, 900 * kib, 100),
            )
        val picks = RaftFeedbackDiagnosticsBudget.select(files)
        assertEquals(
            listOf(
                "launch-3/raft-diagnostics.jsonl",
                "launch-2/raft-diagnostics.jsonl",
                "launch-1/raft-diagnostics.jsonl",
            ),
            picks.map { it.file.path }
        )
        assertEquals(824 * kib, picks.last().includedBytes)
        assertEquals(mib, picks.sumOf { it.includedBytes })
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

    @Test
    fun describeListsIncludedAndOmittedShards() {
        val files = listOf(shard("launch-2", 0, 2 * mib, 20), shard("launch-1", 0, 10 * kib, 10))
        val picks = RaftFeedbackDiagnosticsBudget.select(files)
        val text = RaftFeedbackDiagnosticsBudget.describe(files, picks, RaftFeedbackDiagnosticsBudget.DefaultBudgetBytes) { it.path }
        assertTrue(text.contains("included launch-2/raft-diagnostics.jsonl tail"), text)
        assertTrue(text.contains("included launch-1/raft-diagnostics.jsonl 10240 bytes"), text)
    }
}
