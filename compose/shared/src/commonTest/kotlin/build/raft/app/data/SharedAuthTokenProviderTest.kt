package build.raft.app.data

import build.raft.app.settings.SettingsWebViewSurface
import build.raft.app.settings.standaloneSettingsWebViewInjectedJavaScript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedAuthTokenProviderTest {
    @Test
    fun persistsAttemptBeforeTransportAndAtomicallyClearsItWithSuccessor() = runBlocking {
        var resultPublishedBeforeRetirement: Boolean? = null
        val original = sampleSession(accessToken = "crash-old-access", refreshToken = "crash-old-refresh")
        val store = InMemoryRaftAuthSessionStore(original)
        var persistedBeforeTransport: StoredAuthRefreshAttempt? = null
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = original.refreshToken,
                responses =
                    listOf(
                        AuthApiResult.Success(
                            AuthRefreshResponse("crash-new-access", "crash-new-refresh")
                        )
                    ),
                beforeResponse = { persistedBeforeTransport = store.readSession()?.refreshAttempt },
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 123L },
                attemptIdFactory = { "arf_0000000000000001" },
                installationIdFactory = { "ari_00000000000000000000000000000002" },
                beforeFlightRetirement = { resultPublishedBeforeRetirement = it },
            )

        val result = coordinator.refreshSession(failedAccessToken = original.accessToken)

        assertEquals("arf_0000000000000001", persistedBeforeTransport?.attemptId)
        assertEquals("ari_00000000000000000000000000000002", persistedBeforeTransport?.installationId)
        assertEquals(listOf(AuthRefreshAttemptContext("arf_0000000000000001", "ari_00000000000000000000000000000002")), api.attempts)
        val committed = (result as SharedAuthRefreshResult.Success).session
        assertEquals("crash-new-access", committed.accessToken)
        assertNull(committed.refreshAttempt)
        assertNull(store.readSession()?.refreshAttempt)
        assertEquals(false, resultPublishedBeforeRetirement)
    }

    @Test
    fun coldStartReusesPersistedAttemptAfterServerCommitResponseLoss() = runBlocking {
        val persistedAttempt =
            StoredAuthRefreshAttempt(
                attemptId = "arf_0000000000000003",
                installationId = "ari_00000000000000000000000000000004",
                accountId = "user-1",
                predecessorAccessToken = "predecessor-access",
                predecessorRefreshToken = "predecessor-refresh",
                createdAtMillis = 10L,
            )
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession("predecessor-access", "predecessor-refresh")
                    .copy(refreshAttempt = persistedAttempt)
            )
        store.readOrCreateAuthInstallationId(persistedAttempt.installationId)
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "predecessor-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(
                            AuthRefreshResponse("replayed-access", "replayed-refresh")
                        )
                    ),
            )
        val restartedCoordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 20L },
                attemptIdFactory = { "arf_ffffffffffffffff" },
                installationIdFactory = { "ari_ffffffffffffffffffffffffffffffff" },
            )

        val result = restartedCoordinator.refreshSession(failedAccessToken = "predecessor-access")

        assertEquals(
            listOf(AuthRefreshAttemptContext(persistedAttempt.attemptId, persistedAttempt.installationId)),
            api.attempts,
        )
        assertEquals("replayed-access", (result as SharedAuthRefreshResult.Success).session.accessToken)
        assertNull(store.readSession()?.refreshAttempt)
    }

    @Test
    fun accountReplacementCannotConsumePriorAccountsAttempt() = runBlocking {
        val oldAttempt =
            StoredAuthRefreshAttempt(
                attemptId = "arf_0000000000000007",
                installationId = "ari_00000000000000000000000000000008",
                accountId = "user-1",
                predecessorAccessToken = "old-access",
                predecessorRefreshToken = "old-refresh",
                createdAtMillis = 0L,
            )
        val store = InMemoryRaftAuthSessionStore(sampleSession("old-access", "old-refresh").copy(refreshAttempt = oldAttempt))
        store.replaceAuthenticatedSession(
            sampleSession("account-b-access", "account-b-refresh")
                .copy(user = StoredAuthUser(id = "user-2", email = "b@raft.build"))
        )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "account-b-refresh",
                responses = listOf(AuthApiResult.Success(AuthRefreshResponse("b-new-access", "b-new-refresh"))),
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L },
                attemptIdFactory = { "arf_0000000000000009" },
                installationIdFactory = { "ari_0000000000000000000000000000000a" },
            )

        coordinator.refreshSession(failedAccessToken = "account-b-access")

        assertEquals("arf_0000000000000009", api.attempts.single().attemptId)
        assertEquals("user-2", store.readSession()?.user?.id)
    }

    @Test
    fun malformedPersistedAttemptIsAtomicallyReplacedBeforeRefresh() = runBlocking {
        val malformed =
            StoredAuthRefreshAttempt(
                attemptId = "not-a-wire-attempt",
                installationId = "not-an-installation",
                accountId = "user-1",
                predecessorAccessToken = "healed-access",
                predecessorRefreshToken = "healed-refresh",
                createdAtMillis = 1L,
            )
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession("healed-access", "healed-refresh").copy(refreshAttempt = malformed)
            )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "healed-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(AuthRefreshResponse("successor-access", "successor-refresh"))
                    ),
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 2L },
                attemptIdFactory = { "arf_000000000000000b" },
                installationIdFactory = { "ari_0000000000000000000000000000000c" },
            )

        val result = coordinator.refreshSession(failedAccessToken = "healed-access")

        assertTrue(result is SharedAuthRefreshResult.Success)
        assertEquals("arf_000000000000000b", api.attempts.single().attemptId)
        assertNull(store.readSession()?.refreshAttempt)
    }

    @Test
    fun persistedAttemptFromDifferentInstallationIsNeverReplayed() = runBlocking {
        val foreignAttempt =
            StoredAuthRefreshAttempt(
                attemptId = "arf_0000000000000011",
                installationId = "ari_11111111111111111111111111111111",
                accountId = "user-1",
                predecessorAccessToken = "install-access",
                predecessorRefreshToken = "install-refresh",
                createdAtMillis = 1L,
            )
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession("install-access", "install-refresh").copy(refreshAttempt = foreignAttempt)
            )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "install-refresh",
                responses =
                    listOf(AuthApiResult.Success(AuthRefreshResponse("new-access", "new-refresh"))),
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 2L },
                attemptIdFactory = { "arf_0000000000000012" },
                installationIdFactory = { "ari_22222222222222222222222222222222" },
            )

        coordinator.refreshSession(failedAccessToken = "install-access")

        assertEquals("arf_0000000000000012", api.attempts.single().attemptId)
        assertEquals("ari_22222222222222222222222222222222", api.attempts.single().installationId)
    }

    @Test
    fun refreshDiagnosticsNeverExposePredecessorOrSuccessorTokens() = runBlocking {
        val secrets = listOf("raw-old-access", "raw-old-refresh", "raw-new-access", "raw-new-refresh")
        val logs = mutableListOf<String>()
        val store = InMemoryRaftAuthSessionStore(sampleSession(secrets[0], secrets[1]))
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi =
                    FakeRefreshAuthApi(
                        refreshTokenAssertion = secrets[1],
                        responses =
                            listOf(AuthApiResult.Success(AuthRefreshResponse(secrets[2], secrets[3]))),
                    ),
                clockMillis = { 3L },
                logger = logs::add,
                warningLogger = logs::add,
                criticalLogger = logs::add,
                attemptIdFactory = { "arf_000000000000000d" },
                installationIdFactory = { "ari_0000000000000000000000000000000e" },
            )

        coordinator.refreshSession(failedAccessToken = secrets[0])

        assertTrue(logs.isNotEmpty())
        secrets.forEach { secret -> assertFalse(logs.any { secret in it }) }
    }

    @Test
    fun refreshIdentityFactoriesProduceCanonicalWireShapes() {
        assertEquals("arf_0000000000000001", newAuthRefreshAttemptId { 1L })
        var next = 1L
        assertEquals(
            "ari_00000000000000010000000000000002",
            newAuthInstallationId { next++ },
        )
        assertTrue("arf_0123456789abcdef".isValidAuthRefreshAttemptId())
        assertTrue("ari_0123456789abcdef0123456789abcdef".isValidAuthInstallationId())
        assertFalse("arf_ABCDEF".isValidAuthRefreshAttemptId())
    }

    @Test
    fun webViewBootstrapCannotConsumeNativeRefreshOwnerBeforeNextNativeRefresh() = runBlocking {
        val original = sampleSession(accessToken = "webview-old-access", refreshToken = "native-refresh-secret")
        val store = InMemoryRaftAuthSessionStore(original)
        val scripts =
            listOf(
                standaloneSettingsWebViewInjectedJavaScript(original, null, SettingsWebViewSurface.Billing, "https://app.raft.build"),
                standaloneSettingsWebViewInjectedJavaScript(original, null, SettingsWebViewSurface.Marketplace, "https://app.raft.build"),
                standaloneSettingsWebViewInjectedJavaScript(original, null, SettingsWebViewSurface.Administration, "https://app.raft.build"),
            )
        scripts.forEach { script ->
            assertContains(script, "removeItem(\"slock_refresh_token\")")
            assertFalse(script.contains(original.refreshToken))
        }
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "native-refresh-secret",
                responses =
                    listOf(
                        AuthApiResult.Success(
                            AuthRefreshResponse(
                                accessToken = "webview-successor-access",
                                refreshToken = "webview-successor-refresh",
                            )
                        )
                    ),
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L },
            )

        val result =
            coordinator.refreshSession(
                failedAccessToken = "webview-old-access",
                failedRefreshToken = "native-refresh-secret",
            )

        assertEquals(1, api.refreshCalls)
        assertEquals(
            "webview-successor-access",
            (result as SharedAuthRefreshResult.Success).session.accessToken,
        )
        assertEquals("webview-successor-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun coalescesRefreshAcrossProviderInstances() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(accessToken = "old-access", refreshToken = "old-refresh")
            )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "old-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh")),
                        AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"invalid refresh"}""")
                    ),
                delayMillis = 50
            )
        val firstProvider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L }
            )
        val secondProvider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L }
            )

        coroutineScope {
            val first = async { firstProvider.refreshSession(failedAccessToken = "old-access") }
            val second = async { secondProvider.refreshSession(failedAccessToken = "old-access") }

            assertEquals("fresh-access", first.await()?.accessToken)
            assertEquals("fresh-access", second.await()?.accessToken)
        }
        assertEquals(1, api.refreshCalls)
        assertEquals("fresh-access", store.readSession()?.accessToken)
    }

    @Test
    fun cancelledCallerAfterSuccessDoesNotCancelRefreshCommit() = runBlocking {
        val store =
            BlockingCommitSessionStore(
                initialSession =
                    sampleSession(
                        accessToken = "cancel-owner-old-access",
                        refreshToken = "cancel-owner-old-refresh"
                    ),
                blockedAccessToken = "cancel-owner-fresh-access"
            )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "cancel-owner-old-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(
                            AuthRefreshResponse(
                                accessToken = "cancel-owner-fresh-access",
                                refreshToken = "cancel-owner-fresh-refresh"
                            )
                        )
                    )
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L }
            )

        val caller =
            launch {
                provider.refreshSession(
                    failedAccessToken = "cancel-owner-old-access",
                    failedRefreshToken = "cancel-owner-old-refresh",
                    clearOnUnauthorized = false,
                    reason = "connect_error",
                    caller = "android_socket"
                )
            }
        store.commitStarted.await()
        caller.cancelAndJoin()
        store.releaseCommit.complete(Unit)

        withTimeout(2_000L) {
            while (store.readSession()?.accessToken != "cancel-owner-fresh-access") {
                yield()
            }
        }
        assertEquals(1, api.refreshCalls)
        assertEquals("cancel-owner-fresh-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun returnsNewerStoredSessionWithoutRefreshingOldTokensAgain() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(
                    accessToken = "already-new-access",
                    refreshToken = "already-new-refresh"
                )
            )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "unused-refresh",
                responses = emptyList()
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L }
            )

        val result =
            provider.refreshSession(
                failedAccessToken = "already-old-access",
                failedRefreshToken = "already-old-refresh",
                reason = "http_401",
                caller = "shared_network"
            )

        assertEquals("already-new-access", result?.accessToken)
        assertEquals("already-new-refresh", result?.refreshToken)
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun retriesSameRefreshTokenWhenFirstResponseIsAmbiguouslyLost() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(
                    accessToken = "ambiguous-old-access",
                    refreshToken = "ambiguous-old-refresh",
                )
            )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "ambiguous-old-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(
                            AuthRefreshResponse(
                                accessToken = "ambiguous-successor-access",
                                refreshToken = "ambiguous-successor-refresh",
                            )
                        )
                    ),
                beforeResponse = { call ->
                    if (call == 1) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                },
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L },
                refreshAttemptTimeoutMillis = 25L,
            )

        val result =
            coordinator.refreshSession(
                failedAccessToken = "ambiguous-old-access",
                failedRefreshToken = "ambiguous-old-refresh",
                clearOnUnauthorized = true,
                reason = "connect_error",
                caller = "android_socket",
            )

        assertTrue(firstStarted.isCompleted)
        assertEquals(2, api.refreshCalls)
        assertEquals(
            "ambiguous-successor-access",
            (result as SharedAuthRefreshResult.Success).session.accessToken,
        )
        assertEquals("ambiguous-successor-refresh", store.readSession()?.refreshToken)
        releaseFirst.complete(Unit)
        Unit
    }

    @Test
    fun offlineTimeoutMarkerAllowsSeparateRecoveryFlight() = runBlocking {
        var now = 1_000L
        val neverRespond = CompletableDeferred<Unit>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(
                    accessToken = "timeout-old-access",
                    refreshToken = "timeout-old-refresh",
                )
            )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "timeout-old-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(
                            AuthRefreshResponse(
                                accessToken = "recovered-access",
                                refreshToken = "recovered-refresh",
                            )
                        )
                    ),
                beforeResponse = { call ->
                    if (call <= 2) neverRespond.await()
                },
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { now },
                refreshAttemptTimeoutMillis = 25L,
            )

        val result =
            coordinator.refreshSession(
                failedAccessToken = "timeout-old-access",
                failedRefreshToken = "timeout-old-refresh",
                clearOnUnauthorized = true,
                reason = "connect_error",
                caller = "android_socket",
            )

        assertEquals(2, api.refreshCalls)
        assertEquals(0, (result as SharedAuthRefreshResult.Failure).response.code)
        assertEquals("refresh_retry_timeout", result.response.contractCode)
        assertEquals("timeout-old-access", store.readSession()?.accessToken)
        assertEquals("timeout-old-refresh", store.readSession()?.refreshToken)

        now += 29L * 60L * 1_000L
        val recoveryFlight =
            coordinator.refreshSession(
                failedAccessToken = "timeout-old-access",
                failedRefreshToken = "timeout-old-refresh",
                clearOnUnauthorized = true,
                reason = "network_recovered",
                caller = "shared_network_recovery",
            )

        assertEquals(3, api.refreshCalls)
        assertEquals("recovered-access", (recoveryFlight as SharedAuthRefreshResult.Success).session.accessToken)
        assertEquals("recovered-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun doesNotRetryConsumedTokenAfterSuspendExceedsReplayWindow() = runBlocking {
        var now = 1_000L
        val neverRespond = CompletableDeferred<Unit>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(
                    accessToken = "suspend-old-access",
                    refreshToken = "suspend-old-refresh",
                )
            )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "suspend-old-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(
                            AuthRefreshResponse(
                                accessToken = "lost-successor-access",
                                refreshToken = "lost-successor-refresh",
                            )
                        )
                    ),
                beforeResponse = {
                    now += 28L * 60L * 1_000L
                    neverRespond.await()
                },
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { now },
                refreshAttemptTimeoutMillis = 25L,
            )

        val result =
            coordinator.refreshSession(
                failedAccessToken = "suspend-old-access",
                failedRefreshToken = "suspend-old-refresh",
                clearOnUnauthorized = true,
                reason = "connect_error",
                caller = "android_socket",
            )

        assertEquals(1, api.refreshCalls)
        assertEquals(0, (result as SharedAuthRefreshResult.Failure).response.code)
        assertEquals("refresh_replay_window_elapsed", result.response.contractCode)
        assertEquals("suspend-old-access", store.readSession()?.accessToken)
        assertEquals("suspend-old-refresh", store.readSession()?.refreshToken)
        Unit
    }

    @Test
    fun sharesUnauthorizedResultWhileKeepingClearPolicyCallerSpecific() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(
                    accessToken = "unauthorized-shared-access",
                    refreshToken = "unauthorized-shared-refresh"
                )
            )
        val api =
            FakeRefreshAuthApi(
                refreshTokenAssertion = "unauthorized-shared-refresh",
                responses =
                    listOf(
                        AuthApiResult.Failure(
                            code = 401,
                            errorBodyText = """{"error":"invalid refresh"}"""
                        )
                    ),
                beforeResponse = {
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                }
            )
        val joinObserved = CompletableDeferred<Unit>()
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L }
            )
        val middleware =
            RaftNetworkAuthMiddleware(
                sessionStore = store,
                authApi = api,
                logger = { message ->
                    if (message.startsWith("refresh_join")) joinObserved.complete(Unit)
                }
            )

        coroutineScope {
            val socket =
                async {
                    provider.refreshSession(
                        failedAccessToken = "unauthorized-shared-access",
                        failedRefreshToken = "unauthorized-shared-refresh",
                        clearOnUnauthorized = false,
                        reason = "connect_error",
                        caller = "android_socket"
                    )
                }
            requestStarted.await()
            val authLoss =
                async {
                    middleware.refreshAfterUnauthorized(
                        failedSession = checkNotNull(store.readSession()),
                        clearOnUnauthorized = true,
                        reason = "http_401",
                        caller = "shared_network"
                    )
                }
            joinObserved.await()
            releaseResponse.complete(Unit)

            assertNull(socket.await())
            assertEquals(401, (authLoss.await() as SharedAuthRefreshResult.Failure).response.code)
        }
        assertEquals(1, api.refreshCalls)
        assertNull(store.readSession())
    }

    @Test
    fun clearsSessionWhenRefreshTokenIsUnauthorized() = runBlocking {
        val warnings = mutableListOf<String>()
        val criticalWarnings = mutableListOf<String>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(accessToken = "old-access", refreshToken = "old-refresh")
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi =
                    FakeRefreshAuthApi(
                        refreshTokenAssertion = "old-refresh",
                        responses =
                            listOf(
                                AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"invalid refresh"}""")
                            )
                ),
                clockMillis = { 0L },
                warningLogger = warnings::add,
                criticalLogger = criticalWarnings::add
            )

        assertNull(provider.refreshSession(failedAccessToken = "old-access"))
        assertNull(store.readSession())
        assertEquals(
            listOf(
                "refresh_failed status=401 reason=unknown caller=unknown attemptAgeMs=0",
                "refresh_unauthorized_session_cleared status=401 reason=unknown caller=unknown attemptAgeMs=0"
            ),
            criticalWarnings
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun unauthorizedRefreshCompareAndClearPreservesConcurrentTokenRotation() = runBlocking {
        val store =
            RotateBeforeCompareClearSessionStore(
                initialSession = sampleSession("stale-access", "stale-refresh"),
                successorAccessToken = "successor-access",
                successorRefreshToken = "successor-refresh"
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi =
                    FakeRefreshAuthApi(
                        refreshTokenAssertion = "stale-refresh",
                        responses =
                            listOf(
                                AuthApiResult.Failure(
                                    code = 401,
                                    errorBodyText = """{"error":"invalid refresh"}"""
                                )
                            )
                    ),
                clockMillis = { 0L }
            )

        val result =
            provider.refreshSession(
                failedAccessToken = "stale-access",
                failedRefreshToken = "stale-refresh",
                clearOnUnauthorized = true
            )

        assertEquals("successor-access", result?.accessToken)
        assertEquals("successor-refresh", result?.refreshToken)
        assertEquals("successor-access", store.readSession()?.accessToken)
        assertEquals(1, store.compareAndClearCalls)
    }

    @Test
    fun preservesSessionWhenRefreshTokenIsUnauthorizedAndClearDisabled() = runBlocking {
        val warnings = mutableListOf<String>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(accessToken = "preserve-access", refreshToken = "preserve-refresh")
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi =
                    FakeRefreshAuthApi(
                        refreshTokenAssertion = "preserve-refresh",
                        responses =
                            listOf(
                                AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"invalid refresh"}""")
                            )
                    ),
                clockMillis = { 0L },
                warningLogger = warnings::add
            )

        assertNull(
            provider.refreshSession(
                failedAccessToken = "preserve-access",
                clearOnUnauthorized = false
            )
        )
        assertEquals("preserve-access", store.readSession()?.accessToken)
        assertEquals(
            listOf(
                "refresh_failed status=401 reason=unknown caller=unknown attemptAgeMs=0",
                "refresh_unauthorized_session_preserved status=401 reason=unknown caller=unknown attemptAgeMs=0"
            ),
            warnings
        )
    }

    @Test
    fun currentSessionConsumesCommittedTokensWithoutProactiveRefresh() = runBlocking {
        val warnings = mutableListOf<String>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(accessToken = "old-access", refreshToken = "")
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi =
                    FakeRefreshAuthApi(
                        refreshTokenAssertion = "unused",
                        responses = emptyList()
                    ),
                clockMillis = { 0L },
                warningLogger = warnings::add
            )

        assertEquals("old-access", provider.currentSession()?.accessToken)
        assertEquals("old-access", store.readSession()?.accessToken)
        assertEquals(emptyList(), warnings)
    }

    private class BlockingCommitSessionStore(
        initialSession: StoredAuthSession,
        private val blockedAccessToken: String
    ) : RaftAuthSessionStore {
        private val delegate = InMemoryRaftAuthSessionStore(initialSession)
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()

        override suspend fun readSession(): StoredAuthSession? = delegate.readSession()

        override suspend fun saveSession(session: StoredAuthSession) {
            if (session.accessToken == blockedAccessToken) {
                commitStarted.complete(Unit)
                releaseCommit.await()
            }
            delegate.saveSession(session)
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            delegate.updateTokens(accessToken, refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            delegate.updateSelectedServer(serverId, serverSlug)
        }

        override suspend fun clearSession(reason: String, source: String) {
            delegate.clearSession(reason, source)
        }
    }

    private class RotateBeforeCompareClearSessionStore(
        initialSession: StoredAuthSession,
        private val successorAccessToken: String,
        private val successorRefreshToken: String,
    ) : RaftAuthSessionStore {
        private val delegate = InMemoryRaftAuthSessionStore(initialSession)
        var compareAndClearCalls: Int = 0
            private set

        override suspend fun readSession(): StoredAuthSession? = delegate.readSession()

        override suspend fun saveSession(session: StoredAuthSession) = delegate.saveSession(session)

        override suspend fun updateTokens(accessToken: String, refreshToken: String) =
            delegate.updateTokens(accessToken, refreshToken)

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) =
            delegate.updateSelectedServer(serverId, serverSlug)

        override suspend fun clearSession(reason: String, source: String) =
            delegate.clearSession(reason, source)

        override suspend fun clearSessionIfMatches(
            expectedAccountId: String,
            expectedAccessToken: String,
            expectedRefreshToken: String,
            reason: String,
            source: String,
        ): Boolean {
            compareAndClearCalls += 1
            delegate.updateTokens(successorAccessToken, successorRefreshToken)
            return delegate.clearSessionIfMatches(
                expectedAccountId = expectedAccountId,
                expectedAccessToken = expectedAccessToken,
                expectedRefreshToken = expectedRefreshToken,
                reason = reason,
                source = source,
            )
        }
    }

    private fun sampleSession(
        accessToken: String,
        refreshToken: String
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
            accessToken = accessToken,
            refreshToken = refreshToken,
            selectedServerId = "server-1",
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )
}
