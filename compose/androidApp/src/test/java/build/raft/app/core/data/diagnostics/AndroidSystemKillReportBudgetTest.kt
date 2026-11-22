package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.app.ApplicationExitInfo
import build.raft.app.shared.RaftBuildStamp
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visible, review and background kills have their own caps, planned in that order; only
 * background ones are dropped (and counted) over budget.
 */
class AndroidSystemKillReportBudgetTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 10 * day
    private val mainProcess = "build.raft.app"

    @Test
    fun onlyAConfirmedBackgroundKillUsesTheBackgroundBudget() {
        val at = now - 1_000L
        val old = old(pid = 1, at = at)
        val recent = old.copy(lastInteractionAtMillis = at - 1_000L)
        val unknown = old.copy(lastInteractionAtMillis = -1L)
        fun budget(importance: Int, telemetry: MemoryTelemetrySnapshot?, reason: Int = ApplicationExitInfo.REASON_LOW_MEMORY) =
            systemKillBudget(exit(pid = 1, at = at, importance = importance, reason = reason), telemetry)

        for (importance in listOf(IMPORTANCE_FOREGROUND_SERVICE, IMPORTANCE_PERCEPTIBLE, IMPORTANCE_SERVICE, IMPORTANCE_CACHED)) {
            assertEquals("$importance old", SystemKillBudget.Background, budget(importance, old))
            assertEquals("$importance recent", SystemKillBudget.Visible, budget(importance, recent))
            assertEquals("$importance unknown", SystemKillBudget.Review, budget(importance, unknown))
            assertEquals("$importance none", SystemKillBudget.Review, budget(importance, null))
            assertEquals(
                "$importance reason 9",
                SystemKillBudget.Review,
                budget(importance, old, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE),
            )
        }
        for (importance in listOf(IMPORTANCE_FOREGROUND, IMPORTANCE_VISIBLE)) {
            assertEquals("$importance old", SystemKillBudget.Visible, budget(importance, old))
            assertEquals("$importance none", SystemKillBudget.Visible, budget(importance, null))
        }
        // Below foreground service (not a background importance Hands archives on).
        assertEquals(SystemKillBudget.Review, budget(0, old))
    }

    @Test
    fun budgetsArePlannedVisibleFirstThenReviewThenBackground() {
        // Older review and background kills first in time; the visible one is the newest.
        val review = (1..5).map { exit(pid = it, at = now - 20_000L + it, importance = IMPORTANCE_SERVICE) }
        val background = (6..9).map { exit(pid = it, at = now - 10_000L + it, importance = IMPORTANCE_CACHED) }
        val visible = exit(pid = 10, at = now - 1_000L, importance = IMPORTANCE_FOREGROUND)

        val plan =
            plan(
                review + background + visible,
                snapshots = background.map { old(it.pid, it.timestampMillis) },
                perLaunchCap = 1,
                reviewPerLaunch = 3,
                backgroundPerLaunch = 2,
            )

        assertEquals(SystemKillAction.Submit, plan.last().action)
        assertEquals(
            List(3) { SystemKillAction.Submit } + List(2) { SystemKillAction.Deferred },
            plan.filter { it.budget == SystemKillBudget.Review }.map { it.action },
        )
        assertEquals(
            List(2) { SystemKillAction.Submit } + List(2) { SystemKillAction.RateLimited },
            plan.filter { it.budget == SystemKillBudget.Background }.map { it.action },
        )
        // Uploads go visible, then review, then background.
        assertEquals(
            listOf(10, 1, 2, 3, 6, 7),
            systemKillProcessingOrder(plan).filter { it.action == SystemKillAction.Submit }.map { it.exit.pid },
        )
    }

    @Test
    fun visibleKillsOverTheLaunchCapAreDeferredWithoutHoldingUpOthers() {
        val exits =
            (1..4).map { exit(pid = it, at = now - 10_000L + it, importance = IMPORTANCE_VISIBLE) } +
                exit(pid = 5, at = now - 1_000L, importance = IMPORTANCE_CACHED)

        val plan = plan(exits, snapshots = listOf(old(5, now - 1_000L)))

        assertEquals(
            listOf(
                SystemKillAction.Submit, SystemKillAction.Submit, SystemKillAction.Submit,
                SystemKillAction.Deferred, SystemKillAction.Submit,
            ),
            plan.map { it.action },
        )
    }

    @Test
    fun theNextUploadedReportCarriesTheDroppedBatch() {
        val batch = BackgroundDropBatch("batch-1", listOf(BackgroundDropEntry(12600001L, "2026-11-15", 4)))
        val exit = exit(pid = 1, at = now - 1_000L, importance = IMPORTANCE_VISIBLE)

        val extras = systemKillExtras(exit, null, batch)

        assertEquals("batch-1", extras["crash_background_dropped_id"])
        val dropped = extras["crash_background_dropped"] as JSONArray
        assertEquals(1, dropped.length())
        assertEquals(12600001L, dropped.getJSONObject(0).getLong("version_code"))
        assertEquals("2026-11-15", dropped.getJSONObject(0).getString("exit_day_utc"))
        assertEquals(4, dropped.getJSONObject(0).getInt("count"))
        // The report's own exit is unchanged.
        assertEquals(now - 1_000L, extras["crash_exit_timestamp"])

        assertFalse(systemKillExtras(exit, null).containsKey("crash_background_dropped"))
        assertFalse(systemKillExtras(exit, null).containsKey("crash_background_dropped_id"))
    }

    /** A foreground snapshot whose sample and interaction are both 600 s before [at]: recent foreground false. */
    private fun old(pid: Int, at: Long) =
        MemoryTelemetrySnapshot(
            pid = pid,
            processStartMillis = 0L,
            writtenAtMillis = at - 600_000L,
            foreground = true,
            foregroundChangedAtMillis = at - 700_000L,
            sampledAtMillis = at - 600_000L,
            lastInteractionAtMillis = at - 600_000L,
        )

    private fun plan(
        exits: List<SystemKillExit>,
        snapshots: List<MemoryTelemetrySnapshot> = emptyList(),
        recentVisible: List<Long> = emptyList(),
        recentReview: List<Long> = emptyList(),
        recentBackground: List<Long> = emptyList(),
        watermark: Long = 0L,
        handled: Set<SystemKillExitKey> = emptySet(),
        perLaunchCap: Int = AndroidSystemKillReporter.MaxReportsPerLaunch,
        reviewPerLaunch: Int = AndroidSystemKillReporter.MaxReviewReportsPerLaunch,
        backgroundPerLaunch: Int = AndroidSystemKillReporter.MaxBackgroundReportsPerLaunch,
    ) = planSystemKillReports(
        exits = exits,
        snapshots = snapshots,
        cutoffMillis = 0L,
        watermarkMillis = watermark,
        recentReportMillis = recentVisible,
        nowMillis = now,
        perLaunchCap = perLaunchCap,
        dailyCap = AndroidSystemKillReporter.MaxReportsPerDay,
        mainProcessName = mainProcess,
        review = SystemKillBudgetLimit(reviewPerLaunch, AndroidSystemKillReporter.MaxReviewReportsPerDay, recentReview),
        background = SystemKillBudgetLimit(backgroundPerLaunch, AndroidSystemKillReporter.MaxBackgroundReportsPerDay, recentBackground),
        handledAboveWatermark = handled,
    )

    private fun exit(pid: Int, at: Long, importance: Int, reason: Int = ApplicationExitInfo.REASON_LOW_MEMORY) =
        SystemKillExit(
            pid = pid,
            timestampMillis = at,
            reasonCode = reason,
            status = 0,
            importance = importance,
            pssKb = 0L,
            rssKb = 0L,
            build = RaftBuildStamp.StampedBuild("1.26.0", 12600001L),
            processName = mainProcess,
        )
}
