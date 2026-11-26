package build.raft.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Continue in browser (iOS, only legacy passkeys): "Verify it's you" reaches no way to answer
 * after `passkey_rp_unavailable`, starts the web step-up, has the dialog open its page, takes the
 * page's callback for that request only, exchanges the code with the verifier and retries the
 * refused action once. With a code as well, the code stays and the same web step-up runs from
 * under its field (#158; the decision: SecondFactorStepUpOlderPasskeyTest); a passkey under the
 * current RP ID still opens the platform's sheet. Wire shapes: AppWebStepUpApiTest.
 */
class AppWebStepUpCoordinatorTest {
    private val passkeyOnly =
        """{"error":"Confirm it's you with your second step before attaching a computer.","code":"second_factor_step_up_required","methods":["passkey"]}"""
    /** An authenticator app (and its backup codes) as well as passkeys. */
    private val withCode =
        """{"error":"Confirm it's you with your second step before attaching a computer.","code":"second_factor_step_up_required","methods":["totp","passkey","backup_code"]}"""
    /** iOS's `current` group answered: a passkey under the current RP ID, older ones too. */
    private val currentOptions =
        """{"options":{"rpId":"app.raft.test","challenge":"Y2hhbGxlbmdlLXRva2Vu","allowCredentials":[{"id":"q83vEjRWeJA","type":"public-key"}],"timeout":300000,"userVerification":"required"},"rp":"current","otherRps":["legacy"]}"""
    private val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val returnUri = "raft://login/callback"
    private val verifier = "v".repeat(43)

    private class Harness(
        val startResults: ArrayDeque<AppWebStepUpStartResult>,
        val completeResults: ArrayDeque<AppWebStepUpCompleteResult> = ArrayDeque(listOf(AppWebStepUpCompleteResult.Verified)),
        val store: InMemoryRaftKeyValueStore = InMemoryRaftKeyValueStore(),
        webUrl: String? = null,
        action: String? = "computer_attach",
        /** The options call for iOS's `current` group; by default only older passkeys. */
        options: () -> SecondFactorPasskeyOptionsResult = {
            SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.PasskeyRpUnavailable("current", listOf("legacy")))
        },
    ) {
        var clock = 1_000L
        val starts = mutableListOf<AppWebStepUpStartBody>()
        val completes = mutableListOf<AppWebStepUpCompleteBody>()
        val logs = mutableListOf<String>()
        private var generations = 0
        val pending = AppWebStepUpPendingStore({ store }, now = { clock })
        val coordinator =
            SecondFactorStepUpCoordinator(
                stepUp = { error("no step-up answer: the web verifies this sign-in") },
                passkeyOptions = { options() },
                nativePasskeys = true,
                passkeyRpGroup = "current",
                webUrl = if (webUrl != null) { _ -> webUrl } else null,
                webTarget = { SecondFactorStepUpWebTarget.Computers.takeIf { webUrl != null } },
                webStepUp =
                    SecondFactorWebStepUp(
                        start = { body -> starts += body; startResults.removeFirst() },
                        complete = { body -> completes += body; completeResults.removeFirst() },
                        returnUri = "raft://login/callback",
                        pending = pending,
                        pkce = { AppWebStepUpPkce("v".repeat(43), "c".repeat(43)) },
                        generation = { "gen-${++generations}" },
                        log = logs::add,
                    ),
                webStepUpAction = action,
            )
    }

    private fun started(id: String) = AppWebStepUpStartResult.Started(id, "https://app.raft.build/login/app/verify?request=$id", "2026-11-19T10:05:00Z")

    private fun refusal(body: String = passkeyOnly): DataResult<String> =
        DataResult.Error(
            ScreenError(
                code = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
                retryable = false,
                secondFactorStepUp = parseSharedSecondFactorStepUpRequired(403, body)
            )
        )

    private class ScriptedAction(vararg results: DataResult<String>) {
        private val script = results.toList()
        var calls = 0
            private set

        suspend fun invoke(): DataResult<String> = script[minOf(calls++, script.lastIndex)]
    }

    /** Use passkey → `passkey_rp_unavailable`: no way to answer here, Continue in browser. */
    private suspend fun Harness.reachLegacyOnly(): SecondFactorStepUpDialogState {
        coordinator.accept(SecondFactorStepUpIntent.Submit)
        val dialog = assertNotNull(coordinator.state.value)
        assertNull(dialog.method)
        assertEquals(SecondFactorWebStepUpPhase.Ready, dialog.webStepUp)
        assertTrue(dialog.continuesInBrowser)
        assertFalse(dialog.opensOnWeb, "Continue in browser stands in for Open on the web")
        return dialog
    }

    /** Continue in browser: the session the dialog is asked to open. */
    private suspend fun Harness.continueInBrowser(): SecondFactorWebStepUpSession {
        coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)
        val dialog = assertNotNull(coordinator.state.value)
        assertEquals(SecondFactorWebStepUpPhase.Waiting, dialog.webStepUp)
        return assertNotNull(dialog.webStepUpSession)
    }

    private suspend fun Harness.finish(session: SecondFactorWebStepUpSession, result: RaftAppWebStepUpBrowserResult) =
        coordinator.accept(SecondFactorStepUpIntent.WebStepUpBrowserFinished(session.id, result))

    @Test
    fun theWebVerifiesThisSignInAndTheActionGoesOnceMore() = runTest {
        val harness = Harness(ArrayDeque(listOf(started("r-1"))), action = "computer_attach")
        val action = ScriptedAction(refusal(), DataResult.Content("attached"))
        val outcome = async { harness.coordinator.run(action::invoke) }
        runCurrent()
        harness.reachLegacyOnly()

        val session = harness.continueInBrowser()
        assertEquals("https://app.raft.build/login/app/verify?request=r-1", session.url)
        assertEquals("raft", session.callbackScheme)
        val start = harness.starts.single()
        assertEquals(returnUri, start.returnUri)
        assertEquals("c".repeat(43), start.codeChallenge)
        assertEquals("S256", start.codeChallengeMethod)
        assertEquals("computer_attach", start.action)
        // On disk while the page is up: the request and its verifier.
        val onFile = assertNotNull(harness.pending.read())
        assertEquals("r-1", onFile.requestId)
        assertEquals(verifier, onFile.verifier)
        assertEquals(returnUri, onFile.returnUri)

        harness.finish(session, RaftAppWebStepUpBrowserResult.Callback("$returnUri?requestId=r-1&code=k-1"))
        runCurrent()

        assertEquals(listOf(AppWebStepUpCompleteBody("k-1", verifier, returnUri)), harness.completes)
        assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("attached")), outcome.await())
        assertEquals(2, action.calls, "the refused action goes exactly once more")
        assertNull(harness.coordinator.state.value)
        assertNull(harness.pending.read(), "the request is used up")
    }

    @Test
    fun aCallbackForAnotherRequestIsIgnoredAndARepeatCannotExchangeAgain() = runTest {
        val harness = Harness(ArrayDeque(listOf(started("r-2"))))
        val action = ScriptedAction(refusal(), DataResult.Content("attached"))
        val outcome = async { harness.coordinator.run(action::invoke) }
        runCurrent()
        harness.reachLegacyOnly()
        val session = harness.continueInBrowser()

        // An earlier attempt's callback handed to the app (openURL): stale, still waiting.
        harness.coordinator.accept(SecondFactorStepUpIntent.WebStepUpCallback("$returnUri?requestId=r-old&code=k-old"))
        harness.coordinator.accept(SecondFactorStepUpIntent.WebStepUpCallback("raft://login/callback?requestId=r-2"))
        val waiting = assertNotNull(harness.coordinator.state.value)
        assertEquals(SecondFactorWebStepUpPhase.Waiting, waiting.webStepUp)
        assertEquals(session, waiting.webStepUpSession)
        assertTrue(harness.completes.isEmpty())
        assertTrue(harness.logs.any { "result=stale" in it })

        // This request's callback, through openURL; the web session's copy after it is not exchanged again.
        harness.coordinator.accept(SecondFactorStepUpIntent.WebStepUpCallback("$returnUri?requestId=r-2&code=k-2"))
        runCurrent()
        harness.finish(session, RaftAppWebStepUpBrowserResult.Callback("$returnUri?requestId=r-2&code=k-2"))
        assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("attached")), outcome.await())
        assertEquals(1, harness.completes.size)
    }

    @Test
    fun closingTheBrowserKeepsTheDialogAndTryingAgainStartsANewRequest() = runTest {
        val harness = Harness(ArrayDeque(listOf(started("r-3"), started("r-4"))))
        val action = ScriptedAction(refusal(), DataResult.Content("attached"))
        val outcome = async { harness.coordinator.run(action::invoke) }
        runCurrent()
        harness.reachLegacyOnly()

        val first = harness.continueInBrowser()
        harness.finish(first, RaftAppWebStepUpBrowserResult.Closed)
        val closed = assertNotNull(harness.coordinator.state.value)
        assertEquals(SecondFactorStepUpBanner.WebStepUpCancelled, closed.banner)
        assertEquals(SecondFactorWebStepUpPhase.Ready, closed.webStepUp)
        assertNull(closed.webStepUpSession)
        assertNull(harness.pending.read(), "the closed attempt's request is dropped")

        // A late callback of the closed attempt: not waiting, nothing exchanged.
        harness.coordinator.accept(SecondFactorStepUpIntent.WebStepUpCallback("$returnUri?requestId=r-3&code=k-3"))
        assertTrue(harness.completes.isEmpty())

        val second = harness.continueInBrowser()
        assertTrue(second.id != first.id)
        assertEquals("r-4", harness.pending.read()?.requestId)
        assertNull(harness.coordinator.state.value?.banner)

        // Cancel: the action ends quietly and the request on file goes with the dialog.
        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
        assertEquals(1, action.calls)
        assertNull(harness.pending.read())
    }

    @Test
    fun thePagesCancelAndAnExpiredRequestLetThePersonTryAgain() = runTest {
        val harness =
            Harness(
                ArrayDeque(listOf(started("r-5"), started("r-6"), started("r-7"))),
                completeResults = ArrayDeque(listOf(AppWebStepUpCompleteResult.Expired(trace))),
            )
        val outcome = async { harness.coordinator.run(ScriptedAction(refusal())::invoke) }
        runCurrent()
        harness.reachLegacyOnly()

        // Cancel on the page: error=access_denied.
        harness.finish(harness.continueInBrowser(), RaftAppWebStepUpBrowserResult.Callback("$returnUri?requestId=r-5&error=access_denied"))
        assertEquals(SecondFactorStepUpBanner.WebStepUpCancelled, harness.coordinator.state.value?.banner)

        // The page ran out: error=expired.
        harness.finish(harness.continueInBrowser(), RaftAppWebStepUpBrowserResult.Callback("$returnUri?requestId=r-6&error=expired"))
        assertEquals(SecondFactorStepUpBanner.WebStepUpExpired(), harness.coordinator.state.value?.banner)

        // The code ran out before the exchange: 410, with its trace ID.
        harness.finish(harness.continueInBrowser(), RaftAppWebStepUpBrowserResult.Callback("$returnUri?requestId=r-7&code=k-7"))
        runCurrent()
        val expired = assertNotNull(harness.coordinator.state.value)
        assertEquals(SecondFactorStepUpBanner.WebStepUpExpired(trace), expired.banner)
        assertEquals(SecondFactorWebStepUpPhase.Ready, expired.webStepUp)
        assertFalse(expired.submitting)
        assertEquals(listOf("k-7"), harness.completes.map { it.code })

        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }

    @Test
    fun failuresShowTheirTraceId() = runTest {
        val harness =
            Harness(
                ArrayDeque(listOf(AppWebStepUpStartResult.Failed("Try later", "http_503", trace), started("r-9"))),
                completeResults = ArrayDeque(listOf(AppWebStepUpCompleteResult.Failed(null, "code_not_found", trace))),
            )
        val outcome = async { harness.coordinator.run(ScriptedAction(refusal())::invoke) }
        runCurrent()
        harness.reachLegacyOnly()

        harness.coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)
        val startFailed = assertNotNull(harness.coordinator.state.value)
        assertEquals(SecondFactorStepUpBanner.Failed("Try later", trace), startFailed.banner)
        assertEquals(SecondFactorWebStepUpPhase.Ready, startFailed.webStepUp)
        assertNull(harness.pending.read())

        harness.finish(harness.continueInBrowser(), RaftAppWebStepUpBrowserResult.Callback("$returnUri?requestId=r-9&code=k-9"))
        runCurrent()
        assertEquals(SecondFactorStepUpBanner.Failed(null, trace), harness.coordinator.state.value?.banner)

        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }

    @Test
    fun noWebStepUpOnTheServerFallsBackToOpenOnTheWeb() = runTest {
        val webUrl = "https://app.raft.build/s/acme/computers"
        val harness = Harness(ArrayDeque(listOf(AppWebStepUpStartResult.Unavailable)), webUrl = webUrl)
        val action = ScriptedAction(refusal(), DataResult.Content("attached"))
        val first = async { harness.coordinator.run(action::invoke) }
        runCurrent()
        harness.reachLegacyOnly()

        harness.coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)
        val fallback = assertNotNull(harness.coordinator.state.value)
        assertNull(fallback.webStepUp)
        assertNull(fallback.webStepUpSession)
        assertTrue(fallback.opensOnWeb, "#5035's Open on the web")
        assertEquals(webUrl, fallback.webUrl)
        assertNull(harness.pending.read())
        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, first.await())

        // The next refusal goes straight to Open on the web: no second start.
        val second = async { harness.coordinator.run(ScriptedAction(refusal())::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        val again = assertNotNull(harness.coordinator.state.value)
        assertNull(again.webStepUp)
        assertTrue(again.opensOnWeb)
        harness.coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)
        assertEquals(1, harness.starts.size)
        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        second.await()
    }

    @Test
    fun twoStepVerificationTurnedOffMeanwhileJustRetries() = runTest {
        val harness = Harness(ArrayDeque(listOf(AppWebStepUpStartResult.NotEnabled)))
        val action = ScriptedAction(refusal(), DataResult.Content("attached"))
        val outcome = async { harness.coordinator.run(action::invoke) }
        runCurrent()
        harness.reachLegacyOnly()
        harness.coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)
        assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("attached")), outcome.await())
        assertEquals(2, action.calls)
    }

    @Test
    fun aSignInThatCantBeVerifiedAsksToSignInAgain() = runTest {
        val harness =
            Harness(
                ArrayDeque(listOf(AppWebStepUpStartResult.SessionFamilyRequired, started("r-11"))),
                completeResults = ArrayDeque(listOf(AppWebStepUpCompleteResult.SessionFamilyRequired)),
            )
        val outcome = async { harness.coordinator.run(ScriptedAction(refusal())::invoke) }
        runCurrent()
        harness.reachLegacyOnly()
        harness.coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)
        assertEquals(SecondFactorStepUpBanner.SignInAgain, harness.coordinator.state.value?.banner)
        harness.finish(harness.continueInBrowser(), RaftAppWebStepUpBrowserResult.Callback("$returnUri?requestId=r-11&code=k-11"))
        runCurrent()
        assertEquals(SecondFactorStepUpBanner.SignInAgain, harness.coordinator.state.value?.banner)
        harness.coordinator.accept(SecondFactorStepUpIntent.SignInAgain)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }

    @Test
    fun withoutTheWebStepUpThePasskeyOnlyStateIsUnchanged() = runTest {
        val coordinator =
            SecondFactorStepUpCoordinator(
                stepUp = { error("not answered") },
                passkeyOptions = {
                    SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.PasskeyRpUnavailable("current", listOf("legacy")))
                },
                nativePasskeys = true,
                passkeyRpGroup = "current",
            )
        val outcome = async { coordinator.run(ScriptedAction(refusal())::invoke) }
        runCurrent()
        coordinator.accept(SecondFactorStepUpIntent.Submit)
        val dialog = assertNotNull(coordinator.state.value)
        assertNull(dialog.method)
        assertNull(dialog.webStepUp)
        assertFalse(dialog.continuesInBrowser)
        coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)
        assertNull(coordinator.state.value?.webStepUp)
        coordinator.accept(SecondFactorStepUpIntent.Cancel)
        outcome.await()
    }

    @Test
    fun withACodeOlderPasskeysGoStraightToTheBrowserAndCanSwitchToACode() = runTest {
        // #proj-mobile:#188: older passkeys with an authenticator app go straight into Continue in
        // browser with no warning banner, keep code methods as switch links, and still allow
        // launching the browser passkey flow beside the code after switching.
        val harness = Harness(ArrayDeque(listOf(started("r-12a"), started("r-12b"))))
        val action = ScriptedAction(refusal(withCode), DataResult.Content("attached"))
        val outcome = async { harness.coordinator.run(action::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        val browserReady = assertNotNull(harness.coordinator.state.value)
        assertNull(browserReady.method)
        assertNull(browserReady.banner)
        assertTrue(browserReady.continuesInBrowser)
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), browserReady.otherMethods)

        // Browser opens straight away; switching methods is blocked while the browser session is up.
        val firstSession = harness.continueInBrowser()
        harness.coordinator.accept(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.Totp))
        assertNull(harness.coordinator.state.value?.method, "cannot switch away while web step-up is under way")

        // Closing the browser leaves Continue in browser + secondary links to switch to a code.
        harness.finish(firstSession, RaftAppWebStepUpBrowserResult.Closed)
        val cancelled = assertNotNull(harness.coordinator.state.value)
        assertNull(cancelled.method)
        assertEquals(SecondFactorStepUpBanner.WebStepUpCancelled, cancelled.banner)
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), cancelled.otherMethods)

        // Switching to authenticator code clears the cancel banner and keeps the browser link beside the code.
        harness.coordinator.accept(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.Totp))
        val codeEntry = assertNotNull(harness.coordinator.state.value)
        assertEquals(SecondFactorStepUpMethod.Totp, codeEntry.method)
        assertNull(codeEntry.banner)
        assertTrue(codeEntry.webStepUpBesideCode)
        assertFalse(codeEntry.continuesInBrowser)

        // Verify with your passkey in the browser from the code view runs the same web step-up.
        val secondSession = harness.continueInBrowser()
        assertEquals(listOf("computer_attach", "computer_attach"), harness.starts.map { it.action })
        // One answer at a time: a code typed meanwhile is not sent.
        harness.coordinator.accept(SecondFactorStepUpIntent.InputChanged("123456"))
        assertFalse(assertNotNull(harness.coordinator.state.value).canSubmit)
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertEquals(SecondFactorWebStepUpPhase.Waiting, harness.coordinator.state.value?.webStepUp)

        harness.finish(secondSession, RaftAppWebStepUpBrowserResult.Callback("$returnUri?requestId=r-12b&code=k-12"))
        runCurrent()
        assertEquals(listOf("k-12"), harness.completes.map { it.code })
        assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("attached")), outcome.await())
        assertEquals(2, action.calls, "the refused action goes exactly once more")
    }

    @Test
    fun unavailableStartWithACodeFallsBackToCodeInsteadOfOpenOnTheWeb() = runTest {
        val webUrl = "https://app.raft.build/s/acme/computers"
        val harness = Harness(ArrayDeque(listOf(AppWebStepUpStartResult.Unavailable)), webUrl = webUrl)
        val first = async { harness.coordinator.run(ScriptedAction(refusal(withCode))::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertTrue(assertNotNull(harness.coordinator.state.value).continuesInBrowser)

        harness.coordinator.accept(SecondFactorStepUpIntent.ContinueInBrowser)
        val fallback = assertNotNull(harness.coordinator.state.value)
        assertEquals(SecondFactorStepUpMethod.Totp, fallback.method, "falls back to TOTP instead of stranding on Open on the web")
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), fallback.methods)
        assertEquals(SecondFactorStepUpBanner.PasskeyUnsupported, fallback.banner)
        assertNull(fallback.webStepUp)
        assertNull(fallback.webStepUpSession)
        assertFalse(fallback.opensOnWeb)
        assertFalse(fallback.continuesInBrowser)
        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, first.await())

        // The next refusal skips web step-up and lands straight on TOTP with the warning banner.
        val second = async { harness.coordinator.run(ScriptedAction(refusal(withCode))::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        val again = assertNotNull(harness.coordinator.state.value)
        assertEquals(SecondFactorStepUpMethod.Totp, again.method)
        assertEquals(SecondFactorStepUpBanner.PasskeyUnsupported, again.banner)
        assertNull(again.webStepUp)
        assertEquals(1, harness.starts.size)
        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        second.await()
    }

    @Test
    fun aPasskeyUnderTheCurrentRpIdStillOpensThePlatformsSheet() = runTest {
        val harness = Harness(ArrayDeque(), options = { parseSharedSecondFactorPasskeyAssertionOptions(200, currentOptions) })
        val outcome = async { harness.coordinator.run(ScriptedAction(refusal(withCode))::invoke) }
        runCurrent()
        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)

        val dialog = assertNotNull(harness.coordinator.state.value)
        assertEquals("app.raft.test", dialog.passkeyCeremony?.request?.options?.rpId, "the platform's passkey sheet")
        assertEquals(SecondFactorStepUpMethod.Passkey, dialog.method)
        assertNull(dialog.webStepUp, "the browser is only for older passkeys")
        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
        assertTrue(harness.starts.isEmpty())
    }
}
