package build.raft.app

import build.raft.app.RaftHostNavigationDecision.Allow
import build.raft.app.RaftHostNavigationDecision.ReissueWithHeader
import build.raft.app.RaftHostNavigationDecision.ReissueWithoutHeader
import build.raft.app.RaftHostNavigationKind.History
import build.raft.app.RaftHostNavigationKind.Initial
import build.raft.app.RaftHostNavigationKind.Page
import build.raft.app.RaftHostNavigationKind.Redirect
import build.raft.app.RaftHostNavigationKind.Reload
import build.raft.app.matches
import build.raft.app.settings.matches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * App entries (#356): the scope an entry's header is sent to, the header's value, what each main-frame
 * navigation does about it, and the in-memory registry (RaftAppEntry.kt). The scope cases port slock's
 * desktop `parseWebPreviewAppOrigin` tests (apps/desktop/src/main/webPreviewTabs.test.ts).
 */
class RaftAppEntryRulesTest {
    private val raftHosts = setOf("app.raft.build", "api.raft.build")
    private val app = "https://app.example.com"
    private val appHome = "https://app.example.com/home"
    private val host = RaftHostHeader(origin = app, value = "android")
    private val request = RaftAppEntryRequest(scopeOrigin = app, appName = "Orbital Notes", preferredServerId = "server-1")

    private fun scope(origin: String, url: String, debug: Boolean = false): String? =
        raftAppEntryScopeOrigin(origin, url, raftHosts, debug)

    private fun decide(
        url: String,
        method: String = "GET",
        kind: RaftHostNavigationKind = Page,
        carriesHeader: Boolean = false,
        host: RaftHostHeader? = this.host,
        budgetLeft: Int = RAFT_HOST_REISSUE_BUDGET,
        committed: Boolean = false,
    ): RaftHostNavigationDecision = raftHostNavigationDecision(url, method, kind, carriesHeader, host, budgetLeft, committed)

    @Test
    fun scopeIsHttpsOnAnAsciiHostAndNeverOneOfRaftsOwn() {
        listOf(
            "https://bücher.example" to "https://bücher.example/",
            "https://аррӏе.com" to "https://аррӏе.com/",
            "https://app%2Eexample.com" to "https://app%2Eexample.com/",
            "https://app.raft.build" to "https://app.raft.build/x",
            "https://api.raft.build" to "https://API.raft.build/",
            "https://API.raft.build" to "https://api.raft.build/",
            "https://app.raft.build." to "https://app.raft.build./",
            "http://app.example.com" to "http://app.example.com/",
            "ftp://app.example.com" to "ftp://app.example.com/",
            "javascript:alert(1)" to "javascript:alert(1)",
        ).forEach { (origin, url) -> assertNull(scope(origin, url, debug = true), origin) }
        assertEquals("https://localhost:5173", scope("https://localhost:5173", "https://localhost:5173/"), "https on any host")
    }

    @Test
    fun aLoadTheAppMakesCarriesTheHeaderOnTheEntrysOriginOnly() {
        assertEquals("android", raftHostHeaderValueFor(appHome, host))
        assertEquals("android", raftHostHeaderValueFor("https://APP.example.com/callback?code=abc", host))
        listOf("https://evil.example.com/", "https://app.example.com:8443/", "http://app.example.com/", "https://user@app.example.com/")
            .forEach { assertNull(raftHostHeaderValueFor(it, host), it) }
        assertNull(raftHostHeaderValueFor(appHome, null))
    }

    @Test
    fun aRequestCarryingTheHeaderOffTheOriginIsReissuedWithoutIt() {
        listOf(
            "https://evil.example.com/",
            "http://app.example.com/",
            "https://app.example.com:8443/",
            "https://app.example.com./",
            "https://app.example.com.evil.example/",
            "https://user@app.example.com/",
            "https://my_app.example.com/",
        ).forEach { url ->
            RaftHostNavigationKind.entries.forEach { kind ->
                assertEquals(ReissueWithoutHeader, decide(url, kind = kind, carriesHeader = true), "$url ${kind.name}")
            }
        }
        assertEquals(ReissueWithoutHeader, decide("https://evil.example.com/", carriesHeader = true, budgetLeft = 0), "the budget aside")
        assertEquals(ReissueWithoutHeader, decide("https://evil.example.com/", kind = Redirect, carriesHeader = true, committed = true), "even after commit")
        assertEquals(ReissueWithoutHeader, decide(appHome, carriesHeader = true, host = null), "not an entry page: no header at all")
    }

    @Test
    fun reissueWithHeaderRunsOnlyForPreCommitSameOriginGetRedirects() {
        assertEquals(ReissueWithHeader, decide(appHome, kind = Redirect, carriesHeader = false, committed = false))
        assertEquals(Allow, decide(appHome, kind = Redirect, carriesHeader = true, committed = false), "already carries header")
        assertEquals(Allow, decide(appHome, kind = Redirect, carriesHeader = false, committed = false, budgetLeft = 0), "budget exhausted")
        assertEquals(Allow, decide(appHome, kind = Redirect, method = "POST", carriesHeader = false, committed = false), "never POST")

        // Document-initiated Page navigations (links, GET forms like Stamp /login?agree=1) and
        // post-commit redirects must not be cancelled and re-issued, preserving Sec-Fetch-Site: same-origin.
        assertEquals(Allow, decide(appHome, kind = Page, carriesHeader = false, committed = false))
        assertEquals(Allow, decide(appHome, kind = Page, carriesHeader = false, committed = true))
        assertEquals(Allow, decide(appHome, kind = Redirect, carriesHeader = false, committed = true))
        assertEquals(Allow, decide(appHome, kind = Initial, carriesHeader = false, committed = false))
        assertEquals(Allow, decide(appHome, kind = History, carriesHeader = false, committed = true))
        assertEquals(Allow, decide(appHome, kind = Reload, carriesHeader = false, committed = true))
    }

    @Test
    fun anEntryIsClaimedForItsExactAddressByOnePage() {
        val registry = RaftAppEntryRegistry()
        val token = registry.register(appHome, request)
        assertNull(registry.claim(token, "$appHome/", "page-1"), "another address")
        assertNull(registry.claim(token, "https://app.example.com/Home", "page-1"))
        assertNull(registry.claim("not-a-token", appHome, "page-1"))
        assertEquals(request, registry.claim(token, appHome, "page-1"))
        assertEquals(request, registry.claim(token, appHome, "page-1"), "the page that has it, composed again")
        assertNull(registry.claim(token, appHome, "page-2"), "any other page with the same page data")
        assertNull(RaftAppEntryRegistry().claim(token, appHome, "page-1"), "another registry: a process started since")
    }

    @Test
    fun tokensAreRandomVersion4Uuids() {
        val registry = RaftAppEntryRegistry()
        val tokens = List(32) { registry.register(appHome, request) }
        assertEquals(32, tokens.toSet().size)
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        tokens.forEach { assertTrue(uuid.matches(it), it) }
    }
}
