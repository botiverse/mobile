package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo as Importance
import android.app.ApplicationExitInfo
import build.raft.app.shared.RaftBuildStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSystemKillReportTest {
    private val cached = Importance.IMPORTANCE_CACHED
    private val foreground = Importance.IMPORTANCE_FOREGROUND
    private val stamp = RaftBuildStamp.StampedBuild("1.22.0", 12200001L)

    @Test
    fun huaweiOtherKillWithCachedImportanceIsForegroundOnlyWhenTelemetrySaysForeground() {
        // fb-52cc0499: reason=other reasonCode=13 importance=400, user scrolling one second earlier.
        assertEquals(SystemKillDecision.Report, classify(ApplicationExitInfo.REASON_OTHER, cached, true))
        assertEquals(SystemKillDecision.ReportBackground, classify(ApplicationExitInfo.REASON_OTHER, cached, false))
        assertEquals(SystemKillDecision.ReportBackground, classify(ApplicationExitInfo.REASON_OTHER, cached, null))
        // botiverse/hands#955: a foreground sample 1 s before the exit counts even if the snapshot
        // already says background (the app switched away in that second).
        val switchedAway =
            snapshot(pid = 1, start = 1_000L, foreground = false).copy(foregroundChangedAtMillis = 99_500L, sampledAtMillis = 99_000L)
        assertEquals(
            SystemKillDecision.Report,
            classifySystemKill(ApplicationExitInfo.REASON_OTHER, cached, switchedAway, exitTimestampMillis = 100_000L),
        )
    }

    @Test
    fun userInitiatedPackageAndCrashExitsAreNeverReportedEvenInForeground() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE
        )) {
            assertEquals(SystemKillDecision.SkipUserInitiated, classify(reason, foreground, true))
        }
        for (reason in listOf(ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE)) {
            assertEquals(SystemKillDecision.SkipPackageChange, classify(reason, foreground, true))
        }
        for (reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_ANR)) {
            assertEquals(SystemKillDecision.SkipReportedElsewhere, classify(reason, foreground, true))
        }
    }

    @Test
    fun aNativeCrashIsReportedUnlessConfirmedElsewhereWhateverItsImportance() {
        // No longer assumed filed by HandsCrash: the send phase decides (decideNativeExit).
        for (importance in listOf(foreground, cached)) {
            for (telemetry in listOf(true, false, null)) {
                assertEquals(SystemKillDecision.ReportNativeCrash, classify(ApplicationExitInfo.REASON_CRASH_NATIVE, importance, telemetry))
            }
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
    fun onlyNonRetryable4xxIsAPermanentRejection() {
        assertTrue(isPermanentHandsRejection(build.hands.update.HandsFeedbackException(400, "bad")))
        assertFalse(isPermanentHandsRejection(build.hands.update.HandsFeedbackException(429, "slow down")))
        assertFalse(isPermanentHandsRejection(build.hands.update.HandsFeedbackException(503, "down")))
        assertFalse(isPermanentHandsRejection(java.io.IOException("offline")))
        // Configuration and auth answers are retried: a wrong client key (401), forbidden (403),
        // an unknown app slug (404). Only malformed requests are given up.
        for (code in listOf(401, 403, 404, 408)) {
            assertFalse("$code", isPermanentHandsRejection(build.hands.update.HandsFeedbackException(code, "config")))
        }
        for (code in listOf(400, 413, 422)) {
            assertTrue("$code", isPermanentHandsRejection(build.hands.update.HandsFeedbackException(code, "malformed")))
        }
    }

    private fun exit(
        pid: Int,
        at: Long,
        reason: Int = ApplicationExitInfo.REASON_OTHER,
        importance: Int = foreground,
        processName: String = mainProcess,
    ) = SystemKillExit(
        pid,
        at,
        reason,
        status = 0,
        importance = importance,
        pssKb = 0L,
        rssKb = 0L,
        build = stamp,
        processName = processName,
    )

    private val mainProcess = "build.raft.app"

    private fun snapshot(pid: Int, start: Long, foreground: Boolean) =
        MemoryTelemetrySnapshot(pid, start, start + 10L, foreground, start + 5L)

    /**
     * [classifySystemKill] with no telemetry; a foreground snapshot sampled one second before the
     * exit; or a background one, moved there 300 s before the exit after its last foreground sample
     * (nothing is sampled in the background, so a real backgrounded process has no fresh sample).
     */
    private fun classify(reason: Int, importance: Int, telemetryForeground: Boolean?, status: Int = 0): SystemKillDecision {
        val at = 400_000L
        val telemetry =
            when (telemetryForeground) {
                null -> null
                true -> snapshot(pid = 1, start = 1_000L, foreground = true).copy(sampledAtMillis = at - 1_000L)
                false ->
                    snapshot(pid = 1, start = 1_000L, foreground = false).copy(
                        foregroundChangedAtMillis = at - 300_000L,
                        sampledAtMillis = at - 301_000L,
                    )
            }
        return classifySystemKill(reason, importance, telemetry, at, status)
    }

    private fun plan(
        exits: List<SystemKillExit>,
        snapshots: List<MemoryTelemetrySnapshot> = emptyList(),
        cutoff: Long = 0L,
        watermark: Long = 0L,
        recent: List<Long> = emptyList(),
        now: Long = 1_000_000L,
        perLaunchCap: Int = 3,
        dailyCap: Int = 5,
    ) = planSystemKillReports(exits, snapshots, cutoff, watermark, recent, now, perLaunchCap, dailyCap, mainProcess)

    /** One version-source case: what the exit, its process record and this install say. */
    private data class VersionCase(
        val name: String,
        val stamped: Boolean = false,
        /** The exited process's memory record: [recordedTelemetry], [unversionedTelemetry] or none. */
        val telemetry: MemoryTelemetrySnapshot? = null,
        val packageUpdatedMillis: Long? = 50_000L,
        val exitAt: Long = 100_000L,
        val isolated: Boolean? = null,
        /** [ApplicationExitInfo.getPackageUid] / [ApplicationExitInfo.getDefiningUid]; null when unavailable. */
        val packageUid: Int? = ownUid,
        val definingUid: Int? = ownUid,
        /** Expected crash_defining_uid_foreign; null when it is omitted. */
        val expectedDefiningUidForeign: Boolean? = false,
        val expectedSource: String,
        /** Expected fallback_version_code; null when none is sent. */
        val expectedFallbackCode: Long? = null,
    )

    private companion object {
        const val ownUid = 10_123
        const val providerUid = 10_456
    }

    private val reporter = SystemKillReporterBuild("1.26.0", 12600002L, packageUpdatedMillis = null, nowMillis = 200_000L)
    /** A record from an older install (1.23.0) than this one; its own record is what counts. */
    private val recordedTelemetry =
        snapshot(pid = 7, start = 1_000L, foreground = true).copy(versionCode = 12300004L, versionName = "1.23.0")
    private val unversionedTelemetry = snapshot(pid = 7, start = 1_000L, foreground = true)

    private val versionCases =
        listOf(
            VersionCase("stamp wins over record and install", stamped = true, telemetry = recordedTelemetry, expectedSource = "process_state_summary"),
            VersionCase("record wins over install", telemetry = recordedTelemetry, expectedSource = "last_process_state", expectedFallbackCode = 12300004L),
            VersionCase("updated before exit", expectedSource = "installed_before_exit", expectedFallbackCode = 12600002L),
            VersionCase("unversioned record, updated before exit", telemetry = unversionedTelemetry, expectedSource = "installed_before_exit", expectedFallbackCode = 12600002L),
            VersionCase("unversioned record, no update time", telemetry = unversionedTelemetry, packageUpdatedMillis = null, expectedSource = "unknown"),
            VersionCase("updated at the exit millisecond", packageUpdatedMillis = 100_000L, expectedSource = "unknown"),
            VersionCase("updated after exit", packageUpdatedMillis = 150_000L, expectedSource = "unknown"),
            VersionCase("no update time", packageUpdatedMillis = null, expectedSource = "unknown"),
            VersionCase("zero update time", packageUpdatedMillis = 0L, expectedSource = "unknown"),
            VersionCase("negative update time", packageUpdatedMillis = -1L, expectedSource = "unknown"),
            VersionCase("update and exit in the future", packageUpdatedMillis = 250_000L, exitAt = 300_000L, expectedSource = "unknown"),
            VersionCase("exit in the future", exitAt = 250_000L, expectedSource = "unknown"),
            // An external service's process runs the provider package's code, not our build.
            VersionCase("foreign defining uid, updated before exit", isolated = true, definingUid = providerUid, expectedDefiningUidForeign = true, expectedSource = "unknown"),
            // Our own isolated process runs our APK.
            VersionCase("own isolated process, updated before exit", isolated = true, expectedSource = "installed_before_exit", expectedFallbackCode = 12600002L),
            // A missing or invalid uid can't rule out a foreign defining uid.
            VersionCase("no defining uid", definingUid = null, expectedDefiningUidForeign = null, expectedSource = "unknown"),
            VersionCase("no package uid", packageUid = null, expectedDefiningUidForeign = null, expectedSource = "unknown"),
            VersionCase("zero defining uid", definingUid = 0, expectedDefiningUidForeign = null, expectedSource = "unknown"),
            VersionCase("negative package uid", packageUid = -1, expectedDefiningUidForeign = null, expectedSource = "unknown"),
            VersionCase("zero defining and package uid", definingUid = 0, packageUid = 0, expectedDefiningUidForeign = null, expectedSource = "unknown"),
        )

    private fun extrasFor(case: VersionCase): Map<String, Any?> =
        systemKillExtras(
            exit(pid = 7, at = case.exitAt).copy(
                build = if (case.stamped) stamp else null,
                isolatedProcess = case.isolated,
                packageUid = case.packageUid,
                definingUid = case.definingUid,
            ),
            case.telemetry,
            reporter = reporter.copy(packageUpdatedMillis = case.packageUpdatedMillis),
        )

    @Test
    fun versionSourceIsStampThenProcessRecordThenInstalledBeforeExitThenUnknown() {
        for (case in versionCases) {
            val extras = extrasFor(case)

            assertEquals(case.name, case.expectedSource, extras["crash_version_source"])
            assertEquals(case.name, case.expectedSource == "installed_before_exit", extras["crash_version_inferred"])
            assertEquals(case.name, case.expectedFallbackCode, extras["fallback_version_code"])
            assertEquals(
                case.name,
                mapOf(12300004L to "1.23.0", 12600002L to "1.26.0")[case.expectedFallbackCode],
                extras["fallback_version_name"],
            )
            // version_* is only ever the exit's own stamp.
            assertEquals(case.name, if (case.stamped) 12200001L else null, extras["version_code"])
            assertEquals(case.name, if (case.stamped) "1.22.0" else null, extras["version_name"])
        }
    }
}
