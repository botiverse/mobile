package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosMobileOAuthCoordinatorTest {
    @Test
    fun loginStartBindsIosPlatformProductionReturnAndPendingGeneration() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)

        val result = fixture.coordinator.startBrowser(
            provider = "github",
            mode = "login",
            generation = "generation-1",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )

        assertEquals("started", result.status)
        assertEquals(202, result.code)
        assertEquals("browser", result.presentation)
        assertEquals("raft", result.callbackScheme)
        assertEquals("ios", runtime.startedLogin?.platform)
        assertEquals("production", runtime.startedLogin?.appEnv)
        assertEquals("raft://oauth/callback", runtime.startedLogin?.returnUri)
        assertEquals("generation-1", fixture.pendingStore.read()?.generation)
        assertEquals(Verifier, fixture.pendingStore.read()?.codeVerifier)
    }

    @Test
    fun appleIsRejectedByTheBrowserCoordinatorWithoutCreatingPendingState() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)

        val result = fixture.coordinator.startBrowser(
            provider = "apple",
            mode = "login",
            generation = "apple-browser-generation",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )

        assertEquals("error", result.status)
        assertNull(result.authorizationUrl)
        assertNull(runtime.startedLogin)
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
                mode = "login",
                generation = "generation-$environmentKey",
                codeVerifier = Verifier,
                codeChallenge = Challenge,
            )

            assertEquals("started", result.status, environmentKey)
            assertEquals("raft-debug", result.callbackScheme, environmentKey)
            assertEquals("raft-debug://oauth/callback", runtime.startedLogin?.returnUri, environmentKey)
            assertEquals(environmentKey, runtime.startedLogin?.appEnv, environmentKey)
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
    fun appleLoginUsesNativeCredentialAuthorizationAndNeverReturnsABrowserUrl() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)

        val start = fixture.coordinator.startNativeApple(
            mode = "login",
            generation = "apple-generation",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )
        val pendingAfterStart = fixture.pendingStore.read()
        val completion = fixture.coordinator.completeNativeApple(
            generation = "apple-generation",
            authorizationCode = "apple-authorization-code",
            identityToken = "apple-identity-token",
        )

        assertEquals("started", start.status)
        assertEquals("native_apple", start.presentation)
        assertNull(start.authorizationUrl)
        assertEquals("login", runtime.startedNativeApple?.mode)
        assertEquals("native-request-token", pendingAfterStart?.requestToken)
        assertEquals("native-request-1", runtime.authorizedNativeApple?.requestId)
        assertEquals("native-request-token", runtime.authorizedNativeApple?.requestToken)
        assertEquals("apple-authorization-code", runtime.authorizedNativeApple?.authorizationCode)
        assertEquals("apple-identity-token", runtime.authorizedNativeApple?.identityToken)
        assertEquals("choose_server", completion.status)
        assertEquals("apple-handoff", runtime.completedLogin?.code)
        assertEquals("apple", completion.provider)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun nativeAppleRejectsWebCallbacksAndStaleCredentialGenerationsWithoutConsumingPending() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.coordinator.startNativeApple(
            mode = "login",
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
        assertEquals("choose_server", exact.status)
        assertEquals("exact-code", runtime.authorizedNativeApple?.authorizationCode)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun nativeAppleExpiryAndEnvironmentChangeFailBeforeCredentialAuthorization() = runBlocking {
        var nowMillis = 1_000L
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime, nowMillis = { nowMillis })
        fixture.coordinator.startNativeApple(
            mode = "login",
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
        assertEquals("login_error", expired.status)
        assertTrue(expired.message.orEmpty().contains("expired", ignoreCase = true))
        assertNull(runtime.authorizedNativeApple)

        nowMillis = 1_000L
        fixture.coordinator.startNativeApple(
            mode = "login",
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
        assertEquals("login_error", wrongEnvironment.status)
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
    fun successfulLoginCallbacksRouteByCanonicalSelectedServerState() = runBlocking {
        listOf(null to "choose_server", "server-1" to "app_shell").forEach { (selectedServerId, expectedStatus) ->
            val runtime = FakeRuntime(environmentKey = "production").apply {
                loginOutcome = IosMobileOAuthLoginOutcome.Success(selectedServerId)
            }
            val fixture = fixture(runtime)
            fixture.startLogin()

            val result = fixture.coordinator.complete(fixture.callback(code = "handoff-$expectedStatus"))

            assertEquals(expectedStatus, result.status)
            assertEquals("handoff-$expectedStatus", runtime.completedLogin?.code)
            assertEquals(Verifier, runtime.completedLogin?.codeVerifier)
            assertEquals("ios", runtime.completedLogin?.platform)
            assertNull(fixture.pendingStore.read())
        }
    }

    @Test
    fun canonicalServerCallbackMayOmitProviderAndMode() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.startLogin()

        val result = fixture.coordinator.complete(
            fixture.callback(
                provider = null,
                mode = null,
                code = "server-handoff",
            )
        )

        assertEquals("choose_server", result.status)
        assertEquals("server-handoff", runtime.completedLogin?.code)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun staleGenerationAndProviderMismatchAreIgnoredWithoutDestroyingCurrentRequest() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.startLogin()
        val mismatches = listOf(
            fixture.callback(code = "handoff-old", expectedGeneration = "generation-old"),
            fixture.callback(code = "handoff-wrong-provider", provider = "github"),
            fixture.callback(code = "handoff-wrong-mode", mode = "link"),
        )

        mismatches.forEach { callback ->
            assertEquals("ignored", fixture.coordinator.complete(callback).status)
            assertEquals("generation-1", fixture.pendingStore.read()?.generation)
        }
        assertEquals(0, runtime.loginCompleteCount)
    }

    @Test
    fun callbackExpiryClearsPendingWithoutSendingHandoffToNetwork() = runBlocking {
        var nowMillis = 1_000L
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime, nowMillis = { nowMillis })
        fixture.startLogin()
        nowMillis += 10L * 60L * 1000L

        val result = fixture.coordinator.complete(fixture.callback(code = "expired-handoff"))

        assertEquals("login_error", result.status)
        assertTrue(result.message.orEmpty().contains("expired", ignoreCase = true))
        assertEquals(0, runtime.loginCompleteCount)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun typedConflictCodesKeepExistingAccountGuidanceDistinctFromOtherUserConflict() {
        fun message(error: String, provider: String = "google"): String =
            iosMobileOAuthProviderErrorMessage(
                provider = provider,
                mode = "login",
                error = error,
                accountConflictMessage = { providerName -> "password_then_settings:$providerName" },
                providerConflictMessage = { providerName -> "other_user:$providerName" },
            )

        assertEquals("password_then_settings:Google", message("account_conflict"))
        assertEquals("other_user:Google", message("provider_conflict"))
        assertEquals("password_then_settings:Social", message("account_conflict", "unexpected"))
    }

    @Test
    fun legalAcceptanceKeepsCredentialsOnlyInTheInProcessRouteResult() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production").apply {
            loginOutcome = IosMobileOAuthLoginOutcome.LegalAcceptanceRequired("accept terms")
        }
        val fixture = fixture(runtime)
        fixture.startLogin()

        val result = fixture.coordinator.complete(fixture.callback(code = "legal-handoff"))

        assertEquals("legal_acceptance", result.status)
        assertEquals("google", result.provider)
        assertEquals("legal-handoff", result.handoffCode)
        assertEquals(Verifier, result.codeVerifier)
        assertNull(fixture.pendingStore.read())
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
        fixture.startLogin()
        val callback = fixture.callback(code = "one-time-handoff")

        assertEquals("choose_server", fixture.coordinator.complete(callback).status)
        assertEquals("ignored", fixture.coordinator.complete(callback).status)
        assertEquals(1, runtime.loginCompleteCount)
    }

    @Test
    fun environmentChangeFailsClosedBeforeCompletion() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.startLogin()
        runtime.environmentKey = "staging"

        val result = fixture.coordinator.complete(fixture.callback(code = "wrong-environment-handoff"))

        assertEquals("login_error", result.status)
        assertTrue(result.message.orEmpty().contains("environment", ignoreCase = true))
        assertEquals(0, runtime.loginCompleteCount)
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
            mode = "login",
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
        assertEquals("login_error", cancelled.status)
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
                mode = "login",
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
        assertEquals("choose_server", completed.status)
        assertEquals("native-retry-code", runtime.authorizedNativeApple?.authorizationCode)
        assertEquals("native-retry-token", runtime.authorizedNativeApple?.identityToken)
        assertNull(pendingStore.read())
    }

    @Test
    fun cancellationOnlyOwnsItsExactGeneration() = runBlocking {
        val runtime = FakeRuntime(environmentKey = "production")
        val fixture = fixture(runtime)
        fixture.startLogin()

        assertEquals("ignored", fixture.coordinator.cancel("generation-old", "provider_cancelled").status)
        assertEquals("generation-1", fixture.pendingStore.read()?.generation)
        val cancelled = fixture.coordinator.cancel("generation-1", "provider_cancelled")
        assertEquals("login_error", cancelled.status)
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
        suspend fun startLogin() {
            val result = coordinator.startBrowser(
                provider = "google",
                mode = "login",
                generation = "generation-1",
                codeVerifier = Verifier,
                codeChallenge = Challenge,
            )
            assertEquals("started", result.status)
        }

        fun callback(
            provider: String? = "google",
            mode: String? = "login",
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
        data class LoginStart(
            val provider: String,
            val platform: String,
            val appEnv: String,
            val returnUri: String,
            val codeChallenge: String,
        )

        data class LoginComplete(
            val code: String,
            val codeVerifier: String,
            val platform: String,
        )

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

        var startedLogin: LoginStart? = null
        var startedBrowserLinkReturnUri: String? = null
        var startedNativeApple: NativeAppleStart? = null
        var authorizedNativeApple: NativeAppleAuthorize? = null
        var completedLogin: LoginComplete? = null
        var completedLink: LinkComplete? = null
        var loginCompleteCount = 0
        var loginOutcome: IosMobileOAuthLoginOutcome = IosMobileOAuthLoginOutcome.Success(null)
        var linkOutcome: IosMobileOAuthLinkOutcome = IosMobileOAuthLinkOutcome.Success
        var nativeAppleAuthorizationOutcome: IosNativeAppleAuthorizationOutcome? = null

        override suspend fun startBrowserLogin(
            provider: String,
            platform: String,
            appEnv: String,
            returnUri: String,
            codeChallenge: String,
        ): IosMobileOAuthStartOutcome {
            startedLogin = LoginStart(provider, platform, appEnv, returnUri, codeChallenge)
            return IosMobileOAuthStartOutcome.Success(startResponse(returnUri))
        }

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

        override suspend fun completeLogin(
            code: String,
            codeVerifier: String,
            platform: String,
        ): IosMobileOAuthLoginOutcome {
            loginCompleteCount += 1
            completedLogin = LoginComplete(code, codeVerifier, platform)
            return loginOutcome
        }

        override suspend fun completeLink(
            provider: String,
            code: String,
            codeVerifier: String,
        ): IosMobileOAuthLinkOutcome {
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
