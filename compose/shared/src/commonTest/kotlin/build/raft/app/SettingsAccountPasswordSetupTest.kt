package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.ScriptedAgentControlsNetworkClient
import build.raft.app.data.sendSharedPasswordSetupEmail
import build.raft.app.settings.RaftSettingsConnectedAccount
import build.raft.app.settings.SettingsAccountPasswordRow
import build.raft.app.settings.SettingsAccountPasswordSetupIntent
import build.raft.app.settings.SettingsAccountPasswordSetupProvider
import build.raft.app.settings.SettingsAccountPasswordSetupState
import build.raft.app.settings.SettingsAccountPasswordSetupStore
import build.raft.app.settings.settingsAccountDisconnectRequiresPassword
import build.raft.app.settings.settingsAccountPasswordRow
import build.raft.app.thread.dispatch
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
import kotlin.test.assertTrue

/**
 * Settings › Account for an account without a password (web AccountSection, botiverse/slock
 * staging 4be606f7fd789488e69df1095d4af028cc21ee49): which Password row shows, when Disconnect
 * asks "Set a password first", and the setup email (`POST /api/auth/forgot-password`; routes/auth.ts
 * answers `{ ok, message }`, app.ts's limiter refuses with 429 `{ error }`).
 */
class SettingsAccountPasswordSetupTest {
    private val okBody = """{"ok":true,"message":"If an account exists with that email, a reset link has been sent."}"""
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
}
