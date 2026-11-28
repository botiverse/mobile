package build.raft.app.shared

import build.raft.app.data.AuthNetworkResponse
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftAuthTransport
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.revokeAuthSessionAtBaseUrlBestEffort
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #3823 follow-up: Settings > Developer's API-server switch cleared the session with
 * `runBlocking` on the main thread, which deadlocks when a Main coroutine is queued on the
 * auth-session lock. It now suspends; these pin the ordering and completion it must keep.
 */
class AndroidApiEnvironmentSessionResetTest {
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

    /**
     * The real process-wide session `Mutex` held by another thread while the switch runs on a
     * single-threaded Main. This is the #3823 shape: the unlock hands the lock to the Main waiter,
     * which must be able to run (the old `runBlocking` parked Main and never let it). The order
     * stays save -> clear session -> realtime disconnect -> answer.
     */
    @Test
    fun switchOnMainWaitsForTheRealSessionLockAndKeepsTheOrder() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val holderLocked = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        // Holds the shared session lock: readSession() logs a signed-out read inside the lock.
        val holder =
            Thread {
                runBlocking {
                    KeyValueAuthSessionStore(
                        InMemoryRaftKeyValueStore(),
                        diagnosticsLogger = {
                            holderLocked.countDown()
                            releaseHolder.await(10, TimeUnit.SECONDS)
                        },
                    ).readSession()
                }
            }
        try {
            val authStore = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
            runBlocking {
                authStore.replaceAuthenticatedSession(
                    StoredAuthSession(
                        user = StoredAuthUser(id = "user-1", email = "a@example.com"),
                        accessToken = "access",
                        refreshToken = "refresh",
                        selectedServerId = "server-1",
                    )
                )
            }
            holder.start()
            assertTrue(holderLocked.await(10, TimeUnit.SECONDS))
            val events = Collections.synchronizedList(mutableListOf<String>())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

            scope.launchSettingsBridgeAction(callback = { events += "answer" }) {
                val switch =
                    switchApiEnvironment(
                        current = "production",
                        persist = {
                            events += "save"
                            "staging"
                        },
                        authStore = authStore,
                    ) { before, after ->
                        val cleared = authStore.readCommittedSessionSnapshot() == null
                        events += "disconnect:$before->$after:cleared=$cleared"
                    }
                assertNotNull(switch)
                mapOf("status" to "ok")
            }

            // The caller (Main) is free while the clear waits for the lock.
            assertEquals(listOf("save"), events.toList())
            assertNotNull(authStore.readCommittedSessionSnapshot())

            releaseHolder.countDown()
            holder.join(10_000)
            // The unlock handed the lock to the Main waiter; Main runs it.
            main.scheduler.advanceUntilIdle()

            assertEquals(
                listOf("save", "disconnect:production->staging:cleared=true", "answer"),
                events.toList(),
            )
            scope.cancel()
        } finally {
            releaseHolder.countDown()
            holder.join(10_000)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reselectingTheSameEnvironmentKeepsTheSession() = runTest {
        val events = mutableListOf<String>()
        val auth = HeldLockAuthStore(events, lockHeld = false)

        val switch =
            switchApiEnvironment(
                current = "production",
                target = "production",
                beforePersist = { _, _ -> events += "beforePersist" },
                persist = { "production" },
                authStore = auth,
            ) { _, _ ->
                events += "disconnect"
            }

        assertEquals(ApiEnvironmentSwitch(after = "production", changed = false), switch)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun switchApiEnvironmentRevokesAgainstOldServerBeforePersistingAndClearing() = runTest {
        val events = mutableListOf<String>()
        val authStore = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        authStore.replaceAuthenticatedSession(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "a@example.com"),
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                selectedServerId = "server-1",
            )
        )
        val requestedUrls = mutableListOf<String>()
        var capturedBody: String? = null
        var capturedAuth: String? = null

        val switch =
            switchApiEnvironment(
                current = "https://old.example/api/",
                target = "https://new.example/api/",
                beforePersist = { beforeBaseUrl, _ ->
                    val session = authStore.readSession()
                    assertNotNull(session)
                    revokeAuthSessionAtBaseUrlBestEffort(
                        apiBaseUrl = beforeBaseUrl,
                        session = session!!,
                        transportFactory = { baseUrl ->
                            requestedUrls += baseUrl
                            RecordingLogoutTransport { body, authorization ->
                                capturedBody = body
                                capturedAuth = authorization
                                events += "logout:$baseUrl:hasSession=${authStore.readCommittedSessionSnapshot() != null}"
                                AuthNetworkResponse(200, """{"ok":true}""")
                            }
                        },
                    )
                },
                persist = {
                    events += "persist"
                    "https://new.example/api/"
                },
                authStore = authStore,
            ) { before, after ->
                events += "disconnect:$before->$after:cleared=${authStore.readCommittedSessionSnapshot() == null}"
            }

        assertEquals(ApiEnvironmentSwitch(after = "https://new.example/api/", changed = true), switch)
        assertEquals(listOf("https://old.example/api/"), requestedUrls)
        assertEquals("""{"refreshToken":"old-refresh-token"}""", capturedBody)
        assertEquals("Bearer old-access-token", capturedAuth)
        assertNull(authStore.readCommittedSessionSnapshot())
        assertEquals(
            listOf(
                "logout:https://old.example/api/:hasSession=true",
                "persist",
                "disconnect:https://old.example/api/->https://new.example/api/:cleared=true",
            ),
            events,
        )
    }

