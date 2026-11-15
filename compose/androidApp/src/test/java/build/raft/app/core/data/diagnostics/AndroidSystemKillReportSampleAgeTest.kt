package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import build.raft.app.shared.RaftBuildStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the exit record's importance and the age of the app's last telemetry sample decide a system
 * kill, and how the ticket and the skip audit line show those ages.
 */
class AndroidSystemKillReportSampleAgeTest {
    private val exitAt = 2_000_000L
    private val mainProcess = "build.raft.app"

    @Test
    fun aStaleForegroundSnapshotAtImportanceServiceIsSkippedWithAnAuditLine() {
        // Hands 4a5504d3: foreground set 600 s, last sample 570 s, trim ui_hidden 1241 s before an
        // exit the system recorded at importance 300.
        val telemetry = stale4a5504d3()
        val exit = exit(importance = IMPORTANCE_SERVICE)

        assertEquals(
            SystemKillDecision.SkipNotImportant,
            classifySystemKill(exit.reasonCode, exit.importance, telemetry, exitAt),
        )
        val item = plan(exit, telemetry)
        assertEquals(SystemKillDecision.SkipNotImportant, item.decision)
        assertEquals(SystemKillAction.Skip, item.action)

        val written = mutableListOf<String>()
        assertTrue(recordSystemKillSkipAudit(item) { written += it })
        assertEquals(
            listOf(
                "system_kill_skipped reason=low_memory importance=300 snapshot_age_s=570 fg_age_s=600 " +
                    "telemetry_foreground=true timestampMillis=$exitAt"
            ),
            written,
        )

        assertEquals(
            "System kill: low_memory (reasonCode=3 status=0 importance=300)\n" +
                "last seen in foreground 570s before exit (in foreground since 600s before exit; " +
                "system: background, importance 300; sample over 60s old, not trusted)\n" +
                "1900s after launch, last RSS 373 MB (570s before exit), last PSS 360 MB (580s before exit), " +
                "last trim ui_hidden (1241s before exit, before the move to foreground)",
            systemKillMessage(exit, telemetry),
        )
        // The metadata Hands and triage read keeps its names and values.
        val extras = systemKillExtras(exit, telemetry)
        assertEquals(false, extras["crash_foreground"])
        assertEquals(true, extras["crash_telemetry_foreground"])
        assertEquals(570_000L, extras["crash_memory_sample_age_ms"])
        assertEquals(1_241_000L, extras["crash_trim_age_ms"])
    }

    @Test
    fun importanceForegroundAlwaysReportsWhateverTheSnapshot() {
        assertAlwaysReported(IMPORTANCE_FOREGROUND)
    }

    @Test
    fun importanceVisibleAlwaysReportsWhateverTheSnapshot() {
        assertAlwaysReported(IMPORTANCE_VISIBLE)
    }

    @Test
    fun aStaleBackgroundSnapshotDoesNotHideAVisibleExit() {
        // The app may have come back to the foreground after its last snapshot was written.
        for (importance in listOf(IMPORTANCE_FOREGROUND, IMPORTANCE_VISIBLE)) {
            val item = plan(exit(importance = importance), staleBackground())
            assertEquals("importance $importance", SystemKillDecision.Report, item.decision)
            assertEquals("importance $importance", SystemKillAction.Submit, item.action)
            assertNull(systemKillSkipAuditLine(item))
        }
        assertTrue(
            systemKillMessage(exit(importance = IMPORTANCE_VISIBLE), staleBackground()).endsWith(
                "in background since 800s before exit (system: foreground, importance 200)\n" +
                    "1900s after launch, last RSS 373 MB (900s before exit), last PSS 360 MB (580s before exit), " +
                    "last trim running_low (850s before exit, before the move to background)"
            )
        )
    }

    @Test
    fun importanceForegroundServiceReportsOnlyWithAFreshForegroundSample() {
        val fresh = foregroundSnapshot(sampleAgeMillis = 5_000L)
        assertEquals(SystemKillAction.Submit, plan(exit(importance = IMPORTANCE_FOREGROUND_SERVICE), fresh).action)

        for (telemetry in listOf(stale4a5504d3(), staleBackground(), null)) {
            val item = plan(exit(importance = IMPORTANCE_FOREGROUND_SERVICE), telemetry)
            assertEquals("$telemetry", SystemKillAction.Skip, item.action)
            val written = mutableListOf<String>()
            assertTrue(recordSystemKillSkipAudit(item) { written += it })
            assertTrue(written.single(), written.single().startsWith("system_kill_skipped reason=low_memory importance=125 "))
        }
    }

