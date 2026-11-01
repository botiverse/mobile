package build.raft.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Step-up for sensitive actions. Bodies are the server's, botiverse/slock staging
 * 540981dd7e459ce8421fab70b369448b0f4c4c16:
 *  - the refusal: routes/secondFactor.ts `refuseWithoutRecentSecondFactor` with
 *    services/secondFactor/stepUpGate.ts `STEP_UP_ACTIONS`, as asserted by
 *    routes/secondFactorStepUp.api.test.ts `assertRefused`;
 *  - POST /api/auth/second-factor/step-up: services/secondFactor/management.ts `stepUpSession`
 *    and its `SecondFactorError`s (with `lockedUntil`/`methods` on the lock), and
 *    routes/secondFactor.ts `requireWebSessionFamily`.
 */
class SecondFactorStepUpTest {
    private val passwordRefusal =
        """{"error":"Confirm it's you with your second step before changing your password.","code":"second_factor_step_up_required","methods":["totp","backup_code"]}"""
    private val passkeyOnlyRefusal =
        """{"error":"Confirm it's you with your second step before disconnecting a sign-in method.","code":"second_factor_step_up_required","methods":["passkey"]}"""
    private val account = """{"id":"user-1","email":"user@slock.ai","name":"slock-user","displayName":"Slock User","emailVerified":true}"""
    private val providers = """{"providers":[{"id":"google","label":"Google","enabled":true}]}"""
    private val identities = """{"identities":[],"passwordConfigured":true}"""

