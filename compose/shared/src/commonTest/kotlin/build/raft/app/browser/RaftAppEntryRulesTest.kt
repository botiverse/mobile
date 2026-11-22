package build.raft.app

import build.raft.app.RaftHostNavigationDecision.Allow
import build.raft.app.RaftHostNavigationDecision.ReissueWithHeader
import build.raft.app.RaftHostNavigationDecision.ReissueWithoutHeader
import build.raft.app.RaftHostNavigationKind.History
import build.raft.app.RaftHostNavigationKind.Initial
import build.raft.app.RaftHostNavigationKind.Page
import build.raft.app.RaftHostNavigationKind.Redirect
import build.raft.app.RaftHostNavigationKind.Reload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    ): RaftHostNavigationDecision = raftHostNavigationDecision(url, method, kind, carriesHeader, host, budgetLeft)

    @Test
    fun scopeIsTheOpenedAddressesOwnOriginWrittenExactly() {
        assertEquals(app, scope(app, appHome))
        assertEquals(app, scope(app, "https://APP.example.com:443/login?next=%2F#top"), "any address on the origin")
        assertEquals("https://app.example.com:8443", scope("https://app.example.com:8443", "https://app.example.com:8443/"))
        assertEquals("https://xn--bcher-kva.example", scope("https://xn--bcher-kva.example", "https://xn--bcher-kva.example/"))
        assertEquals("https://app.example.com.", scope("https://app.example.com.", "https://app.example.com./"))
        listOf(
            app to "https://evil.example.com/",
            app to "https://app.example.com:8443/",
            app to "https://app.example.com./",
            app to "http://app.example.com/",
            app to "https://user:pass@app.example.com/home",
            app to "",
            "https://app.example.com/" to appHome,
            "https://app.example.com/home" to appHome,
            "https://app.example.com?x=1" to appHome,
            "HTTPS://APP.EXAMPLE.COM" to appHome,
            "https://app.example.com:443" to appHome,
            "https://user:pass@app.example.com" to appHome,
            "null" to appHome,
            "" to appHome,
        ).forEach { (origin, url) -> assertNull(scope(origin, url, debug = true), "$origin for $url") }
    }

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
    fun plainHttpOnlyOnLoopbackAndOnlyInADebugBuild() {
        listOf("http://localhost:5173", "http://localhost", "http://127.0.0.1:3000", "http://[::1]:5173", "http://10.0.2.2:8080")
            .forEach { origin ->
                assertEquals(origin, scope(origin, "$origin/login?next=%2F", debug = true), origin)
                assertNull(scope(origin, "$origin/login", debug = false), "a release build: $origin")
            }
        listOf("http://192.168.1.4:3000", "http://localhost.evil.example", "http://10.0.2.3").forEach { origin ->
            assertNull(scope(origin, "$origin/", debug = true), "not this device: $origin")
        }
        assertNull(scope("http://[::1]:5173", "http://[::1]:8080/", debug = true), "another port of the loopback")
        assertNull(scope("http://[0:0:0:0:0:0:0:1]:5173", "http://[0:0:0:0:0:0:0:1]:5173/", debug = true))
        assertNull(scope("http://[::1]:5173", "http://user@[::1]:5173/", debug = true))
    }

    @Test
    fun theHeaderValueIsThePlatformsAndDesktopSendsNone() {
        assertEquals("android", raftHostHeaderValue("android"))
        assertEquals("ios", raftHostHeaderValue("iOS"))
        assertNull(raftHostHeaderValue("desktop"))
        assertNull(raftHostHeaderValue(""))
    }

    @Test
    fun theContextChecksTheScopeAgainAndNamesThePlatformsHeader() {
        val context = assertNotNull(raftAppEntryContext(request, appHome, raftHosts, debug = false, platformName = "android"))
        assertEquals(RaftAppEntryContext("android", app, "Orbital Notes", "server-1"), context)
        assertEquals(RaftHostHeader(app, "android"), context.raftHost)
        assertEquals(
            RaftHostHeader(app, "ios"),
            raftAppEntryContext(request, appHome, raftHosts, debug = false, platformName = "iOS")?.raftHost,
        )
        val desktop = assertNotNull(raftAppEntryContext(request, appHome, raftHosts, debug = false, platformName = "desktop"))
        assertNull(desktop.raftHost, "no header where the platform sends none")
        assertNull(raftAppEntryContext(request, "https://evil.example.com/", raftHosts, debug = false, platformName = "android"))
        assertNull(
            raftAppEntryContext(
                request.copy(scopeOrigin = "https://app.raft.build"),
                "https://app.raft.build/",
                raftHosts,
                debug = false,
                platformName = "android",
            ),
        )
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
    fun sameOriginLinksAndRedirectsWithoutTheHeaderAreReissuedWithIt() {
        listOf(Page, Redirect).forEach { kind ->
            assertEquals(ReissueWithHeader, decide("https://app.example.com/login?next=%2F", kind = kind), kind.name)
        }
        assertEquals(ReissueWithHeader, decide(appHome, method = "get"))
        assertEquals(ReissueWithHeader, decide("https://APP.EXAMPLE.COM:443/x"))
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
        assertEquals(ReissueWithoutHeader, decide(appHome, carriesHeader = true, host = null), "not an entry page: no header at all")
    }

    @Test
    fun everythingElseGoesOnAsItIs() {
        listOf(Initial, History, Reload).forEach { kind -> assertEquals(Allow, decide(appHome, kind = kind), kind.name) }
        assertEquals(Allow, decide(appHome, method = "POST"), "a POST can't be sent again")
        assertEquals(Allow, decide("https://evil.example.com/", method = "POST", carriesHeader = true))
        assertEquals(Allow, decide(appHome, carriesHeader = true), "it has the header already")
        assertEquals(Allow, decide("https://evil.example.com/"), "another origin, without the header")
        assertEquals(Allow, decide(appHome, host = null), "not an entry page")
        listOf("javascript:alert(1)", "intent://scan/#Intent;end", "about:blank", "data:text/html,hi").forEach { url ->
            assertEquals(Allow, decide(url, carriesHeader = true), url)
        }
    }

    @Test
    fun aChainReissuesWithTheHeaderOnlyWhileItsBudgetLasts() {
        val decisions = (RAFT_HOST_REISSUE_BUDGET downTo 0).map { budgetLeft -> decide(appHome, kind = Redirect, budgetLeft = budgetLeft) }
        assertEquals(List(10) { ReissueWithHeader } + Allow, decisions)
        assertEquals(Allow, decide(appHome, budgetLeft = -1))
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
    fun releaseAndResetLeaveNoEntry() {
        val registry = RaftAppEntryRegistry()
        val token = registry.register(appHome, request)
        assertEquals(request, registry.claim(token, appHome, "page-1"))
        registry.release(token, "page-2")
        assertEquals(request, registry.claim(token, appHome, "page-1"), "only its page releases it")
        registry.release(token, "page-1")
        assertNull(registry.claim(token, appHome, "page-1"))

        val unclaimed = registry.register(appHome, request)
        registry.release(unclaimed, "page-3")
        assertNull(registry.claim(unclaimed, appHome, "page-3"), "a page that closed before it claimed its entry")

        val claimed = registry.register(appHome, request)
        val waiting = registry.register("https://app.example.com/other", request)
        assertEquals(request, registry.claim(claimed, appHome, "page-4"))
        registry.reset()
        assertNull(registry.claim(claimed, appHome, "page-4"))
        assertNull(registry.claim(waiting, "https://app.example.com/other", "page-5"))
    }

    @Test
    fun tokensAreRandomVersion4Uuids() {
        val registry = RaftAppEntryRegistry()
        val tokens = List(32) { registry.register(appHome, request) }
        assertEquals(32, tokens.toSet().size)
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        tokens.forEach { assertTrue(uuid.matches(it), it) }
    }

    @Test
    fun raftsHostsAreTheEnvironmentsTheLinkHostsAndTheClientConfigs() {
        val hosts =
            raftBrowserRaftHosts(
                webUrl = "https://raft-app-staging.botiverse.dev",
                apiBaseUrl = "https://api-aws-staging.botiverse.dev/api/",
                appHosts = listOf(" Open.Example.Build. "),
            )
        listOf(
            "raft-app-staging.botiverse.dev",
            "api-aws-staging.botiverse.dev",
            "app.raft.build",
            "open.raft.build",
            "app.slock.ai",
            "open.example.build",
            "APP.RAFT.BUILD.",
        ).forEach { assertTrue(raftBrowserIsRaftHost(it, hosts), it) }
        listOf("evil-app.raft.build", "app.raft.build.evil.example", "app.example.com").forEach {
            assertFalse(raftBrowserIsRaftHost(it, hosts), it)
        }
        assertNull(
            raftAppEntryScopeOrigin("https://open.example.build", "https://open.example.build/", hosts, debug = false),
            "a host of the client config is no App's",
        )
    }
}
