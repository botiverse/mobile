package build.raft.app

import build.raft.app.core.data.auth.AndroidMobileOAuth
import build.raft.app.shared.resolveAndroidShellLaunchTarget
import build.raft.app.shared.taskLaunchPlan
import java.io.File
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
    fun coldAndWarmLaunchesUseTheSameTypedCallbackAdmissionChain() {
        val source = mainActivitySource()

        assertEquals(2, Regex("\\bhandleLaunchIntent\\(intent\\)").findAll(source).count())
        assertEquals(1, Regex("private fun handleLaunchIntent\\(intent: Intent\\?\\)").findAll(source).count())
        val handler =
            source.substringAfter("private fun handleLaunchIntent(intent: Intent?)")
                .substringBefore("private fun handleUnsupportedSlockWebLink")
        assertTrue(handler.indexOf("handleMobileOAuthCallback") < handler.indexOf("handleSocialAuthCallback"))
        assertTrue(handler.indexOf("handleSocialAuthCallback") < handler.indexOf("handleEmailVerificationLink"))
        assertTrue(handler.indexOf("handleEmailVerificationLink") < handler.indexOf("handleMobileDownloadAppLink"))
        assertTrue(handler.indexOf("handleMobileDownloadAppLink") < handler.indexOf("handleUnsupportedSlockWebLink"))
        assertTrue(handler.indexOf("handleUnsupportedSlockWebLink") < handler.indexOf("openSharedShell"))
    }

    @Test
    fun mobileDownloadAdmissionAcceptsOnlyCanonicalHttpsHostsAndExactPath() {
        listOf("app.raft.build", "raft-app-staging.botiverse.dev").forEach { host ->
            assertTrue(
                isMobileDownloadAppLink(
                    action = "android.intent.action.VIEW",
                    dataScheme = "https",
                    dataHost = host,
                    dataPath = "/download",
                )
            )
            assertTrue(
                isMobileDownloadAppLink(
                    action = "android.intent.action.VIEW",
                    dataScheme = "HTTPS",
                    dataHost = host.uppercase(),
                    dataPath = "/download",
                )
            )
        }

        listOf(null, "", "/", "/download/", "/Download", "/download/anything").forEach { path ->
            assertFalse(
                isMobileDownloadAppLink(
                    action = "android.intent.action.VIEW",
                    dataScheme = "https",
                    dataHost = "app.raft.build",
                    dataPath = path,
                )
            )
        }
        assertFalse(isMobileDownloadAppLink("android.intent.action.MAIN", "https", "app.raft.build", "/download"))
        assertFalse(isMobileDownloadAppLink("android.intent.action.VIEW", "http", "app.raft.build", "/download"))
        assertFalse(isMobileDownloadAppLink("android.intent.action.VIEW", "https", "app.slock.ai", "/download"))
        assertFalse(isMobileDownloadAppLink("android.intent.action.VIEW", "https", "example.com", "/download"))
    }

    @Test
    fun mobileDownloadLinkIsConsumedBeforeGenericBrowserFallback() {
        listOf("app.raft.build", "raft-app-staging.botiverse.dev").forEach { host ->
            assertTrue(
                isMobileDownloadAppLink(
                    action = "android.intent.action.VIEW",
                    dataScheme = "https",
                    dataHost = host,
                    dataPath = "/download",
                )
            )
            assertTrue(
                "the host stays in the Web-link family so VIEW launches preserve the existing task",
                shouldOpenUnsupportedSlockWebLinkExternally(
                    action = "android.intent.action.VIEW",
                    dataScheme = "https",
                    dataHost = host,
                    dataPath = "/other",
                )
            )
        }

        val source = mainActivitySource()
        val handler =
            source.substringAfter("private fun handleMobileDownloadAppLink(intent: Intent?)")
                .substringBefore("private fun handleUnsupportedSlockWebLink")
        assertTrue(handler.contains("setIntent(Intent(this, MainActivity::class.java))"))
        assertTrue(handler.contains("openSharedShell(intent)"))
        assertFalse(handler.contains("openSharedShell(Intent(this, MainActivity::class.java))"))
    }

    @Test
    fun verifiedManifestFiltersNeverBroadenCanonicalHostsBeyondDownload() {
        val filters =
            Regex("<intent-filter[^>]*>[\\s\\S]*?</intent-filter>")
                .findAll(androidManifestSource())
                .map { it.value }
                .toList()
        val productionFilters = filters.filter { it.contains("android:host=\"app.raft.build\"") }
        val stagingFilters = filters.filter { it.contains("android:host=\"raft-app-staging.botiverse.dev\"") }

        assertEquals(2, productionFilters.size)
        assertEquals(1, stagingFilters.size)
        assertTrue(
            productionFilters.any {
                it.contains("android:autoVerify=\"true\"") && it.contains("android:path=\"/download\"")
            }
        )
        assertTrue(
            productionFilters.any {
                !it.contains("android:autoVerify=\"true\"") && it.contains("android:path=\"/\"")
            }
        )
        assertTrue(
            stagingFilters.single().contains("android:autoVerify=\"true\"") &&
                stagingFilters.single().contains("android:path=\"/download\"")
        )
        assertTrue(productionFilters.all { it.contains("android:path=") })
        assertTrue(stagingFilters.all { it.contains("android:path=\"/download\"") })
    }

    @Test
    fun unsupportedRaftWebLinkFallsBackToExternalBrowserAfterTypedHandlersDeclineIt() {
        assertTrue(
            shouldOpenUnsupportedSlockWebLinkExternally(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.raft.build"
            )
        )
        assertTrue(
            shouldOpenUnsupportedSlockWebLinkExternally(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "app.slock.ai"
            )
        )
        assertFalse(
            shouldOpenUnsupportedSlockWebLinkExternally(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                dataHost = "example.com"
            )
        )
        assertFalse(
            shouldOpenUnsupportedSlockWebLinkExternally(
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
            shouldOpenUnsupportedSlockWebLinkExternally(
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
            shouldOpenUnsupportedSlockWebLinkExternally(
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
            shouldOpenUnsupportedSlockWebLinkExternally(
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

    @Test
    fun androidOAuthCallbackRoutesTypedConflictCodesThroughTheSharedMapper() {
        // Source-wiring regression. The Android callback used to hand the raw
        // query value straight to the toast, so `account_conflict` reached the
        // user verbatim. This asserts the mapper sits between them and that the
        // non-conflict passthrough survives, which unit-testing MainActivity
        // directly cannot reach.
        val source = mainActivitySource()

        assertTrue(
            "MainActivity must import the shared conflict mapper",
            source.contains("import build.raft.app.data.mobileOAuthConflictMessageOrNull")
        )
        assertTrue(
            "the OAuth callback error must be mapped before it is surfaced",
            source.contains("mobileOAuthConflictMessageOrNull(providerName, error) ?: error")
        )
        assertFalse(
            "the raw error code must not be toasted directly any more",
            source.contains("openAfterMobileOAuthError(error, pending.mode)")
        )
    }

    private fun mainActivitySource(): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, "src/main/java/build/raft/app/MainActivity.kt")
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate MainActivity.kt from ${System.getProperty("user.dir")}")
    }

    private fun androidManifestSource(): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, "src/main/AndroidManifest.xml")
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate AndroidManifest.xml from ${System.getProperty("user.dir")}")
    }
}
