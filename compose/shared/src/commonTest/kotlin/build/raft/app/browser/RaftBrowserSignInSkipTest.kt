package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Raft's sign-in from a page of the in-app browser (#356 §5 step 5): when a request the person already
 * consented to is approved without the sheet ([raftSignInSkipTarget]). Ports the consent-skip cases of
 * slock's web (packages/web/tests/desktopBrowserSignIn.behavior.test.tsx, #proj-workspace:#357):
 * anything less certain than "consented to every scope asked, where the sheet would sign in" shows the
 * sheet, and the server checks the consent again (`confirmSkipped`).
 */
class RaftBrowserSignInSkipTest {
    private val returnUrl = "https://orbital.example.com/return"
    private val setupSearch =
        "?flow=oidc&client_id=orbital-notes&return_to=https%3A%2F%2Forbital.example.com%2Freturn" +
            "&scope=openid+profile&state=state-1&nonce=nonce-1"
    private val alpha = RaftSignInServer(id = "server-a", slug = "alpha")
    private val launch = RaftSignInServer(id = "server-1", slug = "launch")
    private val servers = listOf(alpha, launch)
    private val consented = RaftSignInConsent(granted = true, coversRequested = true)

    private fun lookup(
        server: RaftSignInServer,
        consent: RaftSignInConsent? = consented,
        availability: RaftSignInAvailability = RaftSignInAvailability.Ready,
        loginMode: RaftSignInLoginMode = RaftSignInLoginMode.Server,
        returnUrl: String? = this.returnUrl,
        scopeAllowed: Boolean = true,
    ): RaftSignInLookup =
        RaftSignInLookup(
            serverId = server.id,
            name = "Orbital Notes",
            returnUrl = returnUrl,
            availability = availability,
            loginMode = loginMode,
            scopeAllowed = scopeAllowed,
            consent = consent,
        )

    /** The conversation's Server is Launch, as in the web's cases. */
    private fun skip(
        lookups: List<RaftSignInLookup>,
        search: String = setupSearch,
        preferredServerId: String? = launch.id,
    ): RaftSignInSkipTarget? =
        raftSignInSkipTarget(assertNotNull(raftSignInSetupRequest(search), search), lookups, servers, preferredServerId)

    private fun onServer(server: RaftSignInServer) = RaftSignInSkipTarget.Server(serverId = server.id, name = "Orbital Notes")

    @Test
    fun consentedOnTheConversationsServerIsApprovedThereWithoutTheSheet() {
        assertEquals(onServer(launch), skip(listOf(lookup(alpha), lookup(launch))))
    }

    @Test
    fun theSheetAsksWhenTheAppAsksToConfirmAgainOrTheConsentDoesNotCoverTheRequest() {
        listOf(
            "prompt=consent" to "$setupSearch&prompt=consent",
            "prompt=login" to "$setupSearch&prompt=login",
            "prompt=none login" to "$setupSearch&prompt=none+login",
        ).forEach { (label, search) -> assertNull(skip(listOf(lookup(alpha), lookup(launch)), search = search), label) }
        listOf(
            "scopes not covered" to RaftSignInConsent(granted = true, coversRequested = false),
            "not granted" to RaftSignInConsent(granted = false, coversRequested = true),
            "no consent" to null,
        ).forEach { (label, consent) ->
            assertNull(skip(listOf(lookup(alpha, consent = consent), lookup(launch, consent = consent))), label)
        }
        assertEquals(onServer(launch), skip(listOf(lookup(launch)), search = "$setupSearch&prompt=none"), "another prompt")
    }

    @Test
    fun anOlderServerThatSendsNoConsentNeverSkips() {
        val older = RaftSignInLookup(serverId = launch.id, name = "Orbital Notes", returnUrl = returnUrl)
        assertNull(skip(listOf(older)))
        assertNull(skip(listOf(older, older.copy(serverId = alpha.id)), preferredServerId = null))
    }

    @Test
    fun thePreferredServerReadyButNotConsentedShowsTheSheetEvenWithAConsentElsewhere() {
        assertNull(skip(listOf(lookup(alpha), lookup(launch, consent = null))))
        assertNull(skip(listOf(lookup(alpha), lookup(launch, scopeAllowed = false))), "its scopes not allowed there")
    }

    @Test
    fun withThePreferredServerNotReadyTheOneServerTheConsentCoversIsApproved() {
        assertEquals(onServer(alpha), skip(listOf(lookup(alpha))), "the App is not open on the conversation's Server")
        listOf(RaftSignInAvailability.InstallRequired, RaftSignInAvailability.Suspended, RaftSignInAvailability.Unknown)
            .forEach { availability ->
                assertEquals(onServer(alpha), skip(listOf(lookup(alpha), lookup(launch, availability = availability))), availability.name)
            }
        assertEquals(onServer(alpha), skip(listOf(lookup(alpha), lookup(launch, consent = null)), preferredServerId = null))
        assertNull(skip(listOf(lookup(alpha), lookup(launch)), preferredServerId = null), "two Servers covered: which is not guessed")
        assertNull(skip(listOf(lookup(alpha, availability = RaftSignInAvailability.Suspended))), "a consent where it can't sign in")
    }

    @Test
    fun aServerHintNamesTheServerByIdOrSlug() {
        listOf("server=server-a", "server=alpha").forEach { hint ->
            assertEquals(onServer(alpha), skip(listOf(lookup(alpha), lookup(launch)), search = "$setupSearch&$hint"), hint)
        }
        assertNull(
            skip(listOf(lookup(alpha), lookup(launch, consent = null)), search = "$setupSearch&server=launch"),
            "the hinted Server not consented",
        )
        assertNull(skip(listOf(lookup(alpha), lookup(launch)), search = "$setupSearch&server=elsewhere"), "a Server the person is not in")
    }

    @Test
    fun anAccountModeAppIsApprovedWithTheAccountOnlyWithItsConsentAndNoServerHint() {
        val account = listOf(lookup(alpha, loginMode = RaftSignInLoginMode.Account), lookup(launch, loginMode = RaftSignInLoginMode.Account))
        assertEquals(RaftSignInSkipTarget.Account(name = "Orbital Notes"), skip(account))
        assertNull(skip(account, search = "$setupSearch&server=launch"), "the server refuses a Server-scoped issuer there")
        assertNull(skip(account.map { it.copy(consent = null) }), "no account consent")
        assertNull(skip(account.map { it.copy(consent = RaftSignInConsent(granted = true, coversRequested = false)) }))
    }

    @Test
    fun onlyLookupsRegisteredWithTheRequestsReturnAddressCount() {
        val other = "https://orbital.example.com/other"
        assertNull(skip(listOf(lookup(alpha, returnUrl = other), lookup(launch, returnUrl = other))), "consented, but not this address")
        assertEquals(onServer(alpha), skip(listOf(lookup(alpha), lookup(launch, returnUrl = other))))
        assertNull(skip(emptyList()), "no lookup answered")
    }
}
