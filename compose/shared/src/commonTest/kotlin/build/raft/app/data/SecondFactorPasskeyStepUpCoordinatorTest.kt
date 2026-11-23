package build.raft.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Use passkey in "Verify it's you": the options, the platform prompt (the dialog runs it and
 * answers with [SecondFactorStepUpIntent.PasskeyAssertionFinished]) and the step-up's `webauthn`.
 * Payload shapes: SecondFactorPasskeyStepUpApiTest (botiverse/slock staging e21bf9b97).
 */
class SecondFactorPasskeyStepUpCoordinatorTest {
    private val withCodes =
        """{"error":"Confirm it's you with your second step before changing your password.","code":"second_factor_step_up_required","methods":["totp","passkey","backup_code"]}"""
    private val passkeyOnly =
        """{"error":"Confirm it's you with your second step before disconnecting a sign-in method.","code":"second_factor_step_up_required","methods":["passkey"]}"""
    private val optionsBody =
        """{"options":{"rpId":"raft.test","challenge":"Y2hhbGxlbmdlLXRva2Vu","allowCredentials":[{"id":"q83vEjRWeJA","transports":["internal","hybrid"],"type":"public-key"}],"timeout":300000,"userVerification":"required"}}"""
    private val assertion =
        """{"id":"q83vEjRWeJA","rawId":"q83vEjRWeJA","type":"public-key","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0In0","authenticatorData":"SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MFAAAAAQ","signature":"MEUCIQDx"},"clientExtensionResults":{},"authenticatorAttachment":"platform"}"""
    private val trace = "4bf92f3577b34da6a3ce929d0e0e4736"

    private class Harness(
        val stepUpResults: ArrayDeque<SecondFactorStepUpResult> = ArrayDeque(listOf(SecondFactorStepUpResult.Verified())),
        val optionsResult: () -> SecondFactorPasskeyOptionsResult,
        nativePasskeys: Boolean = true,
        rpGroup: String? = null,
        webUrl: (suspend (SecondFactorStepUpWebTarget) -> String?)? = null,
        webTarget: SecondFactorStepUpWebTarget? = null,
    ) {
        val answers = mutableListOf<SecondFactorStepUpAnswer>()
        val optionGroups = mutableListOf<String?>()
        val coordinator =
            SecondFactorStepUpCoordinator(
                stepUp = { answers += it; stepUpResults.removeFirst() },
                passkeyOptions = { rp -> optionGroups += rp; optionsResult() },
                nativePasskeys = nativePasskeys,
                passkeyRpGroup = rpGroup,
                webUrl = webUrl,
                webTarget = { webTarget },
            )
    }

    private fun ready() = parseSharedSecondFactorPasskeyAssertionOptions(200, optionsBody)

    /** Options in [rp] (RP ID [rpId]) while the person also has a passkey in [otherRps]. */
    private fun readyIn(rp: String, rpId: String, vararg otherRps: String): SecondFactorPasskeyOptionsResult {
        val others = otherRps.joinToString(",") { "\"$it\"" }
        val extra = if (otherRps.isEmpty()) "" else ""","otherRps":[$others]"""
        return parseSharedSecondFactorPasskeyAssertionOptions(200, optionsBody.replace("raft.test", rpId).dropLast(1) + ""","rp":"$rp"$extra}""")
    }

    private fun refusal(body: String): DataResult<String> =
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

    /** Submit (Use passkey) and return the prompt the dialog is asked to run. */
    private suspend fun Harness.usePasskey(): SecondFactorPasskeyCeremony {
        coordinator.accept(SecondFactorStepUpIntent.Submit)
        return assertNotNull(coordinator.state.value?.passkeyCeremony, "Use passkey asks the dialog for the prompt")
    }

