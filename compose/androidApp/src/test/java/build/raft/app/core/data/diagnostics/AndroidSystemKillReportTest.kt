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
    private val foregroundService = Importance.IMPORTANCE_FOREGROUND_SERVICE
    private val visible = Importance.IMPORTANCE_VISIBLE
    private val perceptible = Importance.IMPORTANCE_PERCEPTIBLE
    private val service = Importance.IMPORTANCE_SERVICE
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
    fun foregroundServiceKillOfABackgroundedProcessIsABackgroundKill() {
        // RaftBackgroundConnectionService keeps the backgrounded process at 125.
        for (reason in listOf(ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_SIGNALED)) {
            assertEquals(SystemKillDecision.ReportBackground, classify(reason, foregroundService, false))
            assertEquals(SystemKillDecision.ReportBackground, classify(reason, foregroundService, null))
            assertEquals(SystemKillDecision.ReportBackground, classify(reason, perceptible, null))
        }
    }

    @Test
    fun freshTelemetryRaisesABackgroundImportanceButNeverLowersAVisibleOne() {
        // The exit record is authoritative when it says foreground or visible.
        assertEquals(
            SystemKillDecision.Report,
            classify(ApplicationExitInfo.REASON_LOW_MEMORY, foreground, false)
        )
        assertEquals(
            SystemKillDecision.Report,
            classify(ApplicationExitInfo.REASON_LOW_MEMORY, service, true)
        )
    }

    @Test
    fun withoutTelemetryOnlyForegroundAndVisibleImportanceAreForeground() {
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
                assertEquals("$reason/$importance", SystemKillDecision.Report, classify(reason, importance, null))
            }
            for (importance in listOf(foregroundService, perceptible, service, cached, 1000)) {
                assertEquals(
                    "$reason/$importance",
                    SystemKillDecision.ReportBackground,
                    classify(reason, importance, null)
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
            assertEquals(SystemKillDecision.SkipUserInitiated, classify(reason, foreground, true))
        }
        for (reason in listOf(ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE)) {
            assertEquals(SystemKillDecision.SkipPackageChange, classify(reason, foreground, true))
        }
        for (reason in listOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR
        )) {
            assertEquals(SystemKillDecision.SkipReportedElsewhere, classify(reason, foreground, true))
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
        // Five system kills interleaved with user swipe-aways: no exit is dropped unclassified.
        val exits =
            (1..10).map { i ->
                exit(pid = i, at = i * 100L, reason = if (i % 2 == 0) ApplicationExitInfo.REASON_OTHER else ApplicationExitInfo.REASON_USER_REQUESTED)
            }

        val plan = plan(exits, perLaunchCap = 2)

        assertEquals(10, plan.size)
        assertEquals(
            listOf(
                SystemKillAction.Skip, SystemKillAction.Submit, // 100, 200
                SystemKillAction.Skip, SystemKillAction.Submit, // 300, 400
                SystemKillAction.Skip, // 500
                // Over this launch's visible cap: queued for a later launch, never dropped.
                SystemKillAction.Deferred, SystemKillAction.Skip,
                SystemKillAction.Deferred, SystemKillAction.Skip,
                SystemKillAction.Deferred
            ),
            plan.map { it.action }
        )
    }

    @Test
    fun theDailyCapDefersVisibleKillsAndCountsEarlierReports() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        val exits = (1..3).map { exit(pid = it, at = now - 1_000L + it, importance = foreground) }

        val plan = plan(exits, now = now, recent = listOf(now - day, now - 5L), dailyCap = 2)

        // One slot left today; an older report (a day ago) no longer counts. Visible kills over the
        // day's budget are queued, not dropped.
        assertEquals(
            listOf(SystemKillAction.Submit, SystemKillAction.Deferred, SystemKillAction.Deferred),
            plan.map { it.action }
        )
        assertEquals(listOf(now - 5L), decodeRecentReports("${now - day},${now - 5},junk", now))
    }

    @Test
    fun aFailedUploadRetriedNextLaunchStillMatchesItsTelemetry() {
        // Launch 1 saw the Huawei kill but the upload failed (watermark not moved). Launch 2's ring
        // still holds the killed process, next to launch 1's own entry.
        val killed = snapshot(pid = 4321, start = 1_000L, foreground = true).copy(sampledAtMillis = 39_000L)
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
        val killed = snapshot(pid = 4321, start = 1_000L, foreground = true).copy(sampledAtMillis = 39_000L)
        val sticky = snapshot(pid = 4400, start = 41_000L, foreground = false)
        val ring = listOf(killed, sticky)
        val exits =
            listOf(
                exit(pid = 4321, at = 40_000L, importance = cached),
                exit(pid = 4400, at = 60_000L, importance = foregroundService)
            )

        val plan = plan(exits, snapshots = ring)

        // Both are sent; the sticky process's kill is a background one.
        assertEquals(listOf(SystemKillAction.Submit, SystemKillAction.Submit), plan.map { it.action })
        assertEquals(listOf(SystemKillDecision.Report, SystemKillDecision.ReportBackground), plan.map { it.decision })
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
        assertNull(plan.single().telemetry)
        assertEquals(SystemKillDecision.ReportBackground, plan.single().decision)
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
        // The system's view (importance 400) is background; the app's telemetry still said foreground.
        assertEquals(false, extras["crash_foreground"])
        assertEquals(true, extras["crash_telemetry_foreground"])
        assertFalse(extras.containsKey("crash_background_duration_ms"))
        assertEquals(true, extras["crash_recent_foreground"])
        assertEquals("sample", extras["crash_recent_foreground_source"])
        assertEquals(10L, extras["crash_recent_foreground_age_s"])
        assertEquals(-1L, extras["crash_last_interaction_age_ms"])
        assertEquals("process_state_summary", extras["crash_version_source"])
        assertFalse(extras.containsKey("fallback_version_code"))

        val message = systemKillMessage(huawei, telemetry)
        assertTrue(message, message.startsWith("System kill: other (reasonCode=13 status=0 importance=400)"))
        assertTrue(
            message,
            message.contains(
                "last seen in foreground 10s before exit (in foreground since 50s before exit; system: background, importance 400)\n" +
                    "60s after launch, last RSS 585 MB (10s before exit), last PSS 500 MB (20s before exit), " +
                    "last trim running_low (5s before exit)"
            )
        )
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

    @Test
    fun crashForegroundFollowsTheExitImportance() {
        mapOf(
            foreground to true,
            visible to true,
            foregroundService to false,
            perceptible to false,
            service to false,
            cached to false,
            1000 to false,
        ).forEach { (importance, expected) ->
            val extras = systemKillExtras(exit(pid = 1, at = 5L, importance = importance), null)
            assertEquals("importance $importance", expected, extras["crash_foreground"])
            assertFalse(extras.containsKey("crash_telemetry_foreground"))
        }
    }

    @Test
    fun backgroundDurationIsTheTimeSinceTheLastMoveToBackground() {
        val telemetry = snapshot(pid = 7, start = 1_000L, foreground = false).copy(foregroundChangedAtMillis = 40_000L)

        val extras = systemKillExtras(exit(pid = 7, at = 100_000L, importance = cached), telemetry)

        assertEquals(60_000L, extras["crash_background_duration_ms"])
        assertEquals(false, extras["crash_foreground"])
        assertEquals(false, extras["crash_telemetry_foreground"])
    }

    @Test
    fun backgroundDurationIsOmittedWithoutTelemetry() {
        val extras = systemKillExtras(exit(pid = 7, at = 100_000L, importance = cached), null)

        assertFalse(extras.containsKey("crash_background_duration_ms"))
    }

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

    @Test
    fun reporterVersionIsSentWithEveryVersionSourceAndNeverAsTheExitVersion() {
        for (case in versionCases) {
            val extras = extrasFor(case)

            assertEquals(case.name, "1.26.0", extras["reporter_version_name"])
            assertEquals(case.name, 12600002L, extras["reporter_version_code"])
            assertNotEquals(case.name, 12600002L, extras["version_code"])
        }
        // Without a reporter build (older call sites) nothing is inferred and no reporter field is sent.
        val bare = systemKillExtras(exit(pid = 7, at = 100_000L).copy(build = null), null)
        assertEquals("unknown", bare["crash_version_source"])
        assertFalse(bare.containsKey("reporter_version_code"))
    }

    @Test
    fun extrasMarkAnIsolatedProcessOnlyWhenKnown() {
        for (isolated in listOf(true, false, null)) {
            val extras = systemKillExtras(exit(pid = 7, at = 100_000L).copy(isolatedProcess = isolated), null)

            assertEquals("$isolated", isolated, extras["crash_process_isolated"])
            assertEquals("$isolated", isolated != null, extras.containsKey("crash_process_isolated"))
        }
    }

    @Test
    fun extrasMarkAForeignDefiningUidOnlyWhenBothUidsAreValid() {
        for (case in versionCases) {
            val extras = extrasFor(case)
            assertEquals(case.name, case.expectedDefiningUidForeign, extras["crash_defining_uid_foreign"])
            assertEquals(case.name, case.expectedDefiningUidForeign != null, extras.containsKey("crash_defining_uid_foreign"))
        }
    }

    @Test
    fun extrasCarryTheProcessNameAndTheDescriptionCutTo200Chars() {
        val long = "x".repeat(250)
        val extras = systemKillExtras(exit(pid = 7, at = 100_000L).copy(description = long), null)

        assertEquals(mainProcess, extras["crash_process_name"])
        assertEquals("x".repeat(200), extras["crash_exit_description"])

        val withoutDescription = systemKillExtras(exit(pid = 7, at = 100_000L).copy(description = "  "), null)
        assertFalse(withoutDescription.containsKey("crash_exit_description"))
    }

    @Test
    fun exitsOfSecondaryProcessesAreSkippedAndPassTheWatermark() {
        val items =
            plan(
                exits =
                    listOf(
                        exit(pid = 1, at = 10L, processName = "$mainProcess:keepalive"),
                        exit(pid = 2, at = 20L, processName = "com.google.android.webview:sandboxed_process0"),
                        exit(pid = 3, at = 30L),
                        exit(pid = 4, at = 40L, processName = ""),
                    ),
            )

        assertEquals(
            listOf(
                SystemKillDecision.SkipSecondaryProcess,
                SystemKillDecision.SkipSecondaryProcess,
                SystemKillDecision.Report,
                SystemKillDecision.Report,
            ),
            items.map { it.decision },
        )
        assertEquals(
            listOf(SystemKillAction.Skip, SystemKillAction.Skip, SystemKillAction.Submit, SystemKillAction.Submit),
            items.map { it.action },
        )
    }

    @Test
    fun aSignaledExitWithACrashSignalIsLeftToHandsCrash() {
        val visible = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        listOf(4, 6, 7, 8, 11).forEach { signal ->
            assertEquals(
                SystemKillDecision.SkipReportedElsewhere,
                classify(ApplicationExitInfo.REASON_SIGNALED, visible, true, status = signal),
            )
        }
        // SIGKILL is the system killing the process: still a system kill.
        assertEquals(SystemKillDecision.Report, classify(ApplicationExitInfo.REASON_SIGNALED, visible, true, status = 9))
    }
}
