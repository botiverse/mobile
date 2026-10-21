package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftNetworkAuthMiddlewareTest {
    @Test
    fun expectedAuthorityMismatchRejectsBeforeNetworkBlock() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(sampleSession(accessToken = "access-b", refreshToken = "refresh-b"))
        val middleware = RaftNetworkAuthMiddleware(store, FakeAuthApi(refreshResult = null))
        var calls = 0

        val response =
            middleware.request(
                serverIdOverride = ServerId("server-1"),
                expectedAuthority =
                    RaftExpectedAuthBinding(
                        accountId = "user-1",
                        accessToken = "access-a",
                        refreshToken = "refresh-a",
                        serverId = "server-1"
                    )
            ) {
                calls += 1
                HomeNetworkResponse(code = 200, bodyText = "{}")
            }

        assertEquals(409, response.code)
        assertEquals(0, calls)
        assertEquals(true, response.errorBodyText?.contains("auth_authority_changed"))
    }

    @Test
    fun expectedAuthorityRejectsChangedLiveSelectionEvenWhenOverrideMatchesOldRoute() = runBlocking {
        val original = sampleSession(accessToken = "access-a", refreshToken = "refresh-a")
        val store = InMemoryRaftAuthSessionStore(original.copy(selectedServerId = "server-2"))
        val middleware = RaftNetworkAuthMiddleware(store, FakeAuthApi(refreshResult = null))
        var calls = 0

        val response =
            middleware.request(
                serverIdOverride = ServerId("server-1"),
                expectedAuthority = original.expectedBinding(),
            ) {
                calls += 1
                HomeNetworkResponse(code = 200, bodyText = "{}")
            }

        assertEquals(409, response.code)
        assertEquals(0, calls)
        assertEquals(true, response.errorBodyText?.contains("auth_authority_changed"))
    }

    @Test
    fun expectedAuthorityNeverRetriesOldMutationAfterTokenRefresh() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(sampleSession(accessToken = "access-a", refreshToken = "refresh-a"))
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(
                    refreshTokenAssertion = "refresh-a",
                    refreshResult = AuthApiResult.Success(AuthRefreshResponse("access-a-prime", "refresh-a-prime"))
                )
            )
        var calls = 0

        val response =
            middleware.request(
                serverIdOverride = ServerId("server-1"),
                expectedAuthority =
                    RaftExpectedAuthBinding(
                        accountId = "user-1",
                        accessToken = "access-a",
                        refreshToken = "refresh-a",
                        serverId = "server-1"
                    )
            ) {
                calls += 1
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
            }

        assertEquals(409, response.code)
        assertEquals(1, calls)
        assertEquals("access-a-prime", store.readSession()?.accessToken)
        assertEquals("refresh-a-prime", store.readSession()?.refreshToken)
        assertEquals(true, response.errorBodyText?.contains("auth_authority_changed"))
    }

    @Test
    fun expectedAuthorityDropsSuccessfulResponseWhenAccountChangesDuringTransport() = runBlocking {
        val original = sampleSession(accessToken = "access-a", refreshToken = "refresh-a")
        val store = InMemoryRaftAuthSessionStore(original)
        val middleware = RaftNetworkAuthMiddleware(store, FakeAuthApi(refreshResult = null))

        val response =
            middleware.request(
                serverIdOverride = ServerId("server-1"),
                expectedAuthority = original.expectedBinding(),
            ) {
                store.saveSession(
                    original.copy(user = StoredAuthUser(id = "user-2", email = "other@raft.build"))
                )
                HomeNetworkResponse(code = 200, bodyText = "{}")
            }

        assertEquals(409, response.code)
        assertEquals(true, response.errorBodyText?.contains("auth_authority_changed"))
    }

    @Test
    fun expectedAuthorityDropsResponseWhenLiveSelectionChangesDuringTransport() = runBlocking {
        val original = sampleSession(accessToken = "access-a", refreshToken = "refresh-a")
        val store = InMemoryRaftAuthSessionStore(original)
        val middleware = RaftNetworkAuthMiddleware(store, FakeAuthApi(refreshResult = null))

        val response =
            middleware.request(
                serverIdOverride = ServerId("server-1"),
                expectedAuthority = original.expectedBinding(),
            ) {
                store.saveSession(original.copy(selectedServerId = "server-2"))
                HomeNetworkResponse(code = 200, bodyText = "{}")
            }

        assertEquals(409, response.code)
        assertEquals(true, response.errorBodyText?.contains("auth_authority_changed"))
    }

    @Test
    fun expectedAuthorityKeepsSuccessfulResponseWhenTokensRotateDuringTransport() = runBlocking {
        val original = sampleSession(accessToken = "access-a", refreshToken = "refresh-a")
        val store = InMemoryRaftAuthSessionStore(original)
        val middleware = RaftNetworkAuthMiddleware(store, FakeAuthApi(refreshResult = null))

        val response =
            middleware.request(
                serverIdOverride = ServerId("server-1"),
                expectedAuthority = original.expectedBinding(),
            ) {
                store.saveSession(
                    original.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh")
                )
                HomeNetworkResponse(code = 200, bodyText = "{}")
            }

        assertEquals(200, response.code)
        assertEquals("{}", response.bodyText)
    }

    @Test
    fun expectedAuthoritySkipsRefreshWhenAccountChangesDuringFailedTransport() = runBlocking {
        val original = sampleSession(accessToken = "access-a", refreshToken = "refresh-a")
        val store = InMemoryRaftAuthSessionStore(original)
        val middleware = RaftNetworkAuthMiddleware(store, FakeAuthApi(refreshResult = null))

        val response =
            middleware.request(
                serverIdOverride = ServerId("server-1"),
                expectedAuthority = original.expectedBinding(),
            ) {
                store.saveSession(
                    original.copy(user = StoredAuthUser(id = "user-2", email = "other@raft.build"))
                )
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
            }

        assertEquals(409, response.code)
        assertEquals(true, response.errorBodyText?.contains("auth_authority_changed"))
    }

    @Test
    fun retriesRequestWithRefreshedTokenAfterUnauthorized() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(sampleSession())
        val api = FakeAuthApi(refreshResult = AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh")))
        val sent = mutableListOf<RaftAuthenticatedRequestContext>()
        val middleware = RaftNetworkAuthMiddleware(store, api)

        val response =
            middleware.request(serverIdOverride = ServerId("server-2")) { auth ->
                sent += auth
                if (sent.size == 1) {
                    HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
                } else {
                    HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                }
            }

        assertEquals(200, response.code)
        assertEquals(2, sent.size)
        assertEquals("Bearer expired-access", sent[0].authorization)
        assertEquals("server-2", sent[0].serverId)
        assertEquals("Bearer fresh-access", sent[1].authorization)
        assertEquals("server-2", sent[1].serverId)
        assertEquals("fresh-access", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun reportsTerminalAuthLossWhenRefreshTokenIsUnauthorized() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(accessToken = "preserve-access", refreshToken = "preserve-refresh")
            )
        val reported = mutableListOf<Pair<String, String>>()
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(
                    refreshTokenAssertion = "preserve-refresh",
                    refreshResult =
                        AuthApiResult.Failure(
                            code = 401,
                            errorBodyText = """{"error":"expiredToken"}"""
                        )
                ),
                terminalAuthLossReporter = { session, caller ->
                    reported += session.accessToken to caller
                }
            )

        val response =
            middleware.request(caller = "test_terminal_auth_loss") {
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
            }

        assertEquals(401, response.code)
        assertEquals("preserve-access", store.readSession()?.accessToken)
        assertEquals("preserve-refresh", store.readSession()?.refreshToken)
        assertEquals(listOf("preserve-access" to "test_terminal_auth_loss"), reported)
    }

    @Test
    fun preservesTransientRefreshFailureInsteadOfReclassifyingItAsAuthLoss() = runBlocking {
        val originalSession = sampleSession()
        val store = InMemoryRaftAuthSessionStore(originalSession)
        val reported = mutableListOf<String>()
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(
                    refreshResult =
                        AuthApiResult.Failure(
                            code = 503,
                            errorBodyText = """{"error":"temporarily_unavailable"}"""
                        )
                ),
                terminalAuthLossReporter = { _, caller -> reported += caller }
            )

        val response =
            middleware.request(caller = "test_transient_refresh") {
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
            }

        assertEquals(503, response.code)
        assertEquals("""{"error":"temporarily_unavailable"}""", response.errorBodyText)
        assertEquals(originalSession.accessToken, store.readSession()?.accessToken)
        assertEquals(originalSession.refreshToken, store.readSession()?.refreshToken)
        assertEquals(emptyList(), reported)
    }

    @Test
    fun preservesRefreshTransportFailureInsteadOfReclassifyingItAsAuthLoss() = runBlocking {
        val originalSession = sampleSession()
        val store = InMemoryRaftAuthSessionStore(originalSession)
        val reported = mutableListOf<String>()
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(
                    refreshResult =
                        AuthApiResult.Failure(
                            code = 0,
                            errorBodyText = """{"error":"network_timeout"}""",
                        )
                ),
                terminalAuthLossReporter = { _, caller -> reported += caller },
            )

        val response =
            middleware.request(caller = "test_refresh_transport_failure") {
                HomeNetworkResponse(
                    code = 401,
                    bodyText = null,
                    errorBodyText = """{"error":"expiredToken"}""",
                )
            }

        assertEquals(0, response.code)
        assertEquals("""{"error":"network_timeout"}""", response.errorBodyText)
        assertEquals(originalSession.accessToken, store.readSession()?.accessToken)
        assertEquals(originalSession.refreshToken, store.readSession()?.refreshToken)
        assertEquals(emptyList(), reported)
    }

    @Test
    fun reportsTerminalAuthLossWhenRefreshCredentialIsMissing() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(accessToken = "missing-refresh-access", refreshToken = "")
            )
        val reported = mutableListOf<String>()
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(refreshResult = null),
                terminalAuthLossReporter = { _, caller -> reported += caller }
            )

        val response =
            middleware.request(caller = "test_missing_refresh") {
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
            }

        assertEquals(401, response.code)
        assertEquals(listOf("test_missing_refresh"), reported)
        assertEquals("missing-refresh-access", store.readSession()?.accessToken)
    }

    @Test
    fun reportsTerminalAuthLossWhenSessionBecomesUnavailableDuringRefresh() = runBlocking {
        val originalSession = sampleSession()
        val store = InMemoryRaftAuthSessionStore(originalSession)
        val reported = mutableListOf<Pair<String, String>>()
        var requestCalls = 0
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(
                    refreshResult = AuthApiResult.Success(AuthRefreshResponse("unused-access", "unused-refresh")),
                    beforeRefresh = {
                        store.clearSession(
                            reason = "concurrent_logout",
                            source = "test",
                        )
                    },
                ),
                terminalAuthLossReporter = { session, caller ->
                    reported += session.accessToken to caller
                },
            )

        val response =
            middleware.request(caller = "test_unavailable_refresh") {
                requestCalls += 1
                HomeNetworkResponse(
                    code = 401,
                    bodyText = null,
                    errorBodyText = """{"error":"expiredToken"}""",
                )
            }

        assertEquals(401, response.code)
        assertEquals(1, requestCalls)
        assertEquals(null, store.readSession())
        assertEquals(listOf(originalSession.accessToken to "test_unavailable_refresh"), reported)
    }

    @Test
    fun terminalAuthLossEntersFunnelAndClearsUnauthenticatedSession() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(accessToken = "funnel-access", refreshToken = "funnel-refresh")
            )
        val decisions = mutableListOf<AuthLossDecision>()
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = { SharedAuthResult.Unauthenticated },
                clearSessionIfMatches = { session, reason, source ->
                    store.clearSessionIfMatchesOutcome(
                        expectedAccountId = session.user.id,
                        expectedAccessToken = session.accessToken,
                        expectedRefreshToken = session.refreshToken,
                        reason = reason,
                        source = source,
                    )
                },
                scope = this
            )
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(
                    refreshTokenAssertion = "funnel-refresh",
                    refreshResult =
                        AuthApiResult.Failure(
                            code = 401,
                            errorBodyText = """{"error":"invalid refresh"}"""
                        )
                ),
                terminalAuthLossReporter = { session, caller ->
                    decisions +=
                        funnel.report(
                            AuthLossSignal(
                                source = caller,
                                tokenFingerprint = session.accessToken.authLossTokenFingerprint(),
                                capturedSession = session,
                            )
                        ).await()
                }
            )

        val response =
            middleware.request(caller = "test_funnel") {
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
            }

        assertEquals(401, response.code)
        assertEquals(null, store.readSession())
        assertEquals(
            listOf<AuthLossDecision>(AuthLossDecision.AuthRequired("restore_unauthenticated")),
            decisions
        )
    }

    @Test
    fun terminalAuthLossPreservesSessionRotatedBeforeFunnelResolution() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                sampleSession(accessToken = "old-rotated-access", refreshToken = "old-rotated-refresh")
            )
        val rotated = sampleSession(accessToken = "rotated-access", refreshToken = "rotated-refresh")
        val decisions = mutableListOf<AuthLossDecision>()
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = {
                    store.readSession()
                        ?.let { SharedAuthResult.Success(it) }
                        ?: SharedAuthResult.Unauthenticated
                },
                clearSessionIfMatches = { session, reason, source ->
                    store.clearSessionIfMatchesOutcome(
                        expectedAccountId = session.user.id,
                        expectedAccessToken = session.accessToken,
                        expectedRefreshToken = session.refreshToken,
                        reason = reason,
                        source = source,
                    )
                },
                scope = this
            )
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(
                    refreshTokenAssertion = "old-rotated-refresh",
                    refreshResult =
                        AuthApiResult.Failure(
                            code = 401,
                            errorBodyText = """{"error":"invalid refresh"}"""
                        )
                ),
                terminalAuthLossReporter = { session, caller ->
                    store.saveSession(rotated)
                    decisions +=
                        funnel.report(
                            AuthLossSignal(
                                source = caller,
                                tokenFingerprint = session.accessToken.authLossTokenFingerprint(),
                                capturedSession = session,
                            )
                        ).await()
                }
            )

        val response =
            middleware.request(caller = "test_rotated_session") {
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
            }

        assertEquals(401, response.code)
        assertEquals("rotated-access", store.readSession()?.accessToken)
        assertEquals(
            listOf<AuthLossDecision>(AuthLossDecision.SessionPreserved("restore_success")),
            decisions
        )
    }

    @Test
    fun retryStillUnauthorizedReportsRefreshedSessionToAuthLossFunnel() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(sampleSession())
        val reported = mutableListOf<Pair<String, String>>()
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(refreshResult = AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh"))),
                terminalAuthLossReporter = { session, caller ->
                    reported += session.accessToken to caller
                },
            )
        var calls = 0

        val response =
            middleware.request {
                calls += 1
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
            }

        assertEquals(401, response.code)
        assertEquals(2, calls)
        assertEquals("fresh-access", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
        assertEquals(listOf("fresh-access" to "shared_network"), reported)
    }

    @Test
    fun treatsExpiredTokenBodyAsRefreshableEvenWhenStatusIsNot401() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(sampleSession())
        val middleware =
            RaftNetworkAuthMiddleware(
                store,
                FakeAuthApi(refreshResult = AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh")))
            )
        var calls = 0

        val response =
            middleware.request { _ ->
                calls += 1
                if (calls == 1) {
                    HomeNetworkResponse(code = 400, bodyText = null, errorBodyText = """{"message":"jwt expired"}""")
                } else {
                    HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                }
            }

        assertEquals(200, response.code)
        assertEquals(2, calls)
        assertEquals("fresh-access", store.readSession()?.accessToken)
    }

    @Test
    fun retriesWithNewerSessionWhenAnotherCallerAlreadyRefreshed() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(sampleSession())
        val middleware = RaftNetworkAuthMiddleware(store, FakeAuthApi(refreshResult = null))
        val sent = mutableListOf<RaftAuthenticatedRequestContext>()

        val response =
            middleware.request { auth ->
                sent += auth
                if (sent.size == 1) {
                    store.saveSession(sampleSession(accessToken = "fresh-access", refreshToken = "fresh-refresh"))
                    HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
                } else {
                    HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                }
            }

        assertEquals(200, response.code)
        assertEquals("expired-access", sent[0].accessToken)
        assertEquals("fresh-access", sent[1].accessToken)
        assertEquals("fresh-access", store.readSession()?.accessToken)
    }

    private class FakeAuthApi(
        private val refreshResult: AuthApiResult<AuthRefreshResponse>?,
        private val refreshTokenAssertion: String = "refresh-token",
        private val beforeRefresh: suspend () -> Unit = {},
    ) : RaftAuthApi {
        override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun completeSocialLogin(
            provider: String,
            bodyText: String
        ): AuthApiResult<AuthSocialCompleteResponse> =
            error("not used")

        override suspend fun fetchSocialAuthProviders(platform: String): AuthApiResult<Set<String>> =
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

        override suspend fun resendVerification(authorization: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun verifyEmail(token: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
            error("not used")

        override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> {
            assertEquals(refreshTokenAssertion, refreshToken)
            beforeRefresh()
            return refreshResult ?: error("refresh should not be called")
        }

        override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
            error("not used")

        override suspend fun serverOrder(authorization: String): AuthApiResult<ServerSwitcherOrderPreference> =
            error("not used")

        override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
            error("not used")
    }

    private fun sampleSession(
        accessToken: String = "expired-access",
        refreshToken: String = "refresh-token"
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
            accessToken = accessToken,
            refreshToken = refreshToken,
            selectedServerId = "server-1",
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )

    private fun StoredAuthSession.expectedBinding(): RaftExpectedAuthBinding =
        RaftExpectedAuthBinding(
            accountId = user.id,
            accessToken = accessToken,
            refreshToken = refreshToken,
            serverId = requireNotNull(currentServerId),
        )
}
