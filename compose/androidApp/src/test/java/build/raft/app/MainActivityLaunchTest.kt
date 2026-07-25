package build.raft.app

import build.raft.app.core.data.auth.AndroidMobileOAuth
import build.raft.app.shared.resolveAndroidShellLaunchTarget
import build.raft.app.shared.taskLaunchPlan
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        assertTrue(AndroidMobileOAuth.isCallbackRoute("raft-dev", "oauth", "/callback"))
        assertTrue(
            AndroidMobileOAuth.isCallbackUri(
                scheme = "raft-dev",
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
                scheme = "raft-dev",
                host = "oauth",
                path = "/wrong",
                code = "handoff-code",
                error = null,
            )
        )
        assertFalse(
            AndroidMobileOAuth.isCallbackUri(
                scheme = "raft-dev",
                host = "oauth",
                path = "/callback",
                code = null,
                error = null,
            )
        )
    }

    @Test
    fun coldAndWarmLaunchesUseTheSameTypedCallbackAdmissionChain() {
        val source = mainActivitySource()

        assertEquals(2, Regex("\\bhandleLaunchIntent\\(intent\\)").findAll(source).count())
        assertEquals(1, Regex("private fun handleLaunchIntent\\(intent: Intent\\?\\)").findAll(source).count())
        val handler =
            source.substringAfter("private fun handleLaunchIntent(intent: Intent?)")
                .substringBefore("private fun handleUnsupportedSlockWebLink")
        assertTrue(handler.indexOf("handleExternalShareIntent") < handler.indexOf("handleMobileOAuthCallback"))
        assertTrue(handler.indexOf("handleMobileOAuthCallback") < handler.indexOf("handleSocialAuthCallback"))
        assertTrue(handler.indexOf("handleSocialAuthCallback") < handler.indexOf("handleEmailVerificationLink"))
        assertTrue(handler.indexOf("handleEmailVerificationLink") < handler.indexOf("openSharedShell"))
    }

    @Test
    fun externalShareLaunchPageDataContainsOnlyOpaqueTokenAndIntent() {
        val pageData = androidExternalSharePageData(AndroidExternalShareLaunch("opaque-token-1234"))

        assertEquals(setOf("intent", "externalShareToken"), pageData.keys().asSequence().toSet())
        assertEquals("open_share_to_raft", pageData.getString("intent"))
        assertEquals("opaque-token-1234", pageData.getString("externalShareToken"))
        listOf("text", "subject", "items", "uris", "attachments", "targets", "auth", "envelope")
            .forEach { forbidden -> assertFalse(pageData.has(forbidden)) }
    }

    @Test
    fun externalShareColdAndWarmAdmissionShareOneOwnerAndClearTheRawIntent() {
        val source = mainActivitySource()
        val handler = source.substringAfter("private fun handleExternalShareIntent(intent: Intent?)")
            .substringBefore("private fun handleUnsupportedSlockWebLink")

        assertEquals(1, Regex("externalShareAdmissionOwner = AndroidExternalShareAdmissionOwner\\.create")
            .findAll(source).count())
        assertTrue(handler.indexOf("setIntent(Intent(this, MainActivity::class.java))") < handler.indexOf("externalShareAdmissionOwner.submit"))
        assertTrue(source.contains("openSharedShellPageData(androidExternalSharePageData(result.launch))"))
    }

    @Test
    fun parkedDistinctWarmSharePublishesOnlyTheNewestGeneration() = runTest {
        val firstRelease = CompletableDeferred<Unit>()
        val admitted = mutableListOf<String>()
        val published = mutableListOf<String>()
        val owner = AndroidExternalShareAdmissionOwner<String>(
            scope = backgroundScope,
            admit = { value ->
                admitted += value
                if (value == "first") firstRelease.await()
                AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch("token-$value-123456"))
            },
            onLatestResult = { result ->
                published += (result as AndroidExternalShareIntakeResult.Admitted).launch.token
            },
        )

        owner.submit("first")
        runCurrent()
        owner.submit("second")
        firstRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), admitted)
        assertEquals(listOf("token-second-123456"), published)
    }

    @Test
    fun parkedSamePayloadStillSerializesAndPublishesOneLatestResult() = runTest {
        val firstRelease = CompletableDeferred<Unit>()
        var calls = 0
        val published = mutableListOf<String>()
        val owner = AndroidExternalShareAdmissionOwner<String>(
            scope = backgroundScope,
            admit = {
                calls += 1
                if (calls == 1) firstRelease.await()
                AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch("same-token-123456"))
            },
            onLatestResult = { result ->
                published += (result as AndroidExternalShareIntakeResult.Admitted).launch.token
            },
        )

        owner.submit("same")
        runCurrent()
        owner.submit("same")
        firstRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals(listOf("same-token-123456"), published)
    }

    @Test
    fun exportedTrampolineOwnsSendAndSendMultipleFilters() {
        val manifest = androidManifestSource()
        val mainActivity = manifest.substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")

        assertTrue(mainActivity.contains("android:exported=\"true\""))
        assertTrue(mainActivity.contains("android.intent.action.SEND\""))
        assertTrue(mainActivity.contains("android.intent.action.SEND_MULTIPLE\""))
        assertEquals(2, Regex("android:mimeType=\"\\*/\\*\"").findAll(mainActivity).count())
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
