package build.raft.app

import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthCall
import build.raft.app.data.RuntimeAuthLoginRegion
import build.raft.app.data.RuntimeAuthLogoutResult
import build.raft.app.data.RuntimeAuthOAuthMethod
import build.raft.app.data.RuntimeAuthProvider
import build.raft.app.data.RuntimeAuthProviderConnectResult
import build.raft.app.data.RuntimeAuthProviderKeyEntry
import build.raft.app.data.RuntimeAuthProviderOAuthFlow
import build.raft.app.data.RuntimeAuthProvidersResult
import build.raft.app.data.RuntimeAuthRequestError
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthSignedInProvider
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthUnavailableReason
import build.raft.app.data.RuntimeAuthView
import build.raft.app.model.RuntimeAuthAccountLabel
import build.raft.app.model.RuntimeAuthLoginEventPushed
import build.raft.app.model.RuntimeAuthLoginOutcome
import build.raft.app.model.RuntimeAuthLoginResultPushed
import build.raft.app.model.RuntimeAuthLoginStep
import build.raft.app.model.RuntimeAuthUpdated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The runtime card's sign-in, sign-out and Pi Add Provider against web RuntimeSignInDialog.tsx,
 * RuntimeSignOutDialog.tsx, PiSignInDialog.tsx and RuntimeUsageSurface.tsx `runtimeAuthAction`
 * (botiverse/slock staging 853c6998b).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComputerRuntimeAuthSessionTest {
    @Test
    fun eventsBeforeTheLoginIdAreReplayedOnlyForThatLogin() = runTest {
        val ops = FakeAuthOps()
        val session = signIn(RuntimeAuthRuntime.Codex, ops)
        session.open()
        runCurrent()
        assertEquals(listOf("start codex region=null provider=null"), ops.calls)
        // Arrive while the start request is still out.
        session.onRealtime(event(OTHER_LOGIN, RuntimeAuthLoginStep.Info("someone else's")))
        session.onRealtime(event(LOGIN, RuntimeAuthLoginStep.DeviceCode("ABCD-1234", "https://auth.openai.com/codex/device", 900)))
        session.onRealtime(event(LOGIN, RuntimeAuthLoginStep.Info("wrong computer"), machineId = "m2"))
        ops.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()
        val state = session.state
        assertTrue(state.started)
        assertEquals("ABCD-1234", state.deviceCode?.userCode)
        assertNull(state.info, "another login's and another Computer's events are dropped")
        session.onRealtime(result(LOGIN, RuntimeAuthLoginOutcome.LoggedIn(RuntimeAuthAccountLabel(maskedLabel = "a***@x.com"))))
        runCurrent()
        assertTrue(session.state.done)
        assertEquals("a***@x.com", session.state.account?.text)
        assertTrue("invalidate" in ops.calls, "the card reads the new status")
        session.close()
        runCurrent()
        assertTrue(ops.calls.none { it.startsWith("cancel") }, "an ended login is not cancelled")
    }

    @Test
    fun closingCancelsARunningLoginAndOneThatStartsAfterward() = runTest {
        val ops = FakeAuthOps()
        val running = signIn(RuntimeAuthRuntime.Claude, ops)
        running.open()
        ops.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()
        running.close()
        runCurrent()
        assertEquals("cancel claude $LOGIN", ops.calls.last())

        val late = FakeAuthOps()
        val closedEarly = signIn(RuntimeAuthRuntime.Claude, late)
        closedEarly.open()
        runCurrent()
        closedEarly.close()
        late.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()
        assertEquals("cancel claude $LOGIN", late.calls.last(), "closed while starting: the login began anyway, so it ends")
    }

    /**
     * The card's scope is cancelled as it leaves composition (an account or server switch, the
     * activity recreated, the card scrolled away): the login still ends on the Computer, whether it
     * was running or still starting, instead of holding the runtime for the login's 20 min TTL.
     */
    @Test
    fun leavingThePageEndsTheLoginOnTheComputer() = runTest {
        val running = FakeAuthOps()
        val runningCard = CoroutineScope(coroutineContext + Job())
        val runningSession = ComputerRuntimeSignInSession("s1", "m1", RuntimeAuthRuntime.Codex, provider = null, ops = running, scope = runningCard)
        runningSession.open()
        running.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()
        runningCard.cancel()
        runningSession.close()
        runCurrent()
        assertEquals("cancel codex $LOGIN", running.calls.last(), "a running login")

        val starting = FakeAuthOps()
        val startingCard = CoroutineScope(coroutineContext + Job())
        val startingSession = ComputerRuntimeSignInSession("s1", "m1", RuntimeAuthRuntime.Codex, provider = null, ops = starting, scope = startingCard)
        startingSession.open()
        runCurrent()
        startingCard.cancel()
        startingSession.close()
        starting.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()
        assertEquals("cancel codex $LOGIN", starting.calls.last(), "a login whose id arrives after the card left")

        val disposed = FakeAuthOps()
        val disposedCard = CoroutineScope(coroutineContext + Job())
        val controller = ComputerRuntimeAuthController("s1", "m1", "grok", RuntimeAuthRuntime.Grok, disposed, disposedCard)
        controller.openSignIn()
        disposed.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()
        disposedCard.cancel()
        controller.dispose()
        runCurrent()
        assertEquals("cancel grok $LOGIN", disposed.calls.last(), "the controller's dispose")
    }

    @Test
    fun kimiAsksTheRegionFirstAndTryAgainKeepsIt() = runTest {
        val ops = FakeAuthOps()
        val session = signIn(RuntimeAuthRuntime.Kimi, ops)
        session.open()
        runCurrent()
        assertTrue(session.state.choosingRegion)
        assertTrue(ops.calls.isEmpty(), "nothing starts until the person chooses")
        session.continueWithRegion()
        assertTrue(ops.calls.isEmpty(), "no region chosen, no start")
        session.chooseRegion(RuntimeAuthLoginRegion.Global)
        session.continueWithRegion()
        ops.start.complete(RuntimeAuthCall.Refused(RuntimeAuthRequestError(409, "computer_offline", TRACE)))
        runCurrent()
        assertEquals(ComputerRuntimeAuthFailure(ComputerRuntimeAuthProblem.ComputerOffline, retryable = true, traceId = TRACE), session.state.failure)
        session.retry()
        runCurrent()
        assertEquals(listOf("start kimi region=global provider=null", "start kimi region=global provider=null"), ops.calls)
    }

    @Test
    fun startRefusalsSayWhetherTryingAgainCanHelp() {
        fun failure(status: Int?, code: String?, runtime: RuntimeAuthRuntime = RuntimeAuthRuntime.Claude) =
            RuntimeAuthRequestError(status, code).signInStartFailure(runtime).let { it.problem to it.retryable }
        assertEquals(ComputerRuntimeAuthProblem.UpgradeRequired to false, failure(409, "computer_upgrade_required"))
        assertEquals(ComputerRuntimeAuthProblem.NotAttached to false, failure(409, "computer_not_attached"))
        assertEquals(ComputerRuntimeAuthProblem.Busy to true, failure(409, "login_in_progress"))
        assertEquals(ComputerRuntimeAuthProblem.PiBusy to true, failure(409, "login_in_progress", RuntimeAuthRuntime.Pi))
        assertEquals(ComputerRuntimeAuthProblem.OpencodeBusy to true, failure(409, "login_in_progress", RuntimeAuthRuntime.Opencode))
        assertEquals(ComputerRuntimeAuthProblem.RateLimited to true, failure(429, "rate_limited"))
        assertEquals(ComputerRuntimeAuthProblem.Forbidden to false, failure(403, null))
        assertEquals(ComputerRuntimeAuthProblem.NotFound to false, failure(404, "runtime_auth_not_offered"))
        assertEquals(ComputerRuntimeAuthProblem.StartFailed to true, failure(500, null))
        assertEquals(
            ComputerRuntimeAuthProblem.InteractionFailed to false,
            RuntimeAuthLoginOutcome.Failed("interaction_failed").signInResultFailure(RuntimeAuthRuntime.Claude)!!.let { it.problem to it.retryable },
        )
        assertEquals(
            ComputerRuntimeAuthProblem.VersionUnsupported,
            RuntimeAuthLoginOutcome.Unsupported("version_unsupported").signInResultFailure(RuntimeAuthRuntime.Claude)?.problem,
        )
        // OpenCode: another way, not a retry; OpenCode 1 says to upgrade to OpenCode 2.
        assertEquals(
            ComputerRuntimeAuthProblem.LoopbackRequired to false,
            RuntimeAuthLoginOutcome.Failed("loopback_required").signInResultFailure(RuntimeAuthRuntime.Opencode)!!.let { it.problem to it.retryable },
        )
        assertEquals(
            ComputerRuntimeAuthProblem.OpencodeVersionUnsupported,
            RuntimeAuthLoginOutcome.Unsupported("version_unsupported").signInResultFailure(RuntimeAuthRuntime.Opencode)?.problem,
        )
        assertEquals(ComputerRuntimeAuthProblem.SignInInProgress, RuntimeAuthLogoutResult.Failed("busy").signOutProblem(RuntimeAuthRuntime.Opencode))
    }

    @Test
    fun aPastedCodeIsSentOnceAndARefusedOneAskedAgain() = runTest {
        val ops = FakeAuthOps()
        val session = signIn(RuntimeAuthRuntime.Claude, ops)
        session.open()
        ops.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()
        session.onRealtime(event(LOGIN, RuntimeAuthLoginStep.Info("for Codex, not Claude")))
        assertNull(session.state.info, "another runtime's push is dropped")
        session.onRealtime(event(LOGIN, RuntimeAuthLoginStep.AuthUrl("https://claude.ai/oauth/authorize"), runtime = "claude"))
        session.onRealtime(event(LOGIN, RuntimeAuthLoginStep.CodePrompt(), runtime = "claude"))
        assertTrue(!session.state.codeEntered, "Open Sign-in Page leads until a code is pasted")
        session.codeChanged("  code#state \n")
        assertTrue(session.state.codeEntered)
        ops.code = RuntimeAuthCall.Refused(RuntimeAuthRequestError(400, "invalid_code"))
        session.submitCode()
        runCurrent()
        assertEquals(ComputerRuntimeSignInCodeState.Invalid, session.state.codeState)
        assertTrue(session.state.codeRejected)
        assertEquals("code claude $LOGIN code#state", ops.calls.last(), "trimmed")
        ops.code = RuntimeAuthCall.Done(Unit)
        session.submitCode()
        runCurrent()
        assertEquals(ComputerRuntimeSignInCodeState.Sent, session.state.codeState)
        assertEquals("", session.state.code, "a sent code is not kept")
        // The runtime did not take it: it prompts again, and the field says so.
        session.onRealtime(event(LOGIN, RuntimeAuthLoginStep.CodePrompt(retry = true), runtime = "claude"))
        assertTrue(session.state.codeRejected)
    }

    @Test
    fun theCardOffersWhatTheWebOffers() {
        fun action(runtime: RuntimeAuthRuntime, status: RuntimeAuthStatus?, view: RuntimeAuthView = VIEW) =
            ComputerRuntimeAuthCardState(runtime.wire, runtime, view.copy(status = listOfNotNull(status?.let { runtime to it }).toMap())).action()
        assertEquals(ComputerRuntimeAuthAction.SignIn, action(RuntimeAuthRuntime.Codex, null), "not reported yet")
        assertEquals(ComputerRuntimeAuthAction.SignIn, action(RuntimeAuthRuntime.Codex, RuntimeAuthStatus.Unknown))
        assertEquals(ComputerRuntimeAuthAction.SignOut, action(RuntimeAuthRuntime.Grok, RuntimeAuthStatus.LoggedIn()))
        assertNull(action(RuntimeAuthRuntime.Qoder, RuntimeAuthStatus.LoggedIn()), "Qoder has no sign-out")
        assertNull(action(RuntimeAuthRuntime.Antigravity, RuntimeAuthStatus.LoggedIn()), "a Computer that signs Antigravity in but not out")
        assertEquals(
            ComputerRuntimeAuthAction.Unavailable(RuntimeAuthUnavailableReason.NotInstalled),
            action(RuntimeAuthRuntime.Claude, RuntimeAuthStatus.Unavailable(RuntimeAuthUnavailableReason.NotInstalled)),
        )
        assertEquals(ComputerRuntimeAuthAction.AddProvider(primary = true), action(RuntimeAuthRuntime.Pi, RuntimeAuthStatus.LoggedOut))
        assertEquals(ComputerRuntimeAuthAction.AddProvider(primary = false), action(RuntimeAuthRuntime.Pi, RuntimeAuthStatus.LoggedIn()))
        assertEquals(ComputerRuntimeAuthAction.Caption(notAttached = false), action(RuntimeAuthRuntime.Kimi, null), "a Computer that predates Kimi's sign-in")
        assertEquals(
            ComputerRuntimeAuthAction.Caption(notAttached = true),
            action(RuntimeAuthRuntime.Codex, null, VIEW.copy(availability = RuntimeAuthAvailability.ComputerNotAttached)),
        )
        assertNull(action(RuntimeAuthRuntime.Codex, null, VIEW.copy(availability = RuntimeAuthAvailability.ComputerOffline)))
        assertNull(action(RuntimeAuthRuntime.Codex, RuntimeAuthStatus.LoggedOut, VIEW.copy(canAct = false)), "a viewer who may not act")
        // Signed out turns the chip orange; Pi with no stored sign-in does not (a key may still serve it).
        assertTrue(ComputerRuntimeAuthCardState("codex", RuntimeAuthRuntime.Codex, VIEW.copy(status = mapOf(RuntimeAuthRuntime.Codex to RuntimeAuthStatus.LoggedOut))).signedOut)
        assertTrue(!ComputerRuntimeAuthCardState("pi", RuntimeAuthRuntime.Pi, VIEW.copy(status = mapOf(RuntimeAuthRuntime.Pi to RuntimeAuthStatus.LoggedOut))).signedOut)
    }

    @Test
    fun signOutKeepsTheDialogOpenWithWhyUntilItSignsOut() = runTest {
        val ops = FakeAuthOps(logout = RuntimeAuthCall.Done(RuntimeAuthLogoutResult.Failed("still_logged_in")))
        val controller = controller(RuntimeAuthRuntime.Grok, ops)
        controller.openSignOut()
        controller.confirmSignOut()
        runCurrent()
        val open = assertIs<ComputerRuntimeAuthDialog.SignOut>(controller.dialog)
        assertEquals(ComputerRuntimeAuthProblem.SignOutStillLoggedInGrok, open.state.problem, "the Computer's to fix, never \"try again\"")
        ops.logout = RuntimeAuthCall.Refused(RuntimeAuthRequestError(504, "computer_timeout", TRACE))
        controller.confirmSignOut()
        runCurrent()
        val timedOut = assertIs<ComputerRuntimeAuthDialog.SignOut>(controller.dialog).state
        assertEquals(ComputerRuntimeAuthProblem.SignOutTimedOut, timedOut.problem)
        assertEquals(TRACE, timedOut.traceId)
        ops.logout = RuntimeAuthCall.Done(RuntimeAuthLogoutResult.LoggedOut)
        controller.confirmSignOut()
        runCurrent()
        assertNull(controller.dialog)
        assertEquals(listOf("logout grok provider=null"), ops.calls.filter { it.startsWith("logout") }.distinct())
    }

    @Test
    fun aPiProviderSignsOutAloneAndNoProviderMeansNoRuntimeWideSignOut() = runTest {
        val ops = FakeAuthOps(logout = RuntimeAuthCall.Done(RuntimeAuthLogoutResult.LoggedOut))
        val controller = controller(RuntimeAuthRuntime.Pi, ops)
        controller.openSignOut()
        assertNull(controller.dialog, "Pi signs out per provider only")
        controller.openSignOut(RuntimeAuthSignedInProvider("deepseek", "DeepSeek", apiKey = true))
        controller.confirmSignOut()
        runCurrent()
        assertEquals("logout pi provider=deepseek", ops.calls.first { it.startsWith("logout") })
    }

    @Test
    fun anUpdateForThisComputerReadsTheStatusAgain() = runTest {
        val ops = FakeAuthOps()
        val controller = controller(RuntimeAuthRuntime.Codex, ops)
        controller.onRealtime(RuntimeAuthUpdated("m2"))
        runCurrent()
        assertTrue(ops.calls.isEmpty())
        controller.onRealtime(RuntimeAuthUpdated("m1"))
        runCurrent()
        assertEquals(listOf("invalidate", "view"), ops.calls)
        assertEquals(RuntimeAuthStatus.LoggedOut, controller.status)
        assertTrue(controller.signedOut)
    }

    @Test
    fun piAddProviderGoesOnToTheSignInForAnAccountAndKeepsALiteralKeyOnly() = runTest {
        val ops = FakeAuthOps(providers = RuntimeAuthCall.Done(RuntimeAuthProvidersResult.Listed(PI_PROVIDERS)))
        val controller = controller(RuntimeAuthRuntime.Pi, ops)
        controller.openSignIn()
        runCurrent()
        val pi = assertIs<ComputerRuntimeAuthDialog.AddProvider>(controller.dialog).session
        assertIs<ComputerRuntimePiProviderList.Listed>(pi.list)
        pi.choose(ComputerRuntimePiChoice(account = false, providerId = "deepseek"))
        pi.continueWithChoice()
        assertEquals(ComputerRuntimePiStep.Key, pi.step)
        pi.apiKeyChanged("!op read secret")
        assertTrue(pi.keyRefusedHere)
        pi.connect()
        runCurrent()
        assertTrue(ops.calls.none { it.startsWith("connect") }, "a key Pi would run is never sent")
        pi.apiKeyChanged(" sk-123 ")
        pi.connect()
        runCurrent()
        assertEquals("connect pi deepseek", ops.calls.last())
        assertEquals(ComputerRuntimePiStep.Done, pi.step)
        assertEquals("", pi.apiKey, "dropped once saved")

        pi.choose(ComputerRuntimePiChoice(account = true, providerId = "openai"))
        pi.continueWithChoice()
        val signIn = assertIs<ComputerRuntimeAuthDialog.SignIn>(controller.dialog).session
        assertEquals(ComputerRuntimeSignInProvider("openai", "ChatGPT Plus/Pro", pastesAddress = true), signIn.provider)
        runCurrent()
        assertEquals("start pi region=null provider=openai", ops.calls.last())
        ops.start.complete(RuntimeAuthCall.Done(LOGIN))
        controller.closeDialog()
        runCurrent()
        assertEquals("cancel pi $LOGIN", ops.calls.last(), "closing ends the provider's login")
    }

    @Test
    fun openCodeNamesTheChosenMethodAndGreysOutOneThatNeedsTheComputersBrowser() = runTest {
        ComputerRuntimeLoopbackRequired.clearForTests()
        val providers =
            listOf(
                RuntimeAuthProvider(
                    "github-copilot",
                    "GitHub Copilot",
                    oauthMethodList =
                        listOf(
                            RuntimeAuthOAuthMethod("Login with GitHub (device)", RuntimeAuthProviderOAuthFlow.DeviceCode),
                            RuntimeAuthOAuthMethod("Login with GitHub", RuntimeAuthProviderOAuthFlow.Link),
                        ),
                ),
            )
        val ops = FakeAuthOps(providers = RuntimeAuthCall.Done(RuntimeAuthProvidersResult.Listed(providers)))
        val controller = controller(RuntimeAuthRuntime.Opencode, ops)
        controller.openSignIn()
        runCurrent()
        val pi = assertIs<ComputerRuntimeAuthDialog.AddProvider>(controller.dialog).session
        pi.choose(ComputerRuntimePiChoice(account = true, providerId = "github-copilot", methodIndex = 1))
        pi.continueWithChoice()
        val signIn = assertIs<ComputerRuntimeAuthDialog.SignIn>(controller.dialog).session
        assertEquals(ComputerRuntimeSignInProvider("github-copilot", "GitHub Copilot", oauthMethod = "Login with GitHub"), signIn.provider)
        runCurrent()
        assertEquals("start opencode region=null provider=github-copilot method=Login with GitHub", ops.calls.last())
        ops.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()
        signIn.onRealtime(RuntimeAuthLoginResultPushed("s1", "m1", "opencode", LOGIN, RuntimeAuthLoginOutcome.Failed("loopback_required")))
        assertEquals(ComputerRuntimeAuthProblem.LoopbackRequired, signIn.state.failure?.problem)
        assertTrue(ComputerRuntimeLoopbackRequired.contains("m1", RuntimeAuthRuntime.Opencode, "github-copilot", "Login with GitHub"))
        // The same method is not started again from here this session.
        controller.closeDialog()
        controller.openSignIn()
        runCurrent()
        val again = assertIs<ComputerRuntimeAuthDialog.AddProvider>(controller.dialog).session
        again.choose(ComputerRuntimePiChoice(account = true, providerId = "github-copilot", methodIndex = 1))
        again.continueWithChoice()
        assertIs<ComputerRuntimeAuthDialog.AddProvider>(controller.dialog)
        // OpenCode's key rule: no {env:} / {file:} reference.
        again.choose(ComputerRuntimePiChoice(account = false, providerId = "github-copilot"))
        again.apiKeyChanged("{env:OPENAI_API_KEY}")
        assertTrue(again.keyRefusedHere)
        again.apiKeyChanged("!literal-for-opencode")
        assertTrue(!again.keyRefusedHere, "a leading ! is Pi's rule, not OpenCode's")
        ComputerRuntimeLoopbackRequired.clearForTests()
    }

    private fun TestScope.signIn(runtime: RuntimeAuthRuntime, ops: FakeAuthOps) =
        ComputerRuntimeSignInSession("s1", "m1", runtime, provider = null, ops = ops, scope = this)

    private fun TestScope.controller(runtime: RuntimeAuthRuntime, ops: FakeAuthOps) =
        ComputerRuntimeAuthController("s1", "m1", runtime.wire, runtime, ops, this)

    private fun event(loginId: String, step: RuntimeAuthLoginStep, machineId: String = "m1", runtime: String = "codex") =
        RuntimeAuthLoginEventPushed("s1", machineId, runtime, loginId, step)

    private fun result(loginId: String, outcome: RuntimeAuthLoginOutcome) = RuntimeAuthLoginResultPushed("s1", "m1", "codex", loginId, outcome)

    private class FakeAuthOps(
        var logout: RuntimeAuthCall<RuntimeAuthLogoutResult> = RuntimeAuthCall.Done(RuntimeAuthLogoutResult.LoggedOut),
        val providers: RuntimeAuthCall<RuntimeAuthProvidersResult> = RuntimeAuthCall.Done(RuntimeAuthProvidersResult.Listed(emptyList())),
    ) : ComputerRuntimeAuthOps {
        val calls = mutableListOf<String>()
        val start = CompletableDeferred<RuntimeAuthCall<String>>()
        var code: RuntimeAuthCall<Unit> = RuntimeAuthCall.Done(Unit)

        override suspend fun view(force: Boolean): RuntimeAuthView {
            calls += "view"
            return VIEW.copy(status = mapOf(RuntimeAuthRuntime.Codex to RuntimeAuthStatus.LoggedOut))
        }

        override suspend fun invalidate() {
            calls += "invalidate"
        }

        override suspend fun startLogin(runtime: RuntimeAuthRuntime, region: RuntimeAuthLoginRegion?, providerId: String?, oauthMethod: String?): RuntimeAuthCall<String> {
            calls += "start ${runtime.wire} region=${region?.wire} provider=$providerId" + (oauthMethod?.let { " method=$it" } ?: "")
            return start.await()
        }

        override suspend fun submitCode(runtime: RuntimeAuthRuntime, loginId: String, code: String): RuntimeAuthCall<Unit> {
            calls += "code ${runtime.wire} $loginId $code"
            return this.code
        }

        override fun cancelLogin(runtime: RuntimeAuthRuntime, loginId: String) {
            calls += "cancel ${runtime.wire} $loginId"
        }

        override suspend fun logout(runtime: RuntimeAuthRuntime, providerId: String?): RuntimeAuthCall<RuntimeAuthLogoutResult> {
            calls += "logout ${runtime.wire} provider=$providerId"
            return logout
        }

        override suspend fun providers(runtime: RuntimeAuthRuntime) = providers

        override suspend fun connect(runtime: RuntimeAuthRuntime, providerId: String, apiKey: String): RuntimeAuthCall<RuntimeAuthProviderConnectResult> {
            calls += "connect ${runtime.wire} $providerId"
            return RuntimeAuthCall.Done(RuntimeAuthProviderConnectResult.Connected)
        }
    }

    private companion object {
        const val LOGIN = "rtl_abcdefghijklmnop12"
        const val OTHER_LOGIN = "rtl_zzzzzzzzzzzzzzzz99"
        const val TRACE = "0123456789abcdef0123456789abcdef"
        val VIEW =
            RuntimeAuthView(
                canAct = true,
                availability = RuntimeAuthAvailability.Ok,
                runtimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex, RuntimeAuthRuntime.Qoder, RuntimeAuthRuntime.Antigravity, RuntimeAuthRuntime.Pi, RuntimeAuthRuntime.Grok),
                logoutRuntimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex, RuntimeAuthRuntime.Pi, RuntimeAuthRuntime.Grok),
            )
        val PI_PROVIDERS =
            listOf(
                RuntimeAuthProvider("openai", "OpenAI", oauthLabel = "ChatGPT Plus/Pro", oauthFlow = RuntimeAuthProviderOAuthFlow.PasteAddress),
                RuntimeAuthProvider("deepseek", "DeepSeek", apiKeyEntry = RuntimeAuthProviderKeyEntry.Key),
            )
    }
}
