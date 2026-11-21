package build.raft.app

import build.raft.app.data.ACCOUNT_ACTION_PROVIDER_CONNECT
import build.raft.app.data.ACCOUNT_ACTION_PROVIDER_DISCONNECT
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.ScreenError
import build.raft.app.data.ScriptedAgentControlsNetworkClient
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.sendSharedPasswordSetupEmail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Account for an account without a password (web AccountSection, botiverse/slock
 * staging 4be606f7fd789488e69df1095d4af028cc21ee49): which Password row shows, when Disconnect
 * asks "Set a password first", and the setup email (`POST /api/auth/forgot-password`; routes/auth.ts
 * answers `{ ok, message }`, app.ts's limiter refuses with 429 `{ error }`).
 */
class SettingsAccountPasswordSetupTest {
    private val traceId = "0af7651916cd43dd8448eb211c80319c"
    private val traceHeaders = mapOf("X-Trace-Id" to listOf(traceId))
    private val okBody = """{"ok":true,"message":"If an account exists with that email, a reset link has been sent."}"""
    private val tooMany = "Too many password reset requests. Please try again later."
    private val fallback = "Failed to send password setup email"
    private val github = SettingsAccountPasswordSetupProvider(id = "github", label = "GitHub")

    private fun passwordSetupStore(network: ScriptedAgentControlsNetworkClient, scope: CoroutineScope) =
        SettingsAccountPasswordSetupStore(sendEmail = { email -> network.sendSharedPasswordSetupEmail(email) }, scope = scope)

    private fun connected(id: String) = RaftSettingsConnectedAccount(id, id, "$id@example.com", "connected", canConnect = false)

    private fun notConnected(id: String) = RaftSettingsConnectedAccount(id, id, "Not connected", "notConnected", canConnect = true)

    @Test
    fun onlyAnAccountWithoutAPasswordGetsSetAPassword() {
        assertEquals(SettingsAccountPasswordRow.SetPassword, settingsAccountPasswordRow(passwordConfigured = false))
        assertEquals(SettingsAccountPasswordRow.ChangePassword, settingsAccountPasswordRow(passwordConfigured = true))
        // Not read yet, or GET /auth/identities failed (the web's `null`): Change password stays.
        assertEquals(SettingsAccountPasswordRow.ChangePassword, settingsAccountPasswordRow(passwordConfigured = null))
    }

    @Test
    fun disconnectAsksForAPasswordOnlyForTheOneLinkedSignInOfAnAccountWithoutOne() {
        val githubOnly = listOf(connected("github"), notConnected("google"))
        val both = listOf(connected("github"), connected("google"))

        assertTrue(settingsAccountDisconnectRequiresPassword(githubOnly, passwordConfigured = false))
        assertFalse(settingsAccountDisconnectRequiresPassword(both, passwordConfigured = false), "one of two linked sign-ins disconnects as before")
        assertFalse(settingsAccountDisconnectRequiresPassword(githubOnly, passwordConfigured = true))
        assertFalse(settingsAccountDisconnectRequiresPassword(githubOnly, passwordConfigured = null), "not read is not 'no password'")
    }

    @Test
    fun setPasswordByEmailSendsTheAccountsAddressAndSaysItWentOut() = runTest {
        val network = ScriptedAgentControlsNetworkClient().apply { respond("POST", "auth/forgot-password", 200, okBody) }
        val store = passwordSetupStore(network, backgroundScope)

        store.dispatch(SettingsAccountPasswordSetupIntent.Send(email = "owner@example.com"))
        runCurrent()

        val request = network.requests.single()
        assertEquals("POST" to "auth/forgot-password", request.method to request.path)
        assertEquals(DataRequestPriority.Foreground, request.priority)
        assertEquals("owner@example.com", Json.parseToJsonElement(request.bodyText.orEmpty()).jsonObject["email"]?.jsonPrimitive?.content)
        // The row's own success line ("Password setup email sent. Use the link to set your password.").
        assertEquals(SettingsAccountPasswordSetupState(sent = true), store.state.value)
    }

    @Test
    fun setAPasswordFirstSaysWhichProviderItWasSentFor() = runTest {
        val network = ScriptedAgentControlsNetworkClient().apply { respond("POST", "auth/forgot-password", 200, okBody) }
        val store = passwordSetupStore(network, backgroundScope)

        store.dispatch(SettingsAccountPasswordSetupIntent.Send(email = "owner@example.com", provider = github))
        runCurrent()
        // The line under Connected accounts ("…then return here to disconnect GitHub.") instead of the row's.
        assertEquals(SettingsAccountPasswordSetupState(sent = true, sentForProvider = github), store.state.value)

        // Disconnect pressed again (web handleRequestDisconnectProvider): the provider's line goes, "sent" stays.
        store.dispatch(SettingsAccountPasswordSetupIntent.DisconnectRequested)
        assertEquals(SettingsAccountPasswordSetupState(sent = true), store.state.value)
    }

    @Test
    fun aFailedSendShowsTheServersWordsOrTheFallbackWithTheTraceId() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("POST", "auth/forgot-password", 429, """{"error":"$tooMany"}""", headers = traceHeaders)
                respond("POST", "auth/forgot-password", 500, "{}", headers = traceHeaders)
            }
        val store = passwordSetupStore(network, backgroundScope)

        store.dispatch(SettingsAccountPasswordSetupIntent.Send(email = "owner@example.com"))
        runCurrent()
        val refused = store.state.value
        assertFalse(refused.sending)
        assertFalse(refused.sent, "nothing went out")
        assertEquals(tooMany, refused.error?.serverMessage)
        assertEquals(traceId, refused.error?.traceId)
        assertEquals(tooMany, refused.error?.let { settingsAccountPasswordSetupErrorText(it, fallback) })

        // A server answer without words: the page's own sentence, still with the request's trace ID.
        store.dispatch(SettingsAccountPasswordSetupIntent.Send(email = "owner@example.com"))
        runCurrent()
        val failed = store.state.value.error
        assertNull(failed?.serverMessage)
        assertEquals(traceId, failed?.traceId)
        assertEquals(fallback, failed?.let { settingsAccountPasswordSetupErrorText(it, fallback) })
        assertEquals(2, network.requests.size)
    }

    @Test
    fun aFailureAfterASendKeepsTheEarlierSuccessAndDisconnectClearsIt() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("POST", "auth/forgot-password", 200, okBody)
                respond("POST", "auth/forgot-password", 429, """{"error":"$tooMany"}""", headers = traceHeaders)
            }
        val store = passwordSetupStore(network, backgroundScope)

        store.dispatch(SettingsAccountPasswordSetupIntent.Send(email = "owner@example.com"))
        runCurrent()
        store.dispatch(SettingsAccountPasswordSetupIntent.Send(email = "owner@example.com"))
        runCurrent()
        // As the web: `passwordSetupEmailSent` stays true, the failure shows as well.
        assertTrue(store.state.value.sent)
        assertEquals(tooMany, store.state.value.error?.serverMessage)

        store.dispatch(SettingsAccountPasswordSetupIntent.DisconnectRequested)
        assertNull(store.state.value.error)
    }

    @Test
    fun oneSendAtATime() = runTest {
        val gate = CompletableDeferred<Unit>()
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("POST", "auth/forgot-password", 200, okBody)
                hold("POST", "auth/forgot-password", gate)
            }
        val store = passwordSetupStore(network, backgroundScope)

        store.dispatch(SettingsAccountPasswordSetupIntent.Send(email = "owner@example.com"))
        runCurrent()
        assertTrue(store.state.value.sending, "in flight: \"Sending setup email…\", the buttons off")
        assertFalse(store.state.value.sent)
        // Another tap meanwhile (the row's button, or the dialog's) sends nothing.
        store.dispatch(SettingsAccountPasswordSetupIntent.Send(email = "owner@example.com", provider = github))
        runCurrent()
        assertEquals(1, network.requests.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(SettingsAccountPasswordSetupState(sent = true), store.state.value, "the first send's answer; the dropped one marks no provider")
        assertEquals(1, network.requests.size)
    }

    @Test
    fun onlyADisconnectRefusedForThePasswordReadsTheSignInMethodsAgain() {
        // routes/auth.ts DELETE /identities/:provider: 409 `{ code: "PASSWORD_CREDENTIAL_REQUIRED", error }`.
        val refused =
            ScreenError(
                code = "http_409",
                serverMessage = "Set a password before disconnecting your final sign-in account.",
                serverCode = SETTINGS_ACCOUNT_PASSWORD_CREDENTIAL_REQUIRED_CODE,
                traceId = traceId,
            )

        assertTrue(settingsAccountDisconnectRefusedForPassword(SharedSettingsActionState(ACCOUNT_ACTION_PROVIDER_DISCONNECT, "github", screenError = refused)))
        assertFalse(settingsAccountDisconnectRefusedForPassword(SharedSettingsActionState(ACCOUNT_ACTION_PROVIDER_DISCONNECT, "github", inProgress = true)))
        assertFalse(
            settingsAccountDisconnectRefusedForPassword(
                SharedSettingsActionState(ACCOUNT_ACTION_PROVIDER_DISCONNECT, "github", screenError = refused.copy(serverCode = "AUTH_PROVIDER_NOT_FOUND"))
            )
        )
        assertFalse(settingsAccountDisconnectRefusedForPassword(SharedSettingsActionState(ACCOUNT_ACTION_PROVIDER_CONNECT, "github", screenError = refused)))
        assertFalse(settingsAccountDisconnectRefusedForPassword(null))
    }
}
