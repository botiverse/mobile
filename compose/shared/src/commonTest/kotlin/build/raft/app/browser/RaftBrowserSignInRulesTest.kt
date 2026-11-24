package build.raft.app

import build.raft.app.RaftSignInAskRefusal.Background
import build.raft.app.RaftSignInAskRefusal.HiddenPage
import build.raft.app.RaftSignInAskRefusal.Initiator
import build.raft.app.RaftSignInAskRefusal.OtherSite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Raft's sign-in from a page of the in-app browser (#356 §5): the pure rules (RaftBrowserSignInRules.kt).
 * Ports slock's desktop cases (apps/desktop/src/main/webPreviewRaftSignInRules.test.ts), the web sheet's
 * (packages/web/tests/desktopBrowserSignIn.behavior.test.tsx) and the shared OAuth redirect ones
 * (packages/shared/src/oauthRedirect.test.ts). Where mobile v1 differs on purpose (the same site is the
 * same origin; an address the WHATWG parser would rewrite, or a malformed escape, is refused), the case
 * says so.
 */
class RaftBrowserSignInRulesTest {
    private val raftHosts = setOf("app.raft.build", "api.raft.build")
    private val api = "https://api.raft.build"
    private val web = "https://app.raft.build"
    private val query =
        "response_type=code&client_id=deck&redirect_uri=https%3A%2F%2Fdeck.example%2Fcallback&scope=openid&state=s1"
    private val setup =
        "$web/login-with-raft/setup?flow=oidc&client_id=deck&return_to=https%3A%2F%2Fdeck.example%2Fcallback" +
            "&scope=openid+profile&state=s1"
    private val returnUrl = "https://orbital.example.com/return"
    private val setupSearch =
        "?flow=oidc&client_id=orbital-notes&return_to=https%3A%2F%2Forbital.example.com%2Freturn" +
            "&scope=openid+profile&state=state-1&nonce=nonce-1"

    private val ask =
        RaftSignInAsk(
            redirectUri = "https://deck.example/callback",
            starter = RaftSignInStarter.Page,
            page = "https://deck.example/login",
            committed = true,
            hops = emptyList(),
            pageVisible = true,
            appForeground = true,
        )

    /** The page's own first load of the App's address, which redirected to its sign-in. */
    private val firstLoad =
        ask.copy(starter = RaftSignInStarter.Launch, page = "", committed = false, hops = listOf("https://deck.example/"))

    private fun match(url: String, origin: String? = api): RaftAuthorizeRequest? = raftAuthorizeRequest(url, origin, raftHosts)

    private fun entry(url: String, origin: String? = web): RaftSetupEntryRequest? = raftSetupEntryRequest(url, origin, raftHosts)

    private fun authorizeRequest(): RaftAuthorizeRequest = assertNotNull(match("$api/api/oauth/authorize?$query"))

    private fun answer(status: Int, location: String?): RaftSetupAnswer? =
        raftSetupAnswer(RaftAuthorizeResponse(status, location), web, authorizeRequest(), raftHosts)

    private fun result(url: String, returnUrl: String = "https://deck.example/callback"): String? =
        raftSignInResultUrl(url, returnUrl, raftHosts)

    @Test
    fun authorizeOriginIsTheApisWhenHttpsWithNoPortAndLoopbackOnlyInADebugBuild() {
        assertEquals(api, raftAuthorizeOrigin("https://api.raft.build", allowLoopback = false))
        assertEquals(api, raftAuthorizeOrigin("https://api.raft.build/api/", allowLoopback = false), "the base URL's path aside")
        assertEquals(api, raftAuthorizeOrigin("https://api.raft.build:443/api/", allowLoopback = false))
        listOf("https://api.raft.build:8443", "http://api.raft.build", "https://user:pw@api.raft.build", "http://localhost:7777", "")
            .forEach { assertNull(raftAuthorizeOrigin(it, allowLoopback = false), it) }
        listOf("http://localhost:7777", "http://127.0.0.1:7777", "http://[::1]:7777", "http://10.0.2.2:7777", "https://localhost:8443")
            .forEach { assertEquals(it, raftAuthorizeOrigin("$it/api/", allowLoopback = true), it) }
        assertNull(raftAuthorizeOrigin("http://192.168.1.4:7777", allowLoopback = true), "not loopback")
        assertNull(raftAuthorizeOrigin("not a url", allowLoopback = true))
    }

    @Test
    fun matcherTakesExactlyTheTwoAuthorizeAddressesOnTheApiOrigin() {
        assertEquals(
            RaftAuthorizeRequest(
                fetchUrl = "$api/api/oauth/authorize?$query",
                redirectUri = "https://deck.example/callback",
                logClientId = "deck",
                state = "s1",
            ),
            match("$api/api/oauth/authorize?$query"),
        )
        assertEquals(
            "$api/oidc/my-server/api/oauth/authorize?$query",
            match("$api/oidc/my-server/api/oauth/authorize?$query")?.fetchUrl,
        )
        // A fragment never reaches the server; the default port is no port.
        assertEquals("$api/api/oauth/authorize?$query", match("$api:443/api/oauth/authorize?$query#frag")?.fetchUrl)
    }