    @Test
    fun aPasskeyComesFirstAndItsAssertionContinuesTheAction() = runTest {
        val harness = Harness(optionsResult = ::ready, rpGroup = "current")
        val action = ScriptedAction(refusal(withCodes), DataResult.Content("applied"))
        val outcome = async { harness.coordinator.run(action::invoke) }
        runCurrent()

        val dialog = assertNotNull(harness.coordinator.state.value)
        assertEquals(
            listOf(SecondFactorStepUpMethod.Passkey, SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode),
            dialog.methods
        )
        assertEquals(SecondFactorStepUpMethod.Passkey, dialog.method)
        assertTrue(dialog.canSubmit, "Use passkey needs nothing typed")

        val ceremony = harness.usePasskey()
        assertEquals(listOf<String?>("current"), harness.optionGroups, "the platform's group is asked for")
        assertEquals("raft.test", ceremony.request.options.rpId)
        assertTrue(harness.coordinator.state.value!!.busy)
        assertFalse(harness.coordinator.state.value!!.canSubmit)

        harness.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(ceremony.id, RaftNativePasskeyAssertionResult.Success(assertion)))
        runCurrent()

        assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("applied")), outcome.await())
        val answer = assertIs<SecondFactorStepUpAnswer.Passkey>(harness.answers.single())
        assertEquals("q83vEjRWeJA", answer.assertion.id)
        assertEquals("MEUCIQDx", answer.assertion.response.signature)
        assertEquals(2, action.calls, "the refused action goes once more")
        assertNull(harness.coordinator.state.value)
    }

    @Test
    fun aClosedPromptKeepsTheDialogAndCancelEndsIt() = runTest {
        val harness = Harness(optionsResult = ::ready)
        val action = ScriptedAction(refusal(withCodes), DataResult.Content("applied"))
        val outcome = async { harness.coordinator.run(action::invoke) }
        runCurrent()

        val ceremony = harness.usePasskey()
        harness.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(ceremony.id, RaftNativePasskeyAssertionResult.Cancelled))
        val shown = assertNotNull(harness.coordinator.state.value)
        assertEquals(SecondFactorStepUpBanner.PasskeyCancelled, shown.banner)
        assertEquals(SecondFactorStepUpMethod.Passkey, shown.method, "Use passkey stays to try again")
        assertNull(shown.passkeyCeremony)
        assertTrue(shown.canSubmit)

        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
        assertEquals(1, action.calls)
        assertTrue(harness.answers.isEmpty())
    }

    @Test
    fun noPasskeyOnThisDeviceFallsBackToTheOtherMethods() = runTest {
        val cases =
            listOf(
                RaftNativePasskeyAssertionResult.NoCredential to SecondFactorStepUpBanner.PasskeyNoCredential,
                RaftNativePasskeyAssertionResult.Unsupported to SecondFactorStepUpBanner.PasskeyUnsupported,
            )
        cases.forEach { (platform, banner) ->
            val harness = Harness(stepUpResults = ArrayDeque(listOf(SecondFactorStepUpResult.Verified())), optionsResult = ::ready)
            val action = ScriptedAction(refusal(withCodes), DataResult.Content("applied"))
            val outcome = async { harness.coordinator.run(action::invoke) }
            runCurrent()

            val ceremony = harness.usePasskey()
            harness.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(ceremony.id, platform))
            val shown = assertNotNull(harness.coordinator.state.value)
            assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), shown.methods, "$platform")
            assertEquals(SecondFactorStepUpMethod.Totp, shown.method)
            assertEquals(banner, shown.banner)

            harness.coordinator.accept(SecondFactorStepUpIntent.InputChanged("123456"))
            harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
            runCurrent()
            assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("applied")), outcome.await())
            assertEquals(listOf<SecondFactorStepUpAnswer>(SecondFactorStepUpAnswer.AuthenticatorCode("123456")), harness.answers)
        }
    }

    @Test
    fun passkeyOnlyWithNoneHereFallsBackToTheWeb() = runTest {
        // The platform has none, or the server none in the group this platform asked for (iOS: `current`).
        val routes =
            listOf<suspend Harness.(SecondFactorPasskeyCeremony?) -> Unit>(
                { ceremony -> coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(ceremony!!.id, RaftNativePasskeyAssertionResult.NoCredential)) },
                { _ -> Unit },
            )
        val options =
            listOf<() -> SecondFactorPasskeyOptionsResult>(
                ::ready,
                { SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.PasskeyNotFound(trace)) },
            )
        routes.zip(options).forEach { (finish, optionsResult) ->
            val harness = Harness(optionsResult = optionsResult)
            val outcome = async { harness.coordinator.run(ScriptedAction(refusal(passkeyOnly))::invoke) }
            runCurrent()
            assertEquals(SecondFactorStepUpMethod.Passkey, harness.coordinator.state.value?.method, "no more \"use the web\" up front")

            harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
            harness.finish(harness.coordinator.state.value?.passkeyCeremony)
            val shown = assertNotNull(harness.coordinator.state.value)
            assertNull(shown.method, "the dialog's own web hand-off")
            assertEquals(emptyList<SecondFactorStepUpMethod>(), shown.methods)
            assertNull(shown.banner)

            harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
            assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
        }
    }

    @Test
    fun serverRefusalsOfThePasskeyStayInTheDialogWithTheirTraceId() = runTest {
        val lockedUntil = 1_793_000_000_000L
        val harness =
            Harness(
                stepUpResults =
                    ArrayDeque(
                        listOf(
                            SecondFactorStepUpResult.CodeInvalid(trace),
                            SecondFactorStepUpResult.PasskeyNotFound(trace),
                            SecondFactorStepUpResult.Locked(lockedUntil, trace),
                            SecondFactorStepUpResult.Failed("Failed to verify this sign-in", "internal_error", trace),
                        )
                    ),
                optionsResult = ::ready,
            )
        val outcome = async { harness.coordinator.run(ScriptedAction(refusal(withCodes))::invoke) }
        runCurrent()

        val expected =
            listOf(
                SecondFactorStepUpBanner.PasskeyInvalid(trace),
                SecondFactorStepUpBanner.PasskeyInvalid(trace),
                SecondFactorStepUpBanner.Locked(lockedUntil, trace),
                SecondFactorStepUpBanner.Failed("Failed to verify this sign-in", trace),
            )
        expected.forEach { banner ->
            val ceremony = harness.usePasskey()
            harness.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(ceremony.id, RaftNativePasskeyAssertionResult.Success(assertion)))
            runCurrent()
            val shown = assertNotNull(harness.coordinator.state.value)
            assertEquals(banner, shown.banner)
            assertEquals(SecondFactorStepUpMethod.Passkey, shown.method)
            assertFalse(shown.busy)
        }
        assertEquals(4, harness.answers.size)
        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }

    @Test
    fun optionsRefusalsAndALateOrBrokenAnswer() = runTest {
        var options: SecondFactorPasskeyOptionsResult =
            SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.PasskeysUnavailable(trace))
        val harness = Harness(optionsResult = { options })
        val outcome = async { harness.coordinator.run(ScriptedAction(refusal(withCodes))::invoke) }
        runCurrent()

        harness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertEquals(SecondFactorStepUpBanner.PasskeysUnavailable(trace), harness.coordinator.state.value?.banner)
        assertNull(harness.coordinator.state.value?.passkeyCeremony)

        options = ready()
        val first = harness.usePasskey()
        // While the prompt is up nothing else starts.
        harness.coordinator.accept(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.Totp))
        assertEquals(SecondFactorStepUpMethod.Passkey, harness.coordinator.state.value?.method)
        // An answer to another attempt is ignored.
        harness.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(first.id + 1, RaftNativePasskeyAssertionResult.Success(assertion)))
        assertEquals(first, harness.coordinator.state.value?.passkeyCeremony)
        // An assertion the server would refuse for its shape is not sent.
        harness.coordinator.accept(
            SecondFactorStepUpIntent.PasskeyAssertionFinished(first.id, RaftNativePasskeyAssertionResult.Success("""{"id":"a","rawId":"b"}"""))
        )
        assertEquals(SecondFactorStepUpBanner.Failed(null), harness.coordinator.state.value?.banner)
        // A platform error: try again or switch.
        val second = harness.usePasskey()
        assertTrue(second.id > first.id)
        harness.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(second.id, RaftNativePasskeyAssertionResult.Error("interrupted")))
        assertEquals(SecondFactorStepUpBanner.PasskeyFailed, harness.coordinator.state.value?.banner)
        assertTrue(harness.answers.isEmpty())

        harness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }

    @Test
    fun noPasskeyWithoutTheNativePortOrTheOptionsCall() = runTest {
        // Desktop (no native port): as before, the codes only, and passkey-only says to use the web.
        val desktop = Harness(optionsResult = { error("not asked") }, nativePasskeys = false)
        async { desktop.coordinator.run(ScriptedAction(refusal(withCodes))::invoke) }
        runCurrent()
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), desktop.coordinator.state.value?.methods)
        desktop.coordinator.accept(SecondFactorStepUpIntent.Cancel)

        val unwired = SecondFactorStepUpCoordinator(stepUp = { error("not answered") }, nativePasskeys = true)
        val outcome = async { unwired.run(ScriptedAction(refusal(passkeyOnly))::invoke) }
        runCurrent()
        assertNull(unwired.state.value?.method)
        unwired.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }

    @Test
    fun iosWithOnlyLegacyPasskeysAnswersOnTheWebOrWithACode() = runTest {
        // iOS asks for `current`; the person's passkeys are all under the legacy RP ID.
        val rpUnavailable = { SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.PasskeyRpUnavailable("current", listOf("legacy"), trace)) }

        // Adding a computer: Open on the web goes to the server's Computers page on the environment's web.
        val pages = mutableListOf<SecondFactorStepUpWebTarget>()
        val resolver: suspend (SecondFactorStepUpWebTarget) -> String? = { target ->
            pages += target
            secondFactorStepUpWebUrl("https://staging.raft.build/", "acme", target)
        }
        val passkeyOnlyHarness = Harness(optionsResult = rpUnavailable, rpGroup = SECOND_FACTOR_PASSKEY_RP_CURRENT, webUrl = resolver)
        val webOutcome =
            async { passkeyOnlyHarness.coordinator.run(SecondFactorStepUpWebTarget.Computers, request = ScriptedAction(refusal(passkeyOnly))::invoke) }
        runCurrent()
        assertFalse(assertNotNull(passkeyOnlyHarness.coordinator.state.value).opensOnWeb, "Use passkey first")
        passkeyOnlyHarness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        val web = assertNotNull(passkeyOnlyHarness.coordinator.state.value)
        assertEquals(listOf<String?>("current"), passkeyOnlyHarness.optionGroups)
        assertNull(web.method, "the dialog's own web hand-off")
        assertNull(web.passkeyCeremony, "no native prompt for a legacy passkey on iOS")
        assertEquals("https://staging.raft.build/s/acme/computers", web.webUrl)
        assertTrue(web.opensOnWeb)
        assertEquals(listOf<SecondFactorStepUpWebTarget>(SecondFactorStepUpWebTarget.Computers), pages)
        passkeyOnlyHarness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, webOutcome.await())

        val codesHarness =
            Harness(
                optionsResult = rpUnavailable,
                rpGroup = SECOND_FACTOR_PASSKEY_RP_CURRENT,
                webUrl = resolver,
                webTarget = SecondFactorStepUpWebTarget.Account,
            )
        val codesOutcome = async { codesHarness.coordinator.run(ScriptedAction(refusal(withCodes))::invoke) }
        runCurrent()
        codesHarness.coordinator.accept(SecondFactorStepUpIntent.Submit)
        val codes = assertNotNull(codesHarness.coordinator.state.value)
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), codes.methods)
        assertEquals(SecondFactorStepUpBanner.PasskeyUnsupported, codes.banner)
        // The coordinator's own page (no target named), but a code answers here: no Open on the web.
        assertEquals("https://staging.raft.build/s/acme/settings/account", codes.webUrl)
        assertFalse(codes.opensOnWeb)
        codesHarness.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, codesOutcome.await())
    }

    @Test
    fun androidTriesThePersonsOtherRpGroupOnceWhenThisDeviceHasNoneInTheFirst() = runTest {
        // Android sends no `rp`: the server's default (current), then the legacy group the options name.
        val answers = ArrayDeque(listOf(readyIn("current", "app.raft.test", "legacy"), readyIn("legacy", "raft.test", "current")))
        val harness = Harness(optionsResult = { answers.removeFirst() }, rpGroup = null)
        val outcome = async { harness.coordinator.run(ScriptedAction(refusal(passkeyOnly), DataResult.Content("applied"))::invoke) }
        runCurrent()

        val first = harness.usePasskey()
        assertEquals("app.raft.test", first.request.options.rpId)
        harness.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(first.id, RaftNativePasskeyAssertionResult.NoCredential))
        val second = assertNotNull(harness.coordinator.state.value?.passkeyCeremony, "a second prompt, for the other group")
        assertEquals(listOf(null, "legacy"), harness.optionGroups)
        assertEquals("raft.test", second.request.options.rpId, "the RP ID is the server's, per group")
        assertTrue(second.id > first.id)

        harness.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(second.id, RaftNativePasskeyAssertionResult.Success(assertion)))
        runCurrent()
        assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("applied")), outcome.await())
        assertIs<SecondFactorStepUpAnswer.Passkey>(harness.answers.single())
    }

    @Test
    fun theOtherRpGroupIsTriedOnlyOnceAndNeverOnIos() = runTest {
        // Android: none in either group on this device → the other methods (here: the web).
        val android = Harness(optionsResult = { readyIn("current", "app.raft.test", "legacy") }, rpGroup = null)
        val androidOutcome = async { android.coordinator.run(ScriptedAction(refusal(passkeyOnly))::invoke) }
        runCurrent()
        val first = android.usePasskey()
        android.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(first.id, RaftNativePasskeyAssertionResult.NoCredential))
        val second = assertNotNull(android.coordinator.state.value?.passkeyCeremony)
        android.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(second.id, RaftNativePasskeyAssertionResult.NoCredential))
        assertNull(android.coordinator.state.value?.passkeyCeremony)
        assertNull(android.coordinator.state.value?.method)
        assertEquals(listOf(null, "legacy"), android.optionGroups)
        android.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, androidOutcome.await())

        // iOS asked for `current` and can't assert the legacy RP ID: no second ask.
        val ios = Harness(optionsResult = { readyIn("current", "app.raft.test", "legacy") }, rpGroup = SECOND_FACTOR_PASSKEY_RP_CURRENT)
        val iosOutcome = async { ios.coordinator.run(ScriptedAction(refusal(withCodes))::invoke) }
        runCurrent()
        val iosCeremony = ios.usePasskey()
        ios.coordinator.accept(SecondFactorStepUpIntent.PasskeyAssertionFinished(iosCeremony.id, RaftNativePasskeyAssertionResult.NoCredential))
        assertEquals(listOf<String?>("current"), ios.optionGroups)
        assertEquals(SecondFactorStepUpMethod.Totp, ios.coordinator.state.value?.method)
        assertEquals(SecondFactorStepUpBanner.PasskeyNoCredential, ios.coordinator.state.value?.banner)
        ios.coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, iosOutcome.await())
    }
}