    @Test
    fun switchApiEnvironmentStillPersistsAndClearsWhenBeforePersistThrowsOrTimesOut() = runTest {
        // Case 1: beforePersist throws an unexpected error.
        run {
            val events = mutableListOf<String>()
            val auth = HeldLockAuthStore(events, lockHeld = false)

            val switch =
                switchApiEnvironment(
                    current = "production",
                    target = "staging",
                    beforePersist = { _, _ ->
                        events += "beforePersist_throws"
                        error("old server unreachable")
                    },
                    persist = {
                        events += "persist"
                        "staging"
                    },
                    authStore = auth,
                ) { _, _ ->
                    events += "disconnect"
                }

            assertEquals(ApiEnvironmentSwitch(after = "staging", changed = true), switch)
            assertEquals(
                listOf("beforePersist_throws", "persist", "clear:api_environment_changed", "disconnect"),
                events,
            )
        }

        // Case 2: revokeAuthSessionAtBaseUrlBestEffort times out against an unresponsive old server.
        run {
            val events = mutableListOf<String>()
            val authStore = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
            authStore.replaceAuthenticatedSession(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "a@example.com"),
                    accessToken = "old-access",
                    refreshToken = "old-refresh",
                    selectedServerId = "server-1",
                )
            )
            val hang = CompletableDeferred<Unit>()

            val switch =
                switchApiEnvironment(
                    current = "https://offline.example/api/",
                    target = "https://api.raft.build/api/",
                    beforePersist = { beforeBaseUrl, _ ->
                        val session = requireNotNull(authStore.readSession())
                        val outcome =
                            revokeAuthSessionAtBaseUrlBestEffort(
                                apiBaseUrl = beforeBaseUrl,
                                session = session,
                                timeoutMillis = 50L,
                                transportFactory = {
                                    RecordingLogoutTransport { _, _ ->
                                        events += "logout_started"
                                        hang.await()
                                        AuthNetworkResponse(200, """{"ok":true}""")
                                    }
                                },
                            )
                        events += "logout_outcome:$outcome"
                    },
                    persist = {
                        events += "persist"
                        "https://api.raft.build/api/"
                    },
                    authStore = authStore,
                ) { _, _ ->
                    events += "disconnect"
                }

            assertEquals(ApiEnvironmentSwitch(after = "https://api.raft.build/api/", changed = true), switch)
            assertNull(authStore.readCommittedSessionSnapshot())
            assertEquals(
                listOf("logout_started", "logout_outcome:failure_timeout", "persist", "disconnect"),
                events,
            )
        }
    }

    private class RecordingLogoutTransport(
        private val onLogout: suspend (String, String?) -> AuthNetworkResponse,
    ) : RaftAuthTransport {
        override suspend fun login(bodyText: String): AuthNetworkResponse = error("not used")
        override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse = error("not used")
        override suspend fun socialAuthProviders(platform: String): AuthNetworkResponse = error("not used")
        override suspend fun startMobileOAuthLink(provider: String, bodyText: String, authorization: String): AuthNetworkResponse = error("not used")
        override suspend fun completeMobileOAuthLink(provider: String, bodyText: String, authorization: String): AuthNetworkResponse = error("not used")
        override suspend fun resendVerification(authorization: String): AuthNetworkResponse = error("not used")
        override suspend fun verifyEmail(bodyText: String): AuthNetworkResponse = error("not used")
        override suspend fun currentUser(authorization: String): AuthNetworkResponse = error("not used")
        override suspend fun refresh(bodyText: String): AuthNetworkResponse = error("not used")
        override suspend fun logout(bodyText: String, authorization: String?): AuthNetworkResponse = onLogout(bodyText, authorization)
        override suspend fun servers(authorization: String): AuthNetworkResponse = error("not used")
        override suspend fun serverOrder(authorization: String): AuthNetworkResponse = error("not used")
        override suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse = error("not used")
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