    @Test
    fun matcherRefusesLookalikeHostsPortsCredentialsOtherSchemesAndOtherDeployments() {
        listOf(
            "https://api.raft.build.evil.example/api/oauth/authorize?$query",
            "https://evil-api.raft.build/api/oauth/authorize?$query",
            "https://sub.api.raft.build/api/oauth/authorize?$query",
            "https://api.raft.build.:443/api/oauth/authorize?$query",
            "https://api.raft.build:8443/api/oauth/authorize?$query",
            "http://api.raft.build/api/oauth/authorize?$query",
            "https://user:pw@api.raft.build/api/oauth/authorize?$query",
            "https://user@api.raft.build/api/oauth/authorize?$query",
            "https://api.raft.build@evil.example/api/oauth/authorize?$query",
            "https://evil.example/api/oauth/authorize?$query",
            "https://evil.example/?next=https://api.raft.build/api/oauth/authorize?$query",
            "https://api-aws-staging.botiverse.dev/api/oauth/authorize?$query",
            "https://app.raft.build/api/oauth/authorize?$query",
            "https://app.slock.ai/api/oauth/authorize?$query",
            "javascript:location='$api/api/oauth/authorize?$query'",
            "app://raft/api/oauth/authorize?$query",
        ).forEach { assertNull(match(it), it) }
        assertEquals(
            "https://deck.example/callback",
            match("https://api-aws-staging.botiverse.dev/api/oauth/authorize?$query", "https://api-aws-staging.botiverse.dev")
                ?.redirectUri,
            "a staging build matches its own API only",
        )
        assertNull(match("$api/api/oauth/authorize?$query", origin = null), "an environment with no acceptable API origin")
    }

    @Test
    fun matcherTakesNoOtherRaftPathAndNeverTheSetupPage() {
        listOf(
            "/login-with-raft/setup",
            "/api/oauth/authorize/human",
            "/api/oauth/authorize/account",
            "/api/oauth/authoriz%65",
            "/api/oauth/token",
            "/api/oauth/userinfo",
            "/oidc//api/oauth/authorize",
            "/oidc/a/b/api/oauth/authorize",
            "/oidc/my_server/api/oauth/authorize",
            "/oidc/${"a".repeat(129)}/api/oauth/authorize",
            "/x/api/oauth/authorize",
            "/api/auth/app-login/start",
            "/",
        ).forEach { path -> assertNull(match("$api$path?$query"), path) }
        assertNull(
            match("$web/login-with-raft/setup?flow=oidc&client_id=deck&return_to=https%3A%2F%2Fdeck.example%2Fcallback"),
            "the setup address on the web host",
        )
        assertNull(match("$web/login-with-raft/setup?$query", origin = web), "even were the web host the API host")
    }

    @Test
    fun matcherTakesTheEndpointInEverySpellingTheServerRoutesThereAndNoOther() {
        listOf(
            "/API/OAuth/Authorize",
            "/api/oauth/authorize/",
            "/API/OAUTH/AUTHORIZE/",
            "/OIDC/My-Server/API/OAuth/authorize",
            "/oidc/My-Server/api/oauth/authorize/",
            "/Oidc/My-Server/Api/Oauth/authorize/",
        ).forEach { path -> assertEquals("$api$path?$query", match("$api$path?$query")?.fetchUrl, path) }
        listOf(
            "/api/oauth/authorize//",
            "/api//oauth/authorize",
            "//api/oauth/authorize",
            "/api/oauth//authorize",
            "/oidc/My-Server/api/oauth/Authorize",
            "/OIDC/My-Server/API/OAUTH/AUTHORIZE",
            "/oidc/My-Server/api/oauth/Authorize/",
            "/oidc/My-Server/api/oauth/authorize//",
            "/oidc/My-Server//api/oauth/authorize",
            "/oidc/My-Server/api/oauth//authorize",
            "/oidc/My-Server/api/oauth",
            "/oidc/My-Server/api/oauthx/authorize",
        ).forEach { path -> assertNull(match("$api$path?$query"), path) }
    }

    @Test
    fun matcherNeedsExactlyOneRedirectUriThePageCouldBeSentTo() {
        fun withRedirect(redirect: String) = match("$api/api/oauth/authorize?response_type=code&client_id=deck&$redirect")
        listOf(
            "",
            "redirect_uri=https%3A%2F%2Fdeck.example%2Fcb&redirect_uri=https%3A%2F%2Fevil.example%2Fcb",
            "redirect_uri=javascript%3Aalert(1)",
            "redirect_uri=app%3A%2F%2Fraft%2F",
            "redirect_uri=https%3A%2F%2Fuser%3Apw%40deck.example%2Fcb",
            "redirect_uri=https%3A%2F%2Fdeck.example%2Fcb%23frag",
            "redirect_uri=https%3A%2F%2Fapp.raft.build%2Fcb",
            "redirect_uri=https%3A%2F%2Fapi.raft.build%2Fapi%2Foauth%2Fauthorize",
            "redirect_uri=https%3A%2F%2Fapp.raft.build.%2Fcb",
            "redirect_uri=https%3A%2F%2FAPP.RAFT.BUILD..%2Fcb",
            // Mobile v1: a host the WHATWG parser would rewrite first (percent-decoded, punycode) is refused.
            "redirect_uri=https%3A%2F%2Fapp%252Eraft.build%2Fcb",
            "redirect_uri=https%3A%2F%2F%D0%B0%D1%80%D1%80%D3%8F%D0%B5.com%2Fcb",
        ).forEach { assertNull(withRedirect(it), it) }
        assertEquals(
            "https://deck.example./cb",
            withRedirect("redirect_uri=https%3A%2F%2Fdeck.example.%2Fcb")?.redirectUri,
            "an outside host with a trailing dot",
        )
        assertEquals("http://localhost:5173/cb", withRedirect("redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fcb")?.redirectUri)
        assertEquals(
            "https://deck.example/cb",
            withRedirect("redirect_uri=+https%3A%2F%2FDeck.Example%3A443%2Fcb%20")?.redirectUri,
            "trimmed, and written as the parser writes it",
        )
        assertNull(match(""))
        assertNull(match("$api/api/oauth/authorize?$query&x=${"a".repeat(RAFT_BROWSER_MAX_URL_LENGTH)}"), "too long")
        assertNull(match("$api/api/oauth/authorize?$query&x=%zz"), "mobile v1: a malformed escape anywhere in the query")
    }

