package build.raft.app

import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthCall
import build.raft.app.data.RuntimeAuthLoginRegion
import build.raft.app.data.RuntimeAuthLogoutResult
import build.raft.app.data.RuntimeAuthProvider
import build.raft.app.data.RuntimeAuthProviderConnectResult
import build.raft.app.data.RuntimeAuthProviderEndpoint
import build.raft.app.data.RuntimeAuthProviderEndpointOption
import build.raft.app.data.RuntimeAuthProviderGroup
import build.raft.app.data.RuntimeAuthProvidersResult
import build.raft.app.data.RuntimeAuthRequestError
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthUnavailableReason
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RUNTIME_INSTALL_COMMANDS
import build.raft.app.resources.Res
import build.raft.app.resources.settings_computers_runtime_auth_dsh_credentials
import build.raft.app.model.RuntimeAuthAccountLabel
import build.raft.app.model.RuntimeAuthLoginEventPushed
import build.raft.app.model.RuntimeAuthLoginOutcome
import build.raft.app.model.RuntimeAuthLoginResultPushed
import build.raft.app.model.RuntimeAuthLoginStep
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
        // DeepSeek Harness has no sign-in (slock #15549): a missing CLI as for any runtime, else how its key is set, for every viewer.
        val dshNote = ComputerRuntimeAuthAction.CredentialsNote(Res.string.settings_computers_runtime_auth_dsh_credentials)
        assertEquals(dshNote, action(RuntimeAuthRuntime.Dsh, RuntimeAuthStatus.Unknown))
        assertEquals(dshNote, action(RuntimeAuthRuntime.Dsh, null))
        assertEquals(dshNote, action(RuntimeAuthRuntime.Dsh, RuntimeAuthStatus.Unknown, VIEW.copy(canAct = false)), "not only a viewer who may act")
        assertEquals(dshNote, action(RuntimeAuthRuntime.Dsh, null, VIEW.copy(availability = RuntimeAuthAvailability.ComputerUpgradeRequired)), "no sign-in to upgrade for")
        assertEquals(
            ComputerRuntimeAuthAction.Unavailable(RuntimeAuthUnavailableReason.NotInstalled),
            action(RuntimeAuthRuntime.Dsh, RuntimeAuthStatus.Unavailable(RuntimeAuthUnavailableReason.NotInstalled)),
        )
        assertTrue(RuntimeAuthRuntime.Dsh.statusOnly)
        assertEquals(RuntimeAuthRuntime.Dsh, RuntimeAuthRuntime.forRaftRuntime("dsh"))
        assertEquals("npm install -g @deepseek-ai/dsh", RUNTIME_INSTALL_COMMANDS["dsh"])
        assertEquals(
            ComputerRuntimeAuthAction.Unavailable(RuntimeAuthUnavailableReason.NotInstalled),
            action(RuntimeAuthRuntime.Claude, RuntimeAuthStatus.Unavailable(RuntimeAuthUnavailableReason.NotInstalled)),
        )
        assertEquals(ComputerRuntimeAuthAction.AddProvider(primary = true), action(RuntimeAuthRuntime.Pi, RuntimeAuthStatus.LoggedOut()))
        assertEquals(ComputerRuntimeAuthAction.AddProvider(primary = false), action(RuntimeAuthRuntime.Pi, RuntimeAuthStatus.LoggedIn()))
        // Qwen Code: ConnectProvider (pink when unconfigured, quiet when configured), never orange chip, no sign-out.
        assertEquals(RuntimeAuthRuntime.Qwen, computerRuntimeCardAuthRuntime("qwen"))
        assertEquals(ComputerRuntimeAuthAction.ConnectProvider(primary = true), action(RuntimeAuthRuntime.Qwen, RuntimeAuthStatus.LoggedOut()))
        assertEquals(ComputerRuntimeAuthAction.ConnectProvider(primary = true), action(RuntimeAuthRuntime.Qwen, RuntimeAuthStatus.Unknown))
        assertEquals(ComputerRuntimeAuthAction.ConnectProvider(primary = true), action(RuntimeAuthRuntime.Qwen, null))
        assertEquals(ComputerRuntimeAuthAction.ConnectProvider(primary = false), action(RuntimeAuthRuntime.Qwen, RuntimeAuthStatus.LoggedIn()))
        assertEquals(
            ComputerRuntimeAuthAction.Unavailable(RuntimeAuthUnavailableReason.NotInstalled),
            action(RuntimeAuthRuntime.Qwen, RuntimeAuthStatus.Unavailable(RuntimeAuthUnavailableReason.NotInstalled)),
        )
        assertEquals(
            ComputerRuntimeAuthAction.Caption(notAttached = true),
            action(RuntimeAuthRuntime.Qwen, null, VIEW.copy(availability = RuntimeAuthAvailability.ComputerNotAttached)),
        )
        assertEquals(ComputerRuntimeAuthAction.Caption(notAttached = false), action(RuntimeAuthRuntime.Kimi, null), "a Computer that predates Kimi's sign-in")
        assertEquals(
            ComputerRuntimeAuthAction.Caption(notAttached = true),
            action(RuntimeAuthRuntime.Codex, null, VIEW.copy(availability = RuntimeAuthAvailability.ComputerNotAttached)),
        )
        assertNull(action(RuntimeAuthRuntime.Codex, null, VIEW.copy(availability = RuntimeAuthAvailability.ComputerOffline)))
        assertNull(action(RuntimeAuthRuntime.Codex, RuntimeAuthStatus.LoggedOut(), VIEW.copy(canAct = false)), "a viewer who may not act")
        // Signed out turns the chip orange; Pi and Qwen do not.
        assertTrue(ComputerRuntimeAuthCardState("codex", RuntimeAuthRuntime.Codex, VIEW.copy(status = mapOf(RuntimeAuthRuntime.Codex to RuntimeAuthStatus.LoggedOut()))).signedOut)
        assertTrue(!ComputerRuntimeAuthCardState("pi", RuntimeAuthRuntime.Pi, VIEW.copy(status = mapOf(RuntimeAuthRuntime.Pi to RuntimeAuthStatus.LoggedOut()))).signedOut)
        assertTrue(!ComputerRuntimeAuthCardState("qwen", RuntimeAuthRuntime.Qwen, VIEW.copy(status = mapOf(RuntimeAuthRuntime.Qwen to RuntimeAuthStatus.LoggedOut()))).signedOut)
    }

    @Test
    fun qwenProviderConnectSessionValidatesResetsOnBackMapsFieldErrorsAndRefreshesView() = runTest {
        val choiceProvider =
            RuntimeAuthProvider(
                id = "bailian",
                label = "Alibaba Cloud Model Studio",
                group = RuntimeAuthProviderGroup("alibaba", "Alibaba Cloud"),
                configured = true,
                endpoint =
                    RuntimeAuthProviderEndpoint.Choice(
                        listOf(
                            RuntimeAuthProviderEndpointOption("cn", "China", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                            RuntimeAuthProviderEndpointOption("intl", "International", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
                        )
                    ),
            )
        val customProvider =
            RuntimeAuthProvider(
                id = "custom-openai",
                label = "OpenAI-compatible",
                group = RuntimeAuthProviderGroup("custom"),
                endpoint = RuntimeAuthProviderEndpoint.Custom("https://api.example.com/v1"),
                needsModelIds = true,
            )
        val ops =
            FakeAuthOps(
                providers = RuntimeAuthCall.Done(RuntimeAuthProvidersResult.Listed(listOf(choiceProvider, customProvider))),
            )
        val controller = controller(RuntimeAuthRuntime.Qwen, ops)
        controller.openSignIn()
        runCurrent()
        val dialog = assertIs<ComputerRuntimeAuthDialog.ConnectProvider>(controller.dialog)
        val session = dialog.session
        assertEquals(ComputerRuntimeProviderConnectStep.Choose, session.step)
        assertEquals(2, assertIs<ComputerRuntimePiProviderList.Listed>(session.list).providers.size)

        // Choice endpoint provider requires endpoint + apiKey.
        session.choose("bailian")
        session.continueWithChoice()
        assertEquals(ComputerRuntimeProviderConnectStep.Details, session.step)
        assertTrue(!session.ready)
        session.apiKeyChanged("sk-secret-1")
        assertTrue(!session.ready, "choice endpoint still missing")
        session.endpointChanged("intl")
        assertTrue(session.ready)

        // Back resets form + key while keeping chosenId.
        session.back()
        assertEquals(ComputerRuntimeProviderConnectStep.Choose, session.step)
        assertEquals("bailian", session.chosenId)
        assertEquals("", session.apiKey)
        assertNull(session.endpointId)

        // Switch to custom provider requiring baseUrl + model + apiKey.
        session.choose("custom-openai")
        session.continueWithChoice()
        session.baseUrlChanged("bad-url")
        session.modelChanged("qwen3-coder")
        session.apiKeyChanged("sk-secret-2")
        assertTrue(session.ready)

        // 400 field errors map directly to their fields.
        ops.connectResult = RuntimeAuthCall.Refused(RuntimeAuthRequestError(400, "invalid_base_url", TRACE))
        session.connect()
        runCurrent()
        assertEquals(ComputerRuntimeProviderField.BaseUrl, session.fieldError)
        assertNull(session.failure)
        session.baseUrlChanged("https://api.example.com/v1")
        assertNull(session.fieldError)

        ops.connectResult = RuntimeAuthCall.Refused(RuntimeAuthRequestError(400, "invalid_model", TRACE))
        session.connect()
        runCurrent()
        assertEquals(ComputerRuntimeProviderField.Model, session.fieldError)
        session.modelChanged("qwen-coder-plus")
        assertNull(session.fieldError)

        ops.connectResult = RuntimeAuthCall.Refused(RuntimeAuthRequestError(400, "invalid_api_key", TRACE))
        session.connect()
        runCurrent()
        assertEquals(ComputerRuntimeProviderField.ApiKey, session.fieldError)
        session.apiKeyChanged("sk-valid-key")
        assertNull(session.fieldError)

        // Timed out triggers view refresh and shows warning banner.
        ops.connectResult = RuntimeAuthCall.Done(RuntimeAuthProviderConnectResult.Failed("timed_out"))
        val viewsBeforeTimeout = ops.calls.count { it == "view" }
        session.connect()
        runCurrent()
        assertEquals(ComputerRuntimeAuthProblem.ProviderConnectTimedOut, session.failure?.problem)
        assertEquals(viewsBeforeTimeout + 1, ops.calls.count { it == "view" })

        // Connected clears key, transitions to Done, and refreshes view.
        ops.connectResult = RuntimeAuthCall.Done(RuntimeAuthProviderConnectResult.Connected)
        session.connect()
        runCurrent()
        assertEquals(ComputerRuntimeProviderConnectStep.Done, session.step)
        assertEquals("", session.apiKey, "key cleared once saved")
        assertEquals(
            "connect qwen custom-openai baseUrl=https://api.example.com/v1 models=[qwen-coder-plus]",
            ops.calls.last { it.startsWith("connect ") },
        )
        assertTrue(ops.calls.none { "sk-valid-key" in it }, "key is never logged in call records")
        assertEquals(viewsBeforeTimeout + 2, ops.calls.count { it == "view" })

        controller.closeDialog()
        runCurrent()
        assertNull(controller.dialog)

        // Closing or disposing mid-entry clears apiKey immediately.
        controller.openSignIn()
        runCurrent()
        val midEntry = assertIs<ComputerRuntimeAuthDialog.ConnectProvider>(controller.dialog).session
        midEntry.choose("custom-openai")
        midEntry.continueWithChoice()
        midEntry.apiKeyChanged("sk-unsent-secret")
        controller.closeDialog()
        assertEquals("", midEntry.apiKey, "closing mid-entry drops unsent apiKey")

        controller.openSignIn()
        runCurrent()
        val disposedEntry = assertIs<ComputerRuntimeAuthDialog.ConnectProvider>(controller.dialog).session
        disposedEntry.choose("custom-openai")
        disposedEntry.continueWithChoice()
        disposedEntry.apiKeyChanged("sk-disposed-secret")
        controller.dispose()
        assertEquals("", disposedEntry.apiKey, "disposing mid-entry drops unsent apiKey")
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
    fun aSignInThatSucceedsAsksTheCardToReadItsUsageAgain() = runTest {
        // Web `RuntimeSignInDialog onSignedIn` (slock #15786): only a login that ended signed in.
        val ops = FakeAuthOps()
        val controller = controller(RuntimeAuthRuntime.Codex, ops)
        var reads = 0
        controller.onSignedIn = { reads++ }
        controller.openSignIn()
        ops.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()
        controller.onRealtime(result(LOGIN, RuntimeAuthLoginOutcome.Failed("timed_out")))
        runCurrent()
        assertEquals(0, reads, "a failed sign-in leaves the usage as it is")
        assertIs<ComputerRuntimeAuthDialog.SignIn>(controller.dialog).session.retry()
        runCurrent()
        controller.onRealtime(result(LOGIN, RuntimeAuthLoginOutcome.LoggedIn()))
        runCurrent()
        assertEquals(1, reads)
        controller.onSignedIn = null
    }

    @Test
    fun reopeningSignInCancelsPreviousRunningLogin() = runTest {
        val ops = FakeAuthOps()
        val controller = controller(RuntimeAuthRuntime.Antigravity, ops)
        controller.openSignIn()
        runCurrent()
        ops.start.complete(RuntimeAuthCall.Done(LOGIN))
        runCurrent()

        controller.openSignIn()
        assertTrue("cancel antigravity $LOGIN" in ops.calls)
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
        var connectResult: RuntimeAuthCall<RuntimeAuthProviderConnectResult> = RuntimeAuthCall.Done(RuntimeAuthProviderConnectResult.Connected)

        override suspend fun view(force: Boolean): RuntimeAuthView {
            calls += "view"
            return VIEW.copy(status = mapOf(RuntimeAuthRuntime.Codex to RuntimeAuthStatus.LoggedOut()))
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

        override suspend fun connect(
            runtime: RuntimeAuthRuntime,
            providerId: String,
            apiKey: String,
            baseUrl: String?,
            modelIds: List<String>?,
        ): RuntimeAuthCall<RuntimeAuthProviderConnectResult> {
            calls += "connect ${runtime.wire} $providerId" + (baseUrl?.let { " baseUrl=$it" } ?: "") + (modelIds?.let { " models=$it" } ?: "")
            return connectResult
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
                runtimes =
                    listOf(
                        RuntimeAuthRuntime.Claude,
                        RuntimeAuthRuntime.Codex,
                        RuntimeAuthRuntime.Qoder,
                        RuntimeAuthRuntime.Qwen,
                        RuntimeAuthRuntime.Antigravity,
                        RuntimeAuthRuntime.Pi,
                        RuntimeAuthRuntime.Grok,
                    ),
                logoutRuntimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex, RuntimeAuthRuntime.Pi, RuntimeAuthRuntime.Grok),
            )
    }
}
