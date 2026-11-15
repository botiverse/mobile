package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
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
 * How the exit record's importance and the age of the app's last sample and user interaction decide
 * whether a system kill is sent and as foreground or background, and how the ticket shows those ages.
 */
class AndroidSystemKillReportSampleAgeTest {
    private val exitAt = 2_000_000L
    private val mainProcess = "build.raft.app"

    @Test
    fun aStaleForegroundSnapshotAtImportanceServiceIsUploadedAsBackground() {
        // Hands 4a5504d3: foreground set 600 s, last sample 570 s, trim ui_hidden 1241 s before an
        // exit the system recorded at importance 300 (built before interactions were recorded).
        val telemetry = stale4a5504d3()
        val exit = exit(importance = IMPORTANCE_SERVICE)

        val item = plan(exit, telemetry)
        assertEquals(SystemKillDecision.ReportBackground, item.decision)
        assertEquals(SystemKillAction.Submit, item.action)

        assertEquals(
            "System kill: low_memory (reasonCode=3 status=0 importance=300)\n" +
                "last seen in foreground 570s before exit (in foreground since 600s before exit; " +
                "system: background, importance 300; no sample or interaction in the last 60s, not trusted)\n" +
                "1900s after launch, last RSS 373 MB (570s before exit), last PSS 360 MB (580s before exit), " +
                "last trim ui_hidden (1241s before exit, before the move to foreground)",
            systemKillMessage(exit, telemetry),
        )
        // The raw fields are sent unchanged.
        val extras = systemKillExtras(exit, telemetry)
        assertEquals(300, extras["crash_exit_importance"])
        assertEquals(false, extras["crash_foreground"])
        assertEquals("ui_hidden", extras["crash_trim"])
        assertEquals(true, extras["crash_telemetry_foreground"])
        assertEquals(570_000L, extras["crash_memory_sample_age_ms"])
        assertEquals(1_241_000L, extras["crash_trim_age_ms"])
        // A stale sample and no interaction recorded: unknown, not "no".
        assertRecentForeground(extras, expected = null, source = "unknown", ageSeconds = null)
    }

    @Test
    fun everySystemKillIsUploadedWithItsRawImportanceAndTheEvidenceFields() {
        val importances =
            listOf(IMPORTANCE_FOREGROUND, IMPORTANCE_VISIBLE, IMPORTANCE_FOREGROUND_SERVICE, IMPORTANCE_SERVICE, IMPORTANCE_CACHED)
        for (importance in importances) {
            val visible = importance == IMPORTANCE_FOREGROUND || importance == IMPORTANCE_VISIBLE
            val exit = exit(importance = importance)

            // With recent foreground: an interaction 1 s before, the sample 570 s.
            val recent = snapshot(sampleAgeMillis = 570_000L, interactionAgeMillis = 1_000L)
            val recentItem = plan(exit, recent)
            assertEquals("$importance recent", SystemKillAction.Submit, recentItem.action)
            assertEquals("$importance recent", SystemKillDecision.Report, recentItem.decision)
            val recentExtras = systemKillExtras(exit, recent)
            assertEquals(importance, recentExtras["crash_exit_importance"])
            assertEquals(visible, recentExtras["crash_foreground"])
            assertRecentForeground(recentExtras, expected = true, source = "interaction", ageSeconds = 1L)

            // Without: both known and old.
            val old = snapshot(sampleAgeMillis = 570_000L, interactionAgeMillis = 600_000L)
            val oldItem = plan(exit, old)
            assertEquals("$importance old", SystemKillAction.Submit, oldItem.action)
            assertEquals(
                "$importance old",
                if (visible) SystemKillDecision.Report else SystemKillDecision.ReportBackground,
                oldItem.decision,
            )
            val oldExtras = systemKillExtras(exit, old)
            assertEquals(importance, oldExtras["crash_exit_importance"])
            assertEquals(visible, oldExtras["crash_foreground"])
            assertRecentForeground(oldExtras, expected = false, source = "none", ageSeconds = null)

            // No snapshot at all.
            val noneItem = plan(exit, null)
            assertEquals("$importance none", SystemKillAction.Submit, noneItem.action)
            val noneExtras = systemKillExtras(exit, null)
            assertEquals(importance, noneExtras["crash_exit_importance"])
            assertEquals(visible, noneExtras["crash_foreground"])
            assertRecentForeground(noneExtras, expected = null, source = "unknown", ageSeconds = null)
        }
    }

