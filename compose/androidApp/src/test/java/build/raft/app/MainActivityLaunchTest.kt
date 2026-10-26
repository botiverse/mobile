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
    fun opaqueAndUnknownUrisNeverReadTypedCallbackQueries() {
        var queryReads = 0
        val queryReader = {
            queryReads += 1
            true
        }

        assertEquals(
            null,
            admittedUriQuery(
                isHierarchical = false,
                routeAccepted = true,
                readQuery = queryReader,
            )
        )
        assertEquals(
            null,
            admittedUriQuery(
                isHierarchical = true,
                routeAccepted = false,
                readQuery = queryReader,
            )
        )
        assertEquals(0, queryReads)
        assertEquals(
            true,
            admittedUriQuery(
                isHierarchical = true,
                routeAccepted = true,
                readQuery = queryReader,
            )
        )
        assertEquals(1, queryReads)
    }

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
    fun nonRootVerifyQueryRemainsAnUnsupportedWebLinkForBrowserFallback() {
        assertEquals(
            null,
            emailVerificationToken(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build",
                dataPath = "/channel/general",
                verifyToken = "email-token-123"
            )
        )
        assertTrue(
            shouldOpenUnsupportedRaftWebLinkExternally(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build",
                dataPath = "/channel/general",
                verifyToken = "email-token-123"
            )
        )
    }

    @Test
    fun warmVerificationAlwaysFinishesOnlyTheTrampoline() {
        EmailVerificationOutcome.entries.forEach { result ->
            assertEquals(
                EmailVerificationNavigation.FinishTrampoline,
                emailVerificationNavigation(hasLiveShellInTask = true, result = result)
            )
        }
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
    fun legacyHostAppViewIntentDoesNotClearExistingTask() {
        assertFalse(
            shouldClearTaskForSharedShell(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.slock.ai"
            )
        )
        assertEquals(
            "app_web_view",
            raftLaunchSource(
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
            raftLaunchSource(
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
        assertEquals("launcher", raftLaunchSource("android.intent.action.MAIN", null, null))
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
            raftLaunchSource(
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
    fun onlyABrowserRequestForAnUntypedWebUrlIsPinnedToABrowser() {
        assertTrue(openUrlUsesBrowserPackage(browser = true, mimeType = null, scheme = "https"))
        assertTrue(openUrlUsesBrowserPackage(browser = true, mimeType = null, scheme = "HTTP"))
        // An ordinary openUrl keeps the external-handler path; typed or non-web URLs never take a browser.
        assertFalse(openUrlUsesBrowserPackage(browser = false, mimeType = null, scheme = "https"))
        assertFalse(openUrlUsesBrowserPackage(browser = true, mimeType = "application/pdf", scheme = "https"))
        assertFalse(openUrlUsesBrowserPackage(browser = true, mimeType = null, scheme = "mailto"))
        assertFalse(openUrlUsesBrowserPackage(browser = true, mimeType = null, scheme = null))
    }

    @Test
    fun theBrowserForOpenInWebIsTheDefaultOneAndNeverARaftApp() {
        // Candidates of the no-host https probe: browsers (and any Raft build that takes https).
        val browsers = listOf("build.raft.app", "build.raft.app.debug", "org.mozilla.firefox", "com.android.chrome")
        assertEquals("com.android.chrome", selectExternalWebHandlerPackage("build.raft.app.debug", "com.android.chrome", browsers))
        // No default (the resolver), or a Raft app as the default: the first other browser.
        assertEquals("org.mozilla.firefox", selectExternalWebHandlerPackage("build.raft.app.debug", "android", browsers))
        assertEquals("org.mozilla.firefox", selectExternalWebHandlerPackage("build.raft.app.debug", "build.raft.app", browsers))
        // Only Raft apps: none, and openUrl takes its ordinary path.
        assertEquals(null, selectExternalWebHandlerPackage("build.raft.app", null, listOf("build.raft.app", "build.raft.app.alpha")))
    }
}
