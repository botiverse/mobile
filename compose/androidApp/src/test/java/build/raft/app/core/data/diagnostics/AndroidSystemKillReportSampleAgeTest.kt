package build.raft.app.core.data.diagnostics

import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import build.raft.app.shared.RaftBuildStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the age of the app's last telemetry sample decides a system kill and shows in its ticket. */
class AndroidSystemKillReportSampleAgeTest {
    private val foreground = 100 // IMPORTANCE_FOREGROUND
    private val visible = 200
    private val service = 300
    private val cached = 400
    private val exitAt = 2_000_000L
    private val mainProcess = "build.raft.app"

    @Test
    fun aStaleForegroundSnapshotAtImportance300IsABackgroundKill() {
        // Hands 4a5504d3: foreground set 600 s, last sample 570 s, trim ui_hidden 1241 s before an
        // exit the system recorded at importance 300.
        val telemetry = stale4a5504d3()
        val exit = exit(importance = service)

        assertEquals(
            SystemKillDecision.SkipNotImportant,
            classifySystemKill(exit.reasonCode, exit.importance, telemetry, exitAt),
        )
        val item = plan(exit, telemetry)
        assertEquals(SystemKillDecision.SkipNotImportant, item.decision)
        assertEquals(SystemKillAction.Skip, item.action)

        val message = systemKillMessage(exit, telemetry)
        assertEquals(
            "System kill: low_memory (reasonCode=3 status=0 importance=300)\n" +
                "last seen in foreground 570s before exit (in foreground since 600s before exit; " +
                "system: background, importance 300; sample over 60s old, not trusted)\n" +
                "1900s after launch, last RSS 373 MB (570s before exit), last PSS 360 MB (580s before exit), " +
                "last trim ui_hidden (1241s before exit, before the move to foreground)",
            message,
        )
        // The metadata Hands and triage read keeps its names and values.
        val extras = systemKillExtras(exit, telemetry)
        assertEquals(false, extras["crash_foreground"])
        assertEquals(true, extras["crash_telemetry_foreground"])
        assertEquals(570_000L, extras["crash_memory_sample_age_ms"])
        assertEquals(1_241_000L, extras["crash_trim_age_ms"])
    }

    @Test
    fun aStaleForegroundSnapshotIsStillReportedWhenTheSystemSawItOnScreen() {
        for (importance in listOf(foreground, visible)) {
            val exit = exit(importance = importance)
            assertEquals(
                "importance $importance",
                SystemKillAction.Submit,
                plan(exit, stale4a5504d3()).action,
            )
            assertTrue(systemKillMessage(exit, stale4a5504d3()).contains("system: foreground, importance $importance;"))
        }
        assertEquals(SystemKillAction.Skip, plan(exit(importance = cached), stale4a5504d3()).action)
    }

    @Test
    fun aFreshForegroundSnapshotDecidesAsBefore() {
        // fb-52cc0499: the user scrolled a second before a kill recorded at importance 400.
        val telemetry = foregroundSnapshot(sampleAgeMillis = 1_000L).copy(lastTrimLevel = 0, lastTrimAtMillis = -1L)
        for (importance in listOf(foreground, visible, service, cached)) {
            val exit = exit(importance = importance)
            assertEquals("importance $importance", SystemKillAction.Submit, plan(exit, telemetry).action)
        }

        val message = systemKillMessage(exit(importance = cached), telemetry)
        assertTrue(
            message,
            message.endsWith(
                "last seen in foreground 1s before exit (in foreground since 600s before exit; " +
                    "system: background, importance 400)\n" +
                    "1900s after launch, last RSS 373 MB (1s before exit), last PSS 360 MB (580s before exit), no trim"
            ),
        )
        assertFalse(message, message.contains("not trusted"))
    }

    @Test
    fun aBackgroundSnapshotStillDecidesWhateverItsAge() {
        // The move to background is committed at once and nothing is sampled after it.
        val telemetry =
            foregroundSnapshot(sampleAgeMillis = 900_000L).copy(
                foreground = false,
                foregroundChangedAtMillis = exitAt - 800_000L,
                lastTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                lastTrimAtMillis = exitAt - 850_000L,
            )
        for (importance in listOf(foreground, visible, service)) {
            assertEquals("importance $importance", SystemKillAction.Skip, plan(exit(importance = importance), telemetry).action)
        }
        assertTrue(
            systemKillMessage(exit(importance = cached), telemetry).endsWith(
                "in background since 800s before exit (system: background, importance 400)\n" +
                    "1900s after launch, last RSS 373 MB (900s before exit), last PSS 360 MB (580s before exit), " +
                    "last trim running_low (850s before exit, before the move to background)"
            )
        )
    }

    @Test
    fun withoutASnapshotTheImportanceDecidesAndTheMessageIsTheExitRecordOnly() {
        assertEquals(SystemKillAction.Skip, plan(exit(importance = service), null).action)
        assertEquals(SystemKillAction.Submit, plan(exit(importance = visible), null).action)
        assertEquals(
            "System kill: low_memory (reasonCode=3 status=0 importance=300)",
            systemKillMessage(exit(importance = service), null),
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
                if (fresh) SystemKillDecision.Report else SystemKillDecision.SkipNotImportant,
                classifySystemKill(ApplicationExitInfo.REASON_LOW_MEMORY, service, telemetry, exitAt),
            )
            assertEquals("age $age", !fresh, systemKillMessage(exit(importance = service), telemetry).contains("not trusted"))
        }
    }

    @Test
    fun aForegroundSnapshotThatWasNeverSampledFallsBackToTheImportance() {
        val telemetry = foregroundSnapshot(sampleAgeMillis = 0L).copy(sampledAtMillis = -1L, rssKb = -1L)

        assertFalse(isTelemetrySampleFresh(telemetry, exitAt))
        assertEquals(SystemKillAction.Skip, plan(exit(importance = service), telemetry).action)
        assertEquals(SystemKillAction.Submit, plan(exit(importance = foreground), telemetry).action)
        val message = systemKillMessage(exit(importance = service), telemetry)
        assertTrue(
            message,
            message.contains(
                "in foreground since 600s before exit, never sampled " +
                    "(system: background, importance 300; sample over 60s old, not trusted)"
            ),
        )
        assertFalse(message, message.contains("last RSS"))
    }

    /** Hands 4a5504d3's snapshot, against an exit at [exitAt]. */
    private fun stale4a5504d3() = foregroundSnapshot(sampleAgeMillis = 570_000L)

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