    @Test
    fun theRequestsStateIsItsOneStateAsThePageWroteIt() {
        assertEquals("s1", match("$api/api/oauth/authorize?$query")?.state)
        assertNull(match("$api/api/oauth/authorize?${query.replace("&state=s1", "")}")?.state, "none")
        assertNull(match("$api/api/oauth/authorize?$query&state=s2")?.state, "more than one")
        assertEquals("a b&c", match("$api/api/oauth/authorize?${query.replace("state=s1", "state=a+b%26c")}")?.state)
        assertEquals("", match("$api/api/oauth/authorize?${query.replace("state=s1", "state=")}")?.state)
        val unloggable = query.replace("client_id=deck", "client_id=a%20b%3Cc%3E")
        assertNull(match("$api/api/oauth/authorize?$unloggable")?.logClientId, "a client id goes to a log only as a plain token")
    }

    @Test
    fun sameSiteIsTheSameOriginInV1() {
        listOf(
            "https://example.com/login" to "https://example.com/callback",
            "https://EXAMPLE.com:443/login" to "https://example.com/callback",
            "http://localhost:5173/" to "http://localhost:5173/cb",
            "http://127.0.0.1:5173/" to "http://127.0.0.1:5173/cb",
            "http://[::1]:5173/" to "http://[::1]:5173/cb",
            "https://a.corp.internal/" to "https://a.corp.internal/cb",
        ).forEach { (a, b) -> assertTrue(raftSignInSameSite(a, b), "$a $b") }
        listOf(
            // One site on desktop (one registrable domain), two origins here.
            "https://www.example.com/login" to "https://auth.example.com/callback",
            "https://deck.co.uk/" to "https://app.deck.co.uk/cb",
            "https://example.com:8443/login" to "https://example.com/callback",
            // Two sites everywhere.
            "http://localhost:5173/" to "http://localhost:8787/cb",
            "http://127.0.0.1:5173/" to "http://127.0.0.1:8787/cb",
            "http://[::1]:5173/" to "http://[::1]:8787/cb",
            "http://10.0.0.5/" to "http://10.0.0.5:8080/cb",
            "https://a.corp.internal/" to "https://a.corp.internal:8443/cb",
            "https://evil.net/" to "https://example.com/callback",
            "https://deck.example.com.evil.net/" to "https://example.com/callback",
            "https://evilexample.com/" to "https://example.com/callback",
            "http://example.com/" to "https://example.com/callback",
            "https://evil.co.uk/" to "https://deck.co.uk/cb",
            "https://evil.github.io/" to "https://deck.github.io/cb",
            "https://a.corp.internal/" to "https://b.corp.internal/cb",
            "http://127.0.0.1/" to "http://127.0.0.2/cb",
            "http://localhost/" to "http://127.0.0.1/cb",
            "https://example.com/" to "nope",
        ).forEach { (a, b) -> assertFalse(raftSignInSameSite(a, b), "$a $b") }
        listOf("", "about:blank", "data:text/html,hi", "file:///x.html", "app://raft/", "not a url").forEach { page ->
            assertFalse(raftSignInSameSite(page, "https://example.com/callback"), page)
        }
    }

    @Test
    fun aDocumentOnTheAppsSiteMayAskThroughItsOwnRedirects() {
        assertNull(raftSignInAskRefusal(ask))
        assertNull(raftSignInAskRefusal(ask.copy(hops = listOf("https://deck.example/auth/start", "https://deck.example/auth/go"))))
        assertEquals(OtherSite, raftSignInAskRefusal(ask.copy(page = "https://www.deck.example/login")))
        assertEquals(
            OtherSite,
            raftSignInAskRefusal(ask.copy(page = "https://app.example.com/", redirectUri = "https://auth.example.com/cb")),
            "one registrable domain: one site on desktop, two origins in v1",
        )
    }

    @Test
    fun aDocumentOnAnotherSiteAskingWithAnAppsReturnAddressIsRefused() {
        listOf("https://evil.example/", "https://deck.example.evil.test/", "http://deck.example/login", "", "about:blank")
            .forEach { page -> assertEquals(OtherSite, raftSignInAskRefusal(ask.copy(page = page)), page) }
    }