    @Test
    fun aFreshForegroundSampleWinsOverAnExitRecordedAsCached() {
        // fb-52cc0499: the user scrolled a second before a kill recorded at importance 400.
        val telemetry = foregroundSnapshot(sampleAgeMillis = 1_000L).copy(lastTrimLevel = 0, lastTrimAtMillis = -1L)
        val exit = exit(importance = IMPORTANCE_CACHED)

        val item = plan(exit, telemetry)
        assertEquals(SystemKillDecision.Report, item.decision)
        assertEquals(SystemKillAction.Submit, item.action)
        assertNull(systemKillSkipAuditLine(item))

        val message = systemKillMessage(exit, telemetry)
        assertEquals(
            "System kill: low_memory (reasonCode=3 status=0 importance=400)\n" +
                "last seen in foreground 1s before exit (in foreground since 600s before exit; " +
                "system: background, importance 400)\n" +
                "1900s after launch, last RSS 373 MB (1s before exit), last PSS 360 MB (580s before exit), no trim",
            message,
        )
    }

    @Test
    fun aFreshForegroundSampleReportsAtEveryBackgroundImportance() {
        val fresh = foregroundSnapshot(sampleAgeMillis = 1_000L)
        for (importance in listOf(IMPORTANCE_FOREGROUND_SERVICE, IMPORTANCE_PERCEPTIBLE, IMPORTANCE_SERVICE, IMPORTANCE_CACHED)) {
            assertEquals("importance $importance", SystemKillAction.Submit, plan(exit(importance = importance), fresh).action)
        }
    }

    @Test
    fun aBackgroundSnapshotWithABackgroundImportanceIsSkipped() {
        for (importance in listOf(IMPORTANCE_FOREGROUND_SERVICE, IMPORTANCE_PERCEPTIBLE, IMPORTANCE_SERVICE, IMPORTANCE_CACHED)) {
            val item = plan(exit(importance = importance), staleBackground())
            assertEquals("importance $importance", SystemKillAction.Skip, item.action)
            assertEquals(
                "system_kill_skipped reason=low_memory importance=$importance snapshot_age_s=900 fg_age_s=800 " +
                    "telemetry_foreground=false timestampMillis=$exitAt",
                systemKillSkipAuditLine(item),
            )
        }
    }

    @Test
    fun withoutASnapshotTheImportanceDecidesAndTheMessageIsTheExitRecordOnly() {
        val skipped = plan(exit(importance = IMPORTANCE_SERVICE), null)
        assertEquals(SystemKillAction.Skip, skipped.action)
        assertEquals(
            "system_kill_skipped reason=low_memory importance=300 snapshot_age_s=none fg_age_s=none " +
                "telemetry_foreground=none timestampMillis=$exitAt",
            systemKillSkipAuditLine(skipped),
        )
        assertEquals(SystemKillAction.Submit, plan(exit(importance = IMPORTANCE_VISIBLE), null).action)
        assertEquals(
            "System kill: low_memory (reasonCode=3 status=0 importance=300)",
            systemKillMessage(exit(importance = IMPORTANCE_SERVICE), null),
        )
    }

    @Test
    fun onlyNotImportantSkipsGetAnAuditLine() {
        val userStopped =
            plan(exit(importance = IMPORTANCE_SERVICE).copy(reasonCode = ApplicationExitInfo.REASON_USER_STOPPED), null)
        assertEquals(SystemKillDecision.SkipUserInitiated, userStopped.decision)
        val written = mutableListOf<String>()
        assertFalse(recordSystemKillSkipAudit(userStopped) { written += it })
        assertTrue(written.isEmpty())
    }

