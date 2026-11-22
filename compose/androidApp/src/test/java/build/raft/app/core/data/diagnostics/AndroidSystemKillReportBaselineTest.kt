package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-launch baseline ([resolveSystemKillBaseline]) does not move when its write fails. */
class AndroidSystemKillReportBaselineTest {
    private val installedAt = 1_000_000L
    private val mainProcess = "build.raft.app"

    /** A preferences file whose commit can fail: a failed commit leaves nothing on disk. */
    private class Disk(var failCommits: Boolean) {
        var stored: Long? = null
        var failures = 0

        fun resolve(updatedAt: Long?, now: Long): Long =
            resolveSystemKillBaseline(
                storedMillis = stored,
                packageUpdatedMillis = updatedAt,
                nowMillis = now,
                persist = { value -> if (failCommits) false else true.also { stored = value } },
                onPersistFailed = { failures++ },
            )
    }

    private fun visibleKill(at: Long) =
        SystemKillExit(
            pid = 42,
            timestampMillis = at,
            reasonCode = ApplicationExitInfo.REASON_LOW_MEMORY,
            status = 0,
            importance = IMPORTANCE_VISIBLE,
            pssKb = 0,
            rssKb = 0,
            build = null,
            processName = mainProcess,
        )

    private fun planned(exit: SystemKillExit, cutoff: Long, now: Long) =
        planSystemKillReports(
            exits = listOf(exit),
            snapshots = emptyList(),
            cutoffMillis = cutoff,
            watermarkMillis = 0L,
            recentReportMillis = emptyList(),
            nowMillis = now,
            perLaunchCap = 3,
            dailyCap = 5,
            mainProcessName = mainProcess,
        ).map { it.exit.timestampMillis }

    @Test
    fun aFailedFirstWriteThenARestartKeepsTheExitInBetween() {
        val disk = Disk(failCommits = true)
        val firstLaunch = installedAt + 60_000L
        val first = disk.resolve(updatedAt = installedAt, now = firstLaunch)
        assertEquals(installedAt, first)
        assertEquals(1, disk.failures)
        assertEquals(null, disk.stored)

        // The first process is killed after its failed write; the app is opened again later.
        val kill = firstLaunch + 30_000L
        val secondLaunch = kill + 60_000L
        disk.failCommits = false
        val second = disk.resolve(updatedAt = installedAt, now = secondLaunch)

        assertEquals(first, second)
        assertEquals(installedAt, disk.stored)
        assertEquals(listOf(kill), planned(visibleKill(kill), cutoff = second, now = secondLaunch))
    }
}