    @Test
    fun aStartThePlatformDidNotSeeIsRefused() {
        assertEquals(Initiator, raftSignInAskRefusal(ask.copy(starter = RaftSignInStarter.Other)))
        assertEquals(
            Initiator,
            raftSignInAskRefusal(ask.copy(starter = RaftSignInStarter.Other, hops = listOf("https://deck.example/auth/start"))),
        )
    }

    @Test
    fun everyAddressTheNavigationWentThroughMustBeOnTheAppsSite() {
        val evil = "https://evil.example/go"
        val own = "https://deck.example/auth/start"
        listOf(listOf(evil), listOf(evil, own), listOf(own, evil, own), listOf(own, evil), listOf(own, "")).forEach { hops ->
            assertEquals(OtherSite, raftSignInAskRefusal(ask.copy(hops = hops)), "$hops")
        }
        listOf(listOf(evil, own), listOf(own, evil)).forEach { hops ->
            assertEquals(OtherSite, raftSignInAskRefusal(firstLoad.copy(hops = hops)), "the first load: $hops")
        }
    }

    @Test
    fun aPageOffScreenOrAnAppInTheBackgroundIsRefused() {
        assertEquals(HiddenPage, raftSignInAskRefusal(ask.copy(pageVisible = false)))
        assertEquals(Background, raftSignInAskRefusal(ask.copy(appForeground = false)))
        assertEquals(HiddenPage, raftSignInAskRefusal(firstLoad.copy(pageVisible = false)))
        assertEquals(Background, raftSignInAskRefusal(firstLoad.copy(appForeground = false)))
    }

    @Test
    fun thePagesFirstLoadMayAskOnlyRedirectedAndBeforeAnyDocumentCommitted() {
        assertNull(raftSignInAskRefusal(firstLoad))
        assertNull(raftSignInAskRefusal(firstLoad.copy(hops = listOf("https://deck.example/", "https://deck.example/auth/start"))))
        assertEquals(Initiator, raftSignInAskRefusal(firstLoad.copy(committed = true, page = "https://deck.example/home")))
        assertEquals(Initiator, raftSignInAskRefusal(firstLoad.copy(committed = true, page = "https://evil.example/")))
        assertEquals(Initiator, raftSignInAskRefusal(firstLoad.copy(hops = emptyList())), "a load straight to the authorize address")
    }

    @Test
    fun theServerAcceptsWithA302ToTheSetupAddressForTheReturnAddressThePageNamed() {
        assertEquals(
            RaftSetupAnswer(
                setupSearch =
                    "?flow=oidc&client_id=deck&return_to=https%3A%2F%2Fdeck.example%2Fcallback&scope=openid+profile&state=s1",
                returnUrl = "https://deck.example/callback",
            ),
            answer(302, setup),
        )
        assertNotNull(
            raftSetupAnswer(RaftAuthorizeResponse(302, setup), "https://app.raft.build/", authorizeRequest(), raftHosts),
            "the environment's web URL, its path aside",
        )
    }

    @Test
    fun anythingElseFromTheServerIsARefusal() {
        assertNull(raftSetupAnswer(null, web, authorizeRequest(), raftHosts), "the fetch failed")
        listOf(
            400 to null,
            200 to setup,
            301 to setup,
            429 to setup,
            302 to null,
            302 to "/login-with-raft/setup?client_id=deck",
            302 to setup.replace(web, "https://evil.example"),
            302 to setup.replace(web, "https://app.raft.build:8443"),
            302 to setup.replace(web, "https://user@app.raft.build"),
            302 to setup.replace("/login-with-raft/setup", "/login"),
            302 to setup.replace("/login-with-raft/setup", "/Login-With-Raft/Setup"),
            302 to setup.replace("https%3A%2F%2Fdeck.example%2Fcallback", "https%3A%2F%2Fevil.example%2Fcallback"),
            302 to "$setup&return_to=https%3A%2F%2Fevil.example%2Fcb",
            302 to "$setup&redirect_uri=https%3A%2F%2Fevil.example%2Fcb",
            302 to setup.replace("client_id=deck&", ""),
            302 to "$setup&client_id=evil",
        ).forEach { (status, location) -> assertNull(answer(status, location), "$status $location") }
        assertNull(answer(302, "$setup&x=${"a".repeat(RAFT_BROWSER_MAX_URL_LENGTH)}"), "too long")
    }

    @Test
    fun theResultIsTheReturnAddressWithItsAnswerAndNothingElse() {
        assertEquals("https://deck.example/callback?code=abc&state=s1", result("https://deck.example/callback?code=abc&state=s1"))
        assertEquals(
            "https://deck.example/callback?error=invalid_scope&state=s1",
            result("https://deck.example/callback?error=invalid_scope&state=s1"),
        )
        assertEquals(
            "https://deck.example/callback?tenant=7&code=abc",
            result("https://deck.example/callback?tenant=7&code=abc", "https://deck.example/callback?tenant=7"),
        )
        assertEquals(
            "https://deck.example/callback?code=abc&tenant=7",
            result("https://deck.example/callback?code=abc&tenant=7", "https://deck.example/callback?tenant=7"),
            "the return address's pairs in any order",
        )
        assertEquals("http://localhost:5173/cb?code=abc", result("http://localhost:5173/cb?code=abc", "http://localhost:5173/cb"))
        assertEquals("https://deck.example/callback?code=abc", result("HTTPS://Deck.Example:443/callback?code=abc"))
    }

