package build.raft.app

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import build.raft.webcontainer.WebJsDialogKind
import build.raft.webcontainer.WebJsDialogRequest
import build.raft.webcontainer.WebJsDialogResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftMobileWebPanelStateTest {
    @Test
    fun storagePartitionSelectsDefaultForRaftAppIsolatedForOutboundChildAndFailsClosedWithoutMultiProfile() {
        // Installed Raft App always uses Default (even on pre-119 Android without MULTI_PROFILE).
        assertEquals(
            RaftMobileWebPanelLaunchResolution.OpenInPanel(
                storagePartition = RaftWebViewStoragePartition.Default,
                isRaftApp = true,
            ),
            resolveRaftMobileWebPanelStorage(
                isRaftAppEntry = true,
                useEphemeralStore = false,
                supportsIsolatedThirdPartyProfile = false,
            ),
        )
        // Cross-origin outbound child page uses IsolatedThirdParty when supported.
        assertEquals(
            RaftMobileWebPanelLaunchResolution.OpenInPanel(
                storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty,
                isRaftApp = false,
            ),
            resolveRaftMobileWebPanelStorage(
                isRaftAppEntry = false,
                useEphemeralStore = false,
                supportsIsolatedThirdPartyProfile = true,
            ),
        )
        // Fails closed to system browser when MULTI_PROFILE is unavailable (never Default).
        assertEquals(
            RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser,
            resolveRaftMobileWebPanelStorage(
                isRaftAppEntry = false,
                useEphemeralStore = false,
                supportsIsolatedThirdPartyProfile = false,
            ),
        )
        // Own-host ephemeral always keeps Ephemeral partition.
        assertEquals(
            RaftMobileWebPanelLaunchResolution.OpenInPanel(
                storagePartition = RaftWebViewStoragePartition.Ephemeral,
                isRaftApp = false,
            ),
            resolveRaftMobileWebPanelStorage(
                isRaftAppEntry = false,
                useEphemeralStore = true,
                supportsIsolatedThirdPartyProfile = false,
            ),
        )
    }

    @Test
    fun failClosedActionNeverHandsOffToSystemBrowserWhenClosingOrPreviouslyAuthorizedApp() {
        val openInPanel =
            RaftMobileWebPanelLaunchResolution.OpenInPanel(
                storagePartition = RaftWebViewStoragePartition.Default,
                isRaftApp = true,
            )
        assertEquals(
            RaftMobileWebPanelFailClosedAction.None,
            resolveRaftMobileWebPanelFailClosedAction(
                storageResolution = openInPanel,
                effectiveLaunchUrl = "https://stamp.build/pulls/1",
                closing = false,
                wasAuthorizedAppEntry = true,
            ),
        )
        // Closing an authorized App page must never trigger a system-browser handoff even if the token is gone.
        assertEquals(
            RaftMobileWebPanelFailClosedAction.None,
            resolveRaftMobileWebPanelFailClosedAction(
                storageResolution = RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser,
                effectiveLaunchUrl = "https://stamp.build/pulls/1",
                closing = true,
                wasAuthorizedAppEntry = true,
            ),
        )
        // If an authorized App loses its token while mounted on pre-119 Android, close silently instead of launching external browser.
        assertEquals(
            RaftMobileWebPanelFailClosedAction.CloseSilently,
            resolveRaftMobileWebPanelFailClosedAction(
                storageResolution = RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser,
                effectiveLaunchUrl = "https://stamp.build/pulls/1",
                closing = false,
                wasAuthorizedAppEntry = true,
            ),
        )
        // Genuine unauthenticated third-party launch without MULTI_PROFILE still fails closed to system browser.
        assertEquals(
            RaftMobileWebPanelFailClosedAction.HandoffToSystemBrowser,
            resolveRaftMobileWebPanelFailClosedAction(
                storageResolution = RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser,
                effectiveLaunchUrl = "https://third-party.example/docs",
                closing = false,
                wasAuthorizedAppEntry = false,
            ),
        )
        assertEquals(
            RaftMobileWebPanelFailClosedAction.None,
            resolveRaftMobileWebPanelFailClosedAction(
                storageResolution = RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser,
                effectiveLaunchUrl = null,
                closing = false,
                wasAuthorizedAppEntry = false,
            ),
        )
    }

    @Test
    fun loadingPlaceholderShowsOnlyBeforeInitialVisualCommitAndNeverCoversWarmOrErrorStates() {
        assertTrue(
            shouldShowRaftMobileWebPanelLoadingPlaceholder(
                hasLaunchUrl = true,
                hasVisualCommit = false,
                hasLoadError = false,
                isSignInResolving = false,
                hasDeclinedSignIn = false,
                isLoading = true,
                progress = 0,
            ),
        )
        assertFalse(
            shouldShowRaftMobileWebPanelLoadingPlaceholder(
                hasLaunchUrl = true,
                hasVisualCommit = true,
                hasLoadError = false,
                isSignInResolving = false,
                hasDeclinedSignIn = false,
                isLoading = true,
                progress = 0,
            ),
        )
        assertFalse(
            shouldShowRaftMobileWebPanelLoadingPlaceholder(
                hasLaunchUrl = true,
                hasVisualCommit = false,
                hasLoadError = false,
                isSignInResolving = false,
                hasDeclinedSignIn = false,
                isLoading = true,
                progress = 25,
            ),
        )
        // Warm keep-alive reuse reports !isLoading immediately and must not flash a loading cover.
        assertFalse(
            shouldShowRaftMobileWebPanelLoadingPlaceholder(
                hasLaunchUrl = true,
                hasVisualCommit = false,
                hasLoadError = false,
                isSignInResolving = false,
                hasDeclinedSignIn = false,
                isLoading = false,
                progress = 0,
            ),
        )
        assertFalse(
            shouldShowRaftMobileWebPanelLoadingPlaceholder(
                hasLaunchUrl = true,
                hasVisualCommit = false,
                hasLoadError = true,
                isSignInResolving = false,
                hasDeclinedSignIn = false,
                isLoading = true,
                progress = 0,
            ),
        )
        assertFalse(
            shouldShowRaftMobileWebPanelLoadingPlaceholder(
                hasLaunchUrl = true,
                hasVisualCommit = false,
                hasLoadError = false,
                isSignInResolving = true,
                hasDeclinedSignIn = false,
                isLoading = true,
                progress = 0,
            ),
        )
        assertFalse(
            shouldShowRaftMobileWebPanelLoadingPlaceholder(
                hasLaunchUrl = true,
                hasVisualCommit = false,
                hasLoadError = false,
                isSignInResolving = false,
                hasDeclinedSignIn = true,
                isLoading = true,
                progress = 0,
            ),
        )
        assertFalse(
            shouldShowRaftMobileWebPanelLoadingPlaceholder(
                hasLaunchUrl = false,
                hasVisualCommit = false,
                hasLoadError = false,
                isSignInResolving = false,
                hasDeclinedSignIn = false,
                isLoading = true,
                progress = 0,
            ),
        )
    }

    @Test
    fun displayHostStripsWwwConvertsIdnToPunycodeAndPreservesNonDefaultPorts() {
        assertEquals("docs.example.com", raftMobileWebPanelDisplayHost("https://docs.example.com/push/ios-setup?k=v#top"))
        assertEquals("docs.example.com", raftMobileWebPanelDisplayHost("https://www.docs.example.com/push"))
        assertEquals("example.com", raftMobileWebPanelDisplayHost("https://WWW.Example.COM:443/path"))
        assertEquals("xn--bcher-kva.example", raftMobileWebPanelDisplayHost("https://bücher.example/shop"))
        assertEquals("xn--bcher-kva.example", raftMobileWebPanelDisplayHost("https://www.bücher.example/shop"))
        assertEquals("example.com:8443", raftMobileWebPanelDisplayHost("https://www.example.com:8443/x"))
        assertEquals("localhost:3000", raftMobileWebPanelDisplayHost("http://localhost:3000/app"))
        assertEquals("www.com", raftMobileWebPanelDisplayHost("https://www.com/"))
        assertEquals("", raftMobileWebPanelDisplayHost("   "))
        assertEquals(
            "accounts.google.com.sign-in.account-recovery.evil-example.net",
            raftMobileWebPanelDisplayHost("https://accounts.google.com.sign-in.account-recovery.evil-example.net/auth"),
        )
    }

    @Test
    fun siteDomainTextSpecHeadTruncatesLtrAndNeverCutsDomainEnd() {
        val domainSpec = raftSiteDomainTextSpec(isDomain = true)
        assertEquals(TextOverflow.StartEllipsis, domainSpec.overflow)
        assertEquals(TextDirection.Ltr, domainSpec.textDirection)
        assertEquals(1, domainSpec.maxLines)
        assertFalse(domainSpec.softWrap)

        val titleSpec = raftSiteDomainTextSpec(isDomain = false)
        assertEquals(TextOverflow.Ellipsis, titleSpec.overflow)
        assertEquals(TextDirection.Unspecified, titleSpec.textDirection)
        assertEquals(1, titleSpec.maxLines)
        assertFalse(titleSpec.softWrap)
    }

    @Test
    fun overflowMenuOffersOpenInBrowserForAllModesAndFloatAndCloseForInstalledApps() {
        assertEquals(
            listOf(
                RaftMobileWebPanelMenuAction.Reload,
                RaftMobileWebPanelMenuAction.MinimizeToFloat,
                RaftMobileWebPanelMenuAction.ForwardToRaft,
                RaftMobileWebPanelMenuAction.Share,
                RaftMobileWebPanelMenuAction.CopyLink,
                RaftMobileWebPanelMenuAction.OpenInBrowser,
                RaftMobileWebPanelMenuAction.ClosePage,
            ),
            raftMobileWebPanelMenuActions(isRaftApp = true, isOutboundChild = false, canMinimizeToFloat = true),
        )
        assertEquals(
            listOf(
                RaftMobileWebPanelMenuAction.Reload,
                RaftMobileWebPanelMenuAction.ForwardToRaft,
                RaftMobileWebPanelMenuAction.Share,
                RaftMobileWebPanelMenuAction.CopyLink,
                RaftMobileWebPanelMenuAction.OpenInBrowser,
                RaftMobileWebPanelMenuAction.ClosePage,
            ),
            raftMobileWebPanelMenuActions(isRaftApp = true, isOutboundChild = false, canMinimizeToFloat = false),
        )
        assertEquals(
            listOf(
                RaftMobileWebPanelMenuAction.Reload,
                RaftMobileWebPanelMenuAction.ForwardToRaft,
                RaftMobileWebPanelMenuAction.Share,
                RaftMobileWebPanelMenuAction.CopyLink,
                RaftMobileWebPanelMenuAction.OpenInBrowser,
            ),
            raftMobileWebPanelMenuActions(isRaftApp = false, isOutboundChild = true, canMinimizeToFloat = true),
        )
    }

    @Test
    fun crossOriginOutboundFromInstalledAppRoutesThirdPartyDomainsAndSkipsSameOriginAndRaftHosts() {
        val appScope = "https://stamp.build"
        val committedAppUrl = "https://stamp.build/s/raft/pull/42"
        val webBase = "https://app.raft.build"
        val apiBase = "https://api.raft.build"

        // Same-origin stays inside the authenticated Raft App WebView.
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://stamp.build/s/raft/pull/42",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                committedDocumentUrl = committedAppUrl,
            ),
        )
        // Cross-origin external link click from committed App origin -> push unauthenticated domain-only child page.
        assertTrue(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/botiverse/mobile/pull/42",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = committedAppUrl,
            ),
        )
        // Raft own hosts and App Store restricted URLs stay handled by their dedicated guards.
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://app.raft.build/s/hq/c/general",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                committedDocumentUrl = committedAppUrl,
            ),
        )
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://api.raft.build/api/oauth/authorize?client_id=x",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                committedDocumentUrl = committedAppUrl,
            ),
        )
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://checkout.stripe.com/c/pay/123",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = true,
                appHosts = emptySet(),
                committedDocumentUrl = committedAppUrl,
            ),
        )
        // Non-http(s) custom schemes stay handled by external app confirmation dialog.
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "mailto:ops@example.com",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                committedDocumentUrl = committedAppUrl,
            ),
        )
    }

    @Test
    fun crossOriginOutboundPreservesServerRedirectsOffOriginOAuthRoundTripsAndPreCommitLoads() {
        val appScope = "https://stamp.build"
        val webBase = "https://app.raft.build"
        val apiBase = "https://api.raft.build"

        // 1. Cross-origin link click from committed stamp.build -> routes out.
        assertTrue(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/botiverse/mobile/pull/1",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = "https://stamp.build/pr/1",
            ),
        )

        // 2. Server 302 redirect from stamp.build to GitHub OAuth -> stays in same WebView (preserves CSRF state cookie).
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/login/oauth/authorize?client_id=stamp&state=csrf123",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = true,
                committedDocumentUrl = "https://stamp.build/settings",
            ),
        )

        // 3. Nothing committed yet (initial launch redirect) -> never routes out.
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/login/oauth/authorize?client_id=stamp",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = "",
            ),
        )

        // 4. Once off scopeOrigin on github.com, all follow-up OAuth/2FA/SSO navigations & callback stay in same WebView.
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/session",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = "https://github.com/login/oauth/authorize?client_id=stamp",
            ),
        )
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://sso.corp.example/saml",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = "https://github.com/session",
            ),
        )
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://stamp.build/auth/github/callback?code=abc&state=csrf123",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = "https://github.com/login/oauth/authorize?client_id=stamp",
            ),
        )

        // 5. Raft own hosts & App Store billing remain routed even on redirects.
        assertTrue(
            raftInAppBrowserShouldRouteNavigation(
                candidateUrl = "https://api.raft.build/api/oauth/authorize?client_id=stamp",
                activeWebBaseUrl = webBase,
                appStoreBillingRules = false,
                useEphemeralStore = false,
                activeApiBaseUrl = apiBase,
                appHosts = emptySet(),
            ),
        )
        assertTrue(
            raftInAppBrowserShouldRouteNavigation(
                candidateUrl = "https://app.raft.build/s/hq/c/general",
                activeWebBaseUrl = webBase,
                appStoreBillingRules = false,
                useEphemeralStore = false,
                activeApiBaseUrl = apiBase,
                appHosts = emptySet(),
            ),
        )

        // 6. Anti-spoofing origin check hides App branding off scopeOrigin and restores it back on scopeOrigin.
        assertTrue(raftAppIsOnScopeOrigin("https://stamp.build/settings", appScope))
        assertTrue(raftAppIsOnScopeOrigin("", appScope))
        assertFalse(raftAppIsOnScopeOrigin("https://github.com/login/oauth/authorize", appScope))
        assertFalse(raftAppIsOnScopeOrigin("about:blank", appScope))
        assertTrue(raftAppIsOnScopeOrigin("https://stamp.build/auth/github/callback?code=abc", appScope))
    }

    @Test
    fun nonRedirectOAuthAndGitHubAppInstallStayInAppTabWhileOrdinaryCrossOriginLinksRouteOut() {
        val appScope = "https://stamp.build"
        val webBase = "https://app.raft.build"
        val apiBase = "https://api.raft.build"
        val committedAppUrl = "https://stamp.build/requests/req_123"

        // GitHub App installation (e.g. window.location.assign(install_url) with isRedirect=false) stays in-tab.
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/apps/stamp-build/installations/new?state=opaque-123",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = committedAppUrl,
            ),
        )
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/apps/stamp-build/installations/select_target",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = committedAppUrl,
            ),
        )
        // Direct link or JS navigation to GitHub OAuth authorize stays in-tab.
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/login/oauth/authorize?client_id=Iv1.abc&scope=repo",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = committedAppUrl,
            ),
        )
        // Generic third-party OAuth/OIDC with redirect_uri returning to scopeOrigin stays in-tab.
        assertFalse(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=abc&response_type=code&redirect_uri=https%3A%2F%2Fstamp.build%2Fauth%2Fgoogle%2Fcallback",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = committedAppUrl,
            ),
        )
        // OAuth URL whose redirect_uri targets a DIFFERENT origin still routes out.
        assertTrue(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=abc&redirect_uri=https%3A%2F%2Fother.example%2Fcallback",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = committedAppUrl,
            ),
        )
        // Plain HTTP OAuth target from an HTTPS App still routes out.
        assertTrue(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "http://idp.example/authorize?client_id=abc&redirect_uri=https%3A%2F%2Fstamp.build%2Fcb",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = committedAppUrl,
            ),
        )
        // Ordinary GitHub repo/PR/app-info links still route out to isolated outbound child pages.
        assertTrue(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/botiverse/mobile/pull/5963",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = committedAppUrl,
            ),
        )
        assertTrue(
            raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = "https://github.com/apps/stamp-build",
                scopeOrigin = appScope,
                activeWebBaseUrl = webBase,
                activeApiBaseUrl = apiBase,
                appStoreBillingRules = false,
                appHosts = emptySet(),
                isRedirect = false,
                committedDocumentUrl = committedAppUrl,
            ),
        )
    }

    @Test
    fun declinedSignInCloseAndUncommittedReloadRecoverWithoutBlankWhiteScreen() {
        assertEquals(
            RaftMobileWebPanelDeclinedCloseAction.ClosePage,
            resolveRaftMobileWebPanelDeclinedCloseAction(hasCommittedDocument = false),
        )
        assertEquals(
            RaftMobileWebPanelDeclinedCloseAction.DismissNotice,
            resolveRaftMobileWebPanelDeclinedCloseAction(hasCommittedDocument = true),
        )

        val launch = "https://stamp.build/pulls/42"
        // Before any commit (or when live URL is blank / about:blank), Reload loads effectiveLaunchUrl directly.
        assertEquals(
            launch,
            resolveRaftMobileWebPanelReloadTargetUrl(
                currentUrl = launch,
                effectiveLaunchUrl = launch,
                hasCommittedDocument = false,
            ),
        )
        assertEquals(
            launch,
            resolveRaftMobileWebPanelReloadTargetUrl(
                currentUrl = "",
                effectiveLaunchUrl = launch,
                hasCommittedDocument = true,
            ),
        )
        assertEquals(
            launch,
            resolveRaftMobileWebPanelReloadTargetUrl(
                currentUrl = "about:blank",
                effectiveLaunchUrl = launch,
                hasCommittedDocument = true,
            ),
        )
        // Once a real document committed on a live URL, normal WebView reload applies.
        assertNull(
            resolveRaftMobileWebPanelReloadTargetUrl(
                currentUrl = "https://stamp.build/pulls/42",
                effectiveLaunchUrl = launch,
                hasCommittedDocument = true,
            ),
        )
        assertNull(
            resolveRaftMobileWebPanelReloadTargetUrl(
                currentUrl = "about:blank",
                effectiveLaunchUrl = null,
                hasCommittedDocument = false,
            ),
        )
    }

    @Test
    fun backActionResolvesWebHistoryOutboundPopAndAppCloseAtRoot() {
        assertEquals(
            RaftAppBrowserBackAction.WebGoBack,
            resolveRaftAppBrowserBackAction(canGoBack = true, isOutboundChild = false),
        )
        assertEquals(
            RaftAppBrowserBackAction.WebGoBack,
            resolveRaftAppBrowserBackAction(canGoBack = true, isOutboundChild = true),
        )
        assertEquals(
            RaftAppBrowserBackAction.PopOutboundChild,
            resolveRaftAppBrowserBackAction(canGoBack = false, isOutboundChild = true),
        )
        assertEquals(
            RaftAppBrowserBackAction.CloseAppPage,
            resolveRaftAppBrowserBackAction(canGoBack = false, isOutboundChild = false),
        )
    }

    @Test
    fun floatingPillVerticalBoundsClampWithinSafeAreaAndRoundTripFraction() {
        val viewport = 844f
        val pillHeight = 48f
        val topBound = 112f
        val bottomBound = 140f

        val atTop = resolveRaftFloatingPillOffsetYPx(viewport, pillHeight, topBound, bottomBound, -0.5f)
        val atBottom = resolveRaftFloatingPillOffsetYPx(viewport, pillHeight, topBound, bottomBound, 1.5f)
        val atMid = resolveRaftFloatingPillOffsetYPx(viewport, pillHeight, topBound, bottomBound, 0.5f)

        assertEquals(topBound, atTop)
        assertEquals(viewport - bottomBound - pillHeight, atBottom)
        assertEquals(topBound + (viewport - bottomBound - pillHeight - topBound) * 0.5f, atMid)
        assertEquals(0f, clampRaftFloatingPillYFraction(0f, viewport, pillHeight, topBound, bottomBound))
        assertEquals(1f, clampRaftFloatingPillYFraction(999f, viewport, pillHeight, topBound, bottomBound))
        assertEquals(0.5f, clampRaftFloatingPillYFraction(atMid, viewport, pillHeight, topBound, bottomBound))
    }

    @Test
    fun webPageForwardFormattingAndEligibility() {
        assertTrue(canForwardWebPageUrl("https://docs.example.com/guide"))
        assertTrue(canForwardWebPageUrl("http://localhost:3000/demo"))
        assertFalse(canForwardWebPageUrl("about:blank"))
        assertFalse(canForwardWebPageUrl("javascript:alert(1)"))
        assertFalse(canForwardWebPageUrl("   "))

        assertEquals(
            "Push Setup Guide\nhttps://docs.example.com/push",
            formatWebPageForwardMessageBody(
                url = "https://docs.example.com/push",
                pageTitle = "  Push   Setup\nGuide  ",
            ),
        )
        assertEquals(
            "https://docs.example.com/push",
            formatWebPageForwardMessageBody(
                url = "https://docs.example.com/push",
                pageTitle = "docs.example.com",
            ),
        )
        assertEquals(
            "https://docs.example.com/push",
            formatWebPageForwardMessageBody(
                url = "https://docs.example.com/push",
                pageTitle = "https://docs.example.com/push",
            ),
        )
        assertEquals(
            "https://docs.example.com/push",
            formatWebPageForwardMessageBody(
                url = "https://docs.example.com/push",
                pageTitle = null,
            ),
        )
    }

    @Test
    fun jsDialogStateCancelsImmediatelyWhenHiddenAndOnVisibilityLoss() {
        val state = RaftWebJsDialogState(initialVisible = false)
        val hiddenResults = mutableListOf<WebJsDialogResult>()
        val hiddenReq =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Confirm,
                originUrl = "https://docs.example.com",
                message = "Hidden confirm?",
                onResult = { hiddenResults += it },
            )
        state.show(hiddenReq)
        assertNull(state.activeRequest)
        assertTrue(hiddenReq.isSettled)
        assertEquals(listOf<WebJsDialogResult>(WebJsDialogResult.Confirm(false)), hiddenResults)

        state.onVisibilityChanged(true)
        val promptResults = mutableListOf<WebJsDialogResult>()
        val promptReq =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Prompt,
                originUrl = "https://docs.example.com",
                message = "Name?",
                defaultPromptText = "hello",
                onResult = { promptResults += it },
            )
        state.show(promptReq)
        assertEquals(promptReq, state.activeRequest)

        state.onVisibilityChanged(false)
        assertNull(state.activeRequest)
        assertTrue(promptReq.isSettled)
        state.confirmActive("too late")
        assertEquals(listOf<WebJsDialogResult>(WebJsDialogResult.Prompt(null)), promptResults)

        state.onVisibilityChanged(true)
        val navCancelResults = mutableListOf<WebJsDialogResult>()
        val navCancelReq =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Confirm,
                originUrl = "https://docs.example.com",
                message = "Still open?",
                onResult = { navCancelResults += it },
            )
        state.show(navCancelReq)
        assertEquals(navCancelReq, state.activeRequest)
        assertTrue(navCancelReq.cancel())
        assertNull(state.activeRequest)
        assertEquals(listOf<WebJsDialogResult>(WebJsDialogResult.Confirm(false)), navCancelResults)
    }

    @Test
    fun multiPageCoordinatorEnforcesFivePageCapTwoLiveLruHibernationAndSessionClear() {
        val releasedTokens = mutableListOf<String>()
        val evictedKeepAliveKeys = mutableListOf<String>()
        val coordinator =
            RaftMobileWebPanelCoordinator(
                maxTabs = 5,
                livePoolSize = 2,
                releaseEntryToken = { token, _ -> releasedTokens += token },
                evictKeepAlive = { key -> evictedKeepAliveKeys += key },
            )

        fun openApp(i: Int): RaftAppBrowserOpenResult =
            coordinator.openOrActivateApp(
                launchUrl = "https://app$i.example/home",
                appEntryToken = "tok-$i",
                request = RaftAppEntryRequest(scopeOrigin = "https://app$i.example", appName = "App $i", preferredServerId = "srv-1"),
                accountId = "acct-1",
                serverId = "srv-1",
                environmentKey = "prod",
            )

        // Open tab 1 & navigate in-page: loadedBaseUrl stays launchUrl while live so warm resume won't reload.
        val r1 = openApp(1)
        assertEquals(1, coordinator.state.value.tabs.size)
        assertEquals(r1.tab.tabId, coordinator.state.value.activeTabId)
        assertFalse(coordinator.state.value.showFloatingPill)
        coordinator.updateTabNavigation(r1.tab.tabId, "https://app1.example/pulls/99", "PR #99")
        assertEquals("https://app1.example/home", coordinator.tabById(r1.tab.tabId)?.loadedBaseUrl)
        assertEquals("https://app1.example/pulls/99", coordinator.tabById(r1.tab.tabId)?.currentUrl)
        assertEquals("PR #99", coordinator.tabById(r1.tab.tabId)?.switcherPrimaryLabel)

        // Suspend tab 1 -> right-edge floating pill shows, tab 1 stays warm & keep-alive eligible.
        assertTrue(coordinator.suspendTab(r1.tab.tabId))
        assertTrue(coordinator.state.value.showFloatingPill)
        assertTrue(coordinator.isKeepAliveEligible(r1.tab.keepAliveKey))

        // Open tab 2 -> both tab 1 and tab 2 stay live (2-live LRU pool).
        val r2 = openApp(2)
        assertEquals(2, coordinator.state.value.liveCount)
        assertTrue(coordinator.tabById(r1.tab.tabId)?.isLive == true)
        assertTrue(coordinator.tabById(r2.tab.tabId)?.isLive == true)
        assertTrue(evictedKeepAliveKeys.isEmpty())

        // Open tab 3 -> tab 1 (oldest LRU) hibernates, snapshots loadedBaseUrl=currentUrl, and evicts keep-alive WebView.
        val r3 = openApp(3)
        assertEquals(2, coordinator.state.value.liveCount)
        assertEquals(listOf(r1.tab.tabId), r3.hibernatedTabs.map { it.tabId })
        val hibernated1 = coordinator.tabById(r1.tab.tabId)
        assertNotNull(hibernated1)
        assertFalse(hibernated1.isLive)
        assertFalse(coordinator.isKeepAliveEligible(hibernated1.keepAliveKey))
        assertEquals("https://app1.example/pulls/99", hibernated1.loadedBaseUrl)
        assertEquals(listOf(r1.tab.keepAliveKey), evictedKeepAliveKeys)

        // Same-URL open deduplicates tab 1, reactivates it, releases redundant token, and hibernates tab 2.
        val reused1 =
            coordinator.openOrActivateApp(
                launchUrl = "https://app1.example/pulls/99",
                appEntryToken = "tok-dup",
                request = RaftAppEntryRequest(scopeOrigin = "https://app1.example", appName = "App 1", preferredServerId = "srv-1"),
                accountId = "acct-1",
                serverId = "srv-1",
                environmentKey = "prod",
            )
        assertTrue(reused1.reusedExisting)
        assertEquals(r1.tab.tabId, reused1.tab.tabId)
        assertTrue("tok-dup" in releasedTokens)
        assertTrue(coordinator.tabById(r1.tab.tabId)?.isLive == true)
        assertFalse(coordinator.tabById(r2.tab.tabId)?.isLive == true)

        // Open tabs 4, 5 -> 5 open tabs max; opening tab 6 evicts oldest suspended tab (tab 2) and never active tab.
        openApp(4)
        val r5 = openApp(5)
        assertEquals(5, coordinator.state.value.tabs.size)
        val r6 = openApp(6)
        assertEquals(5, coordinator.state.value.tabs.size)
        assertEquals(r2.tab.tabId, r6.evictedTab?.tabId)
        assertNull(coordinator.tabById(r2.tab.tabId))
        assertTrue("tok-2" in releasedTokens)
        assertTrue(r2.tab.keepAliveKey in evictedKeepAliveKeys)
        assertEquals(r6.tab.tabId, coordinator.state.value.activeTabId)

        // Memory pressure hibernates background live tab (r5) while protecting the active foreground tab (r6).
        val pressureHibernated = coordinator.hibernateBackgroundTabs()
        assertEquals(listOf(r5.tab.tabId), pressureHibernated.map { it.tabId })
        assertTrue(coordinator.tabById(r6.tab.tabId)?.isLive == true)
        assertFalse(coordinator.tabById(r5.tab.tabId)?.isLive == true)

        // Closing one tab removes only that tab; server switch clears all remaining tabs.
        assertTrue(coordinator.closeTab(r5.tab.tabId))
        assertNull(coordinator.tabById(r5.tab.tabId))
        assertEquals(4, coordinator.state.value.tabs.size)

        coordinator.onSessionContextChanged(accountId = "acct-1", serverId = "srv-2", environmentKey = "prod")
        assertTrue(coordinator.state.value.tabs.isEmpty())
        assertNull(coordinator.state.value.activeTabId)
        assertFalse(coordinator.state.value.showFloatingPill)
    }

    @Test
    fun coordinatorClearsTabsOnLogoutAccountSwitchEnvironmentSwitchAndDroppedRailApp() {
        val coordinator =
            RaftMobileWebPanelCoordinator(
                releaseEntryToken = { _, _ -> },
                evictKeepAlive = {},
            )

        val stamp =
            coordinator.openOrActivateApp(
                launchUrl = "https://stamp.build/pulls/1",
                appEntryToken = "tok-stamp",
                request = RaftAppEntryRequest(scopeOrigin = "https://stamp.build", appName = "Stamp", preferredServerId = "srv-1"),
                accountId = "acct-1",
                serverId = "srv-1",
                environmentKey = "prod",
            ).tab
        coordinator.openOrActivateApp(
            launchUrl = "https://deck.example/board",
            appEntryToken = "tok-deck",
            request = RaftAppEntryRequest(scopeOrigin = "https://deck.example", appName = "Deck", preferredServerId = "srv-1"),
            accountId = "acct-1",
            serverId = "srv-1",
            environmentKey = "prod",
        )
        assertEquals(2, coordinator.state.value.tabs.size)

        // Dropped rail app removes only matching tab.
        coordinator.closeByDroppedRailApp(origin = "https://stamp.build", appName = "Stamp")
        assertNull(coordinator.tabById(stamp.tabId))
        assertEquals(1, coordinator.state.value.tabs.size)

        // Logout clears remaining tabs.
        coordinator.onSessionContextChanged(accountId = null, serverId = null, environmentKey = "prod")
        assertTrue(coordinator.state.value.tabs.isEmpty())

        // Account switch clears tabs.
        coordinator.openOrActivateApp(
            launchUrl = "https://deck.example/board",
            appEntryToken = "tok-deck-2",
            request = RaftAppEntryRequest(scopeOrigin = "https://deck.example", appName = "Deck", preferredServerId = "srv-1"),
            accountId = "acct-1",
            serverId = "srv-1",
            environmentKey = "prod",
        )
        coordinator.onSessionContextChanged(accountId = "acct-2", serverId = "srv-1", environmentKey = "prod")
        assertTrue(coordinator.state.value.tabs.isEmpty())
    }

    @Test
    fun onTabKeepAliveDroppedHibernatesSuspendedTabAndSnapshotsResumeUrl() {
        val evictedKeepAliveKeys = mutableListOf<String>()
        val coordinator =
            RaftMobileWebPanelCoordinator(
                releaseEntryToken = { _, _ -> },
                evictKeepAlive = { evictedKeepAliveKeys += it },
            )
        val tab =
            coordinator.openOrActivateApp(
                launchUrl = "https://stamp.build/home",
                appEntryToken = "tok-1",
                request = RaftAppEntryRequest(scopeOrigin = "https://stamp.build", appName = "Stamp", preferredServerId = "srv-1"),
                accountId = "acct-1",
                serverId = "srv-1",
                environmentKey = "prod",
            ).tab
        coordinator.updateTabNavigation(tab.tabId, "https://stamp.build/pulls/42", "PR #42")
        // Foreground tab ignores keep-alive drop signal.
        coordinator.onTabKeepAliveDropped(tab.keepAliveKey)
        assertTrue(coordinator.tabById(tab.tabId)?.isLive == true)
        assertEquals("https://stamp.build/home", coordinator.tabById(tab.tabId)?.loadedBaseUrl)

        // Once suspended, platform drop (theme switch / parked renderer gone) marks Hibernated & snapshots resumeUrl.
        assertTrue(coordinator.suspendTab(tab.tabId))
        coordinator.onTabKeepAliveDropped(tab.keepAliveKey)
        val afterDrop = coordinator.tabById(tab.tabId)
        assertNotNull(afterDrop)
        assertFalse(afterDrop.isLive)
        assertEquals("https://stamp.build/pulls/42", afterDrop.loadedBaseUrl)
        assertTrue(evictedKeepAliveKeys.isEmpty())
    }
}
