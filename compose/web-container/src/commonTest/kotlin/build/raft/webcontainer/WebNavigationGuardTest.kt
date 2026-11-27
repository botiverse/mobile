package build.raft.webcontainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebNavigationGuardTest {
    @Test
    fun parseOriginNormalizesCaseAndDefaultPorts() {
        val httpsDefault = parseWebContainerOrigin("HTTPS://App.Raft.Build:443/path?token=secret#frag")
        assertNotNull(httpsDefault)
        assertEquals("https", httpsDefault.scheme)
        assertEquals("app.raft.build", httpsDefault.host)
        assertNull(httpsDefault.port)
        assertEquals("https://app.raft.build", httpsDefault.asOriginString())

        val customPort = parseWebContainerOrigin("http://localhost:8080/test")
        assertNotNull(customPort)
        assertEquals("http://localhost:8080", customPort.asOriginString())
        assertEquals(8080, customPort.effectivePort)

        val ipv6 = parseWebContainerOrigin("https://[::1]:8443/app")
        assertNotNull(ipv6)
        assertEquals("https://[::1]:8443", ipv6.asOriginString())
    }

    @Test
    fun parseOriginRejectsCredentialsControlCharsAndMalformedUrls() {
        assertNull(parseWebContainerOrigin("https://user:pass@app.raft.build/"))
        assertNull(parseWebContainerOrigin("https://evil.com\\@app.raft.build/"))
        assertNull(parseWebContainerOrigin("javascript:alert(1)"))
        assertNull(parseWebContainerOrigin("https://app.raft.build:99999/"))
        assertNull(parseWebContainerOrigin("https://app.raft.build/\nnewline"))
        assertNull(parseWebContainerOrigin(""))
    }

    @Test
    fun isolationScopeValidatesIdsAndDerivesDeterministicUuid() {
        assertTrue(WebIsolationScope.isValidScopeId("app_demo-1.0"))
        assertFalse(WebIsolationScope.isValidScopeId("default"))
        assertFalse(WebIsolationScope.isValidScopeId("Default"))
        assertFalse(WebIsolationScope.isValidScopeId("ephemeral"))
        assertFalse(WebIsolationScope.isValidScopeId("../escape"))
        assertFalse(WebIsolationScope.isValidScopeId("has space"))
        assertFalse(WebIsolationScope.isValidScopeId(""))

        val uuidA1 = deriveDeterministicScopeUuid("app_demo")
        val uuidA2 = deriveDeterministicScopeUuid("app_demo")
        val uuidB = deriveDeterministicScopeUuid("app_other")
        assertEquals(uuidA1, uuidA2)
        assertNotEquals(uuidA1, uuidB)
        assertTrue(
            Regex("^[0-9A-F]{8}-[0-9A-F]{4}-5[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$").matches(uuidA1),
            "Expected RFC-4122 v5 UUID format, got $uuidA1",
        )
    }

    @Test
    fun mainFrameNavigationEnforcesSchemeOriginLockAndSingleDocumentFence() {
        val raftOrigin = parseWebContainerOrigin("https://app.raft.build")!!
        val policy =
            WebNavigationPolicy(
                allowNavigation = false,
                allowedSchemes = setOf("https"),
                lockedMainFrameOrigin = raftOrigin,
            )

        val blockedScheme =
            evaluateMainFrameNavigation(
                targetUrl = "javascript:alert(1)",
                initialUrl = "https://app.raft.build/doc",
                policy = policy,
            )
        assertIs<WebNavigationDecision.Block>(blockedScheme)
        assertEquals("scheme_not_allowed", blockedScheme.reason)

        val blockedOrigin =
            evaluateMainFrameNavigation(
                targetUrl = "https://evil.example/doc",
                initialUrl = "https://app.raft.build/doc",
                policy = policy,
            )
        assertIs<WebNavigationDecision.Block>(blockedOrigin)
        assertEquals("origin_locked", blockedOrigin.reason)

        val blockedNav =
            evaluateMainFrameNavigation(
                targetUrl = "https://app.raft.build/other",
                initialUrl = "https://app.raft.build/doc",
                policy = policy,
            )
        assertIs<WebNavigationDecision.Block>(blockedNav)
        assertEquals("navigation_disabled", blockedNav.reason)

        val allowedFragment =
            evaluateMainFrameNavigation(
                targetUrl = "https://app.raft.build/doc#section-2",
                initialUrl = "https://app.raft.build/doc",
                policy = policy,
            )
        assertEquals(WebNavigationDecision.Allow, allowedFragment)
    }

    @Test
    fun originScopedHeaderAttachesOnOriginAndStripsOnCrossOriginRedirect() {
        val raftOrigin = parseWebContainerOrigin("https://app.raft.build")!!
        val policy =
            WebNavigationPolicy(
                allowNavigation = true,
                allowedSchemes = setOf("https"),
                requestHeaderRule =
                    WebRequestHeaderRule(
                        headerName = "Raft-Host",
                        headerValue = "mobile-v1",
                        allowedOrigin = raftOrigin,
                        maxSameOriginReissues = 2,
                    ),
            )
        val chain = WebNavigationChainState()
        chain.startChain(carriesHeader = false, reissueBudget = 2, starter = WebNavigationStarter.Launch)

        // First hop on allowedOrigin without header -> reissue with header.
        val attach =
            evaluateMainFrameNavigation(
                targetUrl = "https://app.raft.build/app",
                initialUrl = "https://app.raft.build/app",
                policy = policy,
                method = "GET",
                requestCarriesScopedHeader = false,
                chainState = chain,
            )
        val withHeader = assertIs<WebNavigationDecision.ReissueWithHeader>(attach)
        assertEquals("Raft-Host", withHeader.headerName)
        assertEquals("mobile-v1", withHeader.headerValue)

        // Redirect leaves allowedOrigin while chain carried header -> strip header.
        val strip =
            evaluateMainFrameNavigation(
                targetUrl = "https://cdn.thirdparty.example/file",
                initialUrl = "https://app.raft.build/app",
                policy = policy,
                method = "GET",
                requestCarriesScopedHeader = true,
                chainState = chain,
            )
        val withoutHeader = assertIs<WebNavigationDecision.ReissueWithoutHeader>(strip)
        assertEquals("Raft-Host", withoutHeader.headerName)

        // Budget exhausted if cross-origin header leak recurs -> fail closed.
        val exhausted =
            evaluateMainFrameNavigation(
                targetUrl = "https://another.thirdparty.example/x",
                initialUrl = "https://app.raft.build/app",
                policy = policy,
                method = "GET",
                requestCarriesScopedHeader = true,
                chainState = chain,
            )
        val blocked = assertIs<WebNavigationDecision.Block>(exhausted)
        assertEquals("header_strip_budget_exhausted", blocked.reason)

        // Committing a document replenishes the reissue budget for subsequent navigations.
        chain.markCommitted()
        assertEquals(2, chain.remainingReissues)
        val reattached =
            evaluateMainFrameNavigation(
                targetUrl = "https://app.raft.build/next",
                initialUrl = "https://app.raft.build/app",
                policy = policy,
                method = "GET",
                requestCarriesScopedHeader = false,
                chainState = chain,
            )
        assertIs<WebNavigationDecision.ReissueWithHeader>(reattached)
    }

    @Test
    fun hostCatchHookTakesPrecedenceOverNormalRouting() {
        var caughtHops: List<String> = emptyList()
        val chain = WebNavigationChainState()
        chain.recordHop("https://app.raft.build/start")

        val decision =
            evaluateMainFrameNavigation(
                targetUrl = "https://app.raft.build/api/oauth/authorize",
                initialUrl = "https://app.raft.build/start",
                policy = WebNavigationPolicy(allowNavigation = true),
                chainState = chain,
                catchesMainFrameNavigation = { ctx ->
                    caughtHops = ctx.priorHops
                    ctx.url.contains("/api/oauth/authorize")
                },
            )
        assertIs<WebNavigationDecision.InterceptedByHost>(decision)
        assertEquals(listOf("https://app.raft.build/start"), caughtHops)
    }

    @Test
    fun subframePolicyChecksSchemesAndOptionalHostAllowlist() {
        val policy =
            WebNavigationPolicy(
                allowedSchemes = setOf("https"),
                allowedSubframeHosts = setOf("embed.raft.build"),
            )
        assertTrue(isAllowedSubframeUrl("about:blank", policy))
        assertTrue(isAllowedSubframeUrl("about:srcdoc", policy))
        assertTrue(isAllowedSubframeUrl("blob:https://embed.raft.build/1234", policy))
        assertTrue(isAllowedSubframeUrl("data:text/html,<h1>ok</h1>", policy))
        assertTrue(isAllowedSubframeUrl("https://embed.raft.build/widget", policy))
        assertTrue(isAllowedSubframeUrl("https://content.embed.raft.build/widget", policy))
        assertFalse(isAllowedSubframeUrl("https://evilembed.raft.build/widget", policy))
        assertFalse(isAllowedSubframeUrl("https://evil.example/widget", policy))
        assertFalse(isAllowedSubframeUrl("file:///etc/passwd", policy))
    }

    @Test
    fun sanitizeRendererRestoreUrlStripsTokensFragmentsAndBootstrapPaths() {
        val policy =
            WebNavigationPolicy(
                restoreBootstrapPathMarkers = setOf("/_bootstrap"),
            )
        val cleaned =
            sanitizeRendererRestoreUrl(
                initialUrl = "https://app.raft.build/home",
                candidateUrl = "https://app.raft.build/doc/42?tab=info&token=secret&code=abc#host=raft",
                policy = policy,
            )
        assertEquals("https://app.raft.build/doc/42?tab=info", cleaned)

        val bootstrapFallback =
            sanitizeRendererRestoreUrl(
                initialUrl = "https://app.raft.build/home",
                candidateUrl = "https://app.raft.build/_bootstrap/init",
                policy = policy,
            )
        assertEquals("https://app.raft.build/home", bootstrapFallback)

        val crossOriginFallback =
            sanitizeRendererRestoreUrl(
                initialUrl = "https://app.raft.build/home",
                candidateUrl = "https://other.example/page",
                policy = policy,
            )
        assertEquals("https://app.raft.build/home", crossOriginFallback)

        assertEquals(
            "https://app.raft.build/doc/42",
            sanitizeUrlForTelemetry("https://app.raft.build/doc/42?token=secret#hash"),
        )
    }

    @Test
    fun launchInitialHopAndCommittedStarterTransitionPreserveSignInInterceptContext() {
        val chain = WebNavigationChainState()
        chain.startChain(
            carriesHeader = false,
            reissueBudget = 2,
            starter = WebNavigationStarter.Launch,
            initialHop = "https://stamp.build/",
        )
        assertEquals(WebNavigationStarter.Launch, chain.starter)
        assertEquals(listOf("https://stamp.build/"), chain.currentHops())

        var observedStarter: WebNavigationStarter? = null
        var observedHops: List<String> = emptyList()
        val decision =
            evaluateMainFrameNavigation(
                targetUrl = "https://api.raft.build/api/oauth/authorize?client_id=stamp",
                initialUrl = "https://stamp.build/",
                policy = WebNavigationPolicy(allowNavigation = true),
                chainState = chain,
                isRedirect = true,
                catchesMainFrameNavigation = { ctx ->
                    observedStarter = ctx.starter
                    observedHops = ctx.priorHops
                    ctx.url.contains("/api/oauth/authorize")
                },
                mutateChainState = false,
            )
        assertIs<WebNavigationDecision.InterceptedByHost>(decision)
        assertEquals(WebNavigationStarter.Launch, observedStarter)
        assertEquals(listOf("https://stamp.build/"), observedHops)
        assertEquals(2, chain.remainingReissues)

        // Once a document commits, subsequent navigations without a fresh Launch belong to the Page.
        chain.markCommitted()
        assertTrue(chain.hasCommittedDocument)
        assertEquals(WebNavigationStarter.Page, chain.starter)
        assertTrue(chain.currentHops().isEmpty())
    }
}