    @Test
    fun parsesTheStepUpRefusalOnlyFromA403WithItsCode() {
        val challenge = assertNotNull(parseSharedSecondFactorStepUpRequired(403, passwordRefusal))
        assertEquals(listOf("totp", "backup_code"), challenge.methods)
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), challenge.usableMethods)
        assertEquals("Confirm it's you with your second step before changing your password.", challenge.serverMessage)

        // Only a passkey: nothing the app can answer with.
        assertEquals(emptyList<SecondFactorStepUpMethod>(), assertNotNull(parseSharedSecondFactorStepUpRequired(403, passkeyOnlyRefusal)).usableMethods)
        // Other refusals are not a step-up.
        assertNull(parseSharedSecondFactorStepUpRequired(403, """{"error":"Sign in again to manage two-step verification.","code":"session_family_required"}"""))
        assertNull(parseSharedSecondFactorStepUpRequired(400, passwordRefusal))
        assertNull(parseSharedSecondFactorStepUpRequired(403, "not json"))
    }

    @Test
    fun parsesEveryStepUpAnswer() {
        val lockedUntil = "2026-10-31T23:40:00.000Z"
        val cases =
            listOf(
                Triple(200, """{"verified":true}""", SecondFactorStepUpResult.Verified(null)),
                Triple(200, """{"verified":true,"backupCodesRemaining":7}""", SecondFactorStepUpResult.Verified(7)),
                Triple(
                    400,
                    """{"error":"That code didn't work. Check your authenticator app and try again.","code":"second_factor_code_invalid"}""",
                    SecondFactorStepUpResult.CodeInvalid
                ),
                Triple(400, """{"error":"That backup code was already used.","code":"backup_code_used"}""", SecondFactorStepUpResult.BackupCodeUsed),
                Triple(
                    429,
                    """{"error":"Too many wrong codes. Try again later.","code":"second_factor_locked","lockedUntil":"$lockedUntil","methods":["totp","backup_code"]}""",
                    SecondFactorStepUpResult.Locked(Instant.parse(lockedUntil).toEpochMilliseconds())
                ),
                Triple(409, """{"error":"Two-step verification is off.","code":"second_factor_not_enabled"}""", SecondFactorStepUpResult.NotEnabled),
                Triple(
                    403,
                    """{"error":"Sign in again to manage two-step verification.","code":"session_family_required"}""",
                    SecondFactorStepUpResult.SessionFamilyRequired
                ),
                Triple(
                    400,
                    """{"error":"Use a code from your authenticator app, a backup code or a passkey.","code":"invalid_request"}""",
                    SecondFactorStepUpResult.Failed("Use a code from your authenticator app, a backup code or a passkey.", "invalid_request")
                ),
                // The flag off for this person: the app's own 404.
                Triple(404, """{"error":"Not found","code":"not_found","path":"/api/auth/second-factor/step-up"}""", SecondFactorStepUpResult.Failed("Not found", "not_found")),
                Triple(200, """{"verified":false}""", SecondFactorStepUpResult.Failed(serverCode = "second_factor_step_up_contract_mismatch")),
            )
        cases.forEach { (status, body, expected) ->
            assertEquals(expected, parseSharedSecondFactorStepUpResult(status, body), "$status $body")
        }
    }

    @Test
    fun theAnswerGoesAsCodeOrBackupCode() {
        val code = Json.parseToJsonElement(buildSharedSecondFactorStepUpBody(SecondFactorStepUpAnswer.AuthenticatorCode("123456"))).jsonObject
        assertEquals("123456", code["code"]?.jsonPrimitive?.content)
        assertEquals(setOf("code"), code.keys)
        val backup = Json.parseToJsonElement(buildSharedSecondFactorStepUpBody(SecondFactorStepUpAnswer.BackupCode("abcd-efgh"))).jsonObject
        assertEquals("abcd-efgh", backup["backupCode"]?.jsonPrimitive?.content)
        assertEquals(setOf("backupCode"), backup.keys)
    }

    @Test
    fun aHostForwardedMobileLinkRefusalKeepsItsMethods() {
        assertEquals(listOf("passkey"), parseSharedMobileOAuthLinkStepUpChallenge("passkey", "Confirm it's you").methods)
        assertEquals(listOf("totp", "backup_code"), parseSharedMobileOAuthLinkStepUpChallenge("totp, backup_code", null).methods)
        // A host that forwarded none: code and backup code.
        assertEquals(listOf("totp", "backup_code"), parseSharedMobileOAuthLinkStepUpChallenge(null, null).methods)
    }

    @Test
    fun aRefusedActionAsksVerifiesAndRetriesOnce() = runTest {
        val answers = mutableListOf<SecondFactorStepUpAnswer>()
        val coordinator = SecondFactorStepUpCoordinator(stepUp = { answers += it; SecondFactorStepUpResult.Verified() })
        val action = ScriptedAction(refusal(passwordRefusal), DataResult.Content("applied"))

        val outcome = async { coordinator.run(action::invoke) }
        runCurrent()
        val dialog = assertNotNull(coordinator.state.value, "dialog opens on the refusal")
        assertEquals(SecondFactorStepUpMethod.Totp, dialog.method)
        assertEquals(listOf(SecondFactorStepUpMethod.BackupCode), dialog.otherMethods)
        assertFalse(dialog.canSubmit)

        coordinator.accept(SecondFactorStepUpIntent.InputChanged("12 34 56 7"))
        assertEquals("123456", coordinator.state.value?.input, "a code keeps its 6 digits")
        coordinator.accept(SecondFactorStepUpIntent.Submit)
        runCurrent()

        assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("applied")), outcome.await())
        assertEquals(listOf<SecondFactorStepUpAnswer>(SecondFactorStepUpAnswer.AuthenticatorCode("123456")), answers)
        assertEquals<Int>(2, action.calls, "the refused action goes once more")
        assertNull(coordinator.state.value)
    }

    @Test
    fun closingTheDialogCancelsWithoutARetry() = runTest {
        val coordinator = SecondFactorStepUpCoordinator(stepUp = { error("no step-up when cancelled") })
        val action = ScriptedAction(refusal(passwordRefusal), DataResult.Content("applied"))

        val outcome = async { coordinator.run(action::invoke) }
        runCurrent()
        coordinator.accept(SecondFactorStepUpIntent.Cancel)

        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
        assertEquals<Int>(1, action.calls)
        assertNull(coordinator.state.value)
    }

    @Test
    fun refusalsOfTheAnswerStayInTheDialog() = runTest {
        val lockedUntil = Instant.parse("2026-10-31T23:40:00Z").toEpochMilliseconds()
        val results =
            ArrayDeque(
                listOf(
                    SecondFactorStepUpResult.CodeInvalid,
                    SecondFactorStepUpResult.BackupCodeUsed,
                    SecondFactorStepUpResult.Locked(lockedUntil),
                    SecondFactorStepUpResult.Failed("Failed to verify this sign-in"),
                )
            )
        val coordinator = SecondFactorStepUpCoordinator(stepUp = { results.removeFirst() })
        val action = ScriptedAction(refusal(passwordRefusal), DataResult.Content("applied"))
        val outcome = async { coordinator.run(action::invoke) }
        runCurrent()

        coordinator.accept(SecondFactorStepUpIntent.InputChanged("000000"))
        coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertEquals(SecondFactorStepUpFieldError.CodeInvalid, coordinator.state.value?.fieldError)

        coordinator.accept(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.BackupCode))
        val switched = assertNotNull(coordinator.state.value)
        assertEquals(SecondFactorStepUpMethod.BackupCode, switched.method)
        assertEquals("", switched.input, "a switch clears what was typed")
        assertNull(switched.fieldError)

        coordinator.accept(SecondFactorStepUpIntent.InputChanged(" ABCD-EFGH "))
        coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertEquals(SecondFactorStepUpFieldError.BackupCodeUsed, coordinator.state.value?.fieldError)

        coordinator.accept(SecondFactorStepUpIntent.InputChanged("WXYZ-1234"))
        coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertEquals(SecondFactorStepUpBanner.Locked(lockedUntil), coordinator.state.value?.banner)

        coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertEquals(SecondFactorStepUpBanner.Failed("Failed to verify this sign-in"), coordinator.state.value?.banner)
        assertFalse(coordinator.state.value!!.submitting)

        coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
        assertEquals<Int>(1, action.calls)
    }

    @Test
    fun sessionFamilyRequiredOffersSignInAgainWhichCloses() = runTest {
        val coordinator = SecondFactorStepUpCoordinator(stepUp = { SecondFactorStepUpResult.SessionFamilyRequired })
        val action = ScriptedAction(refusal(passwordRefusal), DataResult.Content("applied"))
        val outcome = async { coordinator.run(action::invoke) }
        runCurrent()

        coordinator.accept(SecondFactorStepUpIntent.InputChanged("123456"))
        coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertEquals(SecondFactorStepUpBanner.SignInAgain, coordinator.state.value?.banner)

        coordinator.accept(SecondFactorStepUpIntent.SignInAgain)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
        assertEquals<Int>(1, action.calls)
    }

    @Test
    fun turnedOffMeanwhileJustRetries() = runTest {
        val coordinator = SecondFactorStepUpCoordinator(stepUp = { SecondFactorStepUpResult.NotEnabled })
        val action = ScriptedAction(refusal(passwordRefusal), DataResult.Content("applied"))
        val outcome = async { coordinator.run(action::invoke) }
        runCurrent()

        coordinator.accept(SecondFactorStepUpIntent.InputChanged("123456"))
        coordinator.accept(SecondFactorStepUpIntent.Submit)

        assertEquals(SecondFactorStepUpOutcome.Completed(DataResult.Content("applied")), outcome.await())
        assertEquals<Int>(2, action.calls)
    }

    @Test
    fun passkeyOnlyHasNoFieldAndOnlyCloses() = runTest {
        val coordinator = SecondFactorStepUpCoordinator(stepUp = { error("no answer without a field") })
        val action = ScriptedAction(refusal(passkeyOnlyRefusal), DataResult.Content("applied"))
        val outcome = async { coordinator.run(action::invoke) }
        runCurrent()

        val dialog = assertNotNull(coordinator.state.value)
        assertNull(dialog.method)
        assertEquals(emptyList<SecondFactorStepUpMethod>(), dialog.methods)
        coordinator.accept(SecondFactorStepUpIntent.InputChanged("123456"))
        coordinator.accept(SecondFactorStepUpIntent.Submit)
        assertFalse(coordinator.state.value!!.canSubmit)

        coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }

    @Test
    fun anActionNotRefusedNeverOpensTheDialog() = runTest {
        val coordinator = SecondFactorStepUpCoordinator(stepUp = { error("not asked") })
        val action = ScriptedAction(DataResult.Error(ScreenError("http_400", serverMessage = "New password must be at least 8 characters")))

        val outcome = coordinator.run(action::invoke)

        val completed = assertIs<SecondFactorStepUpOutcome.Completed<String>>(outcome)
        assertEquals("http_400", assertIs<DataResult.Error>(completed.result).error.code)
        assertNull(coordinator.state.value)
        assertEquals<Int>(1, action.calls)
    }

    @Test
    fun changePasswordInSettingsStepsUpAndChangesIt() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("PATCH", "auth/me", 403, passwordRefusal)
                respond("PATCH", "auth/me", 200, account)
                respond("POST", "auth/second-factor/step-up", 200, """{"verified":true}""")
                respond("GET", "auth/providers", 200, providers)
                respond("GET", "auth/identities", 200, identities)
            }
        val store = settingsStore(network)

        val change = launch { store.accept(SharedSettingsIntent.ChangeAccountPassword(AccountPasswordChangeRequest("old-password", "new-password"))) }
        runCurrent()
        val dialog = assertNotNull(store.state.value.secondFactorStepUp, "the store shows the dialog")
        assertEquals(SecondFactorStepUpMethod.Totp, dialog.method)
        assertEquals(true, store.state.value.actionState?.inProgress)

        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.InputChanged("123456")))
        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.Submit))
        // The account parses on the JSON dispatcher (a real thread): wait for the action itself.
        change.join()

        assertNull(store.state.value.secondFactorStepUp)
        assertIs<ScreenLoadState.Content<AccountSettingsSnapshot>>(store.state.value.account)
        val action = assertNotNull(store.state.value.actionState)
        assertEquals(ACCOUNT_ACTION_PASSWORD_CHANGE to false, action.action to action.inProgress)
        assertNull(action.errorCode)
        assertEquals(
            listOf("PATCH auth/me", "POST auth/second-factor/step-up", "PATCH auth/me"),
            network.requests.map { "${it.method} ${it.path}" }.filter { !it.startsWith("GET") }
        )
        val stepUp = network.requests.single { it.path == "auth/second-factor/step-up" }
        assertEquals(DataRequestPriority.Foreground, stepUp.priority)
        assertEquals("123456", stepUp.bodyText?.let { Json.parseToJsonElement(it).jsonObject["code"]?.jsonPrimitive?.content })
    }

    @Test
    fun cancellingADisconnectInSettingsShowsNoError() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("DELETE", "auth/identities/google", 403, passwordRefusal)
            }
        val store = settingsStore(network)

        launch { store.accept(SharedSettingsIntent.DisconnectAccountProvider("google")) }
        runCurrent()
        assertNotNull(store.state.value.secondFactorStepUp)

        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.Cancel))
        runCurrent()

        assertNull(store.state.value.secondFactorStepUp)
        assertEquals(
            SharedSettingsActionState(ACCOUNT_ACTION_PROVIDER_DISCONNECT, "google", inProgress = false),
            store.state.value.actionState
        )
        assertNull(store.state.value.routeErrorCode)
        assertEquals(listOf("DELETE auth/identities/google"), network.requests.map { "${it.method} ${it.path}" })
    }

    @Test
    fun signInAgainInSettingsSignsOut() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("DELETE", "auth/identities/google", 403, passwordRefusal)
                respond(
                    "POST",
                    "auth/second-factor/step-up",
                    403,
                    """{"error":"Sign in again to manage two-step verification.","code":"session_family_required"}"""
                )
            }
        val store = settingsStore(network)

        launch { store.accept(SharedSettingsIntent.DisconnectAccountProvider("google")) }
        runCurrent()
        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.InputChanged("123456")))
        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.Submit))
        assertEquals(SecondFactorStepUpBanner.SignInAgain, store.state.value.secondFactorStepUp?.banner)

        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.SignInAgain))
        runCurrent()

        assertNull(store.state.value.secondFactorStepUp)
        assertEquals(SharedSettingsEffect.Logout, store.state.value.effect)
    }

    @Test
    fun aMobileLinkRefusalFromTheHostStepsUpAndStartsTheLinkAgain() = runTest {
        val capabilities = StepUpLinkCapabilities()
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession()),
                        settingsRepository =
                            RealSettingsRepository(
                                InMemoryRaftAuthSessionStore(settingsStoreSampleSession()),
                                ScriptedAgentControlsNetworkClient().apply {
                                    respond("POST", "auth/second-factor/step-up", 200, """{"verified":true,"backupCodesRemaining":9}""")
                                }
                            ),
                        platformCapabilities = capabilities
                    ),
                scope = backgroundScope
            )

        launch { store.accept(SharedSettingsIntent.ConnectAccountProvider(AccountProviderLinkStartRequest("github"))) }
        runCurrent()
        assertEquals(SecondFactorStepUpMethod.Totp, store.state.value.secondFactorStepUp?.method)
        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.BackupCode)))
        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.InputChanged("abcd-efgh")))
        store.accept(SharedSettingsIntent.SecondFactorStepUp(SecondFactorStepUpIntent.Submit))
        runCurrent()

        assertEquals(listOf("github", "github"), capabilities.starts)
        assertNull(store.state.value.secondFactorStepUp)
        assertNull(store.state.value.actionState?.errorCode)
    }

    private fun TestScope.settingsStore(network: RaftNetworkClient): SharedSettingsStore {
        val sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession())
        return SharedSettingsStore(
            dataSource =
                SharedSettingsDataSource(
                    sessionStore = sessionStore,
                    settingsRepository = RealSettingsRepository(sessionStore, network)
                ),
            scope = backgroundScope
        )
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

    /** The host's first link start is refused (Android RaftCmpActivity's map, iOS alike); the next starts. */
    private class StepUpLinkCapabilities : SharedSettingsPlatformCapabilities {
        val starts = mutableListOf<String>()

        override suspend fun startMobileOAuthAccountLink(providerId: String): DataResult<Unit> {
            starts += providerId
            if (starts.size > 1) return DataResult.Content(Unit)
            return DataResult.Error(
                ScreenError(
                    code = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
                    retryable = false,
                    secondFactorStepUp = parseSharedMobileOAuthLinkStepUpChallenge("totp,backup_code", "Confirm it's you")
                )
            )
        }
    }
}
