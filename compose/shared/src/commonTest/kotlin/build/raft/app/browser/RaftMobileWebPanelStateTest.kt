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
    fun storagePartitionSelectsDefaultForRaftAppIsolatedForThirdPartyAndFailsClosedWithoutMultiProfile() {
        // Layer 2 Raft App entry always uses Default (even on pre-119 Android without MULTI_PROFILE).
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
        // Layer 3 third-party ordinary link uses IsolatedThirdParty when supported.
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
        // Layer 3 third-party link fails closed to system browser when MULTI_PROFILE is unavailable (never Default).
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
    fun overflowMenuOmitsOpenInBrowserForRaftAppsOnly() {
        assertEquals(
            listOf(
                RaftMobileWebPanelMenuAction.Reload,
                RaftMobileWebPanelMenuAction.ForwardToRaft,
                RaftMobileWebPanelMenuAction.Share,
                RaftMobileWebPanelMenuAction.CopyLink,
                RaftMobileWebPanelMenuAction.OpenInBrowser,
            ),
            raftMobileWebPanelMenuActions(isRaftApp = false),
        )
        assertEquals(
            listOf(
                RaftMobileWebPanelMenuAction.Reload,
                RaftMobileWebPanelMenuAction.ForwardToRaft,
                RaftMobileWebPanelMenuAction.Share,
                RaftMobileWebPanelMenuAction.CopyLink,
            ),
            raftMobileWebPanelMenuActions(isRaftApp = true),
        )
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
        // Title equal to host or URL adds no extra line.
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

        // Become visible, present a prompt, then collapse panel -> auto-cancels with null once.
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

        // External platform cancellation (e.g. page navigation or renderer death) immediately clears activeRequest.
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
    fun backActionGoesBackWithWebHistoryAndCollapsesKeepingAliveAtFirstEntry() {
        assertEquals(
            RaftMobileWebPanelBackAction.WebGoBack,
            resolveRaftMobileWebPanelBackAction(RaftMobileWebPanelDetent.Default88, canGoBack = true),
        )
        assertEquals(
            RaftMobileWebPanelBackAction.WebGoBack,
            resolveRaftMobileWebPanelBackAction(RaftMobileWebPanelDetent.Fullscreen, canGoBack = true),
        )
        assertEquals(
            RaftMobileWebPanelBackAction.CollapseKeepingAlive,
            resolveRaftMobileWebPanelBackAction(RaftMobileWebPanelDetent.Default88, canGoBack = false),
        )
        assertEquals(
            RaftMobileWebPanelBackAction.CollapseKeepingAlive,
            resolveRaftMobileWebPanelBackAction(RaftMobileWebPanelDetent.Fullscreen, canGoBack = false),
        )
        assertEquals(
            RaftMobileWebPanelBackAction.Ignore,
            resolveRaftMobileWebPanelBackAction(RaftMobileWebPanelDetent.Collapsed, canGoBack = true),
        )
    }

    @Test
    fun backdropAlphaRemainsOpaqueWhenExpandedAndClampsSlideProgress() {
        assertEquals(0f, resolveRaftMobileWebPanelBackdropAlpha(-0.25f))
        assertEquals(0f, resolveRaftMobileWebPanelBackdropAlpha(0f))
        assertEquals(0.5f, resolveRaftMobileWebPanelBackdropAlpha(0.5f))
        assertEquals(1f, resolveRaftMobileWebPanelBackdropAlpha(1f))
        assertEquals(1f, resolveRaftMobileWebPanelBackdropAlpha(1.25f))
    }

    @Test
    fun dragTransitionsBetweenDefault88FullscreenAndCollapsed() {
        assertEquals(
            RaftMobileWebPanelDetent.Fullscreen,
            resolveRaftMobileWebPanelDragTarget(
                current = RaftMobileWebPanelDetent.Default88,
                dragDeltaPx = -120f,
                velocityYPxPerSec = 0f,
                viewportHeightPx = 844f,
            ),
        )
        assertEquals(
            RaftMobileWebPanelDetent.Collapsed,
            resolveRaftMobileWebPanelDragTarget(
                current = RaftMobileWebPanelDetent.Default88,
                dragDeltaPx = 120f,
                velocityYPxPerSec = 0f,
                viewportHeightPx = 844f,
            ),
        )
        assertEquals(
            RaftMobileWebPanelDetent.Default88,
            resolveRaftMobileWebPanelDragTarget(
                current = RaftMobileWebPanelDetent.Default88,
                dragDeltaPx = 18f,
                velocityYPxPerSec = 0f,
                viewportHeightPx = 844f,
            ),
        )
        // Dragging down from Fullscreen steps back to Default88 first.
        assertEquals(
            RaftMobileWebPanelDetent.Default88,
            resolveRaftMobileWebPanelDragTarget(
                current = RaftMobileWebPanelDetent.Fullscreen,
                dragDeltaPx = 160f,
                velocityYPxPerSec = 900f,
                viewportHeightPx = 844f,
            ),
        )
    }

    @Test
    fun coordinatorEnforcesSingleKeptAlivePageLifecycleTokenReleaseAndAccountSwitch() {
        val releasedTokens = mutableListOf<Pair<String, String>>()
        val coordinator =
            RaftMobileWebPanelCoordinator(
                releaseEntryToken = { token, owner -> releasedTokens += token to owner },
            )
        val msgSource =
            RaftMobileWebPanelSource.Message(
                messageId = "msg-1",
                authorName = "Xiaofan",
                authorAvatarKind = RaftAvatarKind.Agent,
                previewText = "Check iOS push setup",
                timestampLabel = "10:04",
            )

        // Open Layer 3 link -> Default88 detent, dynamic host label.
        val first =
            coordinator.open(
                ownerHostId = "host-1",
                launchUrl = "https://www.docs.example.com/push/ios-setup",
                isRaftApp = false,
                source = msgSource,
                accountId = "acct-1",
                environmentKey = "prod",
            )
        assertEquals(RaftMobileWebPanelDetent.Default88, first.detent)
        assertEquals("docs.example.com", first.keepOpenLabel)

        // Dynamic navigation updates currentUrl and keepOpenLabel.
        coordinator.updateCurrentUrl(first.sessionId, "https://accounts.example.com/login")
        assertEquals("accounts.example.com", coordinator.session.value?.keepOpenLabel)

        // Collapse keeps page alive; reopen restores exact session.
        assertTrue(coordinator.collapse(first.sessionId))
        assertTrue(coordinator.session.value?.isCollapsedKeptAlive == true)
        assertTrue(coordinator.expand(first.sessionId))
        assertEquals(first.sessionId, coordinator.session.value?.sessionId)
        assertEquals(RaftMobileWebPanelDetent.Default88, coordinator.session.value?.detent)

        // Opening a Layer 2 Raft App replaces the previous kept page (strictly 1 kept page).
        val appSession =
            coordinator.open(
                ownerHostId = "host-1",
                launchUrl = "https://tracker.example/view/1",
                appEntryToken = "tok-app-1",
                appName = "Tracker",
                isRaftApp = true,
                source = msgSource,
                accountId = "acct-1",
                environmentKey = "prod",
            )
        assertEquals("Tracker", appSession.keepOpenLabel)
        assertTrue(releasedTokens.isEmpty())

        // Opening another link replaces the Raft App and immediately releases its entry token.
        val third =
            coordinator.open(
                ownerHostId = "host-2",
                launchUrl = "https://news.example.org/item",
                appEntryToken = "tok-app-2",
                appName = "News",
                isRaftApp = true,
                accountId = "acct-1",
                environmentKey = "prod",
            )
        assertEquals(listOf("tok-app-1" to "host-1"), releasedTokens)
        assertEquals(third.sessionId, coordinator.session.value?.sessionId)

        // Low-memory clearCollapsed ignores expanded sessions, clears collapsed ones.
        coordinator.clearCollapsed()
        assertNotNull(coordinator.session.value)
        coordinator.collapse(third.sessionId)
        coordinator.clearCollapsed()
        assertNull(coordinator.session.value)
        assertEquals(listOf("tok-app-1" to "host-1", "tok-app-2" to "host-2"), releasedTokens)

        // Account switch and sign-out destroy active/collapsed sessions and release tokens.
        val fourth =
            coordinator.open(
                ownerHostId = "host-2",
                launchUrl = "https://tracker.example/view/2",
                appEntryToken = "tok-app-3",
                appName = "Tracker",
                isRaftApp = true,
                accountId = "acct-1",
                environmentKey = "prod",
            )
        coordinator.onAccountOrEnvironmentChanged(accountId = "acct-1", environmentKey = "prod")
        assertNotNull(coordinator.session.value)
        coordinator.onAccountOrEnvironmentChanged(accountId = "acct-2", environmentKey = "prod")
        assertNull(coordinator.session.value)
        assertEquals("tok-app-3" to "host-2", releasedTokens.last())

        // Explicit close on a stale sessionId is a no-op; on current sessionId destroys and releases.
        assertFalse(coordinator.close(fourth.sessionId))
        val fifth =
            coordinator.open(
                ownerHostId = "host-2",
                launchUrl = "https://docs.example.com/a",
                isRaftApp = false,
                accountId = "acct-2",
                environmentKey = "prod",
            )
        assertTrue(coordinator.close(fifth.sessionId))
        assertNull(coordinator.session.value)
    }
}
