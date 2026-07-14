package build.raft.app.shared

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidShellWindowContractTest {
    @After
    fun tearDown() {
        AndroidShellWindowRegistry.clearForTest()
    }

    @Test
    fun notificationServerCreatesStableDocumentShellIdentity() {
        val pageData = JSONObject().put(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-2")

        val target = resolveAndroidShellLaunchTarget(pageData)

        assertEquals("server-shell:server-2", target.shellInstanceId)
        assertEquals("server-2", target.serverId)
        assertTrue(target.documentShell)
        val enriched = pageData.withAndroidShellLaunchTarget(target)
        assertEquals(target.shellInstanceId, enriched.getString(ANDROID_SHELL_INSTANCE_ID_KEY))
        assertEquals("server-2", enriched.getString(ANDROID_SHELL_SERVER_ID_KEY))
    }

    @Test
    fun existingServerWindowWinsOverCreatingAnotherDocument() {
        AndroidShellWindowRegistry.register(
            AndroidShellLaunchTarget("shell-existing", "server-2", documentShell = true)
        )

        val target = resolveAndroidShellLaunchTarget(JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-2"))

        assertEquals("shell-existing", target.shellInstanceId)
        assertTrue(target.documentShell)
    }

    @Test
    fun notificationServerReusesDefaultShellAlreadyShowingThatServer() {
        AndroidShellWindowRegistry.register(
            AndroidShellLaunchTarget("android-default-app-shell", "server-2", documentShell = false)
        )

        val target = resolveAndroidShellLaunchTarget(
            JSONObject().put(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-2")
        )

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-2", target.serverId)
        assertFalse(target.documentShell)
    }

    @Test
    fun ordinaryLauncherUsesDefaultNonDocumentShell() {
        val target = resolveAndroidShellLaunchTarget(JSONObject())
        AndroidShellWindowRegistry.register(target)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertNull(target.serverId)
        assertFalse(target.documentShell)
    }

    @Test
    fun serverDocumentShellNeverFallsBackToDefaultTaskClear() {
        val target = resolveAndroidShellLaunchTarget(
            JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-2")
        )

        val taskLaunch = target.taskLaunchPlan(requestedTaskClear = true)

        assertFalse(taskLaunch.clearTask)
        assertEquals("server-shell:server-2", taskLaunch.documentShellId)
    }

    @Test
    fun pageDataRoundTripKeepsIdentityAndIntent() {
        val source = JSONObject().put("slockNotificationMessageId", "message-1")
        val target = AndroidShellLaunchTarget("shell-a", "server-a", documentShell = true)

        val enriched = source.withAndroidShellLaunchTarget(target)
        val restored = androidShellLaunchTargetFromPageData(enriched)

        assertEquals(target, restored)
        assertTrue(enriched.getBoolean(ANDROID_DOCUMENT_SHELL_KEY))
        assertEquals("message-1", enriched.getString("slockNotificationMessageId"))
    }

    @Test
    fun firstLoginServerSelectionStaysInDefaultNonDocumentTask() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, "legacy-app-shell")
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-a")
                .put(ANDROID_DOCUMENT_SHELL_KEY, false)

        val target = androidShellLaunchTargetFromPageData(pageData)
        val taskLaunch = target?.taskLaunchPlan(requestedTaskClear = false)

        assertEquals("android-default-app-shell", target?.shellInstanceId)
        assertEquals("server-a", target?.serverId)
        assertFalse(target?.documentShell ?: true)
        assertFalse(taskLaunch?.clearTask ?: true)
        assertNull(taskLaunch?.documentShellId)
    }

    @Test
    fun explicitNonDocumentServerRouteStaysDefaultEvenWithoutShellId() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-a")
                .put(ANDROID_DOCUMENT_SHELL_KEY, false)

        val target = resolveAndroidShellLaunchTarget(pageData)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-a", target.serverId)
        assertFalse(target.documentShell)
    }

    @Test
    fun defaultShellIdentityCannotBePromotedToDocumentMode() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, "android-default-app-shell")
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-a")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)

        val target = androidShellLaunchTargetFromPageData(pageData)

        assertEquals("android-default-app-shell", target?.shellInstanceId)
        assertEquals("server-a", target?.serverId)
        assertFalse(target?.documentShell ?: true)
    }

    @Test
    fun shellIdentityWithoutDocumentMarkerCannotCreateDocumentTask() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, "server-shell:server-a")
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-a")

        val target = androidShellLaunchTargetFromPageData(pageData)

        assertEquals("android-default-app-shell", target?.shellInstanceId)
        assertEquals("server-a", target?.serverId)
        assertFalse(target?.documentShell ?: true)
    }

    @Test
    fun chooserCrossServerRouteWithoutOldShellIdResolvesTargetServerDocument() {
        val replacementPageData = JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")

        val target = resolveAndroidShellLaunchTarget(replacementPageData)

        assertEquals("server-shell:server-b", target.shellInstanceId)
        assertEquals("server-b", target.serverId)
        assertTrue(target.documentShell)
    }

    @Test
    fun configRecreateKeepsShellWhileRealFinishReleasesIt() {
        assertFalse(shouldReleaseAndroidShellWindow(isFinishing = true, isChangingConfigurations = true))
        assertFalse(shouldReleaseAndroidShellWindow(isFinishing = false, isChangingConfigurations = false))
        assertTrue(shouldReleaseAndroidShellWindow(isFinishing = true, isChangingConfigurations = false))
    }

    @Test
    fun documentWindowUsesActivityMetricsWithoutOverwritingDefaultShellAdapter() {
        assertTrue(shouldUseGlobalHostDisplayMetrics(null))
        assertTrue(
            shouldUseGlobalHostDisplayMetrics(
                AndroidShellLaunchTarget("android-default-app-shell", null, documentShell = false)
            )
        )
        assertFalse(
            shouldUseGlobalHostDisplayMetrics(
                AndroidShellLaunchTarget("server-shell:s1", "s1", documentShell = true)
            )
        )
    }
}
