package build.raft.app

import build.raft.app.shared.resolveAndroidShellLaunchTarget
import build.raft.app.shared.taskLaunchPlan
import org.json.JSONObject
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
    fun launcherIntentKeepsDefaultShellTaskPolicyAndNeverCreatesDocument() {
        val target = resolveAndroidShellLaunchTarget(JSONObject())
        val taskLaunch = target.taskLaunchPlan(
            shouldClearTaskForSharedShell("android.intent.action.MAIN", null, null)
        )

        assertTrue(taskLaunch.clearTask)
        assertEquals(null, taskLaunch.documentShellId)
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

    @Test
    fun postLoginWithoutSelectedServerReturnsToChooserForSessionRestore() {
        assertEquals(PostLoginDestination.ChooseServer, postLoginDestination(null))
        assertEquals(PostLoginDestination.ChooseServer, postLoginDestination(""))
        assertEquals(PostLoginDestination.AppShell, postLoginDestination("server-1"))
    }

    @Test
    fun postLoginAppShellPageDataSuppressesRestoreLanding() {
        val pageData = postLoginAppShellPageData()

        assertTrue(pageData.optBoolean("slockSuppressRestoreLanding", false))
        assertTrue(pageData.optString("slockAuthGateInstanceId").isNotBlank())
    }

    @Test
    fun mobileOAuthLoginErrorPageDataReturnsToSocialLogin() {
        val pageData = mobileOAuthLoginErrorPageData("account_conflict")

        assertEquals("account_conflict", pageData.getString("slockAuthMessage"))
        assertEquals("google,github", pageData.getString("slockSocialAuthProviders"))
    }
}
