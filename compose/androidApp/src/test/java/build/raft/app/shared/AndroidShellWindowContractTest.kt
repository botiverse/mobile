package build.raft.app.shared

import build.raft.app.SLOCK_ROUTE_ACCOUNT_ID
import build.raft.app.SLOCK_ROUTE_ENVIRONMENT_ID
import build.raft.app.SLOCK_ROUTE_SERVER_ID
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
        val pageData = JSONObject().put(SlockCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-2")

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
            JSONObject().put(SlockCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-2")
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
    fun pageDataRoundTripPreservesEnabledDocumentIdentity() {
        val owner =
            AndroidShellRouteOwner(
                environmentId = "https://api.test/",
                accountId = "user-1",
                serverId = "server-a",
            ).normalizedOrNull()!!
        val canonical = owner.shellCanonicalUri()
        val source = JSONObject().put("slockNotificationMessageId", "message-1")
        val target =
            AndroidShellLaunchTarget(canonical, "server-a", documentShell = true, routeOwner = owner)

        val enriched = source.withAndroidShellLaunchTarget(target)
        val restored = androidShellLaunchTargetFromPageData(enriched)

        assertEquals(canonical, restored?.shellInstanceId)
        assertEquals("server-a", restored?.serverId)
        assertTrue(restored?.documentShell ?: false)
        assertEquals(owner, restored?.routeOwner)
        assertEquals("https://api.test", enriched.getString(SLOCK_ROUTE_ENVIRONMENT_ID))
        assertEquals("user-1", enriched.getString(SLOCK_ROUTE_ACCOUNT_ID))
        assertEquals("server-a", enriched.getString(SLOCK_ROUTE_SERVER_ID))
        assertTrue(enriched.getBoolean(ANDROID_DOCUMENT_SHELL_KEY))
        assertEquals("message-1", enriched.getString("slockNotificationMessageId"))
    }

    @Test
    fun rawLegacyDocumentPageDataNormalizesBeforeStoreScopeWhilePreservingRoute() {
        val source =
            JSONObject()
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, "server-shell:server-b")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                .put(SlockCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, "server-b")
                .put(SlockCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID, "thread-b")
                .put(SlockCmpActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID, "message-b")

        val normalized = normalizeAndroidAppShellPageData(source)

        assertEquals("android-default-app-shell", normalized.getString(ANDROID_SHELL_INSTANCE_ID_KEY))
        assertEquals("server-b", normalized.getString(ANDROID_SHELL_SERVER_ID_KEY))
        assertFalse(normalized.getBoolean(ANDROID_DOCUMENT_SHELL_KEY))
        assertEquals("thread-b", normalized.getString(SlockCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID))
        assertEquals("message-b", normalized.getString(SlockCmpActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID))
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
    fun enabledExplicitDocumentRouteUsesCanonicalOwnerShell() {
        val owner =
            AndroidShellRouteOwner(
                environmentId = "https://api.test/",
                accountId = "user-1",
                serverId = "server-b",
            ).normalizedOrNull()!!
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                .withAndroidShellRouteOwner(owner)

        val target = resolveAndroidShellLaunchTarget(pageData, multiShellHostEnabled = true)
        val taskLaunch = target.taskLaunchPlan(requestedTaskClear = true)

        val canonical = owner.shellCanonicalUri()
        assertEquals("slock://shell/https%3A%2F%2Fapi.test/user-1/server-b", canonical)
        assertEquals(canonical, target.shellInstanceId)
        assertEquals("server-b", target.serverId)
        assertEquals(owner, target.routeOwner)
        assertTrue(target.documentShell)
        assertFalse(taskLaunch.clearTask)
        assertEquals(canonical, taskLaunch.documentShellId)
        assertFalse(shouldUseGlobalHostDisplayMetrics(target))
    }

    @Test
    fun explicitDocumentRouteWithoutRouteOwnerFallsBackToDefaultShell() {
        val pageData =
            JSONObject()
                .put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)

        val target = resolveAndroidShellLaunchTarget(pageData, multiShellHostEnabled = true)

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertEquals("server-b", target.serverId)
        assertFalse(target.documentShell)
    }

    @Test
    fun enabledDocumentRouteReusesRegisteredOwnerShell() {
        val owner =
            AndroidShellRouteOwner(
                environmentId = "https://api.test",
                accountId = "user-1",
                serverId = "server-b",
            ).normalizedOrNull()!!
        AndroidShellWindowRegistry.register(
            AndroidShellLaunchTarget(
                shellInstanceId = owner.shellCanonicalUri(),
                serverId = "server-b",
                documentShell = true,
                routeOwner = owner,
            )
        )
        val target =
            resolveAndroidShellLaunchTarget(
                JSONObject()
                    .put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")
                    .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                    .withAndroidShellRouteOwner(owner),
                multiShellHostEnabled = true
            )

        assertEquals(owner.shellCanonicalUri(), target.shellInstanceId)
        assertEquals(owner, target.routeOwner)
        assertTrue(target.documentShell)
    }

    @Test
    fun disabledExplicitDocumentRouteFallsBackToDefaultShell() {
        val target =
            resolveAndroidShellLaunchTarget(
                JSONObject()
                    .put(ANDROID_SHELL_SERVER_ID_KEY, "server-b")
                    .put(ANDROID_DOCUMENT_SHELL_KEY, true),
                multiShellHostEnabled = false
            )

        assertEquals("android-default-app-shell", target.shellInstanceId)
        assertFalse(target.documentShell)
        assertTrue(shouldUseGlobalHostDisplayMetrics(target))
    }

    @Test
    fun configRecreateKeepsShellWhileRealFinishReleasesIt() {
        assertFalse(shouldReleaseAndroidShellWindow(isFinishing = true, isChangingConfigurations = true))
        assertFalse(shouldReleaseAndroidShellWindow(isFinishing = false, isChangingConfigurations = false))
        assertTrue(shouldReleaseAndroidShellWindow(isFinishing = true, isChangingConfigurations = false))
    }

}