    @Test
    fun aResultOffTheReturnAddressIsRefused() {
        listOf(
            "https://evil.example/callback?code=abc",
            "https://deck.example.evil.test/callback?code=abc",
            "https://sub.deck.example/callback?code=abc",
            "http://deck.example/callback?code=abc",
            "https://deck.example:8443/callback?code=abc",
            "https://deck.example/callback/../steal?code=abc",
            "https://deck.example/callback/?code=abc",
            "https://deck.example/Callback?code=abc",
            "https://deck.example/other?code=abc",
            "https://deck.example/?code=abc",
            "https://user:pw@deck.example/callback?code=abc",
            "https://deck.example/callback?code=abc#frag",
            // Mobile v1: an empty fragment too (the web's `hash` is "" for it).
            "https://deck.example/callback?code=abc#",
            "https://app.raft.build/callback?code=abc",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "app://raft/",
            "raft://open",
            "",
            "not a url",
        ).forEach { assertNull(result(it), it) }
        assertNull(
            result("https://deck.example/callback?code=abc", "https://deck.example/callback?tenant=7"),
            "the return address's own pair is gone",
        )
        assertNull(result("https://deck.example/callback?tenant=8&code=abc", "https://deck.example/callback?tenant=7"))
        assertNull(result("https://api.raft.build/cb?code=abc", "https://api.raft.build/cb"), "a Raft host even as the return address")
        assertNull(result("https://deck.example/callback?code=abc", "nope"))
    }

    @Test
    fun aDenialIsTheReturnAddressWithAccessDeniedAndTheRequestsStateUnchanged() {
        fun denied(returnUrl: String, state: String?) = raftSignInDeniedUrl(returnUrl, state, raftHosts)
        val withState = assertNotNull(denied("https://deck.example/callback?tenant=7", "s 1&x=y"))
        assertEquals("https://deck.example/callback?tenant=7&error=access_denied&state=s%201%26x%3Dy", withState)
        assertEquals(
            listOf("s 1&x=y"),
            RaftBrowserQuery.parse(raftBrowserUrlOf(withState)?.query)?.all("state"),
            "the state as the page wrote it",
        )
        assertEquals("https://deck.example/callback?error=access_denied", denied("https://deck.example/callback", null))
        assertEquals("http://localhost:5173/cb?error=access_denied&state=", denied("http://localhost:5173/cb", ""))
        assertEquals(
            "https://deck.example/cb?b=x+y&a=%7e&error=access_denied",
            denied("https://deck.example/cb?b=x+y&a=%7e", null),
            "its own query byte for byte",
        )
        listOf(
            "https://app.raft.build/cb",
            "https://api.raft.build/cb",
            "javascript:alert(1)",
            "app://raft/",
            "file:///etc/passwd",
            "nope",
            "https://deck.example/cb#frag",
        ).forEach { assertNull(denied(it, "s1"), it) }
    }

    @Test
    fun theSheetShowsARequestInRaftsOwnWords() {
        assertEquals(
            RaftSignInSetupRequest(
                setupSearch = setupSearch,
                returnHost = "orbital.example.com",
                clientId = "orbital-notes",
                returnUrl = returnUrl,
                scope = "openid profile",
            ),
            raftSignInSetupRequest(setupSearch),
        )
        val bare = "?client_id=a&return_to=https%3A%2F%2Fa.example%2Fcb"
        assertEquals("openid profile", raftSignInSetupRequest(bare)?.scope, "the default scopes")
        assertEquals("openid profile", raftSignInSetupRequest("$bare&scope=")?.scope)
        assertEquals(
            "openid email agent:read",
            raftSignInSetupRequest("client_id=a&scope=openid%20%20email+agent%3Aread&return_to=https%3A%2F%2Fa.example%2Fcb")?.scope,
        )
        assertEquals(
            "orbital-notes",
            raftSignInSetupRequest(setupSearch.replace("client_id=orbital-notes", "client_id=+orbital-notes+"))?.clientId,
        )
        assertEquals(
            "xn--80ak6aa92e.com:8080",
            raftSignInSetupRequest("?client_id=a&return_to=http%3A%2F%2Fxn--80ak6aa92e.com%3A8080%2Fcb")?.returnHost,
            "ASCII, with the port it names",
        )
    }

    @Test
    fun aRequestRaftHasNoWordsForOrWithNoUsableReturnAddressIsNotShown() {
        listOf(
            setupSearch.replace("scope=openid+profile", "scope=openid+Raft-says-press-Continue"),
            "?client_id=a&return_to=javascript%3Aalert(1)",
            "?client_id=a",
            "?return_to=https%3A%2F%2Fa.example%2Fcb",
            "?client_id=+&return_to=https%3A%2F%2Fa.example%2Fcb",
            // Mobile v1: desktop shows this host in punycode; here it is refused.
            "?client_id=a&return_to=https%3A%2F%2F%D0%B0%D1%80%D1%80%D3%8F%D0%B5.com%2Fcb",
            "?client_id=a&return_to=https%3A%2F%2Fa.example%2Fcb&x=%zz",
        ).forEach { assertNull(raftSignInSetupRequest(it), it) }
    }

