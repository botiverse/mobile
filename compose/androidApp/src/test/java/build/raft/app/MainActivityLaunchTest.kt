package build.raft.app

import build.raft.app.core.data.auth.AndroidMobileOAuth
import build.raft.app.shared.resolveAndroidShellLaunchTarget
import build.raft.app.shared.taskLaunchPlan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityLaunchTest {
    @Test
    fun mobileOAuthAdmissionRequiresTheExactHierarchicalCallbackRoute() {
        assertTrue(AndroidMobileOAuth.isCallbackRoute(BuildConfig.OAUTH_CALLBACK_SCHEME, "oauth", "/callback"))
        assertTrue(
            AndroidMobileOAuth.isCallbackUri(
                scheme = BuildConfig.OAUTH_CALLBACK_SCHEME,
                host = "oauth",
                path = "/callback",
                code = "handoff-code",
                error = null,
            )
        )
        assertFalse(
            AndroidMobileOAuth.isCallbackUri(
                scheme = "slockNotificationThreadId",
                host = null,
                path = null,
                code = null,
                error = null,
            )
        )
        assertFalse(
            AndroidMobileOAuth.isCallbackUri(
                scheme = BuildConfig.OAUTH_CALLBACK_SCHEME,
                host = "oauth",
                path = "/wrong",
                code = "handoff-code",
                error = null,
            )
        )
        assertFalse(
            AndroidMobileOAuth.isCallbackUri(
                scheme = BuildConfig.OAUTH_CALLBACK_SCHEME,
                host = "oauth",
                path = "/callback",
                code = null,
                error = null,
            )
        )
        assertFalse(AndroidMobileOAuth.isCallbackRoute("raft-dev", "oauth", "/callback"))
    }

    @Test
    fun unsupportedRaftWebLinkFallsBackToExternalBrowserAfterTypedHandlersDeclineIt() {
        assertTrue(
            shouldOpenUnsupportedRaftWebLinkExternally(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build"
            )
        )
        assertTrue(
            shouldOpenUnsupportedRaftWebLinkExternally(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.slock.ai"
            )
        )
        assertFalse(
            shouldOpenUnsupportedRaftWebLinkExternally(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "example.com"
            )
        )
        assertFalse(
            shouldOpenUnsupportedRaftWebLinkExternally(
                action = "android.intent.action.MAIN",
                dataScheme = "https",
                dataHost = "app.raft.build"
            )
        )
    }

    @Test
    fun exactEmailVerificationLinkIsConsumedBeforeGenericBrowserFallback() {
        assertEquals(
            "email-token-123",
            emailVerificationToken(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build",
                dataPath = "/",
                verifyToken = "email-token-123"
            )
        )
        assertFalse(
            shouldOpenUnsupportedRaftWebLinkExternally(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build",
                dataPath = "/",
                verifyToken = "email-token-123"
            )
        )
        assertEquals(
            null,
            emailVerificationToken(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "example.com",
                dataPath = "/",
                verifyToken = "email-token-123"
            )
        )
        assertTrue(
            shouldOpenUnsupportedRaftWebLinkExternally(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build",
                dataPath = "/",
                verifyToken = "   "
            )
        )
    }

    @Test
    fun coldVerificationLaunchesExactlyOneDestinationForItsOutcome() {
        assertEquals(
            EmailVerificationNavigation.OpenPostLoginDestination,
            emailVerificationNavigation(
                hasLiveShellInTask = false,
                result = EmailVerificationOutcome.Verified
            )
        )
        assertEquals(
            EmailVerificationNavigation.OpenAuthShell,
            emailVerificationNavigation(
                hasLiveShellInTask = false,
                result = EmailVerificationOutcome.Failure
            )
        )
        assertEquals(
            EmailVerificationNavigation.OpenAuthShell,
            emailVerificationNavigation(
                hasLiveShellInTask = false,
                result = EmailVerificationOutcome.LoginRequired
            )
        )
        assertEquals(
            EmailVerificationNavigation.OpenAuthShell,
            emailVerificationNavigation(
                hasLiveShellInTask = false,
                result = EmailVerificationOutcome.RefreshPending
            )
        )
    }

    @Test
    fun externalBrowserSelectionNeverRoutesTheFallbackBackToRaft() {
        assertEquals(
            "com.android.chrome",
            selectExternalWebHandlerPackage(
                ownPackage = "build.raft.app",
                defaultPackage = "build.raft.app",
                candidatePackages = listOf("build.raft.app", "com.android.chrome")
            )
        )
        assertEquals(
            "org.mozilla.firefox",
            selectExternalWebHandlerPackage(
                ownPackage = "build.raft.app",
                defaultPackage = "org.mozilla.firefox",
                candidatePackages = listOf("build.raft.app", "com.android.chrome", "org.mozilla.firefox")
            )
        )
        assertEquals(
            null,
            selectExternalWebHandlerPackage(
                ownPackage = "build.raft.app",
                defaultPackage = "build.raft.app",
                candidatePackages = listOf("build.raft.app")
            )
        )
    }

    @Test
    fun externalBrowserSelectionSkipsOtherInstalledRaftBuilds() {
        // An alpha build next to the release build: the release app is the App Link default, not a browser.
        assertEquals(
            "com.android.chrome",
            selectExternalWebHandlerPackage(
                ownPackage = "build.raft.app.alpha",
                defaultPackage = "build.raft.app",
                candidatePackages = listOf("build.raft.app", "build.raft.app.alpha", "com.android.chrome")
            )
        )
        assertEquals(
            null,
            selectExternalWebHandlerPackage(
                ownPackage = "build.raft.app",
                defaultPackage = "build.raft.app.alpha",
                candidatePackages = listOf("build.raft.app", "build.raft.app.alpha", "build.raft.app.connectedtest")
            )
        )
        // A package that merely shares the prefix text is not a Raft build.
        assertEquals(
            "build.raft.appstore",
            selectExternalWebHandlerPackage(
                ownPackage = "build.raft.app",
                defaultPackage = null,
                candidatePackages = listOf("build.raft.app", "build.raft.appstore")
            )
        )
    }

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
            raftLaunchSource(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build"
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
            raftLaunchSource(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "example.com"
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
    fun launchedFromHistorySkipsMainActivityOneShotIntakeHandlers() {
        assertTrue(shouldSkipMainActivityOneShotIntake(android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertTrue(
            shouldSkipMainActivityOneShotIntake(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
            )
        )
        assertFalse(shouldSkipMainActivityOneShotIntake(0))
        assertFalse(shouldSkipMainActivityOneShotIntake(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun defaultCustomTabsSelectionBindsOnlyWhenDefaultBrowserSupportsCustomTabs() {
        assertEquals(
            "com.android.chrome",
            selectDefaultCustomTabsBrowserPackage(
                ownPackage = "build.raft.app",
                defaultBrowserPackage = "com.android.chrome",
                customTabsPackages = listOf("com.huawei.browser", "com.android.chrome"),
            )
        )
        assertEquals(
            "org.mozilla.firefox",
            selectDefaultCustomTabsBrowserPackage(
                ownPackage = "build.raft.app",
                defaultBrowserPackage = "org.mozilla.firefox",
                customTabsPackages = listOf("com.android.chrome", "org.mozilla.firefox"),
            )
        )
        // Default browser lacks Custom Tabs: fall back to default browser instead of hijacking another browser.
        assertEquals(
            null,
            selectDefaultCustomTabsBrowserPackage(
                ownPackage = "build.raft.app",
                defaultBrowserPackage = "com.example.simplebrowser",
                customTabsPackages = listOf("com.android.chrome", "com.huawei.browser"),
            )
        )
        // No default set (resolver activity), null, or a Raft package: never bind Custom Tabs.
        assertEquals(
            null,
            selectDefaultCustomTabsBrowserPackage(
                ownPackage = "build.raft.app",
                defaultBrowserPackage = "android",
                customTabsPackages = listOf("com.android.chrome"),
            )
        )
        assertEquals(
            null,
            selectDefaultCustomTabsBrowserPackage(
                ownPackage = "build.raft.app",
                defaultBrowserPackage = null,
                customTabsPackages = listOf("com.android.chrome"),
            )
        )
        assertEquals(
            null,
            selectDefaultCustomTabsBrowserPackage(
                ownPackage = "build.raft.app",
                defaultBrowserPackage = "build.raft.app.alpha",
                customTabsPackages = listOf("build.raft.app.alpha", "com.android.chrome"),
            )
        )
    }

    @Test
    fun defaultCustomTabsAppliesOnlyToOrdinaryHttpAndHttpsLinks() {
        assertTrue(shouldPreferDefaultCustomTabs(inBrowser = false, mimeType = null, scheme = "https"))
        assertTrue(shouldPreferDefaultCustomTabs(inBrowser = false, mimeType = null, scheme = "HTTP"))
        assertFalse(shouldPreferDefaultCustomTabs(inBrowser = true, mimeType = null, scheme = "https"))
        assertFalse(shouldPreferDefaultCustomTabs(inBrowser = false, mimeType = "application/pdf", scheme = "https"))
        assertFalse(shouldPreferDefaultCustomTabs(inBrowser = false, mimeType = null, scheme = "mailto"))
        assertFalse(shouldPreferDefaultCustomTabs(inBrowser = false, mimeType = null, scheme = "tel"))
        assertFalse(shouldPreferDefaultCustomTabs(inBrowser = false, mimeType = null, scheme = null))
    }
}
