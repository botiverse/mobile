package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosMobileOAuthCoordinatorTest {
    @Test
    fun loginModeIsRejectedBeforeNetworkOrPendingState() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)

        val browser = fixture.coordinator.startBrowser(
            provider = "github",
            mode = "login",
            generation = "generation-1",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )
        val nativeApple = fixture.coordinator.startNativeApple(
            mode = "login",
            generation = "generation-2",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )

        assertEquals("error", browser.status)
        assertEquals("ios_mobile_oauth_invalid_request", browser.errorCode)
        assertEquals("error", nativeApple.status)
        assertNull(runtime.startedBrowserLinkReturnUri)
        assertNull(runtime.startedNativeApple)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun linkStartBindsProductionReturnAndPendingGeneration() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)

        val result = fixture.coordinator.startBrowser(
            provider = "github",
            mode = "link",
            generation = "generation-1",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )

        assertEquals("started", result.status)
        assertEquals(202, result.code)
        assertEquals("browser", result.presentation)
        assertEquals("raft", result.callbackScheme)
        assertEquals("raft://oauth/callback", runtime.startedBrowserLinkReturnUri)
        assertEquals("generation-1", fixture.pendingStore.read()?.generation)
        assertEquals(Verifier, fixture.pendingStore.read()?.codeVerifier)
    }

    @Test
    fun appleIsRejectedByTheBrowserCoordinatorWithoutCreatingPendingState() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)

        val result = fixture.coordinator.startBrowser(
            provider = "apple",
            mode = "link",
            generation = "apple-browser-generation",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )

        assertEquals("error", result.status)
        assertNull(result.authorizationUrl)
        assertNull(runtime.startedBrowserLinkReturnUri)
        assertNull(runtime.startedNativeApple)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun nonProductionEnvironmentsUseTheDebugCallbackByDefault() = runBlocking {
        listOf("staging", "custom").forEach { environmentKey ->
            val runtime = FakeRuntime(environmentKey = environmentKey)
            val fixture = fixture(runtime)

            val result = fixture.coordinator.startBrowser(
                provider = "google",
                mode = "link",
                generation = "generation-$environmentKey",
                codeVerifier = Verifier,
                codeChallenge = Challenge,
            )

            assertEquals("started", result.status, environmentKey)
            assertEquals("raft-debug", result.callbackScheme, environmentKey)
            assertEquals("raft-debug://oauth/callback", runtime.startedBrowserLinkReturnUri, environmentKey)
            assertEquals(environmentKey, fixture.pendingStore.read()?.environmentKey, environmentKey)
        }
    }

    @Test
    fun buildBoundCallbackOverrideWinsOverTheRuntimeEnvironment() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)

        val result = fixture.coordinator.startBrowser(
            provider = "google",
            mode = "link",
            generation = "debug-build-generation",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
            returnUriOverride = "raft-debug://oauth/callback",
        )

        assertEquals("started", result.status)
        assertEquals("raft-debug", result.callbackScheme)
        assertEquals("raft-debug://oauth/callback", runtime.startedBrowserLinkReturnUri)
        assertEquals("production", fixture.pendingStore.read()?.environmentKey)
    }

    @Test
    fun unregisteredCallbackOverrideFailsBeforeNetworkOrPendingState() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)

        val result = fixture.coordinator.startBrowser(
            provider = "google",
            mode = "link",
            generation = "unregistered-generation",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
            returnUriOverride = "raft-connectedtest://oauth/callback",
        )

        assertEquals("error", result.status)
        assertEquals("ios_mobile_oauth_invalid_return_uri", result.errorCode)
        assertNull(runtime.startedBrowserLinkReturnUri)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun nativeAppleRejectsWebCallbacksAndStaleCredentialGenerationsWithoutConsumingPending() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.coordinator.startNativeApple(
            mode = "link",
            generation = "native-generation",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )

        val browserCallback = fixture.coordinator.complete(
            fixture.callback(
                provider = "apple",
                code = "browser-handoff-must-not-run",
                expectedGeneration = "native-generation",
            )
        )
        val staleNative = fixture.coordinator.completeNativeApple(
            generation = "stale-generation",
            authorizationCode = "stale-code",
            identityToken = "stale-token",
        )

        assertEquals("ignored", browserCallback.status)
        assertEquals("ignored", staleNative.status)
        assertEquals("native-generation", fixture.pendingStore.read()?.generation)
        assertNull(runtime.authorizedNativeApple)

        val exact = fixture.coordinator.completeNativeApple(
            generation = "native-generation",
            authorizationCode = "exact-code",
            identityToken = "exact-token",
        )
        assertEquals("settings_account", exact.status)
        assertEquals("exact-code", runtime.authorizedNativeApple?.authorizationCode)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun nativeAppleExpiryAndEnvironmentChangeFailBeforeCredentialAuthorization() = runBlocking {
        var nowMillis = 1_000L
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime, nowMillis = { nowMillis })
        fixture.coordinator.startNativeApple(
            mode = "link",
            generation = "native-expired",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )
        nowMillis += 10L * 60L * 1000L

        val expired = fixture.coordinator.completeNativeApple(
            generation = "native-expired",
            authorizationCode = "expired-code",
            identityToken = "expired-token",
        )
        assertEquals("settings_account", expired.status)
        assertTrue(expired.message.orEmpty().contains("expired", ignoreCase = true))
        assertNull(runtime.authorizedNativeApple)

        nowMillis = 1_000L
        fixture.coordinator.startNativeApple(
            mode = "link",
            generation = "native-environment",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )
        runtime.environmentKey = "staging"
        val wrongEnvironment = fixture.coordinator.completeNativeApple(
            generation = "native-environment",
            authorizationCode = "environment-code",
            identityToken = "environment-token",
        )
        assertEquals("settings_account", wrongEnvironment.status)
        assertTrue(wrongEnvironment.message.orEmpty().contains("environment", ignoreCase = true))
        assertNull(runtime.authorizedNativeApple)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun nativeAppleLinkAuthorizesThenUsesTheExistingOwnerBoundHandoffCompletion() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        val start = fixture.coordinator.startNativeApple(
            mode = "link",
            generation = "native-link",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )
        assertEquals("native_apple", start.presentation)

        val completion = fixture.coordinator.completeNativeApple(
            generation = "native-link",
            authorizationCode = "native-link-code",
            identityToken = "native-link-token",
        )

        assertEquals("settings_account", completion.status)
        assertEquals("link", runtime.authorizedNativeApple?.mode)
        assertEquals("apple", runtime.completedLink?.provider)
        assertEquals("apple-handoff", runtime.completedLink?.code)
        assertEquals(Verifier, runtime.completedLink?.codeVerifier)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun canonicalServerCallbackMayOmitProviderAndMode() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.startLink()

        val result = fixture.coordinator.complete(
            fixture.callback(
                provider = null,
                mode = null,
                code = "server-handoff",
            )
        )

        assertEquals("settings_account", result.status)
        assertEquals("server-handoff", runtime.completedLink?.code)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun staleGenerationAndProviderMismatchAreIgnoredWithoutDestroyingCurrentRequest() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.startLink()
        val mismatches = listOf(
            fixture.callback(code = "handoff-old", expectedGeneration = "generation-old"),
            fixture.callback(code = "handoff-wrong-provider", provider = "github"),
            fixture.callback(code = "handoff-wrong-mode", mode = "login"),
        )

        mismatches.forEach { callback ->
            assertEquals("ignored", fixture.coordinator.complete(callback).status)
            assertEquals("generation-1", fixture.pendingStore.read()?.generation)
        }
        assertEquals(0, runtime.linkCompleteCount)
    }

    @Test
    fun callbackExpiryClearsPendingWithoutSendingHandoffToNetwork() = runBlocking {
        var nowMillis = 1_000L
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime, nowMillis = { nowMillis })
        fixture.startLink()
        nowMillis += 10L * 60L * 1000L

        val result = fixture.coordinator.complete(fixture.callback(code = "expired-handoff"))

        assertEquals("settings_account", result.status)
        assertTrue(result.message.orEmpty().contains("expired", ignoreCase = true))
        assertEquals(0, runtime.linkCompleteCount)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun typedConflictCodesKeepExistingAccountGuidanceDistinctFromOtherUserConflict() {
        fun message(error: String, provider: String = "google"): String =
            iosMobileOAuthProviderErrorMessage(
                provider = provider,
                mode = "link",
                error = error,
                accountConflictMessage = { providerName -> "password_then_settings:$providerName" },
                providerConflictMessage = { providerName -> "other_user:$providerName" },
            )

        assertEquals("password_then_settings:Google", message("account_conflict"))
        assertEquals("other_user:Google", message("provider_conflict"))
        assertEquals("password_then_settings:Social", message("account_conflict", "unexpected"))
    }

    @Test
    fun accountLinkUsesTheSameAdmissionAndReturnsToSettingsAccount() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        val start = fixture.coordinator.startBrowser(
            provider = "github",
            mode = "link",
            generation = "link-generation",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )
        assertEquals("started", start.status)

        val result = fixture.coordinator.complete(
            fixture.callback(
                provider = "github",
                mode = "link",
                code = "link-handoff",
                expectedGeneration = "link-generation",
            )
        )

        assertEquals("settings_account", result.status)
        assertEquals("github", runtime.completedLink?.provider)
        assertEquals("link-handoff", runtime.completedLink?.code)
        assertEquals(Verifier, runtime.completedLink?.codeVerifier)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun duplicateCallbackCompletesAtMostOnce() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.startLink()
        val callback = fixture.callback(code = "one-time-handoff")

        assertEquals("settings_account", fixture.coordinator.complete(callback).status)
        assertEquals("ignored", fixture.coordinator.complete(callback).status)
        assertEquals(1, runtime.linkCompleteCount)
    }

    @Test
    fun environmentChangeFailsClosedBeforeCompletion() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.startLink()
        runtime.environmentKey = "staging"

        val result = fixture.coordinator.complete(fixture.callback(code = "wrong-environment-handoff"))

        assertEquals("settings_account", result.status)
        assertTrue(result.message.orEmpty().contains("environment", ignoreCase = true))
        assertEquals(0, runtime.linkCompleteCount)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun runtimeNotReadyRetryPreservesAndReturnsExactGenerationForFencedCleanup() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        var availableRuntime: IosMobileOAuthRuntime? = runtime
        val pendingStore = IosMobileOAuthPendingStore(InMemoryRaftKeyValueStore())
        val coordinator = IosMobileOAuthCoordinator(
            pendingStore = pendingStore,
            runtimeProvider = { availableRuntime },
            nowMillis = { 1_000L },
        )
        val start = coordinator.startBrowser(
            provider = "google",
            mode = "link",
            generation = "generation-1",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )
        assertEquals("started", start.status)
        availableRuntime = null

        val retry = coordinator.complete(
            IosMobileOAuthCallback(
                scheme = "raft",
                host = "oauth",
                path = "/callback",
                provider = null,
                mode = null,
                code = "handoff",
                error = null,
                expectedGeneration = null,
            )
        )

        assertEquals("retry", retry.status)
        assertEquals("generation-1", retry.generation)
        assertEquals("generation-1", pendingStore.read()?.generation)
        val cancelled = coordinator.cancel(retry.generation.orEmpty(), "runtime_not_ready")
        assertEquals("settings_account", cancelled.status)
        assertTrue(cancelled.message.orEmpty().contains("still starting", ignoreCase = true))
        assertNull(pendingStore.read())
    }

    @Test
    fun nativeAppleRuntimeRetryPreservesPendingUntilTheSameInMemoryCredentialCanComplete() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        var availableRuntime: IosMobileOAuthRuntime? = runtime
        val pendingStore = IosMobileOAuthPendingStore(InMemoryRaftKeyValueStore())
        val coordinator =
            IosMobileOAuthCoordinator(
                pendingStore = pendingStore,
                runtimeProvider = { availableRuntime },
                nowMillis = { 1_000L },
            )
        val start =
            coordinator.startNativeApple(
                mode = "link",
                generation = "native-retry-generation",
                codeVerifier = Verifier,
                codeChallenge = Challenge,
            )
        assertEquals("native_apple", start.presentation)

        availableRuntime = null
        val retry =
            coordinator.completeNativeApple(
                generation = "native-retry-generation",
                authorizationCode = "native-retry-code",
                identityToken = "native-retry-token",
            )
        assertEquals("retry", retry.status)
        assertEquals("native-retry-generation", retry.generation)
        assertEquals("native-retry-generation", pendingStore.read()?.generation)
        assertNull(runtime.authorizedNativeApple)

        availableRuntime = runtime
        val completed =
            coordinator.completeNativeApple(
                generation = "native-retry-generation",
                authorizationCode = "native-retry-code",
                identityToken = "native-retry-token",
            )
        assertEquals("settings_account", completed.status)
        assertEquals("native-retry-code", runtime.authorizedNativeApple?.authorizationCode)
        assertEquals("native-retry-token", runtime.authorizedNativeApple?.identityToken)
        assertNull(pendingStore.read())
    }

    @Test
    fun cancellationOnlyOwnsItsExactGeneration() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.startLink()

        assertEquals("ignored", fixture.coordinator.cancel("generation-old", "provider_cancelled").status)
        assertEquals("generation-1", fixture.pendingStore.read()?.generation)
        val cancelled = fixture.coordinator.cancel("generation-1", "provider_cancelled")
        assertEquals("settings_account", cancelled.status)
        assertTrue(cancelled.message.orEmpty().contains("cancelled", ignoreCase = true))
        assertNull(fixture.pendingStore.read())
    }

    private fun fixture(
        runtime: FakeRuntime,
        nowMillis: () -> Long = { 1_000L },
    ): Fixture {
        val pendingStore = IosMobileOAuthPendingStore(InMemoryRaftKeyValueStore())
        return Fixture(
            pendingStore = pendingStore,
            coordinator = IosMobileOAuthCoordinator(
                pendingStore = pendingStore,
                runtimeProvider = { runtime },
                nowMillis = nowMillis,
            ),
        )
    }

    private data class Fixture(
        val pendingStore: IosMobileOAuthPendingStore,
        val coordinator: IosMobileOAuthCoordinator,
    ) {
        suspend fun startLink() {
            val result = coordinator.startBrowser(
                provider = "google",
                mode = "link",
                generation = "generation-1",
                codeVerifier = Verifier,
                codeChallenge = Challenge,
            )
            assertEquals("started", result.status)
        }

        fun callback(
            provider: String? = "google",
            mode: String? = "link",
            code: String? = "handoff",
            error: String? = null,
            expectedGeneration: String? = "generation-1",
        ): IosMobileOAuthCallback =
            IosMobileOAuthCallback(
                scheme = "raft",
                host = "oauth",
                path = "/callback",
                provider = provider,
                mode = mode,
                code = code,
                error = error,
                expectedGeneration = expectedGeneration,
            )
    }

    private class FakeRuntime(
        override var environmentKey: String,
    ) : IosMobileOAuthRuntime {
        data class LinkComplete(
            val provider: String,
            val code: String,
            val codeVerifier: String,
        )

        data class NativeAppleStart(
            val mode: String,
            val appEnv: String,
            val returnUri: String,
            val codeChallenge: String,
        )

        data class NativeAppleAuthorize(
            val mode: String,
            val requestId: String,
            val requestToken: String,
            val authorizationCode: String,
            val identityToken: String,
        )

        var startedBrowserLinkReturnUri: String? = null
        var startedNativeApple: NativeAppleStart? = null
        var authorizedNativeApple: NativeAppleAuthorize? = null
        var completedLink: LinkComplete? = null
        var linkCompleteCount = 0
        var linkOutcome: IosMobileOAuthLinkOutcome = IosMobileOAuthLinkOutcome.Success
        var nativeAppleAuthorizationOutcome: IosNativeAppleAuthorizationOutcome? = null

        override suspend fun startBrowserLink(
            provider: String,
            returnUri: String,
            codeChallenge: String,
        ): IosMobileOAuthStartOutcome {
            startedBrowserLinkReturnUri = returnUri
            return IosMobileOAuthStartOutcome.Success(startResponse(returnUri))
        }

        override suspend fun startNativeApple(
            mode: String,
            appEnv: String,
            returnUri: String,
            codeChallenge: String,
        ): IosNativeAppleStartOutcome {
            startedNativeApple = NativeAppleStart(mode, appEnv, returnUri, codeChallenge)
            return IosNativeAppleStartOutcome.Success(
                AuthNativeAppleMobileOAuthStartResponse(
                    requestId = "native-request-1",
                    requestToken = "native-request-token",
                    returnUri = returnUri,
                    expiresAt = "2026-07-23T11:00:00Z",
                )
            )
        }

        override suspend fun authorizeNativeApple(
            mode: String,
            requestId: String,
            requestToken: String,
            authorizationCode: String,
            identityToken: String,
        ): IosNativeAppleAuthorizationOutcome {
            authorizedNativeApple =
                NativeAppleAuthorize(
                    mode,
                    requestId,
                    requestToken,
                    authorizationCode,
                    identityToken,
                )
            return nativeAppleAuthorizationOutcome
                ?: IosNativeAppleAuthorizationOutcome.Success(
                    AuthNativeAppleMobileOAuthAuthorizationResponse(
                        requestId = requestId,
                        handoffCode = "apple-handoff",
                        provider = "apple",
                        mode = mode,
                    )
                )
        }

        override suspend fun completeLink(
            provider: String,
            code: String,
            codeVerifier: String,
        ): IosMobileOAuthLinkOutcome {
            linkCompleteCount += 1
            completedLink = LinkComplete(provider, code, codeVerifier)
            return linkOutcome
        }

        private fun startResponse(returnUri: String): AuthMobileOAuthStartResponse =
            AuthMobileOAuthStartResponse(
                authorizationUrl = "https://accounts.example.test/oauth",
                requestId = "request-1",
                returnUri = returnUri,
                expiresAt = "2026-07-23T11:00:00Z",
            )
    }

    private companion object {
        val Verifier = "a".repeat(43)
        val Challenge = "b".repeat(43)
    }
}
