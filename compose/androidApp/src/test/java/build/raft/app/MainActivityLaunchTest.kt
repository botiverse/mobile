package build.raft.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityLaunchTest {
    @Test
    fun raftAppViewIntentDoesNotClearExistingTask() {
        assertFalse(
            shouldClearTaskForSharedShell(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build"
            )
        )
        assertEquals(
            "app_web_view",
            slockLaunchSource(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build"
            )
        )
    }

    @Test
    fun slockAppViewIntentDoesNotClearExistingTask() {
        assertFalse(
            shouldClearTaskForSharedShell(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.slock.ai"
            )
        )
        assertEquals(
            "app_web_view",
            slockLaunchSource(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.slock.ai"
            )
        )
    }

    @Test
    fun externalViewIntentKeepsTaskReset() {
        assertTrue(
            shouldClearTaskForSharedShell(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "example.com"
            )
        )
        assertEquals(
            "external_view",
            slockLaunchSource(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "example.com"
            )
        )
    }

    @Test
    fun launcherIntentKeepsTaskReset() {
        assertTrue(shouldClearTaskForSharedShell("android.intent.action.MAIN", null, null))
        assertEquals("launcher", slockLaunchSource("android.intent.action.MAIN", null, null))
    }

    @Test
    fun appShellPageDataIntentDoesNotClearExistingTask() {
        assertFalse(
            shouldClearTaskForSharedShell(
                action = null,
                dataScheme = null,
                dataHost = null,
                appShellPageData = true
            )
        )
        assertEquals(
            "app_shell_intent",
            slockLaunchSource(
                action = null,
                dataScheme = null,
                dataHost = null,
                appShellPageData = true
            )
        )
    }
}