    @Test
    fun aForegroundSampleIsTrustedUpToSixtySecondsBeforeTheExit() {
        val expectations =
            mapOf(
                0L to true,
                1L to true,
                SystemKillFreshSampleMillis - 1L to true,
                SystemKillFreshSampleMillis to true,
                SystemKillFreshSampleMillis + 1L to false,
                -1L to false, // sampled after the exit: a clock change, not evidence
            )
        expectations.forEach { (age, fresh) ->
            val telemetry = foregroundSnapshot(sampleAgeMillis = age)
            assertEquals("age $age", fresh, isTelemetrySampleFresh(telemetry, exitAt))
            assertEquals(
                "age $age",
                if (fresh) SystemKillDecision.Report else SystemKillDecision.SkipNotImportant,
                classifySystemKill(ApplicationExitInfo.REASON_LOW_MEMORY, IMPORTANCE_SERVICE, telemetry, exitAt),
            )
            assertEquals(
                "age $age",
                !fresh,
                systemKillMessage(exit(importance = IMPORTANCE_SERVICE), telemetry).contains("not trusted"),
            )
        }
    }

    @Test
    fun aForegroundSnapshotThatWasNeverSampledFallsBackToTheImportance() {
        val telemetry = foregroundSnapshot(sampleAgeMillis = 0L).copy(sampledAtMillis = -1L, rssKb = -1L)

        assertFalse(isTelemetrySampleFresh(telemetry, exitAt))
        assertEquals(SystemKillAction.Skip, plan(exit(importance = IMPORTANCE_SERVICE), telemetry).action)
        assertEquals(SystemKillAction.Submit, plan(exit(importance = IMPORTANCE_FOREGROUND), telemetry).action)
        val message = systemKillMessage(exit(importance = IMPORTANCE_SERVICE), telemetry)
        assertTrue(
            message,
            message.contains(
                "in foreground since 600s before exit, never sampled " +
                    "(system: background, importance 300; sample over 60s old, not trusted)"
            ),
        )
        assertFalse(message, message.contains("last RSS"))
    }

    private fun assertAlwaysReported(importance: Int) {
        val snapshots =
            listOf(
                null,
                foregroundSnapshot(sampleAgeMillis = 1_000L),
                stale4a5504d3(),
                staleBackground(),
                foregroundSnapshot(sampleAgeMillis = 1_000L).copy(foreground = false),
            )
        for (telemetry in snapshots) {
            val item = plan(exit(importance = importance), telemetry)
            assertEquals("$telemetry", SystemKillDecision.Report, item.decision)
            assertEquals("$telemetry", SystemKillAction.Submit, item.action)
        }
    }

    /** Hands 4a5504d3's snapshot, against an exit at [exitAt]. */
    private fun stale4a5504d3() = foregroundSnapshot(sampleAgeMillis = 570_000L)

    /** Moved to background 800 s before the exit; sampled (in foreground) 900 s before. */
    private fun staleBackground() =
        foregroundSnapshot(sampleAgeMillis = 900_000L).copy(
            foreground = false,
            foregroundChangedAtMillis = exitAt - 800_000L,
            lastTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            lastTrimAtMillis = exitAt - 850_000L,
        )

    private fun foregroundSnapshot(sampleAgeMillis: Long) =
        MemoryTelemetrySnapshot(
            pid = 4321,
            processStartMillis = exitAt - 1_900_000L,
            writtenAtMillis = exitAt - sampleAgeMillis,
            foreground = true,
            foregroundChangedAtMillis = exitAt - 600_000L,
            sampledAtMillis = exitAt - sampleAgeMillis,
            rssKb = 373L * 1024L,
            pssKb = 360L * 1024L,
            pssSampledAtMillis = exitAt - 580_000L,
            lastTrimLevel = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            maxTrimLevel = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            lastTrimAtMillis = exitAt - 1_241_000L,
        )

    private fun exit(importance: Int) =
        SystemKillExit(
            pid = 4321,
            timestampMillis = exitAt,
            reasonCode = ApplicationExitInfo.REASON_LOW_MEMORY,
            status = 0,
            importance = importance,
            pssKb = 0L,
            rssKb = 0L,
            build = RaftBuildStamp.StampedBuild("1.26.0", 12600001L),
            processName = mainProcess,
        )

    private fun plan(exit: SystemKillExit, telemetry: MemoryTelemetrySnapshot?): SystemKillPlanItem =
        planSystemKillReports(
            exits = listOf(exit),
            snapshots = listOfNotNull(telemetry),
            cutoffMillis = 0L,
            watermarkMillis = 0L,
            recentReportMillis = emptyList(),
            nowMillis = exitAt + 60_000L,
            perLaunchCap = 3,
            dailyCap = 5,
            mainProcessName = mainProcess,
        ).single()
}
