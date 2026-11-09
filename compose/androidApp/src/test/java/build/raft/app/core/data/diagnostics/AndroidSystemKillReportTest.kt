package build.raft.app.core.data.diagnostics

import android.app.ApplicationExitInfo
import build.raft.app.shared.RaftBuildStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSystemKillReportTest {
    private val cached = 400 // IMPORTANCE_CACHED
    private val foreground = 100 // IMPORTANCE_FOREGROUND
    private val foregroundService = 125
    private val visible = 200
    private val perceptible = 230
    private val service = 300
    private val stamp = RaftBuildStamp.StampedBuild("1.22.0", 12200001L)

    @Test
    fun huaweiOtherKillWithCachedImportanceIsReportedOnlyWhenTelemetrySaysForeground() {
        // fb-52cc0499: reason=other reasonCode=13 importance=400, user scrolling one second earlier.
        assertEquals(SystemKillDecision.Report, classifySystemKill(ApplicationExitInfo.REASON_OTHER, cached, true))
        assertEquals(SystemKillDecision.SkipNotImportant, classifySystemKill(ApplicationExitInfo.REASON_OTHER, cached, false))
        assertEquals(SystemKillDecision.SkipNotImportant, classifySystemKill(ApplicationExitInfo.REASON_OTHER, cached, null))
    }

    @Test
    fun foregroundServiceKillOfABackgroundedProcessIsNotReported() {
        // RaftBackgroundConnectionService keeps the backgrounded process at 125.
        for (reason in listOf(ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_SIGNALED)) {
            assertEquals(SystemKillDecision.SkipNotImportant, classifySystemKill(reason, foregroundService, false))
            assertEquals(SystemKillDecision.SkipNotImportant, classifySystemKill(reason, foregroundService, null))
            assertEquals(SystemKillDecision.SkipNotImportant, classifySystemKill(reason, perceptible, null))
        }
    }

    @Test
    fun telemetryOverridesTheRecordedImportance() {
        assertEquals(
            SystemKillDecision.SkipNotImportant,
            classifySystemKill(ApplicationExitInfo.REASON_LOW_MEMORY, foreground, telemetryForeground = false)
        )
        assertEquals(
            SystemKillDecision.Report,
            classifySystemKill(ApplicationExitInfo.REASON_LOW_MEMORY, service, telemetryForeground = true)
        )
    }

    @Test
    fun withoutTelemetryOnlyForegroundAndVisibleImportanceCount() {
        val reasons =
            listOf(
                ApplicationExitInfo.REASON_UNKNOWN,
                ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                ApplicationExitInfo.REASON_OTHER,
                ApplicationExitInfo.REASON_FREEZER,
                99 // a reason added by a later API level
            )
        for (reason in reasons) {
            for (importance in listOf(foreground, visible)) {
                assertEquals("$reason/$importance", SystemKillDecision.Report, classifySystemKill(reason, importance, null))
            }
            for (importance in listOf(foregroundService, perceptible, service, cached, 1000)) {
                assertEquals(
                    "$reason/$importance",
                    SystemKillDecision.SkipNotImportant,
                    classifySystemKill(reason, importance, null)
                )
            }
        }
    }

    @Test
    fun userInitiatedPackageAndCrashExitsAreNeverReportedEvenInForeground() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE
        )) {
            assertEquals(SystemKillDecision.SkipUserInitiated, classifySystemKill(reason, foreground, true))
        }
        for (reason in listOf(ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE)) {
            assertEquals(SystemKillDecision.SkipPackageChange, classifySystemKill(reason, foreground, true))
        }
        for (reason in listOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR
        )) {
            assertEquals(SystemKillDecision.SkipReportedElsewhere, classifySystemKill(reason, foreground, true))
        }
    }

    @Test
    fun planSkipsExitsAtOrBeforeCutoffAndWatermarkAndDedupes() {
        val exits =
            listOf(
                exit(pid = 1, at = 900L), // before the reporter first ran
                exit(pid = 2, at = 1_100L), // == watermark: already handled
                exit(pid = 3, at = 1_300L),
                exit(pid = 3, at = 1_300L), // the same exit listed twice
                exit(pid = 4, at = 1_200L)
            )

        val plan = plan(exits, cutoff = 1_000L, watermark = 1_100L)

        assertEquals(listOf(1_200L, 1_300L), plan.map { it.exit.timestampMillis })
        assertTrue(plan.all { it.action == SystemKillAction.Submit })
    }

    @Test
    fun aHandledExitIsNotPlannedAgainOnceTheWatermarkPassesIt() {
        val exits = listOf(exit(pid = 7, at = 2_000L))
        val first = plan(exits)
        assertEquals(SystemKillAction.Submit, first.single().action)
        assertTrue(plan(exits, watermark = first.single().exit.timestampMillis).isEmpty())
    }

    @Test
    fun everyExitIsClassifiedAndOnlySubmissionsAreCapped() {
        // Five visible kills interleaved with background ones: no exit is dropped unclassified.
        val exits =
            (1..10).map { i -> exit(pid = i, at = i * 100L, importance = if (i % 2 == 0) foreground else cached) }

        val plan = plan(exits, perLaunchCap = 2)

        assertEquals(10, plan.size)
        assertEquals(
            listOf(
                SystemKillAction.Skip, SystemKillAction.Submit, // 100, 200
                SystemKillAction.Skip, SystemKillAction.Submit, // 300, 400
                SystemKillAction.Skip, // 500
                SystemKillAction.Deferred, // 600: over this launch's cap
                // Everything after a deferred exit waits too, so the watermark stops before 600.
                SystemKillAction.Deferred, SystemKillAction.Deferred,
                SystemKillAction.Deferred, SystemKillAction.Deferred
            ),
            plan.map { it.action }
        )
    }

    @Test
    fun theDailyCapRateLimitsAndCountsEarlierReports() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        val exits = (1..3).map { exit(pid = it, at = now - 1_000L + it, importance = foreground) }

        val plan = plan(exits, now = now, recent = listOf(now - day, now - 5L), dailyCap = 2)

        // One slot left today; an older report (a day ago) no longer counts.
        assertEquals(
            listOf(SystemKillAction.Submit, SystemKillAction.RateLimited, SystemKillAction.RateLimited),
            plan.map { it.action }
        )
        assertEquals(listOf(now - 5L), decodeRecentReports("${now - day},${now - 5},junk", now))
    }

    @Test
    fun aFailedUploadRetriedNextLaunchStillMatchesItsTelemetry() {
        // Launch 1 saw the Huawei kill but the upload failed (watermark not moved). Launch 2's ring
        // still holds the killed process, next to launch 1's own entry.
        val killed = snapshot(pid = 4321, start = 1_000L, foreground = true)
        val launch1 = snapshot(pid = 5000, start = 50_000L, foreground = true)
        val ring = MemoryTelemetryRing.merge(listOf(killed), launch1)
        val huawei = exit(pid = 4321, at = 40_000L, reason = ApplicationExitInfo.REASON_OTHER, importance = cached)

        val retry = plan(listOf(huawei), snapshots = ring).single()

        assertEquals(SystemKillAction.Submit, retry.action)
        assertEquals(killed, retry.telemetry)
    }

    @Test
    fun aStickyServiceRestartBetweenKillAndLaunchDoesNotHideTheKill() {
        // The kill, then the foreground service restarts the process (no activity), then the user
        // opens the app. Each process keeps its own ring entry.
        val killed = snapshot(pid = 4321, start = 1_000L, foreground = true)
        val sticky = snapshot(pid = 4400, start = 41_000L, foreground = false)
        val ring = listOf(killed, sticky)
        val exits =
            listOf(
                exit(pid = 4321, at = 40_000L, importance = cached),
                exit(pid = 4400, at = 60_000L, importance = foregroundService)
            )

        val plan = plan(exits, snapshots = ring)

        assertEquals(listOf(SystemKillAction.Submit, SystemKillAction.Skip), plan.map { it.action })
        assertEquals(listOf(killed, sticky), plan.map { it.telemetry })
    }

    @Test
    fun telemetryOfAProcessStartedAfterTheExitIsNotMatched() {
        // A reused pid: the snapshot belongs to a newer process than the one that exited.
        val newer = snapshot(pid = 4321, start = 50_000L, foreground = true)
        val older = snapshot(pid = 4321, start = 1_000L, foreground = false)
        assertEquals(older, MemoryTelemetryRing.match(listOf(newer, older), pid = 4321, exitTimestampMillis = 40_000L))
        assertEquals(null, MemoryTelemetryRing.match(listOf(newer), pid = 4321, exitTimestampMillis = 40_000L))
        val plan = plan(listOf(exit(pid = 4321, at = 40_000L, importance = cached)), snapshots = listOf(newer))
        assertEquals(SystemKillAction.Skip, plan.single().action)
    }

    @Test
    fun extrasCarryTheStampedVersionExitMemorySampleTrimAndUptime() {
        val huawei = exit(pid = 4321, at = 100_000L, reason = ApplicationExitInfo.REASON_OTHER, importance = cached)
        val telemetry =
            MemoryTelemetrySnapshot(
                pid = 4321,
                processStartMillis = 40_000L,
                writtenAtMillis = 99_000L,
                foreground = true,
                foregroundChangedAtMillis = 50_000L,
                sampledAtMillis = 90_000L,
                rssKb = 600_000L,
                pssKb = 512_000L,
                pssSampledAtMillis = 80_000L,
                javaHeapUsedKb = 100_000L,
                javaHeapMaxKb = 262_144L,
                nativeHeapKb = 80_000L,
                systemAvailKb = 300_000L,
                systemThresholdKb = 200_000L,
                systemLowMemory = false,
                maxTrimLevel = 10,
                lastTrimLevel = 10,
                lastTrimAtMillis = 95_000L
            )

        val extras = systemKillExtras(huawei, telemetry)

        assertEquals("1.22.0", extras["version_name"])
        assertEquals(12200001L, extras["version_code"])
        assertEquals("system_kill", extras["crash_type"])
        assertEquals("SystemKill", extras["crash_exception_class"])
        assertEquals("other", extras["crash_exit_reason"])
        assertEquals(13, extras["crash_exit_reason_code"])
        assertEquals(0, extras["crash_exit_status"])
        assertEquals(400, extras["crash_exit_importance"])
        assertEquals(100_000L, extras["crash_exit_timestamp"])
        assertEquals(60_000L, extras["crash_process_uptime_ms"])
        assertEquals(10_000L, extras["crash_memory_sample_age_ms"])
        assertEquals(600_000L, extras["crash_memory_rss_kb"])
        assertEquals(512_000L, extras["crash_memory_pss_kb"])
        assertEquals(20_000L, extras["crash_memory_pss_age_ms"])
        assertEquals(10, extras["crash_trim_level"])
        assertEquals("running_low", extras["crash_trim"])
        assertEquals(5_000L, extras["crash_trim_age_ms"])
        assertEquals(true, extras["crash_foreground"])

        val message = systemKillMessage(huawei, telemetry)
        assertTrue(message, message.startsWith("System kill: other (reasonCode=13 status=0 importance=400)"))
        assertTrue(message, message.contains("in foreground, 60s after launch, last RSS 585 MB, last PSS 500 MB, trim running_low"))
    }

    @Test
    fun anUnstampedExitHasNoVersionRatherThanThisInstalls() {
        val extras = systemKillExtras(exit(pid = 1, at = 5L, importance = foreground).copy(build = null), null)

        assertFalse(extras.containsKey("version_name"))
        assertFalse(extras.containsKey("version_code"))
        assertEquals("none", extras["crash_memory_sample"])
        assertEquals("other", extras["crash_top_frame"])
        assertFalse(extras.containsKey("crash_process_uptime_ms"))
    }

    @Test
    fun onlyNonRetryable4xxIsAPermanentRejection() {
        assertTrue(isPermanentHandsRejection(build.hands.update.HandsFeedbackException(400, "bad")))
        assertFalse(isPermanentHandsRejection(build.hands.update.HandsFeedbackException(429, "slow down")))
        assertFalse(isPermanentHandsRejection(build.hands.update.HandsFeedbackException(503, "down")))
        assertFalse(isPermanentHandsRejection(java.io.IOException("offline")))
    }

    private fun exit(
        pid: Int,
        at: Long,
        reason: Int = ApplicationExitInfo.REASON_OTHER,
        importance: Int = foreground,
    ) = SystemKillExit(pid, at, reason, status = 0, importance = importance, pssKb = 0L, rssKb = 0L, build = stamp)

    private fun snapshot(pid: Int, start: Long, foreground: Boolean) =
        MemoryTelemetrySnapshot(pid, start, start + 10L, foreground, start + 5L)

    private fun plan(
        exits: List<SystemKillExit>,
        snapshots: List<MemoryTelemetrySnapshot> = emptyList(),
        cutoff: Long = 0L,
        watermark: Long = 0L,
        recent: List<Long> = emptyList(),
        now: Long = 1_000_000L,
        perLaunchCap: Int = 3,
        dailyCap: Int = 5,
    ) = planSystemKillReports(exits, snapshots, cutoff, watermark, recent, now, perLaunchCap, dailyCap)

    @Test
    fun aSignaledExitWithACrashSignalIsLeftToHandsCrash() {
        val visible = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        listOf(4, 6, 7, 8, 11).forEach { signal ->
            assertEquals(
                SystemKillDecision.SkipReportedElsewhere,
                classifySystemKill(ApplicationExitInfo.REASON_SIGNALED, visible, true, status = signal),
            )
        }
        // SIGKILL is the system killing the process: still a system kill.
        assertEquals(SystemKillDecision.Report, classifySystemKill(ApplicationExitInfo.REASON_SIGNALED, visible, true, status = 9))
    }
}
