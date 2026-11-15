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
import org.junit.Test

/**
 * Confirmed background kills have their own caps, never use up or hold up the protected ones, and
 * their drops are counted for the next report.
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
            assertEquals("$importance recent", SystemKillBudget.Protected, budget(importance, recent))
            assertEquals("$importance unknown", SystemKillBudget.Protected, budget(importance, unknown))
            assertEquals("$importance none", SystemKillBudget.Protected, budget(importance, null))
        }
        for (importance in listOf(IMPORTANCE_FOREGROUND, IMPORTANCE_VISIBLE)) {
            assertEquals("$importance old", SystemKillBudget.Protected, budget(importance, old))
        }
        // Below foreground service (not a background importance Hands archives on).
        assertEquals(SystemKillBudget.Protected, budget(0, old))
    }

    @Test
    fun importanceServiceWithUnknownRecentForegroundUsesTheProtectedBudget() {
        val at = now - 1_000L
        val unknown = old(pid = 1, at = at).copy(lastInteractionAtMillis = -1L)
        val item = plan(listOf(exit(pid = 1, at = at, importance = IMPORTANCE_SERVICE)), snapshots = listOf(unknown)).single()

        assertEquals(SystemKillDecision.ReportBackground, item.decision)
        assertEquals(SystemKillBudget.Protected, item.budget)
    }

    @Test
    fun anExcessiveResourceUsageKillUsesTheProtectedBudget() {
        // Hands never auto-archives reason 9 (ACTIONABLE_SYSTEM_KILL_REASON_CODES).
        val at = now - 1_000L
        val exit = exit(pid = 1, at = at, importance = IMPORTANCE_CACHED, reason = ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)
        val item = plan(listOf(exit), snapshots = listOf(old(pid = 1, at = at))).single()

        assertEquals(SystemKillBudget.Protected, item.budget)
        assertEquals(setOf(4, 5, 6, 9), HandsActionableSystemKillReasonCodes)
    }

    @Test
    fun fiveBackgroundKillsThenAForegroundKillTheSameLaunchStillSendsTheForegroundOne() {
        val background = (1..5).map { exit(pid = it, at = now - 10_000L + it, importance = IMPORTANCE_CACHED) }
        val exits = background + exit(pid = 6, at = now - 1_000L, importance = IMPORTANCE_VISIBLE)

        val plan = plan(exits, snapshots = background.map { old(it.pid, it.timestampMillis) })

        assertEquals(List(5) { SystemKillBudget.Background } + SystemKillBudget.Protected, plan.map { it.budget })
        assertEquals(
            listOf(
                SystemKillAction.Submit, SystemKillAction.Submit, SystemKillAction.Submit,
                // Over this launch's background cap: dropped (and counted), not deferred.
                SystemKillAction.RateLimited, SystemKillAction.RateLimited,
                SystemKillAction.Submit,
            ),
            plan.map { it.action },
        )
    }

    @Test
    fun fiveBackgroundKillsEarlierTodayDoNotUseTheProtectedBudget() {
        val exits =
            listOf(
                exit(pid = 1, at = now - 2_000L, importance = IMPORTANCE_SERVICE),
                exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_VISIBLE),
            )

        val plan =
            plan(exits, snapshots = listOf(old(1, now - 2_000L)), recentBackground = List(5) { now - 60_000L * (it + 1) })

        assertEquals(listOf(SystemKillAction.RateLimited, SystemKillAction.Submit), plan.map { it.action })
    }

    @Test
    fun aFullProtectedDayDoesNotStopBackgroundKills() {
        val exits =
            listOf(
                exit(pid = 1, at = now - 2_000L, importance = IMPORTANCE_VISIBLE),
                exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_CACHED),
            )

        val plan = plan(exits, snapshots = listOf(old(2, now - 1_000L)), recentProtected = List(5) { now - 60_000L * (it + 1) })

        assertEquals(listOf(SystemKillAction.RateLimited, SystemKillAction.Submit), plan.map { it.action })
    }

    @Test
    fun backgroundReportsOlderThanADayNoLongerCount() {
        val plan =
            plan(
                listOf(exit(pid = 1, at = now - 1_000L, importance = IMPORTANCE_CACHED)),
                snapshots = listOf(old(1, now - 1_000L)),
                recentBackground = List(5) { now - day - it },
            )

        assertEquals(SystemKillAction.Submit, plan.single().action)
    }

    @Test
    fun protectedKillsOverTheLaunchCapStillDeferEverythingAfterThem() {
        val exits =
            (1..4).map { exit(pid = it, at = now - 10_000L + it, importance = IMPORTANCE_VISIBLE) } +
                exit(pid = 5, at = now - 1_000L, importance = IMPORTANCE_CACHED)

        val plan = plan(exits, snapshots = listOf(old(5, now - 1_000L)))

        assertEquals(
            listOf(
                SystemKillAction.Submit, SystemKillAction.Submit, SystemKillAction.Submit,
                SystemKillAction.Deferred, SystemKillAction.Deferred,
            ),
            plan.map { it.action },
        )
    }

    @Test
    fun protectedReportsAreHandledBeforeBackgroundOnes() {
        val exits =
            listOf(
                exit(pid = 1, at = now - 5_000L, importance = IMPORTANCE_CACHED), // background
                exit(pid = 2, at = now - 4_000L, importance = IMPORTANCE_VISIBLE),
                exit(pid = 3, at = now - 3_000L, importance = IMPORTANCE_CACHED), // background
                exit(pid = 4, at = now - 2_000L, importance = IMPORTANCE_CACHED), // unknown: protected
            )
        val plan = plan(exits, snapshots = listOf(old(1, now - 5_000L), old(3, now - 3_000L)))

        assertEquals(listOf(2, 4, 1, 3), systemKillProcessingOrder(plan).map { it.exit.pid })
    }

    @Test
    fun theWatermarkPassesOnlyAnAllHandledPrefixAndKeepsHandledExitsAboveIt() {
        val t1 = now - 3_000L
        val t2 = now - 2_000L
        val t3 = now - 1_000L
        val exits =
            listOf(
                exit(pid = 1, at = t1, importance = IMPORTANCE_CACHED),
                exit(pid = 2, at = t2, importance = IMPORTANCE_VISIBLE),
                exit(pid = 3, at = t3, importance = IMPORTANCE_VISIBLE),
            )
        val snapshots = listOf(old(1, t1))
        val plan = plan(exits, snapshots = snapshots)
        assertEquals(SystemKillBudget.Background, plan.first().budget)
        val protectedOnly = setOf(SystemKillExitKey(2, t2), SystemKillExitKey(3, t3))

        // The protected ones went first; the background upload (pid 1) then failed.
        val (watermark, above) = advanceSystemKillWatermark(50L, plan, protectedOnly)
        assertEquals(50L, watermark)
        assertEquals(protectedOnly, above)
        assertEquals(protectedOnly, SystemKillExitKey.decodeAll(SystemKillExitKey.encodeAll(above)))

        // Next launch: the handled ones are not planned again.
        val retry = plan(exits, snapshots = snapshots, watermark = watermark, handled = above)
        assertEquals(listOf(1), retry.map { it.exit.pid })

        // Once pid 1 is handled, the watermark passes it; the others stay recorded above it.
        val (after, left) = advanceSystemKillWatermark(watermark, retry, above + SystemKillExitKey(1, t1))
        assertEquals(t1, after)
        assertEquals(protectedOnly, left)
        assertEquals(t3, advanceSystemKillWatermark(50L, plan, protectedOnly + SystemKillExitKey(1, t1)).first)
    }

    @Test
    fun droppedBackgroundReportsAreCountedPerVersionAndUtcExitDay() {
        val dayStart = 20_000L * day // 2024-10-04 UTC
        val ledger =
            BackgroundDropLedger()
                .record(versionCode = 12600001L, exitTimestampMillis = dayStart + 1_000L)
                .record(versionCode = 12600001L, exitTimestampMillis = dayStart + day - 1L)
                .record(versionCode = 12600001L, exitTimestampMillis = dayStart + day)
                .record(versionCode = 12500003L, exitTimestampMillis = dayStart + 5_000L)

        assertEquals(
            listOf(
                BackgroundDropEntry(12500003L, "2024-10-04", 1),
                BackgroundDropEntry(12600001L, "2024-10-04", 2),
                BackgroundDropEntry(12600001L, "2024-10-05", 1),
            ),
            ledger.accumulating,
        )
        assertEquals(ledger, BackgroundDropLedger.decode(ledger.encode()))
    }

    @Test
    fun aDroppedBatchIsResentWithTheSameIdUntilAnUploadSucceeds() {
        val first = BackgroundDropLedger().record(12600001L, now - 1_000L)

        val (attached, batch) = first.attach { "batch-1" }
        assertEquals("batch-1", batch?.id)
        assertEquals(first.accumulating, batch?.entries)

        // The upload failed; drops recorded meanwhile wait for a later batch.
        val afterFailure = BackgroundDropLedger.decode(attached.record(12600001L, now - 500L).encode())
        val (retried, retryBatch) = afterFailure.attach { "batch-2" }
        assertEquals(batch, retryBatch)

        // Success clears only that batch.
        val afterSuccess = retried.uploaded(retryBatch?.id)
        assertNull(afterSuccess.pending)
        val (_, next) = afterSuccess.attach { "batch-3" }
        assertEquals("batch-3", next?.id)
        assertEquals(1, next?.entries?.single()?.count)

        // Nothing dropped: nothing attached.
        val empty = BackgroundDropLedger()
        assertSame(empty, empty.attach { "unused" }.first)
        assertNull(empty.attach { "unused" }.second)
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

    @Test
    fun theDroppedEntryUsesTheExitsOwnVersion() {
        val stamped = exit(pid = 1, at = now, importance = IMPORTANCE_CACHED)
        assertEquals(12600001L, systemKillExitVersionCode(stamped, null))
        val unstamped = stamped.copy(build = null)
        assertEquals(12500003L, systemKillExitVersionCode(unstamped, old(1, now).copy(versionCode = 12500003L)))
        assertEquals(-1L, systemKillExitVersionCode(unstamped, null))
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
        recentProtected: List<Long> = emptyList(),
        recentBackground: List<Long> = emptyList(),
        watermark: Long = 0L,
        handled: Set<SystemKillExitKey> = emptySet(),
    ) = planSystemKillReports(
        exits = exits,
        snapshots = snapshots,
        cutoffMillis = 0L,
        watermarkMillis = watermark,
        recentReportMillis = recentProtected,
        nowMillis = now,
        perLaunchCap = AndroidSystemKillReporter.MaxReportsPerLaunch,
        dailyCap = AndroidSystemKillReporter.MaxReportsPerDay,
        mainProcessName = mainProcess,
        recentBackgroundReportMillis = recentBackground,
        backgroundPerLaunchCap = AndroidSystemKillReporter.MaxBackgroundReportsPerLaunch,
        backgroundDailyCap = AndroidSystemKillReporter.MaxBackgroundReportsPerDay,
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
