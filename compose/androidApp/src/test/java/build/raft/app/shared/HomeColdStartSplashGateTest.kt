package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeColdStartSplashGateTest {
    @Test
    fun serverSwitchAndRebuiltWindowsSkipTheSplash() {
        assertFalse(
            shouldInstallHomeColdStartSplash(
                RaftCmpActivity.PAGE_APP_SHELL,
                """{"slockInitialTab":"home","slockServerSwitchTapEpochMillis":1700000000000}"""
            )
        )
        assertFalse(shouldInstallHomeColdStartSplash(RaftCmpActivity.PAGE_APP_SHELL, null, rebuiltOnNewIntent = true))
    }

    @Test
    fun homeFirstFrameReleasesSplashBeforeTimeout() {
        val gate = HomeColdStartSplashGate(enabled = true, startedAtMillis = 1_000L)

        assertTrue(gate.shouldKeepOnScreen(1_400L))
        assertTrue(gate.release())
        assertFalse(gate.shouldKeepOnScreen(1_401L))
        assertFalse(gate.release())
    }

    @Test
    fun timeoutTransitionsToBrandFallbackBeforeFinalRelease() {
        val gate = HomeColdStartSplashGate(enabled = true, startedAtMillis = 0L)

        assertTrue(gate.shouldKeepOnScreen(HOME_COLD_START_SPLASH_TIMEOUT_MILLIS - 1L))
        assertFalse(gate.timedOut())
        assertFalse(gate.shouldKeepOnScreen(HOME_COLD_START_SPLASH_TIMEOUT_MILLIS))
        assertTrue(gate.timedOut())
        assertTrue(gate.release())
        assertFalse(gate.release())
        assertEquals(0L, gate.millisUntilFallbackTimeout(HOME_COLD_START_SPLASH_FALLBACK_TIMEOUT_MILLIS))
    }
}