    @Test
    fun recentForegroundIsTrueWhenEitherSourceIsFreshEvenIfTheOtherIsMissing() {
        val exit = exit(importance = IMPORTANCE_CACHED)
        assertRecentForeground(
            systemKillExtras(exit, snapshot(sampleAgeMillis = 5_000L, interactionAgeMillis = null)),
            expected = true, source = "sample", ageSeconds = 5L,
        )
        assertRecentForeground(
            systemKillExtras(exit, snapshot(sampleAgeMillis = null, interactionAgeMillis = 2_000L)),
            expected = true, source = "interaction", ageSeconds = 2L,
        )
        // Both fresh: the interaction.
        assertRecentForeground(
            systemKillExtras(exit, snapshot(sampleAgeMillis = 1_000L, interactionAgeMillis = 30_000L)),
            expected = true, source = "interaction", ageSeconds = 30L,
        )
        // Fresh sample, interaction after the exit (invalid): still true from the sample.
        assertRecentForeground(
            systemKillExtras(exit, snapshot(sampleAgeMillis = 5_000L, interactionAgeMillis = -1_000L)),
            expected = true, source = "sample", ageSeconds = 5L,
        )
    }

    @Test
    fun recentForegroundIsFalseOnlyWhenBothSourcesAreKnownAndOld() {
        val exit = exit(importance = IMPORTANCE_CACHED)
        assertRecentForeground(
            systemKillExtras(exit, snapshot(sampleAgeMillis = 61_000L, interactionAgeMillis = 600_000L)),
            expected = false, source = "none", ageSeconds = null,
        )
        // One stale, one missing.
        assertRecentForeground(
            systemKillExtras(exit, snapshot(sampleAgeMillis = 570_000L, interactionAgeMillis = null)),
            expected = null, source = "unknown", ageSeconds = null,
        )
        assertRecentForeground(
            systemKillExtras(exit, snapshot(sampleAgeMillis = null, interactionAgeMillis = 600_000L)),
            expected = null, source = "unknown", ageSeconds = null,
        )
        // One stale, one after the exit (a clock change: invalid).
        assertRecentForeground(
            systemKillExtras(exit, snapshot(sampleAgeMillis = 570_000L, interactionAgeMillis = -1_000L)),
            expected = null, source = "unknown", ageSeconds = null,
        )
        // Both missing.
        assertRecentForeground(
            systemKillExtras(exit, snapshot(sampleAgeMillis = null, interactionAgeMillis = null)),
            expected = null, source = "unknown", ageSeconds = null,
        )
    }

    @Test
    fun anInteractionOneSecondBeforeAnExitRecordedAsCachedIsUploadedAsForeground() {
        // fb-52cc0499: the user scrolled a second before a kill recorded at importance 400; the
        // memory sample itself is older than 60 s.
        val telemetry = stale4a5504d3().copy(lastInteractionAtMillis = exitAt - 1_000L)
        val exit = exit(importance = IMPORTANCE_CACHED)

        val item = plan(exit, telemetry)
        assertEquals(SystemKillDecision.Report, item.decision)
        assertEquals(SystemKillAction.Submit, item.action)

        val message = systemKillMessage(exit, telemetry)
        assertTrue(
            message,
            message.contains(
                "last seen in foreground 570s before exit (in foreground since 600s before exit; " +
                    "system: background, importance 400)\n1900s after launch, last interaction 1s before exit, "
            ),
        )
        assertFalse(message, message.contains("not trusted"))
        val extras = systemKillExtras(exit, telemetry)
        assertRecentForeground(extras, expected = true, source = "interaction", ageSeconds = 1L)
        assertEquals(1_000L, extras["crash_last_interaction_age_ms"])
        assertEquals(400, extras["crash_exit_importance"])
        assertEquals(false, extras["crash_foreground"])
    }

