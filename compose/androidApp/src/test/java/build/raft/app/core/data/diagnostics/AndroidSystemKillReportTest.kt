package build.raft.app.core.data.diagnostics

import android.app.ApplicationExitInfo
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

    @Test
    fun huaweiOtherKillWithCachedImportanceIsReportedOnlyWhenTelemetrySaysForeground() {
        // fb-52cc0499: reason=other reasonCode=13 importance=400, user scrolling one second earlier.
        assertEquals(
            SystemKillDecision.Report,
            classifySystemKill(ApplicationExitInfo.REASON_OTHER, cached, telemetryForeground = true)
        )
        assertEquals(
            SystemKillDecision.SkipNotImportant,
            classifySystemKill(ApplicationExitInfo.REASON_OTHER, cached, telemetryForeground = false)
        )
        assertEquals(
            SystemKillDecision.SkipNotImportant,
            classifySystemKill(ApplicationExitInfo.REASON_OTHER, cached, telemetryForeground = null)
        )
    }

    @Test
    fun systemKillReasonsAreReportedAtPerceptibleImportanceOrBetter() {
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
            for (importance in listOf(foreground, foregroundService, visible, perceptible)) {
                assertEquals(
                    "reason=$reason importance=$importance",
                    SystemKillDecision.Report,
                    classifySystemKill(reason, importance, telemetryForeground = null)
                )
            }
            for (importance in listOf(service, cached, 1000)) {
                assertEquals(
                    "reason=$reason importance=$importance",
                    SystemKillDecision.SkipNotImportant,
                    classifySystemKill(reason, importance, telemetryForeground = false)
                )
            }
        }
    }

    @Test
    fun userInitiatedAndPackageExitsAreNeverReportedEvenInForeground() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE
        )) {
            val decision = classifySystemKill(reason, foreground, telemetryForeground = true)
            assertEquals("reason=$reason", SystemKillDecision.SkipUserInitiated, decision)
            assertFalse(decision.report)
        }
        for (reason in listOf(
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE
        )) {
            assertEquals(
                SystemKillDecision.SkipPackageChange,
                classifySystemKill(reason, foreground, telemetryForeground = true)
            )
        }
    }

    @Test
    fun crashesAndAnrsAreLeftToHandsCrash() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR
        )) {
            assertEquals(
                SystemKillDecision.SkipReportedByHandsCrash,
                classifySystemKill(reason, foreground, telemetryForeground = true)
            )
        }
    }

    @Test
    fun selectionSkipsExitsAtOrBeforeInstallAndWatermarkAndDedupes() {
        val exits =
            listOf(
                SystemKillCandidate(pid = 1, timestampMillis = 900L), // before install
                SystemKillCandidate(pid = 2, timestampMillis = 1_100L), // == watermark: already handled
                SystemKillCandidate(pid = 3, timestampMillis = 1_300L),
                SystemKillCandidate(pid = 3, timestampMillis = 1_300L), // the same exit listed twice
                SystemKillCandidate(pid = 4, timestampMillis = 1_200L)
            )

        val selected =
            selectUnreportedExits(
                exits,
                candidate = { it },
                packageInstalledAtMillis = 1_000L,
                watermarkMillis = 1_100L,
                limit = 3
            )

        assertEquals(listOf(1_200L, 1_300L), selected.map { it.timestampMillis })
    }

    @Test
    fun selectionAfterWatermarkAdvancesReportsNothingTwice() {
        val exits = listOf(SystemKillCandidate(7, 2_000L))
        val first = selectUnreportedExits(exits, { it }, packageInstalledAtMillis = 0L, watermarkMillis = 0L, limit = 3)
        assertEquals(1, first.size)
        val watermark = first.maxOf { it.timestampMillis }
        assertTrue(selectUnreportedExits(exits, { it }, 0L, watermark, 3).isEmpty())
    }

    @Test
    fun selectionKeepsTheNewestWhenCapped() {
        val exits = (1..5).map { SystemKillCandidate(it, it * 100L) }
        val selected = selectUnreportedExits(exits, { it }, 0L, 0L, limit = 2)
        assertEquals(listOf(400L, 500L), selected.map { it.timestampMillis })
    }

    @Test
    fun extrasCarryVersionExitFieldsMemorySampleTrimAndUptime() {
        val exit =
            HistoricalProcessExitSnapshot(
                timestampMillis = 100_000L,
                reasonCode = ApplicationExitInfo.REASON_OTHER,
                status = 0,
                importance = cached,
                pssKb = 0L,
                rssKb = 0L,
                traceAvailable = false,
                pid = 4321
            )
        val telemetry =
            MemoryTelemetrySnapshot(
                pid = 4321,
                processStartMillis = 40_000L,
                writtenAtMillis = 99_000L,
                foreground = true,
                foregroundChangedAtMillis = 50_000L,
                sampledAtMillis = 90_000L,
                pssKb = 512_000L,
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

        val extras = systemKillExtras(exit, telemetry, versionName = "1.22.0", versionCode = 12200001L)

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
        assertEquals(512_000L, extras["crash_memory_pss_kb"])
        assertEquals(100_000L, extras["crash_memory_java_heap_used_kb"])
        assertEquals(262_144L, extras["crash_memory_java_heap_max_kb"])
        assertEquals(10, extras["crash_trim_level"])
        assertEquals("running_low", extras["crash_trim"])
        assertEquals(5_000L, extras["crash_trim_age_ms"])
        assertEquals(true, extras["crash_foreground"])

        val message = systemKillMessage(exit, telemetry)
        assertTrue(message, message.startsWith("System kill: other (reasonCode=13 status=0 importance=400)"))
        assertTrue(message, message.contains("in foreground, 60s after launch, last PSS 500 MB, trim running_low"))
    }

    @Test
    fun extrasWithoutTelemetryStillCarryVersionAndExit() {
        val exit =
            HistoricalProcessExitSnapshot(
                timestampMillis = 5L,
                reasonCode = ApplicationExitInfo.REASON_LOW_MEMORY,
                status = 9,
                importance = foreground,
                pssKb = 1L,
                rssKb = 2L,
                traceAvailable = false,
                pid = 1
            )

        val extras = systemKillExtras(exit, telemetry = null, versionName = "1.22.0", versionCode = 7L)

        assertEquals("none", extras["crash_memory_sample"])
        assertEquals("low_memory", extras["crash_top_frame"])
        assertEquals(7L, extras["version_code"])
        assertFalse(extras.containsKey("crash_process_uptime_ms"))
    }

    @Test
    fun onlyNonRetryable4xxIsAPermanentRejection() {
        assertTrue(isPermanentHandsRejection(build.hands.update.HandsFeedbackException(400, "bad")))
        assertFalse(isPermanentHandsRejection(build.hands.update.HandsFeedbackException(429, "slow down")))
        assertFalse(isPermanentHandsRejection(build.hands.update.HandsFeedbackException(503, "down")))
        assertFalse(isPermanentHandsRejection(java.io.IOException("offline")))
    }
}
