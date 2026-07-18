package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeColdStartSplashGateTest {
    @Test
    fun timeoutRemainingUsesElapsedTime() {
        val gate = HomeColdStartSplashGate(enabled = true, startedAtMillis = 1_000L)

        assertEquals(1_500L, gate.millisUntilTimeout(nowMillis = 1_000L))
        assertEquals(1L, gate.millisUntilTimeout(nowMillis = 2_499L))
        assertEquals(0L, gate.millisUntilTimeout(nowMillis = 2_500L))
    }

    @Test
    fun installsOnlyForHomeAppShellLaunches() {
        assertTrue(shouldInstallHomeColdStartSplash(SlockKuiklyActivity.PAGE_APP_SHELL, null))
        assertTrue(
            shouldInstallHomeColdStartSplash(
                SlockKuiklyActivity.PAGE_APP_SHELL,
                """{"slockInitialTab":"home"}"""
            )
        )
        assertFalse(
            shouldInstallHomeColdStartSplash(
                SlockKuiklyActivity.PAGE_APP_SHELL,
                """{"slockInitialTab":"tasks"}"""
            )
        )
        assertFalse(shouldInstallHomeColdStartSplash(SlockKuiklyActivity.PAGE_THREAD, null))
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
    fun timeoutReleasesSplashAtOnePointFiveSeconds() {
        val gate = HomeColdStartSplashGate(enabled = true, startedAtMillis = 0L)

        assertTrue(gate.shouldKeepOnScreen(1_499L))
        assertFalse(gate.timedOut())
        assertFalse(gate.shouldKeepOnScreen(1_500L))
        assertTrue(gate.timedOut())
        assertFalse(gate.release())
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
            slockKuiklyHostActivityClass(SlockKuiklyActivity.PAGE_APP_SHELL)
        )
        assertEquals(
            SlockKuiklyActivity::class.java,
            slockKuiklyHostActivityClass(SlockKuiklyActivity.PAGE_THREAD)
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
