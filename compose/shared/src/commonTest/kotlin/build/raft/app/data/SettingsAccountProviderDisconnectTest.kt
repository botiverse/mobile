package build.raft.app.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Web Disconnect (SettingsPanel `handleDisconnectProvider`): `DELETE /api/auth/identities/:provider`.
 * Bodies follow botiverse/slock 40ff1fec82cb8f7b97d2691a2cdd4b01ff45501f
 * (`packages/server/src/routes/auth.ts`, `userService.unlinkSocialIdentity`): 200
 * `{ unlinked, identities: [{ provider, providerEmail }], passwordConfigured }`, 409
 * `{ code: "PASSWORD_CREDENTIAL_REQUIRED", error }` for the last sign-in without a password.
 */
class SettingsAccountProviderDisconnectTest {
    private val account = """{"id":"user-1","email":"user@slock.ai","name":"slock-user","displayName":"Slock User","emailVerified":true}"""
    private val providers = """{"providers":[{"id":"google","label":"Google","enabled":true},{"id":"github","label":"GitHub","enabled":true}]}"""

    @Test
    fun parsesTheUnlinkResponse() {
        val result =
            parseSharedAccountProviderUnlinkResult(
                """{"unlinked":true,"identities":[{"provider":"google","providerEmail":"user@gmail.com"}],"passwordConfigured":true}"""
            )

        assertEquals(AccountProviderUnlinkResult(unlinked = true, linkedProviderIds = listOf("google"), passwordConfigured = true), result)
        assertNull(parseSharedAccountProviderUnlinkResult("""{"identities":[]}"""))
    }

    @Test
    fun disconnectDeletesTheIdentityAndReadsTheAccountAgain() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("DELETE", "auth/identities/github", 200, """{"unlinked":true,"identities":[],"passwordConfigured":true}""")
                respond("GET", "auth/me", 200, account)
                respond("GET", "auth/providers", 200, providers)
                respond("GET", "auth/identities", 200, """{"identities":[],"passwordConfigured":true}""")
            }
        val repository = RealSettingsRepository(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()), network)

        val result = repository.disconnectAccountProvider("GitHub")

        assertIs<DataResult.Content<AccountSettingsSnapshot>>(result)
        assertEquals(
            ConnectedAccountStatus.NotConnected,
            result.data.connectedAccounts.single { it.providerId == "github" }.status
        )
        val delete = network.requests.first()
        assertEquals("DELETE" to "auth/identities/github", delete.method to delete.path)
        assertEquals(DataRequestPriority.Foreground, delete.priority)
    }

    @Test
    fun theLastSignInWithoutAPasswordKeepsTheServerReason() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond(
                    "DELETE",
                    "auth/identities/github",
                    409,
                    """{"code":"PASSWORD_CREDENTIAL_REQUIRED","error":"Set a password before disconnecting your final sign-in account."}"""
                )
            }
        val repository = RealSettingsRepository(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()), network)

        val result = repository.disconnectAccountProvider("github")

        assertIs<DataResult.Error>(result)
        assertEquals("Set a password before disconnecting your final sign-in account.", result.error.serverMessage)
        assertEquals(listOf("auth/identities/github"), network.requests.map { it.path })
    }

    @Test
    fun aLinkedProviderTheServerNoLongerOffersStaysListed() {
        val accounts =
            parseSharedAccountConnectedAccounts(
                providersBodyText = """{"providers":[{"id":"google","label":"Google","enabled":true},{"id":"github","label":"GitHub","enabled":false}]}""",
                identitiesBodyText = """{"identities":[{"provider":"github","providerEmail":"user@github.com"}],"passwordConfigured":true}""",
                fallbackEmail = "user@slock.ai"
            )

        assertEquals(listOf("password", "google", "github"), accounts?.map { it.providerId })
        assertEquals(ConnectedAccountStatus.Connected, accounts?.single { it.providerId == "github" }?.status)
    }
}
