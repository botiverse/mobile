package build.raft.app.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppLoginCoordinatorTest {
    private val verifier = "v".repeat(43)
    private val challenge = "c".repeat(43)
    private val uri = "raft://login/callback"
    private class Runtime : AppLoginRuntime {
        override var environment = "production"
        var exchanges = 0
        var receivedVerifier = ""
        var success = true
        override suspend fun start(returnUri: String, challenge: String) =
            SharedAuthResult.Success(AuthAppLoginStartResponse("request", "https://raft.example/login/app?request=request", "2026-09-30T10:00:00Z"))
        override suspend fun complete(code: String, verifier: String): Boolean {
            exchanges++
            receivedVerifier = verifier
            return success
        }
    }
    @Test fun processRestartRetainsVerifierAndDuplicateCallbackCannotExchangeAgain() = runTest {
        val store = InMemorySlockKeyValueStore()
        val runtime = Runtime()
        val first = AppLoginCoordinator(store, { runtime }, { 1000 })
        assertNotNull(first.start("generation", verifier, challenge, uri))
        val restarted = AppLoginCoordinator(store, { runtime }, { 2000 })
        assertEquals("ignored", restarted.complete("raft-debug://login/callback", "code", ""))
        assertEquals(0, runtime.exchanges)
        assertEquals("app_shell", restarted.complete(uri, "code", ""))
        assertEquals(verifier, runtime.receivedVerifier)
        assertEquals("ignored", restarted.complete(uri, "code", ""))
        assertEquals(1, runtime.exchanges)
    }
    @Test fun expiredOrEnvironmentChangedRequestsNeverExchange() = runTest {
        for (changed in listOf(false, true)) {
            val runtime = Runtime()
            var now = 0L
            val coordinator = AppLoginCoordinator(InMemorySlockKeyValueStore(), { runtime }, { now })
            coordinator.start("generation", verifier, challenge, uri)
            if (changed) runtime.environment = "staging" else now = 600_000L
            assertEquals("login_error", coordinator.complete(uri, "code", ""))
            assertEquals(0, runtime.exchanges)
            assertEquals("ignored", coordinator.complete(uri, "code", ""))
        }
    }
    @Test fun retryReplacesCredentialsAndOldBrowserFailureCannotCancelNewRequest() = runTest {
        val runtime = Runtime()
        val coordinator = AppLoginCoordinator(InMemorySlockKeyValueStore(), { runtime }, { 0 })
        coordinator.start("old", verifier, challenge, uri)
        coordinator.start("new", "n".repeat(43), challenge, uri)
        coordinator.cancel("old")
        assertEquals("app_shell", coordinator.complete(uri, "code", ""))
        assertEquals("n".repeat(43), runtime.receivedVerifier)
    }
    @Test fun cancelAndInvalidPreparationCannotDeliverSession() = runTest {
        val runtime = Runtime()
        val coordinator = AppLoginCoordinator(InMemorySlockKeyValueStore(), { runtime }, { 0 })
        assertNull(coordinator.start("generation", verifier, challenge, "raft://oauth/callback"))
        coordinator.start("generation", verifier, challenge, uri)
        assertEquals("login_error", coordinator.complete(uri, "", "access_denied"))
        assertEquals(0, runtime.exchanges)
        assertEquals("ignored", coordinator.complete(uri, "code", ""))
    }
    @Test fun coldStartupCanRetryUntilSharedRuntimeIsReady() = runTest {
        val store = InMemorySlockKeyValueStore()
        val active = Runtime()
        var runtime: AppLoginRuntime? = active
        val coordinator = AppLoginCoordinator(store, { runtime }, { 0 })
        coordinator.start("generation", verifier, challenge, uri)
        runtime = null
        assertEquals("retry", coordinator.complete(uri, "code", ""))
        runtime = active
        assertEquals("app_shell", coordinator.complete(uri, "code", ""))
        assertEquals(1, active.exchanges)
    }
    @Test fun failedExchangeRequiresFreshLogin() = runTest {
        val runtime = Runtime().apply { success = false }
        val coordinator = AppLoginCoordinator(InMemorySlockKeyValueStore(), { runtime }, { 0 })
        coordinator.start("generation", verifier, challenge, uri)
        assertEquals("login_error", coordinator.complete(uri, "code", ""))
        assertEquals("ignored", coordinator.complete(uri, "code", ""))
        assertEquals(1, runtime.exchanges)
    }
}
