package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.ScreenError
import build.raft.app.data.SecondFactorStepUpChallenge
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpIntent
import build.raft.app.data.SecondFactorStepUpMethod
import build.raft.app.data.SecondFactorStepUpResult
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RaftBrowserSignInSessionTest {
    private val apiBase = "https://api.raft.build"
    private val webBase = "https://app.raft.build"
    private val raftHosts = setOf("app.raft.build", "api.raft.build")
    private val appReturnUrl = "https://stamp.build/auth/callback?next=1"
    private val authorizeUrl =
        "https://api.raft.build/api/oauth/authorize?client_id=stamp&redirect_uri=https%3A%2F%2Fstamp.build%2Fauth%2Fcallback%3Fnext%3D1&scope=openid+profile&state=secret-state-xyz&nonce=secret-nonce-abc&code_challenge=challenge-secret-123"
    private val setupLocation =
        "https://app.raft.build/login-with-raft/setup?flow=oidc&client_id=stamp&return_to=https%3A%2F%2Fstamp.build%2Fauth%2Fcallback%3Fnext%3D1&scope=openid+profile&state=secret-state-xyz&nonce=secret-nonce-abc"

    private fun pageAsk(url: String = authorizeUrl): RaftWebViewMainFrameCatchAsk =
        RaftWebViewMainFrameCatchAsk(
            url = url,
            starter = RaftSignInStarter.Page,
            page = "https://stamp.build/pulls",
            committed = true,
            hops = emptyList(),
        )

    private fun defaultAccount(
        accountId: String = "user-1",
        envKey: String = "prod",
    ): RaftBrowserSignInAccountContext =
        RaftBrowserSignInAccountContext(
            accountId = accountId,
            signedInAsLabel = "Alice",
            currentServerId = "srv-1",
            servers =
                listOf(
                    RaftBrowserSignInServerOption(id = "srv-1", name = "Workspace", slug = "workspace"),
                    RaftBrowserSignInServerOption(id = "srv-2", name = "Labs", slug = "labs"),
                ),
            environmentKey = envKey,
        )

    private fun readyClient(
        serverId: String = "srv-1",
        returnUrl: String = appReturnUrl,
        consent: RaftSignInConsent? = null,
        loginMode: RaftSignInLoginMode = RaftSignInLoginMode.Server,
        availability: RaftSignInAvailability = RaftSignInAvailability.Ready,
        canInstall: Boolean = false,
        scopeValidation: RaftBrowserSignInScopeValidation? = null,
    ): RaftBrowserSignInClientDetails =
        RaftBrowserSignInClientDetails(
            serverId = serverId,
            clientId = "stamp",
            name = "Stamp",
            description = "Code review",
            homepageUrl = "https://stamp.build",
            returnUrl = returnUrl,
            logoUrl = null,
            marketplace = false,
            availability = availability,
            canInstall = canInstall,
            botHandle = null,
            scopeValidation = scopeValidation,
            loginMode = loginMode,
            consent = consent,
        )

    private class Harness(
        scope: TestScope,
        var flagEnabled: Boolean = true,
        var pageVisible: Boolean = true,
        var appForeground: Boolean = true,
        var account: RaftBrowserSignInAccountContext? = null,
        var preferredServerId: String? = "srv-1",
        var webUrl: String? = "https://app.raft.build",
        var setupOrigin: String? = raftAuthorizeOrigin("https://app.raft.build", allowLoopback = false),
        var fetchHandler: suspend (String) -> RaftAuthorizeResponse? = { null },
        var lookupHandler: suspend (String, String, String) -> RaftBrowserSignInLookupResult = { _, _, _ ->
            RaftBrowserSignInLookupResult.NotAvailable
        },
        var approveHandler: suspend (RaftBrowserSignInApproveRequest) -> DataResult<RaftBrowserSignInApproveOutcome> = {
            DataResult.Content(RaftBrowserSignInApproveOutcome.Failed("unhandled"))
        },
        val apiOverride: RaftBrowserSignInApi? = null,
        val stepUp: SecondFactorStepUpCoordinator? = null,
    ) {
        val loadedUrls = mutableListOf<String>()
        val toasts = mutableListOf<String>()
        val openedExternal = mutableListOf<String>()
        val logs = mutableListOf<String>()
        private var reqCounter = 0

        val session =
            RaftBrowserSignInSession(
                scope = scope,
                authorizeOriginProvider = { raftAuthorizeOrigin("https://api.raft.build", allowLoopback = false) },
                webUrlProvider = { webUrl },
                setupOriginProvider = { setupOrigin },
                raftHostsProvider = { setOf("app.raft.build", "api.raft.build") },
                featureEnabledProvider = { flagEnabled },
                accountProvider = { account },
                preferredServerId = preferredServerId,
                isPageVisible = { pageVisible },
                isAppForeground = { appForeground },
                fetchAuthorize = { fetchHandler(it) },
                api =
                    apiOverride ?: object : RaftBrowserSignInApi {
                        override suspend fun lookupClient(clientId: String, serverId: String, scope: String) =
                            lookupHandler(clientId, serverId, scope)

                        override suspend fun approve(request: RaftBrowserSignInApproveRequest) = approveHandler(request)

                        override suspend fun findInstalledAppByOrigin(serverId: String, targetOrigin: String) = null
                    },
                stepUp = stepUp,
                loadUrl = {
                    loadedUrls += it
                    true
                },
                onSignedInWithoutAsking = { toasts += it },
                onOpenInBrowser = { openedExternal += it },
                nowMs = { scope.currentTime },
                newRequestId = { "req-${++reqCounter}" },
                log = { logs += it },
            )
    }

    @Test
    fun flagOffSkipsCatchCompletely() = runTest {
        var fetched = false
        val h =
            Harness(
                scope = this,
                flagEnabled = false,
                account = defaultAccount(),
                fetchHandler = {
                    fetched = true
                    RaftAuthorizeResponse(302, setupLocation)
                },
            )
        assertFalse(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertFalse(fetched)
        assertNull(h.session.uiState.value.sheet)
        assertNull(h.session.uiState.value.declinedAuthorizeUrl)
    }

    @Test
    fun consentSkipLoadsValidatedAnswerAndToastsWithoutShowingSheet() = runTest {
        val approveCalls = mutableListOf<RaftBrowserSignInApproveRequest>()
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(serverId = "srv-1", consent = RaftSignInConsent(granted = true, coversRequested = true)),
                        )
                    } else {
                        RaftBrowserSignInLookupResult.NotAvailable
                    }
                },
                approveHandler = { req ->
                    approveCalls += req
                    DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = "secret-code-99", returnUrl = appReturnUrl))
                },
            )

        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()

        assertEquals(1, approveCalls.size)
        assertTrue(approveCalls.single().confirmSkipped)
        assertNull(h.session.uiState.value.sheet)
        assertEquals(listOf("Stamp"), h.toasts)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&code=secret-code-99&state=secret-state-xyz"),
            h.loadedUrls,
        )
        // Zero secret logging: never URL, code, state, nonce, or challenge.
        val allLogs = h.logs.joinToString("\n")
        assertFalse("secret-code-99" in allLogs)
        assertFalse("secret-state-xyz" in allLogs)
        assertFalse("secret-nonce-abc" in allLogs)
        assertFalse("challenge-secret-123" in allLogs)
        assertFalse("https://" in allLogs)
        assertTrue("clientId=stamp" in allLogs)
        assertTrue("requestId=req-1" in allLogs)
    }

    @Test
    fun consentSkipConsentRequiredAndStepUpFallBackToNativeSheet() = runTest {
        var stepUpPrompted = false
        val stepUp =
            SecondFactorStepUpCoordinator(
                stepUp = {
                    stepUpPrompted = true
                    SecondFactorStepUpResult.Verified(null)
                },
            )
        var attempts = 0
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                stepUp = stepUp,
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(serverId = "srv-1", consent = RaftSignInConsent(granted = true, coversRequested = true)),
                        )
                    } else {
                        RaftBrowserSignInLookupResult.NotAvailable
                    }
                },
                approveHandler = { req ->
                    attempts += 1
                    if (req.confirmSkipped) {
                        // First call (skip): 403 step-up required must NOT open step-up dialog; must show sheet instead.
                        DataResult.Error(
                            ScreenError(
                                code = "second_factor_step_up_required",
                                retryable = false,
                                secondFactorStepUp =
                                    SecondFactorStepUpChallenge(
                                        methods = listOf(SecondFactorStepUpMethod.Totp.wireName),
                                        serverMessage = "Verify",
                                    ),
                            ),
                        )
                    } else {
                        DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = "ok-code", returnUrl = appReturnUrl))
                    }
                },
            )

        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()

        assertEquals(1, attempts)
        assertFalse(stepUpPrompted)
        assertNull(stepUp.state.value)
        val sheet = assertNotNull(h.session.uiState.value.sheet)
        assertEquals("Stamp", sheet.appName)
        val mode = assertIs<RaftBrowserSignInSheetMode.ServerReady>(sheet.mode)
        assertEquals("srv-1", mode.selectedServerId)
        assertTrue(h.loadedUrls.isEmpty())

        // Interactive Continue succeeds and loads the callback URL.
        h.session.approveCurrent()
        runCurrent()
        assertEquals(2, attempts)
        assertNull(h.session.uiState.value.sheet)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&code=ok-code&state=secret-state-xyz"),
            h.loadedUrls,
        )
    }

    @Test
    fun interactiveApproveRunsStepUpAndRetriesAfterVerification() = runTest {
        val stepUp =
            SecondFactorStepUpCoordinator(
                stepUp = { SecondFactorStepUpResult.Verified(null) },
                nativePasskeys = false,
            )
        var approveCalls = 0
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                stepUp = stepUp,
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-1"))
                    else RaftBrowserSignInLookupResult.NotAvailable
                },
                approveHandler = {
                    approveCalls += 1
                    if (approveCalls == 1) {
                        DataResult.Error(
                            ScreenError(
                                code = "second_factor_step_up_required",
                                retryable = false,
                                secondFactorStepUp =
                                    SecondFactorStepUpChallenge(
                                        methods = listOf(SecondFactorStepUpMethod.Totp.wireName),
                                        serverMessage = "Verify",
                                    ),
                            ),
                        )
                    } else {
                        DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = "after-2fa", returnUrl = appReturnUrl))
                    }
                },
            )

        h.session.onMainFrameNavigation(pageAsk())
        runCurrent()
        assertNotNull(h.session.uiState.value.sheet)

        h.session.approveCurrent()
        runCurrent()
        assertNotNull(stepUp.state.value)

        // Cancel on sheet while step-up is open is ignored.
        h.session.cancelSheet()
        assertNotNull(h.session.uiState.value.sheet)

        stepUp.accept(SecondFactorStepUpIntent.InputChanged("123456"))
        stepUp.accept(SecondFactorStepUpIntent.Submit)
        runCurrent()

        assertEquals(2, approveCalls)
        assertNull(stepUp.state.value)
        assertNull(h.session.uiState.value.sheet)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&code=after-2fa&state=secret-state-xyz"),
            h.loadedUrls,
        )
    }

    @Test
    fun cancelOnShownSheetLoadsAccessDeniedAndArmsTenSecondQuietPeriod() = runTest {
        var fetchCalls = 0
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = {
                    fetchCalls += 1
                    RaftAuthorizeResponse(302, setupLocation)
                },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-1"))
                    else RaftBrowserSignInLookupResult.NotAvailable
                },
            )

        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertNotNull(h.session.uiState.value.sheet)

        h.session.cancelSheet()
        assertNull(h.session.uiState.value.sheet)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&error=access_denied&state=secret-state-xyz"),
            h.loadedUrls,
        )

        // Within 10 s quiet period: declined without fetching.
        advanceTimeBy(9_999L)
        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(1, fetchCalls)
        assertEquals(authorizeUrl, h.session.uiState.value.declinedAuthorizeUrl)

        // After 10 s: allowed again.
        advanceTimeBy(2L)
        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(2, fetchCalls)
        assertNotNull(h.session.uiState.value.sheet)
    }

    @Test
    fun unregisteredReturnRefusesWithoutLoadingAnythingAndArmsQuietPeriod() = runTest {
        var fetchCalls = 0
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = {
                    fetchCalls += 1
                    RaftAuthorizeResponse(302, setupLocation)
                },
                lookupHandler = { _, serverId, _ ->
                    RaftBrowserSignInLookupResult.Found(
                        readyClient(serverId = serverId, returnUrl = "https://other.example/cb"),
                    )
                },
            )

        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()

        assertNull(h.session.uiState.value.sheet)
        assertEquals(authorizeUrl, h.session.uiState.value.declinedAuthorizeUrl)
        assertTrue(h.loadedUrls.isEmpty())

        // Quiet period holds for 10 s after refusal.
        advanceTimeBy(5_000L)
        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(1, fetchCalls)
    }

    @Test
    fun pendingLockBlocksSecondRequestWhileFirstIsInFlight() = runTest {
        val gate = CompletableDeferred<RaftAuthorizeResponse?>()
        var fetchCalls = 0
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = {
                    fetchCalls += 1
                    gate.await()
                },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-1"))
                    else RaftBrowserSignInLookupResult.NotAvailable
                },
            )

        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(1, fetchCalls)

        // Second request while first is still awaiting server is declined without a second fetch.
        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(1, fetchCalls)
        assertTrue(h.logs.any { "reason=pending" in it })

        gate.complete(RaftAuthorizeResponse(302, setupLocation))
        runCurrent()
        assertNotNull(h.session.uiState.value.sheet)
    }

    @Test
    fun commitLifetimeAccountAndEnvironmentChangesDropSheetWithoutLoading() = runTest {
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-1"))
                    else RaftBrowserSignInLookupResult.NotAvailable
                },
            )

        // 1. Main-frame commit drops shown sheet.
        h.session.onMainFrameNavigation(pageAsk())
        runCurrent()
        assertNotNull(h.session.uiState.value.sheet)
        h.session.onPageCommitted()
        assertNull(h.session.uiState.value.sheet)
        assertTrue(h.loadedUrls.isEmpty())

        // 2. 10-min lifetime expires shown sheet.
        advanceTimeBy(RAFT_SIGN_IN_RAISE_AGAIN_AFTER_MS + 1L)
        h.session.onMainFrameNavigation(pageAsk())
        runCurrent()
        assertNotNull(h.session.uiState.value.sheet)
        advanceTimeBy(RAFT_SIGN_IN_REQUEST_LIFETIME_MS)
        runCurrent()
        assertNull(h.session.uiState.value.sheet)
        assertTrue(h.loadedUrls.isEmpty())

        // 3. Account switch drops shown sheet.
        advanceTimeBy(RAFT_SIGN_IN_RAISE_AGAIN_AFTER_MS + 1L)
        h.session.onMainFrameNavigation(pageAsk())
        runCurrent()
        assertNotNull(h.session.uiState.value.sheet)
        h.account = defaultAccount(accountId = "user-2")
        h.session.onAccountOrEnvironmentChanged(accountId = "user-2", environmentKey = "prod")
        assertNull(h.session.uiState.value.sheet)
        assertTrue(h.loadedUrls.isEmpty())

        // 4. Environment change drops shown sheet.
        advanceTimeBy(RAFT_SIGN_IN_RAISE_AGAIN_AFTER_MS + 1L)
        h.session.onMainFrameNavigation(pageAsk())
        runCurrent()
        assertNotNull(h.session.uiState.value.sheet)
        h.account = defaultAccount(accountId = "user-2", envKey = "staging")
        h.session.onAccountOrEnvironmentChanged(accountId = "user-2", environmentKey = "staging")
        assertNull(h.session.uiState.value.sheet)
        assertTrue(h.loadedUrls.isEmpty())
    }

    @Test
    fun loginModeChangedRerunsLookupOnceAndNonReadyStatesOpenOriginalUrlInBrowser() = runTest {
        var accountModeNow = false
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(
                                serverId = "srv-1",
                                loginMode = if (accountModeNow) RaftSignInLoginMode.Account else RaftSignInLoginMode.Server,
                            ),
                        )
                    } else {
                        RaftBrowserSignInLookupResult.NotAvailable
                    }
                },
                approveHandler = {
                    accountModeNow = true
                    DataResult.Content(RaftBrowserSignInApproveOutcome.LoginModeChanged)
                },
            )

        h.session.onMainFrameNavigation(pageAsk())
        runCurrent()
        assertIs<RaftBrowserSignInSheetMode.ServerReady>(h.session.uiState.value.sheet?.mode)

        h.session.approveCurrent()
        runCurrent()
        val accountMode = assertIs<RaftBrowserSignInSheetMode.AccountReady>(h.session.uiState.value.sheet?.mode)
        assertTrue(accountMode.loginModeChanged)

        // Open in browser opens the original authorize URL without loading access_denied into the WebView.
        h.session.openOriginalAuthorizeInBrowser()
        assertNull(h.session.uiState.value.sheet)
        assertEquals(listOf(authorizeUrl), h.openedExternal)
        assertTrue(h.loadedUrls.isEmpty())
    }

    @Test
    fun installRequiredAndScopeNotApprovedStatesBehavePerSpec() = runTest {
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(
                                serverId = "srv-1",
                                availability = RaftSignInAvailability.InstallRequired,
                                canInstall = true,
                            ),
                        )
                    } else {
                        RaftBrowserSignInLookupResult.NotAvailable
                    }
                },
            )

        h.session.onMainFrameNavigation(pageAsk())
        runCurrent()
        val mode = assertIs<RaftBrowserSignInSheetMode.InstallRequired>(h.session.uiState.value.sheet?.mode)
        assertTrue(mode.canInstall)
        assertFalse(h.session.uiState.value.sheet!!.canApprove)
    }

    @Test
    fun directSetupEntrySkipsAuthorizeFetchStripsOidcParamsAndCompletesSignIn() = runTest {
        val returnEncoded = "https%3A%2F%2Fstamp.build%2Fauth%2Fcallback%3Fnext%3D1"
        val setupPaths =
            listOf(
                "/login-with-raft/setup",
                "/login-with-slock/setup",
                "/login-with-slock-human/setup",
                "/Login-With-Slock-Human/Setup/",
            )
        for (path in setupPaths) {
            var fetchCalls = 0
            val approveCalls = mutableListOf<RaftBrowserSignInApproveRequest>()
            val setupEntryUrl =
                "$webBase$path?flow=oidc&client_id=stamp&return_to=$returnEncoded&scope=openid+profile" +
                    "&state=secret-state-xyz&nonce=secret-nonce-abc&code_challenge=challenge-secret-123" +
                    "&code_challenge_method=S256&server=workspace&utm=1"
            val h =
                Harness(
                    scope = this,
                    account = defaultAccount(),
                    fetchHandler = {
                        fetchCalls += 1
                        RaftAuthorizeResponse(302, setupLocation)
                    },
                    lookupHandler = { _, serverId, _ ->
                        if (serverId == "srv-1") RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-1"))
                        else RaftBrowserSignInLookupResult.NotAvailable
                    },
                    approveHandler = { req ->
                        approveCalls += req
                        DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = "direct-code-1", returnUrl = appReturnUrl))
                    },
                )

            assertTrue(h.session.onMainFrameNavigation(pageAsk(setupEntryUrl)), path)
            runCurrent()

            assertEquals(0, fetchCalls, "$path must never call fetchAuthorize")
            val sheet = assertNotNull(h.session.uiState.value.sheet, path)
            assertEquals("Stamp", sheet.appName, path)
            val mode = assertIs<RaftBrowserSignInSheetMode.ServerReady>(sheet.mode)
            assertEquals("srv-1", mode.selectedServerId)

            h.session.approveCurrent()
            runCurrent()

            val approveReq = approveCalls.single()
            assertFalse(approveReq.oidcFlow, "$path must strip flow=oidc")
            assertNull(approveReq.nonce, "$path must strip nonce")
            assertNull(approveReq.codeChallenge, "$path must strip code_challenge")
            assertNull(approveReq.codeChallengeMethod, "$path must strip code_challenge_method")
            assertEquals("workspace", approveReq.serverHint)
            assertEquals(
                listOf("https://stamp.build/auth/callback?next=1&code=direct-code-1&state=secret-state-xyz"),
                h.loadedUrls,
                path,
            )
            // Zero secret logging.
            val allLogs = h.logs.joinToString("\n")
            assertFalse("direct-code-1" in allLogs)
            assertFalse("secret-state-xyz" in allLogs)
            assertFalse("secret-nonce-abc" in allLogs)
            assertFalse("https://" in allLogs)
        }

        // Also verifies redirect_uri fallback + consent-skip path without authorize fetch.
        var skipFetchCalls = 0
        val skipApproveCalls = mutableListOf<RaftBrowserSignInApproveRequest>()
        val skipHarness =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = {
                    skipFetchCalls += 1
                    RaftAuthorizeResponse(302, setupLocation)
                },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(serverId = "srv-1", consent = RaftSignInConsent(granted = true, coversRequested = true)),
                        )
                    } else {
                        RaftBrowserSignInLookupResult.NotAvailable
                    }
                },
                approveHandler = { req ->
                    skipApproveCalls += req
                    DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = "skip-direct", returnUrl = appReturnUrl))
                },
            )
        val legacyRedirectUriEntry =
            "$webBase/login-with-slock/setup?client_id=stamp&redirect_uri=$returnEncoded&flow=oidc&state=secret-state-xyz"
        assertTrue(skipHarness.session.onMainFrameNavigation(pageAsk(legacyRedirectUriEntry)))
        runCurrent()

        assertEquals(0, skipFetchCalls)
        assertTrue(skipApproveCalls.single().confirmSkipped)
        assertFalse(skipApproveCalls.single().oidcFlow)
        assertNull(skipHarness.session.uiState.value.sheet)
        assertEquals(listOf("Stamp"), skipHarness.toasts)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&code=skip-direct&state=secret-state-xyz"),
            skipHarness.loadedUrls,
        )
    }

    @Test
    fun directSetupEntryEnforcesAskRulesCancelStateQuietPeriodAndOriginCheck() = runTest {
        val returnEncoded = "https%3A%2F%2Fstamp.build%2Fauth%2Fcallback%3Fnext%3D1"
        val validEntry = "$webBase/login-with-raft/setup?client_id=stamp&return_to=$returnEncoded&state=direct-st"
        var fetchCalls = 0
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = {
                    fetchCalls += 1
                    RaftAuthorizeResponse(302, setupLocation)
                },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-1"))
                    else RaftBrowserSignInLookupResult.NotAvailable
                },
            )

        // Invalid / non-setup-origin URLs are not caught as requests.
        listOf(
            "$apiBase/login-with-raft/setup?client_id=stamp&return_to=$returnEncoded",
            "https://app.raft.build:8443/login-with-raft/setup?client_id=stamp&return_to=$returnEncoded",
            "$webBase/login-with-raft/setup?return_to=$returnEncoded",
            "$webBase/login-with-raft/setup?client_id=stamp&return_to=$returnEncoded&state=a&state=b",
            "$webBase/login-with-raft/setup?client_id=stamp&return_to=https%3A%2F%2Fstamp.build%2Fcb%23frag",
        ).forEach { invalid ->
            assertFalse(h.session.onMainFrameNavigation(pageAsk(invalid)), invalid)
        }
        assertNull(h.session.uiState.value.declinedAuthorizeUrl)

        // Cross-site page or cross-site hop is caught (cancelled) and declined with other_site.
        assertTrue(
            h.session.onMainFrameNavigation(
                RaftWebViewMainFrameCatchAsk(
                    url = validEntry,
                    starter = RaftSignInStarter.Page,
                    page = "https://evil.example/login",
                    committed = true,
                    hops = emptyList(),
                ),
            ),
        )
        assertTrue(h.logs.last().contains("reason=other_site"))
        assertTrue(
            h.session.onMainFrameNavigation(
                RaftWebViewMainFrameCatchAsk(
                    url = validEntry,
                    starter = RaftSignInStarter.Page,
                    page = "https://stamp.build/pulls",
                    committed = true,
                    hops = listOf("https://evil.example/bounce"),
                ),
            ),
        )
        assertTrue(h.logs.last().contains("reason=other_site"))

        // Hidden page / background app are refused too.
        h.pageVisible = false
        assertTrue(h.session.onMainFrameNavigation(pageAsk(validEntry)))
        assertTrue(h.logs.last().contains("reason=hidden_page"))
        h.pageVisible = true
        h.appForeground = false
        assertTrue(h.session.onMainFrameNavigation(pageAsk(validEntry)))
        assertTrue(h.logs.last().contains("reason=background"))
        h.appForeground = true
        assertNull(h.session.uiState.value.sheet)

        // Valid request shows sheet; cancelling echoes state and arms 10 s quiet period.
        assertTrue(h.session.onMainFrameNavigation(pageAsk(validEntry)))
        runCurrent()
        assertNotNull(h.session.uiState.value.sheet)
        h.session.cancelSheet()
        assertNull(h.session.uiState.value.sheet)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&error=access_denied&state=direct-st"),
            h.loadedUrls,
        )

        // Immediate retry within quiet period is declined without showing sheet.
        assertTrue(h.session.onMainFrameNavigation(pageAsk("$webBase/login-with-slock/setup?client_id=stamp&return_to=$returnEncoded")))
        runCurrent()
        assertTrue(h.logs.last().contains("reason=raised_recently"))
        assertNull(h.session.uiState.value.sheet)
        assertEquals(0, fetchCalls)

        // Null setupOrigin disables direct setup entry catch.
        h.setupOrigin = null
        advanceTimeBy(RAFT_SIGN_IN_RAISE_AGAIN_AFTER_MS + 1L)
        assertFalse(h.session.onMainFrameNavigation(pageAsk(validEntry)))
    }

    private fun manyServersAccount(
        count: Int = 20,
        currentServerId: String? = "srv-1",
    ): RaftBrowserSignInAccountContext =
        RaftBrowserSignInAccountContext(
            accountId = "user-1",
            signedInAsLabel = "Alice",
            currentServerId = currentServerId,
            servers =
                (1..count).map { index ->
                    RaftBrowserSignInServerOption(
                        id = "srv-$index",
                        name = "Server $index",
                        slug = "server-$index",
                    )
                },
            environmentKey = "prod",
        )

    @Test
    fun serverHintNarrowsLookupToMatchingServersOnly() = runTest {
        val queriedBySlug = mutableListOf<String>()
        val slugHarness =
            Harness(
                scope = this,
                account = manyServersAccount(20),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, "$setupLocation&server=server-12") },
                lookupHandler = { _, serverId, _ ->
                    queriedBySlug += serverId
                    RaftBrowserSignInLookupResult.Found(readyClient(serverId = serverId))
                },
            )
        assertTrue(slugHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(listOf("srv-12"), queriedBySlug)
        val slugSheet = assertNotNull(slugHarness.session.uiState.value.sheet)
        val slugMode = assertIs<RaftBrowserSignInSheetMode.ServerReady>(slugSheet.mode)
        assertEquals(listOf("srv-12"), slugMode.readyServers.map { it.id })
        assertEquals("srv-12", slugMode.selectedServerId)
        assertFalse(slugHarness.session.uiState.value.resolving)

        // Matching by server id also queries only that server.
        val queriedById = mutableListOf<String>()
        val idHarness =
            Harness(
                scope = this,
                account = manyServersAccount(20),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, "$setupLocation&server=srv-15") },
                lookupHandler = { _, serverId, _ ->
                    queriedById += serverId
                    RaftBrowserSignInLookupResult.Found(readyClient(serverId = serverId))
                },
            )
        assertTrue(idHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(listOf("srv-15"), queriedById)
        assertFalse(idHarness.session.uiState.value.resolving)

        // Unknown server hint makes zero lookup calls and fails closed with UnregisteredReturn.
        val queriedUnknown = mutableListOf<String>()
        val unknownHarness =
            Harness(
                scope = this,
                account = manyServersAccount(20),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, "$setupLocation&server=no-such-server") },
                lookupHandler = { _, serverId, _ ->
                    queriedUnknown += serverId
                    RaftBrowserSignInLookupResult.Found(readyClient(serverId = serverId))
                },
            )
        assertTrue(unknownHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertTrue(queriedUnknown.isEmpty())
        assertNull(unknownHarness.session.uiState.value.sheet)
        assertFalse(unknownHarness.session.uiState.value.resolving)
        assertEquals(authorizeUrl, unknownHarness.session.uiState.value.declinedAuthorizeUrl)
        assertEquals(RaftBrowserSignInDropReason.UnregisteredReturn, unknownHarness.session.uiState.value.declinedReason)
    }

    @Test
    fun accountModeFastPathCompletesOnFirstReadyAndCancelsSlowLookups() = runTest {
        val startedSlow = mutableSetOf<String>()
        val cancelledServers = mutableSetOf<String>()
        val slowGate = CompletableDeferred<RaftBrowserSignInLookupResult>()
        val approveCalls = mutableListOf<RaftBrowserSignInApproveRequest>()
        val h =
            Harness(
                scope = this,
                account = manyServersAccount(20),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    when (serverId) {
                        "srv-3" ->
                            RaftBrowserSignInLookupResult.Found(
                                readyClient(
                                    serverId = serverId,
                                    loginMode = RaftSignInLoginMode.Account,
                                    availability = RaftSignInAvailability.Suspended,
                                ),
                            )
                        "srv-7", "srv-12" ->
                            RaftBrowserSignInLookupResult.Found(
                                readyClient(
                                    serverId = serverId,
                                    loginMode = RaftSignInLoginMode.Account,
                                    availability = RaftSignInAvailability.Ready,
                                    consent = RaftSignInConsent(granted = true, coversRequested = true),
                                ),
                            )
                        else -> {
                            startedSlow += serverId
                            try {
                                slowGate.await()
                            } catch (cancelled: CancellationException) {
                                cancelledServers += serverId
                                throw cancelled
                            }
                        }
                    }
                },
                approveHandler = { req ->
                    approveCalls += req
                    DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = "acct-fast-1", returnUrl = appReturnUrl))
                },
            )

        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()

        assertEquals(1, approveCalls.size)
        assertTrue(approveCalls.single().confirmSkipped)
        assertEquals(RaftBrowserSignInApproveTarget.Account, approveCalls.single().target)
        assertNull(h.session.uiState.value.sheet)
        assertFalse(h.session.uiState.value.resolving)
        assertEquals(listOf("Stamp"), h.toasts)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&code=acct-fast-1&state=secret-state-xyz"),
            h.loadedUrls,
        )
        assertFalse(slowGate.isCompleted)
        assertTrue("srv-1" in cancelledServers)
        assertEquals(startedSlow, cancelledServers)

        // First-time Account-mode login (no consent yet) also opens AccountReady immediately without waiting for slow servers.
        val firstTimeStartedSlow = mutableSetOf<String>()
        val firstTimeCancelled = mutableSetOf<String>()
        val firstTimeGate = CompletableDeferred<RaftBrowserSignInLookupResult>()
        val firstTimeHarness =
            Harness(
                scope = this,
                account = manyServersAccount(20),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-7") {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(
                                serverId = serverId,
                                loginMode = RaftSignInLoginMode.Account,
                                availability = RaftSignInAvailability.Ready,
                                consent = null,
                            ),
                        )
                    } else {
                        firstTimeStartedSlow += serverId
                        try {
                            firstTimeGate.await()
                        } catch (cancelled: CancellationException) {
                            firstTimeCancelled += serverId
                            throw cancelled
                        }
                    }
                },
            )
        assertTrue(firstTimeHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertIs<RaftBrowserSignInSheetMode.AccountReady>(firstTimeHarness.session.uiState.value.sheet?.mode)
        assertFalse(firstTimeHarness.session.uiState.value.resolving)
        assertFalse(firstTimeGate.isCompleted)
        assertTrue("srv-1" in firstTimeCancelled)
        assertEquals(firstTimeStartedSlow, firstTimeCancelled)
    }

    @Test
    fun serverModePreferredFastPathSkipsRespectsSelectionRulesAndCompletesPickerOnFallback() = runTest {
        // 1. Preferred server Ready + consent covered skips immediately and cancels slow siblings.
        val startedSlow = mutableSetOf<String>()
        val cancelled = mutableSetOf<String>()
        val slowGate = CompletableDeferred<RaftBrowserSignInLookupResult>()
        val skipHarness =
            Harness(
                scope = this,
                account = manyServersAccount(5),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(
                                serverId = "srv-1",
                                loginMode = RaftSignInLoginMode.Server,
                                consent = RaftSignInConsent(granted = true, coversRequested = true),
                            ),
                        )
                    } else {
                        startedSlow += serverId
                        try {
                            slowGate.await()
                        } catch (c: CancellationException) {
                            cancelled += serverId
                            throw c
                        }
                    }
                },
                approveHandler = {
                    DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = "pref-skip", returnUrl = appReturnUrl))
                },
            )
        assertTrue(skipHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertFalse(slowGate.isCompleted)
        assertEquals(startedSlow, cancelled)
        assertFalse(skipHarness.session.uiState.value.resolving)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&code=pref-skip&state=secret-state-xyz"),
            skipHarness.loadedUrls,
        )

        // 2. Non-preferred server Ready + consent covered MUST NOT skip early while preferred is pending.
        val preferredGate = CompletableDeferred<RaftBrowserSignInLookupResult>()
        val approveCalls = mutableListOf<RaftBrowserSignInApproveRequest>()
        val orderHarness =
            Harness(
                scope = this,
                account = defaultAccount(),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") {
                        preferredGate.await()
                    } else {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(
                                serverId = "srv-2",
                                loginMode = RaftSignInLoginMode.Server,
                                consent = RaftSignInConsent(granted = true, coversRequested = true),
                            ),
                        )
                    }
                },
                approveHandler = { req ->
                    approveCalls += req
                    DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = "unexpected", returnUrl = appReturnUrl))
                },
            )
        assertTrue(orderHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertTrue(approveCalls.isEmpty())
        assertTrue(orderHarness.session.uiState.value.resolving)
        assertNull(orderHarness.session.uiState.value.sheet)

        // Preferred server is Ready without consent -> must open sheet selecting srv-1, never skip to srv-2.
        preferredGate.complete(RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-1", consent = null)))
        runCurrent()
        assertTrue(approveCalls.isEmpty())
        assertFalse(orderHarness.session.uiState.value.resolving)
        val mode = assertIs<RaftBrowserSignInSheetMode.ServerReady>(orderHarness.session.uiState.value.sheet?.mode)
        assertEquals(listOf("srv-1", "srv-2"), mode.readyServers.map { it.id })
        assertEquals("srv-1", mode.selectedServerId)

        // 3. Preferred skip approve failing (403 step-up) fetches remaining servers so the sheet picker is complete.
        var srv2Calls = 0
        val srv2FirstAttemptGate = CompletableDeferred<RaftBrowserSignInLookupResult>()
        val fallbackHarness =
            Harness(
                scope = this,
                account = defaultAccount(),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(
                                serverId = "srv-1",
                                loginMode = RaftSignInLoginMode.Server,
                                consent = RaftSignInConsent(granted = true, coversRequested = true),
                            ),
                        )
                    } else {
                        srv2Calls += 1
                        if (srv2Calls == 1) {
                            srv2FirstAttemptGate.await()
                        } else {
                            RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-2"))
                        }
                    }
                },
                approveHandler = {
                    DataResult.Error(
                        ScreenError(
                            code = "second_factor_step_up_required",
                            retryable = false,
                            secondFactorStepUp =
                                SecondFactorStepUpChallenge(
                                    methods = listOf(SecondFactorStepUpMethod.Totp.wireName),
                                    serverMessage = "Verify",
                                ),
                        ),
                    )
                },
            )
        assertTrue(fallbackHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(2, srv2Calls)
        assertFalse(fallbackHarness.session.uiState.value.resolving)
        val fallbackMode = assertIs<RaftBrowserSignInSheetMode.ServerReady>(fallbackHarness.session.uiState.value.sheet?.mode)
        assertEquals(listOf("srv-1", "srv-2"), fallbackMode.readyServers.map { it.id })
        assertEquals("srv-1", fallbackMode.selectedServerId)
    }

    @Test
    fun resolvingStateTracksLifecycleAcrossFetchLookupCompletionDropAndRefusal() = runTest {
        val fetchGate = CompletableDeferred<RaftAuthorizeResponse?>()
        val lookupGate = CompletableDeferred<RaftBrowserSignInLookupResult>()
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = { fetchGate.await() },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") lookupGate.await() else RaftBrowserSignInLookupResult.NotAvailable
                },
            )

        assertFalse(h.session.uiState.value.resolving)
        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        assertTrue(h.session.uiState.value.resolving)
        assertNull(h.session.uiState.value.sheet)
        assertNull(h.session.uiState.value.declinedAuthorizeUrl)
        assertNull(h.session.uiState.value.declinedReason)

        // Duplicate tap while resolving is dropped as Pending without setting declined notice or clearing resolving.
        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        assertTrue(h.session.uiState.value.resolving)
        assertNull(h.session.uiState.value.declinedAuthorizeUrl)
        assertNull(h.session.uiState.value.declinedReason)

        fetchGate.complete(RaftAuthorizeResponse(302, setupLocation))
        runCurrent()
        assertTrue(h.session.uiState.value.resolving)

        lookupGate.complete(RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-1")))
        runCurrent()
        assertFalse(h.session.uiState.value.resolving)
        assertNotNull(h.session.uiState.value.sheet)

        // Dropping while resolving clears resolving immediately.
        val dropFetchGate = CompletableDeferred<RaftAuthorizeResponse?>()
        val dropHarness =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = { dropFetchGate.await() },
            )
        assertTrue(dropHarness.session.onMainFrameNavigation(pageAsk()))
        assertTrue(dropHarness.session.uiState.value.resolving)
        dropHarness.session.onPageCommitted()
        assertFalse(dropHarness.session.uiState.value.resolving)
        assertNull(dropHarness.session.uiState.value.declinedAuthorizeUrl)

        // Refusing while resolving clears resolving and records ServerRefused.
        val refuseHarness =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = { RaftAuthorizeResponse(400, null) },
            )
        assertTrue(refuseHarness.session.onMainFrameNavigation(pageAsk()))
        assertTrue(refuseHarness.session.uiState.value.resolving)
        runCurrent()
        assertFalse(refuseHarness.session.uiState.value.resolving)
        assertEquals(authorizeUrl, refuseHarness.session.uiState.value.declinedAuthorizeUrl)
        assertEquals(RaftBrowserSignInDropReason.ServerRefused, refuseHarness.session.uiState.value.declinedReason)

        // Consent-skip keeps resolving=true until approve completes, then clears resolving=false.
        val approveGate = CompletableDeferred<DataResult<RaftBrowserSignInApproveOutcome>>()
        val skipHarness =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") {
                        RaftBrowserSignInLookupResult.Found(
                            readyClient(serverId = "srv-1", consent = RaftSignInConsent(granted = true, coversRequested = true)),
                        )
                    } else {
                        RaftBrowserSignInLookupResult.NotAvailable
                    }
                },
                approveHandler = { approveGate.await() },
            )
        assertTrue(skipHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertTrue(skipHarness.session.uiState.value.resolving)
        approveGate.complete(DataResult.Content(RaftBrowserSignInApproveOutcome.Approved(code = "done-1", returnUrl = appReturnUrl)))
        runCurrent()
        assertFalse(skipHarness.session.uiState.value.resolving)
        assertNull(skipHarness.session.uiState.value.sheet)
    }

    @Test
    fun cooldownDeclinedReasonUsesCooldownCopyAndPreservesTenSecondGuard() = runTest {
        assertEquals(10_000L, RAFT_SIGN_IN_RAISE_AGAIN_AFTER_MS)
        var fetchCalls = 0
        val h =
            Harness(
                scope = this,
                account = defaultAccount(),
                fetchHandler = {
                    fetchCalls += 1
                    RaftAuthorizeResponse(302, setupLocation)
                },
                lookupHandler = { _, serverId, _ ->
                    if (serverId == "srv-1") RaftBrowserSignInLookupResult.Found(readyClient(serverId = "srv-1"))
                    else RaftBrowserSignInLookupResult.NotAvailable
                },
            )

        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertNotNull(h.session.uiState.value.sheet)
        h.session.cancelSheet()
        assertNull(h.session.uiState.value.sheet)
        assertFalse(h.session.uiState.value.resolving)

        // Retrying 8.3 s later (< 10 s cooldown) declines with RaisedRecently and maps to the cooldown string.
        advanceTimeBy(8_300L)
        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(1, fetchCalls)
        assertFalse(h.session.uiState.value.resolving)
        assertEquals(authorizeUrl, h.session.uiState.value.declinedAuthorizeUrl)
        assertEquals(RaftBrowserSignInDropReason.RaisedRecently, h.session.uiState.value.declinedReason)
        assertEquals(
            Res.string.in_app_browser_sign_in_declined_recently,
            raftBrowserSignInDeclinedStringRes(h.session.uiState.value.declinedReason),
        )
        // Non-cooldown reasons continue to use the general declined string.
        listOf(
            null,
            RaftBrowserSignInDropReason.SignedOut,
            RaftBrowserSignInDropReason.ServerRefused,
            RaftBrowserSignInDropReason.UnregisteredReturn,
            RaftBrowserSignInDropReason.OtherSite,
        ).forEach { reason ->
            assertEquals(
                Res.string.in_app_browser_sign_in_declined,
                raftBrowserSignInDeclinedStringRes(reason),
                "reason=$reason",
            )
        }

        // Dismissing clears both declinedAuthorizeUrl and declinedReason together.
        h.session.dismissDeclinedNotice()
        assertNull(h.session.uiState.value.declinedAuthorizeUrl)
        assertNull(h.session.uiState.value.declinedReason)

        // Still blocked at 9,999 ms; cleared on page commit; allowed once 10,000 ms have elapsed.
        advanceTimeBy(1_699L)
        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        assertEquals(RaftBrowserSignInDropReason.RaisedRecently, h.session.uiState.value.declinedReason)
        h.session.onPageCommitted()
        assertNull(h.session.uiState.value.declinedAuthorizeUrl)
        assertNull(h.session.uiState.value.declinedReason)

        advanceTimeBy(2L)
        assertTrue(h.session.onMainFrameNavigation(pageAsk()))
        runCurrent()
        assertEquals(2, fetchCalls)
        assertNotNull(h.session.uiState.value.sheet)
        assertNull(h.session.uiState.value.declinedReason)
    }

    @Test
    fun batchLookupCompletesAccountAndServerFlowsInOneRequestWithZeroFallbackLookups() = runTest {
        // 1. Account-mode consent-skip across 20 servers completes in 1 batch lookup request.
        val accountLookupQueries = mutableListOf<Map<String, String>>()
        val accountApprovePaths = mutableListOf<String>()
        val accountNetwork =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse =
                    when (path) {
                        "oauth/clients/lookup" -> {
                            val map = query.toMap()
                            accountLookupQueries += map
                            val ids = assertNotNull(map["server_ids"]).split(",")
                            val entries =
                                ids.joinToString(",") { id ->
                                    when (id) {
                                        "srv-7", "srv-12" ->
                                            """{"serverId":"$id","status":200,"body":{"clientId":"stamp","name":"Stamp","returnUrl":"$appReturnUrl","availability":"ready","loginMode":"account","consent":{"granted":true,"coversRequested":true}}}"""
                                        "srv-19" ->
                                            """{"serverId":"$id","status":500,"body":{"error":"Failed to load OAuth client","code":"lookup_failed"}}"""
                                        else ->
                                            """{"serverId":"$id","status":403,"body":{"error":"This app isn't available to you.","code":"app_not_available"}}"""
                                    }
                                }
                            HomeNetworkResponse(code = 200, bodyText = """{"results":[$entries]}""")
                        }
                        "oauth/authorize/account" -> {
                            accountApprovePaths += path
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"code":"batch-acct-1","returnUrl":"$appReturnUrl","scopes":["openid","profile"]}""",
                            )
                        }
                        else -> error("unexpected path $path")
                    }
            }
        val acctHarness =
            Harness(
                scope = this,
                account = manyServersAccount(20, currentServerId = "srv-5"),
                preferredServerId = "srv-5",
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                apiOverride = NetworkRaftBrowserSignInApi(accountNetwork),
            )
        assertTrue(acctHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()

        assertEquals(1, accountLookupQueries.size)
        val acctSentIds = assertNotNull(accountLookupQueries.single()["server_ids"]).split(",")
        assertEquals(20, acctSentIds.size)
        assertEquals("srv-5", acctSentIds.first())
        assertFalse("server_id" in accountLookupQueries.single())
        assertEquals(listOf("oauth/authorize/account"), accountApprovePaths)
        assertFalse(acctHarness.session.uiState.value.resolving)
        assertNull(acctHarness.session.uiState.value.sheet)
        assertEquals(listOf("Stamp"), acctHarness.toasts)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&code=batch-acct-1&state=secret-state-xyz"),
            acctHarness.loadedUrls,
        )

        // 2. Server-mode skip fallback (403 step-up) opens complete picker in original order with ZERO extra lookups.
        var serverLookupCalls = 0
        val serverNetwork =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse =
                    when (path) {
                        "oauth/clients/lookup" -> {
                            serverLookupCalls += 1
                            val ids = assertNotNull(query.toMap()["server_ids"]).split(",")
                            val entries =
                                ids.joinToString(",") { id ->
                                    when (id) {
                                        "srv-5" ->
                                            """{"serverId":"$id","status":200,"body":{"clientId":"stamp","name":"Stamp","returnUrl":"$appReturnUrl","availability":"ready","consent":{"granted":true,"coversRequested":true}}}"""
                                        "srv-2" ->
                                            """{"serverId":"$id","status":200,"body":{"clientId":"stamp","name":"Stamp","returnUrl":"$appReturnUrl","availability":"ready"}}"""
                                        else ->
                                            """{"serverId":"$id","status":403,"body":{"error":"This app isn't available to you.","code":"app_not_available"}}"""
                                    }
                                }
                            HomeNetworkResponse(code = 200, bodyText = """{"results":[$entries]}""")
                        }
                        "oauth/authorize/human" ->
                            HomeNetworkResponse(
                                code = 403,
                                bodyText = null,
                                errorBodyText = """{"error":"Confirm it's you","code":"second_factor_step_up_required","methods":["totp"]}""",
                            )
                        else -> error("unexpected path $path")
                    }
            }
        val srvHarness =
            Harness(
                scope = this,
                account = manyServersAccount(20, currentServerId = "srv-5"),
                preferredServerId = "srv-5",
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                apiOverride = NetworkRaftBrowserSignInApi(serverNetwork),
            )
        assertTrue(srvHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()

        assertEquals(1, serverLookupCalls)
        assertFalse(srvHarness.session.uiState.value.resolving)
        val sheetMode = assertIs<RaftBrowserSignInSheetMode.ServerReady>(srvHarness.session.uiState.value.sheet?.mode)
        // Preserves original account server list order (srv-2 before srv-5) while selecting preferred srv-5.
        assertEquals(listOf("srv-2", "srv-5"), sheetMode.readyServers.map { it.id })
        assertEquals("srv-5", sheetMode.selectedServerId)
    }

    @Test
    fun batchLookupHttp400FallsBackToFastSingleServerLookupAndHintedSingleUsesServerIdDirectly() = runTest {
        var batchCalls = 0
        val startedSlowSingles = mutableSetOf<String>()
        val cancelledSingles = mutableSetOf<String>()
        val slowGate = CompletableDeferred<HomeNetworkResponse>()
        val olderServerNetwork =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse =
                    when (path) {
                        "oauth/clients/lookup" -> {
                            val map = query.toMap()
                            if ("server_ids" in map) {
                                batchCalls += 1
                                HomeNetworkResponse(
                                    code = 400,
                                    bodyText = null,
                                    errorBodyText = """{"code":"invalid_input","error":"serverId is required"}""",
                                )
                            } else {
                                val sid = assertNotNull(map["server_id"])
                                if (sid == "srv-7") {
                                    HomeNetworkResponse(
                                        code = 200,
                                        bodyText = """{"clientId":"stamp","name":"Stamp","returnUrl":"$appReturnUrl","availability":"ready","loginMode":"account","consent":{"granted":true,"coversRequested":true}}""",
                                    )
                                } else {
                                    startedSlowSingles += sid
                                    try {
                                        slowGate.await()
                                    } catch (cancelled: CancellationException) {
                                        cancelledSingles += sid
                                        throw cancelled
                                    }
                                }
                            }
                        }
                        "oauth/authorize/account" ->
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"code":"fallback-400-ok","returnUrl":"$appReturnUrl","scopes":["openid","profile"]}""",
                            )
                        else -> error("unexpected path $path")
                    }
            }
        val fallbackHarness =
            Harness(
                scope = this,
                account = manyServersAccount(20),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, setupLocation) },
                apiOverride = NetworkRaftBrowserSignInApi(olderServerNetwork),
            )
        assertTrue(fallbackHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()

        assertEquals(1, batchCalls)
        assertFalse(slowGate.isCompleted)
        assertTrue("srv-1" in cancelledSingles)
        assertEquals(startedSlowSingles, cancelledSingles)
        assertFalse(fallbackHarness.session.uiState.value.resolving)
        assertEquals(listOf("Stamp"), fallbackHarness.toasts)
        assertEquals(
            listOf("https://stamp.build/auth/callback?next=1&code=fallback-400-ok&state=secret-state-xyz"),
            fallbackHarness.loadedUrls,
        )

        // When serverHint narrows to 1 server, session calls single `server_id=` directly without ever calling `server_ids=`.
        val hintedQueries = mutableListOf<Map<String, String>>()
        val hintedNetwork =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    hintedQueries += query.toMap()
                    return HomeNetworkResponse(
                        code = 200,
                        bodyText = """{"clientId":"stamp","name":"Stamp","returnUrl":"$appReturnUrl","availability":"ready"}""",
                    )
                }
            }
        val hintedHarness =
            Harness(
                scope = this,
                account = manyServersAccount(20),
                preferredServerId = "srv-1",
                fetchHandler = { RaftAuthorizeResponse(302, "$setupLocation&server=server-12") },
                apiOverride = NetworkRaftBrowserSignInApi(hintedNetwork),
            )
        assertTrue(hintedHarness.session.onMainFrameNavigation(pageAsk()))
        runCurrent()

        assertEquals(1, hintedQueries.size)
        assertEquals("srv-12", hintedQueries.single()["server_id"])
        assertFalse("server_ids" in hintedQueries.single())
        val hintedMode = assertIs<RaftBrowserSignInSheetMode.ServerReady>(hintedHarness.session.uiState.value.sheet?.mode)
        assertEquals(listOf("srv-12"), hintedMode.readyServers.map { it.id })
        assertEquals("srv-12", hintedMode.selectedServerId)
    }
}
