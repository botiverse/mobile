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
    fun notificationServerUsesDefaultSingleShellIdentity() {
        val pageData = JSONObject().put(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-2")

        val target = resolveAndroidShellLaunchTarget(pageData)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-2", target.serverId)
        assertFalse(target.documentShell)
        val enriched = pageData.withAndroidShellLaunchTarget(target)
        assertEquals(target.shellInstanceId, enriched.getString(ANDROID_SHELL_INSTANCE_ID_KEY))
        assertEquals("server-2", enriched.getString(ANDROID_SHELL_SERVER_ID_KEY))
    }

    @Test
    fun legacyRegisteredServerWindowCannotCreateAnotherShell() {
        AndroidShellWindowRegistry.register(
            AndroidShellLaunchTarget("shell-existing", "server-2", documentShell = true)
        )

        val target = resolveAndroidShellLaunchTarget(JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-2"))

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertFalse(target.documentShell)
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
        AndroidShellWindowRegistry.register(target, taskId = 41)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertNull(target.serverId)
        assertFalse(target.documentShell)
        assertTrue(AndroidShellWindowRegistry.hasShellInTask(41))
        assertFalse(AndroidShellWindowRegistry.hasShellInTask(42))
    }

    @Test
    fun legacyDocumentRequestCannotSuppressRequestedTaskClear() {
        val target = resolveAndroidShellLaunchTarget(
            JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-2")
        )

        val taskLaunch = target.taskLaunchPlan(requestedTaskClear = true)

        assertTrue(taskLaunch.clearTask)
    }

    @Test
    fun pageDataRoundTripNormalizesLegacyDocumentIdentity() {
        val source = JSONObject().put("slockNotificationMessageId", "message-1")
        val target = AndroidShellLaunchTarget("shell-a", "server-a", documentShell = true)

        val enriched = source.withAndroidShellLaunchTarget(target)
        val restored = androidShellLaunchTargetFromPageData(enriched)

        assertEquals("android-default-app-shell", restored?.shellInstanceId)
        assertEquals("server-a", restored?.serverId)
        assertFalse(restored?.documentShell ?: true)
        assertFalse(enriched.getBoolean(ANDROID_DOCUMENT_SHELL_KEY))
        assertEquals("message-1", enriched.getString("slockNotificationMessageId"))
    }

    @Test
    fun rawLegacyDocumentPageDataNormalizesBeforeStoreScopeWhilePreservingRoute() {
        val source =
            JSONObject()
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, "server-shell:server-b")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                .put(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-b")
                .put(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID, "thread-b")
                .put(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID, "message-b")

        val normalized = normalizeAndroidAppShellPageData(source)

        assertEquals("android-default-app-shell", normalized.getString(ANDROID_SHELL_INSTANCE_ID_KEY))
        assertEquals("server-b", normalized.getString(ANDROID_SHELL_SERVER_ID_KEY))
        assertFalse(normalized.getBoolean(ANDROID_DOCUMENT_SHELL_KEY))
        assertEquals("thread-b", normalized.getString(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID))
        assertEquals("message-b", normalized.getString(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID))
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
    fun chooserCrossServerRouteWithoutOldShellIdUsesDefaultShell() {
        val replacementPageData = JSONObject().put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")

        val target = resolveAndroidShellLaunchTarget(replacementPageData)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-b", target.serverId)
        assertFalse(target.documentShell)
    }

    @Test
    fun configRecreateKeepsShellWhileRealFinishReleasesIt() {
        assertFalse(shouldReleaseAndroidShellWindow(isFinishing = true, isChangingConfigurations = true))
        assertFalse(shouldReleaseAndroidShellWindow(isFinishing = false, isChangingConfigurations = false))
        assertTrue(shouldReleaseAndroidShellWindow(isFinishing = true, isChangingConfigurations = false))
    }

}
