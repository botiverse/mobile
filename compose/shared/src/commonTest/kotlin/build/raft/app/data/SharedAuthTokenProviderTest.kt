package build.raft.app.data

import build.raft.app.settingsAdministrationWebViewInjectedJavaScript
import build.raft.app.settingsBillingWebViewInjectedJavaScript
import build.raft.app.settingsComputersWebViewInjectedJavaScript
import build.raft.app.settingsMarketplaceWebViewInjectedJavaScript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
            FakeAuthApi(
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
            FakeAuthApi(
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
    fun ambiguousRetryUsesSameDurableAttempt() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(sampleSession("ambiguous-access", "ambiguous-refresh"))
        val api =
            FakeAuthApi(
                refreshTokenAssertion = "ambiguous-refresh",
                responses =
                    listOf(
                        AuthApiResult.Failure(code = 0),
                        AuthApiResult.Success(AuthRefreshResponse("successor-access", "successor-refresh")),
                    ),
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L },
                attemptIdFactory = { "arf_0000000000000005" },
                installationIdFactory = { "ari_00000000000000000000000000000006" },
            )

        coordinator.refreshSession(failedAccessToken = "ambiguous-access")

        assertEquals(2, api.attempts.size)
        assertEquals(api.attempts[0], api.attempts[1])
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
            FakeAuthApi(
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
            FakeAuthApi(
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
            FakeAuthApi(
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
                    FakeAuthApi(
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
                settingsComputersWebViewInjectedJavaScript(original),
                settingsBillingWebViewInjectedJavaScript(original),
                settingsMarketplaceWebViewInjectedJavaScript(original),
                settingsAdministrationWebViewInjectedJavaScript(original),
            )
        scripts.forEach { script ->
            assertContains(script, "removeItem(\"slock_refresh_token\")")
            assertFalse(script.contains(original.refreshToken))
        }
        val api =
            FakeAuthApi(
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
            FakeAuthApi(
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
            FakeAuthApi(
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
            FakeAuthApi(
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
    fun coalescesHttpAndRealtimeTriggersThroughOneCoordinator() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(
                    accessToken = "cross-caller-old-access",
                    refreshToken = "cross-caller-old-refresh"
                )
            )
        val api =
            FakeAuthApi(
                refreshTokenAssertion = "cross-caller-old-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(
                            AuthRefreshResponse(
                                accessToken = "cross-caller-fresh-access",
                                refreshToken = "cross-caller-fresh-refresh"
                            )
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
                        failedAccessToken = "cross-caller-old-access",
                        failedRefreshToken = "cross-caller-old-refresh",
                        clearOnUnauthorized = false,
                        reason = "connect_error",
                        caller = "android_socket"
                    )
                }
            requestStarted.await()
            val http =
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

            assertEquals("cross-caller-fresh-access", socket.await()?.accessToken)
            assertEquals(
                "cross-caller-fresh-access",
                (http.await() as SharedAuthRefreshResult.Success).session.accessToken
            )
        }
        assertEquals(1, api.refreshCalls)
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
            FakeAuthApi(
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
            FakeAuthApi(
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
            FakeAuthApi(
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
    fun ambiguousRetryUnauthorizedRemainsTerminalForDurableAttempt() = runBlocking {
        var resultPublishedBeforeRetirement: Boolean? = null
        val criticalLogs = mutableListOf<String>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(
                    accessToken = "ambiguous-network-access",
                    refreshToken = "ambiguous-network-refresh",
                )
            )
        val api =
            FakeAuthApi(
                refreshTokenAssertion = "ambiguous-network-refresh",
                responses =
                    listOf(
                        AuthApiResult.Failure(code = 0, errorBodyText = "connection reset"),
                        AuthApiResult.Failure(code = 401, errorBodyText = "invalid refresh"),
                    ),
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L },
                criticalLogger = criticalLogs::add,
                beforeFlightRetirement = { resultPublishedBeforeRetirement = it },
            )

        val result =
            coordinator.refreshSession(
                failedAccessToken = "ambiguous-network-access",
                failedRefreshToken = "ambiguous-network-refresh",
                clearOnUnauthorized = true,
                reason = "http_401",
                caller = "shared_network",
            )

        assertEquals(2, api.refreshCalls)
        assertEquals(401, (result as SharedAuthRefreshResult.Failure).response.code)
        assertNull(store.readSession())
        assertEquals(1, criticalLogs.count { it.contains("refresh_unauthorized_session_cleared") })
        assertEquals(false, resultPublishedBeforeRetirement)
    }

    @Test
    fun cancelledOwnerRetiresFlightBeforeWaitersObserveCancellation() = runBlocking {
        var resultPublishedBeforeRetirement: Boolean? = null
        val store = InMemoryRaftAuthSessionStore(
            sampleSession("cancelled-flight-access", "cancelled-flight-refresh")
        )
        val api = FakeAuthApi(
            refreshTokenAssertion = "cancelled-flight-refresh",
            responses = listOf(
                AuthApiResult.Success(AuthRefreshResponse("unused", "unused")),
                AuthApiResult.Success(AuthRefreshResponse("recovered-access", "recovered-refresh")),
            ),
            beforeResponse = { call ->
                if (call == 1) throw CancellationException("cancel owner before publication")
            },
        )
        val coordinator = SharedAuthRefreshCoordinator(
            sessionStore = store,
            authApi = api,
            clockMillis = { 0L },
            beforeFlightRetirement = { resultPublishedBeforeRetirement = it },
        )

        assertFailsWith<CancellationException> {
            coordinator.refreshSession(failedAccessToken = "cancelled-flight-access")
        }
        val recovered = coordinator.refreshSession(failedAccessToken = "cancelled-flight-access")

        assertEquals(2, api.refreshCalls)
        assertEquals("recovered-access", (recovered as SharedAuthRefreshResult.Success).session.accessToken)
        assertEquals(false, resultPublishedBeforeRetirement)
    }

    @Test
    fun priorAmbiguousFlightCannotSuppressLaterDurableUnauthorized() = runBlocking {
        val immediateRetryCompleted = CompletableDeferred<Unit>()
        val criticalLogs = mutableListOf<String>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(
                    accessToken = "near-expiry-access",
                    refreshToken = "near-expiry-refresh",
                )
            )
        val api =
            FakeAuthApi(
                refreshTokenAssertion = "near-expiry-refresh",
                responses =
                    listOf(
                        AuthApiResult.Failure(code = 0, errorBodyText = "connection reset"),
                        AuthApiResult.Failure(code = 0, errorBodyText = "retry timeout"),
                        AuthApiResult.Failure(code = 401, errorBodyText = "revoked or expired"),
                    ),
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 1_000L },
                criticalLogger = criticalLogs::add,
                beforeFlightRetirement = { resultAlreadyPublished ->
                    if (resultAlreadyPublished) immediateRetryCompleted.await()
                },
            )

        val terminal = async {
            val ambiguous = coordinator.refreshSession(
                failedAccessToken = "near-expiry-access",
                failedRefreshToken = "near-expiry-refresh",
            )
            assertEquals(0, (ambiguous as SharedAuthRefreshResult.Failure).response.code)
            assertEquals("near-expiry-refresh", store.readSession()?.refreshToken)
            coordinator.refreshSession(
                failedAccessToken = "near-expiry-access",
                failedRefreshToken = "near-expiry-refresh",
            ).also { immediateRetryCompleted.complete(Unit) }
        }.await()

        assertEquals(3, api.refreshCalls)
        assertEquals(401, (terminal as SharedAuthRefreshResult.Failure).response.code)
        assertNull(store.readSession())
        assertEquals(1, criticalLogs.count { it.contains("refresh_unauthorized_session_cleared") })
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
            FakeAuthApi(
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
                    FakeAuthApi(
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
                "refresh_failed status=401 reason=unknown caller=unknown",
                "refresh_unauthorized_session_cleared status=401 reason=unknown caller=unknown"
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
                    FakeAuthApi(
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
    fun logsRefreshLifecycleWithTokenFingerprintsOnly() = runBlocking {
        val logs = mutableListOf<String>()
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(accessToken = "old-access-token", refreshToken = "old-refresh-token")
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi =
                    FakeAuthApi(
                        refreshTokenAssertion = "old-refresh-token",
                        responses =
                            listOf(
                                AuthApiResult.Success(
                                    AuthRefreshResponse("fresh-access-token", "fresh-refresh-token")
                                )
                            )
                    ),
                clockMillis = { 0L },
                logger = logs::add
            )

        val refreshed =
            provider.refreshSession(
                failedAccessToken = "old-access-token",
                reason = "http_401",
                caller = "shared_network:auth/me"
            )

        assertEquals("fresh-access-token", refreshed?.accessToken)
        val start = logs.single { it.startsWith("refresh_start") }
        assertContains(start, "reason=http_401")
        assertContains(start, "caller=shared_network:auth/me")
        assertContains(start, "access=len=")
        assertContains(start, "refresh=len=")
        val success = logs.single { it.startsWith("refresh_success") }
        assertContains(success, "accessRotated=true")
        assertContains(success, "refreshRotated=true")
        assertContains(success, "oldAccess=len=")
        assertContains(success, "newAccess=len=")
        assertContains(success, "oldRefresh=len=")
        assertContains(success, "newRefresh=len=")
        logs.forEach { log ->
            assertFalse(log.contains("old-access-token"), "refresh diagnostics must not log full old access token")
            assertFalse(log.contains("fresh-access-token"), "refresh diagnostics must not log full new access token")
            assertFalse(log.contains("old-refresh-token"), "refresh diagnostics must not log full old refresh token")
            assertFalse(log.contains("fresh-refresh-token"), "refresh diagnostics must not log full new refresh token")
        }
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
                    FakeAuthApi(
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
                "refresh_failed status=401 reason=unknown caller=unknown",
                "refresh_unauthorized_session_preserved status=401 reason=unknown caller=unknown"
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
                    FakeAuthApi(
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

    @Test
    fun networkMiddlewarePreservesSessionWhenRefreshIsUnauthorized() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(
                    accessToken = "middleware-preserve-access",
                    refreshToken = "middleware-preserve-refresh"
                )
            )
        val middleware =
            RaftNetworkAuthMiddleware(
                sessionStore = store,
                authApi =
                    FakeAuthApi(
                        refreshTokenAssertion = "middleware-preserve-refresh",
                        responses =
                            listOf(
                                AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"invalid refresh"}""")
                            )
                    )
            )

        val response =
            middleware.request {
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"jwt expired"}""")
            }

        assertEquals(401, response.code)
        assertEquals("middleware-preserve-access", store.readSession()?.accessToken)
    }

    private class FakeAuthApi(
        private val refreshTokenAssertion: String,
        private val responses: List<AuthApiResult<AuthRefreshResponse>>,
        private val delayMillis: Long = 0L,
        private val beforeResponse: suspend (Int) -> Unit = {}
    ) : RaftAuthApi {
        var refreshCalls: Int = 0
            private set
        val attempts = mutableListOf<AuthRefreshAttemptContext>()

        override suspend fun refreshWithAttempt(
            refreshToken: String,
            attempt: AuthRefreshAttemptContext,
        ): AuthApiResult<AuthRefreshResponse> {
            attempts += attempt
            return refresh(refreshToken)
        }

        override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> {
            assertEquals(refreshTokenAssertion, refreshToken)
            refreshCalls += 1
            beforeResponse(refreshCalls)
            if (delayMillis > 0) delay(delayMillis)
            return responses.getOrElse(refreshCalls - 1) { responses.last() }
        }

        override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun completeSocialLogin(
            provider: String,
            bodyText: String
        ): AuthApiResult<AuthSocialCompleteResponse> =
            error("not used")

        override suspend fun fetchSocialAuthProviders(platform: String): AuthApiResult<Set<String>> =
            error("not used")

        override suspend fun startMobileOAuth(
            provider: String,
            mode: String,
            platform: String,
            appEnv: String,
            returnUri: String,
            codeChallenge: String,
            authorization: String?
        ): AuthApiResult<AuthMobileOAuthStartResponse> =
            error("not used")

        override suspend fun completeMobileOAuth(
            code: String,
            codeVerifier: String,
            platform: String,
            acceptLegalTerms: Boolean,
            authorization: String?
        ): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun startMobileOAuthLink(
            provider: String,
            returnUri: String,
            codeChallenge: String,
            authorization: String
        ): AuthApiResult<AuthMobileOAuthStartResponse> =
            error("not used")

        override suspend fun completeMobileOAuthLink(
            provider: String,
            code: String,
            codeVerifier: String,
            authorization: String
        ): AuthApiResult<AuthMobileOAuthLinkCompleteResponse> =
            error("not used")

        override suspend fun register(email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun forgotPassword(email: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun resendVerification(authorization: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun verifyEmail(token: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
            error("not used")

        override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
            error("not used")

        override suspend fun serverOrder(authorization: String): AuthApiResult<ServerSwitcherOrderPreference> =
            error("not used")

        override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
            error("not used")
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
