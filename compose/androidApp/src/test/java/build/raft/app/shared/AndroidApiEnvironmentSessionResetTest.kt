package build.raft.app.shared

import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.StoredAuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #3823 follow-up: Settings > Developer's API-server switch cleared the session with
 * `runBlocking` on the main thread, which deadlocks when a Main coroutine is queued on the
 * auth-session lock. It now suspends; these pin the ordering and completion it must keep.
 */
class AndroidApiEnvironmentSessionResetTest {
    @Test
    fun freeLockAnswersInlineAfterTheClearAndItsFollowUp() = runTest {
        val events = mutableListOf<String>()
        val auth = HeldLockAuthStore(events, lockHeld = false)
        val scope = bridgeScope()

        scope.launchSettingsBridgeAction(callback = { events += "callback:${(it as Map<*, *>)["status"]}" }) {
            clearSessionForApiEnvironmentChange(auth) { events += "disconnect" }
            mapOf("status" to "ok")
        }

        // UNDISPATCHED and nothing suspended: answered before launch returned, as the
        // synchronous bridge did, without running the test dispatcher.
        assertEquals(listOf("clear:api_environment_changed", "disconnect", "callback:ok"), events)
        scope.cancel()
    }

    @Test
    fun heldLockSuspendsInsteadOfBlockingThenKeepsTheOrder() = runTest {
        val events = mutableListOf<String>()
        val auth = HeldLockAuthStore(events, lockHeld = true)
        val scope = bridgeScope()

        scope.launchSettingsBridgeAction(callback = { events += "callback" }) {
            clearSessionForApiEnvironmentChange(auth) { events += "disconnect" }
            mapOf("status" to "ok")
        }

        // The caller got its thread back with the clear still waiting for the lock.
        assertEquals(1, auth.waitingClears)
        assertEquals(emptyList<String>(), events)

        auth.releaseLock()
        runCurrent()

        assertEquals(listOf("clear:api_environment_changed", "disconnect", "callback"), events)
        scope.cancel()
    }

    @Test
    fun teardownWhileWaitingStillClearsTheOldServersSessionAndAnswers() = runTest {
        val events = mutableListOf<String>()
        val auth = HeldLockAuthStore(events, lockHeld = true)
        val scope = bridgeScope()

        scope.launchSettingsBridgeAction(callback = { events += "callback" }) {
            clearSessionForApiEnvironmentChange(auth) { events += "disconnect" }
            mapOf("status" to "ok")
        }
        // The Activity is destroyed while the clear waits; the new environment is already saved.
        scope.cancel()
        auth.releaseLock()
        runCurrent()

        // The switch completed, so it is still answered, as the old synchronous path always was.
        assertEquals(listOf("clear:api_environment_changed", "disconnect", "callback"), events)
    }

    @Test
    fun aFailingActionAnswersTheGenericSettingsFailure() = runTest {
        var answer: Any? = null
        val scope = bridgeScope()

        scope.launchSettingsBridgeAction(callback = { answer = it }) { error("environment store unavailable") }

        assertEquals(
            mapOf(
                "status" to "error",
                "code" to "settings_capability_failed",
                "message" to "environment store unavailable",
            ),
            answer,
        )
        assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        scope.cancel()
    }

    private fun TestScope.bridgeScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    /** An auth store whose session lock is held by someone else until [releaseLock]. */
    private class HeldLockAuthStore(
        private val events: MutableList<String>,
        lockHeld: Boolean,
    ) : RaftAuthSessionStore {
        private val lock = CompletableDeferred<Unit>().apply { if (!lockHeld) complete(Unit) }
        var waitingClears = 0
            private set

        fun releaseLock() {
            lock.complete(Unit)
        }

        override suspend fun clearSession(reason: String, source: String) {
            waitingClears++
            lock.await()
            waitingClears--
            assertEquals("android_activity", source)
            events += "clear:$reason"
        }

        override suspend fun readSession(): StoredAuthSession? = error("not used")

        override suspend fun saveSession(session: StoredAuthSession) = error("not used")

        override suspend fun updateTokens(accessToken: String, refreshToken: String) = error("not used")

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) = error("not used")
    }
}