    @Test
    fun onlyTheAppsRegisteredReturnAddressCharacterForCharacterIsShown() {
        fun lookups(vararg returnUrls: String?): List<RaftSignInLookup> =
            returnUrls.mapIndexed { index, url -> RaftSignInLookup(serverId = "server-$index", name = "Orbital Notes", returnUrl = url) }
        assertTrue(raftSignInIsRegisteredReturnAddress(returnUrl, lookups(returnUrl)))
        assertTrue(
            raftSignInIsRegisteredReturnAddress(returnUrl, lookups("https://other.example/return", returnUrl)),
            "registered so on one Server",
        )
        listOf(
            "https://evil.example/return",
            "$returnUrl/",
            "$returnUrl?next=https://evil.example",
            returnUrl.replace("https://", "http://"),
            "https://Orbital.example.com/return",
        ).forEach { assertFalse(raftSignInIsRegisteredReturnAddress(it, lookups(returnUrl)), it) }
        assertFalse(raftSignInIsRegisteredReturnAddress(returnUrl, emptyList()), "no lookup answered")
        assertFalse(raftSignInIsRegisteredReturnAddress(returnUrl, lookups(null)))
        assertFalse(raftSignInIsRegisteredReturnAddress("", lookups("")))
    }

    @Test
    fun theAnswerSetsTheCodeAndTheStateAndKeepsEveryOtherPairAsWritten() {
        assertEquals(
            "https://deck.example/callback?tenant=7&code=c-1&state=s%201",
            raftSignInSuccessUrl("https://deck.example/callback?tenant=7", "c-1", "s 1"),
        )
        assertEquals("https://deck.example/cb?b=x+y&a=%7e&code=c", raftSignInSuccessUrl("https://deck.example/cb?b=x+y&a=%7e", "c", null))
        assertEquals(
            "https://deck.example/cb?code=new&x=1&state=s",
            raftSignInSuccessUrl("https://deck.example/cb?code=old&x=1&code=older&state=old", "new", "s"),
            "set: the first pair of the name takes the value, later ones go",
        )
        assertEquals(
            "https://deck.example/cb?state=kept&code=c",
            raftSignInSuccessUrl("https://deck.example/cb?state=kept", "c", ""),
            "an empty state sets nothing",
        )
        listOf("https://client.test/cb?source=raft", "http://localhost:3000/cb", "http://127.0.0.1:3000/cb", "http://[::1]:3000/cb")
            .forEach { callback ->
                val answer = assertNotNull(raftSignInSuccessUrl(callback, "code&value", "state#value"), callback)
                val url = assertNotNull(raftBrowserUrlOf(answer), answer)
                val answerQuery = assertNotNull(RaftBrowserQuery.parse(url.query), answer)
                assertEquals("code&value", answerQuery.first("code"), answer)
                assertEquals("state#value", answerQuery.first("state"), answer)
                assertEquals(raftBrowserUrlOf(callback)?.origin, url.origin, answer)
                assertEquals(answer, raftSignInResultUrl(answer, callback, raftHosts), "the page may be sent it: $answer")
            }
    }

    @Test
    fun noAnswerGoesToAnAddressThatIsNotASafeReturnAddress() {
        listOf(
            "javascript:window.auditExecuted=true//",
            "JaVaScRiPt:alert(1)",
            "java\tscript:alert(1)",
            "data:text/html,test",
            "//attacker.test/cb",
            "/cb",
            "http://attacker.test/cb",
            "http://10.0.2.2:3000/cb",
            "https://user:password@client.test/cb",
            "https://client.test/cb#fragment",
            "http://localhost.attacker.test/cb",
        ).forEach { assertNull(raftSignInSuccessUrl(it, "secret-code", "s"), it) }
    }

    @Test
    fun scopesTheServersAdminDidNotApproveGoBackToTheAppAsInvalidScope() {
        assertEquals(
            "https://client.test/cb?source=raft&error=invalid_scope&error_description=not%20approved%3A%20openid" +
                "&state=state%23value",
            raftSignInInvalidScopeUrl(
                errorCode = "OAUTH_SCOPE_NOT_APPROVED",
                returnUrl = "https://client.test/cb?source=raft&code=stale",
                description = "not approved: openid",
                state = "state#value",
            ),
        )
        assertEquals(
            "https://client.test/cb?error=invalid_scope",
            raftSignInInvalidScopeUrl("OAUTH_SCOPE_NOT_APPROVED", "https://client.test/cb", description = null, state = ""),
        )
        assertEquals(
            "https://client.test/cb?error=invalid_scope",
            raftSignInInvalidScopeUrl("OAUTH_SCOPE_NOT_APPROVED", "https://client.test/cb?error=old", description = "", state = null),
            "set, not added",
        )
        assertNull(raftSignInInvalidScopeUrl("OAUTH_INSTALLATION_SUSPENDED", returnUrl, null, "s"), "another refusal: the sheet words it")
        assertNull(raftSignInInvalidScopeUrl(null, returnUrl, null, "s"))
        assertNull(raftSignInInvalidScopeUrl("OAUTH_SCOPE_NOT_APPROVED", null, null, "s"))
        assertNull(raftSignInInvalidScopeUrl("OAUTH_SCOPE_NOT_APPROVED", "", null, "s"))
        assertNull(raftSignInInvalidScopeUrl("OAUTH_SCOPE_NOT_APPROVED", "javascript:alert(1)", null, "s"))
    }

