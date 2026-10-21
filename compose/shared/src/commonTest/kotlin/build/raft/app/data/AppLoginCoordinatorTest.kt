package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// No kotlinx-coroutines-test; all suspend fakes complete inline and time is injected.
class AppLoginCoordinatorTest {
    private val verifier = "v".repeat(43)
    private val challenge = "c".repeat(43)
    private val uri = "raft://login/callback"
    private class Runtime : AppLoginRuntime {
        override var environment = "production"
        var exchanges = 0
        var receivedVerifier = ""
        var success = true
        var starts = 0
        override suspend fun start(returnUri: String, challenge: String): SharedAuthResult<AuthAppLoginStartResponse> {
            starts++
            return SharedAuthResult.Success(AuthAppLoginStartResponse("request-$starts", "https://raft.example/login/app?request=request", "2026-09-30T10:00:00Z"))
        }
        override suspend fun complete(code: String, verifier: String): Boolean {
            exchanges++
            receivedVerifier = verifier
            return success
        }
    }
    @Test fun processRestartRetainsVerifierAndDuplicateCallbackCannotExchangeAgain() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val runtime = Runtime()
        val first = AppLoginCoordinator(store, { runtime }, { 1000 })
        assertNotNull(first.start("generation", verifier, challenge, uri))
        val restarted = AppLoginCoordinator(store, { runtime }, { 2000 })
        assertEquals("ignored", restarted.complete("raft-debug://login/callback", "request-1", "code", ""))
        assertEquals(0, runtime.exchanges)
        assertEquals("app_shell", restarted.complete(uri, "request-1", "code", ""))
        assertEquals(verifier, runtime.receivedVerifier)
        assertEquals("ignored", restarted.complete(uri, "request-1", "code", ""))
        assertEquals(1, runtime.exchanges)
    }
    @Test fun internalChannelSchemesAreAcceptedAndUnknownSchemesRejected() = runBlocking {
        val runtime = Runtime()
        val coordinator = AppLoginCoordinator(InMemoryRaftKeyValueStore(), { runtime }, { 0 })
        assertNull(coordinator.start("generation", verifier, challenge, "raft-evil://login/callback"))
        assertEquals(0, runtime.starts)
        assertNotNull(coordinator.start("generation", verifier, challenge, "raft-alpha://login/callback"))
        assertEquals("app_shell", coordinator.complete("raft-alpha://login/callback", "request-1", "code", ""))
    }
    @Test fun everyExitBranchLogsItsReason() = runBlocking {
        val logs = mutableListOf<String>()
        var available = false
        val runtime = Runtime()
        val coordinator = AppLoginCoordinator(InMemoryRaftKeyValueStore(), { if (available) runtime else null }, { 0 }, logs::add)
        available = true
        coordinator.start("generation", verifier, challenge, uri)
        available = false
        assertEquals("retry", coordinator.complete(uri, "request-1", "code", ""))
        available = true
        runtime.environment = "staging"
        assertEquals("login_error", coordinator.complete(uri, "request-1", "code", ""))
        assertEquals("ignored", coordinator.complete(uri, "request-1", "code", ""))
        assertEquals(
            listOf(
                "app_login_complete result=retry reason=runtime_unavailable",
                "app_login_complete result=login_error reason=environment_changed started=production callback=staging",
                "app_login_complete result=ignored reason=no_pending",
            ),
            logs,
        )
    }
    @Test fun expiredOrEnvironmentChangedRequestsNeverExchange() = runBlocking {
        for (changed in listOf(false, true)) {
            val runtime = Runtime()
            var now = 0L
            val coordinator = AppLoginCoordinator(InMemoryRaftKeyValueStore(), { runtime }, { now })
            coordinator.start("generation", verifier, challenge, uri)
            if (changed) runtime.environment = "staging" else now = 600_000L
            assertEquals("login_error", coordinator.complete(uri, "request-1", "code", ""))
            assertEquals(0, runtime.exchanges)
            assertEquals("ignored", coordinator.complete(uri, "request-1", "code", ""))
        }
    }
    @Test fun retryReplacesCredentialsAndOldBrowserFailureCannotCancelNewRequest() = runBlocking {
        val runtime = Runtime()
        val coordinator = AppLoginCoordinator(InMemoryRaftKeyValueStore(), { runtime }, { 0 })
        coordinator.start("old", verifier, challenge, uri)
        coordinator.start("new", "n".repeat(43), challenge, uri)
        coordinator.cancel("old")
        assertEquals(APP_LOGIN_STALE_CALLBACK, coordinator.complete(uri, "request-1", "old-code", ""))
        assertEquals(APP_LOGIN_STALE_CALLBACK, coordinator.complete(uri, "request-1", "", "access_denied"))
        assertEquals(0, runtime.exchanges)
        assertEquals("app_shell", coordinator.complete(uri, "request-2", "code", ""))
        assertEquals("ignored", coordinator.complete(uri, "request-2", "code", ""))
        assertEquals("n".repeat(43), runtime.receivedVerifier)
    }
    @Test fun cancelAndInvalidPreparationCannotDeliverSession() = runBlocking {
        val runtime = Runtime()
        val coordinator = AppLoginCoordinator(InMemoryRaftKeyValueStore(), { runtime }, { 0 })
        assertNull(coordinator.start("generation", verifier, challenge, "raft://oauth/callback"))
        coordinator.start("generation", verifier, challenge, uri)
        assertEquals("login_error", coordinator.complete(uri, "request-1", "", "access_denied"))
        assertEquals(0, runtime.exchanges)
        assertEquals("ignored", coordinator.complete(uri, "request-1", "code", ""))
    }
    @Test fun coldStartupCanRetryUntilSharedRuntimeIsReady() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val active = Runtime()
        var runtime: AppLoginRuntime? = active
        val coordinator = AppLoginCoordinator(store, { runtime }, { 0 })
        coordinator.start("generation", verifier, challenge, uri)
        runtime = null
        assertEquals("retry", coordinator.complete(uri, "request-1", "code", ""))
        runtime = active
        assertEquals("app_shell", coordinator.complete(uri, "request-1", "code", ""))
        assertEquals(1, active.exchanges)
    }
    @Test fun failedExchangeRequiresFreshLogin() = runBlocking {
        val runtime = Runtime().apply { success = false }
        val coordinator = AppLoginCoordinator(InMemoryRaftKeyValueStore(), { runtime }, { 0 })
        coordinator.start("generation", verifier, challenge, uri)
        assertEquals("login_error", coordinator.complete(uri, "request-1", "code", ""))
        assertEquals("ignored", coordinator.complete(uri, "request-1", "code", ""))
        assertEquals(1, runtime.exchanges)
    }
}
