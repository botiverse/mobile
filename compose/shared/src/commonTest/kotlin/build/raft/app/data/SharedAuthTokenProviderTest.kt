package build.raft.app.data

import build.raft.app.settingsAdministrationWebViewInjectedJavaScript
import build.raft.app.settingsBillingWebViewInjectedJavaScript
import build.raft.app.settingsComputersWebViewInjectedJavaScript
import build.raft.app.settingsMarketplaceWebViewInjectedJavaScript
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedAuthTokenProviderTest {
    @Test
    fun webViewBootstrapCannotConsumeNativeRefreshOwnerBeforeNextNativeRefresh() = runBlocking {
        val original = sampleSession(accessToken = "webview-old-access", refreshToken = "native-refresh-secret")
        val store = InMemorySlockAuthSessionStore(original)
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
            InMemorySlockAuthSessionStore(
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
            InMemorySlockAuthSessionStore(
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
            InMemorySlockAuthSessionStore(
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
            SlockNetworkAuthMiddleware(
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
            InMemorySlockAuthSessionStore(
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
            InMemorySlockAuthSessionStore(
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
            InMemorySlockAuthSessionStore(
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
    fun ambiguousTransportFailureFencesSeparateUnauthorizedFlight() = runBlocking {
        val criticalLogs = mutableListOf<String>()
        val store =
            InMemorySlockAuthSessionStore(
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
                        AuthApiResult.Failure(code = 401, errorBodyText = "follow-up unauthorized"),
                        AuthApiResult.Success(
                            AuthRefreshResponse(
                                accessToken = "recovered-access",
                                refreshToken = "recovered-refresh",
                            )
                        ),
                    ),
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L },
                criticalLogger = criticalLogs::add,
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
        assertEquals(0, (result as SharedAuthRefreshResult.Failure).response.code)
        assertEquals("refresh_ambiguous_retry_unauthorized", result.response.contractCode)
        assertEquals("ambiguous-network-access", store.readSession()?.accessToken)
        assertEquals("ambiguous-network-refresh", store.readSession()?.refreshToken)

        val separateFlight =
            coordinator.refreshSession(
                failedAccessToken = "ambiguous-network-access",
                failedRefreshToken = "ambiguous-network-refresh",
                clearOnUnauthorized = true,
                reason = "http_401_follow_up",
                caller = "shared_network_follow_up",
            )

        assertEquals(3, api.refreshCalls)
        assertEquals(0, (separateFlight as SharedAuthRefreshResult.Failure).response.code)
        assertEquals("refresh_prior_ambiguous_unauthorized", separateFlight.response.contractCode)
        assertEquals("ambiguous-network-access", store.readSession()?.accessToken)
        assertEquals("ambiguous-network-refresh", store.readSession()?.refreshToken)
        assertFalse(criticalLogs.any { it.contains("refresh_unauthorized_session_cleared") })

        val recoveryFlight =
            coordinator.refreshSession(
                failedAccessToken = "ambiguous-network-access",
                failedRefreshToken = "ambiguous-network-refresh",
                clearOnUnauthorized = true,
                reason = "network_recovered",
                caller = "shared_network_recovery",
            )

        assertEquals(4, api.refreshCalls)
        assertEquals("recovered-access", (recoveryFlight as SharedAuthRefreshResult.Success).session.accessToken)
        assertEquals("recovered-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun inheritedAmbiguousMarkerDoesNotRenewAfterExpiringDuringFlight() = runBlocking {
        var now = 1_000L
        val criticalLogs = mutableListOf<String>()
        val store =
            InMemorySlockAuthSessionStore(
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
                        AuthApiResult.Failure(code = 401, errorBodyText = "retry unauthorized"),
                        AuthApiResult.Failure(code = 401, errorBodyText = "cross-boundary unauthorized"),
                        AuthApiResult.Failure(code = 401, errorBodyText = "post-expiry unauthorized"),
                    ),
                beforeResponse = { call ->
                    if (call == 3) now += 4_000L
                },
            )
        val coordinator =
            SharedAuthRefreshCoordinator(
                sessionStore = store,
                authApi = api,
                clockMillis = { now },
                criticalLogger = criticalLogs::add,
            )

        coordinator.refreshSession(
            failedAccessToken = "near-expiry-access",
            failedRefreshToken = "near-expiry-refresh",
        )
        now += 29L * 60L * 1_000L + 59_000L

        val crossingFlight =
            coordinator.refreshSession(
                failedAccessToken = "near-expiry-access",
                failedRefreshToken = "near-expiry-refresh",
            )

        assertEquals(3, api.refreshCalls)
        assertEquals(0, (crossingFlight as SharedAuthRefreshResult.Failure).response.code)
        assertEquals("refresh_prior_ambiguous_unauthorized", crossingFlight.response.contractCode)
        assertEquals("near-expiry-refresh", store.readSession()?.refreshToken)

        val postExpiryFlight =
            coordinator.refreshSession(
                failedAccessToken = "near-expiry-access",
                failedRefreshToken = "near-expiry-refresh",
            )

        assertEquals(4, api.refreshCalls)
        assertEquals(401, (postExpiryFlight as SharedAuthRefreshResult.Failure).response.code)
        assertNull(store.readSession())
        assertEquals(1, criticalLogs.count { it.contains("refresh_unauthorized_session_cleared") })
    }

    @Test
    fun sharesUnauthorizedResultWhileKeepingClearPolicyCallerSpecific() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val store =
            InMemorySlockAuthSessionStore(
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
            SlockNetworkAuthMiddleware(
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
            InMemorySlockAuthSessionStore(
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
            InMemorySlockAuthSessionStore(
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
            InMemorySlockAuthSessionStore(
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
            InMemorySlockAuthSessionStore(
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
            InMemorySlockAuthSessionStore(
                sampleSession(
                    accessToken = "middleware-preserve-access",
                    refreshToken = "middleware-preserve-refresh"
                )
            )
        val middleware =
            SlockNetworkAuthMiddleware(
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
    ) : SlockAuthApi {
        var refreshCalls: Int = 0
            private set

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

        override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
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
    ) : SlockAuthSessionStore {
        private val delegate = InMemorySlockAuthSessionStore(initialSession)
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
    ) : SlockAuthSessionStore {
        private val delegate = InMemorySlockAuthSessionStore(initialSession)
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
