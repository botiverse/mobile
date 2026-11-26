package build.raft.app.data

import build.raft.app.resources.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthDataSourceTest {
    private val qaConsumeResponse =
        AuthNetworkResponse(
            200,
            """{"user":{"id":"user-1","email":"user@slock.ai","displayName":"Artin"},"accessToken":"qa-access","refreshToken":"qa-refresh","bootstrap":{"ticketDigest":"sha256:ticket","runCaseId":"00000000-0000-4000-8000-000000000001","attempt":1,"ciJobRef":"gha:job:1","principalId":"user-1","serverId":"server-1","sessionGeneration":"00000000-0000-4000-8000-000000000004","targetRoute":"compose_slock_app_shell","consumedAt":"2026-07-21T15:00:00.000Z"}}"""
        )
    private val qaReadyBodyText =
        """{"receiptId":"receipt-1","receiptDigest":"sha256:receipt","receipt":{"version":"qa-bootstrap-ready/v1","ticketDigest":"sha256:ticket","runCaseId":"00000000-0000-4000-8000-000000000001","attempt":1,"ciJobRef":"gha:job:1","principalId":"user-1","serverId":"server-1","sessionGeneration":"00000000-0000-4000-8000-000000000004","targetRoute":"compose_slock_app_shell","applicationId":"build.raft.app.alpha","signingCertSha256":"sha256:cert","deviceBootGeneration":"sha256:boot","serverReadyVersion":1,"readyAtServer":"2026-07-21T15:00:00.000Z"}}"""
    private val qaReadyResponse = AuthNetworkResponse(200, qaReadyBodyText)

    @Test
    fun loginWritesStoredSessionAndFetchesServers() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.login(" user@slock.ai ", "password")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("""{"email":"user@slock.ai","password":"password"}""", api.loginBody)
        assertEquals("Bearer access-token", api.serversAuthorization)
        assertEquals("user-1", store.readSession()?.user?.id)
        assertEquals(false, store.readSession()?.user?.requiresProfileSetup)
        assertEquals("access-token", store.readSession()?.accessToken)
        assertEquals(null, store.readSession()?.selectedServerId)
        assertEquals(null, store.readSession()?.selectedServerSlug)
        assertEquals(1, store.readSession()?.servers?.size)
    }

    @Test
    fun loginPreservesExplicitPendingProfileNullFromServer() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        val api =
            FakeAuthApi(
                loginResponse =
                    AuthNetworkResponse(
                        200,
                        """{"user":{"id":"user-1","email":"user@slock.ai","emailVerified":true,"profileSetupCompletedAt":null,"profileSetupSuggestedHandle":"user"},"accessToken":"access-token","refreshToken":"refresh-token"}"""
                    )
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.login("user@slock.ai", "password")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals(true, store.readSession()?.user?.requiresProfileSetup)
        assertEquals("user", store.readSession()?.user?.profileSetupSuggestedHandle)
    }

    @Test
    fun qaBootstrapConsumePersistsCanonicalSessionAndBindingInOneReplace() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        val api = FakeAuthApi(qaConsumeResponse = qaConsumeResponse)
        val source = SharedAuthDataSource(transport = api, sessionStore = store)
        val request = QaBootstrapConsumeRequest("raw-ticket", "build.raft.app.alpha", "sha256:cert", "sha256:boot")

        val result = source.consumeQaBootstrap(request)

        assertTrue(result is SharedAuthResult.Success)
        val session = store.readSession()
        assertEquals("qa-access", session?.accessToken)
        assertEquals("server-1", session?.selectedServerId)
        assertFalse(session?.allowImplicitServerFallback ?: true)
        assertTrue(session?.qaBootstrapLaunchAuthorized == true)
        assertEquals("sha256:ticket", session?.qaBootstrap?.ticketDigest)
        assertEquals("build.raft.app.alpha", session?.qaBootstrap?.applicationId)
        assertEquals("sha256:cert", session?.qaBootstrap?.signingCertSha256)
        assertEquals("sha256:boot", session?.qaBootstrap?.deviceBootGeneration)
        assertContains(api.qaConsumeBody.orEmpty(), "raw-ticket")
    }

    @Test
    fun qaLoginBootstrapImportsExactBoundSessionWithoutServerBootstrapRoute() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession())
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)
        val digest = "sha256:${"a".repeat(64)}"
        val envelope =
            """{"version":"qa-bootstrap-login/v1","run_case_id":"00000000-0000-4000-8000-000000000001","attempt":2,"ci_job_ref":"https://github.com/botiverse/mobile/actions/runs/1","principal_id":"user-1","server_id":"server-1","target_route":"compose_slock_app_shell","application_id":"build.raft.app.alpha","signing_cert_sha256":"sha256:cert","boot_generation":"sha256:boot","user":{"id":"user-1","email":"","displayName":"Artin"},"access_token":"login-access","refresh_token":"login-refresh"}"""

        val result =
            source.consumeQaLoginBootstrap(
                envelopeJson = envelope,
                envelopeDigest = digest,
                applicationId = "build.raft.app.alpha",
                signingCertSha256 = "sha256:cert",
                deviceBootGeneration = "sha256:boot",
            )

        assertTrue(result is SharedAuthResult.Success)
        val session = requireNotNull(store.readSession())
        assertEquals("login-access", session.accessToken)
        assertEquals("login-refresh", session.refreshToken)
        assertEquals("", session.user.email)
        assertEquals("server-1", session.selectedServerId)
        assertEquals(QA_BOOTSTRAP_LOGIN_PROTOCOL, session.qaBootstrap?.protocol)
        assertEquals(digest, session.qaBootstrap?.ticketDigest)
        assertEquals(digest, session.qaBootstrap?.sessionGeneration)
        assertNull(api.qaConsumeBody)

        val ready = source.readyQaBootstrap()
        assertTrue(ready is SharedAuthResult.Success)
        val readySuccess = ready as SharedAuthResult.Success
        assertEquals("qa-bootstrap-login-ready/v1", readySuccess.data.receipt.version)
        assertEquals(0, readySuccess.data.receipt.serverReadyVersion)
        assertNull(api.qaReadyBody)
        assertEquals("ready", store.readSession()?.qaBootstrap?.state)
    }

    @Test
    fun qaLoginBootstrapBindingMismatchClearsPreviousSessionAndFailsClosed() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession())
        val envelope =
            """{"version":"qa-bootstrap-login/v1","run_case_id":"case-1","attempt":1,"ci_job_ref":"gha:job:1","principal_id":"user-1","server_id":"server-1","target_route":"compose_slock_app_shell","application_id":"build.raft.app.wrong","signing_cert_sha256":"sha256:cert","boot_generation":"sha256:boot","user":{"id":"user-1","email":"user@slock.ai"},"access_token":"login-access","refresh_token":"login-refresh"}"""

        val result =
            SharedAuthDataSource(transport = FakeAuthApi(), sessionStore = store).consumeQaLoginBootstrap(
                envelopeJson = envelope,
                envelopeDigest = "sha256:${"b".repeat(64)}",
                applicationId = "build.raft.app.alpha",
                signingCertSha256 = "sha256:cert",
                deviceBootGeneration = "sha256:boot",
            )

        assertTrue(result is SharedAuthResult.Failure)
        assertNull(store.readSession())
    }

    @Test
    fun qaBootstrapConsumeDoesNotExposeAnOrdinarySessionWhenAtomicWriteFails() = runBlocking {
        val result = SharedAuthDataSource(
            transport = FakeAuthApi(qaConsumeResponse = qaConsumeResponse),
            sessionStore = ThrowingSaveSessionStore()
        ).consumeQaBootstrap(QaBootstrapConsumeRequest("raw-ticket", "build.raft.app.alpha", "sha256:cert", "sha256:boot"))

        assertTrue(result is SharedAuthResult.Failure)
    }

    @Test
    fun qaBootstrapReadyStoresStableReceiptAndUsesFrozenSessionGeneration() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        val api = FakeAuthApi(qaConsumeResponse = qaConsumeResponse, qaReadyResponse = qaReadyResponse)
        val source = SharedAuthDataSource(transport = api, sessionStore = store)
        assertTrue(
            source.consumeQaBootstrap(QaBootstrapConsumeRequest("raw-ticket", "build.raft.app.alpha", "sha256:cert", "sha256:boot"))
                is SharedAuthResult.Success
        )

        val result = source.readyQaBootstrap()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("sha256:ticket", api.qaReadyTicketDigest)
        assertEquals("Bearer qa-access", api.qaReadyAuthorization)
        assertContains(api.qaReadyBody.orEmpty(), "00000000-0000-4000-8000-000000000004")
        assertEquals("ready", store.readSession()?.qaBootstrap?.state)
        assertEquals("sha256:receipt", store.readSession()?.qaBootstrap?.readyReceipt?.receiptDigest)
    }

    private fun frozenReadyReceiptMutations(body: String): List<Pair<String, String>> =
        listOf(
            "receiptId" to body.replace("\"receiptId\":\"receipt-1\"", "\"receiptId\":\"\""),
            "receiptDigest" to body.replace("\"receiptDigest\":\"sha256:receipt\"", "\"receiptDigest\":\"\""),
            "version" to body.replace("qa-bootstrap-ready/v1", "qa-bootstrap-ready/v2"),
            "ticketDigest" to body.replace("sha256:ticket", "sha256:wrong-ticket"),
            "runCaseId" to body.replace("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000099"),
            "attempt" to body.replace("\"attempt\":1", "\"attempt\":2"),
            "ciJobRef" to body.replace("gha:job:1", "gha:job:2"),
            "principalId" to body.replace("\"principalId\":\"user-1\"", "\"principalId\":\"user-2\""),
            "serverId" to body.replace("\"serverId\":\"server-1\"", "\"serverId\":\"server-2\""),
            "sessionGeneration" to body.replace("00000000-0000-4000-8000-000000000004", "00000000-0000-4000-8000-000000000005"),
            "targetRoute" to body.replace("compose_slock_app_shell", "wrong_route"),
            "applicationId" to body.replace("build.raft.app.alpha", "build.raft.app.wrong"),
            "signingCertSha256" to body.replace("sha256:cert", "sha256:wrong-cert"),
            "deviceBootGeneration" to body.replace("sha256:boot", "sha256:wrong-boot"),
            "serverReadyVersion" to body.replace("\"serverReadyVersion\":1", "\"serverReadyVersion\":2"),
            "readyAtServer" to body.replace("2026-07-21T15:00:00.000Z", "")
        )

    @Test
    fun loginReusesRememberedServerSelectionForSameAccount() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        store.saveRememberedServerSelection(
            StoredAuthUser(id = "user-1", email = "user@slock.ai"),
            StoredAuthServerSelection(serverId = "server-1", serverSlug = "slock")
        )
        val source = SharedAuthDataSource(transport = FakeAuthApi(), sessionStore = store)

        val result = source.login("user@slock.ai", "password")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("server-1", store.readSession()?.selectedServerId)
        assertEquals("slock", store.readSession()?.selectedServerSlug)
    }

    @Test
    fun loginDoesNotReuseRememberedServerSelectionForDifferentAccount() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        store.saveRememberedServerSelection(
            StoredAuthUser(id = "user-2", email = "other@slock.ai"),
            StoredAuthServerSelection(serverId = "server-1", serverSlug = "slock")
        )
        val source = SharedAuthDataSource(transport = FakeAuthApi(), sessionStore = store)

        val result = source.login("user@slock.ai", "password")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals(null, store.readSession()?.selectedServerId)
        assertEquals(null, store.readSession()?.selectedServerSlug)
    }

    @Test
    fun loginMapsHttpFailureWithoutWritingSession() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        val source =
            SharedAuthDataSource(
                transport = FakeAuthApi(loginResponse = AuthNetworkResponse(401, null, """{"error":"bad"}""")),
                sessionStore = store
            )

        val result = source.login("user@slock.ai", "wrong")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(SharedAuthErrorCode.InvalidCredentials, result.code)
        assertEquals(null, store.readSession())
    }

    @Test
    fun loginClearsStaleCachedSessionBeforeRequestFailure() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "old-user", email = "old@slock.ai"),
                    accessToken = "expired-access",
                    refreshToken = "expired-refresh",
                    selectedServerId = "server-old",
                    selectedServerSlug = "old",
                    servers = listOf(StoredAuthServer(id = "server-old", name = "Old", slug = "old"))
                )
            )
        val logs = mutableListOf<String>()
        val source =
            SharedAuthDataSource(
                transport = FakeAuthApi(loginResponse = AuthNetworkResponse(401, null, """{"error":"bad"}""")),
                sessionStore = store,
                diagnosticsLogger = logs::add
            )

        val result = source.login("user@slock.ai", "wrong")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(SharedAuthErrorCode.InvalidCredentials, result.code)
        assertEquals(null, store.readSession())
        assertTrue(logs.any { it.contains("auth_login_clear_cached_session_result result=success") })
    }

    @Test
    fun loginRejectsLegacyTokenAliasesAtTypedApiBoundary() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        loginResponse =
                            AuthNetworkResponse(
                                200,
                                """{"user":{"id":"user-1","email":"user@slock.ai"},"token":"legacy-access","refresh_token":"legacy-refresh"}"""
                            )
                    ),
                sessionStore = store
            )

        val result = source.login("user@slock.ai", "password")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(SharedAuthErrorCode.Unknown, result.code)
        assertEquals("auth_login_contract_mismatch", result.message)
        // The contract code is for logs; the user reads the sign-in failure.
        assertEquals(Res.string.auth_error_sign_in_failed.desc(), result.userMessage)
        assertEquals(null, store.readSession())
    }

    @Test
    fun loginMapsSessionPersistenceFailureToPostLoginStage() = runBlocking {
        val logs = mutableListOf<String>()
        val source =
            SharedAuthDataSource(
                transport = FakeAuthApi(),
                sessionStore = ThrowingSaveSessionStore(),
                diagnosticsLogger = logs::add
            )

        val result = source.login("user@slock.ai", "password")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(SharedAuthErrorCode.Unknown, result.code)
        assertTrue(result.message.contains("session_persist"))
        // The stage id stays in the log message; the user's sentence names no stage.
        assertEquals(Res.string.auth_error_sign_in_setup_failed.desc(), result.userMessage)
        assertTrue(logs.any { it.contains("auth_login_servers_result result=success stage=servers_parse serversCount=1") })
        assertTrue(logs.any { it.contains("auth_session_persist_result result=failure stage=session_persist") })
        assertTrue(logs.none { it.contains("Network unavailable") })
    }

    @Test
    fun restoreSessionReportsLocalReadFailureInsteadOfNoCachedSession() = runBlocking {
        val logs = mutableListOf<String>()
        val source =
            SharedAuthDataSource(
                transport = FakeAuthApi(),
                sessionStore =
                    ThrowingReadSessionStore(
                        IllegalStateException("SQLite error code=14 unable to open database file")
                    ),
                diagnosticsLogger = logs::add
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(SharedAuthErrorCode.Unknown, result.code)
        assertTrue(result.message.contains("Local storage setup failed."))
        assertEquals(Res.string.auth_error_restore_local_storage.desc(), result.userMessage)
        assertTrue(
            logs.any {
                it.contains("auth_restore_result result=failure stage=session_restore") &&
                    it.contains("sqliteCategory=path") &&
                    it.contains("sqliteCode=14") &&
                    it.contains("mapped=local_setup")
            }
        )
        assertTrue(logs.none { it.contains("reason=no_cached_session") })
    }

    @Test
    fun googleSocialCompleteWritesStoredSessionAndFetchesServers() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.completeSocialLogin("google", "social-code")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("""{"code":"social-code"}""", api.socialCompleteBody)
        assertEquals("google", api.socialCompleteProvider)
        assertEquals("Bearer social-access", api.currentUserAuthorization)
        assertEquals("Bearer social-access", api.serversAuthorization)
        assertEquals("user-1", store.readSession()?.user?.id)
        assertEquals("social-access", store.readSession()?.accessToken)
        assertEquals("social-refresh", store.readSession()?.refreshToken)
        assertEquals(null, store.readSession()?.selectedServerId)
    }

    @Test
    fun nativeAppleLinkStartAndAuthorizationRemainBoundToTheCurrentSession() = runBlocking {
        val store = InMemoryRaftAuthSessionStore().apply {
            saveSession(storedSession(accessToken = "current-access"))
        }
        val api =
            FakeAuthApi(
                nativeAppleAuthorizeResponse =
                    AuthNetworkResponse(
                        200,
                        """{"requestId":"native-request-1","handoffCode":"apple-link-handoff","provider":"apple","mode":"link"}"""
                    )
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val start =
            source.startNativeAppleMobileOAuthLink(
                appEnv = "staging",
                returnUri = "raft://oauth/callback",
                codeChallenge = "challenge-apple-link"
            )
        assertTrue(start is SharedAuthResult.Success)
        assertEquals("Bearer current-access", api.nativeAppleStartAuthorization)
        assertEquals(
            """{"mode":"link","platform":"ios","appEnv":"staging","returnUri":"raft://oauth/callback","codeChallenge":"challenge-apple-link","codeChallengeMethod":"S256"}""",
            api.nativeAppleStartBody
        )

        val authorize =
            source.authorizeNativeAppleMobileOAuthLink(
                requestId = "native-request-1",
                requestToken = "native-request-token",
                authorizationCode = "apple-link-code",
                identityToken = "apple-link-token"
            )
        assertTrue(authorize is SharedAuthResult.Success)
        assertEquals("link", authorize.data.mode)
        assertEquals("Bearer current-access", api.nativeAppleAuthorizeAuthorization)
    }

    @Test
    fun mobileOAuthLinkStartUsesProviderEndpointWithCurrentSession() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        store.saveSession(storedSession(accessToken = "current-access"))
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result =
            source.startMobileOAuthLink(
                provider = " Google ",
                returnUri = "raft://oauth/callback",
                codeChallenge = "challenge-1"
            )

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("google", api.mobileOAuthLinkStartProvider)
        assertEquals("Bearer current-access", api.mobileOAuthLinkStartAuthorization)
        assertEquals(
            """{"returnUri":"raft://oauth/callback","codeChallenge":"challenge-1","codeChallengeMethod":"S256"}""",
            api.mobileOAuthLinkStartBody
        )
    }

    /** slock 540981dd routes/auth.ts: the mobile link start is gated (`identity_link`, stepUpGate.ts). */
    @Test
    fun mobileOAuthLinkStartRefusedForAStepUpCarriesTheMethods() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        store.saveSession(storedSession(accessToken = "current-access"))
        val api =
            FakeAuthApi(
                mobileOAuthLinkStartResponse =
                    AuthNetworkResponse(
                        403,
                        null,
                        errorBodyText =
                            """{"error":"Confirm it's you with your second step before connecting a sign-in method.","code":"second_factor_step_up_required","methods":["totp","passkey","backup_code"]}"""
                    )
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.startMobileOAuthLink(provider = "github", returnUri = "raft://oauth/callback", codeChallenge = "challenge-1")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(listOf("totp", "passkey", "backup_code"), result.secondFactorStepUp?.methods)
        assertEquals("Confirm it's you with your second step before connecting a sign-in method.", result.message)
    }

    @Test
    fun mobileOAuthLinkCompleteUsesProviderEndpointWithCurrentSession() = runBlocking {
        val store = InMemoryRaftAuthSessionStore()
        store.saveSession(storedSession(accessToken = "current-access"))
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result =
            source.completeMobileOAuthLink(
                provider = "GitHub",
                code = "handoff-1",
                codeVerifier = "verifier-1"
            )

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("github", result.data.provider)
        assertEquals("link", result.data.mode)
        assertEquals(listOf(AuthMobileOAuthIdentity("github", "user@example.com")), result.data.identities)
        assertEquals("github", api.mobileOAuthLinkCompleteProvider)
        assertEquals("Bearer current-access", api.mobileOAuthLinkCompleteAuthorization)
        assertEquals(
            """{"code":"handoff-1","codeVerifier":"verifier-1"}""",
            api.mobileOAuthLinkCompleteBody
        )
    }

    @Test
    fun completeProfilePersistsCanonicalUserAndUsesBearer() = runBlocking {
        val pending = storedSession().copy(
            user = StoredAuthUser(
                id = "user-1",
                email = "user@slock.ai",
                profileSetupCompletedAt = null,
                profileSetupSuggestedHandle = "user"
            )
        )
        val store = InMemoryRaftAuthSessionStore(pending)
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.completeProfile(name = " artin ", displayName = " Artin Chen ")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("Bearer access-token", api.completeProfileAuthorization)
        assertEquals("""{"name":"artin","displayName":"Artin Chen"}""", api.completeProfileBody)
        assertEquals("artin", store.readSession()?.user?.name)
        assertEquals(false, store.readSession()?.user?.requiresProfileSetup)
    }

    @Test
    fun completeProfilePreservesConcurrentTokenRotationAndServerSelection() = runBlocking {
        val pending = storedSession().copy(
            user = StoredAuthUser(
                id = "user-1",
                email = "user@slock.ai",
                profileSetupCompletedAt = null,
                profileSetupSuggestedHandle = "user"
            )
        )
        val store = InMemoryRaftAuthSessionStore(pending)
        val api = FakeAuthApi(
            onCompleteProfileRequest = {
                assertTrue(
                    store.rotateTokensIfMatch(
                        expectedAccessToken = "access-token",
                        expectedRefreshToken = "refresh-token",
                        newAccessToken = "rotated-access",
                        newRefreshToken = "rotated-refresh"
                    ) != null
                )
                store.updateMetadata(expectedAccountId = "user-1") { latest ->
                    latest.copy(
                        selectedServerId = "server-2",
                        selectedServerSlug = "ops",
                        servers = latest.servers + StoredAuthServer(id = "server-2", name = "Ops", slug = "ops")
                    )
                }
            }
        )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.completeProfile(name = "artin", displayName = "Artin Chen")

        assertTrue(result is SharedAuthResult.Success)
        val persisted = store.readSession()
        assertEquals("rotated-access", persisted?.accessToken)
        assertEquals("rotated-refresh", persisted?.refreshToken)
        assertEquals("server-2", persisted?.selectedServerId)
        assertEquals("ops", persisted?.selectedServerSlug)
        assertEquals(listOf("server-1", "server-2"), persisted?.servers?.map { it.id })
        assertEquals("artin", persisted?.user?.name)
        assertEquals(persisted, (result as SharedAuthResult.Success).data)
    }

    @Test
    fun completeProfileDoesNotRestoreCapturedAccountAfterConcurrentAccountSwitch() = runBlocking {
        val pending = storedSession().copy(
            user = StoredAuthUser(id = "user-1", email = "user@slock.ai", profileSetupCompletedAt = null)
        )
        val switched = storedSession(accessToken = "account-2-access").copy(
            user = StoredAuthUser(id = "user-2", email = "other@slock.ai"),
            refreshToken = "account-2-refresh"
        )
        val store = InMemoryRaftAuthSessionStore(pending)
        val api = FakeAuthApi(onCompleteProfileRequest = { store.replaceAuthenticatedSession(switched) })
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.completeProfile(name = "artin", displayName = "Artin Chen")

        assertEquals(SharedAuthResult.Unauthenticated, result)
        assertEquals(switched, store.readSession())
    }

    @Test
    fun resendVerificationSendsBearerFromStoredSession() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession(accessToken = "verify-token"))
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.resendVerification()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("Bearer verify-token", api.resendVerificationAuthorization)
    }

    @Test
    fun resendVerificationSurfacesRateLimitMessage() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession())
        val api =
            FakeAuthApi(
                resendVerificationResponse =
                    AuthNetworkResponse(429, null, """{"error":"Too many requests. Please wait."}""")
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.resendVerification()

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals("Too many requests. Please wait.", result.message)
    }

    @Test
    fun verifyEmailConsumesExactTokenThenRefreshesAndPersistsCanonicalUser() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                storedSession().copy(
                    user = StoredAuthUser(
                        id = "user-1",
                        email = "user@slock.ai",
                        emailVerified = false
                    )
                )
            )
        val api =
            FakeAuthApi(
                currentUserResponses =
                    ArrayDeque(
                        listOf(
                            AuthNetworkResponse(
                                200,
                                """{"id":"user-1","email":"user@slock.ai","emailVerified":true}"""
                            )
                        )
                    )
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.verifyEmail("email-token-123")

        assertTrue(result is SharedEmailVerificationResult.Verified)
        assertEquals("""{"token":"email-token-123"}""", api.verifyEmailBody)
        assertEquals("Bearer access-token", api.currentUserAuthorization)
        assertEquals(true, store.readSession()?.user?.emailVerified)
        assertEquals("access-token", store.readSession()?.accessToken)
        assertEquals("refresh-token", store.readSession()?.refreshToken)
    }

    @Test
    fun verifyEmailFailureDoesNotRefreshOrFabricateVerifiedSessionState() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                storedSession().copy(
                    user = StoredAuthUser(
                        id = "user-1",
                        email = "user@slock.ai",
                        emailVerified = false
                    )
                )
            )
        val api =
            FakeAuthApi(
                verifyEmailResponse =
                    AuthNetworkResponse(400, null, """{"error":"Invalid or expired verification token"}""")
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.verifyEmail("expired-token")

        assertTrue(result is SharedEmailVerificationResult.Failure)
        assertEquals("Invalid or expired verification token", result.message)
        assertEquals("""{"token":"expired-token"}""", api.verifyEmailBody)
        assertEquals(null, api.currentUserAuthorization)
        assertEquals(false, store.readSession()?.user?.emailVerified)
    }

    @Test
    fun verifyEmailServerFailureCanRetryCanonicalRefreshWithoutReplayingToken() = runBlocking {
        val store = unverifiedSessionStore()
        val currentUserResponses =
            ArrayDeque(
                listOf(
                    AuthNetworkResponse(503, null, """{"error":"temporarily unavailable"}"""),
                    AuthNetworkResponse(
                        200,
                        """{"id":"user-1","email":"user@slock.ai","emailVerified":true}"""
                    )
                )
            )
        val api = FakeAuthApi(currentUserResponses = currentUserResponses)
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val initial = source.verifyEmail("one-time-token")
        assertTrue(initial is SharedEmailVerificationResult.RefreshPending)
        assertEquals(false, store.readSession()?.user?.emailVerified)

        val retried = source.refreshEmailVerificationStatus()

        assertTrue(retried is SharedEmailVerificationResult.Verified)
        assertEquals(1, api.verifyEmailRequestCount)
        assertEquals(2, api.currentUserRequestCount)
        assertEquals(true, store.readSession()?.user?.emailVerified)
    }


    @Test
    fun restoreSessionRefreshesUnauthorizedCurrentUserInSharedLayer() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "expired-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-1",
                    selectedServerSlug = "slock",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
                )
            )
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        currentUserResponses =
                            ArrayDeque(
                                listOf(
                                    AuthNetworkResponse(401, null),
                                    AuthNetworkResponse(200, """{"id":"user-1","email":"user@slock.ai","displayName":"Artin"}""")
                                )
                            ),
                        refreshResponse = AuthNetworkResponse(200, """{"accessToken":"fresh-token","refreshToken":"fresh-refresh"}""")
                    ),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("fresh-token", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun restoreSessionDoesNotWaitForASlowServerListPastItsBudget() = runBlocking {
        // Hands 79db9fe6: /servers answered nothing for 30 s and the whole restore waited for it.
        val store = InMemoryRaftAuthSessionStore(storedSession())
        val logs = mutableListOf<String>()
        val source =
            SharedAuthDataSource(
                transport = FakeAuthApi(onServersRequest = { kotlinx.coroutines.awaitCancellation() }),
                sessionStore = store,
                diagnosticsLogger = { logs += it }
            ).apply { restoreServerListBudgetMillis = 50 }

        val result = kotlinx.coroutines.withTimeout(5_000) { source.restoreSession() }

        assertTrue(result is SharedAuthResult.Success, "the confirmed user is enough to finish the restore")
        assertEquals(listOf("server-1"), result.data.servers.map { it.id }, "the cached server list stands")
        assertEquals("server-1", result.data.currentServerId)
        assertTrue(logs.any { "auth_restore_server_list result=budget_exceeded" in it })
    }

    @Test
    fun restoreSessionKeepsCachedSessionWhenCurrentUserThrows() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession())
        val source =
            SharedAuthDataSource(
                transport = FakeAuthApi(throwCurrentUser = true),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("access-token", result.data.accessToken)
        assertEquals("server-1", result.data.currentServerId)
        assertEquals("access-token", store.readSession()?.accessToken)
    }

    @Test
    fun restoreSessionKeepsCachedSessionWhenRefreshServerUnavailable() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession(accessToken = "expired-token"))
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        currentUserResponses =
                            ArrayDeque(
                                listOf(
                                    AuthNetworkResponse(401, null)
                                )
                            ),
                        refreshResponse = AuthNetworkResponse(503, null, """{"message":"temporary outage"}""")
                    ),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("expired-token", result.data.accessToken)
        assertEquals("server-1", result.data.currentServerId)
        assertEquals("expired-token", store.readSession()?.accessToken)
    }

    @Test
    fun restoreSessionKeepsSessionAndReturnsFailureWhenCurrentUserReturns403() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession(accessToken = "valid-access", refreshToken = "valid-refresh"))
        var refreshCalls = 0
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        currentUserResponses =
                            ArrayDeque(
                                listOf(
                                    AuthNetworkResponse(
                                        403,
                                        null,
                                        """{"error":"Your organization paused access for this account.","code":"account_restricted"}"""
                                    )
                                )
                            ),
                        onRefreshRequest = { refreshCalls += 1 }
                    ),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals("Your organization paused access for this account.".desc(), result.userMessage)
        assertEquals(0, refreshCalls)
        assertEquals("valid-access", store.readSession()?.accessToken)
        assertEquals("valid-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun restoreSessionKeepsSessionWhenRefreshReturns403After401() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession(accessToken = "expired-token", refreshToken = "valid-refresh"))
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        currentUserResponses =
                            ArrayDeque(
                                listOf(
                                    AuthNetworkResponse(401, null, """{"error":"jwt expired"}""")
                                )
                            ),
                        refreshResponse =
                            AuthNetworkResponse(
                                403,
                                null,
                                """{"error":"Account deletion is scheduled.","code":"account_deletion_pending"}"""
                            )
                    ),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals("Account deletion is scheduled.".desc(), result.userMessage)
        assertEquals("expired-token", store.readSession()?.accessToken)
        assertEquals("valid-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun restoreSessionKeepsRefreshedSessionWhenPostRefreshCurrentUserReturns403() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession(accessToken = "expired-token", refreshToken = "old-refresh"))
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        currentUserResponses =
                            ArrayDeque(
                                listOf(
                                    AuthNetworkResponse(401, null, """{"error":"jwt expired"}"""),
                                    AuthNetworkResponse(
                                        403,
                                        null,
                                        """{"error":"Sign in with SSO to continue.","code":"SSO_REQUIRED"}"""
                                    )
                                )
                            ),
                        refreshResponse =
                            AuthNetworkResponse(
                                200,
                                """{"accessToken":"fresh-access","refreshToken":"fresh-refresh"}"""
                            )
                    ),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals("Sign in with SSO to continue.".desc(), result.userMessage)
        assertEquals("fresh-access", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun restoreSessionClearsSessionWhenRefreshReturns401() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession(accessToken = "expired-token", refreshToken = "dead-refresh"))
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        currentUserResponses =
                            ArrayDeque(
                                listOf(
                                    AuthNetworkResponse(401, null, """{"error":"jwt expired"}""")
                                )
                            ),
                        refreshResponse =
                            AuthNetworkResponse(
                                401,
                                null,
                                """{"error":"Session revoked","code":"future_unknown_401_code"}"""
                            )
                    ),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Unauthenticated)
        assertNull(store.readSession())
    }

    @Test
    fun realtimeAuthRefreshUpdatesStoredTokensWithoutChangingServerSelection() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession(accessToken = "stale-token"))
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        refreshResponse = AuthNetworkResponse(200, """{"accessToken":"fresh-token","refreshToken":"fresh-refresh"}""")
                    ),
                sessionStore = store
            )

        val result = source.refreshRealtimeAuth("connect_error")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("fresh-token", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
        assertEquals("server-1", store.readSession()?.selectedServerId)
    }

    @Test
    fun realtimeAuthRefreshPreservesCachedSessionOnUnauthorized() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(storedSession(accessToken = "stale-token"))
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        refreshResponse = AuthNetworkResponse(401, null, """{"error":"expired"}""")
                    ),
                sessionStore = store
            )

        val result = source.refreshRealtimeAuth("connect_error")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals("stale-token", store.readSession()?.accessToken)
        assertEquals("refresh-token", store.readSession()?.refreshToken)
    }

    @Test
    fun selectServerPersistsSelectionInSharedLayer() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    servers =
                        listOf(
                            StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"),
                            StoredAuthServer(id = "server-2", name = "Ops", slug = "ops")
                        )
                )
            )
        val source = SharedAuthDataSource(transport = FakeAuthApi(), sessionStore = store)

        val result = source.selectServer("server-2")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("server-2", store.readSession()?.selectedServerId)
        assertEquals("ops", store.readSession()?.selectedServerSlug)
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "ops"),
            store.readRememberedServerSelection(StoredAuthUser(id = "user-1", email = "user@slock.ai"))
        )
    }

    @Test
    fun logoutKeepsRememberedServerSelectionForNextLogin() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-1",
                    selectedServerSlug = "slock",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
                )
            )
        val source = SharedAuthDataSource(transport = FakeAuthApi(), sessionStore = store)

        val loggedOut = source.logout()
        val loggedIn = source.login("user@slock.ai", "password")

        assertTrue(loggedOut is SharedAuthResult.Success)
        assertTrue(loggedIn is SharedAuthResult.Success)
        assertEquals("server-1", store.readSession()?.selectedServerId)
        assertEquals("slock", store.readSession()?.selectedServerSlug)
    }

    @Test
    fun logoutUnbindsPushInstallationBeforeInvalidatingServerSession() = runBlocking {
        val events = mutableListOf<String>()
        val store = InMemoryRaftAuthSessionStore(storedSession())
        val api = FakeAuthApi(onLogoutRequest = { events += "auth_logout" })
        RaftPushRegistrationLifecycle.install(
            object : RaftPushRegistrationLifecycleSink {
                override fun onSessionSaved() = Unit
                override fun onSessionInvalidated(reason: AuthSessionClearReason) = Unit

                override suspend fun unbindBeforeLogout() {
                    assertTrue(store.readSession() != null)
                    events += "push_unbind"
                }
            }
        )

        try {
            val result = SharedAuthDataSource(transport = api, sessionStore = store).logout()

            assertTrue(result is SharedAuthResult.Success)
            assertEquals(listOf("push_unbind", "auth_logout"), events)
            assertNull(store.readSession())
        } finally {
            RaftPushRegistrationLifecycle.clearForTests()
        }
    }

    @Test
    fun logoutClearsPersistentWebViewAuthStorage() = runBlocking {
        var clearedCount = 0
        val store = InMemoryRaftAuthSessionStore(storedSession())
        val result =
            SharedAuthDataSource(
                transport = FakeAuthApi(),
                sessionStore = store,
                clearWebViewAuthStorage = { clearedCount += 1 },
            ).logout()
        assertTrue(result is SharedAuthResult.Success)
        assertEquals(1, clearedCount)
    }

    @Test
    fun switchingAccountClearsWebViewAuthStorageBeforePersistingNewSession() = runBlocking {
        val events = mutableListOf<String>()
        val previousSession = storedSession()
        val delegate = InMemoryRaftAuthSessionStore(previousSession)
        val recordingStore =
            object : RaftAuthSessionStore by delegate {
                override suspend fun replaceAuthenticatedSessionIfCurrent(
                    session: StoredAuthSession,
                    isCurrent: () -> Boolean,
                ): Boolean {
                    events += "persist:${session.user.id}"
                    return delegate.replaceAuthenticatedSessionIfCurrent(session, isCurrent)
                }
            }
        val otherUserTransport =
            FakeAuthApi(
                loginResponse =
                    AuthNetworkResponse(
                        200,
                        """{"accessToken":"token-2","refreshToken":"refresh-2","user":{"id":"user-2","email":"other@slock.ai","displayName":"Other"},"servers":[]}""",
                    ),
            )
        val source =
            SharedAuthDataSource(
                transport = otherUserTransport,
                sessionStore = recordingStore,
                clearWebViewAuthStorage = { events += "clear_webview" },
            )
        // completeAppLogin transitions directly from user-1 to user-2 without pre-clearing sessionStore,
        // exercising replaceAuthenticatedSessionAndRetireChangedAccount's accountChanged branch.
        val staleResult = source.completeAppLogin("code-1", "verifier-1", isCurrent = { false })
        assertTrue(staleResult is SharedAuthResult.Unauthenticated)
        assertEquals(emptyList(), events, "superseded login must not clear WebView storage")

        val result = source.completeAppLogin("code-2", "verifier-2", isCurrent = { true })
        assertTrue(result is SharedAuthResult.Success)
        assertEquals(listOf("clear_webview", "persist:user-2"), events)
    }

    @Test
    fun loadServersClearsStaleSelectedServerInSharedLayer() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-old",
                    selectedServerSlug = "old",
                    servers = listOf(StoredAuthServer(id = "server-old", name = "Old", slug = "old"))
                )
            )
        val source = SharedAuthDataSource(transport = FakeAuthApi(), sessionStore = store)

        val result = source.loadServers()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals(null, store.readSession()?.selectedServerId)
        assertEquals(null, store.readSession()?.selectedServerSlug)
        assertEquals(listOf("server-1"), store.readSession()?.servers?.map { it.id })
    }

    @Test
    fun loadServersAppliesServerSwitcherOrderPreference() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-1",
                    selectedServerSlug = "slock",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
                )
            )
        val api =
            FakeAuthApi(
                serversResponse =
                    AuthNetworkResponse(
                        200,
                        """[{"id":"server-1","name":"Slock","slug":"slock"},{"id":"server-2","name":"Ops","slug":"ops"},{"id":"server-3","name":"Lab","slug":"lab"}]"""
                    ),
                serverOrderResponse = AuthNetworkResponse(200, """{"serverOrder":["server-3","server-1"]}""")
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.loadServers()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals(listOf("server-3", "server-1", "server-2"), store.readSession()?.servers?.map { it.id })
        assertEquals("server-1", store.readSession()?.selectedServerId)
        assertEquals(listOf("Bearer access-token"), api.serverOrderAuthorizations)
    }

    @Test
    fun loadServersRefreshesUnauthorizedAccessTokenBeforeSigningOut() = runBlocking {
        val logs = mutableListOf<String>()
        val criticalLogs = mutableListOf<String>()
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "expired-token",
                    refreshToken = "refresh-token",
                    servers = listOf(StoredAuthServer(id = "server-old", name = "Old", slug = "old"))
                )
            )
        val api =
            FakeAuthApi(
                serversResponses =
                    ArrayDeque(
                        listOf(
                            AuthNetworkResponse(401, null, """{"error":"expired"}"""),
                            AuthNetworkResponse(200, """[{"id":"server-2","name":"Ops","slug":"ops"}]""")
                        )
                    ),
                refreshResponse = AuthNetworkResponse(200, """{"accessToken":"fresh-token","refreshToken":"fresh-refresh"}""")
            )
        val source =
            SharedAuthDataSource(
                transport = api,
                sessionStore = store,
                diagnosticsLogger = logs::add,
                criticalDiagnosticsLogger = criticalLogs::add
            )

        val result = source.loadServers()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("fresh-token", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
        assertEquals(listOf("server-2"), store.readSession()?.servers?.map { it.id })
        assertEquals(listOf("Bearer expired-token", "Bearer fresh-token"), api.serversAuthorizations)
        assertContains(criticalLogs, "auth_servers_load_result result=unauthorized source=access_token action=refresh")
        assertFalse(logs.contains("auth_servers_load_result result=unauthorized source=access_token action=refresh"))
        assertTrue(
            logs.any {
                it.startsWith("auth_servers_load_result result=success source=refresh_retry serversCount=1 selectedServer=false")
            }
        )
    }

    @Test
    fun loadServersRetryUnauthorizedDoesNotClearConcurrentSuccessorSession() = runBlocking {
        val original = storedSession(accessToken = "expired-access").copy(refreshToken = "expired-refresh")
        val successor = storedSession(accessToken = "successor-access").copy(refreshToken = "successor-refresh")
        val store = InMemoryRaftAuthSessionStore(original)
        var serverRequests = 0
        val api =
            FakeAuthApi(
                serversResponses =
                    ArrayDeque(
                        listOf(
                            AuthNetworkResponse(401, null, """{"error":"expired"}"""),
                            AuthNetworkResponse(401, null, """{"error":"unauthorized"}"""),
                        )
                    ),
                refreshResponse =
                    AuthNetworkResponse(
                        200,
                        """{"accessToken":"fresh-access","refreshToken":"fresh-refresh"}""",
                    ),
                onServersRequest = {
                    serverRequests += 1
                    if (serverRequests == 2) store.saveSession(successor)
                },
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.loadServers()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("successor-access", result.data.accessToken)
        assertEquals("successor-refresh", result.data.refreshToken)
        assertEquals("successor-access", store.readSession()?.accessToken)
        assertEquals(listOf("Bearer expired-access", "Bearer fresh-access"), api.serversAuthorizations)
    }

    @Test
    fun loadServersDropsResponseWhenSessionWasClearedDuringRequest() = runBlocking {
        val logs = mutableListOf<String>()
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "old-token",
                    refreshToken = "old-refresh",
                    servers = listOf(StoredAuthServer(id = "server-old", name = "Old", slug = "old"))
                )
            )
        val api =
            FakeAuthApi(
                serversResponse = AuthNetworkResponse(200, """[{"id":"server-new","name":"New","slug":"new"}]"""),
                onServersRequest = { store.clearSession() }
            )
        val source =
            SharedAuthDataSource(
                transport = api,
                sessionStore = store,
                diagnosticsLogger = logs::add
            )

        val result = source.loadServers()

        assertEquals(SharedAuthResult.Unauthenticated, result)
        assertNull(store.readSession())
        assertEquals(listOf("Bearer old-token"), api.serversAuthorizations)
        assertContains(logs, "auth_servers_load_result result=stale source=access_token action=drop")
    }

    @Test
    fun createServerSelectsCreatedServerInSharedLayer() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
                )
            )
        val api =
            FakeAuthApi(
                createServerResponse = AuthNetworkResponse(201, """{"id":"server-2","name":"Ops","slug":"ops"}"""),
                serversResponse = AuthNetworkResponse(200, """[{"id":"server-1","name":"Slock","slug":"slock"},{"id":"server-2","name":"Ops","slug":"ops"}]""")
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.createServer(" Ops ")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("""{"name":"Ops"}""", api.createServerBody)
        assertEquals("server-2", store.readSession()?.selectedServerId)
        assertEquals(2, store.readSession()?.servers?.size)
    }

    @Test
    fun createServerRefreshesUnauthorizedAccessBeforeRetryingOnce() = runBlocking {
        val store =
            InMemoryRaftAuthSessionStore(
                storedSession(accessToken = "expired-access").copy(refreshToken = "refresh-token")
            )
        val api =
            FakeAuthApi(
                createServerResponses =
                    ArrayDeque(
                        listOf(
                            AuthNetworkResponse(401, null, """{"error":"expired"}"""),
                            AuthNetworkResponse(201, """{"id":"server-2","name":"Ops","slug":"ops"}"""),
                        )
                    ),
                refreshResponse =
                    AuthNetworkResponse(
                        200,
                        """{"accessToken":"fresh-access","refreshToken":"fresh-refresh"}""",
                    ),
                serversResponse =
                    AuthNetworkResponse(
                        200,
                        """[{"id":"server-1","name":"Slock","slug":"slock"},{"id":"server-2","name":"Ops","slug":"ops"}]""",
                    ),
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.createServer("Ops")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals(listOf("Bearer expired-access", "Bearer fresh-access"), api.createServerAuthorizations)
        assertEquals("fresh-access", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
        assertEquals("server-2", store.readSession()?.selectedServerId)
    }

    @Test
    fun createServerDoesNotReplayWhenRefreshTransportFails() = runBlocking {
        val original = storedSession(accessToken = "expired-access").copy(refreshToken = "refresh-token")
        val store = InMemoryRaftAuthSessionStore(original)
        val api =
            FakeAuthApi(
                createServerResponse =
                    AuthNetworkResponse(401, null, """{"error":"expired"}"""),
                refreshResponse =
                    AuthNetworkResponse(0, null, """{"error":"network_timeout"}"""),
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.createServer("Ops")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(SharedAuthErrorCode.Network, result.code)
        assertEquals(listOf("Bearer expired-access"), api.createServerAuthorizations)
        assertEquals(original.accessToken, store.readSession()?.accessToken)
        assertEquals(original.refreshToken, store.readSession()?.refreshToken)
    }

    @Test
    fun createServerDoesNotReplayAcrossConcurrentAccountSwitch() = runBlocking {
        val original = storedSession(accessToken = "expired-access").copy(refreshToken = "expired-refresh")
        val successor =
            storedSession(accessToken = "successor-access")
                .copy(
                    user = StoredAuthUser(id = "user-2", email = "user2@slock.ai"),
                    refreshToken = "successor-refresh",
                )
        val store = InMemoryRaftAuthSessionStore(original)
        val api =
            FakeAuthApi(
                createServerResponse =
                    AuthNetworkResponse(401, null, """{"error":"expired"}"""),
                refreshResponse =
                    AuthNetworkResponse(
                        200,
                        """{"accessToken":"unused-access","refreshToken":"unused-refresh"}""",
                    ),
                onRefreshRequest = { store.saveSession(successor) },
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.createServer("Ops")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals("auth_authority_changed", result.message)
        assertEquals(listOf("Bearer expired-access"), api.createServerAuthorizations)
        assertEquals("user-2", store.readSession()?.user?.id)
        assertEquals("successor-access", store.readSession()?.accessToken)
    }

    @Test
    fun createServerRetryUnauthorizedDoesNotReportSuccessAfterAccountSwitch() = runBlocking {
        val original = storedSession(accessToken = "expired-access").copy(refreshToken = "expired-refresh")
        val successor =
            original.copy(
                user = StoredAuthUser(id = "user-2", email = "user2@slock.ai"),
                accessToken = "successor-access",
                refreshToken = "successor-refresh",
            )
        val store = InMemoryRaftAuthSessionStore(original)
        val api =
            FakeAuthApi(
                createServerResponses =
                    ArrayDeque(
                        listOf(
                            AuthNetworkResponse(401, null, """{"error":"expired"}"""),
                            AuthNetworkResponse(401, null, """{"error":"unauthorized"}"""),
                        )
                    ),
                refreshResponse =
                    AuthNetworkResponse(
                        200,
                        """{"accessToken":"fresh-access","refreshToken":"fresh-refresh"}""",
                    ),
                onCreateServerRequest = { call ->
                    if (call == 2) store.saveSession(successor)
                },
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.createServer("Ops")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals("auth_authority_changed", result.message)
        assertEquals(listOf("Bearer expired-access", "Bearer fresh-access"), api.createServerAuthorizations)
        assertEquals(successor, store.readSession())
    }

    private fun storedSession(
        accessToken: String = "access-token",
        refreshToken: String = "refresh-token",
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
            accessToken = accessToken,
            refreshToken = refreshToken,
            selectedServerId = "server-1",
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )

    private fun unverifiedSessionStore(): InMemoryRaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(
            storedSession().copy(
                user = StoredAuthUser(
                    id = "user-1",
                    email = "user@slock.ai",
                    emailVerified = false
                )
            )
        )

    internal class FakeAuthApi(
        private val loginResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """
                {"user":{"id":"user-1","email":"user@slock.ai","displayName":"Artin"},"accessToken":"access-token","refreshToken":"refresh-token"}
                """.trimIndent()
            ),
        private val socialCompleteResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """{"provider":"google","mode":"login","accessToken":"social-access","refreshToken":"social-refresh"}"""
            ),
        private val nativeAppleStartResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                201,
                """{"requestId":"native-request-1","requestToken":"native-request-token","returnUri":"raft://oauth/callback","expiresAt":"2026-06-29T00:00:00Z"}"""
            ),
        private val nativeAppleAuthorizeResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """{"requestId":"native-request-1","handoffCode":"apple-handoff","provider":"apple","mode":"login"}"""
            ),
        private val mobileOAuthLinkStartResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """{"authorizationUrl":"https://accounts.example/link","requestId":"link-request-1","returnUri":"raft://oauth/callback","expiresAt":"2026-06-29T00:00:00Z"}"""
            ),
        private val mobileOAuthLinkCompleteResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """{"provider":"github","mode":"link","returnUri":"raft://oauth/callback","identities":[{"provider":"github","providerEmail":"user@example.com"}]}"""
            ),
        private val serversResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """[{"id":"server-1","name":"Slock","slug":"slock","role":"owner"}]"""
            ),
        private val serverOrderResponse: AuthNetworkResponse =
            AuthNetworkResponse(200, """{"serverOrder":[]}"""),
        private val currentUserResponses: ArrayDeque<AuthNetworkResponse> =
            ArrayDeque(
                listOf(
                    AuthNetworkResponse(
                        200,
                        """{"id":"user-1","email":"user@slock.ai","displayName":"Artin"}"""
                    )
                )
            ),
        private val refreshResponse: AuthNetworkResponse =
            AuthNetworkResponse(200, """{"accessToken":"access-token","refreshToken":"refresh-token"}"""),
        private val logoutResponse: AuthNetworkResponse =
            AuthNetworkResponse(200, """{"ok":true}"""),
        private val createServerResponse: AuthNetworkResponse =
            AuthNetworkResponse(201, """{"id":"server-2","name":"Ops","slug":"ops"}"""),
        private val createServerResponses: ArrayDeque<AuthNetworkResponse>? = null,
        private val serversResponses: ArrayDeque<AuthNetworkResponse>? = null,
        private val throwCurrentUser: Boolean = false,
        private val resendVerificationResponse: AuthNetworkResponse =
            AuthNetworkResponse(200, """{"ok":true}"""),
        private val verifyEmailResponse: AuthNetworkResponse =
            AuthNetworkResponse(200, """{"ok":true}"""),
        private val qaConsumeResponse: AuthNetworkResponse = AuthNetworkResponse(501, null),
        private val qaReadyResponse: AuthNetworkResponse = AuthNetworkResponse(501, null),
        private val qaStatusResponse: AuthNetworkResponse = AuthNetworkResponse(501, null),
        private val onCurrentUserRequest: suspend (Int) -> Unit = {},
        private val onCompleteProfileRequest: suspend () -> Unit = {},
        private val onServersRequest: suspend () -> Unit = {},
        private val onRefreshRequest: suspend () -> Unit = {},
        private val onCreateServerRequest: suspend (Int) -> Unit = {},
        private val onLogoutRequest: suspend () -> Unit = {},
        private val onQaReadyRequest: suspend () -> Unit = {},
    ) : RaftAuthTransport {
        var loginBody: String? = null
            private set
        var completeProfileAuthorization: String? = null
            private set
        var completeProfileBody: String? = null
            private set
        var socialCompleteProvider: String? = null
            private set
        var socialCompleteBody: String? = null
            private set
        var nativeAppleStartBody: String? = null
            private set
        var nativeAppleStartAuthorization: String? = null
            private set
        var nativeAppleAuthorizeBody: String? = null
            private set
        var nativeAppleAuthorizeAuthorization: String? = null
            private set
        var mobileOAuthLinkStartProvider: String? = null
            private set
        var mobileOAuthLinkStartBody: String? = null
            private set
        var mobileOAuthLinkStartAuthorization: String? = null
            private set
        var mobileOAuthLinkCompleteProvider: String? = null
            private set
        var mobileOAuthLinkCompleteBody: String? = null
            private set
        var mobileOAuthLinkCompleteAuthorization: String? = null
            private set
        var currentUserAuthorization: String? = null
            private set
        var resendVerificationAuthorization: String? = null
            private set
        var verifyEmailBody: String? = null
            private set
        var verifyEmailRequestCount: Int = 0
            private set
        var currentUserRequestCount: Int = 0
            private set
        var serversAuthorization: String? = null
            private set
        val serversAuthorizations = mutableListOf<String>()
        val serverOrderAuthorizations = mutableListOf<String>()
        var createServerBody: String? = null
            private set
        val createServerAuthorizations = mutableListOf<String>()
        var qaConsumeBody: String? = null
            private set
        var qaReadyTicketDigest: String? = null
            private set
        var qaReadyAuthorization: String? = null
            private set
        var qaReadyBody: String? = null
            private set

        override suspend fun login(bodyText: String): AuthNetworkResponse {
            loginBody = bodyText
            return loginResponse
        }

        override suspend fun completeAppLogin(bodyText: String): AuthNetworkResponse = loginResponse

        override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse {
            socialCompleteProvider = provider
            socialCompleteBody = bodyText
            return socialCompleteResponse
        }

        override suspend fun socialAuthProviders(platform: String): AuthNetworkResponse =
            AuthNetworkResponse(code = 501, bodyText = null)

        override suspend fun startNativeAppleMobileOAuth(
            bodyText: String,
            authorization: String?
        ): AuthNetworkResponse {
            nativeAppleStartBody = bodyText
            nativeAppleStartAuthorization = authorization
            return nativeAppleStartResponse
        }

        override suspend fun authorizeNativeAppleMobileOAuth(
            bodyText: String,
            authorization: String?
        ): AuthNetworkResponse {
            nativeAppleAuthorizeBody = bodyText
            nativeAppleAuthorizeAuthorization = authorization
            return nativeAppleAuthorizeResponse
        }

        override suspend fun startMobileOAuthLink(
            provider: String,
            bodyText: String,
            authorization: String
        ): AuthNetworkResponse {
            mobileOAuthLinkStartProvider = provider
            mobileOAuthLinkStartBody = bodyText
            mobileOAuthLinkStartAuthorization = authorization
            return mobileOAuthLinkStartResponse
        }

        override suspend fun completeMobileOAuthLink(
            provider: String,
            bodyText: String,
            authorization: String
        ): AuthNetworkResponse {
            mobileOAuthLinkCompleteProvider = provider
            mobileOAuthLinkCompleteBody = bodyText
            mobileOAuthLinkCompleteAuthorization = authorization
            return mobileOAuthLinkCompleteResponse
        }

        override suspend fun resendVerification(authorization: String): AuthNetworkResponse {
            resendVerificationAuthorization = authorization
            return resendVerificationResponse
        }

        override suspend fun verifyEmail(bodyText: String): AuthNetworkResponse {
            verifyEmailBody = bodyText
            verifyEmailRequestCount += 1
            return verifyEmailResponse
        }

        override suspend fun currentUser(authorization: String): AuthNetworkResponse {
            currentUserAuthorization = authorization
            currentUserRequestCount += 1
            onCurrentUserRequest(currentUserRequestCount)
            if (throwCurrentUser) error("bridge not ready")
            return if (currentUserResponses.size > 1) currentUserResponses.removeFirst() else currentUserResponses.first()
        }

        override suspend fun completeProfile(authorization: String, bodyText: String): AuthNetworkResponse {
            completeProfileAuthorization = authorization
            completeProfileBody = bodyText
            onCompleteProfileRequest()
            return AuthNetworkResponse(
                200,
                """{"id":"user-1","email":"user@slock.ai","name":"artin","displayName":"Artin Chen","profileSetupCompletedAt":"2026-07-21T00:00:00Z"}"""
            )
        }

        override suspend fun refresh(bodyText: String): AuthNetworkResponse {
            onRefreshRequest()
            return refreshResponse
        }

        override suspend fun logout(bodyText: String): AuthNetworkResponse {
            onLogoutRequest()
            return logoutResponse
        }

        override suspend fun servers(authorization: String): AuthNetworkResponse {
            serversAuthorization = authorization
            serversAuthorizations += authorization
            onServersRequest()
            return serversResponses?.let { responses ->
                if (responses.size > 1) {
                    responses.removeFirst()
                } else {
                    responses.first()
                }
            } ?: serversResponse
        }

        override suspend fun serverOrder(authorization: String): AuthNetworkResponse {
            serverOrderAuthorizations += authorization
            return serverOrderResponse
        }

        override suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse {
            createServerBody = bodyText
            createServerAuthorizations += authorization
            onCreateServerRequest(createServerAuthorizations.size)
            return createServerResponses?.let { responses ->
                if (responses.size > 1) {
                    responses.removeFirst()
                } else {
                    responses.first()
                }
            } ?: createServerResponse
        }

        override suspend fun consumeQaBootstrap(bodyText: String): AuthNetworkResponse {
            qaConsumeBody = bodyText
            return qaConsumeResponse
        }

        override suspend fun readyQaBootstrap(ticketDigest: String, authorization: String, bodyText: String): AuthNetworkResponse {
            qaReadyTicketDigest = ticketDigest
            qaReadyAuthorization = authorization
            qaReadyBody = bodyText
            onQaReadyRequest()
            return qaReadyResponse
        }

        override suspend fun statusQaBootstrap(ticketDigest: String, authorization: String): AuthNetworkResponse =
            qaStatusResponse
    }

    private class ThrowingSaveSessionStore(
        private val throwable: Throwable = IllegalStateException("store unavailable")
    ) : RaftAuthSessionStore {
        override suspend fun readSession(): StoredAuthSession? = null

        override suspend fun saveSession(session: StoredAuthSession) {
            throw throwable
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) = Unit

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) = Unit

        override suspend fun clearSession(reason: String, source: String) = Unit
    }

    private class ThrowingReadSessionStore(
        private val throwable: Throwable
    ) : RaftAuthSessionStore {
        override suspend fun readSession(): StoredAuthSession? {
            throw throwable
        }

        override suspend fun saveSession(session: StoredAuthSession) = Unit

        override suspend fun updateTokens(accessToken: String, refreshToken: String) = Unit

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) = Unit

        override suspend fun clearSession(reason: String, source: String) = Unit
    }
}