    @Test
    fun setupEntryMatchesThreeSetupPathsOnWebOriginInEitherCaseAndOneTrailingSlash() {
        val returnWithQuery = "https://deck.example/callback?tenant=7"
        val returnEncoded = "https%3A%2F%2Fdeck.example%2Fcallback%3Ftenant%3D7"
        listOf(
            "/login-with-raft/setup",
            "/login-with-slock/setup",
            "/login-with-slock-human/setup",
            "/Login-With-Raft/Setup",
            "/LOGIN-WITH-SLOCK/SETUP/",
            "/login-with-slock-human/setup/",
        ).forEach { path ->
            assertEquals(
                RaftSetupEntryRequest(
                    setupSearch = "?client_id=deck&return_to=$returnEncoded",
                    redirectUri = returnWithQuery,
                    logClientId = "deck",
                    state = null,
                ),
                entry("$web$path?client_id=deck&return_to=$returnEncoded"),
                path,
            )
        }
        listOf(
            "$web/login-with-raft/setup//?client_id=deck&return_to=$returnEncoded",
            "$web/login-with-raft/setups?client_id=deck&return_to=$returnEncoded",
            "$web/login-with-raft?client_id=deck&return_to=$returnEncoded",
            "$web/x/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
            "$web/login-with-slockx/setup?client_id=deck&return_to=$returnEncoded",
            "$web/login-with-raft/setup%2F?client_id=deck&return_to=$returnEncoded",
            "$api/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
            "https://app.raft.build.evil.example/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
            "https://app.raft.build:8443/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
            "https://user:pw@app.raft.build/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
            "http://app.raft.build/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
            "https://app-aws-staging.botiverse.dev/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
            "https://deck.example/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
        ).forEach { bad -> assertNull(entry(bad), bad) }
        assertNull(entry("$web/login-with-raft/setup?client_id=deck&return_to=$returnEncoded", origin = null))
        assertNull(match("$web/login-with-slock/setup?client_id=deck&redirect_uri=$returnEncoded"))
        assertNull(entry("$api/api/oauth/authorize?$query", origin = api))
    }

    @Test
    fun setupEntryBuildsAllowListedSetupSearchDroppingFlowNonceAndPkce() {
        val returnWithQuery = "https://deck.example/callback?tenant=7"
        val returnEncoded = "https%3A%2F%2Fdeck.example%2Fcallback%3Ftenant%3D7"
        val url =
            "$web/login-with-raft/setup?flow=oidc&nonce=n1&code_challenge=abc&code_challenge_method=S256" +
                "&response_type=code&prompt=none&server=launch&state=s1&scope=openid+profile" +
                "&return_to=$returnEncoded&client_id=deck&utm=x"
        val got = assertNotNull(entry(url))
        assertEquals(
            "?client_id=deck&return_to=$returnEncoded&scope=openid%20profile&state=s1&server=launch",
            got.setupSearch,
        )
        assertEquals(returnWithQuery, got.redirectUri)
        assertEquals("s1", got.state)
        val parsed = assertNotNull(RaftBrowserQuery.parse(got.setupSearch))
        assertEquals(listOf("client_id", "return_to", "scope", "state", "server"), parsed.pairs.map { it.name })
        assertNull(parsed.first("flow"))
        assertNull(parsed.first("nonce"))
        assertNull(parsed.first("code_challenge"))
        assertNull(parsed.first("code_challenge_method"))
        assertNull(parsed.first("prompt"))
        // A fragment on the setup page's own URL is ignored and never read into setupSearch or state.
        val withOuterFragment = assertNotNull(entry("$web/login-with-raft/setup?client_id=deck&return_to=$returnEncoded#state=x"))
        assertEquals("?client_id=deck&return_to=$returnEncoded", withOuterFragment.setupSearch)
        assertNull(withOuterFragment.state)
    }

    @Test
    fun setupEntryUsesRedirectUriWhenReturnToAbsentAndRequiresAgreementWhenBothPresent() {
        val returnEncoded = "https%3A%2F%2Fdeck.example%2Fcallback%3Ftenant%3D7"
        val base = "$web/login-with-slock/setup?client_id=deck"
        assertEquals("?client_id=deck&return_to=$returnEncoded", entry("$base&redirect_uri=$returnEncoded")?.setupSearch)
        assertEquals(
            "?client_id=deck&return_to=$returnEncoded",
            entry("$base&return_to=$returnEncoded&redirect_uri=$returnEncoded")?.setupSearch,
        )
        assertNull(entry("$base&return_to=$returnEncoded&redirect_uri=https%3A%2F%2Fdeck.example%2Fother"))
        assertNull(entry("$base&return_to=$returnEncoded&redirect_uri=$returnEncoded&redirect_uri=$returnEncoded"))
        // Stricter than the web fallback: empty return_to is refused even when redirect_uri is present.
        assertNull(entry("$base&return_to=&redirect_uri=$returnEncoded"))
        assertNull(entry("$base&return_to="))
    }

