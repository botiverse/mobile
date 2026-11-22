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
}
