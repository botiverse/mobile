package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.ApplicationExitInfo
import build.raft.app.core.data.diagnostics.RaftStartupPhaseMarker.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSystemKillReportRestartTest {
    private val mainProcess = "build.raft.app"

    private fun exit(
        pid: Int,
        at: Long,
        importance: Int = IMPORTANCE_CACHED,
        reason: Int = ApplicationExitInfo.REASON_OTHER,
        processName: String = mainProcess,
    ) = SystemKillExit(
        pid = pid,
        timestampMillis = at,
        reasonCode = reason,
        status = 0,
        importance = importance,
        pssKb = 0L,
        rssKb = 0L,
        build = null,
        processName = processName,
    )

    private fun startupRecord(
        pid: Int,
        start: Long,
        phase: Phase,
        phaseAt: Long = start + 100L,
        firstActivityAt: Long = -1L,
        sticky: Boolean = false,
        processName: String = mainProcess,
    ) = StartupPhaseRecord(
        pid = pid,
        processStartMillis = start,
        processName = processName,
        phase = phase,
        phaseAtMillis = phaseAt,
        firstActivityAtMillis = firstActivityAt,
        stickyServiceRestart = sticky,
    )

    @Test
    fun directUserLaunchSetsOutageEqualToRestartDelay() {
        val kill = exit(pid = 100, at = 1_000_000L)
        val restart =
            resolveSystemKillRestart(
                exit = kill,
                startupRecords = emptyList(),
                currentProcessStartMillis = 1_600_000L,
                currentFirstActivityAtMillis = 1_600_450L,
                currentStickyRestart = false,
            )

        assertEquals(SystemKillRestartInfo.KindUserLaunch, restart.restartKind)
        assertFalse(restart.restartedBySystem)
        assertEquals(1_600_000L, restart.restartAtMillis)
        assertEquals(600_000L, restart.restartDelayMs)
        // Direct user launch: outage equals restart delay rather than adding Activity init time.
        assertEquals(600_000L, restart.outageMs)
    }

    @Test
    fun stickyServiceRestartFollowedByLaterUiLaunchTracksBothDelayAndOutage() {
        val kill = exit(pid = 100, at = 1_000_000L, importance = IMPORTANCE_FOREGROUND_SERVICE)
        val stickyProcess =
            startupRecord(
                pid = 101,
                start = 1_005_000L,
                phase = Phase.ApplicationCreated,
                firstActivityAt = -1L,
                sticky = true,
            )

        val restart =
            resolveSystemKillRestart(
                exit = kill,
                startupRecords = listOf(stickyProcess),
                currentProcessStartMillis = 4_600_000L,
                currentFirstActivityAtMillis = 4_600_500L,
                currentStickyRestart = false,
            )

        assertEquals(SystemKillRestartInfo.KindStickyService, restart.restartKind)
        assertTrue(restart.restartedBySystem)
        assertEquals(1_005_000L, restart.restartAtMillis)
        assertEquals(5_000L, restart.restartDelayMs)
        assertEquals(3_600_500L, restart.outageMs)
    }

    @Test
    fun stickyServiceRestartThatLaterOpensActivityInSameProcessStaysStickyService() {
        val kill = exit(pid = 100, at = 1_000_000L)
        val restart =
            resolveSystemKillRestart(
                exit = kill,
                startupRecords = emptyList(),
                currentProcessStartMillis = 1_004_000L,
                currentFirstActivityAtMillis = 2_000_000L,
                currentStickyRestart = true,
            )

        assertEquals(SystemKillRestartInfo.KindStickyService, restart.restartKind)
        assertTrue(restart.restartedBySystem)
        assertEquals(1_004_000L, restart.restartAtMillis)
        assertEquals(4_000L, restart.restartDelayMs)
        assertEquals(1_000_000L, restart.outageMs)
    }

    @Test
    fun backgroundProcessWakeWithoutStickyFlagReportsBackgroundProcessAndNullOutageUntilUi() {
        val kill = exit(pid = 100, at = 1_000_000L)
        val bgWake =
            startupRecord(
                pid = 101,
                start = 1_010_000L,
                phase = Phase.ApplicationCreated,
                firstActivityAt = -1L,
                sticky = false,
            )

        // Still in the background-started process (no activity created yet).
        val noUiYet =
            resolveSystemKillRestart(
                exit = kill,
                startupRecords = listOf(bgWake),
                currentProcessStartMillis = 1_010_000L,
                currentFirstActivityAtMillis = -1L,
                currentStickyRestart = false,
            )
        assertEquals(SystemKillRestartInfo.KindBackgroundProcess, noUiYet.restartKind)
        assertTrue(noUiYet.restartedBySystem)
        assertEquals(10_000L, noUiYet.restartDelayMs)
        assertNull(noUiYet.outageMs)

        // Later user launches UI in a newer process.
        val afterUi =
            resolveSystemKillRestart(
                exit = kill,
                startupRecords = listOf(bgWake),
                currentProcessStartMillis = 1_300_000L,
                currentFirstActivityAtMillis = 1_300_300L,
                currentStickyRestart = false,
            )
        assertEquals(SystemKillRestartInfo.KindBackgroundProcess, afterUi.restartKind)
        assertTrue(afterUi.restartedBySystem)
        assertEquals(10_000L, afterUi.restartDelayMs)
        assertEquals(300_300L, afterUi.outageMs)
    }

    @Test
    fun multiExitChainResolvesEachExitAgainstItsImmediateSubsequentProcess() {
        val exitA = exit(pid = 10, at = 10_000L)
        val stickyB =
            startupRecord(
                pid = 11,
                start = 15_000L,
                phase = Phase.ApplicationCreated,
                firstActivityAt = -1L,
                sticky = true,
            )
        val exitB = exit(pid = 11, at = 25_000L)
        val userCStart = 500_000L
        val userCActivity = 500_250L
        val records =
            listOf(
                startupRecord(pid = 10, start = 1_000L, phase = Phase.FirstFrame, firstActivityAt = 1_200L),
                stickyB,
                // Secondary process record must not confuse main-process restart resolution.
                startupRecord(
                    pid = 99,
                    start = 12_000L,
                    phase = Phase.ApplicationCreated,
                    processName = "$mainProcess:keepalive",
                ),
            )

        val restartA =
            resolveSystemKillRestart(
                exit = exitA,
                startupRecords = records,
                currentProcessStartMillis = userCStart,
                currentFirstActivityAtMillis = userCActivity,
                currentStickyRestart = false,
            )
        assertEquals(SystemKillRestartInfo.KindStickyService, restartA.restartKind)
        assertTrue(restartA.restartedBySystem)
        assertEquals(15_000L, restartA.restartAtMillis)
        assertEquals(5_000L, restartA.restartDelayMs)
        assertEquals(490_250L, restartA.outageMs)

        val restartB =
            resolveSystemKillRestart(
                exit = exitB,
                startupRecords = records,
                currentProcessStartMillis = userCStart,
                currentFirstActivityAtMillis = userCActivity,
                currentStickyRestart = false,
            )
        assertEquals(SystemKillRestartInfo.KindUserLaunch, restartB.restartKind)
        assertFalse(restartB.restartedBySystem)
        assertEquals(userCStart, restartB.restartAtMillis)
        assertEquals(475_000L, restartB.restartDelayMs)
        assertEquals(475_000L, restartB.outageMs)
    }

    @Test
    fun clockRollbackProducesUnknownOrNullDurationsInsteadOfNegativeValues() {
        val kill = exit(pid = 100, at = 1_000_000L)

        // Current process start appears earlier than exit timestamp due to clock rollback.
        val rolledBackStart =
            resolveSystemKillRestart(
                exit = kill,
                startupRecords = emptyList(),
                currentProcessStartMillis = 900_000L,
                currentFirstActivityAtMillis = 900_300L,
                currentStickyRestart = false,
            )
        assertEquals(SystemKillRestartInfo.KindUnknown, rolledBackStart.restartKind)
        assertFalse(rolledBackStart.restartedBySystem)
        assertNull(rolledBackStart.restartAtMillis)
        assertNull(rolledBackStart.restartDelayMs)
        assertNull(rolledBackStart.outageMs)

        // Clock rolled back between sticky restart and Activity creation.
        val rolledBackActivity =
            resolveSystemKillRestart(
                exit = kill,
                startupRecords = emptyList(),
                currentProcessStartMillis = 1_005_000L,
                currentFirstActivityAtMillis = 950_000L,
                currentStickyRestart = true,
            )
        assertEquals(SystemKillRestartInfo.KindStickyService, rolledBackActivity.restartKind)
        assertEquals(5_000L, rolledBackActivity.restartDelayMs)
        assertNull(rolledBackActivity.outageMs)
    }

    @Test
    fun extrasAndMessageCarryRestartFieldsOnlyWhenProvided() {
        val kill = exit(pid = 100, at = 1_000_000L)
        val withoutRestartExtras = systemKillExtras(kill, telemetry = null)
        assertFalse(withoutRestartExtras.containsKey("crash_restart_kind"))
        assertFalse(withoutRestartExtras.containsKey("crash_restarted_by_system"))
        assertFalse(withoutRestartExtras.containsKey("crash_outage_ms"))
        assertFalse(systemKillMessage(kill, telemetry = null).contains("restart "))

        val restart =
            SystemKillRestartInfo(
                restartKind = SystemKillRestartInfo.KindStickyService,
                restartedBySystem = true,
                restartAtMillis = 1_005_000L,
                restartDelayMs = 5_000L,
                outageMs = 125_000L,
            )
        val extras = systemKillExtras(kill, telemetry = null, restart = restart)
        assertEquals("sticky_service", extras["crash_restart_kind"])
        assertEquals(true, extras["crash_restarted_by_system"])
        assertEquals(1_005_000L, extras["crash_restart_at"])
        assertEquals(5_000L, extras["crash_restart_delay_ms"])
        assertEquals(125_000L, extras["crash_outage_ms"])

        val message = systemKillMessage(kill, telemetry = null, restart = restart)
        assertTrue(message, message.contains("restart sticky_service (by_system=true, delay=5s, outage=125s)"))

        val unknownExtras =
            systemKillExtras(
                kill,
                telemetry = null,
                restart = SystemKillRestartInfo(SystemKillRestartInfo.KindUnknown, false, null, null, null),
            )
        assertEquals("unknown", unknownExtras["crash_restart_kind"])
        assertEquals(false, unknownExtras["crash_restarted_by_system"])
        assertFalse(unknownExtras.containsKey("crash_restart_at"))
        assertFalse(unknownExtras.containsKey("crash_restart_delay_ms"))
        assertFalse(unknownExtras.containsKey("crash_outage_ms"))
    }
}
