package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.app.ApplicationExitInfo
import build.raft.app.shared.RaftBuildStamp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Background kills have their own caps and never use up, or hold up, the foreground ones. */
class AndroidSystemKillReportBudgetTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 10 * day
    private val mainProcess = "build.raft.app"

    @Test
    fun fiveBackgroundKillsThenAForegroundKillTheSameLaunchStillSendsTheForegroundOne() {
        val exits =
            (1..5).map { exit(pid = it, at = now - 10_000L + it, importance = IMPORTANCE_CACHED) } +
                exit(pid = 6, at = now - 1_000L, importance = IMPORTANCE_VISIBLE)

        val plan = plan(exits)

        assertEquals(
            List(5) { SystemKillDecision.ReportBackground } + SystemKillDecision.Report,
            plan.map { it.decision },
        )
        assertEquals(
            listOf(
                SystemKillAction.Submit, SystemKillAction.Submit, SystemKillAction.Submit,
                // Over this launch's background cap: dropped, not deferred, so nothing waits behind it.
                SystemKillAction.RateLimited, SystemKillAction.RateLimited,
                SystemKillAction.Submit,
            ),
            plan.map { it.action },
        )
    }

    @Test
    fun fiveBackgroundKillsEarlierTodayDoNotUseTheForegroundBudget() {
        val exits =
            listOf(
                exit(pid = 1, at = now - 2_000L, importance = IMPORTANCE_SERVICE),
                exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_VISIBLE),
            )

        val plan = plan(exits, recentBackground = List(5) { now - 60_000L * (it + 1) })

        assertEquals(listOf(SystemKillAction.RateLimited, SystemKillAction.Submit), plan.map { it.action })
    }

    @Test
    fun aFullForegroundDayDoesNotStopBackgroundKills() {
        val exits =
            listOf(
                exit(pid = 1, at = now - 2_000L, importance = IMPORTANCE_VISIBLE),
                exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_CACHED),
            )

        val plan = plan(exits, recentForeground = List(5) { now - 60_000L * (it + 1) })

        assertEquals(listOf(SystemKillAction.RateLimited, SystemKillAction.Submit), plan.map { it.action })
    }

    @Test
    fun backgroundReportsOlderThanADayNoLongerCount() {
        val plan =
            plan(
                listOf(exit(pid = 1, at = now - 1_000L, importance = IMPORTANCE_CACHED)),
                recentBackground = List(5) { now - day - it },
            )

        assertEquals(SystemKillAction.Submit, plan.single().action)
    }

    @Test
    fun foregroundKillsOverTheLaunchCapStillDeferEverythingAfterThem() {
        val exits =
            (1..4).map { exit(pid = it, at = now - 10_000L + it, importance = IMPORTANCE_VISIBLE) } +
                exit(pid = 5, at = now - 1_000L, importance = IMPORTANCE_CACHED)

        val plan = plan(exits)

        assertEquals(
            listOf(
                SystemKillAction.Submit, SystemKillAction.Submit, SystemKillAction.Submit,
                SystemKillAction.Deferred, SystemKillAction.Deferred,
            ),
            plan.map { it.action },
        )
    }

    private fun plan(
        exits: List<SystemKillExit>,
        recentForeground: List<Long> = emptyList(),
        recentBackground: List<Long> = emptyList(),
    ) = planSystemKillReports(
        exits = exits,
        snapshots = emptyList(),
        cutoffMillis = 0L,
        watermarkMillis = 0L,
        recentReportMillis = recentForeground,
        nowMillis = now,
        perLaunchCap = AndroidSystemKillReporter.MaxReportsPerLaunch,
        dailyCap = AndroidSystemKillReporter.MaxReportsPerDay,
        mainProcessName = mainProcess,
        recentBackgroundReportMillis = recentBackground,
        backgroundPerLaunchCap = AndroidSystemKillReporter.MaxBackgroundReportsPerLaunch,
        backgroundDailyCap = AndroidSystemKillReporter.MaxBackgroundReportsPerDay,
    )

    private fun exit(pid: Int, at: Long, importance: Int) =
        SystemKillExit(
            pid = pid,
            timestampMillis = at,
            reasonCode = ApplicationExitInfo.REASON_LOW_MEMORY,
            status = 0,
            importance = importance,
            pssKb = 0L,
            rssKb = 0L,
            build = RaftBuildStamp.StampedBuild("1.26.0", 12600001L),
            processName = mainProcess,
        )
}