    @Test
    fun anInteraction600SecondsBeforeWithoutAFreshSampleIsUploadedAsBackground() {
        val telemetry = stale4a5504d3().copy(lastInteractionAtMillis = exitAt - 600_000L)
        val exit = exit(importance = IMPORTANCE_CACHED)

        val item = plan(exit, telemetry)
        assertEquals(SystemKillDecision.ReportBackground, item.decision)
        assertEquals(SystemKillAction.Submit, item.action)
        val message = systemKillMessage(exit, telemetry)
        assertTrue(message, message.contains("last interaction 600s before exit"))
        assertTrue(message, message.contains("not trusted"))
        assertRecentForeground(systemKillExtras(exit, telemetry), expected = false, source = "none", ageSeconds = null)
    }

    @Test
    fun anInteractionIsFreshUpToSixtySecondsBeforeTheExit() {
        mapOf(
            0L to true,
            SystemKillFreshSampleMillis to true,
            SystemKillFreshSampleMillis + 1L to false,
            -1L to false,
        ).forEach { (age, fresh) ->
            val telemetry = stale4a5504d3().copy(lastInteractionAtMillis = exitAt - age)
            assertEquals("age $age", fresh, isTelemetryInteractionFresh(telemetry, exitAt))
            assertEquals(
                "age $age",
                if (fresh) SystemKillDecision.Report else SystemKillDecision.ReportBackground,
                classifySystemKill(ApplicationExitInfo.REASON_LOW_MEMORY, IMPORTANCE_CACHED, telemetry, exitAt),
            )
        }
    }

    @Test
    fun aRecentInteractionCountsEvenWhenTheSnapshotSaysBackground() {
        // The user acted, switched away, and the app was killed at importance 400.
        val telemetry = staleBackground().copy(lastInteractionAtMillis = exitAt - 1_000L)
        val exit = exit(importance = IMPORTANCE_CACHED)

        assertEquals(ForegroundEvidence(ForegroundEvidence.Kind.Interaction, 1_000L), recentForegroundEvidence(telemetry, exitAt))
        val item = plan(exit, telemetry)
        assertEquals(SystemKillDecision.Report, item.decision)
        assertEquals(SystemKillAction.Submit, item.action)
        assertRecentForeground(systemKillExtras(exit, telemetry), expected = true, source = "interaction", ageSeconds = 1L)
        assertTrue(systemKillMessage(exit, telemetry).contains("last interaction 1s before exit"))
    }

    @Test
    fun aBackgroundSnapshotsSampleIsNotForegroundEvidence() {
        // Sampled 5 s before the exit but already in background since: the sample source is not fresh.
        val telemetry =
            staleBackground().copy(sampledAtMillis = exitAt - 5_000L, lastInteractionAtMillis = exitAt - 600_000L)
        val exit = exit(importance = IMPORTANCE_CACHED)

        assertNull(recentForegroundEvidence(telemetry, exitAt))
        assertEquals(SystemKillDecision.ReportBackground, plan(exit, telemetry).decision)
        assertRecentForeground(systemKillExtras(exit, telemetry), expected = false, source = "none", ageSeconds = null)
    }

    @Test
    fun importanceForegroundIsAForegroundKillWhateverTheSnapshot() {
        assertAlwaysForeground(IMPORTANCE_FOREGROUND)
    }

    @Test
    fun importanceVisibleIsAForegroundKillWhateverTheSnapshot() {
        assertAlwaysForeground(IMPORTANCE_VISIBLE)
    }

