package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsAccountIdentitiesParserTest {
    private val providers = """{"providers":[{"id":"google","label":"Google","enabled":true},{"id":"github","label":"GitHub","enabled":true}]}"""

    @Test
    fun passwordlessAccountMarksThePasswordCredentialNotConnected() {
        val identities = """{"identities":[{"provider":"google","providerEmail":"a@raft.build"}],"passwordConfigured":false}"""
        val accounts = parseSharedAccountConnectedAccounts(providers, identities, fallbackEmail = "a@raft.build")!!

        assertEquals(false, parseSharedAccountPasswordConfigured(identities))
        assertEquals(ConnectedAccountStatus.NotConnected, accounts.first { it.providerId == "password" }.status)
        val google = accounts.first { it.providerId == "google" }
        assertEquals(ConnectedAccountStatus.Connected, google.status)
        assertEquals("a@raft.build", google.identifier)
        assertEquals(ConnectedAccountStatus.NotConnected, accounts.first { it.providerId == "github" }.status)
    }

    @Test
    fun passwordConfiguredKeepsThePasswordCredentialAndMissingFieldIsUnknown() {
        val withPassword = """{"identities":[],"passwordConfigured":true}"""
        val accounts = parseSharedAccountConnectedAccounts(providers, withPassword, fallbackEmail = "a@raft.build")!!

        assertEquals(true, parseSharedAccountPasswordConfigured(withPassword))
        assertEquals(ConnectedAccountStatus.Connected, accounts.first { it.providerId == "password" }.status)
        assertNull(parseSharedAccountPasswordConfigured("""{"identities":[]}"""))
    }

    @Test
    fun passwordSetupBodyCarriesTheEmail() {
        assertEquals("""{"email":"a@raft.build"}""", buildSharedAccountPasswordSetupBody("a@raft.build"))
    }
}
