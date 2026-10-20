package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeColdStartSplashGateTest {
    @Test
    fun timeoutRemainingUsesElapsedTime() {
        val gate = HomeColdStartSplashGate(enabled = true, startedAtMillis = 1_000L)

        assertEquals(HOME_COLD_START_SPLASH_TIMEOUT_MILLIS, gate.millisUntilTimeout(nowMillis = 1_000L))
        assertEquals(1L, gate.millisUntilTimeout(nowMillis = 1_000L + HOME_COLD_START_SPLASH_TIMEOUT_MILLIS - 1L))
        assertEquals(0L, gate.millisUntilTimeout(nowMillis = 1_000L + HOME_COLD_START_SPLASH_TIMEOUT_MILLIS))
    }

    @Test
    fun installsOnlyForHomeAppShellLaunches() {
        assertTrue(shouldInstallHomeColdStartSplash(SlockCmpActivity.PAGE_APP_SHELL, null))
        assertTrue(
            shouldInstallHomeColdStartSplash(
                SlockCmpActivity.PAGE_APP_SHELL,
                """{"slockInitialTab":"home"}"""
            )
        )
        assertFalse(
            shouldInstallHomeColdStartSplash(
                SlockCmpActivity.PAGE_APP_SHELL,
                """{"slockInitialTab":"tasks"}"""
            )
        )
        assertFalse(shouldInstallHomeColdStartSplash(SlockCmpActivity.PAGE_THREAD, null))
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

    @Test
    fun disabledGateNeverKeepsSplash() {
        val gate = HomeColdStartSplashGate(enabled = false, startedAtMillis = 1_000L)

        assertFalse(gate.shouldKeepOnScreen(1_000L))
        assertFalse(gate.timedOut())
    }

    @Test
    fun identifiesHomeChildPageDataWithoutAcceptingMalformedPayloads() {
        assertTrue(isHomeAppShellChildPageData("""{"shellId":"shell-1","tab":"home"}"""))
        assertFalse(isHomeAppShellChildPageData("""{"tab":"members"}"""))
        assertFalse(isHomeAppShellChildPageData("not-json"))
    }

    @Test
    fun appShellUsesStartingThemeHostWithoutAffectingStandalonePages() {
        assertEquals(
            SlockAppShellActivity::class.java,
            slockKuiklyHostActivityClass(SlockCmpActivity.PAGE_APP_SHELL)
        )
        assertEquals(
            SlockCmpActivity::class.java,
            slockKuiklyHostActivityClass(SlockCmpActivity.PAGE_THREAD)
        )
        assertEquals(
            SlockAppShellActivity::class.java,
            slockAppShellHostActivityClass(
                AndroidShellLaunchTarget("android-default-app-shell", null, documentShell = false)
            )
        )
        assertEquals(
            SlockDocumentShellActivity::class.java,
            slockAppShellHostActivityClass(
                AndroidShellLaunchTarget("server-shell:server-a", "server-a", documentShell = true)
            )
        )
    }
}
