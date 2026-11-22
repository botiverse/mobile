package build.raft.app.data

import build.raft.app.resources.RawStringDesc
import build.raft.app.resources.ResourceFormattedStringDesc
import build.raft.app.resources.ResourceStringDesc
import build.raft.app.resources.StringDesc
import build.raft.app.resources.desc
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
        assertEquals("auth_error_mobile_oauth_request_expired", expired.message)
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
        assertEquals("auth_error_mobile_oauth_environment_changed", wrongEnvironment.message)
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
        assertEquals("auth_error_mobile_oauth_request_expired", result.message)
        assertEquals(0, runtime.linkCompleteCount)
        assertNull(fixture.pendingStore.read())
    }

    @Test
    fun providerErrorCodesReadAsCatalogCopyNeverAsTheCode() {
        fun message(error: String, mode: String = "link"): String =
            testMessageKey(iosMobileOAuthProviderErrorMessage(provider = "github", mode = mode, error = error))

        assertEquals("auth_notice_mobile_oauth_link_cancelled(GitHub)", message("access_denied"))
        assertEquals("auth_notice_mobile_oauth_sign_in_cancelled(GitHub)", message("user_cancelled", mode = "login"))
        assertEquals("auth_error_mobile_oauth_request_expired", message("handoff_code_consumed"))
        assertEquals("auth_error_registration_disabled", message("registration_disabled"))
        assertEquals("auth_error_runtime_starting", message("runtime_not_ready"))
        assertEquals("auth_error_mobile_oauth_link_failed(GitHub)", message("provider_failed"))
        assertEquals("auth_error_mobile_oauth_sign_in_failed(GitHub)", message("browser_failed", mode = "login"))
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
        assertEquals("auth_error_mobile_oauth_environment_changed", result.message)
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
            resolveMessage = ::testMessageKey,
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
        assertEquals("auth_error_runtime_starting", cancelled.message)
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
        assertEquals("auth_notice_mobile_oauth_link_cancelled(Google)", cancelled.message)
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
                resolveMessage = ::testMessageKey,
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

/** The resource key a message names (with its arguments), so assertions need no string catalog. */
private fun testMessageKey(desc: StringDesc): String =
    when (desc) {
        is ResourceStringDesc -> desc.stringRes.key
        is ResourceFormattedStringDesc -> desc.stringRes.key + desc.args.joinToString(prefix = "(", postfix = ")")
        is RawStringDesc -> desc.string
    }