    @Test
    fun setupEntryTrimsReturnAddressAndPreservesExactValueForRegistrationComparison() {
        val returnWithQuery = "https://deck.example/callback?tenant=7"
        val returnEncoded = "https%3A%2F%2Fdeck.example%2Fcallback%3Ftenant%3D7"
        val padded = "%20%20$returnEncoded%09%20"
        val base = "$web/login-with-raft/setup?client_id=deck"
        listOf("&return_to=$padded", "&redirect_uri=$padded", "&return_to=$padded&redirect_uri=$returnEncoded").forEach { suffix ->
            val got = assertNotNull(entry("$base$suffix"), suffix)
            assertEquals("?client_id=deck&return_to=$returnEncoded", got.setupSearch, suffix)
            assertEquals(returnWithQuery, RaftBrowserQuery.parse(got.setupSearch)?.first("return_to"), suffix)
            assertEquals(returnWithQuery, got.redirectUri, suffix)
        }
        // Not rewritten by the URL parser inside setupSearch: registered address is compared char-for-char.
        val mixedCase = assertNotNull(entry("$base&return_to=https%3A%2F%2FDeck.example"))
        assertEquals("https://Deck.example", RaftBrowserQuery.parse(mixedCase.setupSearch)?.first("return_to"))
        assertEquals("https://deck.example/", mixedCase.redirectUri)
    }

    @Test
    fun setupEntryDecodesParameterNamesAndRejectsMissingOrDuplicateParams() {
        val returnEncoded = "https%3A%2F%2Fdeck.example%2Fcallback%3Ftenant%3D7"
        val valid = "$web/login-with-raft/setup?client_id=deck&return_to=$returnEncoded"
        assertNull(entry("$valid&client%5Fid=evil"), "client%5Fid is a second client_id")
        assertNull(entry("$valid&return%5Fto=$returnEncoded"), "return%5Fto is a second return_to")
        assertEquals("?client_id=deck&return_to=$returnEncoded", entry("$valid&%66low=oidc&%6Eonce=n1")?.setupSearch)
        assertEquals("?client_id=deck&return_to=$returnEncoded&state=s1", entry("$valid&%73tate=s1")?.setupSearch)

        val base = "$web/login-with-raft/setup?"
        listOf(
            "return_to=$returnEncoded",
            "client_id=&return_to=$returnEncoded",
            "client_id=%20&return_to=$returnEncoded",
            "client_id=deck&client_id=deck&return_to=$returnEncoded",
            "client_id=deck&client_id=evil&return_to=$returnEncoded",
            "client_id=deck",
            "client_id=deck&return_to=$returnEncoded&return_to=$returnEncoded",
            "client_id=deck&redirect_uri=$returnEncoded&redirect_uri=$returnEncoded",
            "client_id=deck&return_to=$returnEncoded&scope=openid&scope=profile",
            "client_id=deck&return_to=$returnEncoded&state=a&state=b",
            "client_id=deck&return_to=$returnEncoded&server=a&server=b",
            "client_id=deck&return_to=$returnEncoded&x=%zz",
            "client_id=deck&return_to=$returnEncoded&x=${"a".repeat(RAFT_BROWSER_MAX_URL_LENGTH)}",
        ).forEach { q -> assertNull(entry("$base$q"), q) }
    }

    @Test
    fun setupEntryRefusesFragmentsRaftHostsUnsafeReturnAddressesAndUnpackagedLoopback() {
        val returnEncoded = "https%3A%2F%2Fdeck.example%2Fcallback%3Ftenant%3D7"
        listOf(
            "https%3A%2F%2Fapp.raft.build%2Fcallback",
            "https%3A%2F%2Fapi.raft.build%2Fcallback",
            "https%3A%2F%2Fapp.raft.build.%2Fcallback",
            "https%3A%2F%2Fdeck.example%2Fcallback%23frag",
            "https%3A%2F%2Fdeck.example%2Fcallback%23",
            "javascript%3Aalert(1)",
            "file%3A%2F%2F%2Fetc%2Fpasswd",
            "https%3A%2F%2Fuser%3Apw%40deck.example%2Fcallback",
            "not%20a%20url",
            "",
        ).forEach { badReturn ->
            assertNull(entry("$web/login-with-raft/setup?client_id=deck&return_to=$badReturn"), "return_to=$badReturn")
            assertNull(entry("$web/login-with-raft/setup?client_id=deck&redirect_uri=$badReturn"), "redirect_uri=$badReturn")
        }
        assertNull(entry("$web/login-with-raft/setup?client_id=a%20b%3Cc%3E&return_to=$returnEncoded")?.logClientId)
        assertEquals("a b", entry("$web/login-with-raft/setup?client_id=deck&return_to=$returnEncoded&state=a%20b")?.state)
        assertEquals("", entry("$web/login-with-raft/setup?client_id=deck&return_to=$returnEncoded&state=")?.state)
        assertNull(entry("$web/login-with-raft/setup?client_id=deck&return_to=$returnEncoded")?.state)
        assertEquals(
            "https://deck.example/callback?tenant=7",
            raftSetupEntryRequest(
                "http://localhost:5173/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
                raftAuthorizeOrigin("http://localhost:5173", allowLoopback = true),
                setOf("localhost"),
            )?.redirectUri,
        )
        assertNull(
            raftSetupEntryRequest(
                "http://localhost:5173/login-with-raft/setup?client_id=deck&return_to=$returnEncoded",
                raftAuthorizeOrigin("http://localhost:5173", allowLoopback = false),
                setOf("localhost"),
            ),
        )
    }
}
