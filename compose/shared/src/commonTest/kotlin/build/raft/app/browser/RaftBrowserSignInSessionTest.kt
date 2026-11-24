package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SecondFactorStepUpChallenge
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpIntent
import build.raft.app.data.SecondFactorStepUpMethod
import build.raft.app.data.SecondFactorStepUpResult
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
                    object : RaftBrowserSignInApi {
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
}
