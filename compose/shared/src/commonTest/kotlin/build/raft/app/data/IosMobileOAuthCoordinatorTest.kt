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

        val result = fixture.coordinator.start(
            provider = "github",
            mode = "login",
            generation = "generation-1",
            codeVerifier = Verifier,
            codeChallenge = Challenge,
        )

        assertEquals("started", result.status)
        assertEquals(202, result.code)
        assertEquals("raft", result.callbackScheme)
        assertEquals("ios", runtime.startedLogin?.platform)
        assertEquals("production", runtime.startedLogin?.appEnv)
        assertEquals("raft://oauth/callback", runtime.startedLogin?.returnUri)
        assertEquals("generation-1", fixture.pendingStore.read()?.generation)
        assertEquals(Verifier, fixture.pendingStore.read()?.codeVerifier)
    }

    @Test
    fun nonProductionEnvironmentsUseDevelopmentCallbackWithoutLiteralCloneCases() = runBlocking {
        listOf("staging", "custom").forEach { environmentKey ->
            val runtime = FakeRuntime(environmentKey = environmentKey)
            val fixture = fixture(runtime)

            val result = fixture.coordinator.start(
                provider = "google",
                mode = "login",
                generation = "generation-$environmentKey",
                codeVerifier = Verifier,
                codeChallenge = Challenge,
            )

            assertEquals("started", result.status, environmentKey)
            assertEquals("raft-dev", result.callbackScheme, environmentKey)
            assertEquals("raft-dev://oauth/callback", runtime.startedLogin?.returnUri, environmentKey)
            assertEquals(environmentKey, runtime.startedLogin?.appEnv, environmentKey)
        }
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
        val start = fixture.coordinator.start(
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
        val pendingStore = IosMobileOAuthPendingStore(InMemorySlockKeyValueStore())
        val coordinator = IosMobileOAuthCoordinator(
            pendingStore = pendingStore,
            runtimeProvider = { availableRuntime },
            nowMillis = { 1_000L },
        )
        val start = coordinator.start(
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
        val pendingStore = IosMobileOAuthPendingStore(InMemorySlockKeyValueStore())
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
            val result = coordinator.start(
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

        var startedLogin: LoginStart? = null
        var completedLogin: LoginComplete? = null
        var completedLink: LinkComplete? = null
        var loginCompleteCount = 0
        var loginOutcome: IosMobileOAuthLoginOutcome = IosMobileOAuthLoginOutcome.Success(null)
        var linkOutcome: IosMobileOAuthLinkOutcome = IosMobileOAuthLinkOutcome.Success

        override suspend fun startLogin(
            provider: String,
            platform: String,
            appEnv: String,
            returnUri: String,
            codeChallenge: String,
        ): IosMobileOAuthStartOutcome {
            startedLogin = LoginStart(provider, platform, appEnv, returnUri, codeChallenge)
            return IosMobileOAuthStartOutcome.Success(startResponse(returnUri))
        }

        override suspend fun startLink(
            provider: String,
            returnUri: String,
            codeChallenge: String,
        ): IosMobileOAuthStartOutcome =
            IosMobileOAuthStartOutcome.Success(startResponse(returnUri))

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