    @Test
    fun aStaleBackgroundSnapshotDoesNotHideAVisibleExit() {
        for (importance in listOf(IMPORTANCE_FOREGROUND, IMPORTANCE_VISIBLE)) {
            assertEquals("importance $importance", SystemKillDecision.Report, plan(exit(importance = importance), staleBackground()).decision)
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
    fun importanceForegroundServiceIsForegroundOnlyWithRecentEvidence() {
        val service = exit(importance = IMPORTANCE_FOREGROUND_SERVICE)
        assertEquals(SystemKillDecision.Report, plan(service, snapshot(5_000L, null)).decision)
        assertEquals(SystemKillDecision.Report, plan(service, snapshot(570_000L, 5_000L)).decision)
        for (telemetry in listOf(stale4a5504d3(), staleBackground(), null)) {
            val item = plan(service, telemetry)
            assertEquals("$telemetry", SystemKillDecision.ReportBackground, item.decision)
            assertEquals("$telemetry", SystemKillAction.Submit, item.action)
        }
    }

    @Test
    fun aFreshForegroundSampleWinsOverAnExitRecordedAsCached() {
        val telemetry = foregroundSnapshot(sampleAgeMillis = 1_000L).copy(lastTrimLevel = 0, lastTrimAtMillis = -1L)
        val exit = exit(importance = IMPORTANCE_CACHED)

        assertEquals(SystemKillDecision.Report, plan(exit, telemetry).decision)
        assertEquals(
            "System kill: low_memory (reasonCode=3 status=0 importance=400)\n" +
                "last seen in foreground 1s before exit (in foreground since 600s before exit; " +
                "system: background, importance 400)\n" +
                "1900s after launch, last RSS 373 MB (1s before exit), last PSS 360 MB (580s before exit), no trim",
            systemKillMessage(exit, telemetry),
        )
    }

    @Test
    fun withoutASnapshotTheMessageIsTheExitRecordOnly() {
        assertEquals(SystemKillDecision.ReportBackground, plan(exit(importance = IMPORTANCE_SERVICE), null).decision)
        assertEquals(SystemKillDecision.Report, plan(exit(importance = IMPORTANCE_VISIBLE), null).decision)
        assertEquals(
            "System kill: low_memory (reasonCode=3 status=0 importance=300)",
            systemKillMessage(exit(importance = IMPORTANCE_SERVICE), null),
        )
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
                if (fresh) SystemKillDecision.Report else SystemKillDecision.ReportBackground,
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
    fun aForegroundSnapshotThatWasNeverSampledSaysSo() {
        val telemetry = foregroundSnapshot(sampleAgeMillis = 0L).copy(sampledAtMillis = -1L, rssKb = -1L)

        assertFalse(isTelemetrySampleFresh(telemetry, exitAt))
        assertEquals(SystemKillDecision.ReportBackground, plan(exit(importance = IMPORTANCE_SERVICE), telemetry).decision)
        assertEquals(SystemKillDecision.Report, plan(exit(importance = IMPORTANCE_FOREGROUND), telemetry).decision)
        val message = systemKillMessage(exit(importance = IMPORTANCE_SERVICE), telemetry)
        assertTrue(
            message,
            message.contains(
                "in foreground since 600s before exit, never sampled " +
                    "(system: background, importance 300; no sample or interaction in the last 60s, not trusted)"
            ),
        )
        assertFalse(message, message.contains("last RSS"))
    }

    private fun assertRecentForeground(extras: Map<String, Any?>, expected: Boolean?, source: String, ageSeconds: Long?) {
        if (expected == null) {
            assertFalse("crash_recent_foreground omitted", extras.containsKey("crash_recent_foreground"))
        } else {
            assertEquals(expected, extras["crash_recent_foreground"])
        }
        assertEquals(source, extras["crash_recent_foreground_source"])
        if (ageSeconds == null) {
            assertFalse("crash_recent_foreground_age_s omitted", extras.containsKey("crash_recent_foreground_age_s"))
        } else {
            assertEquals(ageSeconds, extras["crash_recent_foreground_age_s"])
        }
    }

    private fun assertAlwaysForeground(importance: Int) {
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

    /** A foreground snapshot; a null age is a time never recorded (-1), a negative one after the exit. */
    private fun snapshot(sampleAgeMillis: Long?, interactionAgeMillis: Long?) =
        foregroundSnapshot(sampleAgeMillis = sampleAgeMillis ?: 0L).copy(
            sampledAtMillis = sampleAgeMillis?.let { exitAt - it } ?: -1L,
            lastInteractionAtMillis = interactionAgeMillis?.let { exitAt - it } ?: -1L,
        )

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
